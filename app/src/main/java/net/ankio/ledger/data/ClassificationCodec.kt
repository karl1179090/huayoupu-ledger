package net.ankio.ledger.data

import net.ankio.ledger.core.*
import org.json.JSONArray
import org.json.JSONObject

class ClassificationException(val code: String, message: String): IllegalArgumentException(message)

data class StoredClassificationSnapshot(val operationKey: String, val snapshot: ClassificationSnapshot, val active: Boolean,val taskDatabase:String?=null)
data class ClassificationBackup(val configuration: ClassificationSnapshot, val snapshots: List<StoredClassificationSnapshot>)

/** One bounded wire format for durable task snapshots, bridge data and backups. */
object ClassificationCodec {
    fun rule(rule: ClassificationRule): JSONObject = JSONObject().put("id", rule.id).put("name", rule.name)
        .put("enabled", rule.enabled).put("direction", rule.direction.name).put("categoryId", rule.categoryId)
        .put("order", rule.order).put("matchMode", rule.matchMode.name).put("source", rule.source ?: JSONObject.NULL)
        .put("conflictReason", rule.conflictReason ?: JSONObject.NULL).put("conditions", JSONArray().apply {
            rule.conditions.forEach { put(JSONObject().put("field", it.field.name).put("operator", it.operator.name).put("value", it.value)) }
        })

    fun snapshot(value: ClassificationSnapshot): JSONObject = JSONObject().put("formatVersion", 1).put("version", value.version)
        .put("customRules", JSONArray().apply { value.customRules.forEach { put(rule(it)) } })
        .put("builtinRules", JSONArray().apply { value.builtinRules.forEach { put(rule(it)) } })

    fun readRule(input: JSONObject): ClassificationRule {
        fun text(key: String, max: Int): String = (input.get(key) as? String)?.takeIf { it.length <= max }
            ?: throw ClassificationException("RULE_INVALID", "规则字段无效：$key")
        val conditions=input.getJSONArray("conditions")
        if(conditions.length()!=1)throw ClassificationException("RULE_INVALID", "请选择一条商家匹配条件")
        val condition=conditions.getJSONObject(0)
        val field=runCatching { ClassificationField.valueOf(condition.getString("field")) }.getOrNull()
            ?: throw ClassificationException("RULE_INVALID", "请选择商家条件")
        val operator=runCatching { ClassificationOperator.valueOf(condition.getString("operator")) }.getOrNull()
            ?: throw ClassificationException("RULE_INVALID", "请选择包含或等于")
        val keyword=condition.get("value") as? String ?: throw ClassificationException("RULE_INVALID", "请输入关键词")
        val direction=runCatching { Direction.valueOf(text("direction",16)) }.getOrNull()
        if(direction !in listOf(Direction.EXPENSE,Direction.INCOME))throw ClassificationException("RULE_INVALID", "请选择收入或支出")
        val enabled=input.get("enabled") as? Boolean ?: throw ClassificationException("RULE_INVALID", "规则启停状态无效")
        if(input.optString("matchMode","ALL")!="ALL" || !input.isNull("source"))throw ClassificationException("RULE_INVALID", "当前仅支持商家单条件")
        val order=if(input.has("order"))input.get("order") as? Int ?: -1 else 0
        return ClassificationRule(if(input.isNull("id"))"" else text("id",160),text("name",80).trim(),enabled,direction!!,
            text("categoryId",160),order,listOf(ClassificationCondition(field,operator,keyword)),
            conflictReason=if(input.isNull("conflictReason"))null else text("conflictReason",200)).also(::validateRule)
    }

    fun validateRule(rule: ClassificationRule, requireId: Boolean = false) {
        if(rule.name.isBlank() || rule.name.length>80 || rule.name.any(Char::isISOControl) ||
            rule.id.length>160 || requireId && rule.id.isBlank() || rule.order !in 0..1_000_000 ||
            rule.categoryId.isBlank() || rule.categoryId.length>160 || rule.direction !in listOf(Direction.EXPENSE,Direction.INCOME) ||
            rule.conditions.size!=1 || rule.conditions.any { it.value.length>200 || ClassificationEngine.normalized(it.value).isBlank() || it.value.any(Char::isISOControl) })
            throw ClassificationException("RULE_INVALID", "请填写规则名称、有效关键词、收支方向及目标分类")
    }

    fun readSnapshot(json: String): ClassificationSnapshot {
        require(json.length<=2_000_000) { "分类快照过大" }
        return readSnapshot(JSONObject(json))
    }
    fun readSnapshot(input: JSONObject): ClassificationSnapshot {
        require(input.getInt("formatVersion")==1) { "分类快照版本不支持" }
        val version=input.getString("version");require(version.isNotBlank() && version.length<=160)
        fun rules(key:String):List<ClassificationRule> {
            val entries=input.getJSONArray(key);require(entries.length()<=2_000) { "分类规则过多" }
            return (0 until entries.length()).map { readRule(entries.getJSONObject(it)).also { value -> validateRule(value,true) } }
                .also { values -> require(values.map { it.id }.toSet().size==values.size) { "分类规则标识重复" } }
        }
        return ClassificationSnapshot(version,rules("customRules"),rules("builtinRules"))
    }

    fun decision(value: CategoryDecision?): JSONObject = value?.let {
        JSONObject().put("categoryId",it.categoryId?:JSONObject.NULL).put("source",it.source.name).put("manual",it.manual)
            .put("ruleId",it.ruleId?:JSONObject.NULL).put("ruleVersion",it.ruleVersion?:JSONObject.NULL).put("explanation",it.explanation)
    } ?: JSONObject().put("categoryId",JSONObject.NULL).put("source","LEGACY").put("manual",false)
        .put("ruleId",JSONObject.NULL).put("ruleVersion",JSONObject.NULL).put("explanation","保留原分类")

    fun readDecision(input: JSONObject?): CategoryDecision? = input?.let {
        fun optional(key:String)=if(it.isNull(key))null else it.getString(key).also { text -> require(text.length<=200) }
        val explanation=it.getString("explanation");require(explanation.length<=300)
        CategoryDecision(optional("categoryId"),CategorySource.valueOf(it.getString("source")),
            it.get("manual") as? Boolean ?: error("分类人工状态无效"),optional("ruleId"),optional("ruleVersion"),explanation)
    }

    fun backup(value: ClassificationBackup): JSONObject = JSONObject().put("configuration",snapshot(value.configuration))
        .put("snapshots",JSONArray().apply { value.snapshots.forEach { put(JSONObject().put("operationKey",it.operationKey)
            .put("active",it.active).put("taskDatabase",it.taskDatabase ?: JSONObject.NULL).put("snapshot",snapshot(it.snapshot))) } })

    fun readBackup(input:JSONObject):ClassificationBackup {
        val snapshots=input.getJSONArray("snapshots");require(snapshots.length()<=100_000)
        val keys=mutableSetOf<String>()
        return ClassificationBackup(readSnapshot(input.getJSONObject("configuration")),(0 until snapshots.length()).map { index ->
            val entry=snapshots.getJSONObject(index);val key=entry.getString("operationKey")
            require(key.length in 1..200 && keys.add(key)) { "分类快照任务标识无效" }
            StoredClassificationSnapshot(key,readSnapshot(entry.getJSONObject("snapshot")),entry.get("active") as? Boolean ?: error("快照状态无效"),if(entry.isNull("taskDatabase"))null else entry.getString("taskDatabase").also { require(it.length in 1..200) })
        })
    }
}
