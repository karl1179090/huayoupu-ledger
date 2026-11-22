package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.*
import java.util.UUID

/** Configuration changes and durable target reservations share the ledger transaction boundary. */
internal object ClassificationStorage {
    data class PreparedDraft(val configuration: ClassificationConfiguration, val rule: ClassificationRule, val payload: String)

    fun create(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE classification_configuration(singleton INTEGER PRIMARY KEY CHECK(singleton=1),payload TEXT NOT NULL,migration_payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE classification_snapshots(operation_key TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL,active INTEGER NOT NULL,task_database TEXT)")
        db.execSQL("ALTER TABLE records ADD COLUMN category_decision TEXT")
        val baseline=ClassificationDefaults.migrationBaseline.let { original -> original.copy(builtinRules=original.builtinRules.map { rule ->
            if(CategoryDirectory.find(db,rule.categoryId)!=null)rule else rule.copy(enabled=false,categoryId="cat:${rule.direction.name.lowercase()}:other")
        }) }
        val payload = ClassificationCodec.snapshot(baseline).toString()
        db.insertOrThrow("classification_configuration",null,ContentValues().apply { put("singleton",1)
            put("payload",payload);put("migration_payload",payload) })
    }
    private fun readPayload(db: SQLiteDatabase): String = db.rawQuery("SELECT payload FROM classification_configuration WHERE singleton=1",null).use {
        check(it.moveToFirst());it.getString(0)
    }
    fun configuration(db: SQLiteDatabase): ClassificationSnapshot = ClassificationCodec.readSnapshot(readPayload(db))
    fun current(db: SQLiteDatabase): ClassificationConfiguration = ClassificationCodec.readConfiguration(readPayload(db))
    fun replacements(db: SQLiteDatabase): List<CategoryReplacement> = ClassificationCodec.readReplacements(readPayload(db))
    fun migrationBaseline(db:SQLiteDatabase):ClassificationSnapshot = db.rawQuery("SELECT migration_payload FROM classification_configuration WHERE singleton=1",null).use {
        check(it.moveToFirst());ClassificationCodec.readSnapshot(it.getString(0))
    }
    private fun writePayload(db: SQLiteDatabase, payload: String) {
        db.update("classification_configuration",ContentValues().apply { put("payload",payload) },"singleton=1",null)
    }
    private fun validateConfiguration(
        configuration: ClassificationConfiguration,
        categories: Map<String, LedgerCategory>,
        replacements: Map<String, CategoryReplacement> = configuration.replacements.associateBy { it.categoryId },
    ): String {
        val payload = ClassificationCodec.encodeConfiguration(configuration)
        CategoryReplacementStorage.validateTargets(replacements, categories)
        validateRuleTargets(configuration.snapshot, replacements, categories)
        return payload
    }
    private fun validateRuleTargets(
        snapshot: ClassificationSnapshot,
        replacements: Map<String, CategoryReplacement>,
        categories: Map<String, LedgerCategory>,
    ) {
        (snapshot.customRules + snapshot.builtinRules).forEach { rule ->
            val id = CategoryReplacementStorage.resolve(rule.categoryId, replacements)
            require(categories[id]?.direction == rule.direction) { "规则目标分类不存在或方向不一致" }
        }
    }
    private fun validatedPayload(db: SQLiteDatabase, configuration: ClassificationConfiguration): String =
        validateConfiguration(configuration, CategoryDirectory.list(db).associateBy { it.id })

    private fun ClassificationSnapshot.newVersion() = copy(version = UUID.randomUUID().toString())

    private fun target(db:SQLiteDatabase,rule:ClassificationRule) {
        try { CategoryDirectory.requireCategory(db,rule.categoryId,rule.direction) }
        catch(_:CategoryException) { throw ClassificationException("RULE_TARGET","请选择存在且收支方向一致的目标分类") }
    }
    /** Save and hypothetical drafts share identity, target, ordering and complete wire validation. */
    fun prepareDraft(db: SQLiteDatabase, input: ClassificationRule): PreparedDraft {
        ClassificationCodec.validateRule(input);target(db,input)
        val configuration = current(db)
        val current = configuration.snapshot
        val old=input.id.takeIf(String::isNotBlank)?.let { id -> current.customRules.firstOrNull { it.id==id }
            ?: throw ClassificationException("RULE_NOT_FOUND","规则已不存在，请刷新后重试") }
        val rule=input.copy(id=old?.id ?: UUID.randomUUID().toString(),order=if(old?.direction==input.direction)old.order
            else (current.customRules.filter { it.direction==input.direction }.maxOfOrNull { it.order } ?: -1)+1,conflictReason=null)
        val rules=current.customRules.filterNot { it.id==rule.id }+rule
        // Validate the final UUID version too: a restored version can have a different byte length.
        val updated = configuration.copy(snapshot = current.copy(customRules = rules).newVersion())
        return PreparedDraft(updated, rule, validatedPayload(db, updated))
    }
    fun save(db:SQLiteDatabase,input:ClassificationRule):ClassificationRule {
        val prepared = prepareDraft(db, input)
        writePayload(db, prepared.payload)
        return prepared.rule
    }
    fun replaceTarget(db: SQLiteDatabase, replacement: CategoryReplacement) {
        val current = current(db)
        val snapshot = current.snapshot
        fun replace(rule:ClassificationRule)=if(rule.categoryId==replacement.categoryId)rule.copy(categoryId=replacement.replacementId) else rule
        val updated = snapshot.copy(customRules = snapshot.customRules.map(::replace), builtinRules = snapshot.builtinRules.map(::replace))
        val configuration = ClassificationConfiguration(if (updated == snapshot) snapshot else updated.newVersion(), current.replacements + replacement)
        writePayload(db, validatedPayload(db, configuration))
    }
    private fun updateRules(db: SQLiteDatabase, current: ClassificationConfiguration, snapshot: ClassificationSnapshot) {
        writePayload(db, validatedPayload(db, current.copy(snapshot = snapshot.newVersion())))
    }
    fun delete(db:SQLiteDatabase,id:String) {
        val current = current(db)
        val snapshot = current.snapshot
        if(snapshot.customRules.none { it.id==id })throw ClassificationException("RULE_NOT_FOUND","规则已不存在，请刷新后重试")
        updateRules(db,current,snapshot.copy(customRules=snapshot.customRules.filterNot { it.id==id }))
    }
    fun reorder(db:SQLiteDatabase,direction:Direction,ids:List<String>) {
        val current = current(db)
        val snapshot = current.snapshot
        val existing=snapshot.customRules.filter { it.direction==direction }.map { it.id }.toSet()
        if(direction !in listOf(Direction.EXPENSE,Direction.INCOME) || ids.size!=existing.size || ids.toSet()!=existing)
            throw ClassificationException("RULE_INVALID","规则顺序已变化，请刷新后重试")
        updateRules(db,current,snapshot.copy(customRules=snapshot.customRules.map { if(it.direction==direction)it.copy(order=ids.indexOf(it.id)) else it }))
    }
    fun setBuiltinEnabled(db:SQLiteDatabase,id:String,enabled:Boolean) {
        val current = current(db)
        val snapshot = current.snapshot
        val rule=snapshot.builtinRules.firstOrNull { it.id==id }
            ?: throw ClassificationException("RULE_NOT_FOUND","内置规则已不存在，请刷新后重试")
        target(db,rule)
        if(rule.enabled!=enabled)updateRules(db,current,snapshot.copy(builtinRules=snapshot.builtinRules.map { if(it.id==id)it.copy(enabled=enabled) else it }))
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
    fun reconcile(db:SQLiteDatabase,taskDatabase:String,active:Map<String,Long>,snapshot:(Long)->ClassificationSnapshot) {
        val previous=db.rawQuery("SELECT operation_key FROM classification_snapshots WHERE task_database=? AND active=1",arrayOf(taskDatabase)).use { cursor ->
            buildSet { while(cursor.moveToNext())add(cursor.getString(0)) }
        }
        previous.filterNot(active::containsKey).forEach { release(db,it) }
        active.forEach { (key,id) -> if(key !in previous)activate(db,key,taskDatabase) { snapshot(id) } }
    }
    fun activate(db:SQLiteDatabase,key:String,taskDatabase:String,snapshot:()->ClassificationSnapshot) {
        val rows=db.update("classification_snapshots",ContentValues().apply { put("active",1);put("task_database",taskDatabase) },
            "operation_key=?",arrayOf(key))
        if(rows==0)hold(db,key,snapshot(),taskDatabase)
    }
    fun release(db:SQLiteDatabase,key:String) { db.execSQL("UPDATE classification_snapshots SET active=0 WHERE operation_key=?",arrayOf(key)) }
    fun forget(db:SQLiteDatabase,key:String) { db.delete("classification_snapshots","operation_key=?",arrayOf(key)) }
    fun references(db:SQLiteDatabase,categoryId:String):Long {
        val current = current(db)
        val replacements = current.replacements.associateBy { it.categoryId }
        fun count(snapshot:ClassificationSnapshot)=(snapshot.customRules+snapshot.builtinRules).count { CategoryReplacementStorage.resolve(it.categoryId,replacements)==categoryId }.toLong()
        return count(current.snapshot)+replacements.values.count { CategoryReplacementStorage.resolve(it.categoryId,replacements)==categoryId }+db.rawQuery("SELECT payload FROM classification_snapshots WHERE active=1",null).use { c ->
            var result=0L;while(c.moveToNext())result+=count(ClassificationCodec.readSnapshot(c.getString(0)));result
        }
    }
    fun backup(db: SQLiteDatabase): ClassificationBackup {
        val current = current(db)
        val snapshots = db.rawQuery("SELECT operation_key,payload,active,task_database FROM classification_snapshots ORDER BY operation_key",null).use { c ->
            buildList { while(c.moveToNext())add(StoredClassificationSnapshot(c.getString(0),ClassificationCodec.readSnapshot(c.getString(1)),c.getInt(2)==1,if(c.isNull(3))null else c.getString(3))) }
        }
        return ClassificationBackup(current.snapshot, snapshots, current.replacements)
    }

    fun validateBackup(backup:ClassificationBackup,categories:List<LedgerCategory>) {
        val targets=categories.associateBy { it.id }
        val replacements = backup.replacements.associateBy { it.categoryId }
        validateConfiguration(ClassificationConfiguration(backup.configuration, backup.replacements), targets, replacements)
        backup.snapshots.forEach { entry ->
            ClassificationCodec.snapshot(entry.snapshot)
            if (entry.active) validateRuleTargets(entry.snapshot, replacements, targets)
        }
    }

    private fun ClassificationSnapshot.remap(mapping: Map<String, LedgerCategory>): ClassificationSnapshot {
        fun remap(rule: ClassificationRule) = rule.copy(categoryId = mapping[rule.categoryId]?.id ?: rule.categoryId)
        return copy(customRules = customRules.map(::remap), builtinRules = builtinRules.map(::remap))
    }
    /** Local identities win; conflicting incoming definitions are retained disabled and explained. */
    private fun mergeRules(current: ClassificationSnapshot, incoming: ClassificationSnapshot): ClassificationSnapshot {
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
        return if (rules == current.customRules) current else current.copy(customRules = rules).newVersion()
    }

    fun prepareRestore(db: SQLiteDatabase, backup: ClassificationBackup, mapping: Map<String, LedgerCategory>): String {
        val current = current(db)
        val incoming = backup.configuration.remap(mapping)
        val snapshot = if (current.snapshot == migrationBaseline(db)) incoming else mergeRules(current.snapshot, incoming)
        val replacements = CategoryReplacementStorage.merge(current.replacements, backup.replacements, mapping)
        return validatedPayload(db, ClassificationConfiguration(snapshot, replacements))
    }

    fun restore(db:SQLiteDatabase,backup:ClassificationBackup,mapping:Map<String,LedgerCategory>) {
        writePayload(db, prepareRestore(db, backup, mapping))
        // Queue rows are restored separately. Payloads remain available for a later task reconnect,
        // while an unrelated backup cannot introduce a permanent active target reservation.
        backup.snapshots.forEach { entry ->
            val snapshot=entry.snapshot.remap(mapping);val existing=db.rawQuery("SELECT payload FROM classification_snapshots WHERE operation_key=?",arrayOf(entry.operationKey)).use { if(it.moveToFirst())it.getString(0) else null }
            val payload=ClassificationCodec.snapshot(snapshot).toString()
            val key=if(existing!=null && existing!=payload)"imported:"+UUID.nameUUIDFromBytes((entry.operationKey+payload).toByteArray()) else entry.operationKey
            db.insertWithOnConflict("classification_snapshots",null,ContentValues().apply { put("operation_key",key);put("payload",payload);put("active",0);put("task_database",entry.taskDatabase) },SQLiteDatabase.CONFLICT_IGNORE)
        }
    }
}
