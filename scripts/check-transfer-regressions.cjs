'use strict';
const path=require('node:path'),{spawnSync}=require('node:child_process');
const scripts=['check-record-motion.cjs','check-tab-navigation.cjs','check-ledger-optimization.cjs','check-ledger-search.cjs','check-wallet-whitelist.cjs','check-transfers.cjs','check-transfer-acceptance.cjs','check-category-directory.cjs','check-receipt-details.cjs','check-classification-rules.cjs','check-transaction-time.cjs','check-bank-receipt-ui.cjs'];
for(const script of scripts){
  const result=spawnSync(process.execPath,[path.join(__dirname,script)],{cwd:path.resolve(__dirname,'..'),env:process.env,stdio:'inherit'});
  if(result.error)throw result.error;
  if(result.status!==0)process.exit(result.status||1);
}
console.log(`PASS ${scripts.length} browser regression scripts`);
