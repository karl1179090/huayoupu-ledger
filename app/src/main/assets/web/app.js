'use strict';
const $=id=>document.getElementById(id);
const native=window.LedgerNative;
// Android 11 may ship an older WebView that supports grid gap but not flex gap.
const gapProbe=document.createElement('div');gapProbe.style.cssText='display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden';gapProbe.innerHTML='<div style="height:1px"></div><div style="height:1px"></div>';document.body.append(gapProbe);document.body.classList.toggle('no-flex-gap',gapProbe.scrollHeight!==3);gapProbe.remove();
let query='',searching=false;
let ledgerView=null,viewKey='',viewLimit=200,searchTimer,undoTimer;
let dataRevision='',loadedScope='',loadingPage=false,editorBaseline=null,recordReturnControl=null;
const listScope=()=>JSON.stringify([month,query,filter]);
let recognitionTasks=[],recordPanelOpen=false,recordPanelClosing=false,recordAnchor=null,recordHome=null;
let ledgerTouchActive=false,ledgerRenderPending=false;
let lastControl=null,lastControlAt=0,sheetAnchor=null,sheetClosing=false,manualOpen=false,manualClosing=false,manualHome=null,manualAnchor=null,manualReturnControl=null;
function controlBounds(control){if(!control?.getBoundingClientRect)return null;const r=control.getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height,radius:parseFloat(getComputedStyle(control).borderTopLeftRadius)||Math.min(r.width,r.height)/2}}
function clickOrigin(){return Date.now()-lastControlAt<1200?controlBounds(lastControl):null}
function nativeOrigin(control){const r=controlBounds(control);if(!r)return '{}';const left=Math.max(0,r.left),top=Math.max(0,r.top),width=Math.max(0,Math.min(innerWidth,r.left+r.width)-left),height=Math.max(0,Math.min(innerHeight,r.top+r.height)-top);return JSON.stringify({left,top,width,height,radius:Math.min(r.radius,width/2,height/2),viewportWidth:innerWidth})}
document.addEventListener('click',e=>{const control=e.target.closest('button,[role="button"]');if(control){lastControl=control;lastControlAt=Date.now()}},true);
let automatic={enabled:false,applications:[]},screenshotStorage={},captureHealth={enabled:false,ready:false};
let walletSettings={defaultAccountUid:null,appBindings:{}},bankCodeOptions=[],manualWalletUid=null,manualWalletSelected=false,manualSubmissionToken='';
const submissionToken=()=>window.crypto?.randomUUID?.()||'submit-'+Date.now()+'-'+Math.random().toString(36).slice(2);
let records=[],channels={},accounts=[],bankOptions=[],customCategories={expense:[],income:[]},categoryDirectory=null,categoryIconOptions=null,classificationRules={formatVersion:1,version:'',customRules:[],builtinRules:[]},activeRulesSheetRefresh=null,automationRunning=false,filter='expense',page='ledger',month=localMonth(Date.now()),draft=null,working=false,importError='',previewUrl='',timer;
function normRuleKeyword(s){return Array.from(String(s||'').normalize('NFKC').toLowerCase()).filter(c=>!/\s/u.test(c)).join('')}
function normalizeRuleItem(r,idx=0){
 if(!r||typeof r!=='object')return null;
 const dir=String(r.direction||'EXPENSE').toUpperCase()==='INCOME'?'INCOME':'EXPENSE';
 const conds=Array.isArray(r.conditions)&&r.conditions.length?r.conditions.map(c=>({
  field:String(c?.field||'MERCHANT').toUpperCase()==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT',
  operator:String(c?.operator||'CONTAINS').toUpperCase()==='EQUALS'?'EQUALS':String(c?.operator||'CONTAINS').toUpperCase()==='NOT_CONTAINS'?'NOT_CONTAINS':'CONTAINS',
  value:String(c?.value||'')
 })):[];
 const mode=String(r.matchMode||'ALL').toUpperCase()==='ANY'?'ANY':'ALL';
 const src=typeof r.source==='string'&&r.source.trim()?r.source.trim():null;
 return {id:String(r.id||`rule:${dir.toLowerCase()}:${idx}`),name:String(r.name||''),enabled:r.enabled!==false,direction:dir,categoryId:String(r.categoryId||''),order:typeof r.order==='number'?r.order:idx,matchMode:mode,source:src,conditions:conds,conflictReason:r.conflictReason||null};
}
function applyClassificationRules(raw){
 if(!raw||typeof raw!=='object')return;
 classificationRules={formatVersion:Number(raw.formatVersion||1),version:String(raw.version||''),customRules:Array.isArray(raw.customRules)?raw.customRules.map((r,i)=>normalizeRuleItem(r,i)).filter(Boolean):[],builtinRules:Array.isArray(raw.builtinRules)?raw.builtinRules.map((r,i)=>normalizeRuleItem(r,i)).filter(Boolean):[]};
}
function customRulesByDirection(dir){
 const d=String(dir||'EXPENSE').toUpperCase()==='INCOME'?'INCOME':'EXPENSE';
 return (classificationRules.customRules||[]).filter(r=>r.direction===d).sort((a,b)=>(a.order??0)-(b.order??0)||String(a.id).localeCompare(String(b.id)));
}
function builtinRulesByDirection(dir){
 const d=String(dir||'EXPENSE').toUpperCase()==='INCOME'?'INCOME':'EXPENSE';
 return (classificationRules.builtinRules||[]).filter(r=>r.direction===d);
}
const ruleSourceNames={
 'LIVE':'现场识别',
 'IMAGE':'图片导入',
 'LIVE:com.tencent.mm':'微信现场',
 'LIVE:com.eg.android.AlipayGphone':'支付宝现场',
 'IMAGE:wechat':'微信凭证图片',
 'IMAGE:alipay':'支付宝凭证图片'
};
function formatRuleSource(src){
 if(!src)return '';
 if(ruleSourceNames[src])return ruleSourceNames[src];
 if(src.startsWith('LIVE:')){
  const pkg=src.slice(5);
  return `现场识别（${knownReceiptApps[pkg]||pkg}）`;
 }
 if(src.startsWith('IMAGE:')){
  const app=src.slice(6);
  return `图片导入（${app==='wechat'?'微信':app==='alipay'?'支付宝':app}）`;
 }
 return src;
}
function formatSingleConditionText(c){
 const f=c.field==='ORDER_CONTENT'?'订单内容':'商家';
 const op=c.operator==='EQUALS'?'等于':c.operator==='NOT_CONTAINS'?'不包含':'包含';
 return `${f}${op}「${c.value}」`;
}
function formatRuleConditionSummary(rule){
 const conds=Array.isArray(rule?.conditions)&&rule.conditions.length?rule.conditions:[];
 if(!conds.length)return '未设置条件';
 let text='';
 if(conds.length===1){
  text=formatSingleConditionText(conds[0]);
 }else{
  const isAny=rule?.matchMode==='ANY';
  text=(isAny?'任一满足：':'全部满足：')+conds.map(formatSingleConditionText).join(isAny?' 或 ':' 且 ');
 }
 const srcText=formatRuleSource(rule?.source);
 return srcText?`${srcText} · ${text}`:text;
}
function formatCategoryDecision(record){
 if(!record||record.direction==='TRANSFER')return '';
 const d=record.categoryDecision;if(!d||typeof d!=='object')return '';
 const exp=String(d.explanation||'').trim();
 if(exp)return exp;
 return ({CUSTOM_RULE:'按规则自动归类',BUILTIN_RULE:'内置规则自动归类',FALLBACK_OTHER:'未命中规则，归入其他',MANUAL:'人工选择分类',IMPORT:'保留导入分类',LEGACY:'保留原分类'})[d.source]||'';
}
const captureName=r=>r.captureMode==='page'||['page_auto','wechat_auto','payment_auto'].some(origin=>String(r.origin||'').startsWith(origin))||/^(page_auto|wechat_auto|payment_auto)/.test(String(r.diagnostic?.trigger||''))?'自动记账':sourceNames[r.source];
const sourceNames={OCR:'摇一摇',IMAGE:'图片导入',SMS:'自动 · 短信',NOTIFICATION:'自动 · 通知',MANUAL:'手动'};
const ALLOWED_CATEGORY_ICONS=new Set(['utensils','bus','bag-shopping','gamepad','house','plus','graduation-cap','shirt','basket-shopping','phone','gift','ellipsis','wallet','award','rotate-left','tag','heart','paw','plane','coffee','car','book','briefcase','coins','music','dumbbell']);
const DEFAULT_ICON_OPTIONS=[{key:'utensils',label:'餐饮'},{key:'bus',label:'交通'},{key:'bag-shopping',label:'购物'},{key:'gamepad',label:'娱乐'},{key:'house',label:'居住'},{key:'plus',label:'医疗'},{key:'graduation-cap',label:'教育'},{key:'shirt',label:'服饰'},{key:'basket-shopping',label:'日用'},{key:'phone',label:'通讯'},{key:'gift',label:'礼金'},{key:'ellipsis',label:'其他'},{key:'wallet',label:'工资'},{key:'award',label:'奖金'},{key:'rotate-left',label:'退款'},{key:'tag',label:'标签'},{key:'heart',label:'关怀'},{key:'paw',label:'宠物'},{key:'plane',label:'旅行'},{key:'coffee',label:'饮品'},{key:'car',label:'汽车'},{key:'book',label:'学习'},{key:'briefcase',label:'办公'},{key:'coins',label:'理财'},{key:'music',label:'影音'},{key:'dumbbell',label:'运动'}];
const DEFAULT_EXPENSE_PRESETS=[['dining','餐饮','utensils'],['transit','交通','bus'],['shopping','购物','bag-shopping'],['entertainment','娱乐','gamepad'],['housing','居住','house'],['medical','医疗','plus'],['education','教育','graduation-cap'],['apparel','服饰','shirt'],['daily','日用','basket-shopping'],['telecom','通讯','phone'],['social','人情','gift'],['other','其他','ellipsis',true]];
const DEFAULT_INCOME_PRESETS=[['salary','工资','wallet'],['bonus','奖金','award'],['gift','礼物','gift'],['refund','退款','rotate-left'],['other','其他','ellipsis',true]];
function safeIconKey(icon){const k=String(icon||'').trim();return ALLOWED_CATEGORY_ICONS.has(k)?k:''}
function activeIconOptions(){const list=Array.isArray(categoryIconOptions)&&categoryIconOptions.length?categoryIconOptions.filter(o=>o&&safeIconKey(o.key)):DEFAULT_ICON_OPTIONS;return list.length?list:DEFAULT_ICON_OPTIONS}
function directoryItems(dir){
 const isIncome=String(dir||'').toUpperCase()==='INCOME'||dir==='income',key=isIncome?'income':'expense',direction=isIncome?'INCOME':'EXPENSE';
 const raw=categoryDirectory?.[key];
 if(Array.isArray(raw)&&raw.length)return raw.map((item,idx)=>({id:String(item.id||`cat:${key}:${idx}`),name:String(item.name||'其他'),direction:item.direction||direction,icon:safeIconKey(item.icon)||presetOrAliasIcon(item.name)||'tag',order:typeof item.order==='number'?item.order:idx,isFallback:!!item.isFallback||String(item.name||'').trim()==='其他',referenceCount:Number(item.referenceCount||0),canDelete:item.canDelete!==undefined?!!item.canDelete:(!item.isFallback&&String(item.name||'').trim()!=='其他'&&Number(item.referenceCount||0)===0)}));
 const presets=isIncome?DEFAULT_INCOME_PRESETS:DEFAULT_EXPENSE_PRESETS,customs=(customCategories?.[key]||[]).filter(name=>!presets.some(p=>p[1]===name));
 return [...presets.map(([slug,name,icon,fallback],idx)=>({id:`cat:${key}:${slug}`,name,direction,icon,order:idx,isFallback:!!fallback,referenceCount:0,canDelete:!fallback})),...customs.map((name,idx)=>({id:`cat:${key}:custom:${idx}:${name}`,name:String(name),direction,icon:safeIconKey(presetOrAliasIcon(name))||'tag',order:presets.length+idx,isFallback:false,referenceCount:0,canDelete:true}))];
}
function findCategoryItem(direction,id,name){
 const items=directoryItems(direction);
 if(id){const byId=items.find(c=>c.id===id);if(byId)return byId}
 const trimmed=String(name||'').trim();
 if(trimmed){const byName=items.find(c=>c.name===trimmed);if(byName)return byName}
 return items[0]||items.find(c=>c.isFallback)||{id:`cat:${String(direction||'expense').toLowerCase()}:other`,name:'其他',direction:String(direction||'EXPENSE').toUpperCase(),icon:'ellipsis',isFallback:true,referenceCount:0,canDelete:false};
}
function resolveRecordCategory(r){
 if(!r||r.direction==='TRANSFER')return {id:null,name:r?.category||'转账',icon:''};
 const dir=r.direction==='INCOME'?'INCOME':'EXPENSE',items=directoryItems(dir);
 const matched=(r.categoryId&&items.find(c=>c.id===r.categoryId))||(r.category&&items.find(c=>c.name===String(r.category).trim()));
 const name=matched?.name||String(r.category||'其他'),icon=safeIconKey(matched?.icon)||safeIconKey(r.categoryIcon)||iconForCategory(name,dir);
 return {id:matched?.id||(r.categoryId??null),name,icon};
}
const amountFormatter=new Intl.NumberFormat('en-US',{minimumFractionDigits:2,maximumFractionDigits:2});
const cents=value=>{if(typeof value==='bigint')return value;if(value==null||value==='')return 0n;if(typeof value==='number'&&!Number.isSafeInteger(value))throw Error('金额精度错误');return BigInt(value)};
const money=value=>{let n=cents(value),negative=n<0n;if(negative)n=-n;const digits=n.toString().padStart(3,'0'),whole=digits.slice(0,-2).replace(/\B(?=(\d{3})+(?!\d))/g,',');return (negative?'-':'')+whole+'.'+digits.slice(-2)};
const ratio=(a,b)=>cents(b)===0n?0:Number(cents(a))/Number(cents(b));
const percentDiff=(a,b)=>Math.abs(ratio(cents(a)-cents(b),b)*100).toFixed(0);
const sumValues=list=>list.reduce((sum,value)=>sum+cents(value),0n);
const directionLabel=value=>({EXPENSE:'支出',INCOME:'收入',TRANSFER:'转账'})[value]||'待确认';
const filterDirection=()=>({expense:'EXPENSE',income:'INCOME',transfer:'TRANSFER',all:''})[filter]||'';
const expenseOf=r=>r.direction==='EXPENSE'?cents(r.amountCents):r.direction==='TRANSFER'?cents(r.transfer?.feeCents):0n;
const receiptFieldValue=field=>typeof field?.value==='string'?field.value.trim():'';
const recordMerchant=r=>r?.merchant!==undefined&&r?.merchant!==null?String(r.merchant).trim():receiptFieldValue(r?.receipt?.merchant);
const knownReceiptApps={'com.tencent.mm':'微信','com.eg.android.AlipayGphone':'支付宝','com.taobao.taobao':'淘宝','com.unionpay':'云闪付','com.jingdong.app.mall':'京东','com.xunmeng.pinduoduo':'拼多多','com.sankuai.meituan':'美团'};
const receiptSourceNames={ocr:'识别文字',node:'页面节点',capture:'采集来源'};
const receiptFieldSpecs=[['merchant','商家'],['productDescription','商品说明'],['transactionDescription','交易说明'],['orderRemark','订单备注'],['serviceProvider','服务商'],['receiptIssuer','凭证来源'],['sourceApp','来源应用']];
function formatReceiptSourceApp(value){const raw=String(value??'').trim();if(!raw)return '';const app=(automatic.applications||[]).find(a=>a.packageName===raw&&a.label);return app?.label||knownReceiptApps[raw]||raw}
function isTrustedDateMetadata(time,occurredAt){
 if(!time||typeof time!=='object')return false;
 if(time.precision!=='DATE')return false;
 if(time.source!=='EXPLICIT'&&time.source!=='YEAR_INFERRED')return false;
 if(typeof time.accountingAt!=='number'||!Number.isFinite(time.accountingAt))return false;
 if(occurredAt!==undefined&&occurredAt!==null&&time.accountingAt!==occurredAt)return false;
 if(typeof time.localDate!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(time.localDate))return false;
 const d=new Date(time.localDate+'T00:00:00');
 return !isNaN(d.getTime());
}
function trustedReceiptDate(record,timeEdited=false){
 if(!record||timeEdited||record._timeEdited)return null;
 const t=record.receipt?.time;
 if(!isTrustedDateMetadata(t,record.occurredAt))return null;
 return t.localDate;
}
function receiptTimeBasis(time,userEdited=false,record=null){
 if(userEdited)return '人工修改记账时间';
 if(!time||typeof time!=='object')return '';
 const {source,precision,reference}=time;
 if(source==='MANUAL')return '人工修改记账时间';
 if(source==='RECOGNITION_FALLBACK')return '使用识别时间';
 if(precision==='DATE'){
  if(record&&!trustedReceiptDate(record,userEdited))return '';
  if(!record&&!isTrustedDateMetadata(time))return '';
  if(source==='EXPLICIT')return '凭证日期（仅日期）';
  if(source==='YEAR_INFERRED')return reference==='ORIGINAL_CAPTURE'?'凭证日期，年份按采集日期推断':'凭证日期，年份按识别日期推断';
  return '';
 }
 if(source==='EXPLICIT')return '凭证交易时间';
 if(source==='YEAR_INFERRED')return reference==='ORIGINAL_CAPTURE'?'凭证日期，年份按采集日期推断':'凭证日期，年份按识别日期推断';
 return '';
}
const transferName=r=>r.direction==='TRANSFER'?`${r.transfer?.from?.accountName||r.transfer?.from?.reference?.name||'待选钱包'} → ${r.transfer?.to?.accountName||r.transfer?.to?.reference?.name||'待选钱包'}`:recordMerchant(r)||(r?.receipt?'未提供':'未填写商户');
const signedMoney=(cents,income=false)=>`¥ ${income?'+':'-'}${money(cents)}`;
function escapeText(value){const e=document.createElement('span');e.textContent=value??'';return e.innerHTML.replace(/"/g,'&quot;').replace(/'/g,'&#39;')}
function localTime(time){const d=new Date(time),pad=n=>String(n).padStart(2,'0');return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`}
function localMonth(time){const d=new Date(time),value=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}`;return value.length<7?localTime(time).slice(0,7):value.slice(0,7)}
function recordMonth(r){
 const trusted=trustedReceiptDate(r);
 if(trusted)return trusted.slice(0,7);
 return localMonth(r?.occurredAt);
}
function itemDateKey(item){
 if(item.record){
  const trusted=trustedReceiptDate(item.record);
  if(trusted)return trusted;
 }
 return localTime(item.time).slice(0,10);
}
function recordDisplayTime(r){
 const trusted=trustedReceiptDate(r);
 if(trusted)return `${trusted} 仅日期`;
 return localTime(r?.occurredAt).replace('T',' ');
}
function monthRecords(){return records.filter(r=>r.state==='POSTED'&&recordMonth(r)===month)}
function sums(list){return list.reduce((a,r)=>{if(r.direction==='INCOME')a.income+=cents(r.amountCents);else a.expense+=expenseOf(r);return a},{income:0n,expense:0n})}
function notify(text){const t=document.querySelector('.toast');t.textContent=text;t.classList.add('show');clearTimeout(timer);timer=setTimeout(()=>t.classList.remove('show'),2600)}
function showUndo(deletion){let bar=$('delete-undo');clearTimeout(undoTimer);if(!deletion||deletion.expiresAt<=Date.now()){bar?.remove();return}if(!bar){bar=document.createElement('div');bar.id='delete-undo';bar.className='delete-undo';bar.setAttribute('role','status');document.body.append(bar)}bar.innerHTML='<span>已删除</span><button type="button">撤销</button>';bar.querySelector('button').onclick=()=>{try{const result=JSON.parse(native.mutateRecord?native.mutateRecord(JSON.stringify({action:'UNDO',id:deletion.id,submissionToken:deletion.undoToken||(deletion.undoToken=submissionToken()),...(deletion.expectedRevision!=null?{expectedRevision:deletion.expectedRevision}:{})})):native.undoDelete(deletion.id));if(!result.ok){notify(result.error||'撤销时间已过');bar.remove();return}bar.remove();viewKey='';refresh();if(page==='stats')drawStats();notify('已恢复账单')}catch(_){notify('恢复失败，请重试')}};undoTimer=setTimeout(()=>bar.remove(),deletion.expiresAt-Date.now())}
function loadedTotals(){return ledgerView?.summary?.totals||sums(monthRecords())}
function loadedCategories(type){return ledgerView?.summary?.categories?.filter(r=>r.direction===(type==='income'?'INCOME':'EXPENSE'))||categoryTotals(monthRecords().filter(r=>type==='income'?r.direction==='INCOME':r.direction!=='INCOME'))}
function remember(){try{native?.remember(JSON.stringify(getState()))}catch(_){}}
function getState(){return {page,recordPanel:recordPanelOpen,manualPanel:manualOpen,month,filter,editorBaseline,draft:storedDraft(),previewUrl,manual:{amount:$('manual-amount').value,note:$('manual-note').value,type:manualType,category:manualCategory,categoryId:manualCategoryId,walletUid:manualWalletUid,walletSelected:manualWalletSelected,submissionToken:manualSubmissionToken,transfer:manualTransfer,occurredAt:$('manual-occurred-at').value},statsType,rankingType,assetsHidden}}
function go(id){closeSheet(false);if(manualOpen||manualClosing)closeManual(false);if(recordPanelOpen||recordPanelClosing)closeRecordPanel(false);if(id!=='ledger'&&searching)setSearchOpen(false,false);page=id;document.querySelectorAll('.screen').forEach(s=>s.classList.toggle('mobile-active',s.id===id));if(id==='import'&&!draft)newDraft();if(id==='manual')drawCategories();if(id==='stats')drawStats();if(id==='assets')drawAssets();remember()}
function categoryStyle(r){const resolved=resolveRecordCategory(r),cat=resolved.name,icon=resolved.icon;return [icon,r.direction==='INCOME'?'pink':/交通|停车|高速|bus|car|plane/.test(cat+' '+icon)?'blue':/购物|服饰|日用|bag-shopping|shirt|basket-shopping/.test(cat+' '+icon)?'orange':'green']}
function ledgerIcon(r,pending=false){
 const resolved=resolveRecordCategory(r),cat=resolved.name;
 const wechat=r.paymentProvider==='wechat'&&['','其他','其他收入'].includes(String(cat||'').trim());
 const [icon,color]=wechat?['weixin','green']:pending?['wand-magic-sparkles','blue']:categoryStyle(r);
 const fallback=icon?`<i class="${wechat?'fa-brands':'fa-solid'} fa-${icon}" aria-hidden="true"></i>`:`<span class="category-initial" aria-hidden="true">${escapeText(Array.from(cat||'其他')[0])}</span>`;
 if(!r.hasMerchantIcon||!/^[a-f0-9]{64}$/.test(r.merchantIconKey||''))return `<span class="category-icon ${color}">${fallback}</span>`;
 return `<span class="category-icon ${color} merchant-icon"><img src="https://appassets.androidplatform.net/merchant-icon/${r.merchantIconKey}.img?v=${r.merchantIconRevision||0}" alt="" decoding="async"/>${fallback}</span>`;
}

function render(){
 const list=monthRecords(),total=loadedTotals(),[y,m]=month.split('-');
 document.querySelector('#month h1').textContent=`${y}年${Number(m)}月`;
 const selected=filter==='income'?'income':'expense',direction=selected==='income'?'INCOME':'EXPENSE';
 document.querySelector('.balance-title').textContent=selected==='income'?'本月收入':'本月支出（含转账手续费）';
 document.querySelector('.balance-number').classList.toggle('long-amount',money(total[selected]).length>17);
 document.querySelector('.balance-number').innerHTML=`<small>¥</small> ${money(total[selected])}`;
 $('home-count').textContent=`共 ${total[selected+'Count']??list.filter(r=>r.direction===direction).length} 笔${selected==='income'?'收入':'消费'}`;
 document.querySelector('.totals').innerHTML=`<div>收入 ¥ ${money(total.income)}</div><div>支出 ¥ ${money(total.expense)}</div>`;
 drawChart(list,new Date(),ledgerView?.summary?.days);
 const previous=localMonth(new Date(Number(y),Number(m)-2,1).getTime()),before=ledgerView?.summary?.previous?.[selected]??sums(records.filter(r=>r.state==='POSTED'&&recordMonth(r)===previous))[selected];
 $('home-change').textContent=cents(before)>0n?`较上月 ${cents(total[selected])>=cents(before)?'↑':'↓'} ${percentDiff(total[selected],before)}%`:'本月每日趋势';
 const search=query.toLowerCase();
 const shown=records.filter(r=>(r.state==='POSTED'||r.state==='REVIEW')&&(query||recordMonth(r)===month)
  &&(filter==='all'||r.direction===filterDirection()||filter==='expense'&&!r.direction)
  &&(!query||[r.merchant,transferName(r),resolveRecordCategory(r).name,r.category,r.note,r.transactionId,sourceNames[r.source]].some(v=>String(v||'').toLowerCase().includes(search))));
 const attempts=recognitionTasks.filter(t=>!t.recordId&&!['SKIPPED','DUPLICATE'].includes(t.status)&&localMonth(t.createdAt)===month&&(!query||[captureName(t),taskNames[t.status],t.message].some(v=>String(v||'').toLowerCase().includes(search))));
 const timeline=[...shown.map(record=>({record,time:record.occurredAt,id:record.id})),...attempts.map(task=>({task,time:task.createdAt,id:task.id}))].sort((a,b)=>{
  const dayDiff=itemDateKey(b).localeCompare(itemDateKey(a));
  if(dayDiff!==0)return dayDiff;
  return b.time-a.time||b.id-a.id||Number(!!a.task)-Number(!!b.task);
 });
 const groups=new Map();for(const item of timeline){const key=itemDateKey(item);if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item)}
 const dailyTotals=new Map();if(!query&&ledgerView?.summary?.days)for(const row of ledgerView.summary.days)if(row.direction===direction)dailyTotals.set(row.day,(dailyTotals.get(row.day)||0n)+cents(row.amount));
 const today=localTime(Date.now()).slice(0,10),yesterday=localTime(Date.now()-86400000).slice(0,10);
 const timelineMarkup=[...groups].map(([key,items])=>{
  const rows=items.filter(item=>item.record).map(item=>item.record);
  const [itemYear,itemMonth,itemDay]=key.split('-').map(Number),d=new Date(itemYear,itemMonth-1,itemDay);
  const label=key===today?'今天':key===yesterday?'昨天':['星期日','星期一','星期二','星期三','星期四','星期五','星期六'][d.getDay()];
  const pending=rows.filter(r=>r.state==='REVIEW').length,dayTotal=filter==='transfer'?sumValues(rows.filter(r=>r.state==='POSTED').map(expenseOf)):!query&&ledgerView?.summary?.days?(dailyTotals.get(itemDay)||0):sums(rows.filter(r=>r.state==='POSTED'))[selected];
  return `<div class="date-line day-header"><span>${itemMonth}月${itemDay}日　${label}</span><span>${pending?pending+' 笔待确认':rows.some(r=>r.state==='POSTED')?(filter==='transfer'?'手续费 ¥ ':'¥ ')+money(dayTotal):'识别记录'}</span></div><section class="card transactions">`+items.map(item=>{
   if(item.task)return recognitionRow(item.task);
   const r=item.record,catLabel=resolveRecordCategory(r).name||'其他';
   const pending=r.state==='REVIEW',income=r.direction==='INCOME',source=captureName(r)||'手动',aiSource=['OCR','IMAGE'].includes(r.source);
   return swipeRow(`<button class="transaction entry ${pending?'ai-pending-entry':''}" ${pending?'data-pending':'data-record'}="${r.id}" aria-label="${pending?'':'编辑'}${escapeText(transferName(r))}，${pending?'待确认记账':directionLabel(r.direction)+money(r.amountCents)+'元'}，${escapeText(catLabel)}，${escapeText(source)}">${ledgerIcon(r,pending)}<span class="record-main entry-text"><span class="record-name entry-title">${escapeText(transferName(r))}</span><span class="record-meta entry-subtitle"><span class="record-time">${trustedReceiptDate(r)?'仅日期':localTime(r.occurredAt).slice(11)}</span><span class="record-category">${escapeText(catLabel)}</span><span class="tag ${aiSource?'ai':r.source==='MANUAL'?'manual':''}">${escapeText(source)}</span></span></span>${pending?'<span class="pending tag">待确认</span>':`<span class="amount entry-amount ${income?'positive':''}">${r.direction==='TRANSFER'?'¥ '+money(r.amountCents):signedMoney(r.amountCents,income)}</span>`}<span class="chevron" aria-hidden="true">›</span></button>`,r.id,false,r.merchant||'账单');
  }).join('')+'</section>';
 }).join('');
 renderTimeline((query?'<p class="search-scope">搜索全部月份</p>':'')+(timelineMarkup||`<div class="empty-state">${query?'未找到匹配的账单':'这个月还没有'+(selected==='income'?'收入':'支出')+'账单'}<br>点击底部 + 手动记账或导入账单截图</div>`)+(ledgerView?.hasMore?'<button type="button" id="load-more-records" class="load-more-records">加载更多账单</button>':''));
 if($('load-more-records'))$('load-more-records').onclick=loadMoreRecords;
 $('entries').querySelectorAll('.merchant-icon img').forEach(img=>{const failed=()=>{img.parentElement.classList.remove('merchant-icon');img.remove()};img.onerror=failed;if(img.complete&&!img.naturalWidth)failed()});
 $('entries').querySelectorAll('[data-record],[data-pending]').forEach(e=>e.onclick=()=>openRecord(Number(e.dataset.record||e.dataset.pending),false,e.getBoundingClientRect()));
 $('entries').querySelectorAll('[data-task]').forEach(e=>e.onclick=()=>openTask(Number(e.dataset.task),e.getBoundingClientRect()));
 bindSwipeRows();
 document.querySelectorAll('[data-filter]').forEach(e=>{e.classList.toggle('active',e.dataset.filter===filter);e.setAttribute('aria-pressed',String(e.dataset.filter===filter))});
 drawSettings();
}
// Keep unchanged rows alive across native snapshot updates. Replacing a button
// between touch-down and touch-up cancels its click in Android WebView.
function renderTimeline(markup){
 if(ledgerTouchActive){ledgerRenderPending=true;return}
 if($('entries')._timelineMarkup===markup)return;
 const existing=new Map(Array.from($('entries').querySelectorAll('.swipe-row')).map(row=>[row.dataset.swipeKey,row]));
 const template=document.createElement('template');template.innerHTML=markup;
 template.content.querySelectorAll('.swipe-row').forEach(row=>{const previous=existing.get(row.dataset.swipeKey),html=row.outerHTML;if(previous&&previous._timelineMarkup===html)row.replaceWith(previous);else row._timelineMarkup=html});
 // Avoid detaching the touch target at all when the timeline has not changed.
 $('entries').textContent='';$('entries').appendChild(template.content);$('entries')._timelineMarkup=markup;
}
const taskNames={QUEUED:'等待识别',CAPTURING:'正在截图',RECOGNIZING:'AI 识别中',FAILED:'识别失败',NO_BILL:'未识别到账单',DUPLICATE:'已跳过重复',CANCELLED:'已停止',REVIEW:'待确认',SUCCEEDED:'已记账'};
function recognitionRow(t){const running=['CAPTURING','QUEUED','RECOGNIZING'].includes(t.status),label=taskNames[t.status]||'识别记录';return swipeRow(`<button class="entry recognition-entry ${running?'is-recognizing':'is-failed'}" data-task="${t.id}" aria-label="${escapeText(captureName(t)||'图片识别')}，${label}"><span class="category-icon">${running?'<span class="recognition-spinner" aria-hidden="true"></span>':'<i class="fa-regular fa-image" aria-hidden="true"></i>'}</span><span class="entry-text"><span class="entry-title">${label}</span><span class="entry-subtitle">${localTime(t.createdAt).slice(11)} · ${escapeText(captureName(t)||'图片识别')}</span></span><span class="recognition-state">${running?'处理中':'未入账'}</span><span class="chevron" aria-hidden="true">›</span></button>`,t.id,true,'识别记录',running)}
function swipeRow(content,id,task,label,busy=false){return `<div class="swipe-row" data-swipe-key="${task?'task':'record'}:${id}"><button class="swipe-delete" type="button" tabindex="-1" aria-hidden="true" aria-label="删除${escapeText(label)}" data-delete-id="${id}" data-delete-task="${task}" ${busy?'disabled':''}><i class="fa-regular fa-trash-can" aria-hidden="true"></i><span>${busy?'识别中':'删除'}</span></button>${content}</div>`}
function setSwipeOpen(row,open){row.classList.toggle('swipe-open',open);row.classList.remove('swipe-dragging');row.querySelector('.swipe-delete').tabIndex=open?0:-1;row.querySelector('.swipe-delete').setAttribute('aria-hidden',String(!open));row.querySelector('.entry').style.removeProperty('transform')}
function closeSwipeRows(except=null){$('entries').querySelectorAll('.swipe-open').forEach(row=>{if(row!==except)setSwipeOpen(row,false)})}
function bindSwipeRows(){
 $('entries').querySelectorAll('.swipe-row').forEach(row=>{
  if(row._swipeBound)return;row._swipeBound=true;
  const entry=row.querySelector('.entry'),remove=row.querySelector('.swipe-delete');let gesture=null,suppressUntil=0;
  entry.addEventListener('pointerdown',e=>{if(e.isPrimary===false||e.button>0)return;closeSwipeRows(row);gesture={id:e.pointerId,x:e.clientX,y:e.clientY,base:row.classList.contains('swipe-open')?-80:0,axis:''}});
  entry.addEventListener('pointermove',e=>{if(!gesture||e.pointerId!==gesture.id)return;const dx=e.clientX-gesture.x,dy=e.clientY-gesture.y;if(!gesture.axis&&Math.max(Math.abs(dx),Math.abs(dy))>10){gesture.axis=Math.abs(dx)>Math.abs(dy)*1.3?'x':'y';if(gesture.axis==='x')try{entry.setPointerCapture(e.pointerId)}catch(_){}}if(gesture.axis!=='x')return;row.classList.add('swipe-dragging');e.preventDefault();entry.style.transform=`translateX(${Math.max(-80,Math.min(0,gesture.base+dx))}px)`;suppressUntil=Date.now()+400});
  const finish=e=>{if(!gesture||e.pointerId!==gesture.id)return;if(gesture.axis==='x'){const open=e.type!=='pointercancel'&&gesture.base+e.clientX-gesture.x<-36;setSwipeOpen(row,open);suppressUntil=Date.now()+400}gesture=null};
  // Android implicitly captures the original text/icon target. Its release bubbles
  // when capture moves to this button; that is not cancellation of our gesture.
  entry.addEventListener('pointerup',finish);entry.addEventListener('pointercancel',finish);entry.addEventListener('lostpointercapture',e=>{if(e.target===entry&&gesture){row.classList.remove('swipe-dragging');entry.style.removeProperty('transform');gesture=null}});
  entry.addEventListener('click',e=>{if(Date.now()<suppressUntil||row.classList.contains('swipe-open')){e.preventDefault();e.stopImmediatePropagation();if(Date.now()>=suppressUntil)setSwipeOpen(row,false)}},true);
  row.addEventListener('keydown',e=>{if(e.key==='Escape')setSwipeOpen(row,false)});
  const activateDelete=()=>{if(row.classList.contains('swipe-open')&&!remove.disabled)deleteEntry(Number(remove.dataset.deleteId),remove.dataset.deleteTask==='true')};
  let deletePress=null,pointerHandled=false;
  remove.addEventListener('pointerdown',e=>{pointerHandled=true;if(e.isPrimary===false||e.button>0||remove.disabled||!row.classList.contains('swipe-open'))return;deletePress={id:e.pointerId,x:e.clientX,y:e.clientY,moved:false};try{remove.setPointerCapture(e.pointerId)}catch(_){}});
  remove.addEventListener('pointermove',e=>{if(deletePress?.id===e.pointerId&&Math.hypot(e.clientX-deletePress.x,e.clientY-deletePress.y)>10)deletePress.moved=true});
  remove.addEventListener('pointercancel',()=>{deletePress=null});
  // Recent Android WebViews can omit click immediately after a swipe. Activate
  // a genuine tap on pointer-up; ignore its later compatibility click.
  remove.addEventListener('pointerup',e=>{if(deletePress?.id!==e.pointerId)return;const press=deletePress;deletePress=null;const r=remove.getBoundingClientRect();if(press.moved||Math.hypot(e.clientX-press.x,e.clientY-press.y)>10||e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)return;e.preventDefault();activateDelete()});
  remove.onclick=e=>{if(!pointerHandled||e.detail===0)activateDelete()};
 });
}
$('entries').addEventListener('pointerdown',e=>{if(!e.target.closest('.swipe-row'))closeSwipeRows()});
$('entries').addEventListener('pointerdown',e=>{if(e.isPrimary!==false)ledgerTouchActive=true},true);
function finishLedgerTouch(){ledgerTouchActive=false;if(ledgerRenderPending){ledgerRenderPending=false;setTimeout(()=>render(),80)}}
document.addEventListener('pointerup',finishLedgerTouch,true);document.addEventListener('pointercancel',finishLedgerTouch,true);window.addEventListener('blur',finishLedgerTouch);
function openTask(id,anchor=null){recordReturnControl=document.activeElement;editorBaseline=null;const t=recognitionTasks.find(t=>t.id===id);if(!t){notify('识别记录已不存在');return}if(t.recordId){openRecord(t.recordId,false,anchor);return}const fallbackCat=findCategoryItem('EXPENSE',null,'其他');draft={id:0,recognitionTaskId:t.id,taskStatus:t.status,source:t.source,captureMode:/^(page_auto|wechat_auto|payment_auto)/.test(String(t.origin||''))?'page':'',amount:'',merchant:'',category:fallbackCat.name,categoryId:fallbackCat.id,direction:'EXPENSE',occurredAt:t.createdAt,_originalOccurredAt:t.createdAt,_timeEdited:false,note:'',reason:t.message,state:'REVIEW',rawText:''};working=['CAPTURING','QUEUED','RECOGNIZING'].includes(t.status);importError=t.status==='FAILED'?t.message:'';previewUrl=t.hasScreenshot?'https://appassets.androidplatform.net/task-capture/'+t.id+'.jpg':'';updateEditor();showRecordPanel(anchor);remember()}
function syncTaskEditor(){if((!recordPanelOpen&&page!=='import')||!draft?.recognitionTaskId)return;const t=recognitionTasks.find(t=>t.id===draft.recognitionTaskId);if(!t)return;if(t.recordId&&working){const r=records.find(r=>r.id===t.recordId);if(r){applyRecord(r);remember()}return}if(t.status!==draft.taskStatus||(!previewUrl&&t.hasScreenshot)){draft={...storedDraft(),taskStatus:t.status,reason:t.message};working=['CAPTURING','QUEUED','RECOGNIZING'].includes(t.status);importError=t.status==='FAILED'?t.message:'';if(t.hasScreenshot)previewUrl='https://appassets.androidplatform.net/task-capture/'+t.id+'.jpg';updateEditor();remember()}}
function capsuleBounds(){return {left:innerWidth/2-80,top:innerHeight-90,width:160,height:52}}
function fluidPanel(panel,origin,reverse=false,done=()=>{}){
 FluidMotion.run(panel,origin,reverse,done,$('record-shade'));
}
function showRecordPanel(origin){if(page==='import')go('ledger');if(recordPanelClosing)closeRecordPanel(false);if(recordPanelOpen)return;closeSheet(false);const panel=$('record-panel');recordAnchor=origin?{left:origin.left,top:origin.top,width:origin.width,height:origin.height}:capsuleBounds();recordHome=document.createComment('record-editor-home');$('import').before(recordHome);panel.querySelector('.record-panel-content').append($('import'));recordPanelOpen=true;panel.hidden=false;panel.open=true;$('record-shade').hidden=false;PanelFocus.activate(panel,recordReturnControl);fluidPanel(panel,recordAnchor);remember()}
function closeRecordPanel(animate=true,done=()=>{}){const panel=$('record-panel');if(!panel.open){done();return}recordPanelOpen=false;recordPanelClosing=true;const finish=()=>{FluidMotion.stop(panel);panel.removeAttribute('style');panel.classList.remove('morphing');panel._fade?.cancel();panel._fade=null;panel.querySelector('.record-panel-content').style.opacity='1';panel.hidden=true;panel.open=false;$('record-shade').hidden=true;document.querySelector('.workspace').removeAttribute('aria-hidden');if(recordHome){recordHome.replaceWith($('import'));recordHome=null}PanelFocus.release(panel);recordPanelClosing=false;remember();done()};if(animate)fluidPanel(panel,recordAnchor,true,finish);else finish()}
function settingsHelp(){showSheet('如何记账',`<div class="settings-help"><h3>支付结果自动记账</h3><p>选择微信或支付宝并开启自动识别，按步骤授予页面识别权限。仅在所选应用已适配、可读取且通过隐私检查的成功页采集。有效支出有可用钱包时直接入账；可信的本人充值与提现需双方及费用信息完整，否则整笔待确认，普通收入由你主动记录。</p><h3>没抓到，摇一摇补录</h3><p>先在“应用白名单”选择允许摇一摇的应用，再打开单笔支付结果或账单详情，摇动手机补录。红包、快速返回等没有停留在成功页的交易，也可以用这个方式记录。</p><h3>收入由你主动记录</h3><p>自动识别记录支出，以及已适配且可信的本人钱包充值与提现；普通收入由你手动、截图或摇一摇记录。</p><h3>本人钱包互转</h3><p>手动选择两个自己的资产钱包，支持额外扣手续费或到账扣手续费。本金不计收支，手续费计支出。信息不全时整笔待确认，双方余额都不动；给别人转账仍是支出。</p><h3>迁移历史账本</h3><p>可先导入钱包余额快照，再导历史账单并关闭余额联动。导入默认关闭，后续编辑和删除也不改变余额；完整迁移可使用 ZIP 备份。</p><h3>钱包余额自动更新</h3><p>普通收支优先匹配明确的付款方式，其次使用应用钱包和默认钱包。本人互转必须确认双方，不套用默认钱包。新账单更新余额，退款回到原钱包；升级前的历史记录不追溯扣款。资产余额可为负数，也可手动校准。</p><h3>记录保存在本机</h3><p>识别在本机完成，无需配置，截图不上传。账单和商户图标保存在本机。截图由应用自动压缩和定期清理，清理截图不会删除账单。</p></div>`)}
function drawSettings(){
 if($('category-summary-count'))$('category-summary-count').textContent=`支出 ${directoryItems('expense').length} 个 · 收入 ${directoryItems('income').length} 个`;
 if($('classification-rules-summary')){const all=classificationRules?.customRules||[],enabled=all.filter(r=>r.enabled!==false).length;$('classification-rules-summary').textContent=all.length?`启用 ${enabled} 条 · 共 ${all.length} 条`:'按商家自动归类'}
 $('shake-application-count').textContent=captureHealth.shakeSelectedCount?`已选 ${captureHealth.shakeSelectedCount} 个`:'请选择应用';
 for(const toggle of document.querySelectorAll('[data-payment-app]')) {
  const app=(automatic.applications||[]).find(a=>a.packageName===toggle.dataset.paymentApp);
  toggle.setAttribute('aria-checked',String(!!(automatic.enabled&&app?.selected)));
  toggle.disabled=!!app&&!app.installed&&!app.selected;
  toggle.title=app&&!app.installed?'请先安装'+app.label:'';
 }
 $('automatic-status-label').textContent=automatic.enabled?(!channels.ocr?.permitted?'等待页面识别授权':automationRunning?'已开启 · 支出及已适配的本人充值/提现':'正在启动识别服务'):'首次开启，将引导完成页面识别授权';
 for(const toggle of document.querySelectorAll('[data-channel]')){
  const key=toggle.dataset.channel,state=channels[key]||{enabled:false,permitted:false};
  toggle.setAttribute('aria-checked',String(!!state.enabled));
  let label=state.enabled?'已开启':'已关闭';
  if(key==='ocr'&&captureHealth.shakeSelectionRequired)label='请选择应用';else if(state.enabled&&!state.permitted)label='等待授权';
  else if(state.enabled&&state.permitted)label=key==='ocr'?(automationRunning?'运行中':'正在启动'):key==='sms'?'等待交易短信':state.connected?'已连接':'等待连接';
  const detail=!state.enabled&&state.permitted?'系统仍授权':key==='ocr'?(state.enabled?'检查后台设置':'截图识别'):key==='sms'?'银行交易短信':'交易通知';
  const copy=toggle.dataset.channel==='ocr'?document.querySelector('[data-channel-info=ocr]'):toggle.parentElement.querySelector('.setting-copy');
  copy.querySelector('.permission').textContent=`${label} · ${detail}`;
  copy.setAttribute('aria-label',`${toggle.getAttribute('aria-label')}，${label}，${detail}`);
 }
}
function drawChart(list,now=new Date(),aggregated=null){
 const [year,mo]=month.split('-').map(Number),days=new Date(year,mo,0).getDate(),current=localMonth(now.getTime());
 const eligible=list.filter(r=>r.state==='POSTED'&&recordMonth(r)===month&&r.amountCents!=null&&['INCOME','EXPENSE','TRANSFER'].includes(r.direction));
 const recordDay=r=>{const trusted=trustedReceiptDate(r);return trusted?Number(trusted.slice(8,10)):new Date(r.occurredAt).getDate()};
 // Current months stop at today, except explicitly dated future records. Past months use the full month.
 const lastDay=month<current?days:Math.max(month===current?now.getDate():1,...(aggregated?aggregated.map(r=>r.day):eligible.map(recordDay)));
 const mode=filter==='income'?'income':'expense',name=mode==='income'?'累计收入':'累计支出',values=Array(lastDay).fill(0n);
 if(aggregated)aggregated.filter(r=>r.direction===(mode==='income'?'INCOME':'EXPENSE')).forEach(r=>values[r.day-1]+=cents(r.amount));else eligible.filter(r=>mode==='income'?r.direction==='INCOME':r.direction!=='INCOME').forEach(r=>values[recordDay(r)-1]+=mode==='income'?cents(r.amountCents):expenseOf(r));
 const sum=values.reduce((s,v)=>s+v,0n);const cumulative=[0n,...values.map((v,i)=>values.slice(0,i+1).reduce((s,x)=>s+x,0n))];
 // A simple sparkline: zero at the beginning, cumulative values with a little headroom.
 const max=Math.max(Number(sum),100)*1.08,y=v=>54-Number(v)/max*46,points=cumulative.map((v,i)=>[i*250/lastDay,y(v)]),zero=y(0);
 const path=points.map(([x,y],i)=>`${i?'L':'M'}${x.toFixed(2)} ${y.toFixed(2)}`).join(' '),svg=document.querySelector('.chart svg');
 svg.querySelectorAll('path')[0].setAttribute('d',`${path} V${zero} H0Z`);svg.querySelectorAll('path')[1].setAttribute('d',path);
 svg.querySelector('g').innerHTML=[0,lastDay].map(d=>`<circle cx="${points[d][0]}" cy="${points[d][1]}" r="1.5"/>`).join('');
 svg.setAttribute('role','img');svg.setAttribute('aria-label',`${year}年${mo}月每日${name}，月初从零开始，截至${mo}月${lastDay}日，${name}${money(sum)}元。仅计入已确认账单。`);
 let caption=document.querySelector('.trend-caption');if(!caption){caption=document.createElement('button');caption.className='trend-caption';caption.type='button';document.querySelector('.chart').prepend(caption)}caption.textContent=`每日${name} · 查看明细`;caption.onclick=showTrendDetails;caption.setAttribute('aria-label',`查看每日${name}明细`);
 const buckets=Array(8).fill(0n);values.forEach((v,i)=>buckets[Math.min(7,Math.floor(i/lastDay*8))]+=v);const peak=Math.max(1,...buckets.map(Number));
 $('home-bars').innerHTML=buckets.map((v,i)=>`<i style="height:${v?Math.max(6,Number(v)/peak*51):3}px;--bar-index:${i}" aria-hidden="true"></i>`).join('');
 $('home-bars').setAttribute('aria-label',`${name}${money(sum)}元，按日期分为八段`);
 svg.dataset.endDay=lastDay;svg.dataset.endCents=sum;svg.dataset.maxCents=max;svg.dataset.dailyCents=JSON.stringify(cumulative.map(value=>value<=BigInt(Number.MAX_SAFE_INTEGER)?Number(value):value.toString()));
}
function showTrendDetails(){
 const svg=document.querySelector('.chart svg'),last=Number(svg.dataset.endDay),[year,mo]=month.split('-').map(Number),list=monthRecords(),mode=filter==='income'?'income':'expense',label=mode==='income'?'收入':'支出';let cumulative=0n;
 const rows=Array.from({length:last},(_,i)=>{const day=i+1,total=ledgerView?.summary?.days?ledgerView.summary.days.filter(r=>r.day===day&&r.direction===(mode==='income'?'INCOME':'EXPENSE')).reduce((s,r)=>s+cents(r.amount),0n):sums(list.filter(r=>recordDay(r)===day))[mode];cumulative+=total;return `<tr><th scope="row">${mo}/${day}</th><td>${money(total)}</td><td>${money(cumulative)}</td></tr>`}).join('');
 showSheet(`${year}年${mo}月每日${label}`,`<div class="trend-curve">${svg.outerHTML}</div><p class="sheet-copy">仅计入已确认${label}账单。月初从 0 开始，逐日累加${label}金额；待确认及失败的识别不计入。</p><div class="trend-table-wrap"><table class="trend-table"><thead><tr><th scope="col">日期</th><th scope="col">当日${label}（元）</th><th scope="col">累计${label}（元）</th></tr></thead><tbody>${rows}</tbody></table></div>`);
}

function refresh(){
 if(!native){render();return true}
 try{
  const scope=listScope();
  const result=JSON.parse(native.snapshotFirst?native.snapshotFirst(month,query,filterDirection(),viewKey):native.snapshotView?native.snapshotView(month,query,viewLimit,filterDirection(),viewKey):native.snapshot());
  if(result.error||result.ok===false)throw Error(result.error);
  if(result.unchanged){if(activeRulesSheetRefresh){activeRulesSheetRefresh(true);drawSettings()}return false}
  const retainTail=!!native.snapshotFirst&&loadedScope===scope&&dataRevision===result.dataRevision&&records.length>(result.records||[]).length;
  const previousView=ledgerView;
  records=retainTail?[...result.records,...records.slice(result.records.length)]:result.records;
  viewKey=result.viewKey||'';dataRevision=result.dataRevision||'';loadedScope=scope;
  ledgerView=result.ledgerView||null;
  if(retainTail&&ledgerView){ledgerView.hasMore=previousView.hasMore;ledgerView.nextCursor=previousView.nextCursor}
  recognitionTasks=result.recognitionTasks||[];channels=result.channels;accounts=result.accounts||[];bankOptions=result.bankOptions||[];customCategories=result.customCategories||{expense:[],income:[]};if(result.categoryDirectory)categoryDirectory=result.categoryDirectory;if(result.categoryIconOptions)categoryIconOptions=result.categoryIconOptions;if(result.classificationRules)applyClassificationRules(result.classificationRules);else if(native.classificationRules){try{const cr=JSON.parse(native.classificationRules());if(cr.ok&&cr.classificationRules)applyClassificationRules(cr.classificationRules)}catch(_){}}automationRunning=!!result.automationRunning;automatic=result.automatic||{enabled:false,applications:[]};screenshotStorage=result.screenshotStorage||{};captureHealth=result.captureHealth||{enabled:false,ready:false};walletSettings=result.walletSettings||{defaultAccountUid:null,appBindings:{}};bankCodeOptions=result.bankCodeOptions||[];
  showUndo(result.pendingDeletion);render();syncTaskEditor();syncRecordHistory();if(manualOpen||page==='manual')drawCategories();else drawManualWallet();if(page==='stats')drawStats();if(draft){updateEditor();drawRecordWallet()}if(activeRulesSheetRefresh)activeRulesSheetRefresh(false);return true;
 }catch(_){notify('账本读取失败，请重试');return false}
}
function loadMoreRecords(){
 if(loadingPage)return;
 if(!native?.recordPage){viewLimit+=200;refresh();return}
 const cursor=ledgerView?.nextCursor;if(!cursor)return;
 loadingPage=true;
 try{
  const result=JSON.parse(native.recordPage(month,query,filterDirection(),cursor.occurredAt,cursor.id,dataRevision));
  if(!result.ok)throw Error(result.error);
  if(result.reset){viewKey='';refresh();notify('账本已更新，请继续加载');return}
  const knownIds=new Set(records.map(r=>r.id));records.push(...result.records.filter(r=>!knownIds.has(r.id)));
  ledgerView.hasMore=result.ledgerView.hasMore;ledgerView.nextCursor=result.ledgerView.nextCursor;render();
 }catch(_){notify('更多账单读取失败，请重试')}finally{loadingPage=false}
}
function newDraft(){editorBaseline=null;const defCat=findCategoryItem('EXPENSE',null,'餐饮');draft={id:0,submissionToken:submissionToken(),walletAccountUid:walletSettings.defaultAccountUid,manualWalletSelected:false,_categoryEdited:true,amount:'',direction:'EXPENSE',merchant:'',category:defCat.name,categoryId:defCat.id,occurredAt:Date.now(),_originalOccurredAt:Date.now(),_timeEdited:false,note:'',state:'REVIEW',source:'MANUAL',reason:'',rawText:''};previewUrl='';importError='';updateEditor()}
function recordWithDetails(record){
 if(record._detailsLoaded||!native?.recordDetails)return {...record};
 try{const details=JSON.parse(native.recordDetails(record.id));if(!details.ok)throw Error(details.error);const merged={...record,...details.record,receipt:details.record?.receipt!==undefined?details.record.receipt:(record.receipt??null),rawText:details.rawText,history:details.history,_detailsLoaded:true};if(merged.categoryDecision===undefined&&record.categoryDecision!==undefined)merged.categoryDecision=record.categoryDecision;return merged}
 catch(_){notify('详情读取失败，请重新打开');return {...record,history:[],rawText:''}}
}
function syncRecordHistory(){
 if(!draft?.id||(!recordPanelOpen&&page!=='import'))return;
 const current=records.find(r=>r.id===draft.id);if(!current)return;
 const revision=r=>JSON.stringify([r.refund,r.historyRevision,r.hasScreenshot,r._storedMerchant??recordMerchant(r),r.category,r.categoryId,r.categoryIcon,r.categoryDecision?.explanation,r.categoryDecision?.source,r.categoryDecision?.categoryId,r.hasMerchantIcon,r.merchantIconKey,r.merchantIconRevision,r.iconStatus,r.paymentProvider,r.wallet,r.transfer,r.receipt]);
 if(revision(current)===revision(draft))return;
 const wasDirty=editorDirty(),edits=readDraft(),financialChanged=current.amountCents!==draft.amountCents,currentMerchant=recordMerchant(current);
 const merchant=edits.merchant===(draft._storedMerchant??draft.merchant)?currentMerchant:edits.merchant;
 const {transfer:transferInput,...fields}=edits;draft={...draft,...recordWithDetails(current),...fields,transferInput:wasDirty?transferInput:undefined,merchant,_storedMerchant:currentMerchant,amount:financialChanged?String(current.amountCents/100):edits.amount,...(!wasDirty?{_originalOccurredAt:current.occurredAt,_timeEdited:false}:{})};
 if(!wasDirty){const resolved=resolveRecordCategory(current);draft.category=resolved.name;draft.categoryId=resolved.id;draft.expectedRevision=current.wallet?.revision??0}updateEditor();if(!wasDirty)editorBaseline=JSON.stringify(readDraft());remember();
}
function readDraft(){
 const direction=$('direction').dataset.value,category=$('category').textContent,categoryId=direction==='TRANSFER'?null:($('category').dataset.categoryId||findCategoryItem(direction,draft?.categoryId,category)?.id||null);
 let occurredAt;
 if(draft&&!draft._timeEdited&&draft._originalOccurredAt!=null){occurredAt=draft._originalOccurredAt}
 else if(draft&&(draft.refund||localTime(draft.occurredAt)===$('occurred-at').value)){occurredAt=draft.occurredAt}
 else{occurredAt=new Date($('occurred-at').value).getTime()}
 return {merchant:$('merchant').value,category,...(direction!=='TRANSFER'&&categoryId?{categoryId}:{}),amount:$('amount').value,direction,occurredAt,timeEdited:!!draft?._timeEdited,categoryEdited:!!draft?._categoryEdited,note:$('note').value,...(draft?.manualWalletSelected?{walletAccountUid:draft.walletAccountUid,manualWalletSelected:true}:{}),...(direction==='TRANSFER'?{transfer:readTransfer('record')}:{})};
}
function updateEditor(){if(!draft)return;const resolvedCat=resolveRecordCategory(draft);$('merchant').value=draft.merchant||'';$('merchant').placeholder=draft.receipt&&typeof draft.receipt==='object'&&!draft.merchant?'未提供':'商户名称';$('category').textContent=resolvedCat.name||draft.category||'其他';$('category').dataset.categoryId=draft.direction==='TRANSFER'?'':(resolvedCat.id||draft.categoryId||'');const catExplain=$('category-explanation'),decisionText=formatCategoryDecision({...draft,direction:$('direction')?.dataset?.value||draft.direction});if(catExplain){catExplain.hidden=!decisionText;catExplain.textContent=decisionText}const trustedDate=trustedReceiptDate(draft,draft._timeEdited);$('occurred-at').value=trustedDate?`${trustedDate}T00:00`:localTime(draft.occurredAt||Date.now());updateTimeChip();$('amount').parentElement.classList.remove('error-field');$('amount').value=draft.amount??(draft.amountCents!=null?String(draft.amountCents/100):'');$('note').value=draft.note||'';$('direction').dataset.value=draft.direction||'';$('direction').textContent=directionLabel(draft.direction);updateAmountSign();$('source-label').textContent=draft.captureMode==='page'?'交易自动识别':({OCR:'摇一摇识别',IMAGE:'截图识别',SMS:'短信记账',NOTIFICATION:'通知记账',MANUAL:'手动记账'})[draft.source]||'';
 const posted=draft.state==='POSTED';
 const editor=$('import'),automatic=['SMS','NOTIFICATION'].includes(draft.source),aiRecord=['OCR','IMAGE'].includes(draft.source);
 editor.classList.toggle('standard-record',!!draft.id&&(automatic||draft.source==='MANUAL'));editor.classList.toggle('ai-record',aiRecord);editor.classList.toggle('posted-record',posted);
 editor.classList.toggle('compact-record',!!(draft.id||draft.recognitionTaskId));
 const hero=editor.querySelector('.record-hero');hero.hidden=!draft.id;hero.innerHTML=draft.id?ledgerIcon(draft):'';
 const original=$('original-message');original.hidden=true;original.querySelector('p').textContent=draft.rawText||'';
document.querySelector('#import .pending').textContent=draft.recognitionTaskId?(taskNames[draft.taskStatus]||'识别记录'):(posted?'已入账':'待确认');$('editor-title').textContent=captureName(draft)||'手动记账';$('delete-record').hidden=(!draft.id&&!draft.recognitionTaskId)||working;$('delete-record').textContent=draft.recognitionTaskId?'删除识别记录':'删除账单';$('raw-text-button').hidden=!draft.rawText;document.querySelector('.editor-tools').style.display=draft.rawText?'flex':'none';$('review-reason').hidden=posted||!draft.reason;$('review-reason').textContent=draft.reason||'';
 const scanner=document.querySelector('.scanner');scanner.hidden=draft.source==='MANUAL'&&!!draft.id;const canPick=!working&&!draft.id&&!draft.recognitionTaskId,canView=!!previewUrl&&!working;scanner.setAttribute('aria-disabled',String(!canPick&&!canView));scanner.setAttribute('aria-label',canView?'查看原始截图':canPick?'选择支付截图':'暂无原始截图');scanner.tabIndex=canPick||canView?0:-1;scanner.classList.toggle('placeholder',!previewUrl);scanner.classList.toggle('has-preview',!!previewUrl);$('capture-preview').hidden=!previewUrl;if(previewUrl)$('capture-preview').src=previewUrl;
 document.querySelector('.scanning').textContent=working?(draft.taskStatus==='CAPTURING'?'正在获取账单截图…':draft.taskStatus==='QUEUED'?'已获取截图，等待识别…':'正在识别账单…'):importError||(previewUrl?'本次识别截图 · 请核对记账信息':draft.id?(draft.source==='OCR'||draft.source==='IMAGE'?'这笔历史记录未保存原始截图，可查看识别原文并核对字段':'此记录来自'+(captureName(draft)||'手动记账')+'，没有截图'):'点击上方选择支付截图，自动识别');
 document.querySelector('.scanner').classList.toggle('no-record-image',!!(draft.id||draft.recognitionTaskId)&&!previewUrl);
 document.querySelector('#import .page-title h1').textContent=draft.recognitionTaskId?'识别记录':draft.id?'账单详情':'截图智能导入';
 document.querySelector('#import .subtitle').textContent=draft.id?(posted?'已自动入账，可编辑或删除':'请补全提示项后保存'):'主动选择支付截图，自动识别后本地记账';
 document.querySelector('#import .subtitle').hidden=!!draft.id;
 document.querySelector('.scanning').classList.toggle('recognition-error',!!importError);document.querySelector('.scanning').hidden=!!draft.id||!!draft.recognitionTaskId&&!working;document.querySelector('.dots').hidden=!!draft.id;
 document.querySelectorAll('.dots span').forEach((e,i)=>e.classList.toggle('active',working?i===0:i===1));scanner.classList.toggle('working',working);
 const refundPending=!!(draft.refund||draft.standaloneRefund)&&!posted;
 document.querySelector('#import-form .primary').disabled=working;document.querySelector('#import-form .primary').hidden=refundPending;document.querySelector('#import-form .primary').textContent=working?'识别中，请稍候':posted?'保存修改':'确认记账';document.querySelectorAll('#merchant,#amount,#note,#category,#time-label').forEach(e=>e.disabled=working);
 $('amount').readOnly=!!(draft.refund||draft.standaloneRefund);$('amount').min=posted&&draft.refund?'0':'0.01';$('merchant').readOnly=!!draft.refund;$('time-label').disabled=working||!!draft.refund;
 $('category').setAttribute('aria-label',draft.refund?'修改分类':'修改分类与收支方向');
 $('refund-match').hidden=!refundPending||!(draft.refundCandidateCount>0);$('refund-match').disabled=working;
 $('refund-summary').hidden=!(draft.refund||draft.standaloneRefund);
 if(draft.standaloneRefund)$('refund-summary').textContent=`退款回执 · 本次成功退款 ¥${money(draft.standaloneRefund.refundedCents)} · 请核对原账单`;
 if(draft.refund){const r=draft.refund;$('refund-summary').textContent=refundPending?`原付 ¥${money(r.originalAmountCents)} · ${r.mode==='single'?'本次':'累计'}成功退款 ¥${money(r.refundedCents)}`:`${r.refundedCents===r.originalAmountCents?'已全额退款 · ':''}原付 ¥${money(r.originalAmountCents)} · 已退 ¥${money(r.refundedCents)} · 实付 ¥${money(draft.amountCents)}`}
 drawRecordWallet();renderReceiptDetails();renderCaptureGallery();
}
function renderReceiptDetails(){
 const section=$('receipt-details');if(!section)return;
 const receipt=draft?.receipt;
 if(!receipt||typeof receipt!=='object'){section.hidden=true;section.innerHTML='';delete section.dataset.recordId;return}
 const recordKey=String(draft.id||draft.recognitionTaskId||0),keepExpanded=section.dataset.recordId===recordKey&&$('receipt-evidence-toggle')?.getAttribute('aria-expanded')==='true';
 section.dataset.recordId=recordKey;section.hidden=false;
 const rows=receiptFieldSpecs.map(([key,label])=>{
  const rawVal=receiptFieldValue(receipt[key]),missing=key==='merchant'&&!rawVal;
  if(key!=='merchant'&&!rawVal)return '';
  const displayVal=missing?'未提供':key==='sourceApp'?formatReceiptSourceApp(rawVal):rawVal;
  return `<div class="receipt-field" data-receipt-field="${key}"><dt>${label}</dt><dd class="receipt-value${missing?' is-missing':''}">${escapeText(displayVal)}</dd></div>`;
 }).filter(Boolean).join('');
 const evidenceItems=receiptFieldSpecs.map(([key,label])=>{
  const f=receipt[key];if(!f||typeof f!=='object')return '';
  const val=receiptFieldValue(f),raw=String(f.rawText??'').trim()||val,src=String(f.source??'').trim();
  if(!val&&!raw)return '';
  const srcName=receiptSourceNames[src]||'凭证记录';
  return `<div class="receipt-evidence-item" data-evidence-field="${key}" data-evidence-source="${escapeText(src)}"><div class="receipt-evidence-meta"><b>${label}</b><span class="receipt-source-tag">${escapeText(srcName)}</span></div><div class="receipt-evidence-raw">${escapeText(raw)}</div></div>`;
 }).filter(Boolean);
 const timeRaw=String(receipt.time?.rawText??'').trim();
 if(timeRaw){
  const timeSrc=String(receipt.time?.source??'').trim();
  const timeSourceNames={EXPLICIT:'识别文字',YEAR_INFERRED:'年份推断',RECOGNITION_FALLBACK:'识别时间',MANUAL:'人工修改'};
  const timeSrcName=timeSourceNames[timeSrc]||receiptSourceNames[timeSrc]||'识别文字';
  evidenceItems.push(`<div class="receipt-evidence-item" data-evidence-field="time" data-evidence-source="${escapeText(timeSrc)}"><div class="receipt-evidence-meta"><b>交易时间</b><span class="receipt-source-tag">${escapeText(timeSrcName)}</span></div><div class="receipt-evidence-raw">${escapeText(timeRaw)}</div></div>`);
 }
 section.innerHTML=`<div class="receipt-heading"><b>凭证信息</b><small class="receipt-readonly">只读</small></div><dl class="receipt-fields">${rows}</dl>${evidenceItems.length?`<div class="receipt-verify"><button type="button" id="receipt-evidence-toggle" class="receipt-evidence-toggle" aria-expanded="${keepExpanded}" aria-controls="receipt-evidence-panel"><span>核对原始文字与来源</span><small class="receipt-toggle-state">${keepExpanded?'收起':'查看'}</small></button><div id="receipt-evidence-panel" class="receipt-evidence-panel"${keepExpanded?'':' hidden'}>${evidenceItems.join('')}</div></div>`:''}`;
 const toggle=$('receipt-evidence-toggle'),panel=$('receipt-evidence-panel');
 if(toggle&&panel)toggle.onclick=()=>{const next=panel.hidden;panel.hidden=!next;toggle.setAttribute('aria-expanded',String(next));const state=toggle.querySelector('.receipt-toggle-state');if(state)state.textContent=next?'收起':'查看'};
}
function historyKind(item){return ({payment:'付款截图',payment_supplement:'补充截图',refund_pending:'退款截图',partial_refund:'退款截图',full_refund:'退款截图',refund_supplement:'退款补图'})[item.kind]||'账单截图'}
function renderCaptureGallery(){
 const history=draft?.history||[],section=$('capture-gallery'),scroll=section.querySelector('.capture-thumbnails')?.scrollLeft||0;
 section.hidden=!draft?.id||history.length===0||draft.source==='MANUAL';
 if(section.hidden){section.innerHTML='';return}
 document.querySelector('.scanner').hidden=true;
 const available=history.filter(item=>item.hasScreenshot).length;
 section.innerHTML=`<div class="capture-gallery-heading"><b>原始截图 <span>${available} 张</span></b><small>${history.length>2?'左右滑动 · 点击放大':'点击放大'}</small></div><div class="capture-thumbnails" tabindex="0" aria-label="账单截图，可左右滑动">${history.map(item=>`<button type="button" class="history-image${item.hasScreenshot?'':' missing-image'}" data-history-sequence="${item.sequence}" aria-label="查看${historyKind(item)}，第 ${item.sequence} 张">${item.hasScreenshot?`<img loading="lazy" src="https://appassets.androidplatform.net${escapeText(item.screenshotUrl)}" alt="${historyKind(item)}"/>`:'<i class="fa-regular fa-image" aria-hidden="true"></i>'}<span><b>${historyKind(item)}</b><small>${item.hasScreenshot?'点击查看':'未保存或已清理'}</small></span></button>`).join('')}</div>`;
 section.querySelector('.capture-thumbnails').scrollLeft=scroll;
 section.querySelectorAll('[data-history-sequence]').forEach(button=>button.onclick=()=>viewHistory(Number(button.dataset.historySequence)));
}
function viewHistory(sequence){
 const history=draft?.history||[],index=history.findIndex(item=>item.sequence===sequence);if(index<0)return;
 showSheet('原始截图',`<div id="capture-pages" class="capture-pages" tabindex="0" aria-label="左右滑动切换截图">${history.map(item=>`<figure class="capture-page">${item.hasScreenshot?`<img class="capture-full" loading="lazy" src="https://appassets.androidplatform.net${escapeText(item.screenshotUrl)}" alt="${historyKind(item)}，第 ${item.sequence} 张"/>`:'<p class="sheet-copy">本次截图未保存或已清理。</p>'}</figure>`).join('')}</div><div class="capture-navigation"><button type="button" id="capture-previous" aria-label="上一张截图">‹</button><span id="capture-position" role="status" aria-live="polite"></span><button type="button" id="capture-next" aria-label="下一张截图">›</button></div><div id="capture-description"></div>`);
 $('sheet').classList.add('capture-viewer');
 const pages=$('capture-pages');let current=index;
 const describe=()=>{const item=history[current];$('sheet-title').textContent=`${historyKind(item)} · ${current+1} / ${history.length}`;$('capture-position').textContent=`${current+1} / ${history.length}`;$('capture-previous').disabled=current===0;$('capture-next').disabled=current===history.length-1;$('capture-description').innerHTML=`<p class="capture-caption">${localTime(item.at).replace('T',' ')}${item.refundedCents?` · 已退 ¥${money(item.refundedCents)} · 实付 ¥${money(item.amountCents)}`:''}</p>${item.rawText?`<details class="capture-original"><summary>查看识别原文</summary><div class="raw-text">${escapeText(item.rawText)}</div></details>`:''}`};
 const move=delta=>pages.scrollTo({left:Math.max(0,Math.min(history.length-1,current+delta))*pages.clientWidth,behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth'});
 $('capture-previous').onclick=()=>move(-1);$('capture-next').onclick=()=>move(1);
 pages.onkeydown=e=>{if(e.key==='ArrowLeft'||e.key==='ArrowRight'){e.preventDefault();move(e.key==='ArrowLeft'?-1:1)}};
 pages.onscroll=()=>{const next=Math.max(0,Math.min(history.length-1,Math.round(pages.scrollLeft/Math.max(1,pages.clientWidth))));if(next!==current){current=next;describe()}};
 pages.scrollLeft=index*pages.clientWidth;describe();
}
function chooseRefundTarget(){if(!native||!draft?.id)return;try{const result=JSON.parse(native.refundCandidates(draft.id));if(!result.ok){notify(result.error||'无法读取原账单');return}showSheet('选择退款对应的原账单',`<p class="sheet-copy">核对商户、原付款金额和时间，确认后将更新原账单，并保留两次识别截图。</p>${result.candidates.map(r=>`<button type="button" class="sheet-row" data-refund-target="${r.id}"><span>${escapeText(r.merchant)}<br><small>${recordDisplayTime(r)} · 原付 ¥${money(r.originalAmountCents)}</small></span><span>选择 ›</span></button>`).join('')||'<p class="sheet-copy">没有找到可关联的原账单。请先记录原付款，或核对支付平台和原付款金额后重新识别。</p>'}`);document.querySelectorAll('[data-refund-target]').forEach(button=>button.onclick=()=>confirmRefundTarget(Number(button.dataset.refundTarget)))}catch(_){notify('读取失败，请稍后重试')}}
function confirmRefundTarget(targetId){const target=records.find(r=>r.id===targetId);if(!(draft?.refund||draft?.standaloneRefund)||!target)return;const pendingId=draft.id;const refund=draft.refund||{...draft.standaloneRefund,originalAmountCents:target.refund?.originalAmountCents||target.amountCents,mode:'single'};showSheet('确认更新原账单？',`<p class="sheet-copy">${escapeText(target.merchant)} · ${recordDisplayTime(target)}<br>原付 ¥${money(refund.originalAmountCents)}，${refund.mode==='single'?'本次':'累计'}成功退款 ¥${money(refund.refundedCents)}。将按原账单已记录的退款核算实付金额，重复退款不会再次扣减，原分类和备注继续保留。</p><div class="sheet-actions"><button type="button" id="refund-back">返回选择</button><button type="button" id="confirm-refund" class="primary">确认更新原账单</button></div>`);$('refund-back').onclick=chooseRefundTarget;$('confirm-refund').onclick=()=>{try{const result=JSON.parse(native.confirmRefund(pendingId,targetId));if(!result.ok){notify(result.error||'退款更新失败，请重新核对');return}closeSheet();refresh();const updated=records.find(r=>r.id===result.id);if(updated){applyRecord(updated);remember()}notify(result.updated?'退款已更新，原账单截图已保留':'该退款已记录，无需重复扣减')}catch(_){notify('退款更新失败，请重试')}}}
function updateTimeChip(){
 const trustedDate=trustedReceiptDate(draft,draft?._timeEdited);
 const isDateOnly=Boolean(trustedDate);
 const val=$('occurred-at').value||'';
 const formatted=trustedDate?trustedDate.replace(/-/g,'/'):val.replace('T',' ').replace(/-/g,'/');
 $('time-label').textContent=formatted;
 $('time-label').title=(isDateOnly?'修改交易日期：':'修改交易时间：')+formatted;
 $('time-label').setAttribute('aria-label',(isDateOnly?'修改交易日期：':'修改交易时间：')+formatted);
 const basis=receiptTimeBasis(draft?.receipt?.time,draft?._timeEdited,draft);
 const basisEl=$('time-basis');
 if(basisEl){basisEl.textContent=basis;basisEl.hidden=!basis}
}
function updateAmountSign(){$('amount-sign').textContent='¥ '+(({INCOME:'+',EXPENSE:'-',TRANSFER:''})[$('direction').dataset.value]||'');drawRecordWallet()}
function applyRecord(r,keepPreview=false){const detailed=recordWithDetails(r),resolved=resolveRecordCategory(detailed),merchant=recordMerchant(detailed);if($('receipt-details'))delete $('receipt-details').dataset.recordId;draft={...detailed,merchant,category:resolved.name,categoryId:resolved.id,_storedMerchant:merchant,_categoryEdited:false,expectedRevision:detailed.wallet?.revision??r.wallet?.revision??0,submissionToken:submissionToken(),manualWalletSelected:false,_originalOccurredAt:detailed.occurredAt,_timeEdited:false};previewUrl=detailed.hasScreenshot?'https://appassets.androidplatform.net/record-capture/'+r.id+'.jpg':'';working=false;importError='';updateEditor();editorBaseline=JSON.stringify(readDraft())}
function openRecord(id,keepPreview=false,anchor=null){
 const control=document.querySelector(`[data-pending="${id}"],[data-record="${id}"]`),origin=anchor||controlBounds(control);
 let r=records.find(x=>x.id===id);
 if(!r&&native?.recordDetails){try{const detail=JSON.parse(native.recordDetails(id));if(detail.ok)r={...detail.record,rawText:detail.rawText,history:detail.history,_detailsLoaded:true}}catch(_){}}
 if(!r){notify('账单已不存在');return}
 const targetMonth=recordMonth(r),targetFilter=r.direction==='TRANSFER'?'transfer':r.direction==='INCOME'?'income':'expense';
 if(month!==targetMonth||filter!==targetFilter){month=targetMonth;filter=targetFilter;viewLimit=200;viewKey='';refresh()}
 if(page!=='ledger'&&page!=='stats')go('ledger');
 recordReturnControl=control||document.activeElement;applyRecord(records.find(x=>x.id===id)||r,keepPreview);showRecordPanel(origin);remember();
}
function pickImage(){if(draft?.id||draft?.recognitionTaskId){notify('原始截图仅供核对，不能替换；请从新增入口导入新截图');return}if(working){notify('正在识别，请稍候');return}if(native)native.importImage();else notify('请在 Android 应用中导入截图')}
function viewOrPickImage(){if(previewUrl&&!working){showSheet('原始截图',`<img class="capture-full" src="${escapeText(previewUrl)}" alt="本笔账单的原始截图"/><p class="sheet-copy">仅供查看，原始截图不可替换。</p>`);$('sheet').classList.add('capture-viewer');return}pickImage()}
function editorDirty(){return !!draft?.id&&editorBaseline!==null&&JSON.stringify(readDraft())!==editorBaseline}
function cancelEditor(force=false){
 if(!force&&editorDirty()){
  showSheet('保留这次修改？','<p class="sheet-copy">账单内容已修改，还没有保存。</p><div class="sheet-actions"><button type="button" id="keep-editing">继续编辑</button><button type="button" id="discard-edits">放弃修改</button></div>');
  $('keep-editing').onclick=()=>closeSheet();$('discard-edits').onclick=()=>{closeSheet(false);cancelEditor(true)};return;
 }
 const returnPage=recordPanelOpen?page:'ledger',done=()=>{draft=null;editorBaseline=null;working=false;previewUrl='';go(returnPage)};
 if(recordPanelOpen)closeRecordPanel(true,done);else done();
}
function setChannel(key,enabled){if(native){native.setChannel(key,enabled);setTimeout(refresh,100)}else notify('系统权限需要在 Android 应用中开启')}
function revokeChannel(key){if(native){native.revokeChannel(key);setTimeout(refresh,100)}else notify('请在 Android 应用中管理系统授权')}
function privacy(){if(native?.privacyFrom)native.privacyFrom(nativeOrigin(document.querySelector('.privacy-banner')));else if(native)native.privacy();else showSheet('截图与账本保存在本机','<p class="sheet-copy">账单、账户、分类和备注，以及新增、修改、删除操作，均在本机完成和保存。</p><p class="sheet-copy">开启自动识别后，仅在所选应用能明确读取付款成功状态和金额、通过隐私检查后截图记录支出及已适配的本人钱包充值、提现；密码、验证和无法确认的页面跳过。摇一摇仅在你主动选择的应用白名单内取图，也须通过隐私检查。应用目录和图标只在本机用于选择，不上传。识别在本机完成，截图和账本不上传。</p><p class="sheet-copy">你可随时移除允许识别的应用、暂停识别，或撤销系统授权。</p>')}
function showSheet(title,html){
 if(title!=='自动分类规则')activeRulesSheetRefresh=null;
 const panel=$('sheet'),wasOpen=panel.open,wasClosing=sheetClosing;sheetClosing=false;panel.classList.remove('sheet-closing','capture-viewer');delete panel.dataset.fileOperation;
 $('sheet-title').textContent=title;$('sheet-content').innerHTML=html;$('sheet-content').scrollTop=0;
 if(wasOpen){if(wasClosing||panel._morph)FluidMotion.run(panel,sheetAnchor,false,()=>{},null);return}
 sheetAnchor=clickOrigin()||capsuleBounds();panel._returnControl=lastControl;panel.classList.add('fluid-pending');panel.showModal();
 // Establish the transparent backdrop before removing the pending state.
 getComputedStyle(panel,'::backdrop').opacity;
 // Delay one frame so callers can finish bank fields, image-viewer class and title.
 requestAnimationFrame(()=>{if(panel.open&&!sheetClosing)FluidMotion.run(panel,sheetAnchor,false,()=>{},null)});
}
function closeSheet(animate=true){
 activeRulesSheetRefresh=null;
 const panel=$('sheet');if(!panel?.open)return;if(sheetClosing&&animate)return;sheetClosing=true;panel.classList.add('sheet-closing');
 const finish=()=>{FluidMotion.stop(panel);panel.close();panel.classList.remove('capture-viewer','sheet-closing');sheetClosing=false;if(panel._returnControl?.isConnected)panel._returnControl.focus({preventScroll:true})};
 if(animate)FluidMotion.run(panel,sheetAnchor,true,finish,null);else finish();
}
$('sheet').addEventListener('cancel',e=>{e.preventDefault();closeSheet()});
$('sheet').addEventListener('click',e=>{if(e.target===$('sheet')){const r=$('sheet').getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)closeSheet()}});
function about(){if(native?.aboutFrom)native.aboutFrom(nativeOrigin(document.querySelector('.about-app')));else if(native)native.about();else showSheet('关于花有谱','<p class="sheet-copy">花有谱 · 少点手动，收支有谱</p>')}
function submitDraft(confirmDuplicate=false){if((draft?.refund||draft?.standaloneRefund)&&draft.state!=='POSTED'){chooseRefundTarget();return}if(working){notify('正在识别，请稍候');return}if($('direction').dataset.value==='TRANSFER'&&!validTransfer('record'))return;const values=readDraft();$('amount').parentElement.classList.remove('error-field');if(!/^\d+(\.\d{1,2})?$/.test(values.amount)||(Number(values.amount)<0||(Number(values.amount)===0&&!(draft?.refund&&draft.state==='POSTED'&&draft.amountCents===0)))){$('amount').parentElement.classList.add('error-field');$('amount').focus();notify('请输入大于零、最多两位小数的金额');return}if(!Number.isFinite(values.occurredAt)){$('occurred-at').focus();notify('请选择有效交易时间');return}if(!native){notify('请在 Android 应用中保存账单');return}const result=JSON.parse(native.save(JSON.stringify({...values,id:draft?.id||0,recognitionTaskId:draft?.recognitionTaskId||0,confirmDuplicate,expectedRevision:draft?.expectedRevision??draft?.wallet?.revision??0,submissionToken:draft?.submissionToken||(draft.submissionToken=submissionToken())})));if(result.duplicateRequired){showSheet('疑似重复，仍然保存？','<p class="sheet-copy">这笔账单可能已从其他渠道记录，请核对后再确认。</p><div class="sheet-actions"><button onclick="closeSheet()">返回核对</button><button class="primary" id="confirm-duplicate">仍然保存</button></div>');$('confirm-duplicate').onclick=()=>submitDraft(true);return}if(!result.ok){notify(result.error||'保存失败，请重试');return}const edited=draft?.state==='POSTED';filter=values.direction==='TRANSFER'?'transfer':values.direction==='INCOME'?'income':'expense';refresh();cancelEditor(true);notify(result.state==='REVIEW'?'已保存，请选择钱包后入账':edited?'修改已保存':'记账成功')}
function restoreState(state){if(!state)return;month=/^\d{4}-\d{2}$/.test(state.month)?state.month:month;filter=['income','expense','transfer','all'].includes(state.filter)?state.filter:'expense';draft=state.draft||null;if(draft&&draft._originalOccurredAt==null){draft._originalOccurredAt=draft.occurredAt;draft._timeEdited=false}editorBaseline=typeof state.editorBaseline==='string'?state.editorBaseline:null;if(draft)updateEditor();previewUrl=state.previewUrl||'';if(state.manual){manualType=['income','expense','transfer'].includes(state.manual.type)?state.manual.type:'expense';manualTransfer=state.manual.transfer||manualTransfer;$('manual-occurred-at').value=state.manual.occurredAt||localTime(Date.now());manualCategory=state.manual.category||'餐饮';manualCategoryId=state.manual.categoryId||null;$('manual-amount').value=state.manual.amount||'';$('manual-note').value=state.manual.note||'';$('manual-note-count').textContent=$('manual-note').value.length+'/500';manualWalletUid=state.manual.walletUid||walletSettings.defaultAccountUid;manualWalletSelected=!!state.manual.walletSelected;manualSubmissionToken=state.manual.submissionToken||submissionToken();drawManualWallet()}statsType=['expense','income','all'].includes(state.statsType)?state.statsType:'expense';rankingType=state.rankingType==='income'?'income':'expense';assetsHidden=!!state.assetsHidden;go(['ledger','import','settings','manual','stats','assets'].includes(state.page)?state.page:'ledger');refresh();if(draft){updateEditor();if(state.recordPanel)showRecordPanel(null)}if(state.manualPanel)beginManual(false);remember()}
window.LedgerUI={getState,back(){if(window.LedgerSetup?.isOpen()){window.LedgerSetup.close();return true}if($('sheet').open){closeSheet();return true}if(manualOpen||manualClosing){if(!manualClosing)closeManual();return true}if(recordPanelOpen||recordPanelClosing){if(!recordPanelClosing)cancelEditor();return true}if(searching){setSearchOpen(false);return true}if(page!=='ledger'){go('ledger');return true}return false},event(type,data){switch(type){case'fileOperation':if(data){showSheet('文件处理中',`<div class="export-progress" role="status"><span class="recognition-spinner" aria-hidden="true"></span><p>${escapeText(data)}…</p></div>`);$('sheet').dataset.fileOperation='true'}else if($('sheet').dataset.fileOperation){closeSheet();delete $('sheet').dataset.fileOperation}break;case'shakeApplications':openShakeWhitelist();break;case'permissionSetup':window.LedgerSetup?.render(data);break;case'fontScale':document.body.classList.toggle('large-text',data>=1.4);document.body.style.setProperty('--scaled-fab-font',`${35/Math.max(1,data)}px`);break;case'refresh':if(refresh()){if(page==='stats')drawStats();if(page==='assets')drawAssets();if(page==='manual')drawCategories()}break;case'statisticsPeriod':openStatistics(data);break;case'navigate':go(data);break;case'message':notify(data);break;case'restoreState':restoreState(data);break;case'openRecord':openRecord(data,true);break;case'preview':previewUrl=data.url;updateEditor();break;case'importStatus':working=!!data.working;if(working){newDraft();if(data.taskId){draft.recognitionTaskId=data.taskId;draft.taskStatus='CAPTURING'}go('import')}if(data.error)importError=String(data.error);updateEditor();break;case'importError':working=false;importError=typeof data==='string'?data:String(data?.error||'识别失败，请重新选择截图重试');updateEditor();break}}};
document.querySelectorAll('[data-filter]').forEach(b=>b.onclick=()=>{filter=b.dataset.filter;viewLimit=200;refresh();remember()});
document.querySelectorAll('[data-channel]').forEach(s=>s.onclick=()=>setChannel(s.dataset.channel,s.getAttribute('aria-checked')!=='true'));
$('month').onclick=()=>{showSheet('选择账本月份',`<input id="month-picker" class="month-input" type="month" value="${month}" min="2000-01" max="2100-12"/><div class="sheet-actions"><button id="month-prev">上个月</button><button id="month-next">下个月</button><button class="primary" id="month-apply">确定</button></div>`);const move=delta=>{const [y,m]=$('month-picker').value.split('-').map(Number),d=new Date(y,m-1+delta,1);$('month-picker').value=localTime(d.getTime()).slice(0,7)};$('month-prev').onclick=()=>move(-1);$('month-next').onclick=()=>move(1);$('month-apply').onclick=()=>{const value=$('month-picker').value;if(value){month=value;viewLimit=200;refresh();closeSheet();remember()}}};
$('category').onclick=()=>{
 const activeDir=()=>$('direction').dataset.value||'EXPENSE';
 showSheet(draft?.refund?'分类':'分类与收支方向',(draft?.refund?'':`<div class="direction-choices"><button type="button" data-direction="EXPENSE" class="${activeDir()==='EXPENSE'?'active':''}">支出</button><button type="button" data-direction="INCOME" class="${activeDir()==='INCOME'?'active':''}">收入</button><button type="button" data-direction="TRANSFER" class="${activeDir()==='TRANSFER'?'active':''}">转账</button></div>`)+'<div id="record-category-choices"></div><button type="button" id="record-manage-categories" class="sheet-row"><span><b>管理分类目录</b><small>添加、改名、排序或调整图标</small></span><span aria-hidden="true">›</span></button>');
 const renderChoices=dir=>{
  const box=$('record-category-choices'),manageBtn=$('record-manage-categories');if(!box)return;
  if(dir==='TRANSFER'){box.innerHTML='<p class="sheet-copy">本人钱包互转不计入收支分类，请在下方核对转出与转入钱包。</p>';if(manageBtn)manageBtn.hidden=true;return}
  if(manageBtn){manageBtn.hidden=false;manageBtn.onclick=()=>openCategoryManager(dir)}
  const items=directoryItems(dir),curId=$('category').dataset.categoryId||'',curName=$('category').textContent;
  box.innerHTML=items.map(c=>{const selected=curId?c.id===curId:c.name===curName;return `<button type="button" class="sheet-row category-sheet-row" data-category="${escapeText(c.name)}" data-category-id="${escapeText(c.id)}"><span class="category-sheet-label">${categoryBadge(c.name,c.direction,c.icon)}<b>${escapeText(c.name)}</b></span><span>${selected?'✓':''}</span></button>`}).join('');
  box.querySelectorAll('[data-category]').forEach(b=>b.onclick=()=>{const nextId=b.dataset.categoryId||'';$('category').textContent=b.dataset.category;$('category').dataset.categoryId=nextId;if(draft){draft._categoryEdited=true;draft.category=b.dataset.category;draft.categoryId=nextId||null;if(draft.id)draft.categoryDecision={...(draft.categoryDecision||{}),categoryId:nextId||null,source:'MANUAL',manual:true,ruleId:null,ruleVersion:null,explanation:'人工选择分类'};const catExplain=$('category-explanation'),decisionText=formatCategoryDecision({...draft,direction:$('direction')?.dataset?.value||draft.direction});if(catExplain){catExplain.hidden=!decisionText;catExplain.textContent=decisionText}}closeSheet();remember()});
 };
 renderChoices(activeDir());
 document.querySelectorAll('[data-direction]').forEach(b=>b.onclick=()=>{
  const nextDir=b.dataset.direction,prevDir=$('direction').dataset.value;
  $('direction').dataset.value=nextDir;$('direction').textContent=directionLabel(nextDir);
  if(nextDir==='TRANSFER'){$('category').textContent='转账';$('category').dataset.categoryId='';if(draft){if(prevDir!==nextDir)draft._categoryEdited=true;draft.category='转账';draft.categoryId=null}}
  else if(prevDir!==nextDir||!$('category').dataset.categoryId||!directoryItems(nextDir).some(c=>c.id===$('category').dataset.categoryId)){const nextCat=findCategoryItem(nextDir,null,null);$('category').textContent=nextCat.name;$('category').dataset.categoryId=nextCat.id;if(draft){if(prevDir!==nextDir)draft._categoryEdited=true;draft.category=nextCat.name;draft.categoryId=nextCat.id;if(draft.id&&prevDir!==nextDir)draft.categoryDecision={...(draft.categoryDecision||{}),categoryId:nextCat.id,source:'MANUAL',manual:true,ruleId:null,ruleVersion:null,explanation:'人工选择分类'}}}
  const catExplain=$('category-explanation'),decisionText=formatCategoryDecision({...draft,direction:nextDir});if(catExplain){catExplain.hidden=!decisionText;catExplain.textContent=decisionText}
  updateAmountSign();document.querySelectorAll('[data-direction]').forEach(e=>e.classList.toggle('active',e===b));renderChoices(nextDir);remember();
 });
};
$('direction').onclick=()=>{if(draft?.refund||draft?.standaloneRefund){notify('含退款历史的消费不能直接改为转账');return}$('category').click()};
$('time-label').onclick=()=>{
 const trustedDate=trustedReceiptDate(draft,draft?._timeEdited);
 const isDateOnly=Boolean(trustedDate);
 const origPickerVal=trustedDate?`${trustedDate}T00:00`:(draft?._originalOccurredAt!=null?localTime(draft._originalOccurredAt):'');
 const currentVal=$('occurred-at').value||origPickerVal;
 showSheet(isDateOnly?'修改交易日期':'修改交易时间',`<input id="time-picker" class="month-input" type="datetime-local" value="${currentVal}"/><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button class="primary" id="time-apply">确定</button></div>`);
 $('time-apply').onclick=()=>{
  const value=$('time-picker').value;
  if(value){
   if(origPickerVal&&value===origPickerVal){
    if(draft){draft._timeEdited=false;draft.occurredAt=draft._originalOccurredAt}
   }else{
    if(draft){draft._timeEdited=true;draft.occurredAt=new Date(value).getTime()}
   }
   $('occurred-at').value=value;
   updateTimeChip();
   closeSheet();
   remember();
  }
 };
};
$('import-form').onsubmit=e=>{e.preventDefault();submitDraft()};$('import-form').oninput=remember;
$('refund-match').onclick=chooseRefundTarget;
function merchantIconExplanation(record){
const reason=record?.iconDetails?.match(/(?:^|;\s*)reason=(\w+)/)?.[1];
const cropReason={image_size:'截图大小不符合裁剪要求',image_dimensions:'截图尺寸无效',out_of_bounds:'图标位置超出截图范围',too_small:'图标区域太小',aspect_ratio:'区域比例不符合图标要求',too_large:'图标区域过大',decode_failed:'截图无法读取',no_detail:'定位区域接近纯色，未保存为图标',encode_failed:'图标保存失败',processing_failed:'图标处理失败'}[reason];
return record?.iconStatus==='crop_rejected'&&cropReason?cropReason:{cached:'已使用本机商家图标',saved:'已提取并保存',missing_field:'未找到图标位置',not_visible:'未找到明确的商家图标',invalid_metadata:'图标位置信息无效',wrong_kind:'区域内容不是商家图标',confidence_low:'图标位置不够明确',invalid_box_or_confidence:'图标坐标、单位或置信度无效',crop_rejected:'定位区域未通过图片校验'}[record?.iconStatus];
}
$('raw-text-button').onclick=()=>{const status=merchantIconExplanation(draft);showSheet('识别原文',`${status?`<p class="sheet-copy">商家图标：${escapeText(status)}</p>`:''}<div class="raw-text">${escapeText(draft?.rawText||'')}</div>`)};
function requestDelete(id,task=false){
 if(!Number.isSafeInteger(id)||id<=0){notify('记录编号无效');return}
 const record=task?recognitionTasks.find(t=>t.id===id):records.find(r=>r.id===id);
 if(task&&['CAPTURING','QUEUED','RECOGNIZING'].includes(record?.status)){notify('正在识别，请稍后再删除');return}
 showSheet(task?'删除这次识别记录？':'删除这笔账单？',`<p class="sheet-copy">${task?'删除这次未入账的识别记录及本机截图，不影响其他账单。':`${escapeText(record?.merchant||'这笔账单')}${record?' · ¥ '+money(record.amountCents):''}，删除后将重新计算收支汇总。${record?.wallet?.eligibility==='ACTIVE'?'将回滚本笔已生效的钱包余额变化。':'本笔未联动，钱包余额不变。'}`}</p><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button class="danger" id="confirm-delete">确认删除</button></div>`);
 $('confirm-delete').onclick=()=>deleteEntry(id,task);
}
function deleteEntry(id,task=false){
 if(!Number.isSafeInteger(id)||id<=0){notify('记录编号无效');return}
 if(task&&['CAPTURING','QUEUED','RECOGNIZING'].includes(recognitionTasks.find(t=>t.id===id)?.status)){notify('正在识别，请稍后再删除');return}
 if(!native){notify('请在 Android 应用中删除记录');return}
 try{const result=JSON.parse(task?native.deleteTask(id):native.mutateRecord?native.mutateRecord(JSON.stringify({action:'DELETE',id,expectedRevision:(draft?.id===id?draft:records.find(r=>r.id===id))?.wallet?.revision??0,submissionToken:submissionToken()})):native.delete(id));if(!result.ok){notify(result.error||'删除失败');return}closeSheet();closeSwipeRows();viewKey='';refresh();if(page==='stats')drawStats();if(recordPanelOpen&&(task?draft?.recognitionTaskId===id:draft?.id===id))cancelEditor(true);if(!task&&result.expiresAt)showUndo({id,expiresAt:result.expiresAt,expectedRevision:result.expectedRevision});else notify('已删除')}catch(_){notify('删除失败，请重试')}
}
$('delete-record').onclick=()=>requestDelete(draft?.recognitionTaskId||draft?.id,!!draft?.recognitionTaskId);
function showDataTools(){
 showSheet('账单与备份',`<h3>账单</h3><button class="sheet-row" id="import-bills"><span><b>导入账单</b><small>通用 CSV / 钱迹模板 · 预览后导入</small></span><span aria-hidden="true">›</span></button><button class="sheet-row" id="export-bills"><span><b>导出全部账单</b><small>CSV 表格 · 查看或迁移到其他记账应用</small></span><span aria-hidden="true">›</span></button><button class="sheet-row" id="bill-template">下载账单模板 CSV v2 <span>↓</span></button><h3>钱包账户</h3><button class="sheet-row" id="import-wallets"><span><b>导入钱包账户</b><small>余额快照 · 已有余额默认保留</small></span><span>›</span></button><button class="sheet-row" id="export-wallets"><span><b>导出钱包账户</b><small>资产/负债 · 在用/归档 · CSV v1</small></span><span>›</span></button><button class="sheet-row" id="wallet-template">下载钱包模板 CSV v1 <span>↓</span></button><h3>完整备份</h3><button class="sheet-row" id="export-backup"><span><b>完整备份</b><small>ZIP · 账单、截图、商户图标及账户</small></span><span aria-hidden="true">›</span></button><button class="sheet-row" id="restore-backup"><span><b>恢复备份</b><small>支持 ZIP 与旧版 JSON，保留已有编辑</small></span><span aria-hidden="true">›</span></button>`);
 $('import-bills').onclick=()=>{closeSheet();native?.importBills?.()};
 $('bill-template').onclick=()=>{closeSheet();native?.exportImportTemplate?.('bill')};$('wallet-template').onclick=()=>{closeSheet();native?.exportImportTemplate?.('wallet')};$('export-bills').onclick=showBillExport;$('import-wallets').onclick=()=>{closeSheet();native?.importWallets?.()};$('export-wallets').onclick=()=>{closeSheet();native?.exportWallets?.()};
 $('export-backup').onclick=()=>{closeSheet();native?.backup('export')};
 $('restore-backup').onclick=()=>{closeSheet();native?.backup('restore')};
}
function showBillExport(){
 const count=ledgerView?.summary?.postedCount??records.filter(r=>r.state==='POSTED').length;
 showSheet('导出全部账单',`<p class="sheet-copy export-scope">所有月份 · 全部已入账收支及转账，共 ${count} 笔</p><fieldset class="export-formats"><legend>选择格式</legend><label class="export-format"><input type="radio" name="export-format" value="general" checked/><span><b>通用账单 CSV v2</b><small>保留商户、订单号、备注及退款金额</small></span></label><label class="export-format"><input type="radio" name="export-format" value="qianji"/><span><b>钱迹导入模板</b><small>按钱迹官方模板整理，商户保留在备注</small></span></label></fieldset><p class="sheet-copy export-hint">可用 Excel / WPS 打开。金额为最终入账金额；截图请使用完整备份保存。</p><div class="sheet-actions"><button type="button" id="export-return">返回</button><button type="button" class="primary" id="export-start">选择保存位置</button></div>`);
 $('export-return').onclick=showDataTools;
 $('export-start').onclick=()=>{if(!native?.exportBills){notify('请在 Android 应用中导出');return}const format=document.querySelector('input[name="export-format"]:checked').value;closeSheet();native.exportBills(format)};
}
$('backup').onclick=showDataTools;
document.querySelectorAll('[data-payment-app]').forEach(button=>button.onclick=()=>{if(!native?.setPaymentApplication){notify('请在 Android 应用中设置支付自动识别');return}native.setPaymentApplication(button.dataset.paymentApp,button.getAttribute('aria-checked')!=='true');});
document.querySelectorAll('[data-channel-info]').forEach(b=>b.onclick=()=>{const key=b.dataset.channelInfo,state=channels[key]||{};if(key==='ocr'&&state.enabled&&captureHealth.enabled&&!captureHealth.ready&&native?.repairCapture){native.repairCapture();return}if(key==='ocr'&&state.enabled&&native?.shakeStatus){native.shakeStatus();return}if(!state.enabled&&state.permitted)revokeChannel(key);else setChannel(key,!state.enabled)});
document.querySelector('.scanner').onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();viewOrPickImage()}};
document.querySelectorAll('i').forEach(i=>i.setAttribute('aria-hidden','true'));

// The second reference adds dedicated manual entry, statistics and local asset accounts.
let manualTransfer={fromAccountUid:null,toAccountUid:null,fee:'0',feeMode:'EXTRA'},statsPeriod='MONTH',periodStats=null;
let manualType='expense',manualCategory='餐饮',manualCategoryId=null,statsType='expense',rankingType='expense',assetsHidden=false;
const expenseCategories=[['餐饮','utensils'],['交通','bus'],['购物','bag-shopping'],['娱乐','gamepad'],['居住','house'],['医疗','plus'],['教育','graduation-cap'],['服饰','shirt'],['日用','basket-shopping'],['通讯','phone'],['人情','gift'],['其他','ellipsis']];
const incomeCategories=[['工资','wallet'],['奖金','award'],['礼物','gift'],['退款','rotate-left'],['其他收入','ellipsis'],['其他','ellipsis']];
const chartColors=['#A588F8','#7FB5F0','#F3B0E1','#CDFDA5','#F7DE8A','#E2DEF0'];
function categoryOptions(type=manualType){return directoryItems(type)}
function beginManual(reset=true){
 if(manualOpen)return;if(manualClosing)closeManual(false);closeSheet(false);if(recordPanelOpen)closeRecordPanel(false);
 if(reset){manualType='expense';const first=findCategoryItem('EXPENSE',null,'餐饮');manualCategory=first.name;manualCategoryId=first.id;$('manual-form').reset();$('manual-note-count').textContent='0/500';manualWalletUid=walletSettings.defaultAccountUid;manualWalletSelected=false;manualSubmissionToken=submissionToken();manualTransfer={fromAccountUid:null,toAccountUid:null,fee:'0',feeMode:'EXTRA'};$('manual-occurred-at').value=localTime(Date.now())}drawCategories();drawManualWallet();
 manualReturnControl=lastControl;manualAnchor=clickOrigin()||capsuleBounds();manualHome=document.createComment('manual-home');$('manual').before(manualHome);$('manual-panel').querySelector('.manual-panel-content').append($('manual'));
 manualOpen=true;$('manual-panel').hidden=false;$('manual-shade').hidden=false;PanelFocus.activate($('manual-panel'),manualReturnControl);
 FluidMotion.run($('manual-panel'),manualAnchor,false,()=>{$('manual-panel').focus({preventScroll:true})},$('manual-shade'));remember();
}
function closeManual(animate=true){
 if(!manualOpen&&!manualClosing)return;manualOpen=false;manualClosing=true;
 const finish=()=>{FluidMotion.stop($('manual-panel'));$('manual-panel').hidden=true;$('manual-shade').hidden=true;if(manualHome){manualHome.replaceWith($('manual'));manualHome=null}manualClosing=false;PanelFocus.release($('manual-panel'));remember()};
 if(animate)FluidMotion.run($('manual-panel'),manualAnchor,true,finish,$('manual-shade'));else finish();
}
$('manual-shade').onclick=()=>closeManual();
$('manual').querySelector('.back').onclick=()=>manualOpen?closeManual():go('ledger');
function clearManualAmount(){$('manual-amount').value='';updateTransferPreview('manual');remember()}
function manualKey(key){const input=$('manual-amount');let value=input.value;if(key==='backspace')value=value.slice(0,-1);else if(key==='.') {if(value.includes('.'))return;value=(value||'0')+'.'}else{const next=value+key;if(next.length>14||next.includes('.')&&next.split('.')[1].length>2)return;value=next.replace(/^0+(?=\d)/,'')}input.value=value;updateTransferPreview('manual');remember()}

function drawCategories(){
 const transfer=manualType==='transfer';$('category-grid').hidden=transfer;$('manage-category').hidden=transfer;drawManualWallet();
 if(!transfer){
  const options=directoryItems(manualType);
  const chosen=(manualCategoryId&&options.find(c=>c.id===manualCategoryId))||options.find(c=>c.name===manualCategory)||options[0]||{id:'',name:manualType==='income'?'工资':'餐饮',icon:'tag',direction:manualType.toUpperCase()};
  manualCategory=chosen.name;manualCategoryId=chosen.id||null;
  $('category-grid').innerHTML=options.map(c=>{const active=c.id?c.id===manualCategoryId:c.name===manualCategory,icon=safeIconKey(c.icon)||iconForCategory(c.name,c.direction)||'tag';return `<button type="button" class="category-choice ${active?'active':''}" data-category="${escapeText(c.name)}" data-category-id="${escapeText(c.id)}" aria-pressed="${active}"><span><i class="fa-solid fa-${icon}" aria-hidden="true"></i></span>${escapeText(c.name)}</button>`}).join('');
  $('category-grid').querySelectorAll('button').forEach(b=>b.onclick=()=>{manualCategory=b.dataset.category;manualCategoryId=b.dataset.categoryId||null;drawCategories();remember()});
 }
 document.querySelectorAll('#manual-type button').forEach(b=>b.classList.toggle('active',b.dataset.type===manualType));
}
document.querySelectorAll('#manual-type button').forEach(b=>b.onclick=()=>{manualType=b.dataset.type;if(manualType==='transfer'){manualCategory='钱包转账';manualCategoryId=null}else{const first=findCategoryItem(manualType,null,manualType==='income'?'工资':'餐饮');manualCategory=first.name;manualCategoryId=first.id}drawCategories();remember()});
$('manual-form').oninput=()=>{$('manual-note-count').textContent=$('manual-note').value.length+'/500';remember()};
$('manual-form').onsubmit=e=>{e.preventDefault();if(manualType==='transfer'&&!validTransfer('manual'))return;const amount=$('manual-amount').value;if(!/^\d+(\.\d{1,2})?$/.test(amount)||Number(amount)<=0){notify('请输入大于零、最多两位小数的金额');$('manual-amount').focus();return}if(!native){notify('请在 Android 应用中保存账单');return}const direction=manualType==='transfer'?'TRANSFER':manualType==='income'?'INCOME':'EXPENSE',resolvedCat=manualType==='transfer'?null:findCategoryItem(direction,manualCategoryId,manualCategory),catName=manualType==='transfer'?'钱包转账':(resolvedCat?.name||manualCategory),catId=manualType==='transfer'?null:(resolvedCat?.id||manualCategoryId);const result=JSON.parse(native.save(JSON.stringify({id:0,amount,direction,merchant:catName,category:catName,...(catId?{categoryId:catId}:{}),occurredAt:new Date($('manual-occurred-at').value||localTime(Date.now())).getTime(),note:$('manual-note').value,confirmDuplicate:false,submissionToken:manualSubmissionToken||(manualSubmissionToken=submissionToken()),...(manualType==='transfer'?{transfer:readTransfer('manual')}:(manualWalletSelected?{walletAccountUid:manualWalletUid,manualWalletSelected:true}:{}))})));if(!result.ok){notify(result.error||'保存失败');return}month=localMonth(Date.now());filter=manualType;$('manual-form').reset();refresh();go('ledger');notify(result.state==='REVIEW'?'已保存，请选择钱包后入账':'保存成功')};
function openCategoryDeletion(targetItem,manageDir){
 const isFallback=!!targetItem.isFallback||String(targetItem.name||'').trim()==='其他';
 if(isFallback){
  notify('系统兜底分类不能删除');
  return;
 }
 if(!native?.previewCategoryDeletion){
  notify('请在 Android 应用中删除分类');
  return;
 }
 let previewRes;
 try{
  previewRes=JSON.parse(native.previewCategoryDeletion(targetItem.id));
 }catch(_){
  notify('无法获取分类删除影响，请重试');
  return;
 }
 if(!previewRes.ok){
  notify(previewRes.error||'无法获取分类删除影响');
  return;
 }
 let currentPreview=previewRes.deletionPreview;
 let selectedReplacementId='';
 let formError='';
 let isStale=false;

 const renderDeletionPreview=()=>{
  const reqReplacement=!!currentPreview.requiresReplacement;
  const isIncome=targetItem.direction==='INCOME';
  const dirLabel=isIncome?'收入':'支出';
  const replacements=Array.isArray(currentPreview.replacements)?currentPreview.replacements:[];

  if(selectedReplacementId&&!replacements.some(r=>r.id===selectedReplacementId)){
   selectedReplacementId='';
  }

  const impactHtml=`
   <div class="category-impact-summary" id="category-impact-summary">
    <div class="category-impact-stat" data-impact="bills">
     <strong class="impact-stat-number" id="preview-bill-count">${currentPreview.billCount}</strong>
     <span class="impact-stat-label">笔关联账单</span>
    </div>
    <div class="category-impact-stat" data-impact="rules">
     <strong class="impact-stat-number" id="preview-rule-count">${currentPreview.ruleCount}</strong>
     <span class="impact-stat-label">条规则目标</span>
    </div>
    <div class="category-impact-stat" data-impact="tasks">
     <strong class="impact-stat-number" id="preview-task-count">${currentPreview.queuedConfigurationCount}</strong>
     <span class="impact-stat-label">个排队任务</span>
    </div>
    ${(currentPreview.replacementCount>0)?`
    <div class="category-impact-stat" data-impact="replacements">
     <strong class="impact-stat-number" id="preview-replacement-count">${currentPreview.replacementCount}</strong>
     <span class="impact-stat-label">个既有替代</span>
    </div>`:''}
   </div>
  `;

  const copyHtml=`
   <p class="sheet-copy category-delete-notice" id="category-delete-notice">
    删除此分类将一次性完成分类移除与关联归属迁移。金额、时间、方向、钱包与账户余额均保持不变；排队中的任务保留原有匹配条件和顺序，改用替代分类入账。操作不可单独撤销。
   </p>
  `;

  const replacementHtml=reqReplacement?`
   <div class="category-replacement-card" id="category-replacement-card">
    <label class="sheet-copy" for="category-replacement-select">
     <b>选择同方向替代分类（必填）</b>
    </label>
    <select class="month-input" id="category-replacement-select" aria-label="选择同方向替代分类" aria-required="true">
     <option value="">请选择同方向${dirLabel}替代分类…</option>
     ${replacements.map(r=>`
      <option value="${escapeText(r.id)}" ${r.id===selectedReplacementId?'selected':''}>
       ${escapeText(r.name)}${r.isFallback?'（兜底）':''}
      </option>
     `).join('')}
    </select>
    <p class="sheet-copy replacement-field-hint" id="replacement-field-hint">
     只能选择存在且同为${dirLabel}的其他分类；已排除待删除分类本身。
    </p>
   </div>
  `:`
   <div class="category-replacement-card category-zero-ref-card" id="category-zero-ref-card">
    <p class="sheet-copy" id="zero-reference-hint">当前分类暂无关联账单、规则或排队任务，无需选择替代分类，可直接确认删除。</p>
   </div>
  `;

  const staleHtml=isStale?`
   <div class="category-stale-alert" id="category-stale-alert" role="alert">
    <b>分类引用已变化</b>
    <span>引用数量与目标列表已更新，请重新核对后再确认删除。</span>
   </div>
  `:'';

  const contentHtml=`
   <div class="category-delete-preview-view" id="category-delete-preview-view">
    <div class="category-delete-target-row">
     ${categoryBadge(targetItem.name,targetItem.direction,targetItem.icon)}
     <div class="category-delete-target-info">
      <b id="delete-target-name">${escapeText(targetItem.name)}</b>
      <span class="category-manage-meta">${dirLabel}分类 · ${reqReplacement?'有引用待迁移':'无关联引用'}</span>
     </div>
    </div>
    ${staleHtml}
    ${impactHtml}
    ${copyHtml}
    ${replacementHtml}
    <p class="category-field-error" id="category-delete-error" role="alert" ${formError?'':'hidden'}>${escapeText(formError)}</p>
    <div class="sheet-actions">
     <button type="button" id="cancel-category-delete">取消</button>
     <button type="button" class="danger" id="confirm-category-delete">确认删除</button>
    </div>
   </div>
  `;

  showSheet('删除分类 · '+targetItem.name,contentHtml);

  const repSelect=$('category-replacement-select');
  if(repSelect){
   repSelect.onchange=()=>{
    selectedReplacementId=repSelect.value;
    formError='';
    const errEl=$('category-delete-error');
    if(errEl)errEl.hidden=true;
    repSelect.removeAttribute('aria-invalid');
   };
  }

  $('cancel-category-delete').onclick=()=>{
   openCategoryManager(manageDir);
   setTimeout(()=>{
    document.querySelector(`[data-delete-category="${targetItem.id}"]`)?.focus();
   },50);
  };

  $('confirm-category-delete').onclick=()=>{
   if(currentPreview.requiresReplacement&&!selectedReplacementId){
    formError='该分类仍有引用，请选择同方向的替代分类';
    const errEl=$('category-delete-error');
    if(errEl){
     errEl.textContent=formError;
     errEl.hidden=false;
    }
    const selectEl=$('category-replacement-select');
    if(selectEl){
     selectEl.setAttribute('aria-invalid','true');
     selectEl.focus();
    }
    notify(formError);
    return;
   }

   const payload={
    id:targetItem.id,
    replacementId:currentPreview.requiresReplacement?selectedReplacementId:null,
    previewToken:currentPreview.previewToken
   };

   let res;
   try{
    res=JSON.parse(native.confirmCategoryDeletion(JSON.stringify(payload)));
   }catch(_){
    formError='分类删除失败，请重试';
    const errEl=$('category-delete-error');
    if(errEl){errEl.textContent=formError;errEl.hidden=false;}
    notify(formError);
    return;
   }

   if(!res.ok){
    if(res.code==='CATEGORY_PREVIEW_STALE'){
     isStale=true;
     formError=res.error||'分类引用已变化，请核对最新影响后再确认删除';
     if(res.deletionPreview){
      currentPreview=res.deletionPreview;
      if(!currentPreview.replacements?.some(r=>r.id===selectedReplacementId)){
       selectedReplacementId='';
      }
     }
     renderDeletionPreview();
     notify(formError);
     return;
    }
    formError=res.error||'删除失败';
    const errEl=$('category-delete-error');
    if(errEl){
     errEl.textContent=formError;
     errEl.hidden=false;
    }
    notify(formError);
    return;
   }

   if(res.categoryDirectory)categoryDirectory=res.categoryDirectory;
   if(res.categoryIconOptions)categoryIconOptions=res.categoryIconOptions;
   if(res.classificationRules&&typeof applyClassificationRules==='function'){
    applyClassificationRules(res.classificationRules);
   }
   viewKey='';dataRevision='';loadedScope='';
   if(periodStats&&native?.statistics){
    try{
     const latest=JSON.parse(native.statistics(statsPeriod));
     if(latest.ok)periodStats=latest;
    }catch(_){}
   }
   refresh();
   drawCategories();
   if(page==='stats')drawStats();
   if(draft)updateEditor();
   if(manualCategoryId===targetItem.id){
    manualCategoryId=selectedReplacementId||null;
    const rep=findCategoryItem(manualType,selectedReplacementId,null);
    if(rep)manualCategory=rep.name;
   }
   if(draft&&draft.categoryId===targetItem.id){
    draft.categoryId=selectedReplacementId||null;
    const rep=findCategoryItem(draft.direction||'EXPENSE',selectedReplacementId,null);
    if(rep){draft.category=rep.name;draft.categoryIcon=rep.icon;}
   }
   notify('分类已删除');
   openCategoryManager(manageDir);
  };
 };

 renderDeletionPreview();
}
function openCategoryManager(initialDirection){
 let manageDir=String(initialDirection||manualType||'expense').toLowerCase()==='income'?'income':'expense',editingItem=null,selectedIcon='tag';
 const syncFromNative=()=>{if(!native?.getCategoryDirectory)return;try{const res=JSON.parse(native.getCategoryDirectory());if(res.ok){if(res.categoryDirectory)categoryDirectory=res.categoryDirectory;if(res.categoryIconOptions)categoryIconOptions=res.categoryIconOptions}}catch(_){}};
 const applyDirectoryResult=res=>{if(res?.categoryDirectory)categoryDirectory=res.categoryDirectory;if(res?.categoryIconOptions)categoryIconOptions=res.categoryIconOptions;viewKey='';dataRevision='';loadedScope='';if(periodStats&&native?.statistics){try{const latest=JSON.parse(native.statistics(statsPeriod));if(latest.ok)periodStats=latest}catch(_){}}refresh();drawCategories();if(page==='stats')drawStats();if(draft)updateEditor();remember()};
 syncFromNative();
 const defaultIcon=dir=>safeIconKey(activeIconOptions()[0]?.key)||(dir==='income'?'wallet':'tag');
 selectedIcon=defaultIcon(manageDir);
 const renderManager=()=>{
  const items=directoryItems(manageDir),expCount=directoryItems('expense').length,incCount=directoryItems('income').length,dirLabel=manageDir==='income'?'收入':'支出',icons=activeIconOptions();
  const hasPreview=!!native?.previewCategoryDeletion;
  if(!icons.some(o=>o.key===selectedIcon))selectedIcon=safeIconKey(editingItem?.icon)||defaultIcon(manageDir);
  showSheet('分类管理',`<div class="category-manager-header"><div class="segmented" id="category-manage-tabs" role="tablist" aria-label="收支分类方向"><button type="button" data-manage-dir="expense" class="${manageDir==='expense'?'active':''}" aria-pressed="${manageDir==='expense'}">支出分类 (${expCount})</button><button type="button" data-manage-dir="income" class="${manageDir==='income'?'active':''}" aria-pressed="${manageDir==='income'}">收入分类 (${incCount})</button></div>${editingItem?'<button type="button" id="reset-category-form" class="category-add-btn">+ 新增分类</button>':''}</div><div class="category-editor-card" id="category-editor-card"><h4 id="category-editor-title">${editingItem?(editingItem.isFallback?`修改「${escapeText(editingItem.name)}」图标`:`编辑${dirLabel}分类 · ${escapeText(editingItem.name)}`):`新增${dirLabel}分类`}</h4><label class="sheet-copy" for="new-category-name">${editingItem?.isFallback?'系统兜底分类「其他」不可改名或删除，可调整下方图标与排序':`新增或修改${dirLabel}分类名称（最多 40 字）`}</label><input class="month-input" id="new-category-name" maxlength="40" value="${escapeText(editingItem?editingItem.name:'')}" placeholder="分类名称，例如：咖啡、数码、兼职" ${editingItem?.isFallback?'disabled readonly aria-disabled="true"':''}/><div class="category-icon-picker" id="category-icon-picker" role="radiogroup" aria-label="选择分类图标">${icons.map(opt=>`<button type="button" class="category-icon-option" data-icon-key="${escapeText(opt.key)}" aria-pressed="${opt.key===selectedIcon}" title="${escapeText(opt.label)}"><i class="fa-solid fa-${escapeText(opt.key)}" aria-hidden="true"></i><span>${escapeText(opt.label)}</span></button>`).join('')}</div><p class="category-field-error" id="category-form-error" role="alert" hidden></p><div class="sheet-actions">${editingItem?'<button type="button" id="cancel-category-edit">取消编辑</button>':'<button type="button" onclick="closeSheet()">关闭</button>'}<button type="button" class="primary" id="save-category">${editingItem?'保存修改':'添加分类'}</button></div></div>${hasPreview?'<p class="sheet-copy">上下移动可调整手动记账、账单编辑与筛选统计的顺序；删除非兜底分类时可预览影响并指定替代分类，一次完成迁移。</p>':'<p class="sheet-copy">上下移动可调整手动记账、账单编辑与筛选统计的顺序；已关联账单的分类受保护，需先处理关联账单后才能删除。</p>'}<div class="category-manage-list" id="category-manage-list">${items.map((item,idx)=>{const meta=item.isFallback?`系统兜底分类 · 不可删除或改名${item.referenceCount>0?` · 已关联 ${item.referenceCount} 笔账单`:''}`:hasPreview?(item.referenceCount>0?`已关联 ${item.referenceCount} 笔账单 · 删除时将迁移引用`:'未关联账单 · 可改名、排序或删除'):(!item.canDelete||item.referenceCount>0?`已关联 ${item.referenceCount} 笔账单 · 暂不可删除（请先处理关联账单）`:'未关联账单 · 可改名、排序或删除');const canShowDelete=hasPreview?!item.isFallback:(item.canDelete&&!item.isFallback);return `<div class="category-manage-row" data-category-row="${escapeText(item.id)}" data-category-name="${escapeText(item.name)}">${categoryBadge(item.name,item.direction,item.icon)}<div class="category-manage-info"><b>${escapeText(item.name)}${item.isFallback?' <small class="tag">兜底</small>':''}</b><span class="category-manage-meta">${escapeText(meta)}</span></div><div class="category-manage-actions"><button type="button" class="category-action-btn" data-move-up="${escapeText(item.id)}" aria-label="上移${escapeText(item.name)}" ${idx===0?'disabled':''}>↑</button><button type="button" class="category-action-btn" data-move-down="${escapeText(item.id)}" aria-label="下移${escapeText(item.name)}" ${idx===items.length-1?'disabled':''}>↓</button><button type="button" class="category-action-btn" data-edit-category="${escapeText(item.id)}" aria-label="${item.isFallback?'修改'+escapeText(item.name)+'图标':'编辑'+escapeText(item.name)}">${item.isFallback?'图标':'编辑'}</button>${canShowDelete?`<button type="button" class="category-action-btn danger-btn" data-delete-category="${escapeText(item.id)}" data-remove-category="${escapeText(item.name)}" aria-label="删除${escapeText(item.name)}">删除</button>`:''}</div></div>`}).join('')}</div>`);
  const showError=msg=>{const el=$('category-form-error');if(el){el.textContent=msg||'';el.hidden=!msg}};
  document.querySelectorAll('[data-manage-dir]').forEach(b=>b.onclick=()=>{manageDir=b.dataset.manageDir;editingItem=null;selectedIcon=defaultIcon(manageDir);syncFromNative();renderManager()});
  if($('reset-category-form'))$('reset-category-form').onclick=()=>{editingItem=null;selectedIcon=defaultIcon(manageDir);renderManager()};
  if($('cancel-category-edit'))$('cancel-category-edit').onclick=()=>{editingItem=null;selectedIcon=defaultIcon(manageDir);renderManager()};
  document.querySelectorAll('[data-icon-key]').forEach(b=>b.onclick=()=>{selectedIcon=b.dataset.iconKey;document.querySelectorAll('[data-icon-key]').forEach(e=>e.setAttribute('aria-pressed',String(e===b)))});
  $('save-category').onclick=()=>{
   showError('');
   const rawName=editingItem?.isFallback?editingItem.name:$('new-category-name').value.trim();
   if(!editingItem?.isFallback&&!rawName){showError('请输入分类名称');notify('请输入分类名称');$('new-category-name').focus();return}
   if(!native){showError('请在 Android 应用中管理分类');notify('请在 Android 应用中管理分类');return}
   try{
    const wasEditing=!!editingItem;
    const result=JSON.parse(native.saveCategory?native.saveCategory(JSON.stringify({...(editingItem?.id?{id:editingItem.id}:{}),name:rawName,direction:manageDir.toUpperCase(),icon:selectedIcon})):native.addCategory(rawName,manageDir));
    if(!result.ok){showError(result.error||'分类保存失败');notify(result.error||'分类保存失败');return}
    const savedName=result.category?.name||rawName,savedId=result.category?.id||editingItem?.id||null;
    if(manualType===manageDir){manualCategory=savedName;if(savedId)manualCategoryId=savedId}
    if(draft&&String(draft.direction||'').toLowerCase()===manageDir&&editingItem?.id&&draft.categoryId===editingItem.id){draft.category=savedName;draft.categoryId=savedId||draft.categoryId}
    editingItem=null;selectedIcon=defaultIcon(manageDir);
    applyDirectoryResult(result);renderManager();notify(wasEditing?'分类已更新':'分类已添加');
   }catch(_){showError('分类保存失败，请重试');notify('分类保存失败，请重试')}
  };
  document.querySelectorAll('[data-edit-category]').forEach(b=>b.onclick=()=>{
   const found=items.find(c=>c.id===b.dataset.editCategory);if(!found)return;
   editingItem={...found};selectedIcon=safeIconKey(found.icon)||defaultIcon(manageDir);renderManager();
   if(!found.isFallback)$('new-category-name')?.focus();
  });
  const moveItem=(id,delta)=>{
   const idx=items.findIndex(c=>c.id===id),targetIdx=idx+delta;if(idx<0||targetIdx<0||targetIdx>=items.length)return;
   if(!native?.reorderCategories){notify('请在 Android 应用中调整分类顺序');return}
   const ids=items.map(c=>c.id);[ids[idx],ids[targetIdx]]=[ids[targetIdx],ids[idx]];
   try{
    const result=JSON.parse(native.reorderCategories(manageDir.toUpperCase(),JSON.stringify(ids)));
    if(!result.ok){showError(result.error||'分类排序失败');notify(result.error||'分类排序失败');return}
    applyDirectoryResult(result);renderManager();
   }catch(_){notify('分类排序失败，请重试')}
  };
  document.querySelectorAll('[data-move-up]').forEach(b=>b.onclick=()=>moveItem(b.dataset.moveUp,-1));
  document.querySelectorAll('[data-move-down]').forEach(b=>b.onclick=()=>moveItem(b.dataset.moveDown,1));
  document.querySelectorAll('[data-delete-category]').forEach(b=>b.onclick=()=>{
   const found=items.find(c=>c.id===b.dataset.deleteCategory);if(!found)return;
   if(!native){notify('请在 Android 应用中删除分类');return}
   if(native.previewCategoryDeletion){
    if(editingItem?.id===found.id)editingItem=null;
    openCategoryDeletion(found,manageDir);
    return;
   }
   try{
    const result=JSON.parse(native.removeCategory?native.removeCategory(found.id):native.deleteCategory(found.name,manageDir));
    if(!result.ok){if(result.code==='CATEGORY_REFERENCED'){syncFromNative();renderManager()}showError(result.error||'删除失败');notify(result.error||'删除失败');return}
    if(editingItem?.id===found.id)editingItem=null;
    applyDirectoryResult(result);renderManager();notify('分类已删除');
   }catch(_){notify('删除失败，请重试')}
  });
 };
 renderManager();
}
$('manage-category').onclick=()=>openCategoryManager(manualType);
function openClassificationRules(initialDirection){
 let manageDir=String(initialDirection||'EXPENSE').toUpperCase()==='INCOME'?'INCOME':'EXPENSE',editingRule=null,formDir=manageDir,formName='',formEnabled=true,formCategoryId='',formMatchMode='ALL',formSource='',formConditions=[{field:'MERCHANT',operator:'CONTAINS',value:''}],internalMutation=false;
 const syncFromNative=()=>{
  if(native?.getCategoryDirectory){try{const res=JSON.parse(native.getCategoryDirectory());if(res.ok){if(res.categoryDirectory)categoryDirectory=res.categoryDirectory;if(res.categoryIconOptions)categoryIconOptions=res.categoryIconOptions}}catch(_){}}
  if(native?.classificationRules){try{const res=JSON.parse(native.classificationRules());if(res.ok&&res.classificationRules)applyClassificationRules(res.classificationRules)}catch(_){}}
 };
 const ensureValidCategoryId=(dir,preferredId)=>{
  const list=directoryItems(dir),d=String(dir||'EXPENSE').toUpperCase()==='INCOME'?'INCOME':'EXPENSE';
  const matched=preferredId&&list.find(c=>c.id===preferredId&&c.direction===d);
  return matched?matched.id:(list[0]?.id||'');
 };
 const captureDraftInputs=()=>{
  if($('rule-name'))formName=$('rule-name').value;
  if($('rule-enabled'))formEnabled=!!$('rule-enabled').checked;
  if($('rule-direction'))formDir=$('rule-direction').value==='INCOME'?'INCOME':'EXPENSE';
  if($('rule-category'))formCategoryId=$('rule-category').value;
  if($('rule-match-mode'))formMatchMode=$('rule-match-mode').value==='ANY'?'ANY':'ALL';
  if($('rule-source'))formSource=$('rule-source').value||'';
  const condRows=document.querySelectorAll('#rule-conditions-list [data-condition-row]');
  if(condRows.length){
   formConditions=Array.from(condRows).map(row=>{
    const f=row.querySelector('[data-condition-field]')?.value==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT';
    const op=row.querySelector('[data-condition-operator]')?.value;
    const operator=op==='EQUALS'?'EQUALS':op==='NOT_CONTAINS'?'NOT_CONTAINS':'CONTAINS';
    const val=row.querySelector('[data-condition-keyword]')?.value||'';
    return {field:f,operator,value:val};
   });
  }else if($('rule-keyword')){
   const f=$('rule-field')?.value==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT';
   const op=$('rule-operator')?.value;
   const operator=op==='EQUALS'?'EQUALS':op==='NOT_CONTAINS'?'NOT_CONTAINS':'CONTAINS';
   const val=$('rule-keyword')?.value||'';
   formConditions=[{field:f,operator,value:val}];
  }
 };
 const applyRulesResult=res=>{
  if(res?.classificationRules)applyClassificationRules(res.classificationRules);
  internalMutation=true;
  try{viewKey='';dataRevision='';loadedScope='';refresh();drawSettings();remember()}finally{internalMutation=false}
 };
 syncFromNative();
 formCategoryId=ensureValidCategoryId(formDir,'');
 const renderRulesSheet=(focusSelector=null)=>{
  const expRules=customRulesByDirection('EXPENSE'),incRules=customRulesByDirection('INCOME'),rules=manageDir==='INCOME'?incRules:expRules;
  const builtinExpRules=builtinRulesByDirection('EXPENSE'),builtinIncRules=builtinRulesByDirection('INCOME'),builtinRules=manageDir==='INCOME'?builtinIncRules:builtinExpRules;
  const dirLabel=manageDir==='INCOME'?'收入':'支出',formDirLabel=formDir==='INCOME'?'收入':'支出';
  const dirCategories=directoryItems(formDir);
  formCategoryId=ensureValidCategoryId(formDir,formCategoryId);
  const firstEnabledId=rules.find(r=>r.enabled!==false)?.id||null;
  const editorTitle=editingRule?(editingRule.id?`编辑规则 · ${escapeText(editingRule.name)}`:`复制规则预填 · ${escapeText(formName)}`):`新增${formDirLabel}规则`;
  const knownSources=['LIVE','IMAGE','LIVE:com.tencent.mm','LIVE:com.eg.android.AlipayGphone','IMAGE:wechat','IMAGE:alipay'];
  const customSourceOption=formSource&&!knownSources.includes(formSource)?`<option value="${escapeText(formSource)}" selected>${escapeText(formatRuleSource(formSource))}</option>`:'';
  showSheet('自动分类规则',`<div class="category-manager-header"><div class="segmented" id="rule-manage-tabs" role="tablist" aria-label="自动分类规则方向"><button type="button" data-rule-dir="EXPENSE" class="${manageDir==='EXPENSE'?'active':''}" aria-pressed="${manageDir==='EXPENSE'}">支出规则 (${expRules.length})</button><button type="button" data-rule-dir="INCOME" class="${manageDir==='INCOME'?'active':''}" aria-pressed="${manageDir==='INCOME'}">收入规则 (${incRules.length})</button></div>${editingRule?'<button type="button" id="reset-rule-form" class="category-add-btn">+ 新增规则</button>':''}</div><p class="sheet-copy rule-scope-note" id="classification-rules-note">按列表从上到下匹配，<b>首条命中的启用规则生效</b>（自定义规则优先于内置规则）。修改、停用或删除规则仅影响后续新识别账单，不会修改已有账单。</p><div class="category-editor-card" id="rule-editor-card"><div class="rule-editor-top"><h4 id="rule-editor-title">${editorTitle}</h4><label class="rule-enable-control" for="rule-enabled"><input type="checkbox" id="rule-enabled" ${formEnabled?'checked':''}/><span>启用规则</span></label></div><label class="sheet-copy" for="rule-name">规则名称（必填，最多 80 字）</label><input class="month-input" id="rule-name" maxlength="80" value="${escapeText(formName)}" placeholder="例如：星巴克咖啡、平台外卖"/><div class="rule-form-grid"><div><label class="sheet-copy" for="rule-direction">适用方向</label><select class="month-input" id="rule-direction"><option value="EXPENSE" ${formDir==='EXPENSE'?'selected':''}>支出</option><option value="INCOME" ${formDir==='INCOME'?'selected':''}>收入</option></select></div><div><label class="sheet-copy" for="rule-category">目标分类</label><select class="month-input" id="rule-category">${dirCategories.map(c=>`<option value="${escapeText(c.id)}" ${c.id===formCategoryId?'selected':''}>${escapeText(c.name)}</option>`).join('')}</select></div></div><div class="rule-form-grid"><div><label class="sheet-copy" for="rule-match-mode">条件关系</label><select class="month-input" id="rule-match-mode"><option value="ALL" ${formMatchMode!=='ANY'?'selected':''}>全部满足</option><option value="ANY" ${formMatchMode==='ANY'?'selected':''}>任一满足</option></select></div><div><label class="sheet-copy" for="rule-source">来源限制（可选）</label><select class="month-input" id="rule-source"><option value="" ${!formSource?'selected':''}>不限来源</option><option value="LIVE" ${formSource==='LIVE'?'selected':''}>现场识别</option><option value="IMAGE" ${formSource==='IMAGE'?'selected':''}>图片导入</option><option value="LIVE:com.tencent.mm" ${formSource==='LIVE:com.tencent.mm'?'selected':''}>微信现场</option><option value="LIVE:com.eg.android.AlipayGphone" ${formSource==='LIVE:com.eg.android.AlipayGphone'?'selected':''}>支付宝现场</option><option value="IMAGE:wechat" ${formSource==='IMAGE:wechat'?'selected':''}>微信凭证图片</option><option value="IMAGE:alipay" ${formSource==='IMAGE:alipay'?'selected':''}>支付宝凭证图片</option>${customSourceOption}</select></div></div><div class="rule-conditions-header"><label class="sheet-copy"><b>匹配条件</b>（1～20 个）</label><button type="button" class="category-add-btn" id="add-rule-condition" ${formConditions.length>=20?'disabled':''}>+ 添加条件</button></div><p class="sheet-copy rule-hint-note">说明：订单内容包括商品说明、交易说明与订单备注；缺少某字段时不命中该字段的所有条件（包括“不包含”）。</p><div class="rule-conditions-list" id="rule-conditions-list">${formConditions.map((cond,idx)=>`<div class="rule-condition-row" data-condition-row="${idx}"><div class="rule-condition-controls"><div><label class="sheet-copy" for="${idx===0?'rule-field':`rule-field-${idx}`}">匹配字段</label><select class="month-input" data-condition-field="${idx}" id="${idx===0?'rule-field':`rule-field-${idx}`}"><option value="MERCHANT" ${cond.field!=='ORDER_CONTENT'?'selected':''}>商家</option><option value="ORDER_CONTENT" ${cond.field==='ORDER_CONTENT'?'selected':''}>订单内容</option></select></div><div><label class="sheet-copy" for="${idx===0?'rule-operator':`rule-operator-${idx}`}">匹配方式</label><select class="month-input" data-condition-operator="${idx}" id="${idx===0?'rule-operator':`rule-operator-${idx}`}"><option value="CONTAINS" ${cond.operator==='CONTAINS'?'selected':''}>包含</option><option value="EQUALS" ${cond.operator==='EQUALS'?'selected':''}>等于</option><option value="NOT_CONTAINS" ${cond.operator==='NOT_CONTAINS'?'selected':''}>不包含</option></select></div></div><div class="rule-condition-keyword-row"><div style="flex:1;min-width:0"><label class="sheet-copy" for="${idx===0?'rule-keyword':`rule-keyword-${idx}`}">关键词（必填，最多 200 字）</label><input class="month-input" data-condition-keyword="${idx}" id="${idx===0?'rule-keyword':`rule-keyword-${idx}`}" maxlength="200" value="${escapeText(cond.value)}" placeholder="${cond.field==='ORDER_CONTENT'?'例如：外卖、咖啡、打车':'例如：星巴克、瑞幸'}"/></div>${formConditions.length>1?`<button type="button" class="category-action-btn danger-btn rule-remove-condition-btn" data-remove-condition="${idx}" aria-label="删除第 ${idx+1} 个条件">删除</button>`:''}</div></div>`).join('')}</div><p class="category-field-error" id="rule-form-error" role="alert" hidden></p><div class="sheet-actions">${editingRule?'<button type="button" id="cancel-rule-edit">取消'+(editingRule.id?'编辑':'预填')+'</button>':'<button type="button" onclick="closeSheet()">关闭</button>'}<button type="button" class="primary" id="save-rule">${editingRule?.id?'保存修改':'保存规则'}</button></div></div><div class="category-manage-list" id="classification-rules-list">${rules.length?rules.map((rule,idx)=>{const targetCat=directoryItems(rule.direction).find(c=>c.id===rule.categoryId),targetName=targetCat?targetCat.name:'失效分类（请重新选择）',targetIcon=targetCat?targetCat.icon:'tag',condSummary=formatRuleConditionSummary(rule),isFirst=rule.enabled!==false&&rule.id===firstEnabledId;return `<div class="category-manage-row rule-manage-row${rule.enabled===false?' is-disabled':''}" data-rule-row="${escapeText(rule.id)}" data-rule-name="${escapeText(rule.name)}" data-rule-enabled="${rule.enabled!==false}"><div class="rule-manage-main">${categoryBadge(targetName,rule.direction,targetIcon)}<div class="category-manage-info"><b><span class="rule-row-name">${escapeText(rule.name)}</span><span class="rule-target-tag" data-rule-target-name="${escapeText(rule.id)}">→ ${escapeText(targetName)}</span>${isFirst?`<span class="rule-first-tag" data-rule-first-priority="${escapeText(rule.id)}">首条优先</span>`:''}${rule.enabled===false?'<span class="rule-disabled-tag">已停用</span>':''}</b><span class="category-manage-meta" data-rule-summary="${escapeText(rule.id)}">${escapeText(condSummary)}</span><span class="rule-priority-meta">优先级 #${idx+1}${isFirst?' · 首条命中优先':rule.enabled===false?' · 已停用不参与匹配':' · 按顺序依次匹配'}${rule.conflictReason?' · '+escapeText(rule.conflictReason):''}</span></div><button type="button" class="switch rule-row-switch" role="switch" data-toggle-rule="${escapeText(rule.id)}" aria-checked="${rule.enabled!==false}" aria-label="${rule.enabled!==false?'停用':'启用'}规则 ${escapeText(rule.name)}"></button></div><div class="rule-manage-bar"><span class="rule-priority-meta">目标分类：<b data-rule-category-label="${escapeText(rule.id)}">${escapeText(targetName)}</b></span><div class="category-manage-actions"><button type="button" class="category-action-btn" data-rule-up="${escapeText(rule.id)}" aria-label="上移规则 ${escapeText(rule.name)}" ${idx===0?'disabled':''}>↑</button><button type="button" class="category-action-btn" data-rule-down="${escapeText(rule.id)}" aria-label="下移规则 ${escapeText(rule.name)}" ${idx===rules.length-1?'disabled':''}>↓</button><button type="button" class="category-action-btn" data-edit-rule="${escapeText(rule.id)}" aria-label="编辑规则 ${escapeText(rule.name)}">编辑</button><button type="button" class="category-action-btn danger-btn" data-delete-rule="${escapeText(rule.id)}" aria-label="删除规则 ${escapeText(rule.name)}">删除</button></div></div></div>`}).join(''):`<div class="empty-state" id="classification-rules-empty">暂无${dirLabel}自动分类规则<br>在上方填写关键词与目标分类即可添加</div>`}</div><div class="builtin-rules-section" id="builtin-rules-section"><div class="builtin-rules-header"><h4 id="builtin-rules-title">内置${dirLabel}规则 (${builtinRules.length})</h4><span class="sheet-copy rule-priority-meta">自定义规则优先匹配；可单独启停或复制为自定义规则</span></div><div class="category-manage-list" id="builtin-rules-list">${builtinRules.length?builtinRules.map(rule=>{const targetCat=directoryItems(rule.direction).find(c=>c.id===rule.categoryId),targetName=targetCat?targetCat.name:'未知分类',targetIcon=targetCat?targetCat.icon:'tag',condSummary=formatRuleConditionSummary(rule);return `<div class="category-manage-row rule-manage-row builtin-rule-row${rule.enabled===false?' is-disabled':''}" data-builtin-rule-row="${escapeText(rule.id)}" data-builtin-rule-name="${escapeText(rule.name)}" data-builtin-rule-enabled="${rule.enabled!==false}"><div class="rule-manage-main">${categoryBadge(targetName,rule.direction,targetIcon)}<div class="category-manage-info"><b><span class="rule-row-name">${escapeText(rule.name)}</span><span class="rule-target-tag" data-builtin-target-name="${escapeText(rule.id)}">→ ${escapeText(targetName)}</span><span class="rule-builtin-tag">内置</span>${rule.enabled===false?'<span class="rule-disabled-tag">已停用</span>':''}</b><span class="category-manage-meta" data-builtin-summary="${escapeText(rule.id)}">${escapeText(condSummary)}</span><span class="rule-priority-meta">${rule.enabled===false?'已停用 · 不参与匹配':'启用中 · 自定义规则未命中时匹配'}</span></div><button type="button" class="switch rule-row-switch" role="switch" data-toggle-builtin-rule="${escapeText(rule.id)}" aria-checked="${rule.enabled!==false}" aria-label="${rule.enabled!==false?'停用':'启用'}内置规则 ${escapeText(rule.name)}"></button></div><div class="rule-manage-bar"><span class="rule-priority-meta">目标分类：<b data-builtin-category-label="${escapeText(rule.id)}">${escapeText(targetName)}</b></span><div class="category-manage-actions"><button type="button" class="category-action-btn" data-copy-builtin-rule="${escapeText(rule.id)}" aria-label="复制内置规则 ${escapeText(rule.name)}">复制为自定义</button></div></div></div>`}).join(''):`<div class="empty-state" id="builtin-rules-empty">暂无内置${dirLabel}规则</div>`}</div></div>`);
  const clearInvalid=()=>{
   ['rule-name','rule-category','rule-direction','rule-match-mode','rule-source'].forEach(id=>$(id)?.removeAttribute('aria-invalid'));
   document.querySelectorAll('#rule-conditions-list input, #rule-conditions-list select').forEach(el=>el.removeAttribute('aria-invalid'));
  };
  const showError=(msg,field=null)=>{
   clearInvalid();
   const el=$('rule-form-error');
   if(el){el.textContent=msg||'';el.hidden=!msg}
   if(field){field.setAttribute('aria-invalid','true');field.focus({preventScroll:true})}
   if(msg)notify(msg);
  };
  ['rule-name','rule-category','rule-direction','rule-match-mode','rule-source'].forEach(id=>{
   const el=$(id);if(el)el.oninput=el.onchange=()=>{if(el.getAttribute('aria-invalid')==='true')showError('')};
  });
  document.querySelectorAll('#rule-conditions-list input, #rule-conditions-list select').forEach(el=>{
   el.oninput=el.onchange=()=>{if(el.getAttribute('aria-invalid')==='true')showError('')};
  });
  document.querySelectorAll('[data-rule-dir]').forEach(b=>b.onclick=()=>{
   const nextDir=b.dataset.ruleDir==='INCOME'?'INCOME':'EXPENSE';
   manageDir=nextDir;editingRule=null;formDir=nextDir;formName='';formConditions=[{field:'MERCHANT',operator:'CONTAINS',value:''}];formMatchMode='ALL';formSource='';formEnabled=true;formCategoryId=ensureValidCategoryId(nextDir,'');
   syncFromNative();renderRulesSheet(`[data-rule-dir="${nextDir}"]`);
  });
  $('rule-direction').onchange=()=>{
   captureDraftInputs();
   formDir=$('rule-direction').value==='INCOME'?'INCOME':'EXPENSE';
   manageDir=formDir;
   formCategoryId=ensureValidCategoryId(formDir,formCategoryId);
   renderRulesSheet('#rule-direction');
  };
  const resetForm=()=>{editingRule=null;formDir=manageDir;formName='';formConditions=[{field:'MERCHANT',operator:'CONTAINS',value:''}];formMatchMode='ALL';formSource='';formEnabled=true;formCategoryId=ensureValidCategoryId(manageDir,'');renderRulesSheet('#rule-name')};
  if($('reset-rule-form'))$('reset-rule-form').onclick=resetForm;
  if($('cancel-rule-edit'))$('cancel-rule-edit').onclick=resetForm;
  if($('add-rule-condition')){
   $('add-rule-condition').onclick=()=>{
    captureDraftInputs();
    if(formConditions.length>=20){showError('最多添加 20 个匹配条件');return}
    formConditions.push({field:'ORDER_CONTENT',operator:'CONTAINS',value:''});
    renderRulesSheet(`[data-condition-keyword="${formConditions.length-1}"]`);
   };
  }
  document.querySelectorAll('[data-remove-condition]').forEach(b=>{
   b.onclick=()=>{
    const idx=Number(b.dataset.removeCondition);
    captureDraftInputs();
    if(formConditions.length<=1){showError('规则至少保留一个匹配条件');return}
    if(idx>=0&&idx<formConditions.length){
     formConditions.splice(idx,1);
     renderRulesSheet('#rule-name');
    }
   };
  });
  $('save-rule').onclick=()=>{
   showError('');
   captureDraftInputs();
   const rawName=formName.trim(),dir=formDir,catId=formCategoryId,enabled=formEnabled;
   if(!rawName){showError('请输入规则名称',$('rule-name'));return}
   if(rawName.length>80){showError('规则名称不能超过 80 个字符',$('rule-name'));return}
   if(/[\u0000-\u001F\u007F-\u009F]/.test(rawName)){showError('规则名称包含不支持的字符',$('rule-name'));return}
   if(!['EXPENSE','INCOME'].includes(dir)){showError('请选择有效的适用方向',$('rule-direction'));return}
   const validCat=directoryItems(dir).find(c=>c.id===catId&&c.direction===dir);
   if(!validCat){showError('请选择当前方向下的有效目标分类',$('rule-category'));return}
   if(!formConditions.length){showError('请至少添加一个匹配条件');return}
   if(formConditions.length>20){showError('匹配条件不能超过 20 个');return}
   for(let i=0;i<formConditions.length;i++){
    const cond=formConditions[i];
    const fName=cond.field==='ORDER_CONTENT'?'订单内容':'商家';
    const kwEl=document.querySelector(`[data-condition-keyword="${i}"]`)||document.querySelector(`[data-condition-row="${i}"] input`)||$('rule-keyword');
    const opEl=document.querySelector(`[data-condition-operator="${i}"]`)||$('rule-operator');
    if(!['CONTAINS','EQUALS','NOT_CONTAINS'].includes(cond.operator)){showError(`请选择有效的${fName}匹配方式`,opEl);return}
    const rawKeyword=cond.value.trim();
    if(!rawKeyword||!normRuleKeyword(rawKeyword)){showError(`请输入有效的${fName}关键词（不能仅为空白）`,kwEl);return}
    if(rawKeyword.length>200){showError(`${fName}关键词不能超过 200 个字符`,kwEl);return}
    const hasIllegalControl=Array.from(rawKeyword).some(c=>{const cp=c.codePointAt(0);return (cp<=31||(cp>=127&&cp<=159))&&!/\s/u.test(c)});
    if(hasIllegalControl){showError(`${fName}关键词包含不支持的控制字符`,kwEl);return}
   }
   if(!native?.saveClassificationRule){showError('请在 Android 应用中保存自动分类规则');return}
   try{
    const wasEditing=!!(editingRule&&editingRule.id);
    const dirRules=customRulesByDirection(dir);
    const order=wasEditing&&editingRule.direction===dir?editingRule.order:dirRules.length;
    const payload={
     ...(editingRule?.id?{id:editingRule.id}:{}),
     name:rawName,
     enabled,
     direction:dir,
     categoryId:validCat.id,
     order,
     matchMode:formMatchMode==='ANY'?'ANY':'ALL',
     source:formSource?formSource:null,
     conditions:formConditions.map(c=>({field:c.field,operator:c.operator,value:c.value.trim()}))
    };
    const result=JSON.parse(native.saveClassificationRule(JSON.stringify(payload)));
    if(!result.ok){if(result.code==='RULE_TARGET')syncFromNative();showError(result.error||'规则保存失败');return}
    editingRule=null;manageDir=dir;formDir=dir;formName='';formConditions=[{field:'MERCHANT',operator:'CONTAINS',value:''}];formMatchMode='ALL';formSource='';formEnabled=true;formCategoryId=validCat.id;
    applyRulesResult(result);renderRulesSheet('#rule-name');notify(wasEditing?'规则已更新':'规则已保存');
   }catch(_){showError('规则保存失败，请重试')}
  };
  document.querySelectorAll('[data-edit-rule]').forEach(b=>b.onclick=()=>{
   const found=rules.find(r=>r.id===b.dataset.editRule);if(!found)return;
   editingRule={...found};manageDir=found.direction;formDir=found.direction;formName=found.name;formEnabled=found.enabled!==false;formCategoryId=ensureValidCategoryId(found.direction,found.categoryId);
   formMatchMode=found.matchMode==='ANY'?'ANY':'ALL';formSource=found.source||'';
   formConditions=Array.isArray(found.conditions)&&found.conditions.length
    ?found.conditions.map(c=>({field:c.field==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT',operator:c.operator==='EQUALS'?'EQUALS':c.operator==='NOT_CONTAINS'?'NOT_CONTAINS':'CONTAINS',value:c.value||''}))
    :[{field:'MERCHANT',operator:'CONTAINS',value:''}];
   renderRulesSheet('#rule-name');
  });
  document.querySelectorAll('[data-toggle-rule]').forEach(b=>b.onclick=()=>{
   const found=rules.find(r=>r.id===b.dataset.toggleRule);if(!found)return;
   if(!native?.saveClassificationRule){showError('请在 Android 应用中修改规则状态');return}
   captureDraftInputs();
   const nextEnabled=!(found.enabled!==false);
   const payload={id:found.id,name:found.name,enabled:nextEnabled,direction:found.direction,categoryId:found.categoryId,order:found.order,matchMode:found.matchMode||'ALL',source:found.source||null,conditions:found.conditions};
   try{
    const result=JSON.parse(native.saveClassificationRule(JSON.stringify(payload)));
    if(!result.ok){showError(result.error||'规则状态更新失败');return}
    if(editingRule?.id===found.id){editingRule.enabled=nextEnabled;formEnabled=nextEnabled}
    applyRulesResult(result);renderRulesSheet(`[data-toggle-rule="${CSS.escape(found.id)}"]`);notify(nextEnabled?'规则已启用':'规则已停用');
   }catch(_){showError('规则状态更新失败，请重试')}
  });
  const moveRule=(id,delta)=>{
   const idx=rules.findIndex(r=>r.id===id),targetIdx=idx+delta;if(idx<0||targetIdx<0||targetIdx>=rules.length)return;
   if(!native?.reorderClassificationRules){showError('请在 Android 应用中调整规则顺序');return}
   captureDraftInputs();
   const ids=rules.map(r=>r.id);[ids[idx],ids[targetIdx]]=[ids[targetIdx],ids[idx]];
   try{
    const result=JSON.parse(native.reorderClassificationRules(manageDir,JSON.stringify(ids)));
    if(!result.ok){showError(result.error||'规则排序失败');return}
    applyRulesResult(result);
    const preferredBtn=delta<0?(targetIdx===0?`[data-rule-down="${CSS.escape(id)}"]`:`[data-rule-up="${CSS.escape(id)}"]`):(targetIdx===rules.length-1?`[data-rule-up="${CSS.escape(id)}"]`:`[data-rule-down="${CSS.escape(id)}"]`);
    renderRulesSheet(preferredBtn);
   }catch(_){showError('规则排序失败，请重试')}
  };
  document.querySelectorAll('[data-rule-up]').forEach(b=>b.onclick=()=>moveRule(b.dataset.ruleUp,-1));
  document.querySelectorAll('[data-rule-down]').forEach(b=>b.onclick=()=>moveRule(b.dataset.ruleDown,1));
  document.querySelectorAll('[data-delete-rule]').forEach(b=>b.onclick=()=>{
   const found=rules.find(r=>r.id===b.dataset.deleteRule);if(!found)return;
   if(!native?.deleteClassificationRule){showError('请在 Android 应用中删除规则');return}
   captureDraftInputs();
   try{
    const result=JSON.parse(native.deleteClassificationRule(found.id));
    if(!result.ok){showError(result.error||'规则删除失败');return}
    if(editingRule?.id===found.id){editingRule=null;formName='';formConditions=[{field:'MERCHANT',operator:'CONTAINS',value:''}];formMatchMode='ALL';formSource='';formEnabled=true}
    applyRulesResult(result);renderRulesSheet('#rule-name');notify('规则已删除');
   }catch(_){showError('规则删除失败，请重试')}
  });
  document.querySelectorAll('[data-toggle-builtin-rule]').forEach(b=>{
   b.onclick=()=>{
    const found=builtinRules.find(r=>r.id===b.dataset.toggleBuiltinRule);if(!found)return;
    if(!native?.setBuiltinClassificationRuleEnabled){showError('请在 Android 应用中修改内置规则状态');return}
    captureDraftInputs();
    const nextEnabled=!(found.enabled!==false);
    const payload={id:found.id,enabled:nextEnabled};
    try{
     const result=JSON.parse(native.setBuiltinClassificationRuleEnabled(JSON.stringify(payload)));
     if(!result.ok){showError(result.error||'内置规则状态更新失败');return}
     applyRulesResult(result);renderRulesSheet(`[data-toggle-builtin-rule="${CSS.escape(found.id)}"]`);notify(nextEnabled?'内置规则已启用':'内置规则已停用');
    }catch(_){showError('内置规则状态更新失败，请重试')}
   };
  });
  document.querySelectorAll('[data-copy-builtin-rule]').forEach(b=>{
   b.onclick=()=>{
    const found=builtinRules.find(r=>r.id===b.dataset.copyBuiltinRule);if(!found)return;
    if(!native?.copyBuiltinClassificationRule){showError('请在 Android 应用中复制内置规则');return}
    try{
     const result=JSON.parse(native.copyBuiltinClassificationRule(found.id));
     if(!result.ok||!result.rule){showError(result.error||'无法复制内置规则');return}
     const draftRule=result.rule;
     editingRule={id:null,name:draftRule.name||`${found.name}（副本）`};
     formDir=draftRule.direction||found.direction;
     manageDir=formDir;
     formName=draftRule.name||`${found.name}（副本）`;
     formEnabled=true;
     formCategoryId=ensureValidCategoryId(formDir,draftRule.categoryId||found.categoryId);
     formMatchMode=draftRule.matchMode==='ANY'?'ANY':'ALL';
     formSource=draftRule.source||'';
     formConditions=Array.isArray(draftRule.conditions)&&draftRule.conditions.length
      ?draftRule.conditions.map(c=>({field:c.field==='ORDER_CONTENT'?'ORDER_CONTENT':'MERCHANT',operator:c.operator==='EQUALS'?'EQUALS':c.operator==='NOT_CONTAINS'?'NOT_CONTAINS':'CONTAINS',value:String(c.value||'')}))
      :[{field:'MERCHANT',operator:'CONTAINS',value:''}];
     renderRulesSheet('#rule-name');
     notify('已载入规则副本，修改后请点击保存');
    }catch(_){showError('复制内置规则失败，请重试')}
   };
  });
  if(focusSelector){const target=$('sheet-content')?.querySelector(focusSelector);if(target&&!target.disabled)target.focus({preventScroll:true});else $('rule-name')?.focus({preventScroll:true})}
 };
 activeRulesSheetRefresh=(pullNative=false)=>{if(internalMutation)return;if(!$('sheet')?.open||!$('classification-rules-list')){activeRulesSheetRefresh=null;return}captureDraftInputs();if(pullNative)syncFromNative();formCategoryId=ensureValidCategoryId(formDir,formCategoryId);renderRulesSheet()};
 renderRulesSheet('#rule-name');
}
const categoryAliases=[
 [/停车|泊车/,'square-parking'],[/高速|路桥|过路|通行/,'road'],[/母婴|亲子|育儿|奶粉|宝宝/,'baby'],[/宠物/,'paw'],
 [/咖啡|茶饮|奶茶/,'mug-saucer'],[/餐|美食|饮食|食品|外卖|零食|吃饭/,'utensils'],[/交通|打车|公交|地铁|出行|车费/,'bus'],
 [/汽车|加油|充电|养车/,'car'],[/购物|网购|数码|电器|淘宝|拼多多/,'bag-shopping'],[/日用|生活用品|超市/,'basket-shopping'],
 [/服饰|衣|鞋|包|配饰/,'shirt'],[/医疗|药|健康|看病/,'briefcase-medical'],[/教育|学习|培训|书/,'graduation-cap'],
 [/居住|住房|房租|物业|家居/,'house'],[/水电|燃气/,'bolt'],[/通讯|通信|话费|网络/,'phone'],[/娱乐|游戏|电影/,'gamepad'],
 [/运动|健身/,'dumbbell'],[/旅行|旅游|酒店/,'suitcase'],[/美容|护肤|美妆/,'spa'],[/红包/,'envelope'],
 [/人情|礼物|礼品|赠/,'gift'],[/转账/,'money-bill-transfer'],[/工资|薪资/,'wallet'],[/奖金|奖励/,'award'],[/退款|退货/,'rotate-left'],
 [/投资|理财|利息/,'chart-line'],[/办公|工作/,'briefcase']
];
function presetOrAliasIcon(name){const value=String(name||'其他').trim();return [...expenseCategories,...incomeCategories].find(c=>c[0]===value)?.[1]||categoryAliases.find(([pattern])=>pattern.test(value))?.[1]||''}
function iconForCategory(name,direction=null,explicitIcon=null){
 const direct=safeIconKey(explicitIcon);if(direct)return direct;
 const value=String(name||'其他').trim();
 if(categoryDirectory){
  const dirs=direction?[String(direction).toLowerCase()==='income'?'income':'expense']:['expense','income'];
  for(const k of dirs){const found=(categoryDirectory[k]||[]).find(c=>String(c?.name||'').trim()===value);const key=safeIconKey(found?.icon);if(key)return key}
 }
 return presetOrAliasIcon(value);
}
function categoryBadge(name,direction=null,explicitIcon=null){const label=String(name||'其他'),icon=iconForCategory(label,direction,explicitIcon),key=icon||label,tone=Array.from(key).reduce((sum,c)=>sum+c.codePointAt(0),0)%5;return `<span class="circle category-icon category-tone-${tone}">${icon?`<i class="fa-solid fa-${icon}" aria-hidden="true"></i>`:`<span class="category-initial" aria-hidden="true">${escapeText(Array.from(label)[0])}</span>`}</span>`}

function categoryTotals(list){const groups=new Map();for(const r of list){const transfer=r.direction==='TRANSFER',amount=r.direction==='INCOME'?cents(r.amountCents):expenseOf(r);if(transfer&&amount===0n)continue;const resolved=resolveRecordCategory(r),catName=resolved.name,key=transfer?'system:transfer_fee':`category:${catName}`;const old=groups.get(key)||{key,categoryId:transfer?null:resolved.id,icon:transfer?'':resolved.icon,direction:r.direction==='INCOME'?'INCOME':'EXPENSE',name:transfer?'转账手续费':catName==='转账手续费'?'转账手续费（自定义）':catName,amount:0n,count:0};old.amount+=amount;old.count++;groups.set(key,old)}return [...groups.values()].sort((a,b)=>a.amount>b.amount?-1:a.amount<b.amount?1:0)}
function statsCategories(type){return periodStats?periodStats.categories.filter(r=>r.direction===(type==='income'?'INCOME':'EXPENSE')):loadedCategories(type)}
function drawStats(){
 if(periodStats&&native?.statistics){try{const latest=JSON.parse(native.statistics(statsPeriod));if(!latest.ok)throw Error(latest.error);periodStats=latest}catch(error){notify(error.message||'统计刷新失败，保留上次数据')}}
 const totals=periodStats?.totals||loadedTotals(),total=statsType==='all'?cents(totals.income)+cents(totals.expense):cents(totals[statsType]),name=statsType==='income'?'收入':statsType==='all'?'收支':'支出';
 const list=monthRecords(),count=statsType==='all'?(totals.totalCount??list.length):(totals[statsType+'Count']??list.filter(r=>statsType==='income'?r.direction==='INCOME':r.direction==='EXPENSE'||r.direction==='TRANSFER'&&expenseOf(r)>0n).length);
 const data=statsType==='all'?[{name:'支出',amount:totals.expense},{name:'收入',amount:totals.income}].filter(d=>cents(d.amount)>0n):statsCategories(statsType);
 const [y,m]=month.split('-');$('stats-month').textContent=periodStats?`${localTime(periodStats.rangeStart).slice(0,10)}～${localTime(periodStats.rangeEnd-1).slice(0,10)}`:`${y}年${Number(m)}月`;
 $('stats-label').textContent='总'+name;$('analysis-label').textContent=(periodStats?({WEEK:'本周',MONTH:'本月',YEAR:'本年'})[statsPeriod]:'本月')+name+'总计';$('stats-total').textContent='¥ '+money(total);$('analysis-total').innerHTML='<small>¥</small> '+money(total);$('analysis-total').classList.toggle('long-amount',money(total).length>17);$('stats-total').classList.toggle('long-amount',money(total).length>14);$('stats-count').textContent=`共 ${count} 笔`;
 const previousTotals=ledgerView?.summary?.previous,prior=statsType==='all'?cents(previousTotals?.income)+cents(previousTotals?.expense):previousTotals?.[statsType];$('stats-change').textContent=periodStats?'转账仅手续费计支出 · 退款抵扣原消费日期':prior&&cents(prior)>0n?`较上月${total>=cents(prior)?'增加':'减少'} ${percentDiff(total,prior)}%`:'暂无上月数据可比较';
 const labels=data.length>6?[...data.slice(0,5),{name:'其他分类',amount:sumValues(data.slice(5).map(d=>d.amount))}]:data;let end=0;
 $('donut').style.background=total>0n?'conic-gradient('+labels.map((d,i)=>{const start=end;end+=ratio(d.amount,total)*100;return `${chartColors[i%chartColors.length]} ${start}% ${end}%`}).join(',')+')':'#EFEDF6';
 $('chart-labels').innerHTML=labels.map((d,i)=>`<div class="legend-row"><i class="dot" style="background:${chartColors[i%chartColors.length]}" aria-hidden="true"></i><span>${escapeText(d.name)}</span><span>${(ratio(d.amount,total)*100).toFixed(0)}%</span></div>`).join('')||'<span class="muted small">暂无分类数据</span>';
 const ranked=statsCategories(rankingType),rankTotal=sumValues(ranked.map(d=>d.amount));
 $('ranking').innerHTML=ranked.map((d,i)=>`<button type="button" data-ranking-category="${escapeText(d.name)}" data-category-key="${escapeText(d.key||d.name)}" data-category-id="${escapeText(d.categoryId||'')}" class="category-row ranking-row">${categoryBadge(d.name,d.direction||(rankingType==='income'?'INCOME':'EXPENSE'),d.icon)}<span class="category-middle ranking-info"><span class="category-name">${escapeText(d.name)}</span><span class="progress-row"><span class="progress"><i style="width:${ratio(d.amount,rankTotal)*100}%;background:${chartColors[i%chartColors.length]}"></i></span><span class="percentage">${(ratio(d.amount,rankTotal)*100).toFixed(0)}%</span></span></span><span class="category-amount">¥ ${money(d.amount)}</span><span class="chevron">›</span></button>`).join('')||'<div class="empty-state">本周期暂无这类开销</div>';
 $('ranking').querySelectorAll('[data-ranking-category]').forEach(b=>b.onclick=()=>showCategoryDetails(b.dataset.rankingCategory,rankingType,b.dataset.categoryKey));
 for(const id of ['stats-type','ranking-type'])document.querySelectorAll('#'+id+' button').forEach(b=>{const selected=b.dataset.type===(id==='stats-type'?statsType:rankingType);b.classList.toggle('active',selected);b.setAttribute('aria-pressed',String(selected))});
 document.querySelectorAll('#stats-period button').forEach(b=>b.classList.toggle('active',b.dataset.period===statsPeriod));
 const chart=$('period-expense-chart');chart.hidden=!periodStats;
 if(periodStats){const max=periodStats.buckets.reduce((a,b)=>cents(b.expenseCents)>a?cents(b.expenseCents):a,0n);chart.innerHTML=`<p>开销合计 ¥ ${money(periodStats.totalExpenseCents)}</p><div class="expense-bars">${periodStats.buckets.map(b=>`<div class="expense-bar ${b.current?'current':''} ${b.future?'future':''}"><span class="expense-bar-value">${money(b.expenseCents)}</span><i style="height:${Math.max(2,ratio(b.expenseCents,max)*80)}px"></i><small>${escapeText(b.label)}</small></div>`).join('')}</div>`}
}
function showCategoryDetails(category,type,key=category){
 let list=[],more=false;
 function load(){try{if(periodStats&&native?.contributionRecords){const result=JSON.parse(native.contributionRecords(statsPeriod,key,type==='income'?'INCOME':'EXPENSE',list.length));if(!result.ok)throw Error(result.error);list.push(...result.records);more=result.hasMore}
 else if(native?.categoryRecords){const result=JSON.parse(native.categoryRecords(month,key,type==='income'?'INCOME':'EXPENSE',list.length));if(!result.ok)throw Error(result.error);list.push(...result.records.filter(r=>r.state==='POSTED'));more=result.hasMore}
 else list=monthRecords().filter(r=>{const cat=resolveRecordCategory(r).name;return type==='income'?r.direction==='INCOME'&&(cat===category||`category:${cat}`===key):key==='system:transfer_fee'?r.direction==='TRANSFER'&&expenseOf(r)>0n:r.direction==='EXPENSE'&&(cat===category||`category:${cat}`===key)}).map(r=>({...r,contributionCents:(type==='income'?cents(r.amountCents):expenseOf(r)).toString()}));draw()}catch(_){notify('明细读取失败，请重试')}}
 function draw(){const summary=statsCategories(type).find(r=>(r.key||r.name)===key);showSheet(`${category} · ${type==='income'?'收入':'支出'}明细`,`<p class="sheet-copy">${summary?.count??list.length} 笔 · 合计 ¥ ${money(summary?.amount??sumValues(list.map(r=>r.contributionCents??r.amountCents)))}</p><div class="category-detail-list">${list.map(r=>`<button type="button" class="category-detail-row" data-category-record="${r.id}">${ledgerIcon(r)}<span><b>${escapeText(r.direction==='TRANSFER'?'转账手续费':transferName(r))}</b><small>${recordDisplayTime(r)}</small></span><strong>${signedMoney(r.contributionCents??(r.direction==='TRANSFER'?r.transfer?.feeCents:r.amountCents),type==='income')}</strong><span class="chevron">›</span></button>`).join('')||'<p class="empty-state">暂无账单</p>'}</div>${more?'<button id="category-more" class="load-more-records">加载更多</button>':''}`);$('sheet-content').querySelectorAll('[data-category-record]').forEach(b=>b.onclick=()=>{const anchor=b.getBoundingClientRect();closeSheet();openRecord(Number(b.dataset.categoryRecord),false,anchor)});if($('category-more'))$('category-more').onclick=load}
 load();
}
document.querySelectorAll('#stats-type button').forEach(b=>b.onclick=()=>{statsType=b.dataset.type;if(statsType!=='all')rankingType=statsType;drawStats();remember()});document.querySelectorAll('#ranking-type button').forEach(b=>b.onclick=()=>{rankingType=b.dataset.type;drawStats();remember()});
function chooseStatsMonth(){statsPeriod='MONTH';periodStats=null;$('month').click()}
// The same month selector drives the ledger and statistics from one real data set.
const monthClick=$('month').onclick;$('month').onclick=()=>{monthClick();const apply=$('month-apply').onclick;$('month-apply').onclick=()=>{apply();drawStats()}};
const accountStyles={bank:['credit-card','#fa5469','#ffe8eb'],alipay:['alipay','#fff','#2199ff'],wechat:['weixin','#fff','#22cc86'],cash:['wallet','#fa6b32','#ffeadf'],custom:['wallet','#7D5BE2','#EFE9FC']};
function accountBank(kind){return bankOptions.find(bank=>bank.kind===kind)}
function accountSymbol(kind){
 const bank=accountBank(kind);if(bank)return `<img class="account-bank-logo" src="${escapeText(bank.icon)}" alt="" width="36" height="36"/>`;
 const [icon,color,bg]=accountStyles[kind]||accountStyles.custom;
 return `<i class="${['alipay','wechat'].includes(kind)?'fa-brands':'fa-solid'} fa-${icon}" style="color:${color}" aria-hidden="true"></i>`;
}
function drawAssets(){
 const assets=accounts.filter(a=>a.type==='ASSET').reduce((s,a)=>s+cents(a.amountCents),0n),debt=accounts.filter(a=>a.type==='LIABILITY').reduce((s,a)=>s+cents(a.amountCents),0n),fmt=value=>assetsHidden?'****':'¥ '+money(value);
 $('net-amount').innerHTML=assetsHidden?'****':`<small>¥</small>${money(assets-debt)}`;
 $('asset-summary').innerHTML=`<div>总资产<b>${fmt(assets)}</b></div><div>总负债<b>${fmt(debt)}</b></div>`;
 $('account-count').textContent=`共 ${accounts.length} 个账户`;
 const accountMarkup=items=>items.map(a=>{const bank=accountBank(a.kind),bg=bank?'#fff':(accountStyles[a.kind]||accountStyles.custom)[2],label=bank?.label||{bank:'银行卡',alipay:'第三方支付',wechat:'第三方支付',cash:'现金账户'}[a.kind]||'自定义账户';return `<button class="card account account-row" data-account="${a.id}" aria-label="编辑账户 ${escapeText(a.name)}，${escapeText(label)}"><span class="circle account-symbol" style="background:${bg}">${accountSymbol(a.kind)}</span><span class="account-info account-copy"><span class="account-first"><span class="account-name">${escapeText(a.name)}${a.uid===walletSettings.defaultAccountUid?'<small class="wallet-default">默认</small>':''}</span><span class="account-amount account-value">${fmt(a.amountCents)}</span><span class="chevron" aria-hidden="true">›</span></span>${a.cardLast4?`<span class="account-desc">尾号 ${escapeText(a.cardLast4)}</span>`:''}${a.description?`<span class="account-desc">${escapeText(a.description)}</span>`:''}${a.type==='LIABILITY'||a.name!==label?`<span class="account-bottom"><span class="tag">${escapeText(a.type==='LIABILITY'?'负债 · '+label:label)}</span></span>`:''}</span></button>`}).join('');$('accounts').innerHTML=accountMarkup(accounts.filter(a=>!a.archived))||'<div class="empty-state">还没有账户，点击右上角 + 添加</div>';if(accounts.some(a=>a.archived))$('accounts').innerHTML+=`<details class="archived-accounts"><summary>已归档账户（仍计入总资产）</summary>${accountMarkup(accounts.filter(a=>a.archived))}</details>`;
 $('accounts').querySelectorAll('[data-account]').forEach(b=>b.onclick=()=>editAccount(Number(b.dataset.account)));
 $('hide-assets').setAttribute('aria-label',assetsHidden?'显示资产':'隐藏资产');$('hide-assets').setAttribute('aria-pressed',String(assetsHidden));$('hide-assets').innerHTML=`<i class="fa-solid fa-${assetsHidden?'eye-slash':'eye'}" aria-hidden="true"></i>`;
}
$('hide-assets').onclick=()=>{assetsHidden=!assetsHidden;drawAssets();remember()};
$('add-account').onclick=()=>editAccount();
function editAccount(id=0){
 const account=accounts.find(a=>a.id===id)||{id:0,name:'',description:'',amountCents:0,type:'ASSET',kind:'custom'};
 const isBank=account.kind==='bank'||!!accountBank(account.kind);
 showSheet(id?'编辑账户':'添加账户',`<form id="account-form" novalidate>
 <div class="account-editor-mark"><span class="circle account-symbol" id="account-preview" aria-hidden="true"></span><span id="account-bank-title"></span></div>
 <label for="account-kind">账户类别</label><select class="month-input" id="account-kind"><option value="bank">银行卡</option><option value="alipay">支付宝</option><option value="wechat">微信</option><option value="cash">现金</option><option value="custom">其他账户</option></select>
 <div id="account-bank-field" hidden><label for="account-bank">银行卡图标</label><select class="month-input" id="account-bank">${bankOptions.map(bank=>`<option value="${escapeText(bank.kind)}">${escapeText(bank.label)}</option>`).join('')}<option value="bank">其他银行</option></select></div>
 <div id="account-bank-identity"><label for="account-bank-code">银行</label><select class="month-input" id="account-bank-code"><option value="">未填写银行</option>${account.bankCode&&!bankCodeOptions.some(b=>b.code===account.bankCode)?`<option value="${escapeText(account.bankCode)}">其他银行</option>`:''}${bankCodeOptions.map(bank=>`<option value="${escapeText(bank.code)}">${escapeText(bank.label)}</option>`).join('')}</select><label for="account-last4">银行卡尾号（可选）</label><input class="month-input" id="account-last4" inputmode="numeric" maxlength="4" value="${escapeText(account.cardLast4||'')}" placeholder="四位数字，例如 0007"/></div><label for="account-name">账户名称（可选）</label><input class="month-input" id="account-name" maxlength="40" value="${escapeText(account.name)}" placeholder="留空使用账户类别"/>
 <label for="account-amount">余额（必填）</label><input class="month-input" id="account-amount" type="text" inputmode="text" required aria-describedby="account-amount-error" value="${id?account.amountCents/100:''}" placeholder="请输入余额，可填 0"/>
 <p class="account-field-error" id="account-amount-error" role="alert" hidden></p>
 <label for="account-type">计入</label><select class="month-input" id="account-type"><option value="ASSET">资产</option><option value="LIABILITY">负债</option></select>
 <label for="account-description">备注（可选）</label><input class="month-input" id="account-description" maxlength="80" value="${escapeText(account.description)}"/>
 <p class="sheet-copy account-balance-hint">新账单自动更新余额，可手动校准。资产可为负数；校准不增加普通收支。</p>${id?'<div class="account-tools"><button type="button" id="account-movements">余额记录</button><button type="button" id="archive-account">归档账户</button></div>':''}<div class="sheet-actions">${id?'<button type="button" id="delete-account">删除账户</button>':'<button type="button" onclick="closeSheet()">取消</button>'}<button class="primary" id="save-account" type="submit">保存账户</button></div></form>`);
 $('account-type').value=account.type;$('account-kind').value=isBank?'bank':account.kind;$('account-bank').value=isBank?account.kind:'bank_icbc';$('account-bank-code').value=account.bankCode||account.kind.replace(/^bank_/, '')||'';const accountSaveToken=submissionToken();
 let previousDefault=accountBank(account.kind)?.label||'';
 const selectedKind=()=>$('account-kind').value==='bank'?$('account-bank').value:$('account-kind').value;
 const updateBank=(fillName=false)=>{const bankMode=$('account-kind').value==='bank',kind=selectedKind(),bank=accountBank(kind);$('account-bank-field').hidden=!bankMode;$('account-bank-identity').hidden=!bankMode;$('account-bank').disabled=!bankMode;
  $('account-preview').innerHTML=accountSymbol(kind);$('account-preview').style.background=bank?'#fff':(accountStyles[kind]||accountStyles.custom)[2];
  $('account-bank-title').textContent=bank?.label||$('account-kind').selectedOptions[0].textContent;
  $('account-name').placeholder='留空使用'+$('account-bank-title').textContent;
  if(fillName&&bank&&(!$('account-name').value.trim()||$('account-name').value===previousDefault))$('account-name').value=bank.label;
  previousDefault=bank?.label||'';
 };
 $('account-kind').onchange=()=>updateBank(true);$('account-bank').onchange=()=>{$('account-bank-code').value=$('account-bank').value.replace(/^bank_/,'');updateBank(true)};updateBank();
 const amountInput=$('account-amount'),amountError=$('account-amount-error');
 const showAmountError=message=>{amountError.textContent=message;amountError.hidden=!message;amountInput.setAttribute('aria-invalid',String(!!message))};
 const validateAmount=()=>{const value=amountInput.value.trim();const message=amountInput.validity.badInput?'请输入有效余额':!value?'请输入余额，可填 0':!/^-?\d+(?:\.\d{1,2})?$/.test(value)?'请输入有效余额，最多两位小数':Math.abs(Number(value))>999999999.99?'账户余额过大':$('account-type').value==='LIABILITY'&&Number(value)<0?'负债余额不能为负数':'';showAmountError(message);return !message};
 amountInput.onblur=validateAmount;amountInput.oninput=()=>{if(amountInput.getAttribute('aria-invalid')==='true')validateAmount()};
 $('account-form').onsubmit=e=>{e.preventDefault();if(!validateAmount()){amountInput.focus();return}if(!native){notify('请在 Android 应用中保存账户');return}
  const bankMode=$('account-kind').value==='bank',last4=$('account-last4').value.trim();if(bankMode&&last4&&!/^[0-9]{4}$/.test(last4)){notify('银行卡尾号需为四位数字');$('account-last4').focus();return}
  const changedBalance=!!id&&Number(amountInput.value)!==Number(account.amountCents)/100;
  const payload={id:account.id,name:$('account-name').value,description:$('account-description').value,amount:amountInput.value,type:$('account-type').value,kind:selectedKind(),bankCode:bankMode?$('account-bank-code').value:null,cardLast4:bankMode?last4:null,calibrateBalance:changedBalance,expectedRevision:account.revision??0,submissionToken:accountSaveToken};
  const commit=()=>{const result=JSON.parse(native.saveAccount(JSON.stringify(payload)));if(!result.ok){if(result.code==='STALE_ACCOUNT_REVISION'){const updated=JSON.parse(native.snapshot());const latest=updated.accounts?.find(a=>a.id===id);if(latest){account.revision=latest.revision;account.amountCents=latest.amountCents}$('account-calibration-confirm')?.remove();showAmountError(result.error||'余额已更新，请重新核对');notify('余额已更新，输入已保留；请核对后再次保存')}else if(/余额/.test(result.error||'')){showAmountError(result.error);amountInput.focus()}else notify(result.error||'保存失败');return}refresh();drawAssets();closeSheet();notify('账户已保存')};
  if(changedBalance){$('account-calibration-confirm')?.remove();const box=document.createElement('div');box.id='account-calibration-confirm';box.className='calibration-confirm';box.setAttribute('role','status');const copy=document.createElement('p');copy.textContent=`确认校准余额：¥${money(account.amountCents)} → ¥${money(Math.round(Number(amountInput.value)*100))}。不会新增普通收入或支出。`;const confirm=document.createElement('button');confirm.type='button';confirm.className='primary';confirm.id='confirm-calibration';confirm.textContent='确认校准并保存';confirm.onclick=commit;box.append(copy,confirm);$('account-form').append(box);confirm.focus();return}commit()};
 if(id&&account.type==='ASSET'&&!account.archived){const button=document.createElement('button');button.type='button';button.id='account-transfer';button.className='sheet-row';button.textContent='从此钱包转账';button.onclick=()=>{closeSheet(false);beginManual();manualType='transfer';manualTransfer.fromAccountUid=account.uid;drawCategories()};$('account-form').append(button)}
 if(id){$('account-movements').onclick=()=>showWalletMovements(account);$('archive-account').hidden=!!account.archived;$('archive-account').onclick=()=>{const result=JSON.parse(native.archiveAccount(id));if(!result.ok){notify(result.error||'归档失败');return}refresh();drawAssets();closeSheet();notify('账户已归档，原账单仍可更新余额')}};
 if(id)$('delete-account').onclick=()=>{showSheet('删除这个账户？','<p class="sheet-copy">删除账户后，已有账单会保留。</p><div class="sheet-actions"><button onclick="closeSheet()">取消</button><button class="primary" id="confirm-delete-account">删除账户</button></div>');$('confirm-delete-account').onclick=()=>{const result=JSON.parse(native.deleteAccount(id));if(result.ok){refresh();drawAssets();closeSheet();notify('账户已删除')}else notify(result.error||'删除失败')}};
}

function applySearch(){clearTimeout(searchTimer);query=$('ledger-search').value.slice(0,200);viewLimit=200;refresh()}
function setSearchOpen(open,returnFocus=true){
 clearTimeout(searchTimer);searching=open;
 const header=$('search-toggle').closest('header');
 header.classList.toggle('is-searching',open);$('ledger-search-form').hidden=!open;
 $('search-toggle').setAttribute('aria-expanded',String(open));
 if(open){$('ledger-search').focus();return}
 $('ledger-search').blur();$('ledger-search').value='';$('search-clear').hidden=true;
 query='';viewLimit=200;refresh();
 if(returnFocus)$('search-toggle').focus({preventScroll:true});
}
$('search-toggle').onclick=()=>setSearchOpen(true);
$('search-cancel').onclick=()=>setSearchOpen(false);
$('search-clear').onclick=()=>{$('ledger-search').value='';$('search-clear').hidden=true;applySearch();$('ledger-search').focus()};
$('ledger-search').oninput=()=>{$('search-clear').hidden=!$('ledger-search').value;clearTimeout(searchTimer);searchTimer=setTimeout(applySearch,180)};
$('ledger-search-form').onsubmit=e=>{e.preventDefault();applySearch();$('ledger-search').blur()};
$('ledger-search-form').onkeydown=e=>{if(e.key==='Escape'){e.preventDefault();setSearchOpen(false)}};
function walletLabel(account){return account?`${account.name}${account.cardLast4?' · 尾号 '+account.cardLast4:''}${account.archived?'（已归档）':''}`:'选择钱包'}
function availableWallets(){return accounts.filter(a=>a.type==='ASSET'&&!a.archived)}
function drawManualWallet(){drawTransfer('manual');$('manual-wallet').hidden=manualType==='transfer';const account=accounts.find(a=>a.uid===(manualWalletSelected?manualWalletUid:walletSettings.defaultAccountUid));$('manual-wallet').querySelector('span').textContent=account?walletLabel(account):'选择钱包';}
function drawRecordWallet(){
 if(!draft)return;drawTransfer('record');const isTransfer=$('direction').dataset.value==='TRANSFER';$('record-wallet').hidden=isTransfer;if(isTransfer){$('wallet-explanation').textContent=eligibilityText(draft.wallet?.eligibility);return}const wallet=draft.wallet,uid=draft.manualWalletSelected?draft.walletAccountUid:wallet?.accountUid||(!draft.id?walletSettings.defaultAccountUid:draft.walletAccountUid),account=accounts.find(a=>a.uid===uid);
 const historical=!!draft.id&&wallet&&wallet.eligibility!=='ACTIVE';
 $('record-wallet').disabled=working;$('record-wallet').querySelector('span').textContent=account?walletLabel(account):wallet?.accountName||'选择钱包';
 const source={MANUAL:'人工选择',PAYMENT_METHOD:'根据付款方式匹配',APP:'使用应用钱包',DEFAULT:'使用默认钱包'}[draft.manualWalletSelected?'MANUAL':wallet?.selectionSource]||'';
 let explanation=historical?eligibilityText(wallet.eligibility):draft.state==='POSTED'?`${source}${source?' · ':''}本笔余额变化 ${Number(wallet?.appliedCents)>0?'+':''}${money(wallet?.appliedCents||0)} 元`:uid?'待确认，余额尚未变化':'请选择钱包后入账';
 if(draft.manualWalletSelected&&!historical)explanation='人工选择 · 保存后更新钱包余额';
 if(wallet?.reviewCode?.startsWith('TRANSFER_'))explanation+='\n'+transferReviewMessage(wallet.reviewCode)+'；确认为本人互转时，可切换为转账后核对双方钱包。';
 if(wallet?.reviewCode==='PAYMENT_EVIDENCE_CONFLICT')explanation+=' · 付款方式有冲突，请核对钱包';
 if(wallet?.reviewCode==='PAYMENT_METHOD_AMBIGUOUS')explanation+=' · 组合付款或方式冲突，已使用回退钱包';
 if(wallet?.reviewCode==='PAYMENT_METHOD_UNSUPPORTED')explanation+=' · 此付款方式使用回退钱包';
 if(draft.paymentMethod?.rawValue)explanation+=`\n${draft.paymentMethod.rawLabel||'付款方式'}：${draft.paymentMethod.rawValue}`;
 $('wallet-explanation').textContent=explanation;
}
function chooseWallet(target){
 const current=target==='manual'?(manualWalletSelected?manualWalletUid:walletSettings.defaultAccountUid):(draft?.manualWalletSelected?draft.walletAccountUid:draft?.wallet?.accountUid||walletSettings.defaultAccountUid);
 showSheet('选择钱包',`<p class="sheet-copy">支出扣减、收入增加此钱包。人工选择后，后续识别会保留你的选择。</p>${availableWallets().map(a=>`<button type="button" class="sheet-row wallet-option" data-wallet-uid="${escapeText(a.uid)}" aria-pressed="${a.uid===current}"><span>${escapeText(walletLabel(a))}<small>余额 ¥${money(a.amountCents)}</small></span><b>${a.uid===current?'✓':'选择'}</b></button>`).join('')||'<p class="sheet-copy">暂无可用资产钱包，请先到资产页添加账户。</p>'}<button type="button" id="wallet-go-assets" class="sheet-row">管理资产账户 <span>›</span></button>`);
 document.querySelectorAll('[data-wallet-uid]').forEach(b=>b.onclick=()=>{if(target==='manual'){manualWalletUid=b.dataset.walletUid;manualWalletSelected=true;drawManualWallet()}else{draft.walletAccountUid=b.dataset.walletUid;draft.manualWalletSelected=true;drawRecordWallet()}closeSheet();remember()});
 $('wallet-go-assets').onclick=()=>{closeSheet(false);go('assets')};
}
function showWalletSettings(){
 const options=(selected,empty)=>`<option value="">${empty}</option>`+availableWallets().map(a=>`<option value="${escapeText(a.uid)}" ${a.uid===selected?'selected':''}>${escapeText(walletLabel(a))}</option>`).join('');
 const bindings=Object.entries(walletSettings.appBindings||{}),apps=[['com.tencent.mm','微信'],['com.eg.android.AlipayGphone','支付宝'],...bindings.filter(([pkg])=>!['com.tencent.mm','com.eg.android.AlipayGphone'].includes(pkg)).map(([pkg])=>[pkg,pkg])];
 showSheet('默认钱包与应用绑定',`<p class="sheet-copy">明确的付款方式优先，其次使用应用钱包，再使用默认钱包。设置只影响新账单。</p><label for="default-wallet">默认钱包</label><select id="default-wallet" class="month-input">${options(walletSettings.defaultAccountUid,'未设置')}</select><p class="sheet-copy">没有可用钱包的新账单会等待你选择，不会自动创建账户。</p><h3>应用钱包</h3>${apps.map(([pkg,name])=>`<label for="app-wallet-${escapeText(pkg)}">${escapeText(name)}</label><select class="month-input" id="app-wallet-${escapeText(pkg)}" data-app-wallet="${escapeText(pkg)}">${options(walletSettings.appBindings?.[pkg],'按平台钱包自动匹配')}</select>`).join('')}<details><summary>绑定其他来源应用</summary><label for="binding-package">应用包名</label><input class="month-input" id="binding-package" placeholder="例如 com.example.app" maxlength="200"/><label for="binding-wallet">使用钱包</label><select class="month-input" id="binding-wallet">${options(null,'请选择')}</select><button type="button" id="add-app-binding" class="sheet-row">保存应用绑定</button></details>`);
 const save=(method,...args)=>{if(!native?.[method]){notify('请在 Android 应用中设置钱包');return false}const result=JSON.parse(native[method](...args));if(!result.ok){notify(result.error||'设置失败');return false}viewKey='';refresh();notify('钱包设置已保存');return true};
 $('default-wallet').onchange=()=>{if(!save('setDefaultWallet',$('default-wallet').value))$('default-wallet').value=walletSettings.defaultAccountUid||''};
 document.querySelectorAll('[data-app-wallet]').forEach(input=>input.onchange=()=>{if(!save('setAppWallet',input.dataset.appWallet,input.value))input.value=walletSettings.appBindings?.[input.dataset.appWallet]||''});
 $('add-app-binding').onclick=()=>{if(!$('binding-wallet').value){notify('请选择钱包');return}if(save('setAppWallet',$('binding-package').value.trim(),$('binding-wallet').value))showWalletSettings()};
}
function showWalletMovements(account,offset=0){
 if(!native?.walletMovements)return;const result=JSON.parse(native.walletMovements(account.uid,offset,30));if(!result.ok){notify(result.error||'余额记录读取失败');return}
 const labels={BILL_IMPORT:'账单批次导入',WALLET_IMPORT_SNAPSHOT:'钱包快照校准',POST:'新增账单',EDIT:'账单修改',TRANSFER:'更换钱包',REFUND:'退款',DELETE:'删除撤回',UNDO_DELETE:'撤销删除',CALIBRATION:'余额校准',RESTORE_SNAPSHOT:'恢复快照'};
 showSheet(account.name+' · 余额记录',`<p class="sheet-copy">当前余额 ¥${money(accounts.find(a=>a.uid===account.uid)?.amountCents)}。校准不会新增普通收支。</p>${result.movements.map(m=>`<div class="movement-row"><span><b>${escapeText(labels[m.kind]||'余额变动')}</b><small>${escapeText(m.summary||'')} · ${localTime(m.createdAt).replace('T',' ')}</small></span><span><b>${m.deltaCents>0?'+':''}${money(m.deltaCents)}</b><small>${money(m.balanceBefore)} → ${money(m.balanceAfter)}</small></span></div>`).join('')||'<p class="sheet-copy">暂无余额变动记录</p>'}<div class="sheet-actions">${offset?'<button id="movements-prev">上一页</button>':''}${result.hasMore?'<button id="movements-next">下一页</button>':''}</div>`);
 if($('movements-prev'))$('movements-prev').onclick=()=>showWalletMovements(account,Math.max(0,offset-30));if($('movements-next'))$('movements-next').onclick=()=>showWalletMovements(account,offset+30);
}
let shakeCatalogSession=0,shakeCatalogVersion=null;
function openShakeWhitelist(enableAfter=false){
 if(window.LedgerSetup?.isOpen())window.LedgerSetup.close();const session=++shakeCatalogSession;shakeCatalogVersion=null;
 showSheet('主动识别 · 应用白名单','<p class="sheet-copy">摇一摇与「识别当前账单」快捷方式仅在你选择的应用内取图。快捷方式无需开启摇一摇；移除应用后，两种方式尚未完成的识别都会停止。</p><label class="sr-only" for="shake-app-search">搜索应用名称或包名</label><input id="shake-app-search" class="month-input" type="search" maxlength="200" placeholder="搜索应用名称或包名"/><p id="shake-selected-count" class="sheet-copy" role="status">正在读取应用列表…</p><div id="shake-app-list" class="shake-app-list"></div><button type="button" id="shake-app-more" class="sheet-row" hidden>加载更多应用</button><p class="sheet-copy">系统桌面、输入法和本应用不可选择。目录仅包含当前用户空间可见的应用。</p><button type="button" id="shake-app-done" class="primary">完成选择</button>');
 $('shake-app-done').textContent=enableAfter?'继续开启摇一摇':'完成选择';$('shake-app-done').onclick=()=>{if(enableAfter&&!captureHealth.shakeSelectedCount){notify('请选择允许摇一摇记账的应用');return}closeSheet();if(enableAfter)setChannel('ocr',true)};
 let searchTimer;const load=(offset=0,reloadCatalog=false)=>{
  if(session!==shakeCatalogSession||!$('shake-app-list'))return;
  if(!native?.listInstalledApplications){$('shake-selected-count').textContent='请在 Android 应用中选择应用';return}
  const result=JSON.parse(native.listInstalledApplications($('shake-app-search').value,offset,60,reloadCatalog));if(!result.ok){$('shake-selected-count').textContent=result.error||'应用列表读取失败';return}
  if(offset&&shakeCatalogVersion!==result.version){load(0);return}shakeCatalogVersion=result.version;
  $('shake-selected-count').textContent=`已选 ${result.selectedCount} 个应用 · 共 ${result.total} 个搜索结果`;
  if(!offset)$('shake-app-list').replaceChildren();
  for(const app of result.applications){const row=document.createElement('label');row.className='shake-app-row';const icon=document.createElement('img');icon.alt='';icon.loading='lazy';if(/^\/installed-app-icon\/[a-f0-9]{64}\.png$/.test(app.iconUrl||''))icon.src='https://appassets.androidplatform.net'+app.iconUrl;else icon.style.visibility='hidden';icon.onerror=()=>{icon.style.visibility='hidden'};row.append(icon);const text=document.createElement('span'),title=document.createElement('b'),detail=document.createElement('small');title.textContent=app.label;detail.textContent=app.packageName+(app.system?' · 系统应用':'')+(app.reason?' · '+app.reason:'');text.append(title,detail);row.append(text);const input=document.createElement('input');input.type='checkbox';input.checked=!!app.selected;input.disabled=!app.selectable;input.setAttribute('aria-label','允许'+app.label+'主动识别账单');input.dataset.shakePackage=app.packageName;input.onchange=()=>{const saved=JSON.parse(native.setShakeApplication(app.packageName,input.checked));if(!saved.ok){input.checked=!input.checked;notify(saved.error||'应用选择保存失败');return}captureHealth=saved.captureHealth||captureHealth;viewKey='';refresh();load(0)};row.append(input);$('shake-app-list').append(row)}
  $('shake-app-more').hidden=!result.hasMore;$('shake-app-more').onclick=()=>load(offset+result.applications.length);
 };
 $('shake-app-search').oninput=()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>load(0),180)};setTimeout(()=>load(0,true),0);
}

refresh();drawCategories();drawStats();drawAssets();
const summaryCard=document.querySelector('#ledger .balance');if(summaryCard){summaryCard.setAttribute('role','button');summaryCard.tabIndex=0;summaryCard.onclick=e=>{if(!e.target.closest('button')){lastControl=summaryCard;lastControlAt=Date.now();showTrendDetails()}};summaryCard.onkeydown=e=>{if((e.key==='Enter'||e.key===' ')&&e.target===summaryCard){e.preventDefault();lastControl=summaryCard;lastControlAt=Date.now();showTrendDetails()}}}

$('record-shade').onclick=()=>cancelEditor();

function eligibilityText(value){return ({ACTIVE:'已联动 · 本金不计收支，手续费计支出',IMPORT_EXCLUDED:'导入未联动 · 后续编辑、删除和撤销均不改变钱包余额',LEGACY_EXCLUDED:'历史未联动 · 钱包余额不变',RESTORE_EXCLUDED:'恢复未联动 · 钱包余额不变'})[value]||'待确认，双方余额尚未变化'}
function decimalCents(value){if(!/^\d+(?:\.\d{1,2})?$/.test(value))throw Error('请输入最多两位小数的非负金额');const [whole,fraction='']=value.split('.');const n=BigInt(whole)*100n+BigInt(fraction.padEnd(2,'0'));if(n>99999999999n)throw Error('金额过大');return n}
function readTransfer(target){
 const saved=target==='manual'?manualTransfer:(draft?.transferInput||draft?.transfer||{}),field=name=>$(target+'-'+name);
 return {fromAccountUid:field('from')?field('from').value||null:saved.fromAccountUid||null,toAccountUid:field('to')?field('to').value||null:saved.toAccountUid||null,
  fee:field('fee')?.value??saved.fee??(saved.feeCents!=null?money(saved.feeCents).replace(/,/g,''):target==='manual'?'0':''),feeMode:field('fee-mode')?.value||saved.feeMode||'EXTRA'};
}
function transferAmounts(target){const data=readTransfer(target),amount=decimalCents($(target==='manual'?'manual-amount':'amount').value||'0');if(amount<=0n)throw Error('请输入大于零的转账金额');if(data.fee===''&&target==='record'&&draft?.transfer?.feeCents==null)throw Error('请确认手续费，无费用请填 0');const fee=decimalCents(data.fee||'0');if(data.feeMode==='DEDUCTED'&&fee>=amount)throw Error('到账扣费时，手续费须小于转账金额');const debit=data.feeMode==='EXTRA'?amount+fee:amount,credit=data.feeMode==='DEDUCTED'?amount-fee:amount;if(debit>99999999999n)throw Error('实际扣款金额过大');return {debit,credit,fee}}
function validTransfer(target){try{const data=readTransfer(target);const history=target==='record'&&draft?.id&&draft.wallet?.eligibility!=='ACTIVE',reference=end=>history&&draft.transfer?.[end]?.reference;if((!data.fromAccountUid&&!reference('from'))||(!data.toAccountUid&&!reference('to')))throw Error('请选择转出和转入钱包');if(data.fromAccountUid&&data.fromAccountUid===data.toAccountUid)throw Error('转出与转入钱包不能相同');transferAmounts(target);return true}catch(error){notify(error.message);return false}}
function drawTransfer(target){
 const box=$(target+'-transfer');if(!box)return;const active=target==='manual'?manualType==='transfer':$('direction').dataset.value==='TRANSFER';box.hidden=!active;if(!active)return;const editKey=target==='manual'?manualSubmissionToken:String(draft?.uid||draft?.submissionToken||draft?.id);if(box.dataset.recordKey===editKey&&box.contains(document.activeElement)&&box.querySelector('select')){updateTransferPreview(target);return}
 const data=target==='manual'?manualTransfer:(draft.transferInput||draft.transfer||{}),original=target==='record'?draft.transfer:null;
 const options=(selected,end)=>'<option value="">'+escapeText(original?.[end]?.reference?.name?original[end].reference.name+'（未关联）':'请选择自己的资产钱包')+'</option>'+accounts.filter(a=>a.type==='ASSET'&&(!a.archived||original&&[original.fromAccountUid,original.toAccountUid].includes(a.uid))).map(a=>`<option value="${escapeText(a.uid)}" ${a.uid===selected?'selected':''}>${escapeText(walletLabel(a))}</option>`).join('');
 box.innerHTML=`<div class="transfer-wallets"><label>转出钱包<select id="${target}-from">${options(data.fromAccountUid,'from')}</select></label><button type="button" id="${target}-swap" aria-label="交换转出和转入钱包">⇄</button><label>转入钱包<select id="${target}-to">${options(data.toAccountUid,'to')}</select></label></div><div class="transfer-fees"><label>手续费<input id="${target}-fee" inputmode="decimal" aria-label="手续费" value="${escapeText(data.fee??(data.feeCents!=null?money(data.feeCents).replace(/,/g,''):target==='manual'?'0':''))}" placeholder="请确认，无费用填0"/></label><label>费用方式<select id="${target}-fee-mode"><option value="EXTRA" ${data.feeMode!=='DEDUCTED'?'selected':''}>额外扣费</option><option value="DEDUCTED" ${data.feeMode==='DEDUCTED'?'selected':''}>到账扣费</option></select></label></div><p id="${target}-transfer-preview" class="wallet-explanation" role="status"></p>${target==='record'&&draft.wallet?.reviewCode?'<p class="transfer-review">请核对：'+escapeText(transferReviewMessage(draft.wallet?.reviewCode))+'</p>':''}`;
 box.dataset.recordKey=editKey;
 const keep=()=>{const value=readTransfer(target);if(target==='manual')manualTransfer=value;else draft.transferInput=value;updateTransferPreview(target);remember()};
 for(const name of ['from','to','fee','fee-mode'])$(target+'-'+name).oninput=keep;
 $(target+'-swap').onclick=()=>{const from=$(target+'-from'),to=$(target+'-to'),value=from.value;from.value=to.value;to.value=value;keep()};updateTransferPreview(target);
}
function updateTransferPreview(target){const label=$(target+'-transfer-preview');if(!label)return;try{const {debit,credit,fee}=transferAmounts(target);const value=readTransfer(target),fromAccount=accounts.find(a=>a.uid===value.fromAccountUid),toAccount=accounts.find(a=>a.uid===value.toAccountUid),from=fromAccount?walletLabel(fromAccount):draft?.transfer?.from?.reference?.name||'转出',to=toAccount?walletLabel(toAccount):draft?.transfer?.to?.reference?.name||'转入';label.textContent=`${from}扣 ¥${money(debit)}，${to}到 ¥${money(credit)}；计支出 ¥${money(fee)}`}catch(error){label.textContent=error.message}}
function openStatistics(period){if(!['WEEK','MONTH','YEAR'].includes(period)){notify('统计周期无效');return}statsPeriod=period;try{if(native?.statistics){const result=JSON.parse(native.statistics(period));if(!result.ok)throw Error(result.error);periodStats=result}else periodStats=null;go('stats')}catch(error){notify(error.message||'统计读取失败')}}
document.querySelectorAll('#stats-period button').forEach(button=>button.onclick=()=>openStatistics(button.dataset.period));
$('manual-amount').addEventListener('input',()=>updateTransferPreview('manual'));$('amount').addEventListener('input',()=>updateTransferPreview('record'));

function transferReviewMessage(code){return ({TRANSFER_REVIEW_REQUIRED:'请人工核对这笔本人互转，确认后保存入账',TRANSFER_WALLET_REQUIRED:'请补全双方钱包后一次性入账',TRANSFER_FEE_AMBIGUOUS:'请确认手续费及扣费方式，无费用请填0',TRANSFER_IDENTITY_CONFLICT:'与同一交易的已有记录有冲突，请核对类型、金额及手续费',TRANSFER_PAYMENT_EVIDENCE_CONFLICT:'新账户证据与原选择不同，请核对双方钱包',TRANSFER_STATUS_REQUIRED:'尚未确认转账完成，请核对实际到账状态',TRANSFER_AMOUNT_CONFLICT:'实际扣款、到账与手续费不一致，请核对',BALANCE_LIMIT:'钱包余额将超过支持范围，请核对'})[code]||'交易账户、金额或手续费存在待确认信息'}

function storedDraft(){if(!draft)return null;const {transfer:transferInput,...fields}=readDraft();return {...draft,...fields,...(transferInput?{transferInput}:{})}}
