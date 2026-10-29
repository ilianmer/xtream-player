package com.ilian.xtreamplayer

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.util.Locale

/** Téléchargements hors ligne, gérés par le gestionnaire de téléchargements d'Android. */
/** Fonctions activables. Les téléchargements sont retirés pour l'instant (appli TV) : remettre true pour les réactiver. */
object Features {
    const val DOWNLOADS = false
}

object Downloads {
    data class Status(val row: Db.DlRow, val state: Int, val done: Long, val total: Long, val localUri: String?) {
        val finished: Boolean get() = state == DownloadManager.STATUS_SUCCESSFUL && localUri != null

        val label: String
            get() = when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> "Terminé · ${Downloads.size(total)}"
                DownloadManager.STATUS_FAILED -> "Échec"
                DownloadManager.STATUS_PAUSED -> "En pause (réseau)"
                DownloadManager.STATUS_PENDING -> "En attente"
                DownloadManager.STATUS_RUNNING ->
                    if (total > 0) "En cours · ${done * 100 / total} % de ${Downloads.size(total)}" else "En cours · ${Downloads.size(done)}"
                else -> "Fichier introuvable"
            }

        /** Élément jouable hors ligne. */
        fun playable(): Item? = if (finished) row.item.copy(url = localUri) else null
    }

    private fun dm(ctx: Context) = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun size(b: Long): String = when {
        b >= 1_000_000_000L -> String.format(Locale.FRANCE, "%.1f Go", b / 1e9)
        b >= 1_000_000L -> String.format(Locale.FRANCE, "%.0f Mo", b / 1e6)
        b > 0 -> String.format(Locale.FRANCE, "%.0f Ko", b / 1e3)
        else -> "0 Mo"
    }

    fun freeSpace(ctx: Context): Long = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.usableSpace ?: 0L

    /** Lance le téléchargement. Renvoie un message d'erreur, ou null si c'est parti. */
    fun start(ctx: Context, src: Long, item: Item, url: String): String? {
        if (ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) == null) return "Stockage indisponible sur cet appareil."
        if (freeSpace(ctx) < 500_000_000L) return "Moins de 500 Mo libres sur le stick. Supprime des téléchargements d'abord."
        val ext = item.ext ?: "mp4"
        val safe = item.name.replace(Regex("""[^\p{L}\p{N} ._-]"""), "").trim().take(70).ifBlank { "video" }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(item.name)
            .setDescription("Xtream Player")
            .addRequestHeader("User-Agent", Prefs.userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, "${safe}_${item.id}.$ext")
            .setAllowedOverMetered(true)
        return try {
            val id = dm(ctx).enqueue(req)
            Db.addDownload(id, src, item)
            null
        } catch (e: Exception) {
            "Téléchargement impossible sur cet appareil (${e.message ?: "erreur"})."
        }
    }

    fun statuses(ctx: Context): List<Status> {
        if (!Features.DOWNLOADS) return emptyList()
        val rows = Db.downloads()
        if (rows.isEmpty()) return emptyList()
        val byId = HashMap<Long, Status>()
        try {
            dm(ctx).query(DownloadManager.Query().setFilterById(*rows.map { it.dmId }.toLongArray())).use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iSt = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTot = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iUri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val row = rows.firstOrNull { it.dmId == id } ?: continue
                    byId[id] = Status(row, c.getInt(iSt), c.getLong(iDone), c.getLong(iTot), if (c.isNull(iUri)) null else c.getString(iUri))
                }
            }
        } catch (_: Exception) {
        }
        return rows.map { byId[it.dmId] ?: Status(it, -1, 0, 0, null) }
    }

    fun find(ctx: Context, src: Long, item: Item): Status? =
        statuses(ctx).firstOrNull { it.row.sourceId == src && it.row.item.kind == item.kind && it.row.item.id == item.id }

    /** Téléchargements désactivés : on annule ceux en cours et on libère la place des fichiers. */
    fun purgeAll(ctx: Context) {
        for (r in Db.downloads()) remove(ctx, r.dmId)
    }

    /** Annule le téléchargement et supprime le fichier. */
    fun remove(ctx: Context, dmId: Long) {
        try {
            dm(ctx).remove(dmId)
        } catch (_: Exception) {
        }
        Db.removeDownload(dmId)
    }
}
