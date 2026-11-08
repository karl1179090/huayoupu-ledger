package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Direction
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** A deletion is a durable instruction for old immutable task definitions, never a new match. */
data class CategoryReplacement(val categoryId:String,val categoryName:String,val direction:Direction,val replacementId:String)
data class CategoryDeletionPreview(val category:LedgerCategory,val billCount:Long,val ruleCount:Long,
    val queuedConfigurationCount:Long,val replacementCount:Long,val previewToken:String,val replacements:List<LedgerCategory>) {
    val requiresReplacement get()=billCount+ruleCount+queuedConfigurationCount+replacementCount>0
}
class CategoryPreviewStale(val preview:CategoryDeletionPreview):IllegalArgumentException("分类引用已变化，请核对最新影响后再确认删除")

internal object CategoryReplacementStorage {
    fun encode(entries:List<CategoryReplacement>)=JSONArray().apply { entries.forEach {
        put(JSONObject().put("categoryId",it.categoryId).put("categoryName",it.categoryName).put("direction",it.direction.name).put("replacementId",it.replacementId))
    } }
    fun decode(input:JSONArray?):List<CategoryReplacement> {
        if(input==null)return emptyList()
        require(input.length()<=100_000) { "分类替代记录过多" }
        return (0 until input.length()).map { index -> val entry=input.getJSONObject(index)
            val id=entry.getString("categoryId");val target=entry.getString("replacementId");val name=entry.getString("categoryName")
            val direction=Direction.valueOf(entry.getString("direction"))
            require(id.matches(Regex("[A-Za-z0-9:_-]{1,160}")) && target.matches(Regex("[A-Za-z0-9:_-]{1,160}")) && name.length in 1..1024)
            require(direction in listOf(Direction.EXPENSE,Direction.INCOME))
            CategoryReplacement(id,name,direction,target)
        }.also { require(it.map { entry -> entry.categoryId }.toSet().size==it.size) { "分类替代标识重复" } }
    }
    fun payload(db:SQLiteDatabase)=db.rawQuery("SELECT payload FROM classification_configuration WHERE singleton=1",null).use {
        check(it.moveToFirst());JSONObject(it.getString(0))
    }
    fun entries(db:SQLiteDatabase)=decode(payload(db).optJSONArray("categoryReplacements"))
    fun resolve(id:String,entries:List<CategoryReplacement>):String {
        val index=entries.associateBy { it.categoryId };val visited=mutableSetOf<String>();var current=id
        while(true) { require(visited.add(current)) { "分类替代存在循环" };current=index[current]?.replacementId ?: return current }
    }
    fun resolve(db:SQLiteDatabase,id:String)=resolve(id,entries(db))
    fun validate(entries:List<CategoryReplacement>,categories:List<LedgerCategory>) {
        val targets=categories.associateBy { it.id };val index=entries.associateBy { it.categoryId }
        require(entries.size<=100_000 && index.size==entries.size) { "分类替代标识重复或记录过多" }
        entries.forEach { entry ->
            require(entry.categoryId.matches(Regex("[A-Za-z0-9:_-]{1,160}")) && entry.replacementId.matches(Regex("[A-Za-z0-9:_-]{1,160}")) && entry.categoryName.length in 1..1024)
            require(entry.direction in listOf(Direction.EXPENSE,Direction.INCOME))
            require(entry.categoryId!=entry.replacementId && entry.categoryId !in targets) { "已删除分类与目录冲突" }
            require(entry.categoryId!="cat:${entry.direction.name.lowercase()}:other") { "系统兜底分类不能删除" }
            require(targets[resolve(entry.categoryId,entries)]?.direction==entry.direction) { "分类替代目标不存在或方向不一致" }
            index[entry.replacementId]?.let { require(it.direction==entry.direction) { "分类替代方向冲突" } }
        }
    }
    fun write(db:SQLiteDatabase,entries:List<CategoryReplacement>) {
        val json=payload(db).put("categoryReplacements",encode(entries))
        ClassificationStorage.writePayload(db,json)
    }
    fun restore(db:SQLiteDatabase,incoming:List<CategoryReplacement>,mapping:Map<String,LedgerCategory>) {
        if(incoming.isEmpty())return
        val local=entries(db)
        val additions=incoming.map { entry -> entry.copy(replacementId=mapping[entry.replacementId]?.id ?: entry.replacementId) }
        val combined=local+additions.filter { entry -> local.none { it.categoryId==entry.categoryId } }
        additions.forEach { entry -> local.firstOrNull { it.categoryId==entry.categoryId }?.let { existing ->
            require(existing.direction==entry.direction && resolve(existing.replacementId,combined)==resolve(entry.replacementId,combined)) { "分类替代映射冲突，恢复已取消" }
        } }
        validate(combined,CategoryDirectory.list(db));write(db,combined)
    }
    fun preview(db:SQLiteDatabase,id:String):CategoryDeletionPreview {
        val category=CategoryDirectory.find(db,id) ?: throw CategoryException("CATEGORY_NOT_FOUND","分类已不存在，请刷新后重试")
        if(category.isFallback)throw CategoryException("CATEGORY_SYSTEM","系统兜底分类不能删除")
        val replacements=entries(db)
        fun references(snapshot:net.ankio.ledger.core.ClassificationSnapshot)=(snapshot.customRules+snapshot.builtinRules).any { resolve(it.categoryId,replacements)==id }
        val configuration=ClassificationStorage.configuration(db)
        val rules=(configuration.customRules+configuration.builtinRules).filter { resolve(it.categoryId,replacements)==id }
        val snapshots=db.rawQuery("SELECT operation_key,payload FROM classification_snapshots WHERE active=1 ORDER BY operation_key",null).use { c -> buildList {
            while(c.moveToNext())if(references(ClassificationCodec.readSnapshot(c.getString(1))))add(c.getString(0) to c.getString(1))
        } }
        val bills=db.rawQuery("SELECT uid,category_decision FROM records WHERE category_id=? ORDER BY uid",arrayOf(id)).use { c -> buildList {
            while(c.moveToNext())add(listOf(c.getString(0),if(c.isNull(1))null else c.getString(1)))
        } }
        val previous=replacements.filter { resolve(it.categoryId,replacements)==id }
        // The confirmation binds to identities and definitions, so equal counts cannot hide a changed impact.
        val fingerprint=JSONObject().put("category",id).put("name",category.name).put("direction",category.direction.name)
            .put("bills",JSONArray(bills)).put("rules",JSONArray().apply { rules.forEach { put(ClassificationCodec.rule(it)) } })
            .put("snapshots",JSONArray(snapshots.map { listOf(it.first,it.second) })).put("replacements",encode(previous)).toString()
        val token=MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
        return CategoryDeletionPreview(category,bills.size.toLong(),rules.size.toLong(),snapshots.size.toLong(),previous.size.toLong(),token,
            CategoryDirectory.list(db,category.direction).filter { it.id!=id })
    }
    fun delete(db:SQLiteDatabase,id:String,replacementId:String?,token:String):CategoryDeletionPreview {
        val preview=preview(db,id)
        if(token!=preview.previewToken)throw CategoryPreviewStale(preview)
        if(preview.requiresReplacement && replacementId==null)throw CategoryException("CATEGORY_REPLACEMENT_REQUIRED","该分类仍有引用，请选择同方向的替代分类")
        val target=replacementId?.let { CategoryDirectory.find(db,it)?.takeIf { category -> category.id!=id && category.direction==preview.category.direction }
            ?: throw CategoryException("CATEGORY_REPLACEMENT_INVALID","请选择存在且同方向的其他分类") }
        if(target!=null) {
            val mappings=entries(db)+CategoryReplacement(id,preview.category.name,preview.category.direction,target.id)
            // Update only category projection and the final decision target; the historical rule/reason stays exact.
            db.rawQuery("SELECT id,category_decision FROM records WHERE category_id=?",arrayOf(id)).use { c ->
                while(c.moveToNext()) {
                    val decision=if(c.isNull(1))null else JSONObject(c.getString(1)).put("categoryId",target.id).toString()
                    db.update("records",ContentValues().apply { put("category_id",target.id);put("category",target.name);put("category_decision",decision) },"id=?",arrayOf(c.getLong(0).toString()))
                }
            }
            ClassificationStorage.replaceTarget(db,id,target.id)
            CategoryDirectory.remove(db,id)
            validate(mappings,CategoryDirectory.list(db));write(db,mappings)
        } else CategoryDirectory.remove(db,id)
        return preview
    }
}
