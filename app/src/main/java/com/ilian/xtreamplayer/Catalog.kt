package com.ilian.xtreamplayer

import java.io.IOException
import java.util.Locale

/** Téléchargement du catalogue complet d'une source vers la base locale. */
object Sync {
    fun needed(src: Source): Boolean {
        if (src.lastSync == 0L) return true
        val days = Prefs.updateDays
        return days > 0 && System.currentTimeMillis() - src.lastSync > days * 86_400_000L
    }

    /** [progress] est appelé depuis le thread de travail. */
    fun run(src: Source, progress: (String) -> Unit) {
        val api = XtreamApi(src.server, src.user, src.pass)
        progress("Vérification du compte…")
        val acc = api.account()
        if (!acc.auth) throw IOException("Identifiant ou mot de passe refusé par le serveur.")
        Db.updateAccount(src.id, acc)

        val steps = listOf(Kind.LIVE to "Chaînes", Kind.MOVIE to "Films", Kind.SERIES to "Séries")
        for ((kind, label) in steps) {
            progress("$label : catégories…")
            val cats = api.categories(kind)
            progress("$label : téléchargement…")
            val added = Db.replaceCatalog(src.id, kind, cats) { insert ->
                var n = 0
                api.streamItems(kind) { item ->
                    insert(item)
                    n++
                    if (n % 2000 == 0) progress("$label : ${fmt(n)}")
                }
                progress("$label : ${fmt(n)}")
            }
            // Nouvelles catégories : préréglage de langue de chaque profil
            Langs.onNewCategories(src.id, kind, added)
        }
        Db.setLastSync(src.id, System.currentTimeMillis())
    }

    fun fmt(n: Int): String = String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ')
}

/** Construction de la colonne des catégories (fusion des catégories similaires, masquage). */
object Catalog {
    private val QUALITY = Regex(
        """(?i)[\s|:\-]*[(\[]?\s*\b(sd|hd|fhd|full\s?hd|uhd|4k|8k|hevc|h\.?26[45]|2160p?|1080p?|720p?|(?:50|60)\s?fps)\b\s*[)\]]?"""
    )
    private val TRAILING = Regex("""[\s|:\-]+$""")
    private val SPACES = Regex("""\s{2,}""")

    /** "FR TV (HD)" -> "FR TV" */
    fun cleanName(n: String): String {
        val s = SPACES.replace(TRAILING.replace(QUALITY.replace(n, ""), ""), " ").trim()
        return s.ifBlank { n.trim() }
    }

    fun groups(src: Long, kind: Kind): List<Group> {
        val cats = Db.categories(src, kind).filter { !it.second }.map { it.first }
        if (!Prefs.mergeCategories) return cats.map { Group("c:" + it.id, it.name, listOf(it.id)) }
        val map = LinkedHashMap<String, MutableList<Category>>()
        for (c in cats) map.getOrPut(cleanName(c.name).lowercase()) { mutableListOf() }.add(c)
        return map.map { (k, list) ->
            Group("m:$k", if (list.size > 1) cleanName(list[0].name) else list[0].name, list.map { it.id })
        }
    }
}

/** Liste paginée : ne charge que les pages affichées (pour les catégories de dizaines de milliers d'éléments). */
class ItemList(
    val size: Int,
    /** Recherche d'une chaîne par son numéro : renvoie sa position dans la liste. */
    private val finder: ((Int) -> Int?)? = null,
    private val loader: (Int, Int) -> List<Item>
) {
    fun indexOfNum(num: Int): Int? = finder?.invoke(num)

    private val pages = object : LinkedHashMap<Int, List<Item>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Item>>?): Boolean = this.size > 24
    }

    operator fun get(i: Int): Item? {
        if (i < 0 || i >= size) return null
        val p = i / PAGE
        val page = pages[p] ?: loader(p * PAGE, PAGE).also { pages[p] = it }
        return page.getOrNull(i - p * PAGE)
    }

    companion object {
        const val PAGE = 150
        val EMPTY = ItemList(0) { _, _ -> emptyList() }

        fun of(list: List<Item>) = ItemList(list.size, { n -> list.indexOfFirst { it.num == n }.takeIf { it >= 0 } }) { o, l ->
            val from = o.coerceIn(0, list.size)
            list.subList(from, (from + l).coerceAtMost(list.size))
        }

        /** Liste paginée lue dans la base, avec recherche par numéro de chaîne. */
        fun query(src: Long, kind: Kind, catIds: List<String>?, q: String?): ItemList =
            ItemList(Db.count(src, kind, catIds, q), { n -> Db.indexOfNum(src, kind, catIds, q, n) }) { o, l ->
                Db.page(src, kind, catIds, q, o, l)
            }
    }
}
