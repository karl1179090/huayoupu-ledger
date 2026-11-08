package net.ankio.ledger.data

import net.ankio.ledger.core.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Small read models: no OCR bodies or image blobs cross the bridge in a list request. */
class LedgerReadView(private val store:LedgerStore) {
    data class Window(val start:Long,val end:Long,val previous:Long)
    fun window(month:String,zone:java.time.ZoneId=java.time.ZoneId.systemDefault()):Window {
        require(Regex("(?:20[0-9]{2}|2100)-(?:0[1-9]|1[0-2])").matches(month))
        val date=java.time.YearMonth.parse(month).atDay(1)
        fun at(value:java.time.LocalDate)=value.atStartOfDay(zone).toInstant().toEpochMilli()
        return Window(at(date),at(date.plusMonths(1)),at(date.minusMonths(1)))
    }
    fun records(month:String,search:String="",offset:Int=0,limit:Int=200,direction:String="",category:String="",beforeAt:Long?=null,beforeId:Long?=null,contribution:Boolean=false,rangeStart:Long?=null,rangeEnd:Long?=null):JSONObject {
        val w=window(month);require(search.length<=200 && offset in 0..1_000_000 && limit in 1..200)
        require((beforeAt==null)==(beforeId==null))
        if(beforeAt!=null)require(beforeAt>0 && beforeId!!>0 && offset==0)
        require(direction.isEmpty()||direction in Direction.entries.map { it.name })
        val where=StringBuilder("NOT EXISTS(SELECT 1 FROM record_deletions d WHERE d.record_id=r.id)")
        val args=mutableListOf<String>()
        if(search.isBlank()) { where.append(" AND r.occurred_at>=? AND r.occurred_at<?");args+=(rangeStart ?: w.start).toString();args+=(rangeEnd ?: w.end).toString() }
        else { where.append(" AND (r.merchant LIKE ? ESCAPE '\\' OR r.category LIKE ? ESCAPE '\\' OR r.note LIKE ? ESCAPE '\\' OR r.transaction_id LIKE ? ESCAPE '\\' OR CASE r.source WHEN 'OCR' THEN '摇一摇' WHEN 'IMAGE' THEN '图片导入' WHEN 'MANUAL' THEN '手动' WHEN 'SMS' THEN '自动 · 短信' WHEN 'NOTIFICATION' THEN '自动 · 通知' END LIKE ? ESCAPE '\\' OR fa.name LIKE ? ESCAPE '\\' OR ta.name LIKE ? ESCAPE '\\' OR t.from_reference LIKE ? ESCAPE '\\' OR t.to_reference LIKE ? ESCAPE '\\' OR w.account_reference LIKE ? ESCAPE '\\')")
            val term="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";repeat(10){args+=term} }
        if(contribution && direction=="EXPENSE")where.append(" AND (r.direction='EXPENSE' OR (r.direction='TRANSFER' AND t.fee_cents>0))")
        else if(direction.isNotBlank()) { where.append(if(direction=="EXPENSE")" AND (r.direction=? OR r.direction IS NULL)" else " AND r.direction=?");args+=direction }
        if(category==LedgerStatistics.TRANSFER_FEE_KEY)where.append(" AND r.direction='TRANSFER' AND t.fee_cents>0")
        else if(category.isNotBlank()) { where.append(" AND r.category=? AND r.direction<>'TRANSFER'");args+=category.removePrefix("category:") }
        if(category.isNotBlank()||contribution)where.append(" AND r.state='POSTED'")
        if(beforeAt!=null) {
            where.append(" AND (r.occurred_at<? OR (r.occurred_at=? AND r.id<?))")
            args+=beforeAt.toString();args+=beforeAt.toString();args+=beforeId.toString()
        }
        val rows=JSONArray()
        store.readableDatabase.rawQuery("""SELECT r.id,r.uid,r.merchant,r.category,r.note,r.amount,r.direction,r.occurred_at,r.state,r.source,
            r.reason,r.duplicate_id,r.refund_provider,r.refund_original,r.refund_amount,r.refund_mode,r.refund_id,
            i.merchant_key,length(a.image), (SELECT MAX(sequence) FROM capture_history h WHERE h.record_id=r.id),r.transaction_id,
            CASE WHEN lower(r.origin) LIKE '%com.tencent.mm%' OR r.origin IN ('微信','微信支付') THEN 'wechat'
                WHEN lower(r.origin) LIKE '%com.eg.android.alipaygphone%' OR r.origin='支付宝' THEN 'alipay'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'微信')>0 AND instr(r.raw_text,'支付宝')=0 THEN 'wechat'
                WHEN r.source IN ('IMAGE','OCR') AND instr(r.raw_text,'支付宝')>0 AND instr(r.raw_text,'微信')=0 THEN 'alipay' END,
            w.eligibility,w.account_uid,w.selection_source,w.manual_locked,COALESCE((SELECT e.applied_cents FROM record_wallet_effects e WHERE e.record_uid=r.uid AND e.account_uid=w.account_uid),0),w.review_code,w.revision,
            wa.name,wa.kind,wa.bank_code,wa.card_last4,wa.archived,r.standalone_refund,
            t.record_uid tf_uid,t.from_account_uid tf_from,t.to_account_uid tf_to,t.fee_cents tf_fee,t.fee_mode tf_mode,
            t.from_selection_source tf_from_source,t.to_selection_source tf_to_source,t.from_manual_locked tf_from_locked,
            t.to_manual_locked tf_to_locked,t.fee_manual_locked tf_fee_locked,t.review_code tf_review,
            t.from_reference tf_from_reference,t.to_reference tf_to_reference,fa.name tf_from_name,ta.name tf_to_name,
            fa.archived tf_from_archived,ta.archived tf_to_archived,w.account_reference wallet_reference,
            r.category_id category_id,cat.icon category_icon
            FROM records r LEFT JOIN merchant_icons i ON i.record_id=r.id LEFT JOIN merchant_icon_assets a ON a.merchant_key=i.merchant_key
            LEFT JOIN record_wallet_links w ON w.record_uid=r.uid LEFT JOIN accounts wa ON wa.uid=w.account_uid
            LEFT JOIN record_transfer_details t ON t.record_uid=r.uid LEFT JOIN accounts fa ON fa.uid=t.from_account_uid
            LEFT JOIN accounts ta ON ta.uid=t.to_account_uid
            LEFT JOIN category_directory cat ON cat.id=r.category_id
            WHERE $where ORDER BY r.occurred_at DESC,r.id DESC LIMIT ${limit+1} OFFSET $offset""",args.toTypedArray()).use { c ->
            while(c.moveToNext())rows.put(JSONObject().apply {
                put("id",c.getLong(0));put("uid",c.getString(1));put("merchant",c.getString(2));put("category",c.getString(3));put("note",c.getString(4))
                put("categoryId",c.getColumnIndexOrThrow("category_id").let { if(c.isNull(it))JSONObject.NULL else c.getString(it) })
                put("categoryIcon",c.getColumnIndexOrThrow("category_icon").let { if(c.isNull(it))"tag" else c.getString(it) })
                put("amountCents",if(c.isNull(5))JSONObject.NULL else c.getLong(5));put("direction",if(c.isNull(6))JSONObject.NULL else c.getString(6))
                put("occurredAt",c.getLong(7));put("state",c.getString(8));put("source",c.getString(9));put("reason",c.getString(10))
                put("duplicateId",if(c.isNull(11))JSONObject.NULL else c.getLong(11))
                put("refund",if(c.isNull(12))JSONObject.NULL else JSONObject().put("provider",c.getString(12)).put("originalAmountCents",c.getLong(13))
                    .put("refundedCents",c.getLong(14)).put("mode",c.getString(15)).put("refundId",if(c.isNull(16))JSONObject.NULL else c.getString(16)))
                put("hasMerchantIcon",!c.isNull(18));put("merchantIconKey",if(c.isNull(18))JSONObject.NULL else c.getString(17));put("merchantIconRevision",c.getInt(18))
                put("historyRevision",c.getInt(19).toString());put("hasScreenshot",false);put("captureMode","");put("iconStatus",if(!c.isNull(18))"cached" else "")
                put("transactionId",if(c.isNull(20))JSONObject.NULL else c.getString(20))
                put("paymentProvider",if(c.isNull(21))JSONObject.NULL else c.getString(21))
                put("wallet",JSONObject().put("eligibility",if(c.isNull(22))"LEGACY_EXCLUDED" else c.getString(22))
                    .put("accountUid",if(c.isNull(23))JSONObject.NULL else c.getString(23))
                    .put("selectionSource",if(c.isNull(24))JSONObject.NULL else c.getString(24)).put("manualLocked",c.getInt(25)==1)
                    .put("appliedCents",c.getLong(26)).put("reviewCode",if(c.isNull(27))JSONObject.NULL else c.getString(27))
                    .put("revision",c.getLong(28)).put("accountName",if(c.isNull(29))JSONObject.NULL else c.getString(29))
                    .put("kind",if(c.isNull(30))JSONObject.NULL else c.getString(30)).put("bankCode",if(c.isNull(31))JSONObject.NULL else c.getString(31))
                    .put("cardLast4",if(c.isNull(32))JSONObject.NULL else c.getString(32)).put("archived",c.getInt(33)==1))
                put("standaloneRefund",if(c.isNull(34))JSONObject.NULL else JSONObject(c.getString(34)))
                fun text(key:String):String?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getString(it) }
                fun number(key:String):Long?=c.getColumnIndexOrThrow(key).let { if(c.isNull(it))null else c.getLong(it) }
                getJSONObject("wallet").put("accountReference",text("wallet_reference")?.let(::JSONObject) ?: JSONObject.NULL)
                val fee=number("tf_fee")
                val transfer=if(c.getString(6)=="TRANSFER" || c.isNull(6) && c.getString(8)=="REVIEW" && text("tf_uid")!=null)JSONObject().apply {
                    put("fromAccountUid",text("tf_from") ?: JSONObject.NULL);put("toAccountUid",text("tf_to") ?: JSONObject.NULL)
                    put("feeCents",fee ?: JSONObject.NULL);put("feeMode",text("tf_mode") ?: JSONObject.NULL)
                    for(end in listOf("from","to")) {
                        put(end,JSONObject().put("accountUid",text("tf_$end") ?: JSONObject.NULL).put("accountName",text("tf_${end}_name") ?: JSONObject.NULL)
                            .put("archived",number("tf_${end}_archived")==1L).put("reference",text("tf_${end}_reference")?.let(::JSONObject) ?: JSONObject.NULL))
                        put("${end}SelectionSource",text("tf_${end}_source") ?: JSONObject.NULL);put("${end}ManualLocked",number("tf_${end}_locked")==1L)
                    }
                    put("feeManualLocked",number("tf_fee_locked")==1L);put("reviewCode",text("tf_review") ?: JSONObject.NULL)
                    val calculated=runCatching { net.ankio.ledger.core.TransferCalculator.calculate(c.getLong(5),requireNotNull(fee),net.ankio.ledger.core.FeeMode.valueOf(requireNotNull(text("tf_mode")))) }.getOrNull()
                    put("debitCents",calculated?.debitCents ?: JSONObject.NULL);put("creditCents",calculated?.creditCents ?: JSONObject.NULL)
                } else JSONObject.NULL
                put("transfer",transfer)
                if(contribution)put("contributionCents",(if(c.getString(6)=="TRANSFER")fee ?: 0L else c.getLong(5)).toString())
            })
        }
        val pageUids=(0 until rows.length()).map { rows.getJSONObject(it).getString("uid") }
        val effects=mutableMapOf<String,JSONObject>()
        if(pageUids.isNotEmpty())store.readableDatabase.rawQuery("SELECT record_uid,account_uid,applied_cents FROM record_wallet_effects WHERE record_uid IN (${pageUids.joinToString(",") { "?" }})",pageUids.toTypedArray()).use { c->
            while(c.moveToNext())effects.getOrPut(c.getString(0)) { JSONObject() }.put(c.getString(1),c.getLong(2))
        }
        for(index in 0 until rows.length()) { val row=rows.getJSONObject(index);val e=effects[row.getString("uid")] ?: JSONObject();row.put("walletEffects",e);row.optJSONObject("transfer")?.put("effects",e) }
        val more=rows.length()>limit;if(more)rows.remove(rows.length()-1)
        val last=rows.optJSONObject(rows.length()-1)
        return JSONObject().put("records",rows).put("hasMore",more).put("offset",offset).put("limit",limit)
            .put("nextCursor",if(more && last!=null)JSONObject().put("occurredAt",last.getLong("occurredAt")).put("id",last.getLong("id")) else JSONObject.NULL)
    }
    fun summary(month:String):JSONObject = LedgerStatistics(store).month(month)
}
