package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Transient diagnostics; no snapshot or bill is written by rule testing. */
data class ClassificationTestResult(val preview:ClassificationPreview,val category:LedgerCategory,val draftRuleId:String?=null)
data class ClassificationRuleDraft(val recordId:Long,val input:ClassificationInput,val rule:ClassificationRule)
data class ClassificationTestRequest(val recordId:Long?,val input:ClassificationInput?,val rule:ClassificationRule?)

/** Transient bridge data still delegates complete rule validation to the durable rule codec. */
object ClassificationPreviewCodec {
    private fun invalid():Nothing = throw ClassificationException("RULE_INPUT","请选择有效账单，或填写有效的测试输入")
    fun readRequest(json:String):ClassificationTestRequest {
        val request=try {
            if(json.length>16_384)invalid()
            JSONObject(json)
        } catch(_:Exception) { invalid() }
        val (recordId,input)=try {
            val fromBill=!request.isNull("recordId");val manual=!request.isNull("input")
            if(fromBill==manual)invalid()
            if(fromBill) {
                val value=request.get("recordId")
                val id=when(value) { is Int -> value.toLong();is Long -> value;else -> invalid() }
                if(id !in 1..9_007_199_254_740_991L)invalid()
                id to null
            } else {
                val value=request.getJSONObject("input")
                val direction=Direction.valueOf(value.get("direction") as? String ?: invalid())
                fun optionalText(key:String):String? = if(value.isNull(key))null else value.get(key) as? String ?: invalid()
                val entries=if(value.isNull("orderContent"))JSONArray() else value.getJSONArray("orderContent")
                if(entries.length()>3)invalid()
                val contents=(0 until entries.length()).map { entries.get(it) as? String ?: invalid() }
                null to ClassificationInput.hypothetical(direction,optionalText("merchant"),contents,optionalText("source"))
            }
        } catch(_:Exception) { invalid() }
        val rule=if(request.isNull("rule"))null else {
            val value=request.optJSONObject("rule") ?: throw ClassificationException("RULE_INVALID","规则数据无效")
            ClassificationCodec.readRule(value)
        }
        return ClassificationTestRequest(recordId,input,rule)
    }

    fun input(value:ClassificationInput):JSONObject = JSONObject().put("direction",value.direction.name)
        .put("merchant",value.merchant ?: JSONObject.NULL).put("orderContent",JSONArray(value.orderContent))
        .put("captureSource",value.captureSource?.name ?: JSONObject.NULL).put("sourcePackage",value.sourcePackage ?: JSONObject.NULL)
        .put("platform",value.platform ?: JSONObject.NULL)

    fun preview(value:ClassificationTestResult):JSONObject = JSONObject().put("input",input(value.preview.input))
        .put("decision",ClassificationCodec.decision(value.preview.decision)).put("draftRuleId",value.draftRuleId ?: JSONObject.NULL)
        .put("category",JSONObject().put("id",value.category.id).put("name",value.category.name)
            .put("icon",value.category.icon).put("direction",value.category.direction.name))
        .put("rules",JSONArray().apply { value.preview.rules.forEach { result ->
            put(JSONObject().put("rule",ClassificationCodec.rule(result.rule)).put("source",result.source.name)
                .put("enabled",result.rule.enabled).put("directionMatches",result.directionMatches).put("sourceMatches",result.sourceMatches)
                .put("conditionsMatch",result.conditionsMatch).put("matched",result.matched).put("selected",result.selected)
                .put("interceptedByRuleId",result.interceptedByRuleId ?: JSONObject.NULL).put("conditions",JSONArray().apply {
                    result.conditions.forEach { condition -> put(JSONObject().put("field",condition.condition.field.name)
                        .put("operator",condition.condition.operator.name).put("value",condition.condition.value)
                        .put("matched",condition.matched)) }
                }))
        } })
}
