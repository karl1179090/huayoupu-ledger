package net.ankio.ledger.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.CheckBox
import kotlinx.coroutines.*
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.Direction
import net.ankio.ledger.data.*
import net.ankio.ledger.ledger

/** Native file choices stay outside WebView; every visible change rebuilds a confirmed plan. */
internal class NativeFileImports(private val activity:MainActivity) {
    private val store get()=activity.ledger.store
    private var sheet:MintDialog?=null
    private var errors:List<LedgerCsvImport.RowError> = emptyList()
    private var generation=0
    private var localAccounts:List<LedgerAccount> = emptyList()
    private val receipts get()=activity.getSharedPreferences("file-import-recovery",Activity.MODE_PRIVATE)
    fun close() { generation++;sheet?.dismiss();sheet=null }
    private fun picker(code:Int,title:String?=null) {
        activity.startActivityForResult(Intent(if(title==null)Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
            type=if(title==null)"*/*" else "text/csv";addCategory(Intent.CATEGORY_OPENABLE)
            if(title==null)putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("text/csv","text/comma-separated-values","application/csv","application/octet-stream")) else putExtra(Intent.EXTRA_TITLE,title)
        },code)
    }
    fun importBills()=picker(MainActivity.CSV_IMPORT_REQUEST)
    fun exportBills(format:String) { val selected=LedgerCsvExport.Format.fromKey(format)
        picker(if(selected==LedgerCsvExport.Format.GENERAL)MainActivity.CSV_GENERAL_REQUEST else MainActivity.CSV_QIANJI_REQUEST,
            "花有谱-${if(selected==LedgerCsvExport.Format.GENERAL)"账单-v2" else "钱迹模板"}-${java.time.LocalDate.now()}.csv") }
    fun importWallets()=picker(WALLET_IMPORT)
    fun exportWallets()=picker(WALLET_EXPORT,"花有谱-钱包-v1-${java.time.LocalDate.now()}.csv")
    fun exportTemplate(kind:String) { require(kind in setOf("bill","wallet"));picker(if(kind=="bill")BILL_TEMPLATE else WALLET_TEMPLATE,"花有谱-${if(kind=="bill")"账单-v2" else "钱包-v1"}-合成模板.csv") }
    fun exportErrors() { if(errors.isEmpty()) { activity.message("当前没有错误清单");return };picker(ERROR_EXPORT,"花有谱-导入错误清单.csv") }
    fun recover() {
        val token=receipts.getString("token",null) ?: return
        activity.uiScope.launch {
            val receipt=withContext(Dispatchers.IO) { store.importReceipt(token) }
            if(receipt!=null) {
                activity.ledger.changed(true)
                val summary=runCatching {
                    val json=org.json.JSONObject(receipt)
                    if(json.has("recordUids"))LedgerCsvImport.Result.decode(receipt).let { "上次账单导入已完成：正式 ${it.posted}，待确认 ${it.pendingWallets}，重复 ${it.duplicates}，错误 ${it.invalid}" }
                    else WalletCsvImport.Result.decode(receipt).let { "上次钱包导入已完成：新增 ${it.imported}，更新 ${it.updated}，保留 ${it.retained}，跳过 ${it.skipped}" }
                }.getOrDefault("上次文件导入已完成，未重复执行资金变化")
                activity.message(summary)
            }
            receipts.edit().clear().apply()
        }
    }
    private fun remember(token:String) { receipts.edit().putString("token",token).commit() }
    private fun done() { receipts.edit().clear().apply() }
    private fun openSheet(title:String,subtitle:String):MintDialog { sheet?.dismiss();return MintDialog(activity,title,subtitle).also { sheet=it } }
    private fun refreshBills(document:LedgerCsvImport.Document,options:BillImportOptions,fallback:LedgerCsvImport.Preview?=null) {
        val version=++generation
        activity.uiScope.launch {
            activity.fileProgress("正在重新计算导入预览")
            try { val result=withContext(Dispatchers.IO) { LedgerCsvImport.prepare(store,document,options) to store.accounts() }
                if(version==generation) { localAccounts=result.second;showBills(document,result.first) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { if(version==generation) { fallback?.let { showBills(document,it) };activity.message(error.message?.take(120) ?: "无法预览，请检查文件") } }
            finally { activity.fileProgress(null) }
        }
    }
    private fun showBills(document:LedgerCsvImport.Document,preview:LedgerCsvImport.Preview) {
        if(activity.isFinishing || activity.isDestroyed)return
        errors=preview.errors;val plan=requireNotNull(preview.plan);val dialog=openSheet("导入账单预览",preview.format)
        dialog.content.addView(dialog.text("共 ${preview.total} 行 · 有效 ${preview.ready} · 重复 ${preview.duplicates} · 错误 ${preview.errors.size}",17,MintDialog.INK,true))
        dialog.content.addView(CheckBox(activity).apply { text="联动钱包余额（新文件默认关闭）";isChecked=preview.options.linkWalletBalances
            setOnCheckedChangeListener { _,checked->dialog.dismiss();refreshBills(document,preview.options.copy(linkWalletBalances=checked),preview) } })
        dialog.content.addView(dialog.text(if(preview.options.linkWalletBalances)"过去日期账单也会影响当前余额；转账双方一起入账。" else "钱包余额变化 ¥0.00；这些账单后续编辑、删除或补充退款也不联动。",13,MintDialog.MUTED))
        dialog.content.addView(CheckBox(activity).apply { text="普通收支缺少钱包时，明确使用当前默认钱包";isChecked=preview.options.useDefaultWallet
            setOnCheckedChangeListener { _,checked->dialog.dismiss();refreshBills(document,preview.options.copy(useDefaultWallet=checked),preview) } })
        if(plan.mappings.isNotEmpty())dialog.action("钱包映射（${plan.mappings.size} 项，同名仅作建议）") {
            AlertDialog.Builder(activity).setTitle("选择文件钱包引用").setItems(plan.mappings.map { mapping->
                "${mapping.reference.name.ifBlank { mapping.reference.uid ?: "未命名" }} → ${localAccounts.firstOrNull { it.uid==mapping.accountUid }?.name ?: "未关联"}${if(!mapping.confirmed && mapping.suggestions.isNotEmpty())" · 有同名建议，需确认" else ""}"
            }.toTypedArray()) { _,index->
                val mapping=plan.mappings[index]
                val candidates=localAccounts.filter { it.type==AccountType.ASSET && (!preview.options.linkWalletBalances || !it.archived) }
                AlertDialog.Builder(activity).setTitle("映射 ${mapping.reference.name}").setItems((listOf("保持未关联历史引用")+candidates.map { "${it.name} · ${it.uid.take(8)}${if(it.archived)" · 归档" else ""}" }).toTypedArray()) { _,choice->
                    dialog.dismiss();refreshBills(document,preview.options.copy(mappings=preview.options.mappings+(mapping.reference.key to if(choice==0)"" else candidates[choice-1].uid)),preview)
                }.show()
            }.show()
        }
        dialog.content.addView(dialog.text("正式 ${preview.posted} 笔 · 待确认 ${preview.pendingWallets} 笔；未匹配端不会单边扣款",14,MintDialog.MUTED))
        val counts=plan.records.groupingBy { it.bill.direction }.eachCount();val fee=plan.records.fold(0L) { sum,r->Math.addExact(sum,r.transfer?.feeCents ?: 0L) }
        dialog.content.addView(dialog.text("支出 ${counts[Direction.EXPENSE] ?: 0} · 收入 ${counts[Direction.INCOME] ?: 0} · 转账 ${counts[Direction.TRANSFER] ?: 0} · 手续费 ¥${Money.format(fee)}",13,MintDialog.MUTED))
        preview.walletImpacts.take(20).forEach { impact->dialog.content.addView(dialog.text("${impact.accountName}：¥${Money.format(impact.beforeCents!!)} → ¥${Money.format(impact.afterCents!!)}",13,MintDialog.INK)) }
        if(preview.walletImpacts.size>20)dialog.action("查看全部 ${preview.walletImpacts.size} 个钱包余额变化") {
            AlertDialog.Builder(activity).setTitle("钱包余额变化").setItems(preview.walletImpacts.map { "${it.accountName}：¥${Money.format(it.beforeCents!!)} → ¥${Money.format(it.afterCents!!)}" }.toTypedArray(),null).show()
        }
        preview.samples.forEach { record->dialog.content.addView(dialog.text("${record.bill.merchant.take(20)} · ${when(record.bill.direction) { Direction.EXPENSE->"支出";Direction.INCOME->"收入";Direction.TRANSFER->"转账";else->"待确认" }} ¥${Money.format(record.bill.amountCents!!)}",13,MintDialog.INK)) }
        errorRows(dialog,preview.errors)
        if(preview.ready>0)dialog.action("确认导入以上 ${preview.ready} 笔",true) {
            dialog.dismiss()
            activity.uiScope.launch {
                activity.fileProgress("正在导入账单")
                try { val result=withContext(Dispatchers.IO) { val context=currentCoroutineContext();context.ensureActive();remember(plan.submissionToken);LedgerCsvImport.applyPreview(store,document,preview) { context.ensureActive() } }
                    activity.ledger.changed(true);activity.message("已入账 ${result.posted} 笔，待确认 ${result.pendingWallets} 笔，重复 ${result.duplicates}，错误 ${result.invalid}");done()
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { if(error is WalletException && error.code=="IMPORT_PREVIEW_STALE")refreshBills(document,preview.options)
                    activity.message(error.message?.take(100) ?: "导入失败，整批未提交") }
                finally { activity.fileProgress(null) }
            }
        }
        dialog.action("取消") { dialog.dismiss() };dialog.show()
    }
    private fun refreshWallets(document:WalletCsvImport.Document,choices:Map<String,WalletCsvImport.Choice> = emptyMap()) {
        val version=++generation
        activity.uiScope.launch {
            activity.fileProgress("正在计算钱包快照预览")
            try { val result=withContext(Dispatchers.IO) { WalletCsvImport.prepare(store,document,choices) to store.accounts() }
                if(version==generation) { localAccounts=result.second;showWallets(document,result.first) } }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { activity.message(error.message?.take(100) ?: "钱包文件无法预览") }
            finally { activity.fileProgress(null) }
        }
    }
    private fun showWallets(document:WalletCsvImport.Document,preview:WalletCsvImport.Preview) {
        if(activity.isFinishing || activity.isDestroyed)return
        errors=preview.plan.errors;val dialog=openSheet("导入钱包预览","钱包 CSV v1 · 绝对余额快照")
        dialog.content.addView(dialog.text("共 ${preview.total} 行 · 可处理 ${preview.entries.size} · 待选择 ${preview.unresolved} · 错误 ${errors.size}",17,MintDialog.INK,true))
        dialog.content.addView(dialog.text("新钱包使用文件余额；已有钱包默认保留本地余额。覆盖属于余额校准，原账单已生效的余额影响保留。归档钱包不会静默重新启用。",13,MintDialog.MUTED))
        preview.accounts.take(20).forEach { row->
            dialog.content.addView(dialog.text("${row.name}：本地 ${row.localBalance?.let { "¥${Money.format(it)}" } ?: "未建"} · 文件 ¥${Money.format(row.fileBalance)} → ${row.finalBalance?.let { "¥${Money.format(it)}" } ?: "待选择"}",13,MintDialog.INK))
            val source=document.rows.firstOrNull { it.account.uid==row.sourceUid }?.account
            val local=localAccounts.firstOrNull { it.uid==row.localAccountUid }
            val action=when(row.action) { WalletCsvImport.Action.NEW->"新建";WalletCsvImport.Action.MERGE->"合并";WalletCsvImport.Action.SKIP->"跳过";null->"待选择或有冲突" }
            val metadata=if(source!=null && (row.action==WalletCsvImport.Action.NEW || row.metadataChanged))
                "名称 ${local?.name ?: "未建"} → ${source.name}；${if(source.type==AccountType.ASSET)"资产" else "负债"} · ${source.kind} · ${source.bankCode.orEmpty()} ${source.cardLast4.orEmpty()}；归档 ${if(source.archived || local?.archived==true)"是" else "否"}；说明 ${source.description.ifEmpty { "无" }}"
            else "资料保留本地"
            dialog.content.addView(dialog.text("$action · $metadata",12,MintDialog.MUTED))
        }
        if(preview.accounts.size>20)dialog.action("查看全部 ${preview.accounts.size} 个账户预览") {
            AlertDialog.Builder(activity).setTitle("账户余额预览").setItems(preview.accounts.map { "${it.name}：本地 ${it.localBalance?.let(Money::format) ?: "未建"} · 文件 ${Money.format(it.fileBalance)} → ${it.finalBalance?.let(Money::format) ?: "待处理"}" }.toTypedArray(),null).show()
        }
        dialog.action("账户匹配／新建／合并／跳过") {
            AlertDialog.Builder(activity).setTitle("选择文件账户").setItems(document.rows.map { "${it.account.name} · ${it.account.uid.take(8)}" }.toTypedArray()) { _,index->
                val source=document.rows[index].account;val locals=localAccounts
                AlertDialog.Builder(activity).setTitle("处理 ${source.name}").setItems((listOf("新建（使用文件 UID）","跳过")+locals.map { "合并到 ${it.name} · ${it.uid.take(8)}" }).toTypedArray()) { _,choice->
                    fun update(value:WalletCsvImport.Choice) { dialog.dismiss();refreshWallets(document,preview.choices+(source.uid to value)) }
                    if(choice<2)update(WalletCsvImport.Choice(if(choice==0)WalletCsvImport.Action.NEW else WalletCsvImport.Action.SKIP))
                    else { val local=locals[choice-2];val flags=booleanArrayOf(false,false,false)
                        AlertDialog.Builder(activity).setTitle("合并到 ${local.name}").setMultiChoiceItems(arrayOf("更新账户资料（归档需先解除默认钱包）","用文件余额覆盖本地余额","明确确认替换已有来源 UID 别名"),flags) { _,which,checked->flags[which]=checked }
                            .setPositiveButton("重算预览") { _,_->update(WalletCsvImport.Choice(WalletCsvImport.Action.MERGE,local.uid,flags[0],flags[1],flags[2])) }.setNegativeButton("取消",null).show()
                    }
                }.show()
            }.show()
        }
        errorRows(dialog,errors)
        if(preview.entries.isNotEmpty() && preview.unresolved==0)dialog.action("确认导入已预览账户",true) {
            dialog.dismiss()
            activity.uiScope.launch {
                activity.fileProgress("正在提交钱包快照")
                try { val result=withContext(Dispatchers.IO) { val context=currentCoroutineContext();context.ensureActive();remember(preview.plan.submissionToken);WalletCsvImport.apply(store,preview) { context.ensureActive() } }
                    activity.ledger.changed(true);activity.message("新增 ${result.imported} 个钱包，更新 ${result.updated}，保留 ${result.retained}，跳过 ${result.skipped}，错误 ${result.invalid}");done()
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { if(error is WalletException && error.code=="IMPORT_PREVIEW_STALE")refreshWallets(document,preview.choices)
                    activity.message(error.message?.take(100) ?: "钱包导入失败，整批未提交") }
                finally { activity.fileProgress(null) }
            }
        }
        dialog.action("取消") { dialog.dismiss() };dialog.show()
    }
    private fun errorRows(dialog:MintDialog,rows:List<LedgerCsvImport.RowError>) {
        if(rows.isEmpty())return
        dialog.label("以下错误行不会写入")
        rows.take(8).forEach { dialog.content.addView(dialog.text("第 ${it.row} 行：${it.message}",12,MintDialog.MUTED)) }
        if(rows.size>8)dialog.content.addView(dialog.text("另有 ${rows.size-8} 行，完整原因可导出",12,MintDialog.MUTED))
        dialog.action("导出错误清单（仅行号和原因）") { exportErrors() }
    }
    fun onResult(request:Int,result:Int,data:Intent?):Boolean {
        val handled=request in setOf(MainActivity.CSV_IMPORT_REQUEST,MainActivity.CSV_GENERAL_REQUEST,MainActivity.CSV_QIANJI_REQUEST,WALLET_IMPORT,WALLET_EXPORT,BILL_TEMPLATE,WALLET_TEMPLATE,ERROR_EXPORT)
        if(!handled)return false
        if(result!=Activity.RESULT_OK)return true
        val uri=data?.data ?: return true
        if(request==MainActivity.CSV_IMPORT_REQUEST || request==WALLET_IMPORT) {
            errors=emptyList();generation++
            activity.uiScope.launch {
                activity.fileProgress("正在严格检查 CSV 文件")
                try {
                    if(request==MainActivity.CSV_IMPORT_REQUEST) { val document=withContext(Dispatchers.IO) { activity.contentResolver.openInputStream(uri)!!.use(LedgerCsvImport::read) };refreshBills(document,BillImportOptions()) }
                    else { val document=withContext(Dispatchers.IO) { activity.contentResolver.openInputStream(uri)!!.use(WalletCsvImport::read) };refreshWallets(document) }
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { activity.message(error.message?.take(100) ?: "无法读取文件，请选择支持的 CSV") }
                finally { activity.fileProgress(null) }
            }
        } else activity.uiScope.launch {
            activity.fileProgress("正在生成一致快照文件")
            try {
                val message=withContext(Dispatchers.IO) {
                    val temporary=java.io.File.createTempFile("ledger-file-",".csv",activity.cacheDir)
                    try {
                        var count=0;var pending=0
                        temporary.outputStream().use { output->when(request) {
                            WALLET_EXPORT->count=WalletCsvExport.write(store,output)
                            BILL_TEMPLATE->LedgerCsvExport.template(output)
                            WALLET_TEMPLATE->WalletCsvExport.template(output)
                            ERROR_EXPORT->LedgerCsvImport.writeErrors(errors,output)
                            else->{val snapshot=store.exportFileSnapshot();pending=snapshot.pending
                                count=LedgerCsvExport.write(snapshot.records,if(request==MainActivity.CSV_QIANJI_REQUEST)LedgerCsvExport.Format.QIANJI else LedgerCsvExport.Format.GENERAL,output,snapshot.accounts)}
                        } }
                        ensureActive();(activity.contentResolver.openOutputStream(uri,"wt") ?: error("无法写入文件")).use { out->temporary.inputStream().use { it.copyTo(out) } }
                        when(request) { WALLET_EXPORT->"已导出 $count 个钱包（包含归档账户）";BILL_TEMPLATE,WALLET_TEMPLATE->"合成 CSV 模板已导出";ERROR_EXPORT->"错误清单已导出";else->"已导出 $count 笔正式账单；$pending 笔待确认未纳入（${if(request==MainActivity.CSV_QIANJI_REQUEST)"钱迹 V10" else "通用格式 v2"}）" }
                    } finally { temporary.delete() }
                };activity.message(message)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) { activity.message(error.message?.take(100) ?: "导出失败，请检查位置或格式") }
            finally { activity.fileProgress(null) }
        }
        return true
    }
    companion object { private const val WALLET_IMPORT=17;private const val WALLET_EXPORT=18;private const val BILL_TEMPLATE=19;private const val WALLET_TEMPLATE=20;private const val ERROR_EXPORT=21 }
}
