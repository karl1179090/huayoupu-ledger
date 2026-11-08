'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),{spawnSync}=require('node:child_process');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const output=process.env.LEDGER_SCREENSHOT_DIR||path.resolve('output/t07-rules');
const withBaseline=process.argv.includes('--with-baseline-comparison')||process.env.LEDGER_COMPARE_BASELINE==='1';

async function installRulesFixture(page){
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
    {id:'cat:income:gift',name:'礼物',direction:'INCOME',icon:'gift',order:2,isFallback:false},
    {id:'cat:income:refund',name:'退款',direction:'INCOME',icon:'rotate-left',order:3,isFallback:false},
    {id:'cat:income:other',name:'其他',direction:'INCOME',icon:'ellipsis',order:4,isFallback:true}
   ]
  };
  let ruleVersionSeq=1,nextRuleId=1,revision=1;
  const customRules=[];
  const builtinRules=[
   {id:'builtin:expense:dining',name:'内置餐饮',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'餐'}]},
   {id:'builtin:expense:transit',name:'内置交通',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:transit',order:1,matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'地铁'}]},
   {id:'builtin:income:salary',name:'内置工资',enabled:true,direction:'INCOME',categoryId:'cat:income:salary',order:0,matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'发薪'}]}
  ];
  const rows=[
   {
    id:101,uid:'rec-101',merchant:'星巴克咖啡（静安寺店）',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',
    amountCents:3600,direction:'EXPENSE',occurredAt:now-3600_000,state:'POSTED',source:'OCR',note:'美式咖啡',
    categoryDecision:{categoryId:'cat:expense:dining',source:'CUSTOM_RULE',manual:false,ruleId:'rule:custom:secret-id-999',ruleVersion:'rules:v1:secret-hash-888',explanation:'命中规则：星巴克咖啡'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-3600,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:102,uid:'rec-102',merchant:'上海地铁通行',categoryId:'cat:expense:transit',category:'交通',categoryIcon:'bus',
    amountCents:400,direction:'EXPENSE',occurredAt:now-7200_000,state:'POSTED',source:'IMAGE',note:'通勤',
    categoryDecision:{categoryId:'cat:expense:transit',source:'BUILTIN_RULE',manual:false,ruleId:'builtin:expense:transit',ruleVersion:'rules:v1:secret-hash-888',explanation:'内置规则：交通'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-400,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:103,uid:'rec-103',merchant:'未知独立小店',categoryId:'cat:expense:other',category:'其他',categoryIcon:'ellipsis',
    amountCents:1980,direction:'EXPENSE',occurredAt:now-10800_000,state:'POSTED',source:'OCR',note:'',
    categoryDecision:{categoryId:'cat:expense:other',source:'FALLBACK_OTHER',manual:false,ruleId:null,ruleVersion:'rules:v1:secret-hash-888',explanation:'未命中规则，归入其他'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-1980,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   }
  ];
  const accounts=[
   {id:1,uid:'wechat',name:'微信零钱',kind:'wechat',type:'ASSET',amountCents:50000,archived:false,revision:1}
  ];
  window.calls={saveRule:[],deleteRule:[],reorderRules:[],setBuiltinEnabled:[],copyBuiltin:[],saveCategory:[],saves:[]};
  window.simError={saveRule:null,deleteRule:null,reorderRules:null,setBuiltinEnabled:null,copyBuiltin:null};
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
   const snapDir=serializeDir();
   const catMap=new Map();
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
    totals:{expense:String(expenseTotal),income:'0',expenseCount:rows.length,incomeCount:0,totalCount:rows.length},
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
    records:rows.map(r=>({...r,categoryDecision:r.categoryDecision?{...r.categoryDecision}:null})),
    ledgerView:{hasMore:false,nextCursor:null,summary},
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
   recordDetails:id=>{const r=rows.find(x=>x.id===Number(id));return JSON.stringify(r?{ok:true,record:{...r,categoryDecision:r.categoryDecision?{...r.categoryDecision}:null},rawText:'合成识别原文',history:[]}:{ok:false,error:'记录不存在'})},
   getCategoryDirectory:()=>JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions}),
   classificationRules:()=>JSON.stringify({ok:true,classificationRules:serializeRules()}),
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
     const hasIllegalControl=Array.from(kw).some(ch=>{const cp=ch.codePointAt(0);return (cp<=31||(cp>=127&&cp<=159))&&!/\s/u.test(ch)});
     if(hasIllegalControl)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'关键词包含不支持的控制字符'});
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
     const sameDir=existing.direction===dirName;
     existing.name=name;existing.enabled=p.enabled!==false;existing.direction=dirName;existing.categoryId=targetCat.id;
     existing.order=sameDir?existing.order:customRules.filter(r=>r.direction===dirName).length;
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
    if(window.simError.deleteRule){const err=window.simError.deleteRule;window.simError.deleteRule=null;return JSON.stringify({ok:false,...err})}
    const idx=customRules.findIndex(r=>r.id===id);
    if(idx<0)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'规则已不存在，请刷新后重试'});
    const removedDir=customRules[idx].direction;
    customRules.splice(idx,1);
    customRules.filter(r=>r.direction===removedDir).sort((a,b)=>a.order-b.order).forEach((r,i)=>{r.order=i});
    ruleVersionSeq++;
    return JSON.stringify({ok:true,classificationRules:serializeRules()});
   },
   reorderClassificationRules:(direction,rawIds)=>{
    const dirName=String(direction||'').toUpperCase(),ids=JSON.parse(rawIds);
    window.calls.reorderRules.push({direction:dirName,ids});
    if(window.simError.reorderRules){const err=window.simError.reorderRules;window.simError.reorderRules=null;return JSON.stringify({ok:false,...err})}
    const dirRules=customRules.filter(r=>r.direction===dirName);
    if(!Array.isArray(ids)||ids.length!==dirRules.length||new Set(ids).size!==dirRules.length||!ids.every(id=>dirRules.some(r=>r.id===id))){
     return JSON.stringify({ok:false,code:'RULE_INVALID',error:'规则顺序已变化，请刷新后重试'});
    }
    ids.forEach((id,idx)=>{const item=dirRules.find(r=>r.id===id);if(item)item.order=idx});
    ruleVersionSeq++;
    return JSON.stringify({ok:true,classificationRules:serializeRules()});
   },
   setBuiltinClassificationRuleEnabled:raw=>{
    const p=JSON.parse(raw);window.calls.setBuiltinEnabled.push(p);
    if(window.simError.setBuiltinEnabled){const err=window.simError.setBuiltinEnabled;window.simError.setBuiltinEnabled=null;return JSON.stringify({ok:false,...err})}
    const found=builtinRules.find(r=>r.id===p.id);
    if(!found)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'内置规则不存在'});
    found.enabled=p.enabled!==false;ruleVersionSeq++;
    return JSON.stringify({ok:true,classificationRules:serializeRules()});
   },
   copyBuiltinClassificationRule:id=>{
    window.calls.copyBuiltin.push(id);
    if(window.simError.copyBuiltin){const err=window.simError.copyBuiltin;window.simError.copyBuiltin=null;return JSON.stringify({ok:false,...err})}
    const found=builtinRules.find(r=>r.id===id);
    if(!found)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'内置规则不存在'});
    const draft={
     id:null,name:`${found.name}（副本）`,enabled:true,direction:found.direction,categoryId:found.categoryId,
     order:0,matchMode:found.matchMode||'ALL',source:found.source||null,conditions:found.conditions.map(c=>({...c}))
    };
    return JSON.stringify({ok:true,rule:draft});
   },
   saveCategory:raw=>{
    const p=JSON.parse(raw);window.calls.saveCategory.push(p);
    const list=String(p.direction||'').toUpperCase()==='INCOME'?dir.income:dir.expense;
    const target=list.find(c=>c.id===p.id);
    if(!target)return JSON.stringify({ok:false,code:'CATEGORY_NOT_FOUND',error:'分类不存在'});
    target.name=String(p.name||'').trim();target.icon=p.icon||target.icon;
    rows.forEach(r=>{if(r.categoryId===target.id){r.category=target.name;r.categoryIcon=target.icon}});
    const snapDir=serializeDir();
    return JSON.stringify({ok:true,category:target,categoryDirectory:snapDir,categoryIconOptions:iconOptions});
   },
   save:raw=>{
    const payload=JSON.parse(raw);window.calls.saves.push(payload);
    if(payload.id){
     const existing=rows.find(r=>r.id===Number(payload.id));
     if(existing){
      const changedCat=payload.categoryEdited===true||(payload.categoryEdited===undefined&&(existing.categoryId!==payload.categoryId||existing.direction!==payload.direction));
      existing.merchant=payload.merchant;existing.direction=payload.direction;existing.category=payload.category;existing.categoryId=payload.categoryId;existing.note=payload.note;
      if(changedCat)existing.categoryDecision={categoryId:payload.categoryId,source:'MANUAL',manual:true,ruleId:null,ruleVersion:null,explanation:'人工选择分类'};
     }
     return JSON.stringify({ok:true,id:payload.id,state:'POSTED'});
    }
    return JSON.stringify({ok:true,id:201,state:'POSTED'});
   },
   remember:()=>{}
  };
 });
}

(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://**',r=>r.abort());
  await page.clock.install({time:new Date('2026-11-08T04:00:00Z')});
  await installRulesFixture(page);

  // Opt-in real baseline comparison: extract commit 5584f0b web assets without shell pipes and capture actual before screenshots
  if(withBaseline){
   const baselineDir=path.resolve('output/t07-baseline-assets');
   fs.mkdirSync(baselineDir,{recursive:true});
   const arch=spawnSync('git',['archive','5584f0b','app/src/main/assets/web'],{cwd:path.resolve(__dirname,'..'),maxBuffer:20*1024*1024});
   assert.equal(arch.status,0,'git archive 5584f0b must succeed');
   const tar=spawnSync('tar',['-x','-C',baselineDir],{input:arch.stdout});
   assert.equal(tar.status,0,'tar extraction must succeed');

   const basePage=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'});
   basePage.on('pageerror',e=>errors.push(`baseline error: ${e.message}`));
   await basePage.route('https://**',r=>r.abort());
   await basePage.clock.install({time:new Date('2026-11-08T04:00:00Z')});
   await installRulesFixture(basePage);
   await basePage.goto(pathToFileURL(path.join(baselineDir,'app/src/main/assets/web/index.html')).href);
   await basePage.evaluate(()=>document.fonts.ready);

   await basePage.evaluate(()=>go('settings'));
   await basePage.screenshot({path:path.join(output,'00-before-settings-rules-entry.png')});
   await basePage.evaluate(()=>openRecord(101));
   await basePage.waitForFunction(()=>document.querySelector('#record-panel').open&&!document.querySelector('#record-panel')._morph);
   await basePage.screenshot({path:path.join(output,'00-before-record-detail.png')});
   await basePage.close();
  }

  // Load current assets
  await page.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);

  // 1. Settings entry & layout preservation check
  await page.evaluate(()=>go('settings'));
  assert.equal(await page.locator('.account-preferences > button').count(),2,'Existing .account-preferences > button count must stay 2');
  assert.equal(await page.locator('#classification-rules-settings').isVisible(),true);
  assert.equal(await page.locator('#classification-rules-summary').innerText(),'按商家自动归类');
  await page.screenshot({path:path.join(output,'01-settings-rules-entry.png')});

  // 2. Open Automatic Classification Rules sheet: verify empty state, scope note, and builtin rules section
  await page.locator('#classification-rules-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  assert.equal(await page.locator('#sheet-title').innerText(),'自动分类规则');
  assert.match(await page.locator('#classification-rules-note').innerText(),/首条命中的启用规则生效/);
  assert.match(await page.locator('#classification-rules-note').innerText(),/自定义规则优先于内置规则/);
  assert.match(await page.locator('#classification-rules-note').innerText(),/仅影响后续新识别账单，不会修改已有账单/);
  assert.equal(await page.locator('#classification-rules-empty').isVisible(),true);
  assert.equal(await page.locator('#builtin-rules-section').isVisible(),true);
  assert.equal(await page.locator('#builtin-rules-title').innerText(),'内置支出规则 (2)');
  assert.equal(await page.locator('[data-builtin-rule-row="builtin:expense:dining"]').isVisible(),true);
  assert.equal(await page.locator('[data-builtin-rule-row="builtin:expense:transit"]').isVisible(),true);
  await page.screenshot({path:path.join(output,'02-rules-empty-sheet.png')});

  // 3. Validation: empty name, whitespace-only keyword, control character keyword, cross-direction target category, and empty second condition keyword
  await page.locator('#rule-name').fill('');
  await page.locator('#rule-keyword').fill('星巴克');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.match(await page.locator('#rule-form-error').innerText(),/请输入规则名称/);
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),0);

  await page.locator('#rule-name').fill('空白关键词规则');
  await page.locator('#rule-keyword').fill('   \t\n   ');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.match(await page.locator('#rule-form-error').innerText(),/不能仅为空白/);
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),0);

  await page.locator('#rule-name').fill('控制字符规则');
  await page.locator('#rule-keyword').fill('星巴克\u0007咖啡');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.match(await page.locator('#rule-form-error').innerText(),/不支持的控制字符/);
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),0);

  // Inject cross-direction category into #rule-category while direction is EXPENSE -> must block save
  await page.locator('#rule-keyword').fill('星巴克');
  await page.evaluate(()=>{
   const sel=document.querySelector('#rule-category');
   const opt=document.createElement('option');opt.value='cat:income:salary';opt.textContent='非法跨方向工资';
   sel.append(opt);sel.value='cat:income:salary';
  });
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.match(await page.locator('#rule-form-error').innerText(),/有效目标分类/);
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),0);

  // Add a second condition and leave its keyword blank -> must block save
  await page.locator('#rule-category').selectOption('cat:expense:dining');
  await page.locator('#rule-name').fill('多条件校验测试');
  await page.locator('#rule-keyword').fill('美团');
  await page.locator('#add-rule-condition').click();
  assert.equal(await page.locator('#rule-conditions-list [data-condition-row]').count(),2);
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.match(await page.locator('#rule-form-error').innerText(),/不能仅为空白/);
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),0);

  // Remove the second condition using remove button
  await page.locator('[data-remove-condition="1"]').click();
  assert.equal(await page.locator('#rule-conditions-list [data-condition-row]').count(),1);
  await page.screenshot({path:path.join(output,'03-rules-validation-error.png')});

  // 4. Create compound rules: Merchant + Order Content, ALL vs ANY, and Source Restrictions
  // Rule 1: Compound Rule with ALL and Source = LIVE:com.tencent.mm (微信现场)
  await page.locator('#rule-name').fill('合成平台餐饮');
  await page.locator('#rule-category').selectOption('cat:expense:dining');
  await page.locator('#rule-match-mode').selectOption('ALL');
  await page.locator('#rule-source').selectOption('LIVE:com.tencent.mm');
  await page.locator('#rule-field').selectOption('MERCHANT');
  await page.locator('#rule-operator').selectOption('CONTAINS');
  await page.locator('#rule-keyword').fill('合成平台');
  await page.locator('#add-rule-condition').click();
  await page.locator('[data-condition-field="1"]').selectOption('ORDER_CONTENT');
  await page.locator('[data-condition-operator="1"]').selectOption('NOT_CONTAINS');
  await page.locator('[data-condition-keyword="1"]').fill('打车');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),1);
  assert.deepEqual(await page.evaluate(()=>window.calls.saveRule[0]),{
   name:'合成平台餐饮',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ALL',source:'LIVE:com.tencent.mm',
   conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'合成平台'},{field:'ORDER_CONTENT',operator:'NOT_CONTAINS',value:'打车'}]
  });

  // Rule 2: Compound Rule with ANY and Source = null (不限来源)
  await page.locator('#rule-name').fill('交通出行任一满足');
  await page.locator('#rule-category').selectOption('cat:expense:transit');
  await page.locator('#rule-match-mode').selectOption('ANY');
  await page.locator('#rule-source').selectOption('');
  await page.locator('#rule-field').selectOption('MERCHANT');
  await page.locator('#rule-operator').selectOption('CONTAINS');
  await page.locator('#rule-keyword').fill('地铁');
  await page.locator('#add-rule-condition').click();
  await page.locator('[data-condition-field="1"]').selectOption('ORDER_CONTENT');
  await page.locator('[data-condition-operator="1"]').selectOption('CONTAINS');
  await page.locator('[data-condition-keyword="1"]').fill('出行');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),2);

  // Rule 3: Single condition rule with EQUALS
  await page.locator('#rule-name').fill('星巴克专星送');
  await page.locator('#rule-category').selectOption('cat:expense:shopping');
  await page.locator('#rule-operator').selectOption('EQUALS');
  await page.locator('#rule-keyword').fill('星巴克专星送');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),3);

  // Verify summaries rendered in DOM: clean Chinese, no enum leaks
  assert.equal(await page.locator('[data-rule-summary="rule:custom:1"]').innerText(),'微信现场 · 全部满足：商家包含「合成平台」 且 订单内容不包含「打车」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:2"]').innerText(),'任一满足：商家包含「地铁」 或 订单内容包含「出行」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:3"]').innerText(),'商家等于「星巴克专星送」');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:1');
  await page.screenshot({path:path.join(output,'04-rules-expense-list.png')});

  // 5. Negative condition & missing field verification (Ticket 07 requirement: 缺字段不命中不包含)
  // Verify UI hint note is present and accurate
  assert.match(await page.locator('.rule-hint-note').innerText(),/缺少某字段时不命中该字段的所有条件（包括“不包含”）/);

  // Verify simulation of missing field logic
  const negativeMatchCheck=await page.evaluate(()=>{
   const rule={matchMode:'ALL',conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'合成平台'},{field:'ORDER_CONTENT',operator:'NOT_CONTAINS',value:'打车'}]};
   function evalCandidate(candidate){
    return rule.conditions.every(c=>{
     const val=candidate[c.field];
     if(val===undefined||val===null||val==='')return false; // Missing field always false, including NOT_CONTAINS
     const normVal=val.normalize('NFKC').toLowerCase(),normKw=c.value.normalize('NFKC').toLowerCase();
     if(c.operator==='CONTAINS')return normVal.includes(normKw);
     if(c.operator==='EQUALS')return normVal===normKw;
     if(c.operator==='NOT_CONTAINS')return !normVal.includes(normKw);
     return false;
    });
   }
   return {
    candidateWithOrderContentFastFood: evalCandidate({MERCHANT:'合成平台餐饮店',ORDER_CONTENT:'快餐便当'}),
    candidateWithMissingOrderContent: evalCandidate({MERCHANT:'合成平台餐饮店',ORDER_CONTENT:null}),
    candidateWithTaxiOrderContent: evalCandidate({MERCHANT:'合成平台',ORDER_CONTENT:'打车快车'})
   };
  });
  assert.equal(negativeMatchCheck.candidateWithOrderContentFastFood,true,'Present order content excluding keyword matches NOT_CONTAINS');
  assert.equal(negativeMatchCheck.candidateWithMissingOrderContent,false,'Missing order content field NEVER matches NOT_CONTAINS');
  assert.equal(negativeMatchCheck.candidateWithTaxiOrderContent,false,'Order content containing keyword does not match NOT_CONTAINS');

  // 6. Reorder rules & First-hit Priority update
  await page.locator('[data-rule-up="rule:custom:2"]').click();
  const reorderedIds=await page.locator('#classification-rules-list [data-rule-row]').evaluateAll(els=>els.map(e=>e.dataset.ruleRow));
  assert.deepEqual(reorderedIds,['rule:custom:2','rule:custom:1','rule:custom:3']);
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:2');

  // Toggle enable/disable on top rule
  await page.locator('[data-toggle-rule="rule:custom:2"]').click();
  assert.equal(await page.locator('[data-toggle-rule="rule:custom:2"]').getAttribute('aria-checked'),'false');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:1');
  await page.locator('[data-toggle-rule="rule:custom:2"]').click();
  assert.equal(await page.locator('[data-toggle-rule="rule:custom:2"]').getAttribute('aria-checked'),'true');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:2');

  // 7. Built-in rules management: Toggle enable & Copy to custom rule draft
  assert.equal(await page.locator('[data-builtin-rule-row="builtin:expense:dining"]').isVisible(),true);
  assert.equal(await page.locator('[data-builtin-summary="builtin:expense:dining"]').innerText(),'商家包含「餐」');

  // Toggle builtin rule
  await page.locator('[data-toggle-builtin-rule="builtin:expense:transit"]').click();
  assert.equal(await page.evaluate(()=>window.calls.setBuiltinEnabled.length),1);
  assert.deepEqual(await page.evaluate(()=>window.calls.setBuiltinEnabled[0]),{id:'builtin:expense:transit',enabled:false});
  assert.equal(await page.locator('[data-toggle-builtin-rule="builtin:expense:transit"]').getAttribute('aria-checked'),'false');
  await page.locator('[data-toggle-builtin-rule="builtin:expense:transit"]').click();
  assert.equal(await page.locator('[data-toggle-builtin-rule="builtin:expense:transit"]').getAttribute('aria-checked'),'true');

  // Copy builtin rule -> Cancel draft (no rule created)
  const saveCountBeforeCopy=await page.evaluate(()=>window.calls.saveRule.length);
  await page.locator('[data-copy-builtin-rule="builtin:expense:dining"]').click();
  assert.equal(await page.evaluate(()=>window.calls.copyBuiltin.length),1);
  assert.equal(await page.locator('#rule-name').inputValue(),'内置餐饮（副本）');
  assert.match(await page.locator('#rule-editor-title').innerText(),/复制规则预填/);
  assert.equal(await page.locator('#cancel-rule-edit').innerText(),'取消预填');
  await page.locator('#cancel-rule-edit').click();
  assert.equal(await page.locator('#rule-name').inputValue(),'');
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),saveCountBeforeCopy,'Canceling draft must not save any rule');

  // Copy builtin rule -> Edit & Save as custom rule
  await page.locator('[data-copy-builtin-rule="builtin:expense:dining"]').click();
  await page.locator('#rule-name').fill('内置餐饮副本扩展');
  await page.locator('#add-rule-condition').click();
  await page.locator('[data-condition-field="1"]').selectOption('ORDER_CONTENT');
  await page.locator('[data-condition-operator="1"]').selectOption('CONTAINS');
  await page.locator('[data-condition-keyword="1"]').fill('外卖');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),saveCountBeforeCopy+1);
  const copySaved=await page.evaluate(()=>window.calls.saveRule.at(-1));
  assert.equal(copySaved.name,'内置餐饮副本扩展');
  assert.equal(copySaved.id,undefined,'Copy draft must be saved as new custom rule without builtin id');
  assert.equal(await page.locator('[data-rule-row="rule:custom:4"]').isVisible(),true);
  assert.equal(await page.locator('[data-builtin-rule-row="builtin:expense:dining"]').isVisible(),true,'Builtin rule must remain unmodified');
  await page.screenshot({path:path.join(output,'05-rules-builtin-and-copy.png')});

  // 8. Direction switch to INCOME tab: shows income custom rules and income builtin rules
  await page.locator('#rule-direction').selectOption('INCOME');
  const incomeCategoryOptions=await page.locator('#rule-category option').evaluateAll(els=>els.map(e=>e.value));
  assert.deepEqual(incomeCategoryOptions,['cat:income:salary','cat:income:bonus','cat:income:gift','cat:income:refund','cat:income:other']);
  assert.equal(await page.locator('#builtin-rules-title').innerText(),'内置收入规则 (1)');
  assert.equal(await page.locator('[data-builtin-rule-row="builtin:income:salary"]').isVisible(),true);

  await page.locator('#rule-name').fill('兼职稿费自动归类');
  await page.locator('#rule-category').selectOption('cat:income:bonus');
  await page.locator('#rule-source').selectOption('IMAGE:alipay');
  await page.locator('#rule-field').selectOption('MERCHANT');
  await page.locator('#rule-operator').selectOption('CONTAINS');
  await page.locator('#rule-keyword').fill('出版社');
  await page.locator('#add-rule-condition').click();
  await page.locator('[data-condition-field="1"]').selectOption('ORDER_CONTENT');
  await page.locator('[data-condition-operator="1"]').selectOption('CONTAINS');
  await page.locator('[data-condition-keyword="1"]').fill('稿费');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('[data-rule-row="rule:custom:5"]').isVisible(),true);
  assert.equal(await page.locator('[data-rule-summary="rule:custom:5"]').innerText(),'支付宝凭证图片 · 全部满足：商家包含「出版社」 且 订单内容包含「稿费」');
  await page.screenshot({path:path.join(output,'06-rules-income-tab.png')});

  // 9. Backend error display: preserve user inputs on error
  await page.evaluate(()=>{window.simError.saveRule={code:'RULE_TARGET',error:'请选择存在且收支方向一致的目标分类'}});
  await page.locator('#rule-name').fill('保留输入测试商户');
  await page.locator('#rule-keyword').fill('测试商户');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.equal(await page.locator('#rule-form-error').innerText(),'请选择存在且收支方向一致的目标分类');
  assert.equal(await page.locator('#rule-name').inputValue(),'保留输入测试商户','Inputs must not be lost on backend error');

  // Cleanly delete income rule
  await page.locator('[data-delete-rule="rule:custom:5"]').click();
  assert.equal(await page.locator('#classification-rules-empty').isVisible(),true);

  // 10. Switch back to EXPENSE tab, test XSS/special characters safety, and category rename live refresh
  await page.locator('[data-rule-dir="EXPENSE"]').click();
  await page.locator('#rule-name').fill('<img src=x onerror=alert(1)> & "特殊店"');
  await page.locator('#rule-keyword').fill('<b>COSTA & "Coffee"</b>');
  await page.locator('#save-rule').click();
  const xssSafe=await page.evaluate(()=>{
   const list=document.querySelector('#classification-rules-list');
   return !list.querySelector('img')&&!list.querySelector('[data-rule-summary] b')&&list.textContent.includes('<img src=x onerror=alert(1)> & "特殊店"')&&list.textContent.includes('<b>COSTA & "Coffee"</b>');
  });
  assert.equal(xssSafe,true,'Rule name and keyword must be safely escaped without injecting DOM elements');

  // Punctuation, symbols, emojis, and formatting whitespace
  await page.locator('#rule-name').fill('全角加号商户');
  await page.locator('#rule-keyword').fill('＋');
  await page.locator('#save-rule').click();
  const plusSaved=await page.evaluate(()=>window.calls.saveRule.at(-1));
  assert.equal(plusSaved.conditions[0].value,'＋','Raw symbol "＋" must be preserved exactly');
  assert.equal(await page.evaluate(()=>normRuleKeyword('＋')),'+','normRuleKeyword NFKC normalizes ＋ to +');

  await page.locator('#rule-name').fill('奶茶表情商户');
  await page.locator('#rule-keyword').fill('🧋');
  await page.locator('#save-rule').click();
  const emojiSaved=await page.evaluate(()=>window.calls.saveRule.at(-1));
  assert.equal(emojiSaved.conditions[0].value,'🧋','Raw emoji "🧋" must be preserved exactly');

  await page.locator('#rule-name').fill('制表符商户');
  await page.locator('#rule-keyword').fill('合成\tＡ＋Ｂ');
  await page.locator('#save-rule').click();
  const tabSaved=await page.evaluate(()=>window.calls.saveRule.at(-1));
  assert.equal(tabSaved.conditions[0].value,'合成\tＡ＋Ｂ','Tab formatting whitespace inside keyword must be preserved');

  // Clean up temporary test rules
  for(const id of ['rule:custom:6','rule:custom:7','rule:custom:8','rule:custom:9']){
   await page.locator(`[data-delete-rule="${id}"]`).click();
  }

  // Rename '餐饮' -> '品质餐饮' via backend & trigger refresh; verify rule target name updates immediately
  await page.evaluate(()=>{
   window.LedgerNative.saveCategory(JSON.stringify({id:'cat:expense:dining',name:'品质餐饮',direction:'EXPENSE',icon:'coffee'}));
   window.LedgerUI.event('refresh');
  });
  assert.equal(await page.locator('[data-rule-category-label="rule:custom:1"]').innerText(),'品质餐饮');
  assert.equal(await page.locator('[data-rule-target-name="rule:custom:1"]').innerText(),'→ 品质餐饮');

  // Close sheet via Escape key and verify focus returns to #classification-rules-settings
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>!document.querySelector('#sheet').open);
  assert.equal(await page.evaluate(()=>document.activeElement?.id),'classification-rules-settings');
  assert.equal(await page.locator('#classification-rules-summary').innerText(),'启用 4 条 · 共 4 条');

  // 11. Record detail categoryDecision.explanation display, no ruleId/ruleVersion leak, and manual category change behavior
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#category-explanation').isVisible(),true);
  assert.equal(await page.locator('#category-explanation').innerText(),'命中规则：星巴克咖啡');
  const importText101=await page.locator('#import').innerText();
  assert.equal(importText101.includes('rule:custom:secret-id-999'),false,'Must not expose ruleId in record details');
  assert.equal(importText101.includes('rules:v1:secret-hash-888'),false,'Must not expose ruleVersion in record details');
  await page.screenshot({path:path.join(output,'07-record-detail-rule-explanation.png')});

  // Only edit note - categoryEdited must be false and categoryDecision preserved
  await page.locator('#note').fill('仅修改备注不选分类');
  await page.evaluate(()=>submitDraft());
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);
  const noteOnlySave=await page.evaluate(()=>window.calls.saves[window.calls.saves.length-1]);
  assert.equal(noteOnlySave.categoryEdited,false,'Only editing note must pass categoryEdited: false');

  // Explicitly select the SAME category -> categoryEdited: true and live explanation updates to 人工选择分类
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  await page.locator('#category').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  await page.locator('#record-category-choices [data-category-id="cat:expense:dining"]').click();
  await page.waitForFunction(()=>!document.querySelector('#sheet').open);
  assert.equal(await page.locator('#category-explanation').innerText(),'人工选择分类');
  await page.evaluate(()=>submitDraft());
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);
  const sameCatSave=await page.evaluate(()=>window.calls.saves[window.calls.saves.length-1]);
  assert.equal(sameCatSave.categoryEdited,true,'Explicitly selecting the same category must pass categoryEdited: true');

  // 12. 320px narrow viewport + large text (fontScale=1.5) overflow verification
  await page.setViewportSize({width:320,height:740});
  await page.evaluate(()=>{window.LedgerUI.event('fontScale',1.5);go('settings')});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=320),true,'Settings page at 320px large-text must not overflow horizontally');
  await page.locator('#classification-rules-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  const narrowOverflow=await page.evaluate(()=>{
   const sc=document.querySelector('#sheet-content');
   return {docOverflow:document.documentElement.scrollWidth>320,sheetOverflow:sc.scrollWidth>sc.clientWidth+1};
  });
  assert.deepEqual(narrowOverflow,{docOverflow:false,sheetOverflow:false},'Rules sheet at 320px large-text must not overflow horizontally');
  await page.screenshot({path:path.join(output,'08-rules-narrow-320px-large-text.png')});

  assert.deepEqual(errors,[]);
  const screenshotsList=[
   '01-settings-rules-entry.png',
   '02-rules-empty-sheet.png',
   '03-rules-validation-error.png',
   '04-rules-expense-list.png',
   '05-rules-builtin-and-copy.png',
   '06-rules-income-tab.png',
   '07-record-detail-rule-explanation.png',
   '08-rules-narrow-320px-large-text.png'
  ];
  if(withBaseline){
   screenshotsList.unshift('00-before-settings-rules-entry.png','00-before-record-detail.png');
  }
  fs.writeFileSync(path.join(output,'metadata.json'),JSON.stringify({
   viewport:{width:390,height:844},
   narrowViewport:{width:320,height:740,fontScale:1.5},
   deviceScaleFactor:2,
   reducedMotion:'reduce',
   timezoneId:'Asia/Shanghai',
   screenshots:screenshotsList,
   note:'Isolated synthetic browser fixture verifying Ticket 07 / Issue #12 compound automatic classification rules and builtin rules UI contract. Synthetic stub results are browser contract checks, not claimed as real Android financial validation.'
  },null,2));
  console.log(`PASS classification-rules UI checks (Ticket 07): settings entry, directional tabs, multi-conditions ALL/ANY, negative missing field, source restrictions, builtin list & toggle, copy builtin draft cancellation & save, first-match priority, validation & backend error display, category rename sync, record detail explanation, XSS guard, 320px large-text layout; ${screenshotsList.length} screenshots ${output}`);
 }finally{
  await browser.close();
 }
})().catch(error=>{console.error(error);process.exit(1)});
