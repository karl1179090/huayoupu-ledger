'use strict';
// Node 22's built-in WebSocket talks only to the selected emulator's forwarded WebView socket.
const port=Number(process.argv[2]);
if(!Number.isInteger(port)||port<1||port>65535)throw Error('Pass the local adb-forwarded port');
(async()=>{
 const targets=await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
 const target=targets.find(t=>t.type==='page'&&t.url?.includes('/web/index.html'));
 if(!target?.webSocketDebuggerUrl)throw Error('The routed ledger WebView is not ready');
 const socket=new WebSocket(target.webSocketDebuggerUrl);
 const timer=setTimeout(()=>{socket.close();process.stderr.write('WebView route evaluation timed out\n');process.exit(1)},10000);
 socket.addEventListener('error',()=>{clearTimeout(timer);process.exit(1)});
 socket.addEventListener('open',()=>socket.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{
  expression:"JSON.stringify({page:page,period:statsPeriod,cents:periodStats&&String(periodStats.totalExpenseCents),display:document.querySelector('#analysis-total')?.innerText})",returnByValue:true
 }})));
 socket.addEventListener('message',event=>{
  const value=JSON.parse(event.data);if(value.id!==1)return;
  clearTimeout(timer);socket.close();
  if(value.error||value.result?.exceptionDetails){process.stderr.write(JSON.stringify(value));process.exitCode=1;return}
  console.log(value.result.result.value);
 });
})().catch(error=>{console.error(error.message);process.exitCode=1});
