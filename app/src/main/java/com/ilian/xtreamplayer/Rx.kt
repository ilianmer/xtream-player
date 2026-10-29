package com.ilian.xtreamplayer

/**
 * Expressions régulières compilées une seule fois (les recompiler à chaque affichage d'une carte
 * ou à chaque ligne du guide coûte cher sur un stick TV).
 */
object Rx {
    val NON_ALNUM_SPACE = Regex("[^a-z0-9 ]")
    val NON_ALNUM_PLUS_SPACE = Regex("[^a-z0-9+ ]")
    val NON_ALNUM = Regex("[^a-z0-9]")
    val NON_ALNUM_RUN = Regex("[^a-z0-9]+")
    val NON_ALNUM_PLUS_RUN = Regex("[^a-z0-9+]+")
    val SPACES = Regex("\\s+")
    val SPACES2 = Regex("\\s{2,}")
    val MARKS = Regex("\\p{M}+")
    val TITLE_PUNCT = Regex("""[\[\]()|_.]+""")
    val FR_WORD = Regex("""\b(fr|france|french)\b""")
    val LANG_PREFIX = Regex("""^[^a-z0-9]*[a-z]{2,3}\s*[|:]\s*""")
    val UFC_NUM = Regex("""ufc ?(\d{3})""")
    val HTML_TAG = Regex("<[^>]+>")
    val FILE_UNSAFE = Regex("""[^\p{L}\p{N} ._-]""")
}

/**
 * Fils de travail en priorité basse : le fil de l'interface passe toujours en premier,
 * les téléchargements et calculs se font dans les temps morts (défilement plus régulier).
 */
object Pools {
    private fun factory(name: String) = java.util.concurrent.ThreadFactory { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            } catch (_: Exception) {
            }
            r.run()
        }, name).apply { isDaemon = true }
    }

    fun fixed(n: Int, name: String): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(n, factory(name))

    fun single(name: String): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor(factory(name))

    fun bounded(queue: Int, threads: Int, name: String) = java.util.concurrent.ThreadPoolExecutor(
        threads, threads, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(queue),
        factory(name), java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    )
}
