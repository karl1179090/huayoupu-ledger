'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const output=process.env.LEDGER_SCREENSHOT_DIR||path.resolve('output/t02-category-directory');

async function installCategoryFixture(page){
 await page.addInitScript(()=>{
  const now=new Date('2026-11-06T04:00:00Z').getTime();
  const iconOptions=[
   {key:'utensils',label:'餐饮'},{key:'bus',label:'交通'},{key:'bag-shopping',label:'购物'},{key:'gamepad',label:'娱乐'},
   {key:'house',label:'居住'},{key:'plus',label:'医疗'},{key:'graduation-cap',label:'教育'},{key:'shirt',label:'服饰'},
   {key:'basket-shopping',label:'日用'},{key:'phone',label:'通讯'},{key:'gift',label:'礼金'},{key:'ellipsis',label:'其他'},
   {key:'wallet',label:'工资'},{key:'award',label:'奖金'},{key:'rotate-left',label:'退款'},{key:'tag',label:'标签'},
   {key:'heart',label:'关怀'},{key:'paw',label:'宠物'},{key:'plane',label:'旅行'},{key:'coffee',label:'饮品'},
   {key:'car',label:'汽车'},{key:'book',label:'学习'},{key:'briefcase',label:'办公'},{key:'coins',label:'理财'},
   {key:'music',label:'影音'},{key:'dumbbell',label:'运动'}
  ];
  const dir={
   expense:[
    {id:'cat:expense:dining',name:'餐饮',direction:'EXPENSE',icon:'utensils',order:0,isFallback:false},
    {id:'cat:expense:transit',name:'交通',direction:'EXPENSE',icon:'bus',order:1,isFallback:false},
    {id:'cat:expense:shopping',name:'购物',direction:'EXPENSE',icon:'bag-shopping',order:2,isFallback:false},
    {id:'cat:expense:migrated_long',name:'餐饮美食',direction:'EXPENSE',icon:'utensils',order:3,isFallback:false},
    {id:'cat:expense:migrated_slash',name:'饮食/咖啡',direction:'EXPENSE',icon:'coffee',order:4,isFallback:false},
    {id:'cat:expense:gift',name:'礼金',direction:'EXPENSE',icon:'gift',order:5,isFallback:false},
    {id:'cat:expense:unused',name:'数码配件',direction:'EXPENSE',icon:'tag',order:6,isFallback:false},
    {id:'cat:expense:race_ref',name:'待删竞态分类',direction:'EXPENSE',icon:'book',order:7,isFallback:false},
    {id:'cat:expense:other',name:'其他',direction:'EXPENSE',icon:'ellipsis',order:8,isFallback:true}
   ],
   income:[
    {id:'cat:income:salary',name:'工资',direction:'INCOME',icon:'wallet',order:0,isFallback:false},
    {id:'cat:income:bonus',name:'奖金',direction:'INCOME',icon:'award',order:1,isFallback:false},
    {id:'cat:income:gift',name:'礼金',direction:'INCOME',icon:'gift',order:2,isFallback:false},
    {id:'cat:income:refund',name:'退款',direction:'INCOME',icon:'rotate-left',order:3,isFallback:false},
    {id:'cat:income:legacy_other',name:'其他收入',direction:'INCOME',icon:'coins',order:4,isFallback:false},
    {id:'cat:income:other',name:'其他',direction:'INCOME',icon:'ellipsis',order:5,isFallback:true}
   ]
  };
  const rows=[
   {id:101,uid:'rec-101',merchant:'星巴克臻选',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',amountCents:3800,direction:'EXPENSE',occurredAt:now-3600_000,state:'POSTED',source:'MANUAL',note:'早餐拿铁',wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-3800,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}},
   {id:106,uid:'rec-106',merchant:'海底捞火锅',categoryId:'cat:expense:dining',category:'餐饮',categoryIcon:'utensils',amountCents:12800,direction:'EXPENSE',occurredAt:now-5400_000,state:'POSTED',source:'MANUAL',note:'聚餐',wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-12800,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}},
   {id:102,uid:'rec-102',merchant:'老乡鸡外卖',categoryId:'cat:expense:migrated_long',category:'餐饮美食',categoryIcon:'utensils',amountCents:2650,direction:'EXPENSE',occurredAt:now-7200_000,state:'POSTED',source:'IMAGE',note:'历史长名称分类',wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-2650,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}},
   {id:103,uid:'rec-103',merchant:'Manner Coffee',categoryId:'cat:expense:migrated_slash',category:'饮食/咖啡',categoryIcon:'coffee',amountCents:1500,direction:'EXPENSE',occurredAt:now-10800_000,state:'POSTED',source:'MANUAL',note:'CSV扁平导入',wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-1500,eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}},
   {id:104,uid:'rec-104',merchant:'11月薪资',categoryId:'cat:income:salary',category:'工资',categoryIcon:'wallet',amountCents:1200000,direction:'INCOME',occurredAt:now-14400_000,state:'POSTED',source:'MANUAL',note:'',wallet:{accountUid:'card',accountName:'招商银行卡',appliedCents:1200000,eligibility:'ACTIVE',selectionSource:'MANUAL',revision:1}},
   {id:105,uid:'rec-105',merchant:'本人互转',category:'转账',amountCents:50000,direction:'TRANSFER',occurredAt:now-18000_000,state:'POSTED',source:'MANUAL',note:'',wallet:{eligibility:'ACTIVE',revision:1},transfer:{fromAccountUid:'card',toAccountUid:'wechat',feeCents:200,feeMode:'EXTRA',from:{accountName:'招商银行卡'},to:{accountName:'微信零钱'}}}
  ];
  const accounts=[
   {id:1,uid:'wechat',name:'微信零钱',kind:'wechat',type:'ASSET',amountCents:50000,archived:false,revision:1},
   {id:2,uid:'card',name:'招商银行卡',kind:'bank_cmb',bankCode:'cmb',cardLast4:'0007',type:'ASSET',amountCents:300000,archived:false,revision:1}
  ];
  window.calls={saves:[],saveCategory:[],reorderCategories:[],removeCategory:[]};
  let revision=1,nextCatSeq=1;
  const norm=s=>String(s||'').normalize('NFKC').trim().toLowerCase();
  function serializeDir(){
   const enrich=list=>list.slice().sort((a,b)=>a.order-b.order).map((c,i)=>{
    const refCount=rows.filter(r=>r.categoryId===c.id||(r.direction===c.direction&&r.category===c.name)).length;
    return {...c,order:i,referenceCount:refCount,canDelete:!c.isFallback&&refCount===0};
   });
   return {expense:enrich(dir.expense),income:enrich(dir.income)};
  }
  function buildSummary(){
   const snapDir=serializeDir();
   const catMap=new Map();
   for(const r of rows){
    if(r.state!=='POSTED')continue;
    if(r.direction==='TRANSFER'){
     const fee=Number(r.transfer?.feeCents||0);
     if(fee>0){
      const k='system:transfer_fee',cur=catMap.get(k)||{key:k,name:'转账手续费',direction:'EXPENSE',amount:0,count:0};
      cur.amount+=fee;cur.count++;catMap.set(k,cur);
     }
     continue;
    }
    const dKey=r.direction==='INCOME'?'income':'expense';
    const catObj=snapDir[dKey].find(c=>c.id===r.categoryId)||snapDir[dKey].find(c=>c.name===r.category)||{id:r.categoryId||'',name:r.category||'其他',icon:r.categoryIcon||'ellipsis'};
    r.category=catObj.name;r.categoryIcon=catObj.icon;
    const k='category:'+catObj.name,cur=catMap.get(k)||{key:k,categoryId:catObj.id,icon:catObj.icon,name:catObj.name,direction:r.direction,amount:0,count:0};
    cur.amount+=r.amountCents;cur.count++;catMap.set(k,cur);
   }
   const expenseTotal=rows.filter(r=>r.state==='POSTED').reduce((s,r)=>s+(r.direction==='EXPENSE'?r.amountCents:r.direction==='TRANSFER'?Number(r.transfer?.feeCents||0):0),0);
   const incomeTotal=rows.filter(r=>r.state==='POSTED'&&r.direction==='INCOME').reduce((s,r)=>s+r.amountCents,0);
   return {
    totals:{expense:String(expenseTotal),income:String(incomeTotal),expenseCount:rows.filter(r=>r.direction==='EXPENSE').length,incomeCount:rows.filter(r=>r.direction==='INCOME').length,totalCount:rows.length},
    previous:{expense:'5000',income:'1000000'},
    days:[{day:6,direction:'EXPENSE',amount:String(expenseTotal)},{day:6,direction:'INCOME',amount:String(incomeTotal)}],
    categories:[...catMap.values()].sort((a,b)=>b.amount-a.amount).map(c=>({...c,amount:String(c.amount)})),
    postedCount:rows.length
   };
  }
  function buildSnapshot(){
   const summary=buildSummary(),snapDir=serializeDir();
   return {
    ok:true,dataRevision:'rev-'+(revision++),viewKey:'vk-'+revision,
    records:rows.map(r=>({...r})),
    ledgerView:{hasMore:false,nextCursor:null,summary},
    recognitionTasks:[],channels:{ocr:{enabled:false,permitted:false},sms:{enabled:false,permitted:false},notification:{enabled:false,permitted:false}},
    accounts,bankOptions:[{kind:'bank_cmb',label:'招商银行',icon:'bank-logos/cmb.svg'}],bankCodeOptions:[{code:'cmb',label:'招商银行'}],
    customCategories:{expense:snapDir.expense.map(c=>c.name),income:snapDir.income.map(c=>c.name)},
    categoryDirectory:snapDir,categoryIconOptions:iconOptions,
    automationRunning:false,automatic:{enabled:false,applications:[]},screenshotStorage:{},captureHealth:{enabled:false,ready:false,shakeSelectedCount:2},
    walletSettings:{defaultAccountUid:'wechat',appBindings:{}}
   };
  }
  window.LedgerNative={
   snapshot:()=>JSON.stringify(buildSnapshot()),
   snapshotFirst:()=>JSON.stringify(buildSnapshot()),
   snapshotView:()=>JSON.stringify(buildSnapshot()),
   recordDetails:id=>{const r=rows.find(x=>x.id===Number(id));return JSON.stringify(r?{ok:true,record:r,rawText:'合成识别原文',history:[]}:{ok:false,error:'记录不存在'})},
   getCategoryDirectory:()=>JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions}),
   saveCategory:raw=>{
    const p=JSON.parse(raw);window.calls.saveCategory.push(p);
    const dirName=String(p.direction||'').toUpperCase();
    if(dirName!=='EXPENSE'&&dirName!=='INCOME')return JSON.stringify({ok:false,code:'CATEGORY_DIRECTION',error:'分类方向无效'});
    const list=dirName==='INCOME'?dir.income:dir.expense;
    const name=String(p.name||'').trim();
    if(!name||name.length>40||/[\u0000-\u001F\u007F]/.test(name))return JSON.stringify({ok:false,code:'CATEGORY_INVALID',error:'分类名称长度须在 1～40 字之间'});
    if(!iconOptions.some(o=>o.key===p.icon))return JSON.stringify({ok:false,code:'CATEGORY_INVALID',error:'分类图标无效'});
    if(p.id){
     const target=list.find(c=>c.id===p.id);
     if(!target)return JSON.stringify({ok:false,code:'CATEGORY_NOT_FOUND',error:'分类不存在'});
     if(target.isFallback&&name!==target.name)return JSON.stringify({ok:false,code:'CATEGORY_SYSTEM',error:'系统兜底分类「其他」不可改名'});
     if(list.some(c=>c.id!==p.id&&norm(c.name)===norm(name)))return JSON.stringify({ok:false,code:'CATEGORY_DUPLICATE',error:'同方向下已存在同名分类'});
     const oldName=target.name;target.name=name;target.icon=p.icon;
     rows.forEach(r=>{if(r.categoryId===target.id||(r.direction===dirName&&r.category===oldName)){r.categoryId=target.id;r.category=name;r.categoryIcon=p.icon}});
     const snapDir=serializeDir();
     return JSON.stringify({ok:true,category:snapDir[dirName==='INCOME'?'income':'expense'].find(c=>c.id===target.id),categoryDirectory:snapDir,categoryIconOptions:iconOptions});
    }
    if(list.some(c=>norm(c.name)===norm(name)))return JSON.stringify({ok:false,code:'CATEGORY_DUPLICATE',error:'同方向下已存在同名分类'});
    const created={id:`cat:${dirName.toLowerCase()}:custom_${nextCatSeq++}`,name,direction:dirName,icon:p.icon,order:list.length,isFallback:false};
    list.push(created);
    const snapDir=serializeDir();
    return JSON.stringify({ok:true,category:snapDir[dirName==='INCOME'?'income':'expense'].find(c=>c.id===created.id),categoryDirectory:snapDir,categoryIconOptions:iconOptions});
   },
   reorderCategories:(direction,rawIds)=>{
    const dirName=String(direction||'').toUpperCase(),ids=JSON.parse(rawIds);
    window.calls.reorderCategories.push({direction:dirName,ids});
    const list=dirName==='INCOME'?dir.income:dir.expense;
    if(!Array.isArray(ids)||ids.length!==list.length||new Set(ids).size!==list.length||!ids.every(id=>list.some(c=>c.id===id)))return JSON.stringify({ok:false,code:'CATEGORY_INVALID',error:'分类排序列表不完整'});
    ids.forEach((id,idx)=>{const item=list.find(c=>c.id===id);if(item)item.order=idx});
    list.sort((a,b)=>a.order-b.order);
    return JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions});
   },
   removeCategory:id=>{
    window.calls.removeCategory.push(id);
    if(id==='cat:expense:race_ref'){
     rows.push({id:999,uid:'rec-999',merchant:'并发新账单',categoryId:'cat:expense:race_ref',category:'待删竞态分类',categoryIcon:'book',amountCents:1000,direction:'EXPENSE',occurredAt:now,state:'POSTED',source:'MANUAL',note:''});
     return JSON.stringify({ok:false,code:'CATEGORY_REFERENCED',error:'该分类已关联 1 笔账单，请先处理关联账单后再删除',referenceCount:1});
    }
    for(const key of ['expense','income']){
     const idx=dir[key].findIndex(c=>c.id===id);
     if(idx>=0){
      const target=dir[key][idx];
      if(target.isFallback)return JSON.stringify({ok:false,code:'CATEGORY_SYSTEM',error:'系统兜底分类不可删除'});
      const refCount=rows.filter(r=>r.categoryId===target.id||(r.direction===target.direction&&r.category===target.name)).length;
      if(refCount>0)return JSON.stringify({ok:false,code:'CATEGORY_REFERENCED',error:`该分类已关联 ${refCount} 笔账单，暂不可删除`,referenceCount:refCount});
      dir[key].splice(idx,1);
      return JSON.stringify({ok:true,categoryDirectory:serializeDir(),categoryIconOptions:iconOptions});
     }
    }
    return JSON.stringify({ok:false,code:'CATEGORY_NOT_FOUND',error:'分类不存在'});
   },
   save:raw=>{
    const payload=JSON.parse(raw);window.calls.saves.push(payload);
    if(payload.direction!=='TRANSFER'){
     const list=payload.direction==='INCOME'?dir.income:dir.expense;
     const found=list.find(c=>c.id===payload.categoryId)||list.find(c=>c.name===payload.category);
     if(!found)return JSON.stringify({ok:false,error:'所选分类与收支方向不匹配'});
     payload.categoryId=found.id;payload.category=found.name;payload.categoryIcon=found.icon;
    }
    if(payload.id){
     const existing=rows.find(r=>r.id===Number(payload.id));
     if(existing){existing.merchant=payload.merchant;existing.direction=payload.direction;existing.category=payload.category;existing.categoryId=payload.direction==='TRANSFER'?null:payload.categoryId;existing.categoryIcon=payload.direction==='TRANSFER'?'':payload.categoryIcon;existing.amountCents=Math.round(Number(payload.amount)*100);existing.note=payload.note||''}
     return JSON.stringify({ok:true,id:payload.id,state:'POSTED'});
    }
    const id=200+rows.length;
    rows.unshift({id,uid:'rec-'+id,merchant:payload.merchant||payload.category,category:payload.category,categoryId:payload.direction==='TRANSFER'?null:payload.categoryId,categoryIcon:payload.direction==='TRANSFER'?'':payload.categoryIcon,amountCents:Math.round(Number(payload.amount)*100),direction:payload.direction,occurredAt:payload.occurredAt||now,state:'POSTED',source:'MANUAL',note:payload.note||'',wallet:{accountUid:'wechat',accountName:'微信零钱',appliedCents:-Math.round(Number(payload.amount)*100),eligibility:'ACTIVE',selectionSource:'DEFAULT',revision:1}});
    return JSON.stringify({ok:true,id,state:'POSTED'});
   },
   statistics:period=>{
    const summary=buildSummary();
    return JSON.stringify({ok:true,period,rangeStart:now-86400_000*6,rangeEnd:now+86400_000,totalExpenseCents:summary.totals.expense,incomeCents:summary.totals.income,totals:summary.totals,categories:summary.categories,buckets:[{start:0,end:1,label:'本周',current:true,future:false,expenseCents:summary.totals.expense}]});
   },
   categoryRecords:(m,key,direction)=>{
    const summary=buildSummary();
    const matched=rows.filter(r=>key==='system:transfer_fee'?r.direction==='TRANSFER'&&Number(r.transfer?.feeCents||0)>0:r.direction===direction&&`category:${r.category}`===key).map(r=>({...r,contributionCents:String(r.direction==='TRANSFER'?r.transfer.feeCents:r.amountCents)}));
    return JSON.stringify({ok:true,records:matched,hasMore:false,summary});
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
  await page.clock.install({time:new Date('2026-11-06T04:00:00Z')});
  await installCategoryFixture(page);
  await page.goto(pathToFileURL(path.resolve('app/src/main/assets/web/index.html')).href);
  await page.evaluate(()=>document.fonts.ready);

  // 1. Settings entry & layout preservation check
  await page.evaluate(()=>go('settings'));
  assert.equal(await page.locator('.account-preferences > button').count(),2,'Existing .account-preferences > button count must stay 2');
  assert.equal(await page.locator('#category-settings').isVisible(),true);
  assert.match(await page.locator('#category-summary-count').innerText(),/支出 9 个 · 收入 6 个/);
  await page.screenshot({path:path.join(output,'01-settings-category-entry.png')});

  // 2. Open Category Manager from Settings: verify Expense/Income tabs, migrated categories, fallback & reference protection
  await page.locator('#category-settings').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  assert.equal(await page.locator('#sheet-title').innerText(),'分类管理');
  const expNames=await page.locator('#category-manage-list [data-category-row]').evaluateAll(els=>els.map(e=>e.dataset.categoryName));
  const expIds=await page.locator('#category-manage-list [data-category-row]').evaluateAll(els=>els.map(e=>e.dataset.categoryRow));
  assert.deepEqual(expNames,['餐饮','交通','购物','餐饮美食','饮食/咖啡','礼金','数码配件','待删竞态分类','其他']);
  assert.deepEqual(expIds,['cat:expense:dining','cat:expense:transit','cat:expense:shopping','cat:expense:migrated_long','cat:expense:migrated_slash','cat:expense:gift','cat:expense:unused','cat:expense:race_ref','cat:expense:other']);

  //Referenced category ('餐饮') has no delete button and explains why; unreferenced ('数码配件') has delete button; fallback ('其他') has no delete button
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"] [data-delete-category]').count(),0);
  assert.match(await page.locator('[data-category-row="cat:expense:dining"] .category-manage-meta').innerText(),/已关联 2 笔账单 · 暂不可删除/);
  assert.equal(await page.locator('[data-category-row="cat:expense:other"] [data-delete-category]').count(),0);
  assert.match(await page.locator('[data-category-row="cat:expense:other"] .category-manage-meta').innerText(),/系统兜底分类 · 不可删除或改名/);
  assert.equal(await page.locator('[data-category-row="cat:expense:unused"] [data-delete-category]').count(),1);
  await page.screenshot({path:path.join(output,'02-category-manager-expense.png')});

  // 3. Add new category + duplicate validation + icon selection
  await page.locator('#new-category-name').fill('餐饮');
  await page.locator('#save-category').click();
  assert.equal(await page.locator('#category-form-error').isVisible(),true);
  assert.match(await page.locator('#category-form-error').innerText(),/同名分类/);

  await page.locator('#new-category-name').fill('宠物口粮');
  await page.locator('[data-icon-key="paw"]').click();
  await page.locator('#save-category').click();
  assert.equal(await page.locator('[data-category-name="宠物口粮"]').count(),1);
  assert.equal(await page.locator('[data-category-name="宠物口粮"] .fa-paw').count(),1);

  // 4. Rename preset '餐饮' -> '工作日餐食' and change icon to 'coffee'
  await page.locator('[data-edit-category="cat:expense:dining"]').click();
  assert.match(await page.locator('#category-editor-title').innerText(),/编辑支出分类 · 餐饮/);
  await page.locator('#new-category-name').fill('工作日餐食');
  await page.locator('[data-icon-key="coffee"]').click();
  await page.screenshot({path:path.join(output,'03-category-edit-rename-icon.png')});
  await page.locator('#save-category').click();
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"]').getAttribute('data-category-name'),'工作日餐食');
  assert.equal(await page.locator('[data-category-row="cat:expense:dining"] .fa-coffee').count(),1);

  // 5. Fallback '其他' cannot be renamed, but can change icon and order
  await page.locator('[data-edit-category="cat:expense:other"]').click();
  assert.equal(await page.locator('#new-category-name').isDisabled(),true);
  await page.locator('[data-icon-key="heart"]').click();
  await page.locator('#save-category').click();
  assert.equal(await page.locator('[data-category-row="cat:expense:other"] .fa-heart').count(),1);

  // 6. Reorder categories: move '交通' up to position 0
  await page.locator('[data-move-up="cat:expense:transit"]').click();
  const reorderedIds=await page.locator('#category-manage-list [data-category-row]').evaluateAll(els=>els.map(e=>e.dataset.categoryRow));
  assert.deepEqual(reorderedIds,['cat:expense:transit','cat:expense:dining','cat:expense:shopping','cat:expense:migrated_long','cat:expense:migrated_slash','cat:expense:gift','cat:expense:unused','cat:expense:race_ref','cat:expense:other','cat:expense:custom_1']);

  // 7. Delete unreferenced category vs backend-protected referenced category
  await page.locator('[data-delete-category="cat:expense:unused"]').click();
  assert.equal(await page.locator('[data-category-row="cat:expense:unused"]').count(),0);

  // Race condition where category has reference on backend: must show error, keep row, and update reference badge
  await page.locator('[data-delete-category="cat:expense:race_ref"]').click();
  assert.equal(await page.locator('#category-form-error').isVisible(),true);
  assert.match(await page.locator('#category-form-error').innerText(),/已关联 1 笔账单/);
  assert.equal(await page.locator('[data-category-row="cat:expense:race_ref"]').count(),1);
  assert.equal(await page.locator('[data-category-row="cat:expense:race_ref"] [data-delete-category]').count(),0);

  // 8. Switch to Income tab: verify independent '礼金' & '其他' and legacy '其他收入'
  await page.locator('[data-manage-dir="income"]').click();
  const incNames=await page.locator('#category-manage-list [data-category-row]').evaluateAll(els=>els.map(e=>e.dataset.categoryName));
  assert.deepEqual(incNames,['工资','奖金','礼金','退款','其他收入','其他']);
  await page.screenshot({path:path.join(output,'04-category-manager-income.png')});
  await page.evaluate(()=>closeSheet(false));

  // 9. Verify Home Ledger, Manual Entry, Record Details Editor, and Statistics all reflect unified directory
  await page.evaluate(()=>go('ledger'));
  assert.match(await page.locator('[data-record="101"]').innerText(),/工作日餐食/);
  assert.equal(await page.locator('[data-record="101"] .fa-coffee').count(),1);
  // Historical '餐饮美食' and '饮食/咖啡' remain distinct
  assert.match(await page.locator('[data-record="102"]').innerText(),/餐饮美食/);
  assert.match(await page.locator('[data-record="103"]').innerText(),/饮食\/咖啡/);
  await page.screenshot({path:path.join(output,'05-ledger-synced-categories.png')});

  // Manual Entry reflects new order ('交通' first, '工作日餐食' second, '宠物口粮' included) and sends categoryId + category
  await page.evaluate(()=>beginManual());
  await page.waitForFunction(()=>!document.querySelector('#manual-panel')._morph);
  const manualChoices=await page.locator('#category-grid [data-category]').evaluateAll(els=>els.map(e=>({name:e.dataset.category,id:e.dataset.categoryId})));
  assert.equal(manualChoices[0].name,'交通');
  assert.equal(manualChoices[1].name,'工作日餐食');
  assert.ok(manualChoices.some(c=>c.name==='宠物口粮'));
  await page.screenshot({path:path.join(output,'06-manual-entry-synced.png')});
  await page.locator('#category-grid [data-category="宠物口粮"]').click();
  await page.locator('#manual-amount').fill('45.80');
  await page.evaluate(()=>document.querySelector('#manual-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  const manualSave=await page.evaluate(()=>window.calls.saves.at(-1));
  assert.equal(manualSave.category,'宠物口粮');
  assert.ok(manualSave.categoryId.startsWith('cat:expense:custom_'));

  // Record Details Editor: only shows active direction's categories, resets category when switching direction, omits categoryId for TRANSFER
  await page.evaluate(()=>openRecord(101));
  await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);
  assert.equal(await page.locator('#category').innerText(),'工作日餐食');
  await page.locator('#category').click();
  await page.waitForFunction(()=>{const s=document.querySelector('#sheet');return s.open&&!s.classList.contains('fluid-pending')&&!s._morph});
  const pickerExpenseNames=await page.locator('#record-category-choices [data-category]').evaluateAll(els=>els.map(e=>e.dataset.category));
  assert.deepEqual(pickerExpenseNames,['交通','工作日餐食','购物','餐饮美食','饮食/咖啡','礼金','待删竞态分类','其他','宠物口粮']);
  await page.screenshot({path:path.join(output,'07-record-category-picker.png')});

  // Switch direction to INCOME inside picker -> resets category to first Income category ('工资')
  await page.locator('[data-direction="INCOME"]').click();
  assert.equal(await page.locator('#category').innerText(),'工资');
  const pickerIncomeNames=await page.locator('#record-category-choices [data-category]').evaluateAll(els=>els.map(e=>e.dataset.category));
  assert.deepEqual(pickerIncomeNames,['工资','奖金','礼金','退款','其他收入','其他']);
  await page.locator('#record-category-choices [data-category="礼金"]').click();
  assert.equal(await page.locator('#category').innerText(),'礼金');
  assert.equal(await page.locator('#category').getAttribute('data-category-id'),'cat:income:gift');
  await page.evaluate(()=>submitDraft());
  const editedSave=await page.evaluate(()=>window.calls.saves.at(-1));
  assert.equal(editedSave.direction,'INCOME');
  assert.equal(editedSave.category,'礼金');
  assert.equal(editedSave.categoryId,'cat:income:gift');

  // Statistics view reflects renamed '工作日餐食' with 'coffee' icon and keeps 'system:transfer_fee'
  await page.evaluate(()=>go('stats'));
  assert.match(await page.locator('#ranking').innerText(),/工作日餐食/);
  assert.match(await page.locator('#ranking').innerText(),/转账手续费/);
  assert.equal(await page.locator('#ranking [data-ranking-category="工作日餐食"] .fa-coffee').count(),1);
  await page.screenshot({path:path.join(output,'08-statistics-synced.png')});

  // 10. XSS safety check on category name and icon key
  const xssSafe=await page.evaluate(()=>{
   const badge=categoryBadge('<img src=x onerror=alert(1)>','EXPENSE','"><script>alert(1)</script>');
   const escaped=escapeText('<img src=x onerror=alert(1)>');
   return badge.includes('&lt;')&&!badge.includes('<script>')&&!badge.includes('onerror=')&&escaped.includes('&lt;img')&&!escaped.includes('<img');
  });
  assert.equal(xssSafe,true,'categoryBadge must escape names and reject unsafe icon keys');

  assert.deepEqual(errors,[]);
  fs.writeFileSync(path.join(output,'metadata.json'),JSON.stringify({
   viewport:{width:390,height:844},
   deviceScaleFactor:2,
   reducedMotion:'reduce',
   timezoneId:'Asia/Shanghai',
   screenshots:[
    '01-settings-category-entry.png',
    '02-category-manager-expense.png',
    '03-category-edit-rename-icon.png',
    '04-category-manager-income.png',
    '05-ledger-synced-categories.png',
    '06-manual-entry-synced.png',
    '07-record-category-picker.png',
    '08-statistics-synced.png'
   ],
   note:'Isolated synthetic browser fixture verifying Ticket 02 UI contract; never touches real ledger database or Android emulators.'
  },null,2));
  console.log('PASS category-directory UI checks: settings entry, directional tabs, add/rename/icon/reorder, fallback & reference protection, manual/edit/filter/stats sync, XSS guard; 8 screenshots '+output);
 }finally{
  await browser.close();
 }
})().catch(error=>{console.error(error);process.exit(1)});
