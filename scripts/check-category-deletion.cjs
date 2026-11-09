'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url'),{spawnSync}=require('node:child_process');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const output=process.env.LEDGER_SCREENSHOT_DIR||path.resolve('output/t08-category-deletion');
const withBaselineComparison=process.argv.includes('--with-baseline-comparison')||process.env.LEDGER_COMPARE_BASELINE==='1';
const rootDir=path.resolve(__dirname,'..');

async function installCategoryDeletionFixture(page){
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
    {id:'cat:expense:xss',name:`<script>alert('xss')</script>"&'测试`,direction:'EXPENSE',icon:'coffee',order:3,isFallback:false},
    {id:'cat:expense:unused',name:'数码配件',direction:'EXPENSE',icon:'tag',order:4,isFallback:false},
    {id:'cat:expense:other',name:'其他',direction:'EXPENSE',icon:'ellipsis',order:5,isFallback:true}
   ],
   income:[
    {id:'cat:income:salary',name:'工资',direction:'INCOME',icon:'wallet',order:0,isFallback:false},
    {id:'cat:income:bonus',name:'奖金',direction:'INCOME',icon:'award',order:1,isFallback:false},
    {id:'cat:income:other',name:'其他',direction:'INCOME',icon:'ellipsis',order:2,isFallback:true}
   ]
  };
  let customRules=[
   {id:'rule:custom:dining',name:'星巴克咖啡',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'星巴克'}]},
   {id:'rule:custom:transit',name:'地铁通勤',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:transit',order:1,matchMode:'ALL',source:null,conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'地铁'}]}
  ];
  let builtinRules=[
   {id:'builtin:expense:dining',name:'内置餐饮',enabled:true,direction:'EXPENSE',categoryId:'cat:expense:dining',order:0,matchMode:'ANY',source:'BUILTIN',conditions:[{field:'MERCHANT',operator:'CONTAINS',value:'餐'}]}
  ];
  const queuedTasks=[
   {id:901,status:'QUEUED',source:'IMAGE',targetCategoryId:'cat:expense:dining'}
  ];
  const rows=[
   {
    id:101,uid:'rec-101',merchant:'星巴克臻选',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',
    amountCents:3800,direction:'EXPENSE',occurredAt:now-3600_000,state:'POSTED',source:'OCR',note:'拿铁',
    categoryDecision:{categoryId:'cat:expense:dining',source:'CUSTOM_RULE',manual:false,ruleId:'rule:custom:dining',explanation:'命中规则：星巴克咖啡',rule:{categoryId:'cat:expense:dining'}},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-3800,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:102,uid:'rec-102',merchant:'海底捞火锅',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',
    amountCents:12800,direction:'EXPENSE',occurredAt:now-7200_000,state:'POSTED',source:'MANUAL',note:'聚餐',
    categoryDecision:{categoryId:'cat:expense:dining',source:'MANUAL',manual:true,ruleId:null,explanation:'人工选择分类'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-12800,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:103,uid:'rec-103',merchant:'上海地铁通行',categoryId:'cat:expense:transit',category:'交通',categoryIcon:'bus',
    amountCents:400,direction:'EXPENSE',occurredAt:now-10800_000,state:'POSTED',source:'OCR',note:'通勤',
    categoryDecision:{categoryId:'cat:expense:transit',source:'CUSTOM_RULE',manual:false,ruleId:'rule:custom:transit',explanation:'命中规则：地铁通勤'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-400,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   },
   {
    id:104,uid:'rec-104',merchant:'11月薪资',categoryId:'cat:income:salary',category:'工资',categoryIcon:'wallet',
    amountCents:1200000,direction:'INCOME',occurredAt:now-14400_000,state:'POSTED',source:'MANUAL',note:'',
    categoryDecision:{categoryId:'cat:income:salary',source:'MANUAL',manual:true,explanation:'人工选择分类'},
    wallet:{accountUid:'card',accountName:'招商银行卡',appliedCents:1200000,eligibility:'ACTIVE',selectionSource:'MANUAL',revision:1}
   },
   {
    id:105,uid:'rec-105',merchant:'特殊测试商户',categoryId:'cat:expense:xss',category:`<script>alert('xss')</script>"&'测试`,categoryIcon:'coffee',
    amountCents:2500,direction:'EXPENSE',occurredAt:now-18000_000,state:'POSTED',source:'MANUAL',note:'转义核验',
    categoryDecision:{categoryId:'cat:expense:xss',source:'MANUAL',manual:true,explanation:'人工选择分类'},
    wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-2500,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
   }
  ];
  const accounts=[
   {id:1,uid:'wechat',name:'微信零钱',kind:'wechat',type:'ASSET',amountCents:50000,archived:false,revision:1},
   {id:2,uid:'card',name:'招商银行卡',kind:'bank_cmb',bankCode:'cmb',cardLast4:'0007',type:'ASSET',amountCents:300000,archived:false,revision:1}
  ];

  window.calls={previews:[],confirms:[],saves:[],saveCategory:[],reorderCategories:[]};
  window.simStale=false;
  window.simFailure=false;
  let revision=1,tokenSeq=1;

  function serializeDir(){
   const enrich=list=>list.slice().sort((a,b)=>a.order-b.order).map((c,i)=>{
    const refCount=rows.filter(r=>r.categoryId===c.id||(r.direction===c.direction&&r.category===c.name)).length+customRules.filter(r=>r.categoryId===c.id).length;
    return {...c,order:i,referenceCount:refCount,canDelete:!c.isFallback&&refCount===0};
   });
   return {expense:enrich(dir.expense),income:enrich(dir.income)};
  }

  function serializeRules(){
   return {
    formatVersion:1,
    version:`rules:v1:${revision}`,
    customRules:customRules.slice().sort((a,b)=>a.order-b.order||a.id.localeCompare(b.id)).map(r=>({...r,conditions:r.conditions.map(c=>({...c}))})),
    builtinRules:builtinRules.map(r=>({...r,conditions:r.conditions.map(c=>({...c}))}))
   };
  }

  function buildSummary(){
   const snapDir=serializeDir();
   const catMap=new Map();
   for(const r of rows){
    if(r.state!=='POSTED'||r.direction==='TRANSFER')continue;
    const dKey=r.direction==='INCOME'?'income':'expense';
    const catObj=snapDir[dKey].find(c=>c.id===r.categoryId)||snapDir[dKey].find(c=>c.name===r.category)||{id:r.categoryId||'',name:r.category||'其他',icon:r.categoryIcon||'ellipsis'};
    r.category=catObj.name;r.categoryIcon=catObj.icon;
    const k='category:'+catObj.name,cur=catMap.get(k)||{key:k,categoryId:catObj.id,icon:catObj.icon,name:catObj.name,direction:r.direction,amount:0,count:0};
    cur.amount+=r.amountCents;cur.count++;catMap.set(k,cur);
   }
   const expenseTotal=rows.filter(r=>r.state==='POSTED'&&r.direction==='EXPENSE').reduce((s,r)=>s+r.amountCents,0);
   const incomeTotal=rows.filter(r=>r.state==='POSTED'&&r.direction==='INCOME').reduce((s,r)=>s+r.amountCents,0);
   return {
    totals:{expense:String(expenseTotal),income:String(incomeTotal),expenseCount:rows.filter(r=>r.direction==='EXPENSE').length,incomeCount:rows.filter(r=>r.direction==='INCOME').length,totalCount:rows.length},
    previous:{expense:'5000',income:'1000000'},
    days:[{day:8,direction:'EXPENSE',amount:String(expenseTotal)},{day:8,direction:'INCOME',amount:String(incomeTotal)}],
    categories:[...catMap.values()].sort((a,b)=>b.amount-a.amount).map(c=>({...c,amount:String(c.amount)})),
    postedCount:rows.length
   };
  }

  function buildSnapshot(){
   const summary=buildSummary(),snapDir=serializeDir(),rulesSnap=serializeRules();
   return {
    ok:true,dataRevision:'rev-'+(revision++),viewKey:'vk-'+revision,
    records:rows.map(r=>({...r})),
    ledgerView:{hasMore:false,nextCursor:null,summary},
    recognitionTasks:queuedTasks.map(t=>({...t})),
    channels:{ocr:{enabled:false,permitted:false},sms:{enabled:false,permitted:false},notification:{enabled:false,permitted:false}},
    accounts,bankOptions:[{kind:'bank_cmb',label:'招商银行',icon:'bank-logos/cmb.svg'}],bankCodeOptions:[{code:'cmb',label:'招商银行'}],
    customCategories:{expense:snapDir.expense.map(c=>c.name),income:snapDir.income.map(c=>c.name)},
    categoryDirectory:snapDir,categoryIconOptions:iconOptions,classificationRules:rulesSnap,
    automationRunning:false,automatic:{enabled:false,applications:[]},screenshotStorage:{},captureHealth:{enabled:false,ready:false,shakeSelectedCount:2},
    walletSettings:{defaultAccountUid:'wechat',appBindings:{}}
   };
  }

  function getDeletionPreview(id){
   for(const key of ['expense','income']){
    const found=dir[key].find(c=>c.id===id);
    if(found){
     if(found.isFallback)return {ok:false,code:'CATEGORY_SYSTEM',error:'系统兜底分类不能删除'};
     const billCount=rows.filter(r=>r.categoryId===found.id||(r.direction===found.direction&&r.category===found.name)).length;
     const ruleCount=customRules.filter(r=>r.categoryId===found.id).length+builtinRules.filter(r=>r.categoryId===found.id).length;
     const queuedConfigurationCount=queuedTasks.filter(t=>t.targetCategoryId===found.id).length;
     const replacementCount=0;
     const requiresReplacement=(billCount+ruleCount+queuedConfigurationCount+replacementCount)>0;
     const replacements=dir[key].filter(c=>c.id!==found.id);
     return {
      ok:true,
      deletionPreview:{
       category:{...found,referenceCount:billCount+ruleCount,canDelete:!found.isFallback&&billCount===0&&ruleCount===0},
       billCount,ruleCount,queuedConfigurationCount,replacementCount,requiresReplacement,
       previewToken:`preview-token-${id}-${tokenSeq++}`,
       replacements:replacements.map(c=>({...c,canDelete:!c.isFallback}))
      }
     };
    }
   }
   return {ok:false,code:'CATEGORY_NOT_FOUND',error:'分类已不存在，请刷新后重试'};
  }

  window.LedgerNative={
   snapshot:()=>JSON.stringify(buildSnapshot()),
   snapshotFirst:()=>JSON.stringify(buildSnapshot()),
   snapshotView:()=>JSON.stringify(buildSnapshot()),
   recordDetails:id=>{const r=rows.find(x=>x.id===Number(id));return JSON.stringify(r?{ok:true,record:r,rawText:'合成识别原文',history:[]}:{ok:false,error:'记录不存在'})},
   getCategoryDirectory:()=>JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions}),
   classificationRules:()=>JSON.stringify({ok:true,classificationRules:serializeRules()}),
   previewCategoryDeletion:id=>{
    window.calls.previews.push(id);
    return JSON.stringify(getDeletionPreview(id));
   },
   confirmCategoryDeletion:raw=>{
    const p=JSON.parse(raw);
    window.calls.confirms.push(p);

    if(window.simStale){
     window.simStale=false;
     // Concurrent creation of a new bill referencing target
     const id=999+rows.length;
     rows.push({
      id,uid:'rec-'+id,merchant:'并发新增外卖',categoryId:p.id,category:'餐饮',categoryIcon:'utensils',
      amountCents:2200,direction:'EXPENSE',occurredAt:now,state:'POSTED',source:'MANUAL',note:'并发测试',
      categoryDecision:{categoryId:p.id,source:'MANUAL',manual:true,explanation:'人工选择分类'},
      wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-2200,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}
     });
     const fresh=getDeletionPreview(p.id);
     return JSON.stringify({
      ok:false,code:'CATEGORY_PREVIEW_STALE',
      error:'分类引用已变化，请核对最新影响后再确认删除',
      deletionPreview:fresh.deletionPreview
     });
    }

    if(window.simFailure){
     window.simFailure=false;
     return JSON.stringify({ok:false,code:'CATEGORY_DELETE_FAILED',error:'分类删除失败，所有更改均已回滚'});
    }

    let foundCat=null,dKey='expense';
    for(const key of ['expense','income']){
     const target=dir[key].find(c=>c.id===p.id);
     if(target){foundCat=target;dKey=key;break;}
    }
    if(!foundCat)return JSON.stringify({ok:false,code:'CATEGORY_NOT_FOUND',error:'分类已不存在，请刷新后重试'});
    if(foundCat.isFallback)return JSON.stringify({ok:false,code:'CATEGORY_SYSTEM',error:'系统兜底分类不能删除'});

    const refPreview=getDeletionPreview(p.id);
    if(refPreview.deletionPreview.requiresReplacement&&!p.replacementId){
     return JSON.stringify({ok:false,code:'CATEGORY_REPLACEMENT_REQUIRED',error:'该分类仍有引用，请选择同方向的替代分类'});
    }

    let repCat=null;
    if(p.replacementId){
     if(p.replacementId===p.id){
      return JSON.stringify({ok:false,code:'CATEGORY_REPLACEMENT_INVALID',error:'请选择存在且同方向的其他分类'});
     }
     repCat=dir[dKey].find(c=>c.id===p.replacementId);
     if(!repCat){
      return JSON.stringify({ok:false,code:'CATEGORY_REPLACEMENT_INVALID',error:'请选择存在且同方向的其他分类'});
     }
    }

    // Atomic migration:
    if(repCat){
     rows.forEach(r=>{
      if(r.categoryId===foundCat.id||(r.direction===foundCat.direction&&r.category===foundCat.name)){
       r.categoryId=repCat.id;
       r.category=repCat.name;
       r.categoryIcon=repCat.icon;
       if(r.categoryDecision){
        r.categoryDecision.categoryId=repCat.id;
        // Retain source/manual/explanation and nested rule
       }
      }
     });
     customRules.forEach(r=>{
      if(r.categoryId===foundCat.id)r.categoryId=repCat.id;
     });
     builtinRules.forEach(r=>{
      if(r.categoryId===foundCat.id)r.categoryId=repCat.id;
     });
     queuedTasks.forEach(t=>{
      if(t.targetCategoryId===foundCat.id)t.targetCategoryId=repCat.id;
     });
    }

    // Delete category
    const idx=dir[dKey].findIndex(c=>c.id===p.id);
    if(idx>=0)dir[dKey].splice(idx,1);

    const snapDir=serializeDir(),rulesSnap=serializeRules();
    return JSON.stringify({
     ok:true,
     categoryDirectory:snapDir,
     categoryIconOptions:iconOptions,
     classificationRules:rulesSnap
    });
   },
   saveCategory:raw=>{
    const p=JSON.parse(raw);
    const dirName=String(p.direction||'').toUpperCase();
    const list=dirName==='INCOME'?dir.income:dir.expense;
    if(p.id){
     const target=list.find(c=>c.id===p.id);
     if(target){target.name=p.name;target.icon=p.icon;}
    }
    const snapDir=serializeDir();
    return JSON.stringify({ok:true,categoryDirectory:snapDir,categoryIconOptions:iconOptions});
   },
   reorderCategories:(direction,rawIds)=>{
    return JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions});
   },
   statistics:period=>{
    const summary=buildSummary();
    return JSON.stringify({ok:true,period,rangeStart:now-86400_000*6,rangeEnd:now+86400_000,totalExpenseCents:summary.totals.expense,incomeCents:summary.totals.income,totals:summary.totals,categories:summary.categories,buckets:[{start:0,end:1,label:'本周',current:true,future:false,expenseCents:summary.totals.expense}]});
   },
   categoryRecords:(m,key,direction)=>{
    const summary=buildSummary();
    const matched=rows.filter(r=>r.direction===direction&&`category:${r.category}`===key);
    return JSON.stringify({ok:true,records:matched,hasMore:false,summary});
   },
   save:raw=>JSON.stringify({ok:true}),
   remember:()=>{}
  };
 });
}

(async()=>{
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({headless:true});
 try{
  // Optional Baseline comparison (5584f0b)
  if(withBaselineComparison){
   const baselineDir=path.join(output,'baseline-5584f0b');
   fs.mkdirSync(baselineDir,{recursive:true});
   const gitArchive=spawnSync('git',['archive','5584f0b','app/src/main/assets/web'],{cwd:rootDir});
   if(gitArchive.error||gitArchive.status!==0)throw(gitArchive.error||new Error('git archive failed'));
   const tarExtract=spawnSync('tar',['-x','-C',baselineDir],{input:gitArchive.stdout});
   if(tarExtract.error||tarExtract.status!==0)throw(tarExtract.error||new Error('tar extract failed'));

   const baselinePage=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),bErrors=[];
   baselinePage.on('pageerror',e=>bErrors.push(e.message));
   await baselinePage.route('https://**',r=>r.abort());
   await baselinePage.clock.install({time:new Date('2026-11-08T04:00:00Z')});
   await installCategoryDeletionFixture(baselinePage);
   await baselinePage.goto(pathToFileURL(path.join(baselineDir,'app/src/main/assets/web/index.html')).href);
   await baselinePage.evaluate(()=>document.fonts.ready);
   await baselinePage.evaluate(()=>go('settings'));
   await baselinePage.locator('#category-settings').click();
   await baselinePage.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
   // In 5584f0b: dining (referenced) has NO delete button; only unused has delete button
   assert.equal(await baselinePage.locator('[data-category-row="cat:expense:dining"] [data-delete-category]').count(),0);
   assert.equal(await baselinePage.locator('[data-category-row="cat:expense:unused"] [data-delete-category]').count(),1);
   assert.equal(await baselinePage.locator('[data-category-row="cat:expense:other"] [data-delete-category]').count(),0);
   await baselinePage.screenshot({path:path.join(output,'00-before-category-manager-expense.png')});
   assert.deepEqual(bErrors,[]);
   await baselinePage.close();
  }

  // Current UI testing
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2,reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('https://**',r=>r.abort());
  await page.clock.install({time:new Date('2026-11-08T04:00:00Z')});
  await installCategoryDeletionFixture(page);
  await page.goto(pathToFileURL(path.join(rootDir,'app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);

  // 1. Enter Settings -> Category Manager
  await page.evaluate(()=>go('settings'));
  await page.locator('#category-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  assert.equal(await page.locator('#sheet-title').innerText(),'分类管理');

  // Verify: Non-fallback categories (dining, transit, shopping, xss, unused) have delete button. Fallback (other) does NOT.
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"] [data-delete-category]').count(),1);
  assert.equal(await page.locator('[data-category-row="cat:expense:unused"] [data-delete-category]').count(),1);
  assert.equal(await page.locator('[data-category-row="cat:expense:other"] [data-delete-category]').count(),0);
  assert.match(await page.locator('[data-category-row="cat:expense:dining"] .category-manage-meta').innerText(),/已关联 3 笔账单 · 删除时将迁移引用/);
  assert.match(await page.locator('[data-category-row="cat:expense:other"] .category-manage-meta').innerText(),/系统兜底分类 · 不可删除或改名/);

  // Protected fallback direct preview rejection test
  const sysPreview=await page.evaluate(()=>JSON.parse(window.LedgerNative.previewCategoryDeletion('cat:expense:other')));
  assert.equal(sysPreview.ok,false);
  assert.equal(sysPreview.code,'CATEGORY_SYSTEM');
  assert.equal(sysPreview.error,'系统兜底分类不能删除');

  await page.screenshot({path:path.join(output,'01-category-manager-expense.png')});

  // 2. Click delete on referenced category '餐饮' (cat:expense:dining)
  await page.locator('[data-category-row="cat:expense:dining"] [data-delete-category]').click();
  await page.waitForSelector('#category-delete-preview-view');
  assert.match(await page.locator('#sheet-title').innerText(),/删除分类 · 餐饮/);
  assert.equal(await page.locator('#preview-bill-count').innerText(),'2');
  assert.equal(await page.locator('#preview-rule-count').innerText(),'2');
  assert.equal(await page.locator('#preview-task-count').innerText(),'1');
  assert.match(await page.locator('#category-delete-notice').innerText(),/金额、时间、方向、钱包与账户余额均保持不变/);
  assert.match(await page.locator('#category-delete-notice').innerText(),/操作不可单独撤销/);

  // Check same-direction replacements filtering:
  // Must have transit, shopping, xss, and other (fallback). Must NOT have dining (self) or salary/bonus (income).
  const repOptionValues=await page.locator('#category-replacement-select option').evaluateAll(opts=>opts.map(o=>o.value));
  assert.ok(repOptionValues.includes('cat:expense:transit'));
  assert.ok(repOptionValues.includes('cat:expense:shopping'));
  assert.ok(repOptionValues.includes('cat:expense:other'));
  assert.ok(!repOptionValues.includes('cat:expense:dining')); // Self excluded
  assert.ok(!repOptionValues.includes('cat:income:salary'));  // Direction excluded
  await page.screenshot({path:path.join(output,'02-referenced-deletion-preview.png')});

  // 3. Validation: Confirm without selecting replacement
  await page.locator('#confirm-category-delete').click();
  assert.equal(await page.locator('#category-delete-error').isVisible(),true);
  assert.match(await page.locator('#category-delete-error').innerText(),/该分类仍有引用，请选择同方向的替代分类/);
  assert.equal(await page.locator('#category-replacement-select').getAttribute('aria-invalid'),'true');
  const confirmCallsBefore=await page.evaluate(()=>window.calls.confirms.length);
  assert.equal(confirmCallsBefore,0); // Zero mutation calls

  // 4. Cancel preview: zero writes, focus restoration
  await page.locator('#cancel-category-delete').click();
  await page.waitForSelector('#category-manage-list');
  assert.equal(await page.locator('#sheet-title').innerText(),'分类管理');
  const confirmCallsAfterCancel=await page.evaluate(()=>window.calls.confirms.length);
  assert.equal(confirmCallsAfterCancel,0);
  // Category '餐饮' still exists
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"]').count(),1);

  // 5. Failure does not drop user input (失败不丢状态)
  await page.locator('[data-category-row="cat:expense:dining"] [data-delete-category]').click();
  await page.waitForSelector('#category-delete-preview-view');
  await page.locator('#category-replacement-select').selectOption('cat:expense:transit');
  await page.evaluate(()=>{window.simFailure=true;});
  await page.locator('#confirm-category-delete').click();
  assert.equal(await page.locator('#category-delete-error').isVisible(),true);
  assert.match(await page.locator('#category-delete-error').innerText(),/分类删除失败，所有更改均已回滚/);
  // Selected option is retained
  assert.equal(await page.locator('#category-replacement-select').inputValue(),'cat:expense:transit');

  // 6. Stale preview (CATEGORY_PREVIEW_STALE) & Re-confirmation without auto-retry
  await page.evaluate(()=>{window.simStale=true;});
  await page.locator('#confirm-category-delete').click();
  // Fresh preview delivered: billCount updated to 3
  await page.waitForSelector('#category-stale-alert');
  assert.match(await page.locator('#category-stale-alert').innerText(),/分类引用已变化/);
  assert.equal(await page.locator('#preview-bill-count').innerText(),'3');
  // Selected option retained
  assert.equal(await page.locator('#category-replacement-select').inputValue(),'cat:expense:transit');
  // Crucial: No automatic retry occurred
  const staleCallsCount=await page.evaluate(()=>window.calls.confirms.length);
  await page.waitForTimeout(300);
  const staleCallsCountAfterDelay=await page.evaluate(()=>window.calls.confirms.length);
  assert.equal(staleCallsCount,staleCallsCountAfterDelay); // Exactly 2 confirms called so far (1 failure + 1 stale)

  // Explicit reconfirmation by user
  await page.locator('#confirm-category-delete').click();
  await page.waitForSelector('#category-manage-list');
  await page.screenshot({path:path.join(output,'03-stale-reconfirmation-success.png')});

  // 7. Verify synchronization: directory, rules targets, records, manual
  // Directory: dining is gone
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"]').count(),0);
  // Rules targets: Open classification rules sheet
  await page.evaluate(()=>closeSheet(false));
  await page.locator('#classification-rules-settings').click();
  await page.waitForSelector('#classification-rules-list');
  // '星巴克咖啡' rule target should now be '交通'
  assert.match(await page.locator('[data-rule-row="rule:custom:dining"] [data-rule-target-name]').innerText(),/交通/);
  await page.screenshot({path:path.join(output,'04-rules-target-migrated.png')});
  await page.evaluate(()=>closeSheet(false));

  // Ledger record details: Record 101 category is now '交通', explanation retains original historical cause
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#category').innerText(),'交通');
  assert.equal(await page.locator('#category-explanation').innerText(),'命中规则：星巴克咖啡');
  await page.screenshot({path:path.join(output,'05-record-detail-migrated.png')});
  await page.evaluate(()=>cancelEditor(true));
  await page.waitForFunction(()=>!document.querySelector('#record-panel').open);

  // 8. Unreferenced category direct deletion (数码配件, cat:expense:unused)
  await page.evaluate(()=>go('settings'));
  await page.locator('#category-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  await page.locator('[data-category-row="cat:expense:unused"] [data-delete-category]').click();
  await page.waitForSelector('#category-delete-preview-view');
  assert.equal(await page.locator('#preview-bill-count').innerText(),'0');
  assert.equal(await page.locator('#preview-rule-count').innerText(),'0');
  assert.equal(await page.locator('#preview-task-count').innerText(),'0');
  assert.equal(await page.locator('#zero-reference-hint').isVisible(),true);
  assert.equal(await page.locator('#category-replacement-select').count(),0); // Replacement not required
  await page.screenshot({path:path.join(output,'06-unreferenced-deletion-preview.png')});

  await page.locator('#confirm-category-delete').click();
  await page.waitForSelector('#category-manage-list');
  // '数码配件' is now deleted
  assert.equal(await page.locator('[data-category-row="cat:expense:unused"]').count(),0);
  // Verify payload had replacementId: null
  const lastConfirm=await page.evaluate(()=>window.calls.confirms[window.calls.confirms.length-1]);
  assert.equal(lastConfirm.id,'cat:expense:unused');
  assert.equal(lastConfirm.replacementId,null);

  // 9. XSS guard (category with script tags, quotes and entities)
  await page.locator('[data-category-row="cat:expense:xss"] [data-delete-category]').click();
  await page.waitForSelector('#category-delete-preview-view');
  assert.equal(errors.length,0); // No alert or xss script executed
  // Target name text content is preserved without evaluating script
  assert.match(await page.locator('#delete-target-name').innerText(),/<script>alert\('xss'\)<\/script>"&'测试/);
  await page.screenshot({path:path.join(output,'07-xss-escaped-preview.png')});
  await page.locator('#cancel-category-delete').click();

  // 10. 320px viewport & Large-text (1.5x) responsive layout & focus containment
  await page.setViewportSize({width:320,height:740});
  await page.evaluate(()=>window.LedgerUI.event('fontScale',1.5));
  await page.locator('[data-category-row="cat:expense:xss"] [data-delete-category]').click();
  await page.waitForSelector('#category-delete-preview-view');
  const scrollWidth=await page.evaluate(()=>document.body.scrollWidth);
  assert.ok(scrollWidth<=320,`Horizontal overflow detected: scrollWidth=${scrollWidth} > 320`);
  const sheetContentScrollWidth=await page.evaluate(()=>document.querySelector('#sheet-content').scrollWidth);
  assert.ok(sheetContentScrollWidth<=320,`Sheet content overflow: scrollWidth=${sheetContentScrollWidth} > 320`);
  // Touch targets min-height >= 44px
  const confirmBox=await page.locator('#confirm-category-delete').boundingBox();
  const cancelBox=await page.locator('#cancel-category-delete').boundingBox();
  assert.ok(confirmBox.height>=44,`Confirm button height ${confirmBox.height} < 44`);
  assert.ok(cancelBox.height>=44,`Cancel button height ${cancelBox.height} < 44`);
  await page.screenshot({path:path.join(output,'08-320px-large-text-preview.png')});

  // Escape key closes sheet
  await page.keyboard.press('Escape');
  await page.waitForFunction(()=>!document.querySelector('#sheet').open);

  assert.deepEqual(errors,[]);
  console.log(`PASS category-deletion UI checks (Ticket 08): referenced category migration, zero-reference deletion, protected fallback guard, directional target filtering, stale reconfirmation without auto-retry, failure state retention, cancel zero writes, directory/rule/record synchronization, XSS guard, 320px large-text layout; screenshots saved to ${output}`);
 }finally{
  await browser.close();
 }
})().catch(e=>{console.error(e);process.exitCode=1;});
