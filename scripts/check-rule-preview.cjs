'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),{spawnSync}=require('node:child_process');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const rootDir=path.resolve(__dirname,'..');
const output=process.env.LEDGER_SCREENSHOT_DIR||path.resolve(rootDir,'output/t09-rule-preview');
const withBaselineComparison=process.argv.includes('--with-baseline-comparison')||process.env.LEDGER_COMPARE_BASELINE==='1';

async function installRulePreviewFixture(page){
 await page.addInitScript(()=>{
  const now=new Date('2026-11-08T04:00:00Z').getTime();
  const iconOptions=[
   {key:'utensils',label:'餐饮'},{key:'bus',label:'交通'},{key:'bag-shopping',label:'购物'},{key:'gamepad',label:'娱乐'},
   {key:'house',label:'居住'},{key:'plus',label:'医疗'},{key:'graduation-cap',label:'教育'},{key:'shirt',label:'服饰'},
   {key:'basket-shopping',label:'日用'},{key:'phone',label:'通讯'},{key:'gift',label:'礼金'},{key:'ellipsis',label:'其他'},
   {key:'wallet',label:'工资'},{key:'award',label:'奖金'},{key:'rotate-left',label:'退款'},{key:'tag',label:'标签'},
   {key:'coffee',label:'饮品'},{key:'coins',label:'理财'}
  ];
  const dir={
   expense:[
    {id:'cat:expense:dining',name:'餐饮',direction:'EXPENSE',icon:'utensils',order:0,isFallback:false},
    {id:'cat:expense:transit',name:'交通',direction:'EXPENSE',icon:'bus',order:1,isFallback:false},
    {id:'cat:expense:shopping',name:'购物',direction:'EXPENSE',icon:'bag-shopping',order:2,isFallback:false},
    {id:'cat:expense:other',name:'其他',direction:'EXPENSE',icon:'ellipsis',order:3,isFallback:true}
   ],
   income:[
    {id:'cat:income:salary',name:'工资',direction:'INCOME',icon:'wallet',order:0,isFallback:false},
    {id:'cat:income:bonus',name:'奖金',direction:'INCOME',icon:'award',order:1,isFallback:false},
    {id:'cat:income:other',name:'其他',direction:'INCOME',icon:'ellipsis',order:2,isFallback:true}
   ]
  };
  let ruleVersionSeq=1,nextRuleId=2,revision=1;
  const customRules=[
   {
    id:'rule:custom:1',name:'所有星巴克咖啡',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,
    matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'星巴克'}]
   }
  ];
  const builtinRules=[
   {
    id:'builtin:expense:transit',name:'内置交通',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:transit',order:0,
    matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'地铁'}]
   },
   {
    id:'builtin:income:salary',name:'内置工资',enabled:true,direction:'INCOME',categoryId:'cat:income:salary',order:0,
    matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'发薪'}]
   }
  ];

  const rows=[
   {
    id:101,uid:'rec-101',merchant:'星巴克咖啡（静安寺店）',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',
    amountCents:3600,direction:'EXPENSE',occurredAt:now-3600_000,state:'POSTED',source:'OCR',note:'美式咖啡',orderContent:['美式咖啡'],
    sourceObj:{captureSource:'OCR',platform:'wechat',sourcePackage:'com.tencent.mm'},
    categoryDecision:{categoryId:'cat:expense:dining',source:'CUSTOM_RULE',manual:false,ruleId:'rule:custom:1',explanation:'命中自定义规则「所有星巴克咖啡」'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-3600,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:102,uid:'rec-102',merchant:'上海地铁通行',categoryId:'cat:expense:transit',category:'交通',categoryIcon:'bus',
    amountCents:400,direction:'EXPENSE',occurredAt:now-7200_000,state:'POSTED',source:'LIVE',note:'通勤',orderContent:['地铁出行单程票'],
    sourceObj:{captureSource:'LIVE',platform:'alipay',sourcePackage:'com.eg.android.AlipayGphone'},
    categoryDecision:{categoryId:'cat:expense:transit',source:'BUILTIN_RULE',manual:false,ruleId:'builtin:expense:transit',explanation:'命中内置规则「内置交通」'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-400,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:103,uid:'rec-103',merchant:'全家便利店（南京西路店）',categoryId:'cat:expense:shopping',category:'购物',categoryIcon:'bag-shopping',
    amountCents:1850,direction:'EXPENSE',occurredAt:now-10800_000,state:'POSTED',source:'IMAGE',note:'日用补给',orderContent:['乌龙茶500ml','纯净水'],
    sourceObj:{captureSource:'IMAGE',platform:'alipay',sourcePackage:'com.eg.android.AlipayGphone'},
    categoryDecision:{categoryId:'cat:expense:shopping',source:'FALLBACK_OTHER',manual:false,ruleId:null,explanation:'未命中规则，归入其他'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-1850,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:104,uid:'rec-104',merchant:'未知独立小店',categoryId:'cat:expense:other',category:'其他',categoryIcon:'ellipsis',
    amountCents:2500,direction:'EXPENSE',occurredAt:now-14400_000,state:'POSTED',source:'MANUAL',note:'杂货',orderContent:[],
    sourceObj:{captureSource:'MANUAL',platform:null,sourcePackage:null},
    categoryDecision:{categoryId:'cat:expense:other',source:'FALLBACK_OTHER',manual:false,ruleId:null,explanation:'未命中任何分类规则，归入默认「其他」分类'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-2500,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:105,uid:'rec-105',merchant:'某某科技发薪专户',categoryId:'cat:income:salary',category:'工资',categoryIcon:'wallet',
    amountCents:2500000,direction:'INCOME',occurredAt:now-86400_000,state:'POSTED',source:'OCR',note:'发薪',orderContent:['10月基本薪酬'],
    sourceObj:{captureSource:'OCR',platform:'wechat',sourcePackage:'com.tencent.mm'},
    categoryDecision:{categoryId:'cat:income:salary',source:'BUILTIN_RULE',manual:false,ruleId:'builtin:income:salary',explanation:'命中内置规则「内置工资」'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:2500000,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:106,uid:'rec-106',merchant:'资金调拨转账',categoryId:null,category:'转账',categoryIcon:'arrow-right-arrow-left',
    amountCents:50000,direction:'TRANSFER',occurredAt:now-90000_000,state:'POSTED',source:'MANUAL',note:'零钱存银行卡',orderContent:[],
    sourceObj:{captureSource:'MANUAL',platform:null,sourcePackage:null},
    categoryDecision:null,
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-50000,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   }
  ];

  const accounts=[
   {id:1,uid:'wechat',name:'微信零钱',kind:'wechat',type:'ASSET',amountCents:500000,archived:false,revision:1}
  ];

  window.calls={
   saveRule:[],deleteRule:[],reorderRules:[],setBuiltinEnabled:[],copyBuiltin:[],
   previewClassification:[],classificationRuleDraftFromBill:[],recordPage:[],saves:[]
  };
  window.simError={
   saveRule:null,deleteRule:null,reorderRules:null,setBuiltinEnabled:null,copyBuiltin:null,
   previewClassification:null,classificationRuleDraftFromBill:null
  };

  const normKeyword=s=>Array.from(String(s||'').normalize('NFKC').toLowerCase()).filter(c=>!/\s/u.test(c)).join('');
  function serializeDir(){
   const enrich=list=>list.slice().sort((a,b)=>a.order-b.order).map((c,i)=>{
    const refCount=rows.filter(r=>r.categoryId===c.id).length+customRules.filter(r=>r.categoryId===c.id).length;
    return {...c,order:i,referenceCount:refCount,canDelete:!c.isFallback&&refCount===0};
   });
   return {expense:enrich(dir.expense),income:enrich(dir.income)};
  }
  function serializeRules(){
   return {
    formatVersion:1,
    version:`rules:v1:${ruleVersionSeq}`,
    customRules:customRules.slice().sort((a,b)=>a.direction.localeCompare(b.direction)||a.order-b.order||a.id.localeCompare(b.id)).map(r=>({...r,conditions:r.conditions.map(c=>({...c}))})),
    builtinRules:builtinRules.map(r=>({...r,conditions:r.conditions.map(c=>({...c}))}))
   };
  }
  function buildSummary(){
   const snapDir=serializeDir(),catMap=new Map();
   for(const r of rows){
    if(r.state!=='POSTED'||r.direction==='TRANSFER')continue;
    const dKey=r.direction==='INCOME'?'income':'expense';
    const catObj=snapDir[dKey].find(c=>c.id===r.categoryId)||{id:r.categoryId||'',name:r.category||'其他',icon:r.categoryIcon||'ellipsis'};
    r.category=catObj.name;r.categoryIcon=catObj.icon;
    const k='category:'+catObj.name,cur=catMap.get(k)||{key:k,categoryId:catObj.id,icon:catObj.icon,name:catObj.name,direction:r.direction,amount:0,count:0};
    cur.amount+=r.amountCents;cur.count++;catMap.set(k,cur);
   }
   const expenseTotal=rows.filter(r=>r.state==='POSTED'&&r.direction==='EXPENSE').reduce((s,r)=>s+r.amountCents,0);
   return {
    totals:{expense:String(expenseTotal),income:'2500000',expenseCount:4,incomeCount:1,totalCount:5},
    previous:{expense:'5000',income:'0'},
    days:[{day:8,direction:'EXPENSE',amount:String(expenseTotal)}],
    categories:[...catMap.values()].map(c=>({...c,amount:String(c.amount)})),
    postedCount:rows.length
   };
  }
  function buildSnapshot(){
   const summary=buildSummary(),snapDir=serializeDir(),rulesSnap=serializeRules();
   return {
    ok:true,dataRevision:'rev-'+(revision++),viewKey:'vk-'+revision,
    records:rows.slice(0,3).map(r=>({...r,categoryDecision:r.categoryDecision?{...r.categoryDecision}:null})),
    ledgerView:{hasMore:true,nextCursor:{occurredAt:rows[2].occurredAt,id:rows[2].id},summary},
    recognitionTasks:[],channels:{ocr:{enabled:false,permitted:false},sms:{enabled:false,permitted:false},notification:{enabled:false,permitted:false}},
    accounts,bankOptions:[],bankCodeOptions:[],
    customCategories:{expense:snapDir.expense.map(c=>c.name),income:snapDir.income.map(c=>c.name)},
    categoryDirectory:snapDir,categoryIconOptions:iconOptions,
    classificationRules:rulesSnap,
    automationRunning:false,automatic:{enabled:false,applications:[]},screenshotStorage:{},captureHealth:{enabled:false,ready:false,shakeSelectedCount:1},
    walletSettings:{defaultAccountUid:'wechat',appBindings:{}}
   };
  }

  window.LedgerNative={
   snapshot:()=>JSON.stringify(buildSnapshot()),
   snapshotFirst:()=>JSON.stringify(buildSnapshot()),
   snapshotView:()=>JSON.stringify(buildSnapshot()),
   recordDetails:id=>{
    const r=rows.find(x=>x.id===Number(id));
    return JSON.stringify(r?{ok:true,record:{...r,categoryDecision:r.categoryDecision?{...r.categoryDecision}:null},rawText:'合成识别原文：'+(r.merchant||''),history:[]}:{ok:false,error:'记录不存在'});
   },
   getCategoryDirectory:()=>JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions}),
   classificationRules:()=>JSON.stringify({ok:true,classificationRules:serializeRules()}),
   recordPage:(month,search,direction,beforeAt,beforeId,expectedRevision)=>{
    window.calls.recordPage.push({month,search,direction,beforeAt,beforeId,expectedRevision});
    const remaining=rows.slice(3).filter(r=>r.direction!=='TRANSFER');
    return JSON.stringify({
     ok:true,
     records:remaining.map(r=>({...r,categoryDecision:r.categoryDecision?{...r.categoryDecision}:null})),
     ledgerView:{hasMore:false,nextCursor:null}
    });
   },
   saveClassificationRule:raw=>{
    const p=JSON.parse(raw);window.calls.saveRule.push(p);
    if(window.simError.saveRule){const err=window.simError.saveRule;window.simError.saveRule=null;return JSON.stringify({ok:false,...err})}
    const dirName=String(p.direction||'').toUpperCase();
    if(dirName!=='EXPENSE'&&dirName!=='INCOME')return JSON.stringify({ok:false,code:'RULE_INVALID',error:'自动分类仅支持收入或支出'});
    const name=String(p.name||'').trim();
    if(!name||name.length>80)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'请输入 1～80 字的规则名称'});
    const conds=Array.isArray(p.conditions)?p.conditions:[];
    if(conds.length<1||conds.length>20)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'匹配条件数量必须为 1～20 个'});
    for(const c of conds){
     const f=String(c?.field||'').toUpperCase(),op=String(c?.operator||'').toUpperCase(),kw=String(c?.value||'');
     if(!['MERCHANT','ORDER_CONTENT'].includes(f))return JSON.stringify({ok:false,code:'RULE_INVALID',error:'无效的匹配字段'});
     if(!['CONTAINS','EQUALS','NOT_CONTAINS'].includes(op))return JSON.stringify({ok:false,code:'RULE_INVALID',error:'无效的匹配方式'});
     if(!normKeyword(kw))return JSON.stringify({ok:false,code:'RULE_INVALID',error:f==='ORDER_CONTENT'?'请输入有效的订单内容关键词':'请输入有效的商家匹配关键词'});
     if(kw.length>200)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'关键词不能超过 200 字'});
    }
    const targetList=dirName==='INCOME'?dir.income:dir.expense;
    const targetCat=targetList.find(c=>c.id===p.categoryId&&c.direction===dirName);
    if(!targetCat)return JSON.stringify({ok:false,code:'RULE_TARGET',error:'请选择存在且收支方向一致的目标分类'});
    const matchMode=String(p.matchMode||'ALL').toUpperCase()==='ANY'?'ANY':'ALL';
    const source=typeof p.source==='string'&&p.source.trim()?p.source.trim():null;
    const cleanConds=conds.map(c=>({field:String(c.field).toUpperCase()==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT',operator:c.operator,value:c.value}));
    if(p.id){
     const existing=customRules.find(r=>r.id===p.id);
     if(!existing)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'规则已不存在，请刷新后重试'});
     existing.name=name;existing.enabled=p.enabled!==false;existing.direction=dirName;existing.categoryId=targetCat.id;
     existing.matchMode=matchMode;existing.source=source;existing.conditions=cleanConds;
     ruleVersionSeq++;
     return JSON.stringify({ok:true,classificationRules:serializeRules()});
    }
    const created={
     id:`rule:custom:${nextRuleId++}`,name,enabled:p.enabled!==false,direction:dirName,categoryId:targetCat.id,
     order:customRules.filter(r=>r.direction===dirName).length,matchMode,source,conditions:cleanConds,conflictReason:null
    };
    customRules.push(created);ruleVersionSeq++;
    return JSON.stringify({ok:true,classificationRules:serializeRules()});
   },
   deleteClassificationRule:id=>{
    window.calls.deleteRule.push(id);
    const idx=customRules.findIndex(r=>r.id===id);
    if(idx<0)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'规则已不存在'});
    customRules.splice(idx,1);ruleVersionSeq++;
    return JSON.stringify({ok:true,classificationRules:serializeRules()});
   },
   classificationRuleDraftFromBill:recordId=>{
    window.calls.classificationRuleDraftFromBill.push(recordId);
    if(window.simError.classificationRuleDraftFromBill){
     const err=window.simError.classificationRuleDraftFromBill;window.simError.classificationRuleDraftFromBill=null;
     return JSON.stringify({ok:false,...err});
    }
    const r=rows.find(x=>x.id===Number(recordId));
    if(!r)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'账单不存在'});
    if(r.direction==='TRANSFER')return JSON.stringify({ok:false,code:'RULE_INVALID',error:'转账记录不能创建分类规则'});
    const conds=[];
    if(r.merchant)conds.push({field:'MERCHANT',operator:'EQUALS',value:r.merchant});
    if(r.orderContent&&r.orderContent[0])conds.push({field:'ORDER_CONTENT',operator:'EQUALS',value:r.orderContent[0]});
    else if(r.note)conds.push({field:'ORDER_CONTENT',operator:'EQUALS',value:r.note});
    return JSON.stringify({
     ok:true,recordId:r.id,
     input:{
      direction:r.direction,merchant:r.merchant,
      orderContent:r.orderContent||(r.note?[r.note]:[]),
      captureSource:r.sourceObj?.captureSource||r.source,
      sourcePackage:r.sourceObj?.sourcePackage||null,
      platform:r.sourceObj?.platform||null
     },
     rule:{
      id:null,name:r.merchant||'新规则',enabled:true,direction:r.direction,categoryId:r.categoryId,
      order:0,matchMode:'ALL',source:null,conditions:conds,conflictReason:null
     }
    });
   },
   previewClassification:raw=>{
    window.calls.previewClassification.push(raw);
    if(window.simError.previewClassification){
     const err=window.simError.previewClassification;window.simError.previewClassification=null;
     return JSON.stringify({ok:false,...err});
    }
    const p=JSON.parse(raw);
    let testInput;
    if(p.recordId!=null){
     const r=rows.find(x=>x.id===Number(p.recordId));
     if(!r)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'账单不存在'});
     testInput={
      direction:r.direction,merchant:r.merchant||null,
      orderContent:r.orderContent||(r.note?[r.note]:[]),
      captureSource:r.sourceObj?.captureSource||r.source||'MANUAL',
      sourcePackage:r.sourceObj?.sourcePackage||null,
      platform:r.sourceObj?.platform||null
     };
    }else if(p.input){
     const dir=String(p.input.direction||'').toUpperCase();
     if(dir!=='EXPENSE'&&dir!=='INCOME')return JSON.stringify({ok:false,code:'RULE_INPUT',error:'无效的收支方向'});
     const m=p.input.merchant;
     if(m&&typeof m==='string'&&m.length>2000)return JSON.stringify({ok:false,code:'RULE_INPUT',error:'商家名称不能超过 2000 字'});
     const oc=Array.isArray(p.input.orderContent)?p.input.orderContent:[];
     if(oc.length>3)return JSON.stringify({ok:false,code:'RULE_INPUT',error:'订单内容最多 3 项'});
     for(const item of oc){
      if(item&&item.length>2000)return JSON.stringify({ok:false,code:'RULE_INPUT',error:'订单内容每项不能超过 2000 字'});
     }
     let captureSource=null,platform=null,sourcePackage=null;
     if(p.input.source){
      if(typeof p.input.source==='string'){
       const parts=p.input.source.split(':');
       captureSource=parts[0]||'LIVE';
       platform=parts[1]||null;
      }
     }
     testInput={direction:dir,merchant:m?String(m):null,orderContent:oc.map(String),captureSource,sourcePackage,platform};
    }else{
     return JSON.stringify({ok:false,code:'RULE_INPUT',error:'必须提供 recordId 或测试输入条件'});
    }

    const draftRule=p.rule;
    if(draftRule){
     const dDir=String(draftRule.direction||'').toUpperCase();
     if(dDir!=='EXPENSE'&&dDir!=='INCOME')return JSON.stringify({ok:false,code:'RULE_INVALID',error:'规则方向无效'});
     const targetCat=(dDir==='INCOME'?dir.income:dir.expense).find(c=>c.id===draftRule.categoryId);
     if(!targetCat)return JSON.stringify({ok:false,code:'RULE_TARGET',error:'请选择存在且收支方向一致的目标分类'});
     if(!Array.isArray(draftRule.conditions)||draftRule.conditions.length<1)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'规则至少需要一个匹配条件'});
     for(const c of draftRule.conditions){
      if(!c.value||!c.value.trim())return JSON.stringify({ok:false,code:'RULE_INVALID',error:'请输入有效的匹配关键词'});
     }
    }

    const dirRules=customRules.filter(r=>r.direction===testInput.direction);
    let effectiveCustom=[];
    if(draftRule){
     if(draftRule.id){
      effectiveCustom=dirRules.map(r=>r.id===draftRule.id?{...draftRule,isDraft:true}:{...r,isDraft:false});
     }else{
      effectiveCustom=dirRules.map(r=>({...r,isDraft:false})).concat([{...draftRule,id:'draft:temp',order:dirRules.length,isDraft:true}]);
     }
    }else{
     effectiveCustom=dirRules.map(r=>({...r,isDraft:false}));
    }

    const effectiveBuiltin=builtinRules.filter(r=>r.direction===testInput.direction).map(r=>({...r,isDraft:false}));
    const allCandidateRules=[...effectiveCustom,...effectiveBuiltin];

    function evalCond(c){
     const field=c.field,op=c.operator,kw=(c.value||'').normalize('NFKC').toLowerCase();
     if(field==='MERCHANT'){
      if(!testInput.merchant||!testInput.merchant.trim())return {matched:false};
      const actualVal=testInput.merchant,normVal=actualVal.normalize('NFKC').toLowerCase();
      if(op==='EQUALS')return {matched:normVal===kw};
      if(op==='CONTAINS')return {matched:normVal.includes(kw)};
      if(op==='NOT_CONTAINS')return {matched:!normVal.includes(kw)};
     }else if(field==='ORDER_CONTENT'){
      if(!testInput.orderContent||testInput.orderContent.length===0)return {matched:false};
      const normItems=testInput.orderContent.map(s=>s.normalize('NFKC').toLowerCase());
      if(op==='EQUALS')return {matched:normItems.some(i=>i===kw)};
      if(op==='CONTAINS')return {matched:normItems.some(i=>i.includes(kw))};
      if(op==='NOT_CONTAINS')return {matched:normItems.every(i=>!i.includes(kw))};
     }
     return {matched:false};
    }

    let selectedRule=null;
    const traceRules=[];
    for(const cand of allCandidateRules){
     if(cand.enabled===false){
      traceRules.push({
       rule:cand,source:cand.id.startsWith('builtin:')?'BUILTIN_RULE':'CUSTOM_RULE',
       enabled:false,directionMatches:cand.direction===testInput.direction,sourceMatches:true,
       conditionsMatch:false,matched:false,selected:false,interceptedByRuleId:null,
       conditions:(cand.conditions||[]).map(c=>({field:c.field,operator:c.operator,value:c.value,matched:false}))
      });
      continue;
     }
     const condResults=(cand.conditions||[]).map(c=>{
      const res=evalCond(c);
      return {field:c.field,operator:c.operator,value:c.value,matched:res.matched};
     });
     const isAny=cand.matchMode==='ANY';
     const ruleMatched=condResults.length>0&&(isAny?condResults.some(c=>c.matched):condResults.every(c=>c.matched));

     let isSelected=false,interceptedBy=null;
     if(ruleMatched){
      if(!selectedRule){
       selectedRule=cand;isSelected=true;
      }else{
       interceptedBy=selectedRule.id;
      }
     }
     traceRules.push({
      rule:cand,source:cand.id.startsWith('builtin:')?'BUILTIN_RULE':'CUSTOM_RULE',
      enabled:true,directionMatches:cand.direction===testInput.direction,sourceMatches:true,
      conditionsMatch:ruleMatched,matched:ruleMatched,selected:isSelected,
      interceptedByRuleId:interceptedBy,conditions:condResults
     });
    }

    const fallbackCat=(testInput.direction==='INCOME'?dir.income:dir.expense).find(c=>c.isFallback)||{id:'cat:expense:other',name:'其他',icon:'ellipsis',direction:testInput.direction};
    const decisionCat=selectedRule?((testInput.direction==='INCOME'?dir.income:dir.expense).find(c=>c.id===selectedRule.categoryId)||fallbackCat):fallbackCat;
    const isBuiltin=selectedRule?.id?.startsWith('builtin:');
    const decision={
     categoryId:decisionCat.id,source:selectedRule?(isBuiltin?'BUILTIN_RULE':'CUSTOM_RULE'):'FALLBACK_OTHER',
     ruleId:selectedRule?selectedRule.id:null,ruleVersion:'rules:v1:test',
     explanation:selectedRule?`命中${isBuiltin?'内置':'自定义'}规则「${selectedRule.name}」`:'未命中任何分类规则，归入默认「其他」分类'
    };

    return JSON.stringify({
     ok:true,
     preview:{
      input:testInput,decision,
      draftRuleId:draftRule?(draftRule.id||'draft:temp'):null,
      category:{id:decisionCat.id,name:decisionCat.name,icon:decisionCat.icon||'ellipsis',direction:decisionCat.direction},
      rules:traceRules
     }
    });
   },
   save:raw=>{
    const p=JSON.parse(raw);window.calls.saves.push(p);
    return JSON.stringify({ok:true,recordId:p.id||101});
   }
  };
 });
}

(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({headless:true});
 try{
  // Optional Baseline comparison with f7a366e
  if(withBaselineComparison){
   const baselineDir=path.join(output,'baseline-f7a366e');
   fs.mkdirSync(baselineDir,{recursive:true});
   const gitArchive=spawnSync('git',['archive','f7a366e','app/src/main/assets/web'],{cwd:rootDir});
   if(gitArchive.error||gitArchive.status!==0)throw(gitArchive.error||new Error('git archive failed'));
   const tarExtract=spawnSync('tar',['-x','-C',baselineDir],{input:gitArchive.stdout});
   if(tarExtract.error||tarExtract.status!==0)throw(tarExtract.error||new Error('tar extract failed'));

   const baselinePage=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),bErrors=[];
   baselinePage.on('pageerror',e=>bErrors.push(e.message));
   await baselinePage.route('https://**',r=>r.abort());
   await baselinePage.clock.install({time:new Date('2026-11-08T04:00:00Z')});
   await installRulePreviewFixture(baselinePage);
   await baselinePage.goto(pathToFileURL(path.join(baselineDir,'app/src/main/assets/web/index.html')).href);
   await baselinePage.evaluate(()=>document.fonts.ready);

   // In baseline f7a366e: bill edit does NOT have 'create-rule-from-bill' button
   await baselinePage.evaluate(()=>openRecord(101));
   await baselinePage.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);
   assert.equal(await baselinePage.locator('#create-rule-from-bill').count(),0);
   await baselinePage.screenshot({path:path.join(output,'00-baseline-bill-editor.png')});
   await baselinePage.evaluate(()=>cancelEditor(true));
   await baselinePage.waitForFunction(()=>!document.querySelector('#record-panel').open);

   // In baseline f7a366e: rule manager does NOT have '#rule-tester-card'
   await baselinePage.evaluate(()=>go('settings'));
   await baselinePage.locator('#classification-rules-settings').click();
   await baselinePage.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
   assert.equal(await baselinePage.locator('#rule-tester-card').count(),0);
   await baselinePage.screenshot({path:path.join(output,'00-baseline-rules-manager.png')});

   assert.deepEqual(bErrors,[]);
   await baselinePage.close();
  }

  // Current UI Testing
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://**',r=>r.abort());
  await page.clock.install({time:new Date('2026-11-08T04:00:00Z')});
  await installRulePreviewFixture(page);
  await page.goto(pathToFileURL(path.join(rootDir,'app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);

  // Snapshot before test to verify zero mutation
  const initialSnapshotJson=await page.evaluate(()=>window.LedgerNative.snapshot());

  // 1. Entry from Bill Edit: 'create-rule-from-bill' button
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#create-rule-from-bill').isVisible(),true);
  assert.equal(await page.locator('#create-rule-from-bill').innerText(),'据此创建规则');
  await page.screenshot({path:path.join(output,'01-bill-editor-create-rule-button.png')});

  // Verify TRANSFER bill hides 'create-rule-from-bill'
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);
  await page.evaluate(()=>openRecord(106)); // Record 106 is TRANSFER
  await page.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#create-rule-from-bill').isVisible(),false);
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);

  // Re-open Record 101 bill editor
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);

  // 2. Click '据此创建规则' -> Opens Rule Editor prefilled from bill
  await page.locator('#create-rule-from-bill').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  assert.equal(await page.locator('#sheet-title').innerText(),'自动分类规则');
  assert.match(await page.locator('#rule-editor-title').innerText(),/从账单创建规则/);
  // Check prefilled values
  assert.equal(await page.locator('#rule-name').inputValue(),'星巴克咖啡（静安寺店）');
  assert.equal(await page.locator('#rule-direction').inputValue(),'EXPENSE');
  assert.equal(await page.locator('#rule-category').inputValue(),'cat:expense:dining');
  // Check conditions
  assert.equal(await page.locator('[data-condition-row]').count(),2);
  assert.equal(await page.locator('[data-condition-field="0"]').inputValue(),'MERCHANT');
  assert.equal(await page.locator('[data-condition-operator="0"]').inputValue(),'EQUALS');
  assert.equal(await page.locator('[data-condition-keyword="0"]').inputValue(),'星巴克咖啡（静安寺店）');
  assert.equal(await page.locator('[data-condition-field="1"]').inputValue(),'ORDER_CONTENT');
  assert.equal(await page.locator('[data-condition-operator="1"]').inputValue(),'EQUALS');
  assert.equal(await page.locator('[data-condition-keyword="1"]').inputValue(),'美式咖啡');

  // Verify scope note clarifies append position for new draft
  assert.match(await page.locator('#rule-tester-scope').innerText(),/新规则草稿按保存后的追加位置（自定义规则末尾）测试/);
  // Verify draft toggle is checked
  assert.equal(await page.locator('#test-use-draft').isChecked(),true);

  await page.screenshot({path:path.join(output,'02-rule-editor-prefilled-from-bill.png')});

  // 3. Manual Test with draft: Run Test and verify matched decision
  // In manual mode: merchant is prefilled from draft input
  assert.equal(await page.locator('#test-merchant').inputValue(),'星巴克咖啡（静安寺店）');
  const prevPreviewCalls=await page.evaluate(()=>window.calls.previewClassification.length);
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-result');

  // Verify native.previewClassification was actually called by the click
  const newPreviewCalls=await page.evaluate(()=>window.calls.previewClassification.length);
  assert.ok(newPreviewCalls>prevPreviewCalls,'Clicking run-rule-test-btn must invoke native.previewClassification');

  // Assert result summary is actually in viewport (not scrolled away to top)
  const isSummaryInViewport=await page.evaluate(()=>{
   const el=document.querySelector('#test-result-summary');
   if(!el)return false;
   const r=el.getBoundingClientRect();
   return r.top>=0 && r.top<window.innerHeight && r.bottom>0;
  });
  assert.equal(isSummaryInViewport,true,'Test result summary must be visible in viewport after test run');

  // Verify decision summary
  assert.equal(await page.locator('#test-result-category-name').innerText(),'餐饮');
  assert.equal(await page.locator('.test-decision-source-tag').innerText(),'自定义规则');
  assert.match(await page.locator('#test-decision-explanation').innerText(),/所有星巴克咖啡/);

  // Verify trace cards exist (custom rule, draft rule, builtin rule)
  assert.equal(await page.locator('.rule-trace-card').count(),3);
  // First rule (rule:custom:1) matches and intercepts draft
  assert.equal(await page.locator('[data-trace-rule-id="rule:custom:1"] [data-trace-status="selected"]').innerText(),'✓ 最终命中');
  assert.equal(await page.locator('[data-trace-rule-id="draft:temp"] [data-trace-status="intercepted"]').innerText(),'由排在前面的规则优先生效');
  assert.match(await page.locator('[data-trace-rule-id="draft:temp"] .rule-trace-intercept-note').innerText(),/条件满足，但由排在前面的规则「所有星巴克咖啡」优先生效/);

  await page.screenshot({path:path.join(output,'03-manual-test-intercepted-result.png')});

  // 4. Missing field negative condition (缺字段负向不命中)
  // Edit the rule to have a condition ORDER_CONTENT NOT_CONTAINS "外卖"
  await page.locator('#add-rule-condition').click();
  assert.equal(await page.locator('[data-condition-row]').count(),3);
  await page.locator('[data-condition-field="2"]').selectOption('ORDER_CONTENT');
  await page.locator('[data-condition-operator="2"]').selectOption('NOT_CONTAINS');
  await page.locator('[data-condition-keyword="2"]').fill('外卖');

  // In manual test input: remove order content items so ORDER_CONTENT is missing
  const removeBtns=page.locator('[data-remove-test-order]');
  const rCount=await removeBtns.count();
  for(let i=rCount-1;i>=0;i--){
   await page.locator(`[data-remove-test-order="${i}"]`).click();
  }
  assert.equal(await page.locator('.test-order-row').count(),0);

  // Set merchant to something that doesn't match rule:custom:1
  await page.locator('#test-merchant').fill('西西弗书店');
  // Update draft rule merchant condition to match
  await page.locator('[data-condition-keyword="0"]').fill('西西弗书店');

  // Run test
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-result');

  // The draft has NOT_CONTAINS '外卖', but orderContent is missing/empty!
  // It MUST NOT match, evaluating to false!
  const draftTrace=page.locator('[data-trace-rule-id="draft:temp"]');
  assert.equal(await draftTrace.locator('[data-trace-status="conditions-mismatch"]').innerText(),'条件不满足');

  // Overall decision falls back to '其他'
  assert.equal(await page.locator('#test-result-category-name').innerText(),'其他');
  assert.match(await page.locator('#test-decision-explanation').innerText(),/未命中任何分类规则/);

  await page.screenshot({path:path.join(output,'04-negative-missing-field-result.png')});

  // 5. Test with Existing Bill Picker & trusted source
  // Switch mode to BILL
  await page.locator('#test-mode-bill-btn').click();
  await page.waitForSelector('#test-bill-section');

  // Check pagination info: initial records loaded
  assert.match(await page.locator('#test-bill-page-meta').innerText(),/已加载本月前 3 笔账单/);
  assert.equal(await page.locator('.test-bill-item').count(),3);

  // Click '加载更多账单'
  await page.locator('#test-bill-load-more').click();
  await page.waitForFunction(()=>document.querySelectorAll('.test-bill-item').length===5);
  assert.match(await page.locator('#test-bill-page-meta').innerText(),/已显示本月全部可用账单（共 5 笔）/);

  // Select Record 102 (上海地铁通行)
  await page.locator('[data-test-record-id="102"]').click();
  assert.equal(await page.locator('[data-test-record-id="102"]').getAttribute('aria-checked'),'true');

  // Run test on selected bill
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-result');

  // Assert result summary is visible in viewport for bill test
  const billSummaryInViewport=await page.evaluate(()=>{
   const el=document.querySelector('#test-result-summary');
   if(!el)return false;
   const r=el.getBoundingClientRect();
   return r.top>=0 && r.top<window.innerHeight && r.bottom>0;
  });
  assert.equal(billSummaryInViewport,true,'Test result summary must be visible in viewport for bill test');

  // Record 102 matches builtin交通 rule
  assert.equal(await page.locator('#test-result-category-name').innerText(),'交通');
  assert.equal(await page.locator('.test-decision-source-tag').innerText(),'内置规则');
  assert.match(await page.locator('#test-decision-explanation').innerText(),/内置交通/);

  // Verify trusted source tag is displayed from backend source: LIVE + alipay
  assert.match(await page.locator('#test-result-source').innerText(),/支付宝凭证 · 现场识别/);

  await page.screenshot({path:path.join(output,'05-bill-picker-tested.png')});

  // 6. Test direct test of existing rules from list/header without draft obstruction
  // Close rules sheet and re-open from settings
  await page.evaluate(()=>closeSheet(false));
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return !s.open});
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);

  await page.evaluate(()=>go('settings'));
  await page.locator('#classification-rules-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});

  // Verify: in 新增规则 mode, editingRule is null, formConditions has empty keyword, testingWithDraft is false
  assert.equal(await page.locator('#test-use-draft').isChecked(),false);
  // Enter a merchant name and run test directly
  await page.locator('#test-merchant').fill('星巴克咖啡（徐家汇店）');
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-result');

  // Test should succeed without draft error and hit rule:custom:1
  assert.equal(await page.locator('#test-result-category-name').innerText(),'餐饮');
  assert.match(await page.locator('#test-decision-explanation').innerText(),/所有星巴克咖啡/);

  // 7. Test Invalid Input handling (RULE_INPUT / RULE_INVALID)
  // Enter merchant with length > 2000 chars
  await page.evaluate(()=>{
   $('test-merchant').value='超长商家'.repeat(600); // 2400 chars
  });
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-error');

  // Assert error notice is visible in viewport
  const errorInViewport=await page.evaluate(()=>{
   const el=document.querySelector('#rule-test-error');
   if(!el)return false;
   const r=el.getBoundingClientRect();
   return r.top>=0 && r.top<window.innerHeight && r.bottom>0;
  });
  assert.equal(errorInViewport,true,'Test error notice must be visible in viewport');

  assert.match(await page.locator('#rule-test-error').innerText(),/测试商家名称不能超过 2000 字/);
  await page.screenshot({path:path.join(output,'06-invalid-input-error.png')});

  // 8. Uncommitted Category Prefill & Bill Draft Preservation
  // Close sheet, go to record 101 editor
  await page.evaluate(()=>closeSheet(false));
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return !s.open});
  await page.evaluate(()=>go('ledger'));
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);

  // Change category to '购物' (cat:expense:shopping) without saving
  await page.evaluate(()=>{
   $('category').dataset.categoryId='cat:expense:shopping';
   $('category').textContent='购物';
   if(draft){draft._categoryEdited=true;draft.category='购物';draft.categoryId='cat:expense:shopping';}
   remember();
  });

  // Click '据此创建规则' again
  await page.locator('#create-rule-from-bill').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});

  // Target category MUST be prefilled with user's uncommitted '购物'
  assert.equal(await page.locator('#rule-category').inputValue(),'cat:expense:shopping');

  // Cancel rule sheet again
  await page.evaluate(()=>closeSheet(false));
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return !s.open});

  // In Bill Editor: verify draft still has uncommitted '购物' and is dirty
  assert.equal(await page.locator('#category').innerText(),'购物');
  assert.equal(await page.evaluate(()=>editorDirty()),true);

  await page.screenshot({path:path.join(output,'07-bill-draft-preserved.png')});

  // 9. Explicit Save feedback
  // Re-open '据此创建规则'
  await page.locator('#create-rule-from-bill').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  // Clean up rule name & save
  await page.locator('#rule-name').fill('星巴克臻选咖啡');
  await page.locator('#save-rule').click();

  // Toast / notification feedback
  await page.waitForFunction(()=>document.querySelector('.toast')?.classList.contains('show'));
  assert.match(await page.locator('.toast').innerText(),/规则已保存，仅影响后续新识别任务/);

  await page.screenshot({path:path.join(output,'08-rule-saved-feedback.png')});

  // Verify Zero Mutation from previews: snapshot before vs after preview test calls
  const finalSnapshotJson=await page.evaluate(()=>window.LedgerNative.snapshot());
  const snap1=JSON.parse(initialSnapshotJson),snap2=JSON.parse(finalSnapshotJson);
  // Records, accounts, walletSettings should be completely untouched by previews
  assert.deepEqual(snap1.records,snap2.records);
  assert.deepEqual(snap1.accounts,snap2.accounts);
  assert.deepEqual(snap1.walletSettings,snap2.walletSettings);

  // 10. 320px narrow viewport & large text layout (no horizontal overflow)
  await page.setViewportSize({width:320,height:740});
  await page.evaluate(()=>document.documentElement.classList.add('large-text'));
  await page.waitForTimeout(100);

  // Open rules sheet to check tester card layout in 320px + large text
  await page.evaluate(()=>openClassificationRules('EXPENSE'));
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});

  // Assert no horizontal overflow
  const noOverflow=await page.evaluate(()=>{
   const sc=document.querySelector('#sheet-content');
   return sc.scrollWidth<=sc.clientWidth+1;
  });
  assert.equal(noOverflow,true,'Sheet content must not overflow horizontally on 320px + large-text');

  await page.screenshot({path:path.join(output,'09-narrow-large-text-layout.png')});

  // 11. XSS Prevention Test
  await page.evaluate(()=>{
   $('test-merchant').value='<script>alert("xss")</script>"&\'';
  });
  await page.locator('#run-rule-test-btn').click();
  await page.waitForSelector('#rule-test-result');

  // Verify script tag is NOT in innerHTML as executable HTML
  const xssEscaped=await page.evaluate(()=>{
   const elem=document.querySelector('#rule-test-result');
   return !elem.innerHTML.includes('<script>alert')&&elem.textContent.includes('<script>alert');
  });
  assert.equal(xssEscaped,true,'XSS strings must be safely escaped in text content');

  assert.deepEqual(errors,[]);
  console.log(`PASS rule-preview UI checks (Ticket 09): entry from bill/settings, draft testing, missing field negative logic, trusted sources, interception explanation, draft preservation, 320px layout, XSS guard; screenshots saved to ${output}`);
 }finally{
  await browser.close();
 }
})();
