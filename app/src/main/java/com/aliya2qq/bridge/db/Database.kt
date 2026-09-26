package com.aliya2qq.bridge.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.aliya2qq.bridge.util.BridgeLog
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite 持久化：QQ 与 Misskey 的个人/群绑定关系、桥接开关、MiAuth 待授权会话。
 */
class Database(dbPath: String) {
    private val lock = ReentrantLock()
    private val db: SQLiteDatabase

    init {
        File(dbPath).parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(File(dbPath), null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bindings (
                qq_number            TEXT PRIMARY KEY,
                misskey_token        TEXT NOT NULL,
                misskey_user_id      TEXT NOT NULL,
                misskey_username     TEXT,
                misskey_name         TEXT,
                current_session_id   TEXT,
                notify_enabled       INTEGER NOT NULL DEFAULT 1,
                dm_enabled           INTEGER NOT NULL DEFAULT 1,
                last_seen_message_id TEXT,
                created_at           REAL NOT NULL,
                updated_at           REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_bindings (
                group_id             TEXT PRIMARY KEY,
                misskey_token        TEXT NOT NULL,
                misskey_user_id      TEXT NOT NULL,
                misskey_username     TEXT,
                misskey_name         TEXT,
                current_session_id   TEXT,
                binder_qq            TEXT,
                notify_enabled       INTEGER NOT NULL DEFAULT 1,
                dm_enabled           INTEGER NOT NULL DEFAULT 1,
                awareness_enabled    INTEGER NOT NULL DEFAULT 0,
                last_seen_message_id TEXT,
                created_at           REAL NOT NULL,
                updated_at           REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS miauth_pending (
                session_id TEXT PRIMARY KEY,
                qq_number  TEXT NOT NULL,
                created_at REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_admins (
                group_id  TEXT NOT NULL,
                qq_number TEXT NOT NULL,
                created_at REAL NOT NULL,
                PRIMARY KEY (group_id, qq_number)
            )
            """.trimIndent()
        )
        try {
            db.execSQL(
                "ALTER TABLE group_bindings ADD COLUMN awareness_enabled INTEGER NOT NULL DEFAULT 0"
            )
        } catch (_: Exception) {
        }
        BridgeLog.i("数据库已就绪: $dbPath")
    }

    fun close() = lock.withLock { db.close() }

    private fun queryOne(sql: String, args: Array<String>): Map<String, Any?>? = lock.withLock {
        db.rawQuery(sql, args).use { c ->
            if (!c.moveToFirst()) return null
            val out = HashMap<String, Any?>()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                out[name] = when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    else -> c.getString(i)
                }
            }
            out
        }
    }

    private fun queryAll(sql: String, args: Array<String> = emptyArray()): List<Map<String, Any?>> =
        lock.withLock {
            db.rawQuery(sql, args).use { c ->
                val out = ArrayList<Map<String, Any?>>()
                while (c.moveToNext()) {
                    val row = HashMap<String, Any?>()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        row[name] = when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            else -> c.getString(i)
                        }
                    }
                    out.add(row)
                }
                out
            }
        }

    private fun exec(sql: String, args: Array<Any?>): Int = lock.withLock {
        val stmt = db.compileStatement(sql)
        args.forEachIndexed { i, a ->
            when (a) {
                null -> stmt.bindNull(i + 1)
                is Long -> stmt.bindLong(i + 1, a)
                is Int -> stmt.bindLong(i + 1, a.toLong())
                is Double -> stmt.bindDouble(i + 1, a)
                is Float -> stmt.bindDouble(i + 1, a.toDouble())
                is Boolean -> stmt.bindLong(i + 1, if (a) 1 else 0)
                else -> stmt.bindString(i + 1, a.toString())
            }
        }
        val n = stmt.executeUpdateDelete()
        stmt.close()
        n
    }

    // ---------- bindings ----------

    fun getBinding(qq: String): Map<String, Any?>? =
        queryOne("SELECT * FROM bindings WHERE qq_number = ?", arrayOf(qq.toString()))

    fun getBindingByUserId(misskeyUserId: String): Map<String, Any?>? =
        queryOne(
            "SELECT * FROM bindings WHERE misskey_user_id = ? ORDER BY created_at ASC LIMIT 1",
            arrayOf(misskeyUserId)
        )

    fun getAllBindings(): List<Map<String, Any?>> = queryAll("SELECT * FROM bindings")

    fun upsertBinding(qq: String, token: String, userId: String, username: String, name: String) {
        val now = System.currentTimeMillis() / 1000.0
        lock.withLock {
            val cv = ContentValues().apply {
                put("qq_number", qq.toString())
                put("misskey_token", token)
                put("misskey_user_id", userId)
                put("misskey_username", username)
                put("misskey_name", name)
                put("created_at", now)
                put("updated_at", now)
            }
            val existing = getBinding(qq.toString())
            if (existing == null) db.insert("bindings", null, cv)
            else {
                cv.remove("created_at")
                db.update("bindings", cv, "qq_number = ?", arrayOf(qq.toString()))
            }
        }
        BridgeLog.i("绑定已保存: QQ $qq -> @$username")
    }

    fun deleteBinding(qq: String): Boolean {
        val n = exec("DELETE FROM bindings WHERE qq_number = ?", arrayOf(qq.toString()))
        if (n > 0) BridgeLog.i("绑定已删除: QQ $qq")
        return n > 0
    }

    fun setCurrentSession(qq: String, sessionId: String?) {
        val now = System.currentTimeMillis() / 1000.0
        exec(
            "UPDATE bindings SET current_session_id = ?, updated_at = ? WHERE qq_number = ?",
            arrayOf(sessionId, now, qq.toString())
        )
    }

    fun setLastSeenMessage(qq: String, messageId: String) {
        exec(
            "UPDATE bindings SET last_seen_message_id = ? WHERE qq_number = ?",
            arrayOf(messageId, qq.toString())
        )
    }

    fun setFlag(qq: String, key: String, value: Boolean): Boolean {
        require(key in listOf("notify_enabled", "dm_enabled")) { "不支持的开关: $key" }
        val now = System.currentTimeMillis() / 1000.0
        return exec(
            "UPDATE bindings SET $key = ?, updated_at = ? WHERE qq_number = ?",
            arrayOf(if (value) 1 else 0, now, qq.toString())
        ) > 0
    }

    // ---------- group bindings ----------

    fun getGroupBinding(groupId: String): Map<String, Any?>? =
        queryOne("SELECT * FROM group_bindings WHERE group_id = ?", arrayOf(groupId.toString()))

    fun getAllGroupBindings(): List<Map<String, Any?>> = queryAll("SELECT * FROM group_bindings")

    fun upsertGroupBinding(
        groupId: String,
        token: String,
        userId: String,
        username: String,
        name: String,
        binderQq: String,
    ) {
        val now = System.currentTimeMillis() / 1000.0
        lock.withLock {
            val cv = ContentValues().apply {
                put("group_id", groupId.toString())
                put("misskey_token", token)
                put("misskey_user_id", userId)
                put("misskey_username", username)
                put("misskey_name", name)
                put("binder_qq", binderQq.toString())
                put("created_at", now)
                put("updated_at", now)
            }
            val existing = getGroupBinding(groupId.toString())
            if (existing == null) db.insert("group_bindings", null, cv)
            else {
                cv.remove("created_at")
                db.update("group_bindings", cv, "group_id = ?", arrayOf(groupId.toString()))
            }
        }
        BridgeLog.i("群绑定已保存: 群 $groupId -> @$username (绑定者 $binderQq)")
    }

    fun deleteGroupBinding(groupId: String): Boolean {
        val n = exec("DELETE FROM group_bindings WHERE group_id = ?", arrayOf(groupId.toString()))
        if (n > 0) BridgeLog.i("群绑定已删除: 群 $groupId")
        return n > 0
    }

    fun setGroupCurrentSession(groupId: String, sessionId: String?) {
        val now = System.currentTimeMillis() / 1000.0
        exec(
            "UPDATE group_bindings SET current_session_id = ?, updated_at = ? WHERE group_id = ?",
            arrayOf(sessionId, now, groupId.toString())
        )
    }

    fun setGroupLastSeenMessage(groupId: String, messageId: String) {
        exec(
            "UPDATE group_bindings SET last_seen_message_id = ? WHERE group_id = ?",
            arrayOf(messageId, groupId.toString())
        )
    }

    fun setGroupFlag(groupId: String, key: String, value: Boolean): Boolean {
        require(key in listOf("notify_enabled", "dm_enabled", "awareness_enabled")) {
            "不支持的开关: $key"
        }
        val now = System.currentTimeMillis() / 1000.0
        return exec(
            "UPDATE group_bindings SET $key = ?, updated_at = ? WHERE group_id = ?",
            arrayOf(if (value) 1 else 0, now, groupId.toString())
        ) > 0
    }

    // ---------- group admins ----------

    fun getGroupAdmins(groupId: String): List<String> =
        queryAll(
            "SELECT qq_number FROM group_admins WHERE group_id = ? ORDER BY created_at ASC",
            arrayOf(groupId.toString())
        ).mapNotNull { it["qq_number"]?.toString() }

    fun addGroupAdmin(groupId: String, qq: String): Boolean {
        val now = System.currentTimeMillis() / 1000.0
        return lock.withLock {
            val exists = queryOne(
                "SELECT 1 FROM group_admins WHERE group_id = ? AND qq_number = ?",
                arrayOf(groupId.toString(), qq.toString())
            ) != null
            if (exists) return false
            val cv = ContentValues().apply {
                put("group_id", groupId.toString())
                put("qq_number", qq.toString())
                put("created_at", now)
            }
            db.insert("group_admins", null, cv) > 0
        }
    }

    fun removeGroupAdmin(groupId: String, qq: String): Boolean =
        exec(
            "DELETE FROM group_admins WHERE group_id = ? AND qq_number = ?",
            arrayOf(groupId.toString(), qq.toString())
        ) > 0

    // ---------- miauth pending ----------

    fun addMiauthPending(sessionId: String, qq: String) {
        lock.withLock {
            val cv = ContentValues().apply {
                put("session_id", sessionId)
                put("qq_number", qq.toString())
                put("created_at", System.currentTimeMillis() / 1000.0)
            }
            db.insertWithOnConflict(
                "miauth_pending", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    fun getMiauthPending(sessionId: String): Map<String, Any?>? =
        queryOne("SELECT * FROM miauth_pending WHERE session_id = ?", arrayOf(sessionId))

    fun deleteMiauthPending(sessionId: String) {
        exec("DELETE FROM miauth_pending WHERE session_id = ?", arrayOf(sessionId))
    }
}

/** DB 行的字段读取扩展函数。 */
fun Map<String, Any?>.str(key: String): String = this[key]?.toString() ?: ""
fun Map<String, Any?>.strOrNull(key: String): String? = this[key]?.toString()
fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean {
    val v = this[key] ?: return default
    return when (v) {
        is Number -> v.toInt() != 0
        is Boolean -> v
        else -> v.toString().let { it == "1" || it.equals("true", true) }
    }
}
