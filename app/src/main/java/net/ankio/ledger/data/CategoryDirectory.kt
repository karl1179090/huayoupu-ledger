package net.ankio.ledger.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import net.ankio.ledger.core.Direction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** A category keeps its identity when its display name, icon or position changes. */
data class LedgerCategory(
    val id: String,
    val name: String,
    val direction: Direction,
    val icon: String = "tag",
    val order: Int = 0,
    val isFallback: Boolean = false,
    val referenceCount: Long = 0,
) {
    val canDelete: Boolean get() = !isFallback && referenceCount == 0L
}

class CategoryException(val code: String, message: String, val referenceCount: Long = 0) : IllegalArgumentException(message)

/** SQLite boundary shared by migration, management and record writes. No classification runs here. */
internal object CategoryDirectory {
    val icons = linkedMapOf(
        "utensils" to "餐饮", "bus" to "交通", "bag-shopping" to "购物", "gamepad" to "娱乐",
        "house" to "居住", "plus" to "医疗", "graduation-cap" to "教育", "shirt" to "服饰",
        "basket-shopping" to "日用", "phone" to "通讯", "gift" to "礼物", "ellipsis" to "其他",
        "wallet" to "钱包", "award" to "奖金", "rotate-left" to "退款", "tag" to "标签",
        "heart" to "爱心", "paw" to "宠物", "plane" to "旅行", "coffee" to "咖啡", "car" to "汽车",
        "book" to "书籍", "briefcase" to "工作", "coins" to "钱币", "music" to "音乐", "dumbbell" to "运动",
    )
    private val expense = listOf("餐饮" to "utensils", "交通" to "bus", "购物" to "bag-shopping", "娱乐" to "gamepad",
        "居住" to "house", "医疗" to "plus", "教育" to "graduation-cap", "服饰" to "shirt", "日用" to "basket-shopping",
        "通讯" to "phone", "人情" to "gift", "其他" to "ellipsis")
    private val income = listOf("工资" to "wallet", "奖金" to "award", "礼物" to "gift", "退款" to "rotate-left", "其他" to "ellipsis")
    private val presetKeys = listOf("dining", "transport", "shopping", "entertainment", "housing", "medical", "education", "clothing", "daily", "communication", "social", "other")
    val presets: List<LedgerCategory> = expense.mapIndexed { index, pair ->
        LedgerCategory("cat:expense:${presetKeys[index]}", pair.first, Direction.EXPENSE, pair.second, index, pair.first == "其他")
    } + income.mapIndexed { index, pair ->
        LedgerCategory("cat:income:${listOf("salary", "bonus", "gift", "refund", "other")[index]}", pair.first, Direction.INCOME, pair.second, index, pair.first == "其他")
    }
    private val recognitionAliases = mapOf("餐饮美食" to "dining", "交通出行" to "transport", "购物消费" to "shopping",
        "日用购物" to "daily", "医疗健康" to "medical")

    fun normalized(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC)
        .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }.lowercase(Locale.ROOT)

    fun validateName(name: String) {
        if (name.isBlank() || normalized(name).isEmpty() || name.length > 40 || name != name.trim() || name.any(Char::isISOControl))
            throw CategoryException("CATEGORY_INVALID", "分类名称需为 1 至 40 个字符，且不能包含控制字符")
        if (normalized(name) == normalized("转账手续费"))
            throw CategoryException("CATEGORY_INVALID", "转账手续费为系统分类，请使用其他分类名称")
    }
    fun validateDirection(direction: Direction) {
        if (direction !in setOf(Direction.EXPENSE, Direction.INCOME)) throw CategoryException("CATEGORY_DIRECTION", "分类仅支持收入或支出")
    }

    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE category_directory (
            id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('EXPENSE','INCOME')),
            normalized_name TEXT NOT NULL, icon TEXT NOT NULL, sort_order INTEGER NOT NULL,
            is_fallback INTEGER NOT NULL DEFAULT 0, UNIQUE(direction,name))""")
        db.execSQL("CREATE INDEX categories_normalized ON category_directory(direction,normalized_name)")
        db.execSQL("ALTER TABLE records ADD COLUMN category_id TEXT")
        db.execSQL("CREATE INDEX records_category_id ON records(category_id)")
        presets.forEach { insert(db, it) }
        db.rawQuery("SELECT direction,name FROM custom_categories ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) ensure(db, Direction.valueOf(c.getString(0)), c.getString(1))
        }
        // Exact old names remain separate, including unknown names and flat '一级/二级' CSV labels.
        db.rawQuery("SELECT DISTINCT direction,category FROM records WHERE direction IN ('EXPENSE','INCOME')", null).use { c ->
            while (c.moveToNext()) ensure(db, Direction.valueOf(c.getString(0)), c.getString(1))
        }
        db.execSQL("""UPDATE records SET category_id=(SELECT id FROM category_directory c
            WHERE c.direction=records.direction AND c.name=records.category) WHERE direction IN ('EXPENSE','INCOME')""")
    }

    private fun select(db: SQLiteDatabase, where: String = "", args: Array<String>? = null): List<LedgerCategory> {
        return db.rawQuery("""SELECT c.id,c.name,c.direction,c.icon,c.sort_order,c.is_fallback,
            (SELECT COUNT(*) FROM records r WHERE r.category_id=c.id) FROM category_directory c
            $where ORDER BY c.direction,c.sort_order,c.id""", args).use { c ->
            buildList { while (c.moveToNext()) add(LedgerCategory(c.getString(0), c.getString(1), Direction.valueOf(c.getString(2)),
                c.getString(3), c.getInt(4), c.getInt(5) == 1, c.getLong(6))) }
        }
    }
    fun list(db: SQLiteDatabase, direction: Direction? = null): List<LedgerCategory> =
        if (direction == null) select(db) else select(db, "WHERE c.direction=?", arrayOf(direction.name))
    fun find(db: SQLiteDatabase, id: String): LedgerCategory? = select(db, "WHERE c.id=?", arrayOf(id)).firstOrNull()
    fun byName(db: SQLiteDatabase, direction: Direction, name: String): LedgerCategory? = select(db, "WHERE c.direction=? AND c.name=?", arrayOf(direction.name, name)).firstOrNull()
    fun byNormalized(db: SQLiteDatabase, direction: Direction, name: String): LedgerCategory? = select(db, "WHERE c.direction=? AND c.normalized_name=?", arrayOf(direction.name, normalized(name))).firstOrNull()
    fun requireCategory(db: SQLiteDatabase, id: String, direction: Direction): LedgerCategory {
        val category = find(db, id) ?: throw CategoryException("CATEGORY_NOT_FOUND", "分类已不存在，请刷新后重试")
        if (category.direction != direction) throw CategoryException("CATEGORY_DIRECTION", "分类与账单收支方向不一致")
        return category
    }

    /** Compatibility registration preserves source text; it never maps an unfamiliar category to '其他'. */
    fun ensure(db: SQLiteDatabase, direction: Direction?, name: String): LedgerCategory? {
        if (direction !in setOf(Direction.EXPENSE, Direction.INCOME)) return null
        val type = requireNotNull(direction)
        byName(db, type, name)?.let { return it }
        val next = list(db, type).maxOfOrNull { it.order }?.plus(1) ?: 0
        val id = UUID.nameUUIDFromBytes("category:${type.name}:$name".toByteArray(Charsets.UTF_8)).toString()
        return LedgerCategory(id, name, type, order = next).also { insert(db, it) }
    }

    /** Only new recognition outputs use aliases. Migration and restore always retain exact names. */
    fun recognitionName(db: SQLiteDatabase, direction: Direction?, name: String): String {
        val alias = recognitionAliases[name] ?: return name
        val key = when (direction) {
            Direction.EXPENSE -> "cat:expense:$alias"
            Direction.INCOME -> "cat:income:other"
            else -> return name
        }
        return find(db, key)?.name ?: find(db, "cat:${direction.name.lowercase(Locale.ROOT)}:other")?.name ?: "其他"
    }

    fun save(db: SQLiteDatabase, id: String?, name: String, direction: Direction, icon: String): LedgerCategory {
        validateDirection(direction); validateName(name)
        if (icon !in icons) throw CategoryException("CATEGORY_INVALID", "请选择有效的分类图标")
        val previous = id?.takeIf(String::isNotBlank)?.let { requireCategory(db, it, direction) }
        if (previous?.isFallback == true && name != previous.name) throw CategoryException("CATEGORY_SYSTEM", "系统兜底分类不能改名")
        if (byNormalized(db, direction, name)?.let { it.id != previous?.id } == true)
            throw CategoryException("CATEGORY_DUPLICATE", "该方向已有同名分类")
        val result = LedgerCategory(previous?.id ?: UUID.randomUUID().toString(), name, direction, icon,
            previous?.order ?: (list(db, direction).maxOfOrNull { it.order }?.plus(1) ?: 0), previous?.isFallback ?: false)
        if (previous == null) {
            insert(db, result)
            db.insertOrThrow("custom_categories", null, ContentValues().apply { put("name", name); put("direction", direction.name) })
        } else {
            db.update("category_directory", values(result), "id=?", arrayOf(result.id))
            db.execSQL("UPDATE records SET category=? WHERE category_id=?", arrayOf(name, result.id))
            db.update("custom_categories", ContentValues().apply { put("name", name) }, "direction=? AND name=?", arrayOf(direction.name, previous.name))
        }
        return requireNotNull(find(db, result.id))
    }

    fun reorder(db: SQLiteDatabase, direction: Direction, ids: List<String>) {
        validateDirection(direction)
        val existing = list(db, direction).map { it.id }.toSet()
        if (ids.size != existing.size || ids.toSet() != existing) throw CategoryException("CATEGORY_INVALID", "分类顺序已变化，请刷新后重试")
        ids.forEachIndexed { index, id -> db.update("category_directory", ContentValues().apply { put("sort_order", index) }, "id=?", arrayOf(id)) }
    }

    fun remove(db: SQLiteDatabase, id: String) {
        val category = find(db, id) ?: throw CategoryException("CATEGORY_NOT_FOUND", "分类已不存在，请刷新后重试")
        if (category.isFallback) throw CategoryException("CATEGORY_SYSTEM", "系统兜底分类不能删除")
        if (category.referenceCount > 0) throw CategoryException("CATEGORY_REFERENCED", "该分类仍被 ${category.referenceCount} 笔账单引用，请先处理账单后再删除", category.referenceCount)
        db.delete("custom_categories", "direction=? AND name=?", arrayOf(category.direction.name, category.name))
        db.delete("category_directory", "id=?", arrayOf(id))
    }

    fun validateSnapshot(categories: List<LedgerCategory>) {
        require(categories.size <= 2_000) { "备份分类过多" }
        require(categories.map { it.id }.toSet().size == categories.size) { "分类标识重复" }
        require(categories.map { it.direction to it.name }.toSet().size == categories.size) { "分类名称重复" }
        categories.forEach { category ->
            validateDirection(category.direction)
            require(category.id.matches(Regex("[A-Za-z0-9:_-]{1,160}")) && category.name.length <= 1024 &&
                category.icon in icons && category.order in 0..1_000_000) { "分类目录格式无效" }
            val fallbackId = "cat:${category.direction.name.lowercase(Locale.ROOT)}:other"
            require(category.isFallback == (category.id == fallbackId)) { "分类兜底标识无效" }
            if (category.isFallback) require(category.name == "其他") { "系统兜底分类不能改名" }
            presets.firstOrNull { it.id == category.id }?.let { require(it.direction == category.direction) { "分类方向冲突" } }
        }
        if (categories.isNotEmpty()) for (direction in listOf(Direction.EXPENSE, Direction.INCOME))
            require(categories.count { it.direction == direction && it.isFallback } == 1) { "备份缺少系统兜底分类" }
    }

    /** Restore into a pristine directory reproduces its snapshot; merges keep local edits by identity. */
    fun merge(db: SQLiteDatabase, incoming: List<LedgerCategory>): Map<String, LedgerCategory> {
        validateSnapshot(incoming)
        if (incoming.isEmpty()) return emptyMap()
        val pristine = db.rawQuery("SELECT COUNT(*) FROM records", null).use { it.moveToFirst(); it.getLong(0) == 0L } &&
            list(db).map { it.copy(referenceCount = 0) }.toSet() == presets.toSet()
        if (pristine) {
            db.delete("category_directory", null, null)
            incoming.forEach { insert(db, it) }
            return incoming.associateBy { it.id }
        }
        return incoming.associate { category ->
            val existing = find(db, category.id)?.takeIf { it.direction == category.direction }
                ?: byName(db, category.direction, category.name)
            val target = existing ?: category.copy(
                id = if (find(db, category.id) == null) category.id else UUID.nameUUIDFromBytes("category-conflict:${category.direction}:${category.id}:${category.name}".toByteArray(Charsets.UTF_8)).toString(),
                order = (list(db, category.direction).maxOfOrNull { it.order } ?: -1) + 1,
            ).also { insert(db, it) }
            category.id to target
        }
    }

    private fun insert(db: SQLiteDatabase, category: LedgerCategory) = db.insertOrThrow("category_directory", null, values(category))
    private fun values(category: LedgerCategory) = ContentValues().apply {
        put("id", category.id); put("name", category.name); put("direction", category.direction.name)
        put("normalized_name", normalized(category.name)); put("icon", category.icon)
        put("sort_order", category.order); put("is_fallback", if (category.isFallback) 1 else 0)
    }
}
