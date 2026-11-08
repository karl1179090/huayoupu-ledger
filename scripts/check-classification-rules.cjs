'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const output=process.env.LEDGER_SCREENSHOT_DIR||path.resolve('output/t06-rules');

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
   {id:'builtin:expense:dining',name:'内置餐饮',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ANY',source:'BUILTIN',conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'餐'}]},
   {id:'builtin:expense:transit',name:'内置交通',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:transit',order:1,matchMode:'ANY',source:'BUILTIN',conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'地铁'}]}
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
  window.calls={saveRule:[],deleteRule:[],reorderRules:[],saveCategory:[],saves:[]};
  window.simError={saveRule:null,deleteRule:null,reorderRules:null};
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
    const kwVal=String(conds[0]?.value||'');
    if(conds.length!==1||conds[0]?.field!=='MERCHANT'||!['CONTAINS','EQUALS'].includes(conds[0]?.operator)||!normKeyword(kwVal))return JSON.stringify({ok:false,code:'RULE_INVALID',error:'请输入有效的商家匹配关键词'});
    const hasIllegalControl=Array.from(kwVal).some(c=>{const cp=c.codePointAt(0);return (cp<=31||(cp>=127&&cp<=159))&&!/\s/u.test(c)});
    if(hasIllegalControl)return JSON.stringify({ok:false,code:'RULE_INVALID',error:'商家关键词包含不支持的控制字符'});
    const targetList=dirName==='INCOME'?dir.income:dir.expense;
    const targetCat=targetList.find(c=>c.id===p.categoryId&&c.direction===dirName);
    if(!targetCat)return JSON.stringify({ok:false,code:'RULE_TARGET',error:'请选择存在且收支方向一致的目标分类'});
    if(p.id){
     const existing=customRules.find(r=>r.id===p.id);
     if(!existing)return JSON.stringify({ok:false,code:'RULE_NOT_FOUND',error:'规则已不存在，请刷新后重试'});
     const sameDir=existing.direction===dirName;
     existing.name=name;existing.enabled=p.enabled!==false;existing.direction=dirName;existing.categoryId=targetCat.id;
     existing.order=sameDir?existing.order:customRules.filter(r=>r.direction===dirName).length;
     existing.matchMode=p.matchMode||'ALL';existing.conditions=[{field:'MERCHANT',operator:conds[0].operator,value:kwVal}];
     ruleVersionSeq++;
     return JSON.stringify({ok:true,classificationRules:serializeRules()});
    }
    const created={
     id:`rule:custom:${nextRuleId++}`,name,enabled:p.enabled!==false,direction:dirName,categoryId:targetCat.id,
     order:customRules.filter(r=>r.direction===dirName).length,matchMode:p.matchMode||'ALL',source:null,
     conditions:[{field:'MERCHANT',operator:conds[0].operator,value:kwVal}],conflictReason:null
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
      const changedCat=existing.categoryId!==payload.categoryId||existing.direction!==payload.direction;
      existing.merchant=payload.merchant;existing.direction=payload.direction;existing.category=payload.category;existing.categoryId=payload.categoryId;
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
  await page.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);

  // 1. Settings entry & layout preservation check (including same-fixture, same-viewport before/after pair)
  await page.evaluate(()=>go('settings'));
  await page.evaluate(()=>{const btn=document.querySelector('#classification-rules-settings');if(btn)btn.hidden=true});
  await page.screenshot({path:path.join(output,'00-before-settings-rules-entry.png')});
  await page.evaluate(()=>{const btn=document.querySelector('#classification-rules-settings');if(btn)btn.hidden=false});
  assert.equal(await page.locator('.account-preferences > button').count(),2,'Existing .account-preferences > button count must stay 2');
  assert.equal(await page.locator('#classification-rules-settings').isVisible(),true);
  assert.equal(await page.locator('#classification-rules-summary').innerText(),'按商家自动归类');
  await page.screenshot({path:path.join(output,'01-settings-rules-entry.png')});

  // 2. Open Automatic Classification Rules sheet: verify empty state and scope note
  await page.locator('#classification-rules-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  assert.equal(await page.locator('#sheet-title').innerText(),'自动分类规则');
  assert.match(await page.locator('#classification-rules-note').innerText(),/首条命中的启用规则生效/);
  assert.match(await page.locator('#classification-rules-note').innerText(),/仅影响后续新识别账单，不会修改已有账单/);
  assert.equal(await page.locator('#classification-rules-empty').isVisible(),true);
  await page.screenshot({path:path.join(output,'02-rules-empty-sheet.png')});

  // 3. Validation: empty name, whitespace-only keyword, non-whitespace control char keyword, and invalid cross-direction category block saving
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

  // Inject an income category into #rule-category while direction is EXPENSE -> must block save
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
  await page.screenshot({path:path.join(output,'03-rules-validation-error.png')});

  // 4. Add two EXPENSE rules (CONTAINS and EQUALS) & verify condition summary, target name, and first-match priority
  await page.locator('#rule-category').selectOption('cat:expense:dining');
  await page.locator('#rule-name').fill('星巴克咖啡');
  await page.locator('#rule-operator').selectOption('CONTAINS');
  await page.locator('#rule-keyword').fill('星巴克');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),1);
  assert.deepEqual(await page.evaluate(()=>window.calls.saveRule[0]),{
   name:'星巴克咖啡',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ALL',source:null,
   conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'星巴克'}]
  });

  await page.locator('#rule-name').fill('星巴克专星送精准');
  await page.locator('#rule-category').selectOption('cat:expense:shopping');
  await page.locator('#rule-operator').selectOption('EQUALS');
  await page.locator('#rule-keyword').fill('星巴克专星送');
  await page.locator('#save-rule').click();
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),2);

  const initialRuleIds=await page.locator('#classification-rules-list [data-rule-row]').evaluateAll(els=>els.map(e=>e.dataset.ruleRow));
  assert.deepEqual(initialRuleIds,['rule:custom:1','rule:custom:2']);
  assert.equal(await page.locator('[data-rule-summary="rule:custom:1"]').innerText(),'商家包含「星巴克」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:2"]').innerText(),'商家等于「星巴克专星送」');
  assert.equal(await page.locator('[data-rule-category-label="rule:custom:1"]').innerText(),'餐饮');
  assert.equal(await page.locator('[data-rule-category-label="rule:custom:2"]').innerText(),'购物');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:1');

  // 5. Reorder rules (move rule:custom:2 up to index 0) -> priority badge moves to rule:custom:2 & focus stays inside sheet
  await page.locator('[data-rule-up="rule:custom:2"]').click();
  const reorderedIds=await page.locator('#classification-rules-list [data-rule-row]').evaluateAll(els=>els.map(e=>e.dataset.ruleRow));
  assert.deepEqual(reorderedIds,['rule:custom:2','rule:custom:1']);
  assert.deepEqual(await page.evaluate(()=>window.calls.reorderRules.at(-1)),{direction:'EXPENSE',ids:['rule:custom:2','rule:custom:1']});
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:2');
  assert.equal(await page.evaluate(()=>document.querySelector('#sheet').contains(document.activeElement)),true);

  // 6. Toggle enable/disable on top rule -> first-match priority shifts to next enabled rule
  await page.locator('[data-toggle-rule="rule:custom:2"]').click();
  assert.equal(await page.locator('[data-toggle-rule="rule:custom:2"]').getAttribute('aria-checked'),'false');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:1');
  await page.locator('[data-toggle-rule="rule:custom:2"]').click();
  assert.equal(await page.locator('[data-toggle-rule="rule:custom:2"]').getAttribute('aria-checked'),'true');
  assert.equal(await page.locator('[data-rule-first-priority]').getAttribute('data-rule-first-priority'),'rule:custom:2');

  // 7. Edit existing rule
  await page.locator('[data-edit-rule="rule:custom:1"]').click();
  assert.match(await page.locator('#rule-editor-title').innerText(),/编辑规则 · 星巴克咖啡/);
  assert.equal(await page.locator('#rule-name').inputValue(),'星巴克咖啡');
  await page.locator('#rule-name').fill('星巴克臻选饮品');
  await page.locator('#rule-keyword').fill('星巴克臻选');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('[data-rule-row="rule:custom:1"]').getAttribute('data-rule-name'),'星巴克臻选饮品');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:1"]').innerText(),'商家包含「星巴克臻选」');
  await page.screenshot({path:path.join(output,'04-rules-expense-list.png')});

  // 8. Direction switch resets target category options to valid categories of that direction
  await page.locator('#rule-direction').selectOption('INCOME');
  const incomeCategoryOptions=await page.locator('#rule-category option').evaluateAll(els=>els.map(e=>e.value));
  assert.deepEqual(incomeCategoryOptions,['cat:income:salary','cat:income:bonus','cat:income:gift','cat:income:refund','cat:income:other']);
  assert.equal(await page.locator('#rule-category').inputValue(),'cat:income:salary');

  await page.locator('#rule-name').fill('兼职稿酬自动归类');
  await page.locator('#rule-category').selectOption('cat:income:bonus');
  await page.locator('#rule-operator').selectOption('CONTAINS');
  await page.locator('#rule-keyword').fill('稿酬');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('[data-rule-row="rule:custom:3"]').getAttribute('data-rule-name'),'兼职稿酬自动归类');
  assert.equal(await page.locator('[data-rule-category-label="rule:custom:3"]').innerText(),'奖金');
  await page.screenshot({path:path.join(output,'05-rules-income-tab.png')});

  // 9. Backend error display (RULE_TARGET / RULE_NOT_FOUND)
  await page.evaluate(()=>{window.simError.saveRule={code:'RULE_TARGET',error:'请选择存在且收支方向一致的目标分类'}});
  await page.locator('#rule-name').fill('后端报错测试');
  await page.locator('#rule-keyword').fill('测试商户');
  await page.locator('#save-rule').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.equal(await page.locator('#rule-form-error').innerText(),'请选择存在且收支方向一致的目标分类');

  await page.evaluate(()=>{window.simError.deleteRule={code:'RULE_NOT_FOUND',error:'规则已不存在，请刷新后重试'}});
  await page.locator('[data-delete-rule="rule:custom:3"]').click();
  assert.equal(await page.locator('#rule-form-error').isVisible(),true);
  assert.equal(await page.locator('#rule-form-error').innerText(),'规则已不存在，请刷新后重试');

  // Now delete income rule cleanly
  await page.locator('[data-delete-rule="rule:custom:3"]').click();
  assert.equal(await page.locator('#classification-rules-empty').isVisible(),true);

  // 10. Switch back to EXPENSE tab, test XSS/special chars safety, and test target category rename live refresh
  await page.locator('[data-rule-dir="EXPENSE"]').click();
  await page.locator('#rule-name').fill('<img src=x onerror=alert(1)> & "特殊店"');
  await page.locator('#rule-keyword').fill('<b>COSTA & "Coffee"</b>');
  await page.locator('#save-rule').click();
  const xssSafe=await page.evaluate(()=>{
   const list=document.querySelector('#classification-rules-list');
   return !list.querySelector('img')&&!list.querySelector('[data-rule-summary] b')&&list.textContent.includes('<img src=x onerror=alert(1)> & "特殊店"')&&list.textContent.includes('<b>COSTA & "Coffee"</b>');
  });
  assert.equal(xssSafe,true,'Rule name and keyword must be safely escaped without injecting DOM elements');

  // 10.1 Verify punctuation, symbols, emojis, and formatting whitespace are valid keywords, raw value preserved, and A+B != A-B
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
  assert.equal(await page.evaluate(()=>normRuleKeyword('🧋')),'🧋');

  await page.locator('#rule-name').fill('加法商户');
  await page.locator('#rule-keyword').fill('A+B');
  await page.locator('#save-rule').click();

  await page.locator('#rule-name').fill('减法商户');
  await page.locator('#rule-keyword').fill('A-B');
  await page.locator('#save-rule').click();

  const distinctCheck=await page.evaluate(()=>({
   normPlus: normRuleKeyword('A+B'),
   normMinus: normRuleKeyword('A-B'),
   notEqual: normRuleKeyword('A+B') !== normRuleKeyword('A-B')
  }));
  assert.equal(distinctCheck.normPlus,'a+b');
  assert.equal(distinctCheck.normMinus,'a-b');
  assert.equal(distinctCheck.notEqual,true,'Punctuation/symbol operators A+B and A-B must remain distinct and never be conflated');

  // Verify tab character is preserved as formatting whitespace
  await page.locator('#rule-name').fill('制表符商户');
  await page.locator('#rule-keyword').fill('合成\tＡ＋Ｂ');
  await page.locator('#save-rule').click();
  const tabSaved=await page.evaluate(()=>window.calls.saveRule.at(-1));
  assert.equal(tabSaved.conditions[0].value,'合成\tＡ＋Ｂ','Tab formatting whitespace inside keyword must be preserved');

  // Verify summaries rendered in DOM show raw symbols accurately
  assert.equal(await page.locator('[data-rule-summary="rule:custom:5"]').innerText(),'商家包含「＋」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:6"]').innerText(),'商家包含「🧋」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:7"]').innerText(),'商家包含「A+B」');
  assert.equal(await page.locator('[data-rule-summary="rule:custom:8"]').innerText(),'商家包含「A-B」');

  // Remove the 5 temporary symbol test rules so subsequent assertions remain clean
  for(const id of ['rule:custom:5','rule:custom:6','rule:custom:7','rule:custom:8','rule:custom:9']){
   await page.locator(`[data-delete-rule="${id}"]`).click();
  }

  // Rename '餐饮' (cat:expense:dining) -> '品质餐饮' via backend & trigger refresh; verify rule target name updates immediately
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
  assert.equal(await page.locator('#classification-rules-summary').innerText(),'启用 3 条 · 共 3 条');

  // 11. Record detail categoryDecision.explanation display, no ruleId/ruleVersion leak, and manual category change behavior
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  await page.evaluate(()=>{document.querySelector('.toast')?.classList.remove('show');const ce=document.querySelector('#category-explanation');if(ce)ce.hidden=true});
  await page.screenshot({path:path.join(output,'00-before-record-detail.png')});
  await page.evaluate(()=>{const ce=document.querySelector('#category-explanation');if(ce)ce.hidden=false});
  assert.equal(await page.locator('#category-explanation').isVisible(),true);
  assert.equal(await page.locator('#category-explanation').innerText(),'命中规则：星巴克咖啡');
  const importText101=await page.locator('#import').innerText();
  assert.equal(importText101.includes('rule:custom:secret-id-999'),false,'Must not expose ruleId in record details');
  assert.equal(importText101.includes('rules:v1:secret-hash-888'),false,'Must not expose ruleVersion in record details');
  await page.screenshot({path:path.join(output,'06-record-detail-rule-explanation.png')});
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);

  await page.evaluate(()=>openRecord(102));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#category-explanation').innerText(),'内置规则：交通');
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);

  const ruleSaveCountBeforeManualEdit=await page.evaluate(()=>window.calls.saveRule.length);
  await page.evaluate(()=>openRecord(103));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#category-explanation').innerText(),'未命中规则，归入其他');
  await page.locator('#category').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  await page.locator('#record-category-choices [data-category="购物"]').click();
  await page.waitForFunction(()=>!document.querySelector('#sheet').open);
  assert.equal(await page.locator('#category-explanation').innerText(),'人工选择分类');
  await page.evaluate(()=>submitDraft());
  assert.equal(await page.evaluate(()=>window.calls.saveRule.length),ruleSaveCountBeforeManualEdit,'Manual category edit must never silently create a permanent rule');

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
  await page.screenshot({path:path.join(output,'07-rules-narrow-320px-large-text.png')});

  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(output,'metadata.json'),JSON.stringify({
   viewport:{width:390,height:844},
   narrowViewport:{width:320,height:740,fontScale:1.5},
   deviceScaleFactor:2,
   reducedMotion:'reduce',
   timezoneId:'Asia/Shanghai',
   screenshots:[
    '00-before-settings-rules-entry.png',
    '01-settings-rules-entry.png',
    '02-rules-empty-sheet.png',
    '03-rules-validation-error.png',
    '04-rules-expense-list.png',
    '05-rules-income-tab.png',
    '00-before-record-detail.png',
    '06-record-detail-rule-explanation.png',
    '07-rules-narrow-320px-large-text.png'
   ],
   note:'Isolated synthetic browser fixture verifying Ticket 06 / Issue #11 automatic classification rules UI contract.'
  },null,2));
  console.log('PASS classification-rules UI checks: settings entry, directional tabs, add/edit/toggle/reorder/delete, validation & backend error display, direction switch target reset, category rename sync, record detail explanation without ruleId/ruleVersion leak, XSS guard, 320px large-text layout; 9 screenshots '+output);
 }finally{
  await browser.close();
 }
})().catch(error=>{console.error(error);process.exit(1)});
