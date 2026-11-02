package com.ilian.xtreamplayer

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Index des titres du catalogue, gardé en mémoire : pour chaque film ou série, une empreinte de son titre
 * nettoyé (« FR - Intouchables (2011) 4K » devient « intouchables »). Il permet de retrouver en un instant
 * les dizaines de films d'un acteur, sans relire tout le catalogue à chaque fois. Moins d'1 Mo pour 100 000 titres.
 */
object TitleIndex {
    private class Idx(val stamp: Long, val keys: LongArray)

    private val cache = ConcurrentHashMap<String, Idx>()

    /** Empreinte d'un titre : nettoyé, sans accents ni ponctuation. */
    fun hash(title: String): Int = normalize(Tmdb.cleanTitle(title).first).replace(Rx.NON_ALNUM, "").hashCode()

    /** Construit l'index s'il manque ou si le catalogue a été mis à jour. Bloquant. */
    @Synchronized
    private fun idx(src: Source, kind: Kind): Idx {
        val k = "${src.id}:${kind.name}"
        cache[k]?.takeIf { it.stamp == src.lastSync }?.let { return it }
        var arr = LongArray(4096)
        var n = 0
        Db.titleRows(src.id, kind) { rowid, name ->
            if (n == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[n++] = (hash(name).toLong() shl 32) or (rowid and 0xFFFFFFFFL)
        }
        val keys = arr.copyOf(n)
        keys.sort()
        return Idx(src.lastSync, keys).also { cache[k] = it }
    }

    /** Prépare les index des films et des séries (à l'ouverture de la recherche). */
    fun warm(src: Source) {
        try {
            idx(src, Kind.MOVIE)
            idx(src, Kind.SERIES)
        } catch (_: Exception) {
        }
    }

    /** Numéros de ligne des titres dont l'empreinte correspond. */
    fun rowids(src: Source, kind: Kind, hashes: Collection<Int>): List<Long> {
        val keys = idx(src, kind).keys
        val out = ArrayList<Long>()
        for (h in hashes.toSet()) {
            val lo = h.toLong() shl 32
            var i = lowerBound(keys, lo)
            while (i < keys.size && (keys[i] ushr 32).toInt() == h) {
                out.add(keys[i] and 0xFFFFFFFFL)
                i++
            }
        }
        return out
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * Recherche par acteur ou réalisateur : on trouve la personne (TMDB avec une clé, sinon Wikidata et TVmaze,
 * gratuits), sa filmographie, puis les titres qu'on a dans le catalogue (par identifiant TMDB, sinon par
 * titre et année). Les réponses sont gardées 30 jours sur l'appareil.
 */
object People {
    /** [key] : « t:123 » (TMDB) ou « w:Q123 » (Wikidata). [dept] : acting, directing, writing. */
    data class Person(val key: String, val name: String, val photo: String?, val dept: String, val female: Boolean) {
        val role: String
            get() = when (dept) {
                "directing" -> if (female) "Réalisatrice" else "Réalisateur"
                "writing" -> "Scénariste"
                "creator" -> if (female) "Créatrice" else "Créateur"
                else -> if (female) "Actrice" else "Acteur"
            }

        /** « Avec Omar Sy » pour un acteur, « De Christopher Nolan » pour un réalisateur. */
        val title: String get() = if (dept == "acting") "Avec $name" else "De $name"
    }

    /** Un film ou une série de la filmographie. */
    data class Credit(val title: String, val original: String?, val year: Int?, val tmdb: String?, val tv: Boolean)

    private const val MONTH = 30L * 86_400_000L
    private const val UA = "XtreamPlayer/4 (Android TV; github.com/ilianmer/xtream-player)"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Réponse gardée 30 jours dans la base locale (même table que les fiches TMDB). */
    private fun cached(key: String, fetch: () -> String): String {
        Db.tmdbGet(key)?.let { (json, at) -> if (System.currentTimeMillis() - at < MONTH) return json }
        val v = fetch()
        try {
            Db.tmdbPut(key, v)
        } catch (_: Exception) {
        }
        return v
    }

    /** Chaque mot tapé doit commencer un mot du nom (« omar sy », « nolan », « dicap »). */
    fun matches(query: String, name: String): Boolean {
        val q = normalize(query).split(Rx.NON_ALNUM_RUN).filter { it.length >= 2 }
        if (q.isEmpty()) return false
        val words = normalize(name).split(Rx.NON_ALNUM_RUN).filter { it.isNotEmpty() }
        return q.all { w -> words.any { it.startsWith(w) } }
    }

    /** Assez long pour être un nom de personne (pas un numéro de chaîne, pas deux lettres). */
    fun eligible(q: String): Boolean = q.count { it.isLetter() } >= 4

    // ---------- Personnes ----------

    /** Personnes qui correspondent au texte tapé, les plus connues d'abord. Bloquant. */
    fun candidates(q: String): List<Person> {
        if (!eligible(q)) return emptyList()
        val n = normalize(q.trim())
        val useTmdb = Tmdb.enabled
        val raw = try {
            cached("people:q:${if (useTmdb) "t" else "w"}:$n") { if (useTmdb) tmdbPeople(q) else wikiPeople(q) }
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Person(o.optString("k"), o.optString("n"), o.optString("p").ifBlank { null }, o.optString("d", "acting"), o.optBoolean("f"))
            }.filter { matches(q, it.name) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun personJson(p: Person) = JSONObject().put("k", p.key).put("n", p.name).put("p", p.photo ?: "").put("d", p.dept).put("f", p.female)

    private fun tmdbPeople(q: String): String {
        val res = Tmdb.call("/search/person", mapOf("query" to q.trim(), "include_adult" to "false")).optJSONArray("results") ?: JSONArray()
        val list = ArrayList<Pair<Person, Double>>()
        for (i in 0 until res.length()) {
            val o = res.optJSONObject(i) ?: continue
            val id = o.optLong("id", 0).takeIf { it > 0 } ?: continue
            val name = o.str("name") ?: continue
            val dept = when (o.str("known_for_department")) {
                "Acting" -> "acting"
                "Directing" -> "directing"
                "Writing" -> "writing"
                "Creator" -> "creator"
                else -> continue
            }
            val photo = o.str("profile_path")?.let { "https://image.tmdb.org/t/p/w185$it" }
            list.add(Person("t:$id", name, photo, dept, o.optInt("gender", 0) == 1) to o.optDouble("popularity", 0.0))
        }
        val out = JSONArray()
        for ((p, _) in list.sortedByDescending { it.second }.take(6)) out.put(personJson(p))
        return out.toString()
    }

    private fun sparql(query: String): JSONArray {
        val url = "https://query.wikidata.org/sparql?format=json&query=" + enc(query)
        val o = JSONObject(Http.getText(url, mapOf("Accept" to "application/sparql-results+json", "User-Agent" to UA)))
        return o.optJSONObject("results")?.optJSONArray("bindings") ?: JSONArray()
    }

    private fun JSONObject.v(name: String): String? = optJSONObject(name)?.optString("value")?.ifBlank { null }

    private fun wikiPeople(q: String): String {
        val search = JSONObject(
            Http.getText(
                "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&type=item&limit=10&language=fr&uselang=fr&search=" + enc(q.trim()),
                mapOf("User-Agent" to UA)
            )
        ).optJSONArray("search") ?: JSONArray()
        val ids = LinkedHashMap<String, String>()
        for (i in 0 until search.length()) {
            val o = search.optJSONObject(i) ?: continue
            val id = o.str("id") ?: continue
            val label = o.str("label") ?: continue
            if (matches(q, label)) ids[id] = label
        }
        val out = JSONArray()
        if (ids.isEmpty()) return out.toString()
        // Seulement les personnes qui ont joué dans des films ou des séries, ou qui en ont réalisé
        val rows = sparql(
            """
            SELECT ?p (SAMPLE(?img) AS ?image) (SAMPLE(?sex) AS ?gender) (COUNT(DISTINCT ?c) AS ?nc) (COUNT(DISTINCT ?d) AS ?nd) WHERE {
              VALUES ?p { ${ids.keys.joinToString(" ") { "wd:$it" }} }
              ?p wdt:P31 wd:Q5 .
              OPTIONAL { ?c wdt:P161 ?p }
              OPTIONAL { ?d wdt:P57 ?p }
              OPTIONAL { ?p wdt:P18 ?img }
              OPTIONAL { ?p wdt:P21 ?sex }
            } GROUP BY ?p
            """.trimIndent()
        )
        val found = ArrayList<Pair<Person, Int>>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val qid = r.v("p")?.substringAfterLast('/') ?: continue
            val nc = r.v("nc")?.toIntOrNull() ?: 0
            val nd = r.v("nd")?.toIntOrNull() ?: 0
            if (nc + nd < 2) continue
            val img = r.v("image")?.replace("http://", "https://")?.let { "$it?width=200" }
            val female = r.v("gender")?.endsWith("Q6581072") == true
            val dept = if (nd >= 3 && nd * 2 >= nc) "directing" else "acting"
            found.add(Person("w:$qid", ids[qid] ?: continue, img, dept, female) to nc + nd)
        }
        for ((p, _) in found.sortedByDescending { it.second }.take(6)) out.put(personJson(p))
        return out.toString()
    }

    // ---------- Filmographies ----------

    private val TALK = setOf(10767, 10763, 10764)
    private val SELF = Regex("""(?i)^\s*(self|himself|herself|lui[- ]m[eê]me|elle[- ]m[eê]me|themselves)\b""")

    /** Films et séries de la personne. Bloquant, gardé 30 jours. */
    fun credits(p: Person): List<Credit> {
        val raw = try {
            cached("people:c:${p.key}") {
                when {
                    p.key.startsWith("t:") -> tmdbCredits(p)
                    else -> wikiCredits(p)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONArray(i) ?: return@mapNotNull null
                Credit(o.optString(0), o.optString(1).ifBlank { null }, o.optInt(2, 0).takeIf { it > 0 }, o.optString(3).ifBlank { null }, o.optInt(4) == 1)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun creditJson(c: Credit) = JSONArray().put(c.title).put(c.original ?: "").put(c.year ?: 0).put(c.tmdb ?: "").put(if (c.tv) 1 else 0)

    private fun tmdbCredits(p: Person): String {
        val id = p.key.removePrefix("t:")
        val o = Tmdb.call("/person/$id/combined_credits")
        val out = LinkedHashMap<String, Pair<Credit, Double>>()
        fun add(x: JSONObject) {
            val tv = x.optString("media_type") == "tv"
            val tid = x.optLong("id", 0).takeIf { it > 0 }?.toString() ?: return
            val genres = x.optJSONArray("genre_ids")
            if (tv && genres != null && (0 until genres.length()).any { genres.optInt(it) in TALK }) return
            val title = x.str(if (tv) "name" else "title") ?: return
            val original = x.str(if (tv) "original_name" else "original_title")
            val year = x.str(if (tv) "first_air_date" else "release_date")?.take(4)?.toIntOrNull()
            val key = (if (tv) "tv:" else "movie:") + tid
            if (!out.containsKey(key)) out[key] = Credit(title, original, year, tid, tv) to x.optDouble("popularity", 0.0)
        }
        o.optJSONArray("cast")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                if (SELF.containsMatchIn(x.optString("character"))) continue
                add(x)
            }
        }
        val jobs = when (p.dept) {
            "writing" -> setOf("Director", "Creator", "Writer", "Screenplay")
            else -> setOf("Director", "Creator")
        }
        o.optJSONArray("crew")?.let { a ->
            for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                if (x.optString("job") in jobs) add(x)
            }
        }
        val arr = JSONArray()
        for ((c, _) in out.values.sortedByDescending { it.second }) arr.put(creditJson(c))
        return arr.toString()
    }

    private fun wikiCredits(p: Person): String {
        val qid = p.key.removePrefix("w:")
        val rows = sparql(
            """
            SELECT ?w (SAMPLE(?fr) AS ?t) (SAMPLE(?en) AS ?e) (SAMPLE(?o) AS ?orig) (MIN(?d) AS ?date)
                   (SAMPLE(?tm) AS ?tmdbm) (SAMPLE(?tt) AS ?tmdbt) (MAX(?isTv) AS ?tv) WHERE {
              { ?w wdt:P161 wd:$qid } UNION { ?w wdt:P57 wd:$qid }
              FILTER NOT EXISTS { ?w wdt:P31 wd:Q21191270 }
              OPTIONAL { ?w wdt:P4947 ?tm }
              OPTIONAL { ?w wdt:P4983 ?tt }
              OPTIONAL { ?w wdt:P577 ?d }
              OPTIONAL { ?w wdt:P1476 ?o }
              OPTIONAL { ?w rdfs:label ?fr . FILTER(LANG(?fr) = "fr") }
              OPTIONAL { ?w rdfs:label ?en . FILTER(LANG(?en) = "en") }
              OPTIONAL { ?w wdt:P31 ?ty . FILTER(?ty IN (wd:Q5398426, wd:Q1259759, wd:Q581714, wd:Q526877)) }
              BIND(IF(BOUND(?ty) || BOUND(?tt), 1, 0) AS ?isTv)
            } GROUP BY ?w
            """.trimIndent()
        )
        val list = ArrayList<Credit>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val title = r.v("t") ?: r.v("e") ?: r.v("orig") ?: continue
            val tv = r.v("tv") == "1"
            val year = r.v("date")?.take(4)?.toIntOrNull()
            list.add(Credit(title, r.v("orig") ?: r.v("e"), year, if (tv) r.v("tmdbt") else r.v("tmdbm"), tv))
        }
        // Séries : TVmaze complète Wikidata (distributions des séries récentes)
        try {
            list.addAll(tvmazeShows(p.name))
        } catch (_: Exception) {
        }
        val arr = JSONArray()
        for (c in list.sortedByDescending { it.year ?: 0 }) arr.put(creditJson(c))
        return arr.toString()
    }

    private fun tvmazeShows(name: String): List<Credit> {
        val people = JSONArray(Http.getText("https://api.tvmaze.com/search/people?q=" + enc(name), mapOf("User-Agent" to UA)))
        var id: Long? = null
        for (i in 0 until people.length()) {
            val pp = people.optJSONObject(i)?.optJSONObject("person") ?: continue
            if (normalize(pp.optString("name")) == normalize(name)) {
                id = pp.optLong("id")
                break
            }
        }
        val pid = id ?: return emptyList()
        val credits = JSONArray(Http.getText("https://api.tvmaze.com/people/$pid/castcredits?embed=show", mapOf("User-Agent" to UA)))
        val out = ArrayList<Credit>()
        for (i in 0 until credits.length()) {
            val show = credits.optJSONObject(i)?.optJSONObject("_embedded")?.optJSONObject("show") ?: continue
            val title = show.str("name") ?: continue
            if (show.optString("type") in setOf("Talk Show", "News", "Reality", "Award Show", "Game Show")) continue
            out.add(Credit(title, null, show.str("premiered")?.take(4)?.toIntOrNull(), null, true))
        }
        return out
    }

    // ---------- Dans le catalogue ----------

    /**
     * Les films et séries de la personne qu'on a dans le catalogue, du plus récent au plus ancien,
     * une seule version par titre (4K, HD, VF…). Bloquant.
     */
    fun works(src: Source, p: Person): List<Item> {
        val credits = credits(p)
        if (credits.isEmpty()) return emptyList()
        val found = LinkedHashMap<String, Pair<Item, Int>>()
        for (kind in listOf(Kind.MOVIE, Kind.SERIES)) {
            val mine = credits.filter { it.tv == (kind == Kind.SERIES) }
            if (mine.isEmpty()) continue
            // 1. Par identifiant TMDB, quand le fournisseur le donne
            val byId = try {
                Db.byTmdbVisible(src.id, kind, mine.mapNotNull { it.tmdb })
            } catch (_: Exception) {
                emptyMap()
            }
            val rest = ArrayList<Credit>()
            for (c in mine) {
                val hit = c.tmdb?.let { byId[it] }
                if (hit != null) found.putIfAbsent(dedupeKey(hit), hit to (c.year ?: 0)) else rest.add(c)
            }
            // 2. Par le titre (français ou original) et l'année, grâce à l'index des titres
            if (rest.isEmpty()) continue
            val wanted = HashMap<Int, MutableList<Credit>>()
            for (c in rest) {
                for (t in listOfNotNull(c.title, c.original)) {
                    if (normalize(t).replace(Rx.NON_ALNUM, "").length < 3) continue
                    wanted.getOrPut(TitleIndex.hash(t)) { ArrayList() }.add(c)
                }
            }
            val items = try {
                Db.byRowids(src.id, kind, TitleIndex.rowids(src, kind, wanted.keys))
            } catch (_: Exception) {
                emptyList()
            }
            for (item in items) {
                val (name, y) = Tmdb.cleanTitle(item.name)
                val h = TitleIndex.hash(item.name)
                val year = y?.toIntOrNull()
                val c = wanted[h]?.firstOrNull { c ->
                    val same = listOfNotNull(c.title, c.original).any { key(it) == key(name) }
                    same && (year == null || c.year == null || abs(year - c.year) <= 1)
                } ?: continue
                found.putIfAbsent(dedupeKey(item), item to (c.year ?: year ?: 0))
            }
        }
        return found.values.sortedByDescending { it.second }.map { it.first }.take(80)
    }

    private fun key(title: String) = normalize(Tmdb.cleanTitle(title).first).replace(Rx.NON_ALNUM, "")

    private fun dedupeKey(i: Item) = i.kind.name + ":" + (i.tmdb?.takeIf { it.isNotBlank() && it != "0" } ?: key(i.name))

    // ---------- Distribution d'une fiche ----------

    /** Noms d'une liste « A, B, C » (ou séparés par / ou ;), sans doublons. */
    fun names(raw: String?, max: Int): List<String> =
        raw.orEmpty().split(',', '/', ';', '|').map { it.trim() }.filter { it.length in 3..40 && it.any { c -> c.isLetter() } }.distinct().take(max)

    /** Distribution TMDB (3 acteurs principaux et la réalisation) quand le fournisseur n'en donne pas. Bloquant. */
    fun tmdbCast(tmdbId: String?, tv: Boolean): Pair<List<String>, String?> {
        val id = tmdbId?.takeIf { it.isNotBlank() && it != "0" } ?: return emptyList<String>() to null
        if (!Tmdb.enabled) return emptyList<String>() to null
        return try {
            val raw = cached("credits:${if (tv) "tv" else "movie"}:$id") {
                Tmdb.call("/${if (tv) "tv" else "movie"}/$id/credits").toString()
            }
            val o = JSONObject(raw)
            val cast = o.optJSONArray("cast")?.let { a -> (0 until minOf(3, a.length())).mapNotNull { a.optJSONObject(it)?.str("name") } }.orEmpty()
            val dir = o.optJSONArray("crew")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }?.str("name")
            }
            cast to dir
        } catch (_: Exception) {
            emptyList<String>() to null
        }
    }

    /**
     * Ligne « Avec (A) (B) (C)   De (D) » d'une fiche : chaque nom ouvre la recherche sur cette personne.
     * Renvoie true si la ligne a quelque chose à montrer.
     */
    fun fillRow(act: Activity, row: LinearLayout, cast: List<String>, director: String?): Boolean {
        row.removeAllViews()
        val d = act.resources.displayMetrics.density
        fun label(t: String) = TextView(act, null, 0, R.style.Txt).apply {
            text = t
            textSize = 11f
            setTextColor(act.getColor(R.color.text_faint))
        }
        fun pill(name: String) = TextView(act, null, 0, R.style.Medium).apply {
            text = name
            textSize = 11f
            setSingleLine()
            gravity = Gravity.CENTER
            setTextColor(act.getColorStateList(R.color.row_title_text))
            setBackgroundResource(R.drawable.person_pill_bg)
            setPadding((11 * d).toInt(), 0, (11 * d).toInt(), 0)
            isFocusable = true
            isClickable = true
            setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.06f) }
            setOnClickListener { act.startActivity(Intent(act, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_PERSON, name)) }
        }
        fun lp(start: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (28 * d).toInt()).apply { marginStart = (start * d).toInt() }
        if (cast.isNotEmpty()) {
            row.addView(label("Avec"))
            for ((i, n) in cast.withIndex()) row.addView(pill(n), lp(if (i == 0) 8 else 6))
        }
        if (director != null) {
            row.addView(label("De"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ((if (cast.isNotEmpty()) 16 else 0) * d).toInt()
            })
            row.addView(pill(director), lp(8))
        }
        val shown = row.childCount > 0
        row.visibility = if (shown) View.VISIBLE else View.GONE
        return shown
    }
}
