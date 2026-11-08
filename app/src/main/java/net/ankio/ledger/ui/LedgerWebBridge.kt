package net.ankio.ledger.ui

import android.webkit.JavascriptInterface
import net.ankio.ledger.BuildConfig
import net.ankio.ledger.capture.ChannelSettings
import net.ankio.ledger.core.Capture
import net.ankio.ledger.core.Direction
import net.ankio.ledger.core.FeeMode
import net.ankio.ledger.core.ExpensePeriod
import net.ankio.ledger.data.RecordTransferDetails
import net.ankio.ledger.data.LedgerStatistics
import net.ankio.ledger.core.Money
import net.ankio.ledger.core.AccountMoney
import net.ankio.ledger.core.BankCodes
import net.ankio.ledger.core.ParsedBill
import net.ankio.ledger.core.ReceiptEvidence
import net.ankio.ledger.core.RecordState
import net.ankio.ledger.core.Source
import net.ankio.ledger.core.PaymentApps
import net.ankio.ledger.data.LedgerRecord
import net.ankio.ledger.data.LedgerAccount
import net.ankio.ledger.data.AccountType
import net.ankio.ledger.data.AccountBanks
import net.ankio.ledger.data.CustomCategory
import net.ankio.ledger.data.CategoryDeletionPreview
import net.ankio.ledger.data.CategoryPreviewStale
import net.ankio.ledger.data.ClassificationCodec
import net.ankio.ledger.data.ClassificationException
import net.ankio.ledger.data.CategoryDirectory
import net.ankio.ledger.data.CategoryException
import net.ankio.ledger.data.LedgerCategory
import net.ankio.ledger.data.LedgerReadView
import net.ankio.ledger.data.CalendarRecordPages
import net.ankio.ledger.data.WalletReadModel
import net.ankio.ledger.data.ReceiptEvidenceCodec
import net.ankio.ledger.data.WalletException
import net.ankio.ledger.data.paymentProvider
import net.ankio.ledger.ledger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlinx.coroutines.launch

/** Exposed only to the packaged ledger page; native storage remains the source of truth. */
class LedgerWebBridge(private val activity: MainActivity) {
    private val app get() = activity.ledger

    @JavascriptInterface
    fun snapshot(): String = synchronized(app.store) { buildSnapshot().toString() }

    /** Production list entry: exactly one bounded page, with a revision for continuation. */
    @JavascriptInterface
    fun snapshotFirst(month:String,search:String,direction:String,known:String):String = synchronized(app.store) {
        val zone=java.time.ZoneId.systemDefault()
        val revision=readRevision(month,search,direction,zone)
        snapshotViewData(month,search,200,direction,known,zone).put("dataRevision",revision).toString()
    }

    /** A mutation invalidates the cursor rather than mixing two different ledger views. */
    @JavascriptInterface
    fun recordPage(month:String,search:String,direction:String,beforeAt:Long,beforeId:Long,expectedRevision:String):String = try {
        synchronized(app.store) {
            val zone=java.time.ZoneId.systemDefault()
            val revision=readRevision(month,search,direction,zone)
            if(expectedRevision!=revision)JSONObject().put("ok",true).put("reset",true).toString()
            else {
                val view=LedgerReadView(app.store,zone).records(month,search,direction=direction,beforeAt=beforeAt,beforeId=beforeId)
                    .put("summary",JSONObject()).put("month",month)
                val result=buildSnapshot(view).put("dataRevision",revision)
                if(readRevision(month,search,direction,java.time.ZoneId.systemDefault())!=revision)JSONObject().put("ok",true).put("reset",true).toString()
                else result.toString()
            }
        }
    } catch(_:CalendarRecordPages.StaleCursor) { JSONObject().put("ok",true).put("reset",true).toString() }
      catch(error:Exception) { failure("更多账单读取失败，请重试",failureCode(error)).toString() }

    @JavascriptInterface
    fun snapshotView(month:String,search:String,limit:Int,direction:String,known:String):String =
        synchronized(app.store) { snapshotViewData(month,search,limit,direction,known).toString() }

    private fun readRevision(month:String,search:String,direction:String,zone:java.time.ZoneId):String =
        "calendar-v3|$READ_SESSION|${app.revision.value}|${zone.id}|${month.length}:$month|${search.length}:$search|${direction.length}:$direction"

    private fun snapshotViewData(month:String,search:String,limit:Int,direction:String,known:String,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):JSONObject = try {
        val read=LedgerReadView(app.store,zone)
        val key="${readRevision(month,search,direction,zone)}|$limit|${System.currentTimeMillis()/30_000}|${app.channels.permitted(ChannelSettings.Channel.OCR)}|${net.ankio.ledger.capture.CaptureRuntime.running}|${net.ankio.ledger.capture.ScreenCaptureService.current!=null}"
        if(known==key)JSONObject().put("ok",true).put("unchanged",true)
        else {
            val view=read.records(month,search,limit=200,direction=direction)
            // Each page is bounded, even when the caller requests more history.
            val pages=limit.coerceIn(200,1_000_000)/200
            for(page in 1 until pages) {
                if(!view.getBoolean("hasMore"))break
                val cursor=view.getJSONObject("nextCursor")
                val next=read.records(month,search,limit=200,direction=direction,beforeAt=cursor.getLong("occurredAt"),beforeId=cursor.getLong("id"))
                val rows=next.getJSONArray("records");repeat(rows.length()){view.getJSONArray("records").put(rows.getJSONObject(it))}
                view.put("hasMore",next.getBoolean("hasMore"))
                view.put("nextCursor",next.opt("nextCursor") ?: JSONObject.NULL)
            }
            view.put("summary",read.summary(month)).put("month",month)
            buildSnapshot(view).put("viewKey",key)
        }
    } catch(error:Exception) { failure("账本读取失败，请重试",failureCode(error)) }

    private fun buildSnapshot(view:JSONObject?=null):JSONObject = try {
        val records = JSONArray()
        val requestedIds=view?.getJSONArray("records")?.let { rows -> (0 until rows.length()).mapTo(mutableSetOf()) { rows.getJSONObject(it).getLong("id") } }
        val availableOwners = if(requestedIds==null)app.screenshots.availableOwners() else app.store.availableScreenshotOwners(requestedIds)
        val screenshotIds = app.store.screenshotIds(availableOwners,requestedIds)
        val historyRevisions = app.store.historyRevisions(availableOwners,requestedIds)
        val merchantIconKeys = if(view==null)app.store.merchantIconKeys() else emptyMap()
        val merchantIconRevisions=app.store.merchantIconRevisions(requestedIds)
        val range=view?.optString("month")?.let { LedgerReadView(app.store).window(it) }
        val tasks=app.tasks.snapshot(200,range?.start,range?.end)
        val pageRecordIds=mutableSetOf<Long>()
        val iconStatuses=mutableMapOf<Long,String>()
        val iconDetails=mutableMapOf<Long,String>()
        val taskMetadata=if(requestedIds==null)tasks else app.tasks.metadataForRecords(requestedIds)
        for(index in 0 until taskMetadata.length()) {
            val task=taskMetadata.getJSONObject(index)
            val id=task.optLong("recordId",0)
            if(id>0 && (task.optString("origin").startsWith("payment_auto:") || task.optJSONObject("diagnostic")?.optString("trigger").orEmpty().let { it.startsWith("page_auto") || it.startsWith("wechat_auto") || it.startsWith("payment_auto") }))pageRecordIds.add(id)
            val status=task.optJSONObject("diagnostic")?.optString("icon").orEmpty()
            if(id>0 && status.isNotBlank()) {
                iconStatuses.putIfAbsent(id,status)
                iconDetails.putIfAbsent(id,task.optJSONObject("diagnostic")?.optString("icon_details").orEmpty())
            }
        }
        if(view!=null) {
            val rows=view.getJSONArray("records")
            for(index in 0 until rows.length()) {
                val row=rows.getJSONObject(index);val id=row.getLong("id")
                row.put("captureMode",if(id in pageRecordIds)"page" else "")
                    .put("hasScreenshot",id in screenshotIds)
                    .put("historyRevision",historyRevisions[id]?:"")
                    .put("merchantIconRevision",merchantIconRevisions[row.optString("merchantIconKey")]?:0)
                    .put("iconDetails",iconDetails[id].orEmpty())
                if(!row.getBoolean("hasMerchantIcon"))row.put("iconStatus",iconStatuses[id].orEmpty())
                records.put(row)
            }
        } else app.store.all().forEach { record ->
            records.put(JSONObject().apply {
                put("id", record.id)
                put("uid", record.uid)
                put("merchant", record.bill.merchant)
                put("receipt", ReceiptEvidenceCodec.json(record.bill.receipt))
                put("category", record.bill.category)
                put("categoryDecision",ClassificationCodec.decision(record.bill.categoryDecision))
                app.store.categoryFor(record).let { category ->
                    put("categoryId", category?.id ?: JSONObject.NULL); put("categoryIcon", category?.icon ?: "tag")
                }
                put("note", record.note)
                put("amountCents", record.bill.amountCents ?: JSONObject.NULL)
                put("direction", record.bill.direction?.name ?: JSONObject.NULL)
                put("occurredAt", record.bill.occurredAt)
                put("state", record.state.name)
                put("source", record.capture.source.name)
                put("paymentProvider", paymentProvider(record.capture) ?: JSONObject.NULL)
                put("captureMode",if(record.id in pageRecordIds)"page" else "")
                put("reason", record.reason)
                put("historyRevision", historyRevisions[record.id] ?: "")
                put("hasScreenshot", record.id in screenshotIds)
                put("hasMerchantIcon", record.id in merchantIconKeys)
                put("merchantIconKey", merchantIconKeys[record.id] ?: JSONObject.NULL)
                put("merchantIconRevision",merchantIconKeys[record.id]?.let { merchantIconRevisions[it] }?:0)
                put("iconStatus",if(record.id in merchantIconKeys)"cached" else iconStatuses[record.id].orEmpty())
                put("iconDetails",iconDetails[record.id].orEmpty())
                put("duplicateId", record.duplicateId ?: JSONObject.NULL)
                put("transfer",WalletReadModel.transfer(app.store,record));put("walletEffects",JSONObject(record.walletEffects));put("wallet", WalletReadModel.summary(app.store,record))
                put("standaloneRefund",WalletReadModel.standalone(record.bill.standaloneRefund))
                put("refundCandidateCount",if((record.bill.refund!=null || record.bill.standaloneRefund!=null) && record.state==RecordState.REVIEW)
                    app.store.refundCandidates(record.id).size else 0)
                put("refund", record.bill.refund?.let { refund ->
                    JSONObject().put("provider", refund.provider)
                        .put("originalAmountCents", refund.originalAmountCents)
                        .put("refundedCents", refund.refundedCents).put("mode", refund.mode)
                        .put("refundId", refund.refundId ?: JSONObject.NULL)
                } ?: JSONObject.NULL)
            })
        }
        val channels = JSONObject()
        listOf(ChannelSettings.Channel.OCR).forEach { channel ->
            val state = JSONObject()
                .put("enabled", app.channels.enabled(channel))
                .put("permitted", app.channels.permitted(channel))
            channels.put(channel.key, state)
        }
        val accounts = JSONArray()
        app.store.accounts().forEach { account ->
            accounts.put(JSONObject().put("id", account.id).put("uid", account.uid)
                .put("name", account.name).put("description", account.description)
                .put("amountCents", account.amountCents).put("kind", account.kind).put("type", account.type.name)
                .put("bankCode",account.bankCode?:JSONObject.NULL).put("cardLast4",account.cardLast4?:JSONObject.NULL)
                .put("archived",account.archived).put("revision",account.revision))
        }
        val custom = JSONObject()
        val categories = app.store.customCategories()
        listOf(Direction.EXPENSE,Direction.INCOME).forEach { direction ->
            val names = JSONArray()
            categories.filter { it.direction == direction }.forEach { names.put(it.name) }
            custom.put(if (direction == Direction.EXPENSE) "expense" else "income", names)
        }
        val applications=JSONArray()
        val selectedPackages=app.channels.selectedAutoPackages()
        PaymentApps.entries.forEach { item ->
            val installed=runCatching { activity.packageManager.getApplicationInfo(item.packageName,0) }.isSuccess
            applications.put(JSONObject().put("packageName",item.packageName).put("label",item.label)
                .put("selected",item.packageName in selectedPackages).put("installed",installed)
                .put("coverage",if(item.packageName=="com.taobao.taobao")"testing" else "adapted"))
        }
        JSONObject().put("ok", true).put("version", BuildConfig.VERSION_NAME)
            .put("ledgerView",view?.let { JSONObject().put("hasMore",it.getBoolean("hasMore")).put("summary",it.getJSONObject("summary"))
                .put("nextCursor",it.opt("nextCursor")?:JSONObject.NULL) }?:JSONObject.NULL)
            .put("pendingDeletion",app.store.pendingDeletion()?.let { JSONObject().put("id",it.id).put("expiresAt",it.expiresAt) }?:JSONObject.NULL)
            .put("records", records).put("recognitionTasks",tasks).put("channels", channels)
            .put("accounts", accounts).put("customCategories", custom)
            .put("categoryDirectory", directoryData()).put("categoryIconOptions", categoryIconOptions())
            .put("classificationRules",ClassificationCodec.snapshot(app.store.classificationRules()))
            .put("walletSettings",walletSettingsData())
            .put("bankCodeOptions",JSONArray().apply { BankCodes.entries.forEach { put(JSONObject().put("code",it.code).put("label",it.label)) } })
            .put("bankOptions",JSONArray().apply { AccountBanks.entries.forEach {
                put(JSONObject().put("kind",it.kind).put("label",it.label).put("icon",it.icon))
            } })
            .put("captureHealth",app.captureHealth.snapshot())
            .put("automationRunning", net.ankio.ledger.capture.CaptureRuntime.running && net.ankio.ledger.capture.ScreenCaptureService.current != null)
            .put("wechatAutoConsented",app.channels.wechatAutoConsented())
            .put("automatic",JSONObject().put("enabled",app.channels.automaticEnabled()).put("applications",applications))
            .put("screenshotStorage",app.screenshots.stats())
            .put("status", app.channels.lastStatus())
    } catch (error: Exception) {
        // An explicit error avoids making a failed read look like an empty ledger.
        failure("账本读取失败，请稍后重试",failureCode(error)).put("version", BuildConfig.VERSION_NAME)
    }

    @JavascriptInterface
    fun recordDetails(id:Long):String = try {
        val record=requireNotNull(app.store.get(id)) { "账单已不存在" }
        val ids=setOf(id);val owners=app.store.availableScreenshotOwners(ids)
        val iconKey=app.store.merchantIconKeys(ids)[id]
        val task=app.tasks.metadataForRecords(ids).optJSONObject(0)
        JSONObject().put("ok",true).put("record",JSONObject().put("id",id).put("uid",record.uid)
            .put("hasScreenshot",app.store.screenshotIds(owners,ids).contains(id)).put("historyRevision",app.store.historyRevisions(owners,ids)[id]?:"")
            .put("hasMerchantIcon",iconKey!=null).put("merchantIconKey",iconKey?:JSONObject.NULL)
            .put("merchantIconRevision",iconKey?.let { app.store.merchantIconAsset(it)?.contentHashCode() }?:0)
            .put("captureMode",if(task?.optString("origin")?.startsWith("payment_auto:")==true)"page" else "")
            .put("refundCandidateCount",if((record.bill.refund!=null||record.bill.standaloneRefund!=null)&&record.state==RecordState.REVIEW)app.store.refundCandidates(id).size else 0)
            .put("merchant",record.bill.merchant).put("category",record.bill.category)
            .put("categoryId",app.store.categoryFor(record)?.id ?: JSONObject.NULL).put("categoryIcon",app.store.categoryFor(record)?.icon ?: "tag")
            .put("receipt",ReceiptEvidenceCodec.json(record.bill.receipt))
            .put("categoryDecision",ClassificationCodec.decision(record.bill.categoryDecision))
            .put("note",record.note).put("amountCents",record.bill.amountCents?:JSONObject.NULL).put("direction",record.bill.direction?.name?:JSONObject.NULL)
            .put("occurredAt",record.bill.occurredAt).put("state",record.state.name).put("source",record.capture.source.name)
            .put("paymentProvider",paymentProvider(record.capture)?:JSONObject.NULL)
            .put("transfer",WalletReadModel.transfer(app.store,record)).put("walletEffects",JSONObject(record.walletEffects)).put("wallet",WalletReadModel.summary(app.store,record)).put("paymentMethod",WalletReadModel.evidence(record.bill.paymentMethod))
            .put("standaloneRefund",WalletReadModel.standalone(record.bill.standaloneRefund))
            .put("refund",record.bill.refund?.let { JSONObject().put("provider",it.provider).put("originalAmountCents",it.originalAmountCents).put("refundedCents",it.refundedCents).put("mode",it.mode).put("refundId",it.refundId?:JSONObject.NULL) }?:JSONObject.NULL))
            .put("rawText",record.capture.text).put("history",JSONArray().apply {
            app.store.captureHistory(id).forEach { item ->
                put(JSONObject().put("sequence",item.sequence).put("at",item.at).put("kind",item.kind)
                    .put("amountCents",item.amountCents?:JSONObject.NULL).put("refundedCents",item.refundedCents)
                    .put("rawText",item.rawText).put("hasScreenshot",item.hasScreenshot)
                    .put("screenshotUrl",if(item.hasScreenshot)"/record-history/$id/${item.sequence}.jpg" else JSONObject.NULL))
            }
        }).toString()
    } catch(error:Exception) { failure("账单详情读取失败，请重试",failureCode(error)).toString() }

    @JavascriptInterface
    fun exportBills(format:String) {
        if(format !in setOf("general","qianji"))return
        onMain { activity.exportBills(format) }
    }

    @JavascriptInterface
    fun importBills()=onMain { activity.importBills() }

    @JavascriptInterface fun importWallets()=onMain { activity.importWallets() }
    @JavascriptInterface fun exportWallets()=onMain { activity.exportWallets() }
    @JavascriptInterface fun exportImportTemplate(kind:String) { if(kind in setOf("bill","wallet"))onMain { activity.exportImportTemplate(kind) } }
    @JavascriptInterface fun exportImportErrors()=onMain { activity.exportImportErrors() }

    @JavascriptInterface fun statistics(period:String):String=try {
        LedgerStatistics(app.store).query(ExpensePeriod.valueOf(period)).json().put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"统计读取失败，请重试").toString() }

    @JavascriptInterface fun contributionRecords(period:String,category:String,direction:String,offset:Int):String=try {
        val range=net.ankio.ledger.core.ExpensePeriodBuckets.create(ExpensePeriod.valueOf(period),java.time.Instant.now(),java.time.ZoneId.systemDefault())
        LedgerReadView(app.store).records(java.time.YearMonth.now().toString(),offset=offset,direction=direction.also { checkInput(it in setOf("EXPENSE","INCOME"),"统计类型无效") },category=category,
            contribution=true,calendarStart=range.startDate,calendarEnd=range.endDate).put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"开销明细读取失败").toString() }

    @JavascriptInterface
    fun categoryRecords(month:String,category:String,direction:String,offset:Int):String = try {
        checkInput(category.length<=1024,"分类无效")
        LedgerReadView(app.store).records(month,offset=offset,direction=direction,category=category,contribution=direction=="EXPENSE").put("ok",true).toString()
    } catch(error:Exception) { failure("明细读取失败",failureCode(error)).toString() }

    @JavascriptInterface
    fun save(json: String): String = try {
        checkInput(json.length <= MAX_INPUT_LENGTH, "账单数据过大")
        val input = JSONObject(json)
        checkInput(!input.has("categoryDecision"),"分类信息无效，请重新打开账单")
        val categoryEdited=if(input.has("categoryEdited"))input.get("categoryEdited") as? Boolean
            ?: throw InvalidInput("分类选择状态无效，请重新选择") else false
        checkInput(!input.has("eligibility") && !input.has("walletEffects") && !input.has("appliedCents"),"不能指定余额联动事实")
        val id = integer(input, "id", 0, MAX_SAFE_INTEGER)
        val taskId=if(input.has("recognitionTaskId")) integer(input,"recognitionTaskId",0,MAX_SAFE_INTEGER) else 0L
        if(taskId>0) checkInput(app.tasks.status(taskId) in setOf("FAILED","NO_BILL","CANCELLED","DUPLICATE"),"请等待识别完成，或重新打开识别记录")
        val amount = string(input, "amount", 32).trim()
        checkInput(DECIMAL.matches(amount), "请输入有效金额，最多两位小数")
        val cents = Money.parseCents(amount)?.takeIf { it >= 0 }
            ?: throw InvalidInput("请输入有效金额，最多两位小数")
        val direction = when (string(input, "direction", 16)) {
            Direction.EXPENSE.name -> Direction.EXPENSE
            Direction.INCOME.name -> Direction.INCOME
            Direction.TRANSFER.name -> Direction.TRANSFER
            else -> throw InvalidInput("请选择支出、收入或转账")
        }
        val enteredMerchant = string(input, "merchant", 1024).trim()
        val categoryName = string(input, "category", 1024).trim().ifBlank { "其他" }
        val requestedOccurredAt = integer(input, "occurredAt", 1, MAX_TIME)
        val timeEdited=if(input.has("timeEdited"))input.get("timeEdited") as? Boolean
            ?: throw InvalidInput("时间修改参数无效") else null
        val note = string(input, "note", 500).trim()
        val confirmed = if (input.has("confirmDuplicate")) {
            input.get("confirmDuplicate") as? Boolean ?: throw InvalidInput("重复确认参数无效")
        } else false
        val result = synchronized(app.store) {
            val category = if (direction == Direction.TRANSFER) "本人互转" else {
                val categoryId = if (input.has("categoryId") && !input.isNull("categoryId")) string(input, "categoryId", 160) else ""
                if (categoryId.isNotBlank()) app.store.category(categoryId, direction).name
                else app.store.categoryDirectory(direction).firstOrNull { it.name == categoryName }?.name
                    ?: throw CategoryException("CATEGORY_NOT_FOUND", "分类已不存在或收支方向不一致，请重新选择")
            }
            val original = if (id == 0L) null else (app.store.get(id)
                ?: throw InvalidInput("账单已不存在，请刷新后重试"))
            // A minute-only editor must not truncate the saved seconds/millis when the user did not edit time.
            val occurredAt=if(original!=null && timeEdited==false)original.bill.occurredAt else requestedOccurredAt
            val merchant = enteredMerchant.ifBlank { if(original?.bill?.receipt!=null) "" else "未填写商户" }
            checkInput(original?.bill?.standaloneRefund==null && (original?.bill?.refund == null || original.state == RecordState.POSTED),
                "退款需先选择原账单，不能另记为一笔支出")
            if(direction==Direction.TRANSFER && (original?.bill?.refund!=null || original?.bill?.standaloneRefund!=null))throw WalletException("TRANSFER_REFUND_CONFLICT","含退款历史的消费不能直接改为转账，请先核对退款记录")
            val refund = original?.bill?.refund
            val validatedRefund = original != null && original.state == RecordState.POSTED && refund != null &&
                refund.originalAmountCents > 0 && refund.refundedCents in 0..refund.originalAmountCents &&
                original.bill.direction == Direction.EXPENSE &&
                original.bill.amountCents == refund.originalAmountCents - refund.refundedCents
            checkInput(cents > 0 || validatedRefund, "请输入大于零的有效金额")
            if (refund != null) {
                val existing = requireNotNull(original)
                checkInput(validatedRefund && cents == existing.bill.amountCents && direction == existing.bill.direction,
                    "退款后的实付金额由识别记录计算，请勿手动更改金额或收支方向")
                checkInput(merchant == existing.bill.merchant && occurredAt == existing.bill.occurredAt,
                    "退款账单保留原商户和交易时间，可修改分类和备注")
            }
            if (original?.duplicateId != null && !confirmed) {
                failure("这笔账单可能重复，请核对后确认保存").put("duplicateRequired", true)
            } else {
                val now = System.currentTimeMillis()
                val base = original ?: LedgerRecord(
                    capture = Capture(Source.MANUAL, "manual", "", now),
                    bill = ParsedBill(null, null, "", "其他", now, null, true, ""),
                    state = RecordState.POSTED)
                val transfer=if(direction==Direction.TRANSFER) {
                    checkInput(refund==null && base.bill.standaloneRefund==null,"含退款历史的消费不能直接改为转账，请先核对退款记录")
                    val data=input.optJSONObject("transfer") ?: throw InvalidInput("请选择转出与转入钱包")
                    checkInput(!data.has("effects") && !data.has("eligibility"),"不能指定余额联动事实")
                    fun uid(key:String):String?=if(data.has(key)&&!data.isNull(key))string(data,key,64).takeIf { it.isNotBlank() }?.also {
                        checkInput(runCatching { java.util.UUID.fromString(it).toString()==it }.getOrDefault(false),"钱包标识无效")
                    } else null
                    if((base.bill.direction==Direction.TRANSFER || base.bill.transfer!=null || base.transfer!=null) && base.transfer?.feeCents==null)checkInput(
                        data.has("fee") && string(data,"fee",32).isNotBlank() || data.has("feeCents") && !data.isNull("feeCents"),"请明确确认手续费，无费用填 0")
                    val fee=if(data.has("fee"))Money.parseCents(string(data,"fee",32).ifBlank { "0" }) ?: throw InvalidInput("手续费需为非负金额，最多两位小数")
                        else if(data.has("feeCents")&&!data.isNull("feeCents"))integer(data,"feeCents",0,Money.MAX_CENTS) else 0L
                    val mode=runCatching { FeeMode.valueOf(string(data,"feeMode",16)) }.getOrElse { throw InvalidInput("请选择手续费方式") }
                    (base.transfer ?: RecordTransferDetails(base.uid)).copy(fromAccountUid=uid("fromAccountUid"),toAccountUid=uid("toAccountUid"),feeCents=fee,feeMode=mode,
                        reviewCode=null)
                } else null
                val saved = app.store.saveEdited(base.copy(transfer=transfer,
                    bill = base.bill.copy(amountCents = cents, direction = direction,
                        merchant = merchant, category = category, occurredAt = occurredAt,
                        reliable = true, reason = "", transfer=null, timeExplicit = if(original!=null && occurredAt==base.bill.occurredAt)base.bill.timeExplicit else true),
                    note = note),
                    manualCategorySelected=categoryEdited,
                    walletAccountUid=if(input.has("walletAccountUid")&&!input.isNull("walletAccountUid"))string(input,"walletAccountUid",64) else null,
                    manualWalletSelected=if(input.has("manualWalletSelected"))input.get("manualWalletSelected") as? Boolean ?: throw InvalidInput("钱包选择参数无效") else false,
                    expectedRevision=if(original!=null)integer(input,"expectedRevision",0,MAX_SAFE_INTEGER) else null,
                    submissionToken=string(input,"submissionToken",160).also { checkInput(it.isNotBlank(),"提交标识无效") })
                if(taskId>0) {
                    app.tasks.image(taskId)?.let { app.store.attachScreenshot(saved.id,it) }
                    app.tasks.update(taskId,"SUCCEEDED","已手动补录账单",saved.id)
                }
                app.changed(true)
                JSONObject().put("ok", true).put("id", saved.id).put("state",saved.state.name)
            }
        }
        result.toString()
    } catch (error: WalletException) {
        structuredFailure(error,"保存失败").toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"账单数据无效").toString()
    } catch (_: JSONException) {
        failure("账单数据格式错误","INVALID_INPUT").toString()
    } catch (_: Exception) {
        failure("保存失败，内容已保留，请重试").toString()
    }

    @JavascriptInterface
    fun refundCandidates(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账单编号无效")
        val candidates = JSONArray()
        app.store.refundCandidates(id).forEach { record ->
            val candidate=JSONObject().put("id", record.id).put("merchant", record.bill.merchant)
                .put("occurredAt", record.bill.occurredAt).put("amountCents", record.bill.amountCents ?: JSONObject.NULL)
                .put("originalAmountCents", record.bill.refund?.originalAmountCents ?: record.bill.amountCents ?: JSONObject.NULL)
            record.bill.receipt?.time?.let { time ->
                val evidence=ReceiptEvidenceCodec.json(ReceiptEvidence(time=time)) as JSONObject
                candidate.put("receipt",JSONObject().put("time",evidence.getJSONObject("time")))
            }
            candidates.put(candidate)
        }
        JSONObject().put("ok", true).put("candidates", candidates).toString()
    } catch (error: Exception) {
        structuredFailure(error,"无法读取原账单，请重试").toString()
    }

    @JavascriptInterface
    fun confirmRefund(pendingId: Long, targetId: Long): String = try {
        checkInput(pendingId in 1..MAX_SAFE_INTEGER && targetId in 1..MAX_SAFE_INTEGER, "账单编号无效")
        val saved = app.store.confirmRefund(pendingId, targetId)
        app.tasks.reassignRecord(pendingId, saved.record.id)
        app.changed(true)
        JSONObject().put("ok", true).put("id", saved.record.id).put("updated", saved.updated).toString()
    } catch (error: Exception) {
        structuredFailure(error,"退款关联失败，请重新核对原账单").toString()
    }

    @JavascriptInterface
    fun delete(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账单编号无效")
        synchronized(app.store) {
            checkInput(app.store.get(id) != null, "账单已不存在，请刷新后重试")
            checkInput(app.store.softDelete(id),"账单已不存在，请刷新后重试")
            app.changed(true)
        }
        val deletion=app.store.pendingDeletion()
        app.scope.launch {
            kotlinx.coroutines.delay(net.ankio.ledger.data.LedgerStore.UNDO_WINDOW_MS+150)
            app.store.purgeExpiredDeletes().forEach(app.tasks::deleteForRecord)
            app.changed(true)
        }
        JSONObject().put("ok", true).put("id", id).put("expiresAt",deletion?.expiresAt?:0).toString()
    } catch (error: WalletException) {
        structuredFailure(error,"删除失败，请稍后重试").toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"账单编号无效").toString()
    } catch (_: Exception) {
        failure("删除失败，请稍后重试").toString()
    }

    @JavascriptInterface
    fun undoDelete(id:Long):String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER,"账单编号无效")
        checkInput(app.store.undoDelete(id),"撤销时间已过")
        app.changed(true);JSONObject().put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"撤销失败，请重试").toString() }

    @JavascriptInterface fun mutateRecord(json:String):String=try {
        checkInput(json.length<=2048,"操作参数过大")
        val input=JSONObject(json);val id=integer(input,"id",1,MAX_SAFE_INTEGER)
        val token=string(input,"submissionToken",160).also { checkInput(it.isNotBlank(),"提交标识无效") }
        val revision=if(input.has("expectedRevision"))integer(input,"expectedRevision",0,MAX_SAFE_INTEGER) else null
        val action=string(input,"action",16)
        val result=synchronized(app.store) {
            val changed=when(action) {
                "DELETE"->app.store.softDelete(id,expectedRevision=revision,submissionToken=token)
                "UNDO"->app.store.undoDelete(id,expectedRevision=revision,submissionToken=token)
                else->throw InvalidInput("账单操作无效")
            }
            checkInput(changed,if(action=="DELETE")"账单已不存在" else "撤销时间已过")
            app.changed(true)
            JSONObject().put("ok",true).put("id",id).put("expiresAt",app.store.pendingDeletion()?.expiresAt ?: 0)
                .put("expectedRevision",revision?.let { it+1 } ?: JSONObject.NULL)
        }
        if(action=="DELETE")app.scope.launch {
            kotlinx.coroutines.delay(net.ankio.ledger.data.LedgerStore.UNDO_WINDOW_MS+150)
            app.store.purgeExpiredDeletes().forEach(app.tasks::deleteForRecord);app.changed(true)
        }
        result.toString()
    } catch(error:Exception) { structuredFailure(error,"账单操作失败，请重试").toString() }

    @JavascriptInterface
    fun deleteTask(id:Long):String=try {
        checkInput(id in 1..MAX_SAFE_INTEGER,"识别记录编号无效")
        checkInput(app.tasks.exists(id),"识别记录已不存在")
        checkInput(app.tasks.status(id) !in setOf("CAPTURING","RECOGNIZING"),"正在识别，请稍后再删除")
        app.tasks.delete(id);app.changed();JSONObject().put("ok",true).toString()
    } catch(error:Exception) { failure((error as? IllegalArgumentException)?.message?:"删除失败，请重试",failureCode(error)).toString() }

    @JavascriptInterface
    fun saveAccount(json: String): String = try {
        checkInput(json.length <= MAX_INPUT_LENGTH, "账户数据过大")
        val input = JSONObject(json)
        val id = integer(input, "id", 0, MAX_SAFE_INTEGER)
        val enteredName = string(input, "name", 40).trim()
        val description = if (input.has("description")) string(input, "description", 80).trim() else ""
        val amount = string(input, "amount", 32).trim()
        checkInput(amount.isNotEmpty(), "请输入余额，可填 0")
        val cents = AccountMoney.parseCents(amount) ?: throw InvalidInput("账户余额无效或过大，最多两位小数")
        val kind = string(input, "kind", 16)
        checkInput(kind in LedgerAccount.KINDS, "账户图标类型无效")
        val name=enteredName.ifBlank { AccountBanks.entries.firstOrNull { it.kind==kind }?.label ?: when(kind) {
            "bank"->"银行卡";"alipay"->"支付宝";"wechat"->"微信";"cash"->"现金";else->"其他账户"
        } }
        val type = when (string(input, "type", 16)) {
            "ASSET" -> AccountType.ASSET
            "LIABILITY" -> AccountType.LIABILITY
            else -> throw InvalidInput("请选择资产或负债")
        }
        checkInput(type==AccountType.ASSET || cents>=0,"负债余额不能为负数")
        val bankCode=if(input.has("bankCode")&&!input.isNull("bankCode"))string(input,"bankCode",32).trim().ifBlank { null } else null
        val cardLast4=if(input.has("cardLast4")&&!input.isNull("cardLast4"))string(input,"cardLast4",4).trim().ifBlank { null } else null
        checkInput(bankCode==null || Regex("[a-z][a-z0-9_]{1,31}").matches(bankCode),"请选择有效银行")
        checkInput(cardLast4==null || Regex("[0-9]{4}").matches(cardLast4),"银行卡尾号需为四位数字")
        val saved = synchronized(app.store) {
            val original = if (id == 0L) null else (app.store.getAccount(id)
                ?: throw InvalidInput("账户已不存在，请刷新后重试"))
            app.store.saveAccount((original ?: LedgerAccount(name = name, amountCents = cents)).copy(
                name = name, description = description, amountCents = cents, kind = kind, type = type,
                bankCode=if(input.has("bankCode"))bankCode else original?.bankCode ?: AccountBanks.codeForKind(kind),
                cardLast4=if(input.has("cardLast4"))cardLast4 else original?.cardLast4),
                calibrateBalance=if(input.has("calibrateBalance"))input.get("calibrateBalance") as? Boolean ?: throw InvalidInput("余额校准参数无效") else false,
                expectedRevision=if(input.has("expectedRevision"))integer(input,"expectedRevision",0,MAX_SAFE_INTEGER) else original?.revision,
                submissionToken=if(input.has("submissionToken"))string(input,"submissionToken",160) else null)
        }
        app.changed()
        JSONObject().put("ok", true).put("id", saved.id).toString()
    } catch (error: WalletException) {
        failure(error.message ?: "账户保存失败").put("code",error.code).toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"账户数据无效").toString()
    } catch (_: JSONException) {
        failure("账户数据格式错误","INVALID_INPUT").toString()
    } catch (_: Exception) {
        failure("账户保存失败，内容已保留，请重试").toString()
    }

    @JavascriptInterface
    fun deleteAccount(id: Long): String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER, "账户编号无效")
        synchronized(app.store) {
            checkInput(app.store.getAccount(id) != null, "账户已不存在，请刷新后重试")
            app.store.deleteAccount(id)
        }
        app.changed()
        JSONObject().put("ok", true).put("id", id).toString()
    } catch (error: WalletException) {
        structuredFailure(error,"账户删除失败，请稍后重试").toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"账户编号无效").toString()
    } catch (_: Exception) {
        failure("账户删除失败，请稍后重试").toString()
    }

    private fun walletSettingsData():JSONObject = app.store.walletSettings().let { settings ->
        JSONObject().put("defaultAccountUid",settings.defaultAccountUid?:JSONObject.NULL)
            .put("appBindings",JSONObject(settings.appBindings)).put("revision",settings.revision)
    }
    @JavascriptInterface fun getWalletSettings():String = try {
        walletSettingsData().put("ok",true).toString()
    } catch(error:Exception) { failure("钱包设置读取失败，请重试",failureCode(error)).toString() }
    @JavascriptInterface fun setDefaultWallet(uid:String):String = try {
        app.store.setDefaultWallet(uid.trim().ifBlank { null });app.changed()
        walletSettingsData().put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"默认钱包保存失败").toString() }
    @JavascriptInterface fun setAppWallet(packageName:String,uid:String):String = try {
        app.store.setAppWallet(packageName,uid.trim().ifBlank { null });app.changed()
        walletSettingsData().put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"应用钱包保存失败").toString() }
    @JavascriptInterface fun archiveAccount(id:Long):String = try {
        checkInput(id in 1..MAX_SAFE_INTEGER,"账户编号无效")
        app.store.archiveAccount(id);app.changed();JSONObject().put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"账户归档失败").toString() }
    @JavascriptInterface fun walletMovements(accountUid:String,offset:Int,limit:Int):String = try {
        checkInput(accountUid.length<=64 && offset>=0 && limit in 1..100,"余额记录查询无效")
        val rows=app.store.walletMovements(accountUid,limit+1,offset)
        JSONObject().put("ok",true).put("hasMore",rows.size>limit).put("movements",JSONArray().apply {
            rows.take(limit).forEach { item -> put(JSONObject().put("uid",item.movementUid).put("kind",item.kind)
                .put("deltaCents",item.deltaCents).put("balanceBefore",item.balanceBefore).put("balanceAfter",item.balanceAfter)
                .put("createdAt",item.createdAt).put("summary",item.summary).put("recordUid",item.recordUid?:JSONObject.NULL)) }
        }).toString()
    } catch(error:Exception) { structuredFailure(error,"余额记录读取失败").toString() }
    @JavascriptInterface fun listInstalledApplications(search:String,offset:Int,limit:Int,refresh:Boolean):String = try {
        checkInput(search.length<=200 && offset>=0 && limit in 1..100,"应用查询无效")
        net.ankio.ledger.capture.InstalledAppsRepository.get(activity).page(search,offset,limit,refresh).put("ok",true).toString()
    } catch(error:Exception) { structuredFailure(error,"应用列表读取失败").toString() }
    @JavascriptInterface fun setShakeApplication(packageName:String,enabled:Boolean):String = try {
        app.channels.setShakePackage(packageName,enabled)
        net.ankio.ledger.capture.CaptureRuntime.refreshHealth();app.changed()
        JSONObject().put("ok",true).put("selectedCount",app.channels.selectedShakePackages().size)
            .put("generation",app.channels.shakeMasterGeneration()).put("captureHealth",app.captureHealth.snapshot()).toString()
    } catch(error:Exception) { structuredFailure(error,"应用选择保存失败").toString() }

    private fun classificationResponse()=JSONObject().put("ok",true).put("classificationRules",ClassificationCodec.snapshot(app.store.classificationRules()))
    private fun ruleFailure(error:Exception)=failure(error.message ?: "分类规则操作失败，请重试",(error as? ClassificationException)?.code ?: "RULE_INVALID").toString()
    @JavascriptInterface fun classificationRules():String=try {
        synchronized(app.store) { classificationResponse().toString() }
    } catch(error:Exception) { ruleFailure(error) }
    @JavascriptInterface fun saveClassificationRule(json:String):String=try {
        checkInput(json.length<=MAX_INPUT_LENGTH,"规则数据过长")
        val rule=ClassificationCodec.readRule(JSONObject(json))
        synchronized(app.store) { app.store.saveClassificationRule(rule);app.changed();classificationResponse().toString() }
    } catch(error:Exception) { ruleFailure(error) }
    @JavascriptInterface fun setBuiltinClassificationRuleEnabled(json:String):String=try {
        checkInput(json.length<=MAX_INPUT_LENGTH,"规则数据过长")
        val input=JSONObject(json)
        val id=(input.get("id") as? String)?.takeIf { it.length in 1..160 }
            ?: throw ClassificationException("RULE_INVALID","内置规则标识无效")
        val enabled=input.get("enabled") as? Boolean ?: throw ClassificationException("RULE_INVALID","规则启停状态无效")
        synchronized(app.store) { app.store.setBuiltinClassificationRuleEnabled(id,enabled);app.changed();classificationResponse().toString() }
    } catch(error:Exception) { ruleFailure(error) }
    @JavascriptInterface fun copyBuiltinClassificationRule(id:String):String=try {
        val draft=app.store.copyBuiltinClassificationRule(id)
        JSONObject().put("ok",true).put("rule",ClassificationCodec.rule(draft).put("id",JSONObject.NULL)).toString()
    } catch(error:Exception) { ruleFailure(error) }
    @JavascriptInterface fun deleteClassificationRule(id:String):String=try {
        synchronized(app.store) { app.store.deleteClassificationRule(id);app.changed();classificationResponse().toString() }
    } catch(error:Exception) { ruleFailure(error) }
    @JavascriptInterface fun reorderClassificationRules(direction:String,idsJson:String):String=try {
        checkInput(idsJson.length<=200_000,"规则顺序过长")
        val entries=JSONArray(idsJson);checkInput(entries.length()<=2_000,"规则数量过多")
        val ids=(0 until entries.length()).map { entries.get(it) as? String ?: throw ClassificationException("RULE_INVALID","规则顺序无效") }
        synchronized(app.store) { app.store.reorderClassificationRules(categoryDirection(direction),ids);app.changed();classificationResponse().toString() }
    } catch(error:Exception) { ruleFailure(error) }

    private fun categoryJson(category: LedgerCategory) = JSONObject().put("id", category.id).put("name", category.name)
        .put("direction", category.direction.name).put("icon", category.icon).put("order", category.order)
        .put("isFallback", category.isFallback).put("referenceCount", category.referenceCount).put("canDelete", category.canDelete)

    private fun directoryData(): JSONObject {
        val categories = app.store.categoryDirectory()
        return JSONObject().put("expense", JSONArray().apply { categories.filter { it.direction == Direction.EXPENSE }.forEach { put(categoryJson(it)) } })
            .put("income", JSONArray().apply { categories.filter { it.direction == Direction.INCOME }.forEach { put(categoryJson(it)) } })
    }
    private fun categoryIconOptions() = JSONArray().apply { CategoryDirectory.icons.forEach { (key, label) -> put(JSONObject().put("key", key).put("label", label)) } }
    private fun directoryResponse() = JSONObject().put("ok", true).put("categoryDirectory", directoryData()).put("categoryIconOptions", categoryIconOptions())
    private fun categoryDirection(value: String): Direction = when (value) {
        "EXPENSE", "expense" -> Direction.EXPENSE
        "INCOME", "income" -> Direction.INCOME
        else -> throw CategoryException("CATEGORY_DIRECTION", "分类仅支持收入或支出")
    }
    private fun categoryFailure(error: Exception): String = if (error is CategoryException) structuredFailure(error, "分类操作失败").toString()
        else failure(error.message ?: "分类操作失败，请重试", "CATEGORY_INVALID").toString()

    @JavascriptInterface fun getCategoryDirectory(): String = try {
        synchronized(app.store) { directoryResponse().toString() }
    } catch (error: Exception) { categoryFailure(error) }

    @JavascriptInterface fun saveCategory(json: String): String = try {
        checkInput(json.length <= MAX_INPUT_LENGTH, "分类数据过长")
        val input = JSONObject(json)
        val id = if (input.has("id") && !input.isNull("id")) string(input, "id", 160).takeIf { it.isNotBlank() } else null
        val name = string(input, "name", 40).trim()
        val direction = categoryDirection(string(input, "direction", 16))
        val icon = if (input.has("icon")) string(input, "icon", 40) else "tag"
        synchronized(app.store) {
            val category = app.store.saveCategory(id, name, direction, icon)
            app.changed(); directoryResponse().put("category", categoryJson(category)).toString()
        }
    } catch (error: Exception) { categoryFailure(error) }

    @JavascriptInterface fun reorderCategories(direction: String, idsJson: String): String = try {
        checkInput(idsJson.length <= 200_000, "分类顺序过长")
        val input = JSONArray(idsJson)
        checkInput(input.length() <= 2_000, "分类数量过多")
        val ids = (0 until input.length()).map { index -> (input.get(index) as? String)?.takeIf { it.length <= 160 }
            ?: throw CategoryException("CATEGORY_INVALID", "分类顺序无效") }
        synchronized(app.store) { app.store.reorderCategories(categoryDirection(direction), ids); app.changed(); directoryResponse().toString() }
    } catch (error: Exception) { categoryFailure(error) }

    private fun deletionPreviewJson(value:CategoryDeletionPreview)=JSONObject().put("category",categoryJson(value.category))
        .put("billCount",value.billCount).put("ruleCount",value.ruleCount).put("queuedConfigurationCount",value.queuedConfigurationCount)
        .put("replacementCount",value.replacementCount).put("requiresReplacement",value.requiresReplacement).put("previewToken",value.previewToken)
        .put("replacements",JSONArray().apply { value.replacements.forEach { put(categoryJson(it)) } })

    @JavascriptInterface fun previewCategoryDeletion(id:String):String=try {
        synchronized(app.store) { JSONObject().put("ok",true).put("deletionPreview",deletionPreviewJson(app.store.previewCategoryDeletion(id))).toString() }
    } catch(error:Exception) { categoryFailure(error) }

    @JavascriptInterface fun confirmCategoryDeletion(json:String):String=try {
        if(json.length>MAX_INPUT_LENGTH)throw CategoryException("CATEGORY_INVALID","分类删除数据无效")
        val input=JSONObject(json)
        fun text(key:String)=((input.get(key) as? String)?.takeIf { it.length in 1..160 })
            ?: throw CategoryException("CATEGORY_INVALID","分类删除数据无效")
        val id=text("id");val token=text("previewToken")
        val replacement=if(input.isNull("replacementId"))null else text("replacementId")
        synchronized(app.store) {
            app.store.confirmCategoryDeletion(id,replacement,token);app.changed()
            directoryResponse().put("classificationRules",ClassificationCodec.snapshot(app.store.classificationRules())).toString()
        }
    } catch(error:CategoryPreviewStale) {
        failure(error.message ?: "分类引用已变化，请核对最新影响后再确认删除","CATEGORY_PREVIEW_STALE")
            .put("deletionPreview",deletionPreviewJson(error.preview)).toString()
    } catch(error:CategoryException) { categoryFailure(error) }
    catch(_:org.json.JSONException) { failure("分类删除数据无效","CATEGORY_INVALID").toString() }
    catch(_:Exception) { failure("分类删除失败，所有更改均已回滚","CATEGORY_DELETE_FAILED").toString() }

    @JavascriptInterface fun removeCategory(id: String): String = try {
        synchronized(app.store) { app.store.removeCategory(id); app.changed(); directoryResponse().toString() }
    } catch (error: Exception) { categoryFailure(error) }

    @JavascriptInterface
    fun addCategory(name: String, direction: String): String = try {
        checkInput(name.trim()!="转账手续费","转账手续费为系统分类，请使用其他分类名称")
        val category = categoryInput(name, direction)
        val added = app.store.addCategory(category)
        app.changed()
        JSONObject().put("ok", true).put("added", added).toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"分类数据无效").toString()
    } catch (_: Exception) {
        failure("分类保存失败，请重试").toString()
    }

    @JavascriptInterface
    fun deleteCategory(name: String, direction: String): String = try {
        app.store.deleteCategory(categoryInput(name, direction))
        app.changed()
        JSONObject().put("ok", true).toString()
    } catch (error: IllegalArgumentException) {
        structuredFailure(error,"分类数据无效").toString()
    } catch (_: Exception) {
        failure("分类删除失败，请重试").toString()
    }

    private fun categoryInput(name: String, direction: String): CustomCategory {
        checkInput(name.length <= 40 && name.isNotBlank(), "分类名称需为 1 至 40 个字符")
        val parsed = when (direction) {
            "expense" -> Direction.EXPENSE
            "income" -> Direction.INCOME
            else -> throw InvalidInput("分类收支类型无效")
        }
        return CustomCategory(name.trim(), parsed)
    }

    @JavascriptInterface
    fun importImage() = onMain { activity.importImage() }

    @JavascriptInterface
    fun setChannel(key: String, enabled: Boolean) {
        if (ChannelSettings.Channel.entries.none { it.key == key }) return
        onMain { activity.changeChannel(key, enabled) }
    }
    @JavascriptInterface
    fun configureWechatAuto()=onMain { activity.configureWechatAuto() }

    @JavascriptInterface
    fun setAutoApplication(packageName:String,enabled:Boolean):String = try {
        checkInput(PaymentApps.entries.any { it.packageName==packageName },"暂不支持此应用")
        checkInput(!enabled || runCatching { activity.packageManager.getApplicationInfo(packageName,0) }.isSuccess,"请先安装此应用")
        app.channels.setAutoPackage(packageName,enabled)
        if(!enabled)app.captureQueue.cancelAutomatic(packageName)
        onMain {
            if(app.channels.selectedAutoPackages().isEmpty()) {
                app.channels.setAutomaticEnabled(false)
                net.ankio.ledger.capture.ScreenCaptureService.current?.cancelAutomaticRecognition()
                if(!app.channels.captureEnabled())net.ankio.ledger.capture.CaptureRuntime.stopSafely()
            }
            net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages()
            app.changed()
        }
        JSONObject().put("ok",true).toString()
    } catch(e:Exception) { structuredFailure(e,"无法修改应用选择").toString() }

    /** An individual switch only commits its application after the shared setup completes. */
    @JavascriptInterface
    fun setPaymentApplication(packageName:String,enabled:Boolean)=onMain {
        if(packageName !in setOf("com.tencent.mm","com.eg.android.AlipayGphone"))return@onMain
        if(enabled) {
            if(runCatching { activity.packageManager.getApplicationInfo(packageName,0) }.isFailure) { activity.message("请先安装对应的支付应用");return@onMain }
            activity.permissionSetup.startAutomatic(packageName)
        } else {
            val name=if(packageName=="com.tencent.mm")"微信" else "支付宝"
            MintDialog.confirm(activity,"关闭${name}自动识别？","将停止${name}支付成功页自动记账，其他应用与摇一摇的设置保留。","关闭自动识别") {
                app.channels.setAutoPackage(packageName,false);app.captureQueue.cancelAutomatic(packageName)
                if(app.channels.selectedAutoPackages().isEmpty())app.channels.setAutomaticEnabled(false)
                net.ankio.ledger.capture.ScreenCaptureService.current?.checkPaymentPages()
                if(!app.channels.captureEnabled())net.ankio.ledger.capture.CaptureRuntime.stopSafely()
                app.changed()
            }
        }
    }

    @JavascriptInterface
    fun repairCapture()=onMain { activity.repairCapture() }

    @JavascriptInterface
    fun setAutomaticEnabled(enabled:Boolean)=onMain {
        if(enabled) {
            if(app.channels.selectedAutoPackages().isEmpty()) { activity.message("请先选择至少一个允许自动识别的应用");return@onMain }
            activity.permissionSetup.startAutomatic()
        } else MintDialog.confirm(activity,"暂停自动识别？","将停止所选应用的支付页面自动识别。摇一摇补录及已有账单会保留。","暂停自动识别") {
            app.channels.setAutomaticEnabled(false)
            net.ankio.ledger.capture.ScreenCaptureService.current?.cancelAutomaticRecognition()
            if(app.channels.captureEnabled())runCatching { net.ankio.ledger.capture.CaptureRuntime.refreshHealth() }
            else net.ankio.ledger.capture.CaptureRuntime.stopSafely()
            app.changed()
        }
    }

    @JavascriptInterface
    fun setScreenshotRetention(days:Int):String = try {
        checkInput(days in setOf(7,30,90,0),"截图保留期限无效")
        app.screenshots.setRetentionDays(days)
        app.screenshots.cleanup()
        JSONObject().put("ok",true).put("storage",app.screenshots.stats()).toString()
    } catch(error:Exception) { failure("无法更新截图保留设置",failureCode(error)).toString() }

    @JavascriptInterface
    fun backup(action: String) {
        when (action) {
            "export" -> onMain { activity.exportBackup() }
            "restore" -> onMain { activity.restoreBackup() }
        }
    }

    @JavascriptInterface
    fun about() = onMain { activity.showAbout() }

    @JavascriptInterface
    fun aboutFrom(json:String) = withPageOrigin(json) { bounds,radius -> activity.showAbout(bounds,radius) }

    /** Only finite geometry inside the visible WebView may influence a native surface. */
    private fun withPageOrigin(json:String,open:(android.graphics.RectF,Float?)->Unit) {
        if(json.length>1024)return
        val bounds=runCatching { JSONObject(json) }.getOrNull()?:return
        onMain {
            val viewport=bounds.optDouble("viewportWidth",0.0).toFloat()
            if(!viewport.isFinite() || viewport<=0)return@onMain
            if(activity.webView.width<=0 || activity.webView.height<=0)return@onMain
            val scale=activity.webView.width/viewport
            val location=IntArray(2);activity.webView.getLocationOnScreen(location)
            val x=bounds.optDouble("left",Double.NaN).toFloat()*scale
            val y=bounds.optDouble("top",Double.NaN).toFloat()*scale
            val w=bounds.optDouble("width",Double.NaN).toFloat()*scale
            val h=bounds.optDouble("height",Double.NaN).toFloat()*scale
            if(!listOf(x,y,w,h).all { it.isFinite() } || x<0 || y<0 || w<=0 || h<=0 || x+w>activity.webView.width+1 || y+h>activity.webView.height+1)return@onMain
            val radius=if(bounds.has("radius"))bounds.optDouble("radius",Double.NaN).toFloat()*scale else null
            if(radius!=null && (!radius.isFinite() || radius<0))return@onMain
            open(android.graphics.RectF(x+location[0],y+location[1],x+w+location[0],y+h+location[1]),radius?.coerceAtMost(minOf(w,h)/2f))
        }
    }

    @JavascriptInterface
    fun privacy() = onMain { activity.privacy() }

    @JavascriptInterface
    fun privacyFrom(json:String) = withPageOrigin(json) { bounds,radius -> activity.privacy(bounds,radius) }

    @JavascriptInterface
    fun shakeStatus()=onMain { activity.showShakeStatus() }

    /** Technical metadata only; no bill text, images or model credentials. */
    @JavascriptInterface
    fun captureDiagnostics():String=net.ankio.ledger.data.RecognitionDiagnostics.permissions(app).toString()


    @JavascriptInterface
    fun setupAction(action:String) { if(action in setOf("open","check","close","restricted","back","skip","view:battery","view:autostart","view:task_lock","reset:autostart","reset:task_lock","open:accessibility","open:battery_oem","open:battery","open:task_lock","open:autostart"))onMain { activity.permissionSetup.action(action) } }

    @JavascriptInterface
    fun revokeChannel(key: String) = onMain { activity.revokeChannel(key) }

    @JavascriptInterface
    fun remember(json: String) {
        if (json.length > 65_536) return
        try {
            val validated = JSONObject(json).toString()
            onMain { activity.rememberPage(validated) }
        } catch (_: JSONException) {
            // A malformed transient page state must not replace the last valid draft.
        }
    }

    private fun onMain(action: () -> Unit) {
        activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) action() }
    }

    private fun string(input: JSONObject, key: String, maxLength: Int): String {
        val value = input.get(key) as? String ?: throw InvalidInput("字段格式错误：$key")
        checkInput(value.length <= maxLength, "字段过长：$key")
        return value
    }

    private fun integer(input: JSONObject, key: String, minimum: Long, maximum: Long): Long {
        val value = input.get(key)
        checkInput(value is Int || value is Long, "字段必须是整数：$key")
        return (value as Number).toLong().also {
            checkInput(it in minimum..maximum, "字段超出范围：$key")
        }
    }

    private fun checkInput(valid: Boolean, message: String) {
        if (!valid) throw InvalidInput(message)
    }

    private fun failure(message:String,code:String="OPERATION_FAILED") = JSONObject().put("ok",false).put("error",message).put("code",code)
    private fun failureCode(error:Exception):String = when(error) {
        is ClassificationException -> error.code
        is CategoryException -> error.code
        is WalletException -> error.code
        is IllegalArgumentException -> error.message?.takeIf { it=="STATISTICS_OVERFLOW" } ?: "INVALID_INPUT"
        is JSONException -> "INVALID_INPUT"
        else -> "OPERATION_FAILED"
    }
    private fun structuredFailure(error:Exception,fallback:String):JSONObject {
        val code=failureCode(error)
        val messages=mapOf("TRANSFER_REVIEW_REQUIRED" to "请人工确认这笔转账后入账","TRANSFER_SAME_WALLET" to "转出与转入钱包不能相同","TRANSFER_WALLET_REQUIRED" to "请选择转出与转入钱包",
            "TRANSFER_ACCOUNT_UNSUPPORTED" to "转账仅支持自己的资产钱包","TRANSFER_FEE_AMBIGUOUS" to "请确认手续费与扣费方式",
            "TRANSFER_AMOUNT_CONFLICT" to "转账金额、手续费或到账金额不一致","TRANSFER_IDENTITY_CONFLICT" to "同一交易的信息有冲突，请核对原账单",
            "TRANSFER_STATUS_REQUIRED" to "尚未确认转账完成，请核对终态","TRANSFER_PAYMENT_EVIDENCE_CONFLICT" to "新账户证据与原钱包冲突，请核对",
            "TRANSFER_REFUND_CONFLICT" to "含消费退款记录，不能直接改为转账","STATISTICS_OVERFLOW" to "统计金额超过支持范围，请缩小时间范围")
        return failure(messages[code] ?: error.message ?: fallback,code).also { if(error is CategoryException && error.code=="CATEGORY_REFERENCED")it.put("referenceCount",error.referenceCount) }
    }
    private class InvalidInput(message: String) : IllegalArgumentException(message)

    private companion object {
        val READ_SESSION=java.util.UUID.randomUUID().toString()
        const val MAX_INPUT_LENGTH = 16_384
        const val MAX_TIME = 4_102_444_800_000L
        // WebView's JavaScript numbers must represent record IDs without rounding.
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        val DECIMAL = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    }
}
