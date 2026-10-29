package com.ilian.xtreamplayer

import java.util.Calendar
import kotlin.random.Random

/**
 * Sélection « À la une » : les titres qui conviennent le mieux au moment.
 *
 * Chaque titre reçoit une note :
 * - le créneau (matin : famille, animation, documentaires ; midi : comédie ; soir : action, thriller ;
 *   nuit : horreur, suspense), d'après le nom de sa catégorie ;
 * - la période de l'année (Halloween, Noël, Saint-Valentin, été…) ;
 * - la nouveauté (ajouté récemment par le fournisseur) et la note ;
 * - les goûts : catégories des films et séries regardés ou mis en favori ;
 * - un peu de hasard, fixe pour le créneau (la sélection ne change pas à chaque retour sur l'accueil).
 * Les titres déjà vus sont écartés et on évite plus de deux titres de la même catégorie.
 */
object Recommender {
    enum class Slot(val label: String) { MATIN("Ce matin"), MIDI("Ce midi"), APRES_MIDI("Cet après-midi"), SOIR("Ce soir"), NUIT("Cette nuit") }

    fun slot(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Slot = when (hour) {
        in 5..10 -> Slot.MATIN
        in 11..13 -> Slot.MIDI
        in 14..17 -> Slot.APRES_MIDI
        in 18..22 -> Slot.SOIR
        else -> Slot.NUIT
    }

    private val SLOT_WORDS = mapOf(
        Slot.MATIN to listOf("anim", "dessin", "enfant", "kid", "famil", "jeunesse", "docu", "nature", "comed", "music", "cartoon", "disney", "pixar"),
        Slot.MIDI to listOf("comed", "famil", "aventure", "adventure", "romance", "sitcom", "anim", "cuisine", "telerealite"),
        Slot.APRES_MIDI to listOf("aventure", "adventure", "anim", "famil", "fantas", "comed", "drame", "drama", "western"),
        Slot.SOIR to listOf("action", "thriller", "polic", "crime", "drame", "drama", "science", "sci-fi", "sf", "guerre", "war", "suspense", "box office", "blockbuster", "netflix", "prime", "nouveau"),
        Slot.NUIT to listOf("horreur", "horror", "epouvante", "thriller", "myster", "science", "sf", "fantastique", "suspense")
    )

    private fun seasonWords(month: Int): List<String> = when (month) {
        Calendar.OCTOBER -> listOf("horreur", "horror", "epouvante", "halloween", "fantastique")
        Calendar.NOVEMBER -> listOf("polic", "drame", "thriller", "crime")
        Calendar.DECEMBER -> listOf("noel", "christmas", "famil", "fete", "anim", "disney")
        Calendar.JANUARY -> listOf("drame", "aventure", "biopic")
        Calendar.FEBRUARY -> listOf("romance", "amour", "romant", "love", "comed")
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> listOf("aventure", "comed", "action", "ete", "summer", "plage", "anim")
        Calendar.SEPTEMBER -> listOf("drame", "comed", "nouveau")
        else -> listOf("aventure", "comed")
    }

    private fun matches(name: String, words: List<String>): Int {
        val n = normalize(name)
        return words.count { n.contains(it) }
    }

    /** Titres à mettre en avant pour [kind] (films ou séries). Bloquant mais rapide (base locale). */
    fun pick(src: Long, kind: Kind, count: Int, now: Calendar = Calendar.getInstance()): List<Item> {
        val slot = slot(now.get(Calendar.HOUR_OF_DAY))
        val season = seasonWords(now.get(Calendar.MONTH))
        val taste = Db.tasteCategories(src, kind)
        val maxTaste = (taste.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val seen = Db.watchedIds(src, kind)
        val groups = Catalog.groups(src, kind)

        // Note de chaque catégorie selon le moment et les goûts
        val catScore = HashMap<String, Double>()
        val groupScore = groups.map { g ->
            val slotHit = matches(g.name, SLOT_WORDS[slot].orEmpty())
            val seasonHit = matches(g.name, season)
            val like = g.catIds.sumOf { taste[it] ?: 0 }.toDouble() / maxTaste
            val sc = slotHit * 3.0 + seasonHit * 2.0 + like * 4.0
            for (id in g.catIds) catScore[id] = sc
            g to sc
        }

        // Réserve de candidats : nouveautés + catégories les mieux notées + catégories préférées
        val pool = LinkedHashMap<String, Item>()
        for (it in Db.latest(src, kind, 80)) pool[it.id] = it
        for ((g, _) in groupScore.sortedByDescending { it.second }.take(8)) {
            for (it in Db.page(src, kind, g.catIds, null, 0, 40)) pool.putIfAbsent(it.id, it)
        }
        if (pool.size < count * 3) for (it in Db.page(src, kind, null, null, 0, 120)) pool.putIfAbsent(it.id, it)

        val day = now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR)
        val rnd = Random(day * 10L + slot.ordinal + src)
        val nowSec = System.currentTimeMillis() / 1000
        val scored = pool.values
            .filter { it.id !in seen && !it.icon.isNullOrBlank() }
            .map { item ->
                var s = catScore[item.catId] ?: 0.0
                val age = nowSec - item.added
                if (item.added > 0) s += when {
                    age < 14 * 86_400L -> 4.0
                    age < 45 * 86_400L -> 2.0
                    age < 120 * 86_400L -> 0.8
                    else -> 0.0
                }
                val r = item.rating?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
                if (r >= 7.5) s += 2.0 else if (r >= 6.5) s += 1.0
                s += matches(item.name, season) * 1.5
                s += rnd.nextDouble() * 2.5
                item to s
            }
            .sortedByDescending { it.second }

        // Diversité : pas plus de deux titres de la même catégorie
        val out = ArrayList<Item>()
        val perCat = HashMap<String, Int>()
        for ((item, _) in scored) {
            val c = item.catId ?: ""
            if ((perCat[c] ?: 0) >= 2) continue
            perCat[c] = (perCat[c] ?: 0) + 1
            out.add(item)
            if (out.size >= count) break
        }
        return out
    }

    /** Films et séries mélangés : deux films puis une série. */
    fun featured(src: Long, total: Int = 8): List<Item> {
        val movies = pick(src, Kind.MOVIE, total)
        val series = pick(src, Kind.SERIES, total / 3 + 1)
        val out = ArrayList<Item>()
        var m = 0
        var s = 0
        while (out.size < total && (m < movies.size || s < series.size)) {
            if (m < movies.size) out.add(movies[m++])
            if (out.size < total && m < movies.size) out.add(movies[m++])
            if (out.size < total && s < series.size) out.add(series[s++])
        }
        return out
    }
}
