// Independent browser checks of retained references, input state and same-name card identity.
'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),{pathToFileURL}=require('node:url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const source=fs.readFileSync(path.join(__dirname,'check-wallet-whitelist.cjs'),'utf8');
vm.runInThisContext(source.slice(source.indexOf('async function fixture(page)'),source.indexOf('(async()=>')));
(async()=>{const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:390,height:844},reducedMotion:'reduce',timezoneId:'Asia/Shanghai'}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));await fixture(page);
 await page.goto(pathToFileURL(path.resolve(__dirname,'../app/src/main/assets/web/index.html')).href);
 await page.evaluate(()=>{fixtureAccounts.push({id:8,uid:'card-8',name:'招商银行卡',kind:'bank',bankCode:'cmb',cardLast4:'0008',amountCents:20000,type:'ASSET',revision:1});refresh();beginManual()});
 await page.locator('#manual-type [data-type="transfer"]').click();await page.locator('#manual-from').selectOption('card');
 await page.locator('#manual-to').selectOption('card-8');await page.locator('#manual-amount').fill('100');await page.locator('#manual-fee').fill('1');
 const preview=await page.locator('#manual-transfer-preview').innerText();assert.match(preview,/0007/);assert.match(preview,/0008/);
 const before=await page.evaluate(()=>({value:readTransfer('manual'),token:manualSubmissionToken}));
 const state=await page.evaluate(()=>JSON.parse(JSON.stringify(getState())));
 await page.evaluate(state=>{closeManual(false);restoreState(state)},state);
 assert.deepEqual(await page.evaluate(()=>readTransfer('manual')),before.value);assert.equal(await page.evaluate(()=>manualSubmissionToken),before.token);
 await page.evaluate(()=>{closeManual(false);fixtureRows.push({id:70,uid:'history-reference',merchant:'合成历史互转',category:'转账',amountCents:10000,direction:'TRANSFER',occurredAt:Date.now(),state:'POSTED',source:'IMAGE',note:'',wallet:{eligibility:'IMPORT_EXCLUDED',revision:1},transfer:{fromAccountUid:null,toAccountUid:null,feeCents:100,feeMode:'EXTRA',from:{reference:{version:1,name:'合成原卡A'}},to:{reference:{version:1,name:'合成原账户B'}}}});filter='transfer';refresh();openRecord(70)});
 await page.waitForFunction(()=>!document.querySelector('#record-panel')._morph);await page.locator('#note').fill('合成未保存备注');
 const historicalState=await page.evaluate(()=>JSON.parse(JSON.stringify(getState())));
 assert.equal(historicalState.draft.transfer.from.reference.name,'合成原卡A');
 await page.evaluate(state=>{cancelEditor(true);restoreState(state)},historicalState);
 assert.equal(await page.evaluate(()=>draft.transfer.to.reference.name),'合成原账户B');assert.equal(await page.locator('#note').inputValue(),'合成未保存备注');
 assert.equal(await page.evaluate(()=>validTransfer('record')),true,'Restoring an excluded history cannot force wallet mapping');
 assert.match(await page.locator('#record-transfer').innerText(),/合成原卡A/);assert.match(await page.locator('#record-transfer').innerText(),/合成原账户B/);
 assert.equal(await page.evaluate(()=>editorDirty()),true);
 const precision=await page.evaluate(()=>({text:money('9007199254740993'),groups:categoryTotals([
  {direction:'EXPENSE',category:'转账手续费',amountCents:0},
  {direction:'TRANSFER',category:'转账',amountCents:10000,transfer:{feeCents:100}},
  {direction:'TRANSFER',category:'转账',amountCents:10000,transfer:{feeCents:0}}
 ]).map(r=>({key:r.key,amount:r.amount.toString(),count:r.count}))}));
 assert.equal(precision.text,'90,071,992,547,409.93');assert.equal(precision.groups.length,2);
 assert.deepEqual(precision.groups.find(r=>r.key==='system:transfer_fee'),{key:'system:transfer_fee',amount:'100',count:1});
 assert.deepEqual(errors,[]);console.log('PASS independent transfers: retained original references after restore, dirty fields and submission token, full tails for same-name cards, precise BigInt and zero-fee/full-refund category counts');
 }finally{await browser.close()}})().catch(error=>{console.error(error);process.exitCode=1});
