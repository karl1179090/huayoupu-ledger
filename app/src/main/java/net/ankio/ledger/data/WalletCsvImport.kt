package net.ankio.ledger.data

import net.ankio.ledger.core.Money
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

object WalletCsvImport {
    enum class Action { NEW,MERGE,SKIP }
    data class Row(val row:Int,val account:LedgerAccount)
    data class Document(val rows:List<Row>,val errors:List<LedgerCsvImport.RowError>,val total:Int,val fileSha256:String,val duplicates:Int=0)
    data class Choice(val action:Action,val localAccountUid:String?=null,val updateMetadata:Boolean=false,val overwriteBalance:Boolean=false,val replaceAlias:Boolean=false)
    data class Entry(val source:LedgerAccount,val localAccountUid:String,val action:Action,val updateMetadata:Boolean,val overwriteBalance:Boolean,val replaceAlias:Boolean=false)
    data class AccountPreview(val sourceUid:String,val name:String,val localAccountUid:String?,val localBalance:Long?,val fileBalance:Long,
        val finalBalance:Long?,val action:Action?,val suggestions:List<String>,val requiresChoice:Boolean,val metadataChanged:Boolean=false)
    data class Preview(val plan:ImportPlan,val entries:List<Entry>,val accounts:List<AccountPreview>,val unresolved:Int,val total:Int,val choices:Map<String,Choice>)
    data class Result(val imported:Int,val updated:Int,val retained:Int,val skipped:Int,val invalid:Int,val accountUids:List<String>,val batchUid:String) {
        fun json()=JSONObject().put("imported",imported).put("updated",updated).put("retained",retained).put("skipped",skipped).put("invalid",invalid)
            .put("accountUids",JSONArray(accountUids)).put("batchUid",batchUid).toString()
        companion object { fun decode(value:String):Result { val j=JSONObject(value);val u=j.getJSONArray("accountUids")
            return Result(j.getInt("imported"),j.getInt("updated"),j.getInt("retained"),j.getInt("skipped"),j.getInt("invalid"),(0 until u.length()).map(u::getString),j.getString("batchUid")) } }
    }
    fun read(input:InputStream):Document {
        val (rows,sha)=PortableCsv.read(input);val header=rows.first()
        if(header.toSet()==LedgerCsvExport.generalV1.toSet() || header.toSet()==LedgerCsvExport.generalV2.toSet() || header.toSet()==LedgerCsvExport.qianji.toSet())
            throw IllegalArgumentException("这是账单文件，请使用账单导入入口")
        require(header.size==WalletCsvExport.header.size && header.toSet()==WalletCsvExport.header.toSet()) { "请选择花有谱钱包 CSV v1" }
        require(rows.drop(1).all { header.indexOf("格式版本") in it.indices && PortableCsv.unescape(it[header.indexOf("格式版本")])=="1" }) { "钱包格式版本不支持或混用了版本" }
        val parsed=mutableListOf<Row>();val errors=mutableListOf<LedgerCsvImport.RowError>()
        rows.drop(1).forEachIndexed { index,row->try {
            require(row.size==header.size) { "列数与表头不一致" };val f=header.zip(row).associate { it.first to PortableCsv.unescape(it.second) }
            require(f.values.all { it.length<=1024 }) { "字段过长" };require(f.getValue("币种")=="CNY") { "仅支持 CNY" }
            val type=when(f.getValue("账户类型")) { "资产"->AccountType.ASSET;"负债"->AccountType.LIABILITY;else->throw IllegalArgumentException("账户类型无效") }
            val archived=when(f.getValue("是否归档")) { "是"->true;"否"->false;else->throw IllegalArgumentException("是否归档须为是或否") }
            PortableCsv.instant(f.getValue("导出时间"))
            val uid=f.getValue("钱包UID").ifBlank { null }?.let(PortableCsv::uuid) ?: UUID.randomUUID().toString()
            val account=LedgerAccount(uid=uid,name=f.getValue("名称"),description=f.getValue("说明"),amountCents=PortableCsv.cents(f.getValue("余额"),signed=true),
                type=type,kind=f.getValue("钱包类型"),bankCode=f.getValue("银行代码").ifBlank { null },cardLast4=f.getValue("银行卡尾号").ifBlank { null },archived=archived)
            account.validate();parsed.add(Row(index+2,account))
        } catch(error:Exception) { errors.add(LedgerCsvImport.RowError(index+2,error.message ?: "账户字段无效")) } }
        var duplicates=0;val valid=mutableListOf<Row>()
        parsed.groupBy { it.account.uid }.values.forEach { same->
            if(same.map { it.account }.distinct().size==1) { valid.add(same.first());duplicates+=same.size-1 }
            else same.forEach { errors.add(LedgerCsvImport.RowError(it.row,"同钱包 UID 的余额或资料冲突，请解决后重试")) }
        }
        return Document(valid,errors.sortedBy { it.row },rows.size-1,sha,duplicates)
    }
    fun prepare(store:LedgerStore,document:Document,choices:Map<String,Choice> = emptyMap(),submissionToken:String=UUID.randomUUID().toString()):Preview=store.importReadSnapshot {
        val locals=store.accounts().associateBy { it.uid };val aliases=store.walletImportAliases();val entries=mutableListOf<Entry>();val previews=mutableListOf<AccountPreview>()
        val byName=locals.values.groupBy { it.name };val byCard=locals.values.filter { it.bankCode!=null && it.cardLast4!=null }.groupBy { it.bankCode to it.cardLast4 }
        val rowNumbers=document.rows.associate { it.account.uid to it.row }
        val errors=document.errors.toMutableList();var unresolved=0;var skipped=document.duplicates
        document.rows.forEach { row->val source=row.account;try {
            val direct=locals[source.uid];val alias=aliases[source.uid]?.let(locals::get);val match=direct ?: alias
            val suggestions=(byName[source.name].orEmpty()+byCard[source.bankCode to source.cardLast4].orEmpty()).distinctBy { it.uid }.map { it.uid }
            val explicit=choices[source.uid]
            val choice=explicit ?: if(match!=null)Choice(Action.MERGE,match.uid) else if(suggestions.isEmpty())Choice(Action.NEW) else null
            if(choice==null) {
                unresolved++;previews.add(AccountPreview(source.uid,source.name,null,null,source.amountCents,null,null,suggestions,true));return@forEach
            }
            if(choice.action==Action.SKIP) { skipped++;previews.add(AccountPreview(source.uid,source.name,match?.uid,match?.amountCents,source.amountCents,match?.amountCents,Action.SKIP,suggestions,false));return@forEach }
            val local=if(choice.action==Action.MERGE)locals[choice.localAccountUid ?: match?.uid] else null
            require(choice.action!=Action.MERGE || local!=null) { "请选择合并到的钱包" }
            require(choice.action!=Action.NEW || direct==null && alias==null) { "同 UID 或已映射钱包必须合并，不能重建绕过冲突" }
            require(direct==null || local?.uid==direct.uid) { "同 UID 钱包必须先解决本地资料冲突" }
            require(local==null || local.type==source.type) { "同 UID／映射账户类型冲突，请先在钱包中处理" }
            require(alias==null || local?.uid==alias.uid || choice.replaceAlias) { "来源钱包已有不同别名，请明确确认替换" }
            if(local!=null && choice.updateMetadata && !local.archived && source.archived) {
                val settings=store.walletSettings()
                require(settings.defaultAccountUid!=local.uid) { "请先解除默认钱包，再导入归档资料" }
                require(local.uid !in settings.appBindings.values) { "请先解除应用钱包绑定，再导入归档资料" }
            }
            val final=if(local==null || choice.overwriteBalance)source.amountCents else local.amountCents
            val target=local?.uid ?: source.uid
            entries.add(Entry(source,target,choice.action,choice.updateMetadata,choice.overwriteBalance,choice.replaceAlias))
            previews.add(AccountPreview(source.uid,source.name,local?.uid,local?.amountCents,source.amountCents,final,choice.action,suggestions,false,choice.updateMetadata))
        } catch(error:IllegalArgumentException) { errors.add(LedgerCsvImport.RowError(row.row,error.message ?: "钱包映射冲突")) } }
        // Different sources may merge only when their absolute snapshot and requested metadata agree.
        val conflicts=entries.groupBy { it.localAccountUid }.filterValues { grouped->
            grouped.map { entry->listOf(entry.source.amountCents,entry.source.type,entry.action,entry.updateMetadata,entry.overwriteBalance,
                entry.source.name.takeIf { entry.updateMetadata },entry.source.description.takeIf { entry.updateMetadata },entry.source.kind.takeIf { entry.updateMetadata },
                entry.source.bankCode.takeIf { entry.updateMetadata },entry.source.cardLast4.takeIf { entry.updateMetadata },entry.source.archived.takeIf { entry.updateMetadata }) }.distinct().size>1
        }.keys
        if(conflicts.isNotEmpty()) {
            entries.filter { it.localAccountUid in conflicts }.forEach { e->errors.add(LedgerCsvImport.RowError(rowNumbers.getValue(e.source.uid),"多个来源映到同一钱包但快照或资料冲突")) }
            val sources=entries.filter { it.localAccountUid in conflicts }.map { it.source.uid }.toSet()
            entries.removeAll { it.localAccountUid in conflicts }
            for(index in previews.indices)if(previews[index].sourceUid in sources)previews[index]=previews[index].copy(finalBalance=null,action=null,requiresChoice=true)
        }
        val digest=PortableCsv.digest(choices.toSortedMap().entries.joinToString("|") { "${it.key}:${it.value}" }.toByteArray())
        val impacts=entries.distinctBy { it.localAccountUid }.associate { e->e.localAccountUid to if(e.action==Action.NEW)e.source.amountCents else if(e.overwriteBalance)Math.subtractExact(e.source.amountCents,locals.getValue(e.localAccountUid).amountCents) else 0L }
        val plan=ImportPlan("WALLETS","wallet-v1",document.fileSha256,digest,store.importFactsToken(true),submissionToken,errors=errors.sortedBy { it.row },duplicates=skipped,impacts=impacts)
        Preview(plan,entries,previews,unresolved,document.total,choices)
    }
    fun apply(store:LedgerStore,preview:Preview,cancellationCheck:()->Unit={}):Result {
        require(preview.unresolved==0) { "请先处理所有可能重复的钱包" }
        return store.commitWalletImport(preview,cancellationCheck)
    }
}
