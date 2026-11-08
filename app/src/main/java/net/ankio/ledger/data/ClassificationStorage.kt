package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.*
import java.util.UUID

/** Configuration changes and durable target reservations share the ledger transaction boundary. */
internal object ClassificationStorage {
    fun create(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE classification_configuration(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL,migration_payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE classification_snapshots(operation_key TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,active INTEGER NOT NULL,task_database TEXT)")
        db.execSQL("ALTER TABLE records ADD COLUMN category_decision TEXT")
        val baseline=ClassificationDefaults.migrationBaseline.let { original -> original.copy(builtinRules=original.builtinRules.map { rule ->
            if(CategoryDirectory.find(db,rule.categoryId)!=null)rule else rule.copy(enabled=false,categoryId="cat:${rule.direction.name.lowercase()}:other")
        }) }
        db.insertOrThrow("classification_configuration",null,ContentValues().apply { put("singleton",1)
            put("payload",ClassificationCodec.snapshot(baseline).toString());put("migration_payload",ClassificationCodec.snapshot(baseline).toString()) })
    }
    fun configuration(db:SQLiteDatabase):ClassificationSnapshot = db.rawQuery("SELECT payload FROM classification_configuration WHERE singleton=1",null).use {
        check(it.moveToFirst());ClassificationCodec.readSnapshot(it.getString(0))
    }
    fun migrationBaseline(db:SQLiteDatabase):ClassificationSnapshot = db.rawQuery("SELECT migration_payload FROM classification_configuration WHERE singleton=1",null).use {
        check(it.moveToFirst());ClassificationCodec.readSnapshot(it.getString(0))
    }
    internal fun writePayload(db:SQLiteDatabase,payload:org.json.JSONObject) {
        val serialized=payload.toString()
        ClassificationCodec.readSnapshot(serialized)
        db.update("classification_configuration",ContentValues().apply { put("payload",serialized) },"singleton=1",null)
    }
    private fun payload(db:SQLiteDatabase,snapshot:ClassificationSnapshot)=
        ClassificationCodec.configuration(snapshot,CategoryReplacementStorage.entries(db))
    private fun write(db:SQLiteDatabase,snapshot:ClassificationSnapshot) { writePayload(db,payload(db,snapshot)) }
    private fun changed(db:SQLiteDatabase,snapshot:ClassificationSnapshot) = write(db,snapshot.copy(version=UUID.randomUUID().toString()))
    private fun target(db:SQLiteDatabase,rule:ClassificationRule) {
        try { CategoryDirectory.requireCategory(db,rule.categoryId,rule.direction) }
        catch(_:CategoryException) { throw ClassificationException("RULE_TARGET","请选择存在且收支方向一致的目标分类") }
    }
    /** Save and hypothetical drafts share identity, target, ordering and complete wire validation. */
    fun prepared(db:SQLiteDatabase,input:ClassificationRule):Pair<ClassificationSnapshot,ClassificationRule> {
        ClassificationCodec.validateRule(input);target(db,input)
        val current=configuration(db)
        val old=input.id.takeIf(String::isNotBlank)?.let { id -> current.customRules.firstOrNull { it.id==id }
            ?: throw ClassificationException("RULE_NOT_FOUND","规则已不存在，请刷新后重试") }
        val rule=input.copy(id=old?.id ?: UUID.randomUUID().toString(),order=if(old?.direction==input.direction)old.order
            else (current.customRules.filter { it.direction==input.direction }.maxOfOrNull { it.order } ?: -1)+1,conflictReason=null)
        val rules=current.customRules.filterNot { it.id==rule.id }+rule
        if(rules.size>2_000)throw ClassificationException("RULE_INVALID","分类规则数量过多")
        val snapshot=current.copy(customRules=rules)
        ClassificationCodec.readSnapshot(payload(db,snapshot).toString())
        return snapshot to rule
    }
    fun save(db:SQLiteDatabase,input:ClassificationRule):ClassificationRule {
        val (snapshot,rule)=prepared(db,input)
        changed(db,snapshot);return rule
    }
    fun replaceTarget(db:SQLiteDatabase,id:String,replacementId:String) {
        val current=configuration(db)
        fun replace(rule:ClassificationRule)=if(rule.categoryId==id)rule.copy(categoryId=replacementId) else rule
        val updated=current.copy(customRules=current.customRules.map(::replace),builtinRules=current.builtinRules.map(::replace))
        if(updated!=current)changed(db,updated)
    }
    fun delete(db:SQLiteDatabase,id:String) {
        val current=configuration(db)
        if(current.customRules.none { it.id==id })throw ClassificationException("RULE_NOT_FOUND","规则已不存在，请刷新后重试")
        changed(db,current.copy(customRules=current.customRules.filterNot { it.id==id }))
    }
    fun reorder(db:SQLiteDatabase,direction:Direction,ids:List<String>) {
        val current=configuration(db);val existing=current.customRules.filter { it.direction==direction }.map { it.id }.toSet()
        if(direction !in listOf(Direction.EXPENSE,Direction.INCOME) || ids.size!=existing.size || ids.toSet()!=existing)
            throw ClassificationException("RULE_INVALID","规则顺序已变化，请刷新后重试")
        changed(db,current.copy(customRules=current.customRules.map { if(it.direction==direction)it.copy(order=ids.indexOf(it.id)) else it }))
    }
    fun setBuiltinEnabled(db:SQLiteDatabase,id:String,enabled:Boolean) {
        val current=configuration(db)
        val rule=current.builtinRules.firstOrNull { it.id==id }
            ?: throw ClassificationException("RULE_NOT_FOUND","内置规则已不存在，请刷新后重试")
        target(db,rule)
        if(rule.enabled!=enabled)changed(db,current.copy(builtinRules=current.builtinRules.map { if(it.id==id)it.copy(enabled=enabled) else it }))
    }
    fun copyBuiltin(db:SQLiteDatabase,id:String):ClassificationRule {
        val rule=configuration(db).builtinRules.firstOrNull { it.id==id }
            ?: throw ClassificationException("RULE_NOT_FOUND","内置规则已不存在，请刷新后重试")
        target(db,rule)
        return rule.copy(id="",name=rule.name.take(76)+"（副本）",enabled=true,order=0,conflictReason=null)
    }
    fun hold(db:SQLiteDatabase,key:String,snapshot:ClassificationSnapshot=configuration(db),taskDatabase:String?=null):ClassificationSnapshot {
        require(key.length in 1..200)
        db.rawQuery("SELECT payload FROM classification_snapshots WHERE operation_key=?",arrayOf(key)).use {
            if(it.moveToFirst())return ClassificationCodec.readSnapshot(it.getString(0))
        }
        db.insertOrThrow("classification_snapshots",null,ContentValues().apply {
            put("operation_key",key);put("payload",ClassificationCodec.snapshot(snapshot).toString());put("active",1);put("task_database",taskDatabase)
        })
        return snapshot
    }
    fun reconcile(db:SQLiteDatabase,taskDatabase:String,tasks:List<StoredClassificationSnapshot>) {
        val active=tasks.filter { it.active }.associateBy { it.operationKey }
        db.rawQuery("SELECT operation_key FROM classification_snapshots WHERE task_database=? AND active=1",arrayOf(taskDatabase)).use { cursor ->
            while(cursor.moveToNext())if(cursor.getString(0) !in active)release(db,cursor.getString(0))
        }
        active.values.forEach { entry ->
            hold(db,entry.operationKey,entry.snapshot,taskDatabase)
            db.execSQL("UPDATE classification_snapshots SET active=1,task_database=? WHERE operation_key=?",arrayOf(taskDatabase,entry.operationKey))
        }
    }
    fun release(db:SQLiteDatabase,key:String) { db.execSQL("UPDATE classification_snapshots SET active=0 WHERE operation_key=?",arrayOf(key)) }
    fun forget(db:SQLiteDatabase,key:String) { db.delete("classification_snapshots","operation_key=?",arrayOf(key)) }
    fun references(db:SQLiteDatabase,categoryId:String):Long {
        val replacements=CategoryReplacementStorage.entries(db)
        fun count(snapshot:ClassificationSnapshot)=(snapshot.customRules+snapshot.builtinRules).count { CategoryReplacementStorage.resolve(it.categoryId,replacements)==categoryId }.toLong()
        return count(configuration(db))+replacements.count { CategoryReplacementStorage.resolve(it.categoryId,replacements)==categoryId }+db.rawQuery("SELECT payload FROM classification_snapshots WHERE active=1",null).use { c ->
            var result=0L;while(c.moveToNext())result+=count(ClassificationCodec.readSnapshot(c.getString(0)));result
        }
    }
    fun backup(db:SQLiteDatabase)=ClassificationBackup(configuration(db),db.rawQuery("SELECT operation_key,payload,active,task_database FROM classification_snapshots ORDER BY operation_key",null).use { c ->
        buildList { while(c.moveToNext())add(StoredClassificationSnapshot(c.getString(0),ClassificationCodec.readSnapshot(c.getString(1)),c.getInt(2)==1,if(c.isNull(3))null else c.getString(3))) }
    },CategoryReplacementStorage.entries(db))

    fun validateBackup(backup:ClassificationBackup,categories:List<LedgerCategory>) {
        CategoryReplacementStorage.validate(backup.replacements,categories)
        ClassificationCodec.configuration(backup.configuration,backup.replacements)
        val targets=categories.associateBy { it.id }
        fun validate(snapshot:ClassificationSnapshot,requireTargets:Boolean) {
            ClassificationCodec.snapshot(snapshot)
            (snapshot.customRules+snapshot.builtinRules).forEach { rule ->
                ClassificationCodec.validateRule(rule,true)
                if(requireTargets)require(targets[CategoryReplacementStorage.resolve(rule.categoryId,backup.replacements)]?.direction==rule.direction) { "规则目标分类不存在或方向不一致" }
            }
        }
        validate(backup.configuration,true);backup.snapshots.forEach { validate(it.snapshot,it.active) }
    }

    /** Local identities win; conflicting incoming definitions are retained disabled and explained. */
    fun restore(db:SQLiteDatabase,backup:ClassificationBackup,mapping:Map<String,LedgerCategory>) {
        fun remap(snapshot:ClassificationSnapshot)=snapshot.copy(customRules=snapshot.customRules.map { it.copy(categoryId=mapping[it.categoryId]?.id ?: it.categoryId) },
            builtinRules=snapshot.builtinRules.map { it.copy(categoryId=mapping[it.categoryId]?.id ?: it.categoryId) })
        CategoryReplacementStorage.restore(db,backup.replacements,mapping)
        val incoming=remap(backup.configuration);val current=configuration(db)
        if(current==migrationBaseline(db))write(db,incoming)
        else {
            val rules=current.customRules.toMutableList()
            fun add(rule:ClassificationRule) {
                if(rules.any { it.copy(order=rule.order)==rule })return
                val conflict=rules.any { it.id==rule.id || it.direction==rule.direction && it.name==rule.name }
                val id=if(conflict)UUID.nameUUIDFromBytes(("rule-import:"+ClassificationCodec.rule(rule)).toByteArray()).toString() else rule.id
                if(rules.any { it.id==id })return
                rules+=rule.copy(id=id,enabled=if(conflict)false else rule.enabled,
                    conflictReason=if(conflict)"导入规则与本地配置冲突，已停用，请核对后启用" else rule.conflictReason,
                    order=(rules.filter { it.direction==rule.direction }.maxOfOrNull { it.order } ?: -1)+1)
            }
            incoming.customRules.forEach(::add)
            incoming.builtinRules.filter { imported -> current.builtinRules.none { it==imported } }.forEach { imported ->
                add(imported.copy(id="imported:${imported.id}",enabled=false,name="导入${imported.name}",conflictReason="导入内置配置与本地不同，已停用"))
            }
            if(rules!=current.customRules)changed(db,current.copy(customRules=rules))
        }
        // Queue rows are restored separately. Payloads remain available for a later task reconnect,
        // while an unrelated backup cannot introduce a permanent active target reservation.
        backup.snapshots.forEach { entry ->
            val snapshot=remap(entry.snapshot);val existing=db.rawQuery("SELECT payload FROM classification_snapshots WHERE operation_key=?",arrayOf(entry.operationKey)).use { if(it.moveToFirst())it.getString(0) else null }
            val payload=ClassificationCodec.snapshot(snapshot).toString()
            val key=if(existing!=null && existing!=payload)"imported:"+UUID.nameUUIDFromBytes((entry.operationKey+payload).toByteArray()) else entry.operationKey
            db.insertWithOnConflict("classification_snapshots",null,ContentValues().apply { put("operation_key",key);put("payload",payload);put("active",0);put("task_database",entry.taskDatabase) },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
}
