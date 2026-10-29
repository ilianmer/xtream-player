package com.ilian.xtreamplayer

/**
 * Langue des catégories, devinée d'après leur nom (« FR | ACTION », « AR - MBC », « VOSTFR »…).
 * Sert au préréglage de langue d'un profil : seules les catégories dans ses langues restent affichées,
 * puis on peut affiner à la main dans « Catégories affichées ».
 */
object Langs {
    data class Lang(val code: String, val label: String, val words: Set<String>)

    val ALL = listOf(
        // Strict : seulement ce qui est marqué France / FR / Français (et les versions françaises VF / VOSTFR)
        Lang("fr", "Français", setOf("fr", "fra", "france", "french", "francais", "vf", "vff", "vfq", "vostfr", "truefrench")),
        Lang("ar", "Arabe", setOf("ar", "ara", "arab", "arabe", "arabic", "mbc", "osn", "shahid", "rotana", "aljazeera", "khaleeji", "egypt", "egypte", "maghreb", "dz", "algerie", "tunisie", "tn")),
        Lang("ma", "Maroc", setOf("ma", "maroc", "morocco", "marocain", "moroccan", "amazigh", "2m", "arryadia")),
        Lang("en", "Anglais", setOf("en", "eng", "english", "uk", "us", "usa", "gb", "anglais", "britain", "canada")),
        Lang("es", "Espagnol", setOf("es", "esp", "spain", "espagne", "spanish", "espanol", "latino", "latam", "mexico")),
        Lang("it", "Italien", setOf("it", "ita", "italy", "italia", "italien", "italian")),
        Lang("de", "Allemand", setOf("de", "ger", "germany", "deutsch", "deutschland", "allemagne", "german")),
        Lang("pt", "Portugais", setOf("pt", "por", "portugal", "portugais", "br", "brasil", "brazil")),
        Lang("tr", "Turc", setOf("tr", "tur", "turk", "turkey", "turkiye", "turquie", "turkish")),
        Lang("nl", "Néerlandais", setOf("nl", "netherlands", "dutch", "holland", "pays-bas")),
        Lang("af", "Afrique", setOf("africa", "afrique", "african", "africain", "senegal", "cameroun", "nigeria")),
        Lang("in", "Inde", setOf("india", "inde", "hindi", "bollywood", "tamil", "punjabi")),
        Lang("ku", "Kurde / Perse", setOf("kurd", "kurdish", "kurdistan", "iran", "persian", "farsi"))
    )

    private val ARABIC = Regex("[\\u0600-\\u06FF]")
    private val SPLIT = Regex("[^a-z0-9+]+")

    /** Codes des langues reconnues dans un nom de catégorie (vide si aucune). */
    fun detect(name: String): Set<String> {
        val out = HashSet<String>()
        if (ARABIC.containsMatchIn(name)) out.add("ar")
        val tokens = SPLIT.split(normalize(name)).filter { it.isNotEmpty() }.toSet()
        for (l in ALL) if (l.words.any { it in tokens }) out.add(l.code)
        return out
    }

    fun label(code: String): String = ALL.firstOrNull { it.code == code }?.label ?: code

    /**
     * Préréglage strict : une catégorie n'est visible que si son nom indique une des langues choisies
     * (« FR », « France », « Français »…). Les catégories sans langue reconnaissable sont masquées ;
     * on peut toujours les réafficher une par une dans « Catégories affichées ».
     */
    fun keep(name: String, langs: Set<String>): Boolean {
        if (langs.isEmpty()) return true
        return detect(name).any { it in langs }
    }

    /** Passage au préréglage strict (une seule fois) : masque en plus ce qui n'a pas de langue reconnue. */
    fun tightenAll() {
        for (src in Db.sources()) for (p in Db.profiles(src.id)) {
            val langs = p.langSet
            if (langs.isEmpty()) continue
            for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) {
                val hide = Db.categories(src.id, k, p.id).filter { !it.second && !keep(it.first.name, langs) }.map { it.first.id }
                if (hide.isNotEmpty()) Db.addHidden(src.id, k, hide, p.id)
            }
        }
    }

    /** Nombre de catégories par langue, pour proposer seulement les langues présentes chez le fournisseur. */
    fun stats(src: Long): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) {
            for ((c, _) in Db.categories(src, k)) for (code in detect(c.name)) out[code] = (out[code] ?: 0) + 1
        }
        return out
    }

    /** Applique le préréglage à un profil : masque tout ce qui n'est pas dans ses langues. */
    fun apply(src: Long, profile: Long, langs: Set<String>) {
        for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) {
            val hidden = Db.categories(src, k, profile).filter { !keep(it.first.name, langs) }.map { it.first.id }.toSet()
            Db.setHidden(src, k, hidden, profile)
        }
    }

    /** Nouvelles catégories après une mise à jour : on respecte le préréglage de chaque profil. */
    fun onNewCategories(src: Long, kind: Kind, cats: List<Category>) {
        if (cats.isEmpty()) return
        for (p in Db.profiles(src)) {
            val langs = p.langSet
            if (langs.isEmpty()) continue
            val hide = cats.filter { !keep(it.name, langs) }.map { it.id }
            if (hide.isNotEmpty()) Db.addHidden(src, kind, hide, p.id)
        }
    }
}
