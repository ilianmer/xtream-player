package com.ilian.xtreamplayer

import java.util.Calendar

/**
 * Préchargement pendant l'animation d'ouverture : sélection « À la une », images,
 * scores du sport et chaînes de sport. L'accueil s'affiche ensuite sans attendre.
 */
object Preload {
    @Volatile
    var featured: List<Item>? = null
    @Volatile
    private var featuredKey = ""

    fun key(): String {
        val c = Calendar.getInstance()
        return "${Db.profile}:${Recommender.slot()}:${c.get(Calendar.DAY_OF_YEAR)}"
    }

    /** Sélection préparée pour ce profil et ce créneau, sinon null. */
    fun featuredNow(): List<Item>? = featured?.takeIf { featuredKey == key() }

    /** Bloquant : à lancer en arrière-plan. */
    fun run(src: Source) {
        try {
            for (k in listOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES)) Catalog.groups(src.id, k)
            val f = Recommender.featured(src.id, 8)
            featured = f
            featuredKey = key()
            // Les fonds floutés des premiers titres sont prêts avant l'arrivée sur l'accueil
            for (it in f.take(3)) Img.warmBlur(it.icon)
        } catch (_: Exception) {
        }
    }

    /** Sport et chaînes de sport : plus long, on n'attend pas la fin pour ouvrir l'appli. */
    fun runSlow(src: Source) {
        try {
            SportChannels.candidates(src.id)
            Sport.get(60_000L)
        } catch (_: Exception) {
        }
        // Guide TV externe : remis à jour en arrière-plan s'il a plus de 12 h
        try {
            Session.api()?.let { XmlTv.refreshIfNeeded(src, it) }
        } catch (_: Exception) {
        }
    }
}
