package com.ep.donwnloader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------- 数据模型 ----------------

data class MediaItem(
    val id: Long, val postRowId: Long, val mediaIndex: Int, val mediaType: String,
    val filePath: String, val thumbPath: String, val ext: String, val fileSize: Long,
    val width: Int, val height: Int, val sourceUrl: String,
    val platform: String, val postId: String, val postUrl: String, val postTime: String,
    val postText: String, val handle: String, val authorName: String,
    val avatarUrl: String, val profileUrl: String,
    val deletedAt: String = "",
    /** 来源：''=本 App 自下载，'edqiu'=从 Edqiu 同步导入。 */
    val origin: String = "",
    /** 文件落盘时刻（UTC ISO）。素材库日期手风琴按此分组（帖子组取组内 MAX）。 */
    val downloadedAt: String = "",
)

data class AuthorItem(
    val id: Long, val platform: String, val handle: String, val name: String,
    val avatarUrl: String, val profileUrl: String, val mediaCount: Int,
    val latestTime: String, val items: List<MediaItem>,
)

data class TaskItem(
    val id: Long, val url: String, val platform: String, val kind: String,
    val status: String, val error: String, val message: String,
    val totalFiles: Int, val doneFiles: Int, val skipped: Int, val bytes: Long,
    val createdAt: String, val finishedAt: String,
)

data class ProxyCand(
    val id: Long, val type: String, val host: String, val port: Int, val source: String,
    var latencyMs: Double?, var speedMbps: Double?, var score: Double, var alive: Boolean,
    var lastTested: String,
    /** "user:pass" 原文（不持久化，仅手动代理带认证时由 detect 现取）；展示时不外泄。 */
    var auth: String = "",
) {
    /** 实际连接用 URL（认证部分 URL 编码）；direct 为 null。 */
    fun url(): String? {
        if (type == "direct") return null
        val creds = if (auth.isBlank()) "" else {
            val i = auth.indexOf(':')
            val u = if (i < 0) auth else auth.substring(0, i)
            val p = if (i < 0) "" else auth.substring(i + 1)
            java.net.URLEncoder.encode(u, "UTF-8") +
                (if (p.isBlank()) "" else ":" + java.net.URLEncoder.encode(p, "UTF-8")) + "@"
        }
        return "$type://$creds$host:$port"
    }
    /** 对外展示（不含认证）。 */
    fun label(): String = if (type == "direct") "直连" else "$type://$host:$port"
}

data class ProxyLogItem(val id: Long, val ts: String, val event: String, val detail: String)

data class Stats(val total: Int, val bytes: Long, val authors: Int,
                 val byPlatform: Map<String, Int>, val byType: Map<String, Int> = emptyMap())

/** Edqiu 导入条目（启动巡检的时间戳修正用）。 */
data class ImportedMediaRow(val id: Long, val filePath: String, val downloadedAt: String, val postRowId: Long)

data class InboxItem(
    val id: Long, val url: String, val platform: String, val kind: String,
    val handle: String, val name: String, val avatarUrl: String, val text: String,
    val mediaCount: Int, val status: String, val error: String, val taskId: Long,
    val capturedAt: String, val previewUrl: String,
)

// ---------------- 工具 ----------------

fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

fun toIso(v: String?): String {
    if (v.isNullOrBlank()) return ""
    return try {
        // 兼容 "2026-09-05T01:19:16.451Z" / "2026-09-05 01:19:16" / 纪元秒
        val clean = v.trim().replace(" ", "T")
        val parsed = if (clean.all { it.isDigit() }) {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(clean.toLong() * 1000))
        } else {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.format(sdf.parse(clean.substring(0, 19))!!)
        }
        parsed + "Z"
    } catch (e: Exception) {
        ""
    }
}

// ---------------- SQLite ----------------

class Db(context: Context) : SQLiteOpenHelper(context, "app.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE authors(
            id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, handle TEXT NOT NULL,
            platform_uid TEXT DEFAULT '',
            name TEXT DEFAULT '', avatar_url TEXT DEFAULT '', profile_url TEXT DEFAULT '',
            UNIQUE(platform, handle))""")
        db.execSQL(
            """CREATE TABLE posts(
            id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, post_id TEXT NOT NULL,
            post_url TEXT DEFAULT '', author_id INTEGER, created_at TEXT DEFAULT '', text TEXT DEFAULT '',
            UNIQUE(platform, post_id))""")
        db.execSQL(
            """CREATE TABLE media(
            id INTEGER PRIMARY KEY AUTOINCREMENT, post_row_id INTEGER NOT NULL,
            media_index INTEGER NOT NULL DEFAULT 0, media_type TEXT DEFAULT 'photo',
            file_path TEXT DEFAULT '', thumb_path TEXT DEFAULT '', ext TEXT DEFAULT '',
            file_size INTEGER DEFAULT 0, width INTEGER DEFAULT 0, height INTEGER DEFAULT 0,
            source_url TEXT DEFAULT '', downloaded_at TEXT DEFAULT '',
            content_hash TEXT DEFAULT '', origin TEXT DEFAULT '',
            deleted INTEGER DEFAULT 0, deleted_at TEXT DEFAULT '',
            UNIQUE(post_row_id, media_index))""")
        db.execSQL(
            """CREATE TABLE tasks(
            id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, platform TEXT DEFAULT '',
            kind TEXT DEFAULT '', status TEXT DEFAULT 'queued', error TEXT DEFAULT '',
            message TEXT DEFAULT '', total_files INTEGER DEFAULT 0, done_files INTEGER DEFAULT 0,
            skipped INTEGER DEFAULT 0, bytes INTEGER DEFAULT 0,
            created_at TEXT DEFAULT '', finished_at TEXT DEFAULT '')""")
        db.execSQL(
            """CREATE TABLE proxy_candidates(
            id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, host TEXT NOT NULL,
            port INTEGER NOT NULL, source TEXT DEFAULT '', latency_ms REAL, speed_mbps REAL,
            score REAL DEFAULT 0, alive INTEGER DEFAULT 0, last_tested TEXT DEFAULT '',
            UNIQUE(type, host, port))""")
        db.execSQL(
            """CREATE TABLE proxy_log(
            id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT DEFAULT '',
            event TEXT, detail TEXT)""")
        createInbox(db)
        ensureV5(db)  // 幂等：别名表 + 索引（media.content_hash 已在上面建列）
    }

    /** 检查列是否存在。 */
    private fun colExists(db: SQLiteDatabase, table: String, col: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            var found = false
            while (c.moveToNext()) if (c.getString(nameIdx) == col) { found = true; break }
            found
        }

    /**
     * v5 结构自愈（幂等，onCreate/onUpgrade/onOpen 三处调用）：
     * authors.platform_uid、media.content_hash、author_aliases 表、两个索引。
     * onOpen 调用是为了救回"版本号已到 5 但 DDL 因故未落盘"的库（2026-09-10 真机实况），
     * 也防未来任何来源的版本/结构错位——缺列才 ALTER，存在则零开销。
     */
    fun ensureV5(db: SQLiteDatabase) {
        if (!colExists(db, "authors", "platform_uid"))
            db.execSQL("ALTER TABLE authors ADD COLUMN platform_uid TEXT DEFAULT ''")
        if (!colExists(db, "media", "content_hash"))
            db.execSQL("ALTER TABLE media ADD COLUMN content_hash TEXT DEFAULT ''")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS author_aliases(
            id INTEGER PRIMARY KEY AUTOINCREMENT, platform TEXT NOT NULL, handle TEXT NOT NULL,
            author_id INTEGER NOT NULL, UNIQUE(platform, handle))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_hash ON media(content_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_authors_uid ON authors(platform, platform_uid)")
    }

    private fun createInbox(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS inbox(
            id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE,
            platform TEXT DEFAULT '', kind TEXT DEFAULT '',
            handle TEXT DEFAULT '', name TEXT DEFAULT '', avatar_url TEXT DEFAULT '',
            text TEXT DEFAULT '', media_count INTEGER DEFAULT 0,
            status TEXT DEFAULT 'captured', error TEXT DEFAULT '', task_id INTEGER DEFAULT 0,
            captured_at TEXT DEFAULT '', preview_url TEXT DEFAULT '')""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            createInbox(db)
            db.execSQL("ALTER TABLE media ADD COLUMN deleted INTEGER DEFAULT 0")
        }
        if (oldV < 3) {
            // preview_url 放末列：新表 CREATE 与 ALTER 迁移后的列序一致
            db.execSQL("ALTER TABLE inbox ADD COLUMN preview_url TEXT DEFAULT ''")
        }
        if (oldV < 4) {
            // 回收站删除时间（v4 新装由 onCreate 直接建列；旧库 ALTER 补列）
            db.execSQL("ALTER TABLE media ADD COLUMN deleted_at TEXT DEFAULT ''")
        }
        if (oldV < 5) {
            // v5：作者稳定 UID + 内容 hash + 改名别名表（ensureV5 幂等执行）
            ensureV5(db)
        }
        if (oldV < 6) {
            // v6：media.origin 来源标记（Edqiu 导入识别），ensureV6 幂等执行
            ensureV6(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        // 结构自愈：版本号与 schema 错位的库（如 v5 号 + v4 结构）打开即修复
        ensureV5(db)
        ensureV6(db)
    }

    /** v6 结构自愈（幂等）：media.origin 素材来源标记（''=自下载，'edqiu'=Edqiu 导入）。 */
    fun ensureV6(db: SQLiteDatabase) {
        if (!colExists(db, "media", "origin"))
            db.execSQL("ALTER TABLE media ADD COLUMN origin TEXT DEFAULT ''")
    }

    // ---------- authors / posts / media ----------

    /**
     * 作者归并（2026-09-10 v5 重写）：UID（X id_str / IG pk / BSky DID）优先——
     * ① UID 命中已有行 → 同一作者：handle 变了视为改名，旧名写入 author_aliases，
     *    作者行更新为新 handle（新 handle 若已被他人占用则保持原名，不抢占）；
     * ② handle 命中 → 回填空缺的 UID + 更新资料；
     * ③ 都未命中 → 新建。改名后下载自动归组，素材库不再裂成两个作者。
     */
    fun upsertAuthor(platform: String, handle: String, name: String, avatar: String,
                     profile: String, uid: String = ""): Long {
        val db = writableDatabase
        if (uid.isNotBlank()) {
            readableDatabase.rawQuery(
                "SELECT id, handle FROM authors WHERE platform=? AND platform_uid=? LIMIT 1",
                arrayOf(platform, uid)).use { c ->
                if (c.moveToFirst()) {
                    val aid = c.getLong(0)
                    val old = c.getString(1) ?: ""
                    if (old.isNotBlank() && old != handle) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO author_aliases(platform, handle, author_id) VALUES(?,?,?)",
                            arrayOf(platform, old, aid))
                        // 新 handle 未被其他行占用才更名（撞名极罕见，保持旧行不动）
                        db.execSQL(
                            """UPDATE authors SET handle=?
                               WHERE id=? AND NOT EXISTS(
                                 SELECT 1 FROM authors a2
                                 WHERE a2.platform=? AND a2.handle=? AND a2.id!=?)""",
                            arrayOf(handle, aid, platform, handle, aid))
                    }
                    db.execSQL(
                        """UPDATE authors SET
                          name = CASE WHEN ?!='' THEN ? ELSE name END,
                          avatar_url = CASE WHEN ?!='' THEN ? ELSE avatar_url END,
                          profile_url = CASE WHEN ?!='' THEN ? ELSE profile_url END
                          WHERE id=?""",
                        arrayOf(name, name, avatar, avatar, profile, profile, aid))
                    return aid
                }
            }
        }
        val hitId = readableDatabase.rawQuery(
            "SELECT id FROM authors WHERE platform=? AND handle=? LIMIT 1",
            arrayOf(platform, handle)).use { if (it.moveToFirst()) it.getLong(0) else -1L }
        if (hitId > 0) {
            if (uid.isNotBlank()) {
                db.execSQL("UPDATE authors SET platform_uid=? WHERE id=? AND platform_uid=''",
                    arrayOf(uid, hitId))
            }
            db.execSQL(
                """UPDATE authors SET
                  name = CASE WHEN ?!='' THEN ? ELSE name END,
                  avatar_url = CASE WHEN ?!='' THEN ? ELSE avatar_url END,
                  profile_url = CASE WHEN ?!='' THEN ? ELSE profile_url END
                  WHERE id=?""",
                arrayOf(name, name, avatar, avatar, profile, profile, hitId))
            return hitId
        }
        db.execSQL(
            """INSERT INTO authors(platform, handle, platform_uid, name, avatar_url, profile_url)
               VALUES(?,?,?,?,?,?)""",
            arrayOf(platform, handle, uid, name, avatar, profile))
        return readableDatabase.rawQuery(
            "SELECT id FROM authors WHERE platform=? AND handle=?", arrayOf(platform, handle)).use {
            it.moveToFirst(); it.getLong(0)
        }
    }

    fun upsertPost(platform: String, postId: String, postUrl: String, authorId: Long, createdAt: String, text: String): Long {
        writableDatabase.execSQL(
            """INSERT INTO posts(platform, post_id, post_url, author_id, created_at, text) VALUES(?,?,?,?,?,?)
            ON CONFLICT(platform, post_id) DO UPDATE SET
              post_url = CASE WHEN excluded.post_url!='' THEN excluded.post_url ELSE posts.post_url END,
              created_at = CASE WHEN excluded.created_at!='' THEN excluded.created_at ELSE posts.created_at END""",
            arrayOf(platform, postId, postUrl, authorId, createdAt, text))
        return readableDatabase.rawQuery(
            "SELECT id FROM posts WHERE platform=? AND post_id=?", arrayOf(platform, postId)).use {
            it.moveToFirst(); it.getLong(0)
        }
    }

    /** mediaIndex 传 -1 = 自动分配（nextMediaIndex：同帖现有最大值+1，回收站条目也占位）。
     *  contentHash 下载链路必传；外部导入可为空（省去大文件全量 IO）。 */
    fun addMedia(postRowId: Long, mediaIndex: Int, mediaType: String, filePath: String,
                 ext: String, fileSize: Long, width: Int, height: Int, sourceUrl: String,
                 thumbPath: String, contentHash: String = "", origin: String = "",
                 downloadedAt: String = ""): Boolean {
        val idx = if (mediaIndex >= 0) mediaIndex else nextMediaIndex(postRowId)
        val cv = ContentValues().apply {
            put("post_row_id", postRowId); put("media_index", idx)
            put("media_type", mediaType); put("file_path", filePath)
            put("thumb_path", thumbPath); put("ext", ext); put("file_size", fileSize)
            put("width", width); put("height", height);             put("source_url", sourceUrl)
            put("content_hash", contentHash)
            put("origin", origin)
            // downloadedAt 空 = 现在（下载完成）；外部导入传文件真实落盘时刻，时间线不因导入动作浮顶
            put("downloaded_at", downloadedAt.ifBlank { nowIso() })
        }
        return writableDatabase.insertWithOnConflict(
            "media", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** 同帖下一个可用 media_index（含回收站条目占用，防复活冲突）。 */
    fun nextMediaIndex(postRowId: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(media_index),-1)+1 FROM media WHERE post_row_id=?",
            arrayOf(postRowId.toString())).use { it.moveToFirst(); it.getInt(0) }

    /** 内容 hash 查重（IG p/reel 裂帖等场景归并依据）。返回 (mediaId, postRowId, deleted)；null=无重复。 */
    fun findByHash(hash: String): Triple<Long, Long, Boolean>? {
        if (hash.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT id, post_row_id, deleted FROM media WHERE content_hash=? LIMIT 1",
            arrayOf(hash)).use {
            if (it.moveToFirst()) Triple(it.getLong(0), it.getLong(1), it.getInt(2) == 1) else null
        }
    }

    /** 重下复用已有 DB 行（文件丢失重下 / hash 命中顶替）：更新文件字段 + 下载时间（排序浮顶）。
     *  旧文件若在别处且仍存在则顺带清掉，防孤儿。 */
    fun updateMediaRow(id: Long, filePath: String, fileSize: Long, thumbPath: String, contentHash: String) {
        val old = readableDatabase.rawQuery(
            "SELECT file_path FROM media WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }
        if (old.isNotBlank() && old != filePath && java.io.File(old).exists()) {
            java.io.File(old).delete()
        }
        val cv = ContentValues().apply {
            put("file_path", filePath); put("file_size", fileSize)
            put("thumb_path", thumbPath); put("content_hash", contentHash)
            put("downloaded_at", nowIso())
        }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    /** 回写缩略图路径（视频封面惰性补生成后调用）。 */
    fun updateMediaThumb(id: Long, thumbPath: String) {
        val cv = ContentValues().apply { put("thumb_path", thumbPath) }
        writableDatabase.update("media", cv, "id=?", arrayOf(id.toString()))
    }

    /**
     * 三态判重（2026-09-10 双重验证改造）：不再只认 DB——
     * null            = 未下载过（走正常下载）；
     * deleted=false   = 已下载（文件是否还在由调用方核验，文件丢了要重下）；
     * deleted=true    = 在回收站（命中即恢复，用户重下意图优先）。
     */
    data class MediaCheck(val rowId: Long, val filePath: String, val deleted: Boolean)

    fun mediaCheck(postRowId: Long, mediaIndex: Int): MediaCheck? =
        readableDatabase.rawQuery(
            "SELECT id, file_path, deleted FROM media WHERE post_row_id=? AND media_index=?",
            arrayOf(postRowId.toString(), mediaIndex.toString())).use {
            if (it.moveToFirst())
                MediaCheck(it.getLong(0), it.getString(1) ?: "", it.getInt(2) == 1)
            else null
        }

    /** 按文件绝对路径判重（外部目录同步用）。 */
    fun mediaPathRegistered(path: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM media WHERE source_url=? LIMIT 1", arrayOf(path)).use { it.moveToFirst() }

    // ---------- 存量数据巡检回填（2026-09-10：轻量用户"逛到才下"，UID/hash 不能等自然下载激活） ----------

    /** 空 UID 的作者行：(id, platform, handle)。 */
    fun authorsWithoutUid(): List<Triple<Long, String, String>> =
        readableDatabase.rawQuery(
            """SELECT id, platform, handle FROM authors
               WHERE (platform_uid='' OR (platform='twitter' AND avatar_url=''))
                 AND platform!='local' AND handle NOT IN ('unknown','导入素材')
               ORDER BY id""", null).use { cur ->
            val out = mutableListOf<Triple<Long, String, String>>()
            while (cur.moveToNext()) out.add(Triple(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: ""))
            out
        }

    fun updateAuthorUid(id: Long, uid: String) {
        writableDatabase.execSQL(
            "UPDATE authors SET platform_uid=? WHERE id=? AND platform_uid=''",
            arrayOf(uid, id))
    }

    /** 回填作者资料：UID 必写；name/avatar 非空才覆盖（fxtwitter 为实时权威值，共享导入作者补头像与最新显示名）。 */
    fun updateAuthorProfile(id: Long, uid: String, name: String, avatar: String) {
        writableDatabase.execSQL(
            """UPDATE authors SET platform_uid=?,
               name = CASE WHEN ?!='' THEN ? ELSE name END,
               avatar_url = CASE WHEN ?!='' THEN ? ELSE avatar_url END
               WHERE id=?""",
            arrayOf(uid, name, name, avatar, avatar, id))
    }

    /** 头像变体升级（零网络，幂等）：X CDN 的 _normal 是 48px 小图，统一换成 _400x400 高清变体。 */
    fun upgradeAvatarVariants(): Int {
        writableDatabase.execSQL(
            """UPDATE authors SET avatar_url = REPLACE(avatar_url, '_normal.', '_400x400.')
               WHERE avatar_url LIKE '%_normal.%'""")
        return readableDatabase.rawQuery(
            "SELECT changes()", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ---------- 导入时间戳修正（启动巡检） ----------

    /** Edqiu 导入条目（含回收站）。 */
    fun edqiuImportedRows(): List<ImportedMediaRow> =
        readableDatabase.rawQuery(
            "SELECT id, file_path, downloaded_at, post_row_id FROM media WHERE origin='edqiu'", null).use { cur ->
            val out = mutableListOf<ImportedMediaRow>()
            while (cur.moveToNext()) out.add(ImportedMediaRow(
                cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getLong(3)))
            out
        }

    /** post 行 (created_at, post_id, platform)。 */
    fun postById(rowId: Long): Triple<String, String, String>? =
        readableDatabase.rawQuery(
            "SELECT created_at, post_id, platform FROM posts WHERE id=?",
            arrayOf(rowId.toString())).use {
            if (it.moveToFirst()) Triple(it.getString(0) ?: "", it.getString(1) ?: "", it.getString(2) ?: "")
            else null
        }

    fun updateMediaDownloadedAt(id: Long, iso: String) {
        writableDatabase.execSQL("UPDATE media SET downloaded_at=? WHERE id=?", arrayOf(iso, id))
    }

    fun updatePostCreatedAt(id: Long, iso: String) {
        writableDatabase.execSQL("UPDATE posts SET created_at=? WHERE id=?", arrayOf(iso, id))
    }

    /** hash 为空的素材行：(id, file_path)。含回收站条目（其 hash 参与"恢复+顶替"闸门判定）。 */
    fun mediaWithoutHash(): List<Pair<Long, String>> =
        readableDatabase.rawQuery(
            "SELECT id, file_path FROM media WHERE content_hash='' AND file_path!='' ORDER BY id", null).use { cur ->
            val out = mutableListOf<Pair<Long, String>>()
            while (cur.moveToNext()) out.add(cur.getLong(0) to (cur.getString(1) ?: ""))
            out
        }

    fun updateMediaHash(id: Long, hash: String) {
        if (hash.isBlank()) return
        writableDatabase.execSQL(
            "UPDATE media SET content_hash=? WHERE id=? AND content_hash=''",
            arrayOf(hash, id))
    }

    private val mediaCols = "m.id, m.post_row_id, m.media_index, m.media_type, m.file_path, m.thumb_path, m.ext, m.file_size, m.width, m.height, m.source_url, p.platform, p.post_id, p.post_url, p.created_at, p.text, a.handle, a.name, a.avatar_url, a.profile_url, m.deleted_at, m.origin, m.downloaded_at"

    private fun mediaRow(c: android.database.Cursor): MediaItem = MediaItem(
        c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5),
        c.getString(6), c.getLong(7), c.getInt(8), c.getInt(9), c.getString(10),
        c.getString(11), c.getString(12), c.getString(13), c.getString(14), c.getString(15),
        c.getString(16), c.getString(17), c.getString(18), c.getString(19), c.getString(20) ?: "",
        c.getString(21) ?: "", c.getString(22) ?: "")

    fun listMediaTime(platform: String, type: String, q: String, authorId: Long?, offset: Int, limit: Int,
                      asc: Boolean = false, origin: String = ""): List<MediaItem> {
        val where = StringBuilder(" FROM media m JOIN posts p ON p.id=m.post_row_id JOIN authors a ON a.id=p.author_id WHERE m.deleted=0")
        val args = mutableListOf<String>()
        if (platform.isNotBlank()) { where.append(" AND p.platform=?"); args.add(platform) }
        if (type.isNotBlank()) { where.append(" AND m.media_type=?"); args.add(type) }
        if (authorId != null) { where.append(" AND p.author_id=?"); args.add(authorId.toString()) }
        if (origin.isNotBlank()) { where.append(" AND m.origin=?"); args.add(origin) }
        if (q.isNotBlank()) {
            where.append(" AND (p.text LIKE ? OR a.handle LIKE ? OR a.name LIKE ?)")
            val like = "%$q%"; args.add(like); args.add(like); args.add(like)
        }
        // 时间视图排序（2026-09-10 用户定型）：按【下载时间】倒序——以帖子的最新下载时间分组，
        // 同帖多图保持成组且组内 media_index 正序；同刻下载的帖子按发帖时间倒序；p.id 作最终稳定序
        val newestDl = "(SELECT MAX(m2.downloaded_at) FROM media m2 " +
            "WHERE m2.post_row_id = m.post_row_id AND m2.deleted = 0)"
        val order = if (asc) "$newestDl ASC, p.created_at ASC, p.id ASC, m.media_index ASC"
                    else "$newestDl DESC, p.created_at DESC, p.id DESC, m.media_index ASC"
        val sql = "SELECT $mediaCols$where ORDER BY $order LIMIT ? OFFSET ?"
        args.add(limit.toString()); args.add(offset.toString())
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { cur ->
            val out = mutableListOf<MediaItem>()
            while (cur.moveToNext()) out.add(mediaRow(cur))
            out
        }
    }

    fun listAuthors(platform: String, q: String, offset: Int, limit: Int, perAuthor: Int = 12,
                    origin: String = ""): List<AuthorItem> {
        val where = StringBuilder(
            " FROM authors a JOIN posts p ON p.author_id=a.id JOIN media m ON m.post_row_id=p.id WHERE m.deleted=0")
        val args = mutableListOf<String>()
        if (platform.isNotBlank()) { where.append(" AND a.platform=?"); args.add(platform) }
        if (origin.isNotBlank()) { where.append(" AND m.origin=?"); args.add(origin) }
        if (q.isNotBlank()) {
            // 搜索含改名别名：作者改名后，旧昵称也能搜到
            where.append(" AND (a.handle LIKE ? OR a.name LIKE ? OR EXISTS(" +
                "SELECT 1 FROM author_aliases al WHERE al.author_id=a.id AND al.handle LIKE ?))")
            val like = "%$q%"; args.add(like); args.add(like); args.add(like)
        }
        val rows = readableDatabase.rawQuery(
            """SELECT a.id, a.platform, a.handle, a.name, a.avatar_url, a.profile_url,
               COUNT(m.id) AS cnt, MAX(p.created_at) AS latest$where
               GROUP BY a.id ORDER BY latest DESC LIMIT ? OFFSET ?""",
            (args + listOf(limit.toString(), offset.toString())).toTypedArray()).use { cur ->
            val out = mutableListOf<AuthorItem>()
            while (cur.moveToNext()) {
                out.add(AuthorItem(cur.getLong(0), cur.getString(1), cur.getString(2), cur.getString(3),
                    cur.getString(4), cur.getString(5), cur.getInt(6), cur.getString(7) ?: "", emptyList()))
            }
            out
        }
        return rows.map { a -> a.copy(items = listMediaTime("", "", "", a.id, 0, perAuthor)) }
    }

    fun getMedia(id: Long): MediaItem? =
        readableDatabase.rawQuery(
            "SELECT $mediaCols FROM media m JOIN posts p ON p.id=m.post_row_id JOIN authors a ON a.id=p.author_id WHERE m.id=?",
            arrayOf(id.toString())).use { if (it.moveToFirst()) mediaRow(it) else null }

    /** 软删除：移入回收站（保留文件，记录删除时间）。 */
    fun deleteMedia(id: Long): Boolean {
        writableDatabase.execSQL(
            "UPDATE media SET deleted=1, deleted_at=? WHERE id=?", arrayOf(nowIso(), id))
        return true
    }

    /** 批量软删除。 */
    fun deleteMediaAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE media SET deleted=1, deleted_at=? WHERE id IN (${ids.joinToString(",")})",
            arrayOf(nowIso()))
    }

    fun restoreMedia(id: Long) {
        writableDatabase.execSQL("UPDATE media SET deleted=0, deleted_at='' WHERE id=?", arrayOf(id))
    }

    fun restoreAll() {
        writableDatabase.execSQL("UPDATE media SET deleted=0, deleted_at='' WHERE deleted=1")
    }

    /** 回收站全部行：id → (file_path, thumb_path)。文件由调用方先删，成功后才 hardDeleteMedia。 */
    fun deletedRows(): List<Triple<Long, String, String>> {
        val out = mutableListOf<Triple<Long, String, String>>()
        readableDatabase.rawQuery(
            "SELECT id, file_path, thumb_path FROM media WHERE deleted=1", null).use { cur ->
            while (cur.moveToNext()) {
                out.add(Triple(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: ""))
            }
        }
        return out
    }

    /** 硬删行（仅在实际文件删除成功后调用，防孤儿）。 */
    fun hardDeleteMedia(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "DELETE FROM media WHERE id IN (${ids.joinToString(",")})")
    }

    /** 绝对路径是否已被登记为 file_path / thumb_path（残留清理判定用）。 */
    fun diskPathRegistered(path: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM media WHERE file_path=? OR thumb_path=? LIMIT 1",
            arrayOf(path, path)).use { it.moveToFirst() }

    fun listDeleted(): List<MediaItem> =
        readableDatabase.rawQuery(
            _MEDIA_SELECT_DELETED + " ORDER BY m.id DESC LIMIT 200", null).use { cur ->
            val out = mutableListOf<MediaItem>()
            while (cur.moveToNext()) out.add(mediaRow(cur))
            out
        }

    private val _MEDIA_SELECT_DELETED =
        """SELECT m.id, m.post_row_id, m.media_index, m.media_type, m.file_path, m.thumb_path,
        m.ext, m.file_size, m.width, m.height, m.source_url,
        p.platform, p.post_id, p.post_url, p.created_at, p.text,
        a.handle, a.name, a.avatar_url, a.profile_url, m.deleted_at, m.origin, m.downloaded_at
        FROM media m JOIN posts p ON p.id=m.post_row_id JOIN authors a ON a.id=p.author_id
        WHERE m.deleted=1"""

    fun deletedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM media WHERE deleted=1", null).use {
            it.moveToFirst(); it.getInt(0)
        }

    /** 回收站占用空间（字节）。 */
    fun deletedBytes(): Long =
        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(file_size),0) FROM media WHERE deleted=1", null).use {
            it.moveToFirst(); it.getLong(0)
        }

    fun stats(): Stats {
        val byPlatform = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            """SELECT p.platform, COUNT(*) FROM media m JOIN posts p ON p.id=m.post_row_id
            WHERE m.deleted=0 GROUP BY p.platform""", null).use { cur ->
            while (cur.moveToNext()) byPlatform[cur.getString(0)] = cur.getInt(1)
        }
        val byType = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT media_type, COUNT(*) FROM media WHERE deleted=0 GROUP BY media_type", null).use { cur ->
            while (cur.moveToNext()) byType[cur.getString(0) ?: ""] = cur.getInt(1)
        }
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(file_size),0) FROM media WHERE deleted=0", null).use {
            it.moveToFirst()
            val authors = readableDatabase.rawQuery("SELECT COUNT(*) FROM authors", null).use { a ->
                a.moveToFirst(); a.getInt(0)
            }
            return Stats(it.getInt(0), it.getLong(1), authors, byPlatform, byType)
        }
    }

    // ---------- 收件箱 ----------

    fun captureInbox(url: String, platform: String, kind: String): Long {
        writableDatabase.execSQL(
            """INSERT INTO inbox(url, platform, kind, captured_at) VALUES(?,?,?,?)
            ON CONFLICT(url) DO UPDATE SET captured_at=excluded.captured_at""",
            arrayOf(url, platform, kind, nowIso()))
        return readableDatabase.rawQuery("SELECT id FROM inbox WHERE url=?", arrayOf(url)).use {
            it.moveToFirst(); it.getLong(0)
        }
    }

    fun updateInboxMeta(id: Long, platform: String, kind: String, handle: String, name: String,
                        avatar: String, text: String, mediaCount: Int, preview: String = "") {
        writableDatabase.execSQL(
            """UPDATE inbox SET platform=?, kind=?, handle=?, name=?, avatar_url=?, text=?, media_count=?,
            preview_url=CASE WHEN ?!='' THEN ? ELSE preview_url END WHERE id=?""",
            arrayOf(platform, kind, handle, name, avatar, text, mediaCount, preview, preview, id))
    }

    fun updateInboxStatus(id: Long, status: String, error: String = "", taskId: Long = 0) {
        writableDatabase.execSQL(
            "UPDATE inbox SET status=?, error=?, task_id=? WHERE id=?",
            arrayOf(status, error, taskId, id))
    }

    fun inboxByTask(taskId: Long): InboxItem? =
        readableDatabase.rawQuery("SELECT * FROM inbox WHERE task_id=?", arrayOf(taskId.toString())).use {
            if (it.moveToFirst()) inboxRow(it) else null
        }

    fun inboxItem(id: Long): InboxItem? =
        readableDatabase.rawQuery("SELECT * FROM inbox WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) inboxRow(it) else null
        }

    fun listInbox(status: String = ""): List<InboxItem> {
        val sql = if (status.isBlank())
            "SELECT * FROM inbox ORDER BY id DESC LIMIT 300"
        else "SELECT * FROM inbox WHERE status=? ORDER BY id DESC LIMIT 300"
        return readableDatabase.rawQuery(sql, if (status.isBlank()) null else arrayOf(status)).use { cur ->
            val out = mutableListOf<InboxItem>()
            while (cur.moveToNext()) out.add(inboxRow(cur))
            out
        }
    }

    fun removeInbox(id: Long) {
        writableDatabase.execSQL("DELETE FROM inbox WHERE id=?", arrayOf(id))
    }

    private fun inboxRow(c: android.database.Cursor): InboxItem {
        val g = { i: Int -> c.getString(i) ?: "" }
        return InboxItem(c.getLong(0), g(1), g(2), g(3), g(4), g(5), g(6), g(7),
            c.getInt(8), g(9), g(10), c.getLong(11), g(12), g(13))
    }

    // ---------- tasks ----------

    fun insertTask(url: String, platform: String, kind: String): Long {
        val cv = ContentValues().apply {
            put("url", url); put("platform", platform); put("kind", kind); put("created_at", nowIso())
        }
        return writableDatabase.insert("tasks", null, cv)
    }

    fun updateTask(id: Long, vararg fields: Pair<String, Any?>) {
        if (fields.isEmpty()) return
        val sets = fields.joinToString(",") { "${it.first}=?" }
        val args = fields.map { it.second?.toString() ?: "" } + id.toString()
        writableDatabase.execSQL("UPDATE tasks SET $sets WHERE id=?", args.toTypedArray())
    }

    fun getTask(id: Long): TaskItem? =
        readableDatabase.rawQuery("SELECT * FROM tasks WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) taskRow(it) else null
        }

    private fun taskRow(c: android.database.Cursor): TaskItem {
        val g = { i: Int -> c.getString(i) ?: "" }
        return TaskItem(c.getLong(0), g(1), g(2), g(3), g(4), g(5), g(6),
            c.getInt(7), c.getInt(8), c.getInt(9), c.getLong(10), g(11), g(12))
    }

    fun listTasks(limit: Int = 80): List<TaskItem> =
        readableDatabase.rawQuery(
            "SELECT * FROM tasks ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { cur ->
            val out = mutableListOf<TaskItem>()
            while (cur.moveToNext()) out.add(taskRow(cur))
            out
        }

    fun deleteTask(id: Long) = writableDatabase.execSQL("DELETE FROM tasks WHERE id=?", arrayOf(id))

    fun runningTaskIds(): List<Long> =
        readableDatabase.rawQuery(
            "SELECT id FROM tasks WHERE status IN ('queued','running')", null).use { cur ->
            val out = mutableListOf<Long>()
            while (cur.moveToNext()) out.add(cur.getLong(0))
            out
        }

    // ---------- proxy ----------

    fun upsertCandidate(type: String, host: String, port: Int, source: String): Long {
        writableDatabase.execSQL(
            """INSERT INTO proxy_candidates(type, host, port, source) VALUES(?,?,?,?)
            ON CONFLICT(type, host, port) DO UPDATE SET source=excluded.source""",
            arrayOf(type, host, port, source))
        return readableDatabase.rawQuery(
            "SELECT id FROM proxy_candidates WHERE type=? AND host=? AND port=?",
            arrayOf(type, host, port.toString())).use { it.moveToFirst(); it.getLong(0) }
    }

    fun updateCandidate(id: Long, latencyMs: Double?, speedMbps: Double?, score: Double, alive: Boolean) {
        writableDatabase.execSQL(
            "UPDATE proxy_candidates SET latency_ms=?, speed_mbps=?, score=?, alive=?, last_tested=? WHERE id=?",
            arrayOf(latencyMs, speedMbps, score, if (alive) 1 else 0, nowIso(), id))
    }

    fun clearCandidates() = writableDatabase.execSQL("DELETE FROM proxy_candidates")

    fun candidates(): List<ProxyCand> =
        readableDatabase.rawQuery(
            "SELECT * FROM proxy_candidates ORDER BY score DESC, latency_ms IS NULL, latency_ms ASC", null).use { cur ->
            val out = mutableListOf<ProxyCand>()
            while (cur.moveToNext()) {
                out.add(ProxyCand(
                    cur.getLong(0), cur.getString(1), cur.getString(2), cur.getInt(3),
                    cur.getString(4) ?: "",
                    if (cur.isNull(5)) null else cur.getDouble(5),
                    if (cur.isNull(6)) null else cur.getDouble(6),
                    cur.getDouble(7), cur.getInt(8) == 1, cur.getString(9) ?: ""))
            }
            out
        }

    fun addProxyLog(event: String, detail: String) {
        writableDatabase.execSQL("INSERT INTO proxy_log(ts, event, detail) VALUES(?,?,?)",
            arrayOf(nowIso(), event, detail))
        writableDatabase.execSQL(
            "DELETE FROM proxy_log WHERE id NOT IN (SELECT id FROM proxy_log ORDER BY id DESC LIMIT 200)")
    }

    fun proxyLogs(limit: Int = 60): List<ProxyLogItem> =
        readableDatabase.rawQuery(
            "SELECT * FROM proxy_log ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())).use { cur ->
            val out = mutableListOf<ProxyLogItem>()
            while (cur.moveToNext()) {
                out.add(ProxyLogItem(cur.getLong(0), cur.getString(1) ?: "",
                    cur.getString(2) ?: "", cur.getString(3) ?: ""))
            }
            out
        }
}
