package com.ilian.xtreamplayer

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Base locale : sources, catalogue (pour ne pas tout recharger à chaque démarrage),
 * favoris, reprise de lecture, téléchargements et cache TMDB.
 */
object Db {
    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(ctx: Context) {
        helper = Helper(ctx.applicationContext)
        helper.setWriteAheadLoggingEnabled(true)
    }

    /** Profil en cours : favoris, reprise, catégories masquées et équipes suivies lui sont propres. */
    @Volatile
    var profile: Long = 0L

    /** Change à chaque modification des catégories masquées : les écrans se reconstruisent au retour. */
    @Volatile
    var hiddenVersion = 0

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "xtream.db", null, 3) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL(
                "CREATE TABLE sources(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, server TEXT, user TEXT, pass TEXT, " +
                    "last_sync INTEGER DEFAULT 0, status TEXT, exp_date INTEGER, max_conn INTEGER DEFAULT 0)"
            )
            d.execSQL(
                "CREATE TABLE categories(source_id INTEGER, kind TEXT, cat_id TEXT, name TEXT, pos INTEGER, hidden INTEGER DEFAULT 0, " +
                    "PRIMARY KEY(source_id, kind, cat_id))"
            )
            d.execSQL(
                "CREATE TABLE items(source_id INTEGER, kind TEXT, id TEXT, name TEXT, norm TEXT, icon TEXT, cat_id TEXT, ext TEXT, " +
                    "rating TEXT, tmdb TEXT, plot TEXT, pos INTEGER, num INTEGER, added INTEGER DEFAULT 0, PRIMARY KEY(source_id, kind, id))"
            )
            d.execSQL("CREATE INDEX items_cat ON items(source_id, kind, cat_id, pos)")
            d.execSQL("CREATE INDEX items_pos ON items(source_id, kind, pos)")
            d.execSQL("CREATE INDEX items_added ON items(source_id, kind, added)")
            d.execSQL(FAV_TABLE.replace("%s", "favorites"))
            d.execSQL(PROGRESS_TABLE.replace("%s", "progress"))
            d.execSQL(PROFILES_TABLE)
            d.execSQL(HIDDEN_TABLE)
            d.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
            d.execSQL("CREATE TABLE downloads(dm_id INTEGER PRIMARY KEY, source_id INTEGER, kind TEXT, id TEXT, json TEXT, created INTEGER)")
            d.execSQL("CREATE TABLE tmdb(key TEXT PRIMARY KEY, json TEXT, fetched INTEGER)")
        }

        override fun onUpgrade(d: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Version 3 : numéro de chaîne, date d'ajout, série des épisodes, petites préférences.
                d.execSQL("ALTER TABLE items ADD COLUMN num INTEGER")
                d.execSQL("ALTER TABLE items ADD COLUMN added INTEGER DEFAULT 0")
                d.execSQL("CREATE INDEX items_added ON items(source_id, kind, added)")
                d.execSQL("ALTER TABLE progress ADD COLUMN series TEXT")
                d.execSQL("CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)")
                // Le catalogue est rechargé une fois pour remplir les nouvelles colonnes.
                d.execSQL("UPDATE sources SET last_sync=0")
            }
            if (oldVersion < 3) {
                // Version 6 : profils. Les données existantes vont au premier profil de chaque source.
                d.execSQL(PROFILES_TABLE)
                d.execSQL(
                    "INSERT INTO profiles(source_id, name, color, langs, created) " +
                        "SELECT id, 'Profil 1', 0, '', ${System.currentTimeMillis()} FROM sources"
                )
                val pOf = "(SELECT p.id FROM profiles p WHERE p.source_id=x.source_id ORDER BY p.id LIMIT 1)"
                d.execSQL(FAV_TABLE.replace("%s", "fav3"))
                d.execSQL("INSERT OR IGNORE INTO fav3 SELECT $pOf, x.source_id, x.kind, x.id, x.added FROM favorites x")
                d.execSQL("DROP TABLE favorites")
                d.execSQL("ALTER TABLE fav3 RENAME TO favorites")
                d.execSQL(PROGRESS_TABLE.replace("%s", "prog3"))
                d.execSQL(
                    "INSERT OR IGNORE INTO prog3 SELECT $pOf, x.source_id, x.kind, x.id, x.pos, x.dur, x.finished, x.updated, " +
                        "x.name, x.icon, x.ext, x.extra, x.series FROM progress x"
                )
                d.execSQL("DROP TABLE progress")
                d.execSQL("ALTER TABLE prog3 RENAME TO progress")
                d.execSQL(HIDDEN_TABLE)
                d.execSQL("INSERT OR IGNORE INTO hidden SELECT $pOf, x.source_id, x.kind, x.cat_id FROM categories x WHERE x.hidden=1")
                d.execSQL(
                    "INSERT OR REPLACE INTO kv SELECT 'p' || (SELECT MIN(id) FROM profiles) || ':' || key, value FROM kv " +
                        "WHERE key IN ('sport:teams','sport:teaminfo') AND (SELECT MIN(id) FROM profiles) IS NOT NULL"
                )
            }
        }
    }

    private const val FAV_TABLE =
        "CREATE TABLE %s(profile INTEGER, source_id INTEGER, kind TEXT, id TEXT, added INTEGER, PRIMARY KEY(profile, source_id, kind, id))"
    private const val PROGRESS_TABLE =
        "CREATE TABLE %s(profile INTEGER, source_id INTEGER, kind TEXT, id TEXT, pos INTEGER, dur INTEGER, finished INTEGER, " +
            "updated INTEGER, name TEXT, icon TEXT, ext TEXT, extra TEXT, series TEXT, PRIMARY KEY(profile, source_id, kind, id))"
    private const val PROFILES_TABLE =
        "CREATE TABLE profiles(id INTEGER PRIMARY KEY AUTOINCREMENT, source_id INTEGER, name TEXT, color INTEGER DEFAULT 0, langs TEXT, created INTEGER)"
    private const val HIDDEN_TABLE =
        "CREATE TABLE hidden(profile INTEGER, source_id INTEGER, kind TEXT, cat_id TEXT, PRIMARY KEY(profile, source_id, kind, cat_id))"

    private fun <T> tx(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val r = block()
            d.setTransactionSuccessful()
            return r
        } finally {
            d.endTransaction()
        }
    }

    private fun args(vararg a: Any): Array<String> = a.map { it.toString() }.toTypedArray()
    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    // ---------- Sources ----------

    private const val SRC_COLS = "id, name, server, user, pass, last_sync, status, exp_date, max_conn"

    private fun source(c: Cursor) = Source(
        id = c.getLong(0),
        name = c.str(1) ?: "",
        server = c.str(2) ?: "",
        user = c.str(3) ?: "",
        pass = c.str(4) ?: "",
        lastSync = c.getLong(5),
        status = c.str(6),
        expDate = if (c.isNull(7)) null else c.getLong(7),
        maxConn = c.getInt(8)
    )

    fun sources(): List<Source> = db.rawQuery("SELECT $SRC_COLS FROM sources ORDER BY id", null).use { c ->
        val out = ArrayList<Source>()
        while (c.moveToNext()) out.add(source(c))
        out
    }

    fun source(id: Long): Source? = db.rawQuery("SELECT $SRC_COLS FROM sources WHERE id=?", args(id)).use { c ->
        if (c.moveToFirst()) source(c) else null
    }

    fun addSource(name: String, server: String, user: String, pass: String): Long =
        db.insert("sources", null, ContentValues().apply {
            put("name", name)
            put("server", server)
            put("user", user)
            put("pass", pass)
            put("last_sync", 0L)
        })

    /** Met à jour une source. Si les identifiants changent, son catalogue sera rechargé. */
    fun updateSource(id: Long, name: String, server: String, user: String, pass: String) {
        val old = source(id)
        val changed = old == null || old.server != server || old.user != user || old.pass != pass
        db.update("sources", ContentValues().apply {
            put("name", name)
            put("server", server)
            put("user", user)
            put("pass", pass)
            if (changed) put("last_sync", 0L)
        }, "id=?", args(id))
    }

    fun updateAccount(id: Long, a: AccountInfo) {
        db.update("sources", ContentValues().apply {
            put("status", a.status)
            if (a.expDate != null) put("exp_date", a.expDate) else putNull("exp_date")
            put("max_conn", a.maxConn)
        }, "id=?", args(id))
    }

    fun setLastSync(id: Long, t: Long) {
        db.update("sources", ContentValues().apply { put("last_sync", t) }, "id=?", args(id))
    }

    fun deleteSource(id: Long) {
        tx {
            for (t in listOf("categories", "items", "favorites", "progress", "downloads", "hidden", "profiles")) db.delete(t, "source_id=?", args(id))
            db.delete("sources", "id=?", args(id))
        }
        favCache.clear()
    }

    // ---------- Profils ----------

    fun profiles(src: Long): List<Profile> =
        db.rawQuery("SELECT id, source_id, name, color, langs FROM profiles WHERE source_id=? ORDER BY id", args(src)).use { c ->
            val out = ArrayList<Profile>()
            while (c.moveToNext()) out.add(Profile(c.getLong(0), c.getLong(1), c.str(2) ?: "Profil", c.getInt(3), c.str(4).orEmpty()))
            out
        }

    fun profileById(id: Long): Profile? =
        db.rawQuery("SELECT id, source_id, name, color, langs FROM profiles WHERE id=?", args(id)).use { c ->
            if (c.moveToFirst()) Profile(c.getLong(0), c.getLong(1), c.str(2) ?: "Profil", c.getInt(3), c.str(4).orEmpty()) else null
        }

    fun addProfile(src: Long, name: String, color: Int): Long =
        db.insert("profiles", null, ContentValues().apply {
            put("source_id", src)
            put("name", name)
            put("color", color)
            put("langs", "")
            put("created", System.currentTimeMillis())
        })

    fun updateProfile(id: Long, name: String, color: Int) {
        db.update("profiles", ContentValues().apply {
            put("name", name)
            put("color", color)
        }, "id=?", args(id))
    }

    fun setProfileLangs(id: Long, langs: String) {
        db.update("profiles", ContentValues().apply { put("langs", langs) }, "id=?", args(id))
    }

    /** Supprime un profil et tout ce qui lui appartient. */
    fun deleteProfile(id: Long) {
        tx {
            for (t in listOf("favorites", "progress", "hidden")) db.delete(t, "profile=?", args(id))
            db.delete("kv", "key LIKE ?", arrayOf("p$id:%"))
            db.delete("profiles", "id=?", args(id))
        }
        favCache.clear()
    }

    /** Premier profil de la source (créé s'il n'y en a pas encore). */
    fun ensureProfile(src: Long): Profile {
        profiles(src).firstOrNull()?.let { return it }
        val id = addProfile(src, "Profil 1", 0)
        return profileById(id)!!
    }

    // ---------- Catalogue ----------

    /** Catégories dans l'ordre du fournisseur, avec leur état masqué pour le profil [prof] (le profil en cours par défaut). */
    fun categories(src: Long, kind: Kind, prof: Long = profile): List<Pair<Category, Boolean>> =
        db.rawQuery(
            "SELECT c.cat_id, c.name, CASE WHEN h.cat_id IS NULL THEN 0 ELSE 1 END FROM categories c " +
                "LEFT JOIN hidden h ON h.profile=? AND h.source_id=c.source_id AND h.kind=c.kind AND h.cat_id=c.cat_id " +
                "WHERE c.source_id=? AND c.kind=? ORDER BY c.pos",
            args(prof, src, kind.name)
        ).use { c ->
            val out = ArrayList<Pair<Category, Boolean>>()
            while (c.moveToNext()) out.add(Category(c.str(0) ?: "", c.str(1) ?: "Sans nom") to (c.getInt(2) == 1))
            out
        }

    fun setHidden(src: Long, kind: Kind, ids: Set<String>, prof: Long = profile) {
        hiddenVersion++
        tx {
            db.delete("hidden", "profile=? AND source_id=? AND kind=?", args(prof, src, kind.name))
            for (id in ids) {
                db.insertWithOnConflict("hidden", null, ContentValues().apply {
                    put("profile", prof)
                    put("source_id", src)
                    put("kind", kind.name)
                    put("cat_id", id)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    /** Masque des catégories en plus de celles déjà masquées. */
    /** Masque ou affiche une seule catégorie (enregistré tout de suite). */
    fun setOneHidden(src: Long, kind: Kind, id: String, hide: Boolean, prof: Long = profile) {
        hiddenVersion++
        if (hide) {
            db.insertWithOnConflict("hidden", null, ContentValues().apply {
                put("profile", prof)
                put("source_id", src)
                put("kind", kind.name)
                put("cat_id", id)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        } else {
            db.delete("hidden", "profile=? AND source_id=? AND kind=? AND cat_id=?", args(prof, src, kind.name, id))
        }
    }

    fun addHidden(src: Long, kind: Kind, ids: Collection<String>, prof: Long) {
        hiddenVersion++
        tx {
            for (id in ids) {
                db.insertWithOnConflict("hidden", null, ContentValues().apply {
                    put("profile", prof)
                    put("source_id", src)
                    put("kind", kind.name)
                    put("cat_id", id)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    /**
     * Remplace le catalogue d'un type pour une source, en une seule transaction :
     * si le téléchargement échoue en route, l'ancien catalogue est conservé.
     */
    fun replaceCatalog(src: Long, kind: Kind, cats: List<Category>, fill: ((Item) -> Unit) -> Unit): List<Category> {
        val before = db.rawQuery("SELECT cat_id FROM categories WHERE source_id=? AND kind=?", args(src, kind.name)).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) c.str(0)?.let { out.add(it) }
            out
        }
        tx {
            db.delete("categories", "source_id=? AND kind=?", args(src, kind.name))
            db.delete("items", "source_id=? AND kind=?", args(src, kind.name))
            cats.forEachIndexed { i, cat ->
                db.insertWithOnConflict("categories", null, ContentValues().apply {
                    put("source_id", src)
                    put("kind", kind.name)
                    put("cat_id", cat.id)
                    put("name", cat.name)
                    put("pos", i)
                    put("hidden", 0)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO items(source_id, kind, id, name, norm, icon, cat_id, ext, rating, tmdb, plot, pos, num, added) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            var pos = 0L
            fun bind(i: Int, v: String?) {
                if (v == null) st.bindNull(i) else st.bindString(i, v)
            }
            try {
                fill { item ->
                    st.clearBindings()
                    st.bindLong(1, src)
                    st.bindString(2, kind.name)
                    st.bindString(3, item.id)
                    st.bindString(4, item.name)
                    st.bindString(5, normalize(item.name))
                    bind(6, item.icon)
                    bind(7, item.catId)
                    bind(8, item.ext)
                    bind(9, item.rating)
                    bind(10, item.tmdb)
                    bind(11, item.plot)
                    st.bindLong(12, pos++)
                    val n = item.num
                    if (n == null) st.bindNull(13) else st.bindLong(13, n.toLong())
                    st.bindLong(14, item.added)
                    st.executeInsert()
                }
            } finally {
                st.close()
            }
        }
        // Catégories apparues depuis la dernière mise à jour (pour leur appliquer les préréglages de langue)
        return if (before.isEmpty()) cats else cats.filter { it.id !in before }
    }

    private const val ITEM_COLS = "kind, id, name, icon, ext, plot, cat_id, rating, tmdb, num, added"
    private val ITEM_COLS_I = ITEM_COLS.split(",").joinToString(",") { "i." + it.trim() }

    private fun item(c: Cursor): Item {
        val kind = Kind.valueOf(c.getString(0))
        val rating = c.str(7)
        return Item(
            kind = kind,
            id = c.getString(1),
            name = c.str(2) ?: "?",
            icon = c.str(3),
            ext = c.str(4),
            plot = c.str(5),
            extra = if (kind == Kind.MOVIE && rating != null && rating != "0") "★ $rating" else null,
            catId = c.str(6),
            rating = rating,
            tmdb = c.str(8),
            num = if (c.isNull(9)) null else c.getInt(9),
            added = if (c.isNull(10)) 0L else c.getLong(10)
        )
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** catIds null = toutes les catégories visibles. */
    private fun where(src: Long, kind: Kind, catIds: List<String>?, q: String?, a: MutableList<String>): String {
        val sb = StringBuilder("source_id=? AND kind=?")
        a.add(src.toString())
        a.add(kind.name)
        if (catIds != null) {
            sb.append(" AND cat_id IN (").append(catIds.joinToString(",") { "?" }).append(")")
            a.addAll(catIds)
        } else {
            sb.append(" AND (cat_id IS NULL OR cat_id NOT IN (SELECT cat_id FROM hidden WHERE profile=? AND source_id=? AND kind=?))")
            a.add(profile.toString())
            a.add(src.toString())
            a.add(kind.name)
        }
        if (!q.isNullOrBlank()) {
            val t = q.trim()
            if (kind == Kind.LIVE && t.all { it.isDigit() }) {
                // Chaîne cherchée par son numéro (ou un nom qui contient ces chiffres)
                sb.append(" AND (num = ? OR norm LIKE ? ESCAPE '\\')")
                a.add(t)
            } else {
                sb.append(" AND norm LIKE ? ESCAPE '\\'")
            }
            a.add("%" + escapeLike(normalize(t)) + "%")
        }
        return sb.toString()
    }

    fun count(src: Long, kind: Kind, catIds: List<String>?, q: String?): Int {
        if (catIds != null && catIds.isEmpty()) return 0
        val a = ArrayList<String>()
        val w = where(src, kind, catIds, q, a)
        return db.rawQuery("SELECT COUNT(*) FROM items WHERE $w", a.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun page(src: Long, kind: Kind, catIds: List<String>?, q: String?, offset: Int, limit: Int): List<Item> {
        if (catIds != null && catIds.isEmpty()) return emptyList()
        val a = ArrayList<String>()
        val w = where(src, kind, catIds, q, a)
        a.add(limit.toString())
        a.add(offset.toString())
        return db.rawQuery("SELECT $ITEM_COLS FROM items WHERE $w ORDER BY pos LIMIT ? OFFSET ?", a.toTypedArray()).use { c ->
            val out = ArrayList<Item>(limit)
            while (c.moveToNext()) out.add(item(c))
            out
        }
    }

    /** Les plus récents d'abord (date d'ajout du fournisseur), hors catégories masquées. */
    fun latest(src: Long, kind: Kind, limit: Int): List<Item> {
        val a = ArrayList<String>()
        val w = where(src, kind, null, null, a)
        a.add(limit.toString())
        return db.rawQuery("SELECT $ITEM_COLS FROM items WHERE $w AND added > 0 ORDER BY added DESC LIMIT ?", a.toTypedArray()).use { c ->
            val out = ArrayList<Item>(limit)
            while (c.moveToNext()) out.add(item(c))
            out
        }
    }

    /** Éléments du catalogue correspondant à des identifiants TMDB, dans l'ordre demandé. */
    fun byTmdb(src: Long, kind: Kind, ids: List<String>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val a = arrayListOf(src.toString(), kind.name)
        a.addAll(ids)
        val found = HashMap<String, Item>()
        db.rawQuery(
            "SELECT $ITEM_COLS FROM items WHERE source_id=? AND kind=? AND tmdb IN (${ids.joinToString(",") { "?" }})",
            a.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                val it = item(c)
                val t = it.tmdb ?: continue
                if (t !in found) found[t] = it
            }
        }
        return ids.mapNotNull { found[it] }
    }

    /** Position d'une chaîne (par son numéro) dans une liste filtrée, ou null. */
    fun indexOfNum(src: Long, kind: Kind, catIds: List<String>?, q: String?, num: Int): Int? {
        if (catIds != null && catIds.isEmpty()) return null
        val a = ArrayList<String>()
        val w = where(src, kind, catIds, q, a)
        val a1 = ArrayList(a)
        a1.add(num.toString())
        val pos = db.rawQuery("SELECT pos FROM items WHERE $w AND num = ? ORDER BY pos LIMIT 1", a1.toTypedArray()).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: return null
        a.add(pos.toString())
        return db.rawQuery("SELECT COUNT(*) FROM items WHERE $w AND pos < ?", a.toTypedArray()).use { c -> if (c.moveToFirst()) c.getInt(0) else null }
    }

    /** Catégories des films et séries regardés ou mis en favori : ce que l'utilisateur aime. */
    fun tasteCategories(src: Long, kind: Kind): Map<String, Int> {
        val out = HashMap<String, Int>()
        val k = kind.name
        db.rawQuery(
            "SELECT i.cat_id, COUNT(*) FROM progress p JOIN items i ON i.source_id=p.source_id AND i.kind=p.kind AND i.id=p.id " +
                "WHERE p.profile=? AND p.source_id=? AND p.kind=? AND i.cat_id IS NOT NULL GROUP BY i.cat_id",
            args(profile, src, k)
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = (out[c.getString(0)] ?: 0) + c.getInt(1) * 2 }
        if (kind == Kind.SERIES) {
            db.rawQuery(
                "SELECT i.cat_id, COUNT(DISTINCT p.series) FROM progress p JOIN items i ON i.source_id=p.source_id AND i.kind='SERIES' AND i.id=p.series " +
                    "WHERE p.profile=? AND p.source_id=? AND p.kind='EPISODE' AND i.cat_id IS NOT NULL GROUP BY i.cat_id",
                args(profile, src)
            ).use { c -> while (c.moveToNext()) out[c.getString(0)] = (out[c.getString(0)] ?: 0) + c.getInt(1) * 2 }
        }
        db.rawQuery(
            "SELECT i.cat_id, COUNT(*) FROM favorites f JOIN items i ON i.source_id=f.source_id AND i.kind=f.kind AND i.id=f.id " +
                "WHERE f.profile=? AND f.source_id=? AND f.kind=? AND i.cat_id IS NOT NULL GROUP BY i.cat_id",
            args(profile, src, k)
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = (out[c.getString(0)] ?: 0) + c.getInt(1) }
        return out
    }

    /** Identifiants déjà vus (terminés) : on ne les remet pas en avant. */
    fun watchedIds(src: Long, kind: Kind): Set<String> =
        db.rawQuery("SELECT id FROM progress WHERE profile=? AND source_id=? AND kind=? AND finished=1", args(profile, src, kind.name)).use { c ->
            val out = HashSet<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    /** Un élément du catalogue par son identifiant (null s'il n'existe plus). */
    fun find(src: Long, kind: Kind, id: String): Item? =
        db.rawQuery("SELECT $ITEM_COLS FROM items WHERE source_id=? AND kind=? AND id=?", args(src, kind.name, id)).use { c ->
            if (c.moveToFirst()) item(c) else null
        }

    /** Nombre d'éléments par catégorie (pour l'écran « Catégories affichées »). */
    fun countsByCat(src: Long, kind: Kind): Map<String, Int> =
        db.rawQuery("SELECT cat_id, COUNT(*) FROM items WHERE source_id=? AND kind=? GROUP BY cat_id", args(src, kind.name)).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.str(0) ?: ""] = c.getInt(1)
            out
        }

    fun countAll(src: Long, kind: Kind): Int =
        db.rawQuery("SELECT COUNT(*) FROM items WHERE source_id=? AND kind=?", args(src, kind.name)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun countWithTmdb(src: Long, kind: Kind): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM items WHERE source_id=? AND kind=? AND tmdb IS NOT NULL AND tmdb != '' AND tmdb != '0'",
            args(src, kind.name)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    // ---------- Favoris ----------

    private val favCache = HashMap<String, MutableSet<String>>()

    private fun favSet(src: Long): MutableSet<String> = favCache.getOrPut("$profile:$src") {
        val s = HashSet<String>()
        db.rawQuery("SELECT kind, id FROM favorites WHERE profile=? AND source_id=?", args(profile, src)).use { c ->
            while (c.moveToNext()) s.add(c.getString(0) + ":" + c.getString(1))
        }
        s
    }

    fun isFav(src: Long, item: Item): Boolean = item.key in favSet(src)

    /** Renvoie true si l'élément est maintenant en favori. */
    fun toggleFav(src: Long, item: Item): Boolean {
        val set = favSet(src)
        return if (item.key in set) {
            db.delete("favorites", "profile=? AND source_id=? AND kind=? AND id=?", args(profile, src, item.kind.name, item.id))
            set.remove(item.key)
            false
        } else {
            db.insertWithOnConflict("favorites", null, ContentValues().apply {
                put("profile", profile)
                put("source_id", src)
                put("kind", item.kind.name)
                put("id", item.id)
                put("added", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            set.add(item.key)
            true
        }
    }

    fun favorites(src: Long, kind: Kind): List<Item> = db.rawQuery(
        "SELECT $ITEM_COLS_I FROM favorites f JOIN items i ON i.source_id=f.source_id AND i.kind=f.kind AND i.id=f.id " +
            "WHERE f.profile=? AND f.source_id=? AND f.kind=? ORDER BY f.added DESC",
        args(profile, src, kind.name)
    ).use { c ->
        val out = ArrayList<Item>()
        while (c.moveToNext()) out.add(item(c))
        out
    }

    // ---------- Reprise de lecture ----------

    data class Progress(val pos: Long, val dur: Long, val finished: Boolean)

    fun progress(src: Long, item: Item): Progress? =
        db.rawQuery("SELECT pos, dur, finished FROM progress WHERE profile=? AND source_id=? AND kind=? AND id=?", args(profile, src, item.kind.name, item.id)).use { c ->
            if (c.moveToFirst()) Progress(c.getLong(0), c.getLong(1), c.getInt(2) == 1) else null
        }

    fun progressMap(src: Long, kind: Kind, ids: List<String>): Map<String, Progress> {
        val out = HashMap<String, Progress>()
        for (chunk in ids.chunked(400)) {
            if (chunk.isEmpty()) continue
            val a = arrayListOf(profile.toString(), src.toString(), kind.name)
            a.addAll(chunk)
            db.rawQuery(
                "SELECT id, pos, dur, finished FROM progress WHERE profile=? AND source_id=? AND kind=? AND id IN (${chunk.joinToString(",") { "?" }})",
                a.toTypedArray()
            ).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = Progress(c.getLong(1), c.getLong(2), c.getInt(3) == 1)
            }
        }
        return out
    }

    fun saveProgress(src: Long, item: Item, pos: Long, dur: Long, finished: Boolean) {
        db.insertWithOnConflict("progress", null, ContentValues().apply {
            put("profile", profile)
            put("source_id", src)
            put("kind", item.kind.name)
            put("id", item.id)
            put("pos", pos)
            put("dur", dur)
            put("finished", if (finished) 1 else 0)
            put("updated", System.currentTimeMillis())
            put("name", item.name)
            put("icon", item.icon)
            put("ext", item.ext)
            put("extra", item.extra)
            put("series", item.series)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Films en cours (finished = false) ou terminés (finished = true), du plus récent au plus ancien. */
    fun progressList(src: Long, kind: Kind, finished: Boolean): List<Item> = db.rawQuery(
        "SELECT id, name, icon, ext, extra FROM progress WHERE profile=? AND source_id=? AND kind=? AND finished=? " +
            (if (finished) "" else "AND pos > 0 ") + "ORDER BY updated DESC LIMIT 300",
        args(profile, src, kind.name, if (finished) 1 else 0)
    ).use { c ->
        val out = ArrayList<Item>()
        while (c.moveToNext()) out.add(Item(kind, c.getString(0), c.str(1) ?: "?", c.str(2), c.str(3), extra = c.str(4)))
        out
    }

    data class Resume(val item: Item, val pos: Long, val dur: Long, val updated: Long = 0L)

    /** Films et épisodes commencés (ou un seul type), du plus récent au plus ancien. */
    fun resume(src: Long, limit: Int, kind: Kind? = null): List<Resume> = db.rawQuery(
        "SELECT kind, id, name, icon, ext, extra, pos, dur, series, updated FROM progress WHERE profile=? AND source_id=? AND finished=0 AND pos > 0 " +
            (if (kind != null) "AND kind='${kind.name}' " else "AND kind IN ('MOVIE','EPISODE') ") +
            "ORDER BY updated DESC LIMIT $limit",
        args(profile, src)
    ).use { c ->
        val out = ArrayList<Resume>()
        while (c.moveToNext()) {
            val item = Item(Kind.valueOf(c.getString(0)), c.getString(1), c.str(2) ?: "?", c.str(3), c.str(4), extra = c.str(5), series = c.str(8))
            out.add(Resume(item, c.getLong(6), c.getLong(7), c.getLong(9)))
        }
        out
    }

    /** Dernier épisode regardé (en cours ou fini) de chaque série, de la plus récente à la plus ancienne. */
    fun seriesActivity(src: Long, limit: Int): List<Pair<String, Resume>> {
        val out = ArrayList<Pair<String, Resume>>()
        val seen = HashSet<String>()
        db.rawQuery(
            "SELECT kind, id, name, icon, ext, extra, pos, dur, series, finished, updated FROM progress WHERE profile=? AND source_id=? " +
                "AND kind='EPISODE' AND series IS NOT NULL ORDER BY updated DESC LIMIT 400",
            args(profile, src)
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val series = c.getString(8)
                if (!seen.add(series)) continue
                val item = Item(Kind.EPISODE, c.getString(1), c.str(2) ?: "?", c.str(3), c.str(4), extra = c.str(5), series = series)
                val finished = c.getInt(9) == 1
                out.add(series to Resume(item, if (finished) -1 else c.getLong(6), c.getLong(7), c.getLong(10)))
            }
        }
        return out
    }

    // ---------- Petites valeurs (chaîne choisie pour un match, équipes suivies…) ----------

    /** Clé propre au profil en cours. */
    fun pkey(key: String) = "p$profile:$key"

    fun kvGet(key: String): String? =
        db.rawQuery("SELECT value FROM kv WHERE key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun kvPut(key: String, value: String?) {
        if (value == null) db.delete("kv", "key=?", arrayOf(key))
        else db.insertWithOnConflict("kv", null, ContentValues().apply {
            put("key", key)
            put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---------- Téléchargements ----------

    data class DlRow(val dmId: Long, val sourceId: Long, val item: Item)

    fun addDownload(dmId: Long, src: Long, item: Item) {
        db.insertWithOnConflict("downloads", null, ContentValues().apply {
            put("dm_id", dmId)
            put("source_id", src)
            put("kind", item.kind.name)
            put("id", item.id)
            put("json", item.toJson().toString())
            put("created", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun downloads(): List<DlRow> = db.rawQuery("SELECT dm_id, source_id, json FROM downloads ORDER BY created DESC", null).use { c ->
        val out = ArrayList<DlRow>()
        while (c.moveToNext()) {
            val item = try {
                Item.fromJson(JSONObject(c.getString(2)))
            } catch (_: Exception) {
                null
            }
            if (item != null) out.add(DlRow(c.getLong(0), c.getLong(1), item))
        }
        out
    }

    fun removeDownload(dmId: Long) {
        db.delete("downloads", "dm_id=?", args(dmId))
    }

    // ---------- Cache TMDB ----------

    fun tmdbGet(key: String): Pair<String, Long>? =
        db.rawQuery("SELECT json, fetched FROM tmdb WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) (c.str(0) ?: "{}") to c.getLong(1) else null
        }

    fun tmdbPut(key: String, json: String) {
        db.insertWithOnConflict("tmdb", null, ContentValues().apply {
            put("key", key)
            put("json", json)
            put("fetched", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
