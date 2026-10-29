package com.ilian.xtreamplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Liste de textes focusables (catégories, saisons, menu). */
class CategoryAdapter(
    private val layoutId: Int,
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    var items: List<Category> = emptyList()
        set(v) {
            field = v
            selectedIndex = -1
            notifyDataSetChanged()
        }

    private var selectedIndex = -1
    var selected: Int
        get() = selectedIndex
        set(v) {
            val old = selectedIndex
            selectedIndex = v
            if (old in items.indices) notifyItemChanged(old)
            if (v in items.indices) notifyItemChanged(v)
        }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(layoutId, parent, false) as TextView)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        h.tv.text = items[position].name
        h.tv.isSelected = position == selectedIndex
        h.tv.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.tv.setOnFocusChangeListener { _, hasFocus ->
            val p = h.bindingAdapterPosition
            if (hasFocus && p != RecyclerView.NO_POSITION) onFocus(p)
        }
    }
}

/** Grille paginée de chaînes, films ou séries. */
class ItemAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    var list: ItemList = ItemList.EMPTY
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    /** Type affiché par l'onglet (évite de charger les éléments pour choisir la mise en page). */
    var viewKind: Kind = Kind.LIVE
    var sourceId: Long = -1L

    /** Renseigné quand le préchargement de l'EPG est actif : le programme en cours s'affiche dans la liste. */
    var epgApi: XtreamApi? = null

    /** Ligne sous le titre d'une affiche (état d'un téléchargement…). */
    var subtitle: ((Item) -> String?)? = null

    /** Avancement de lecture entre 0 et 1, affiché en bas de l'affiche. */
    var progress: ((Item) -> Float?)? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView? = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView? = v.findViewById(R.id.sub)
        val star: View? = v.findViewById(R.id.star)
        val bar: ProgressBar? = v.findViewById(R.id.bar)
    }

    override fun getItemViewType(position: Int) = if (viewKind == Kind.LIVE) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == 0) R.layout.item_live else R.layout.item_poster
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        v.findViewById<View>(R.id.frame)?.clipToOutline = true
        val zoom = if (viewType == 0) 1.03f else 1.08f
        v.setOnFocusChangeListener { view, hasFocus -> Fx.focus(view, hasFocus, zoom) }
        return VH(v)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = list[position]
        h.itemView.setOnClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onClick(p)
        }
        h.itemView.setOnLongClickListener {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) onLongClick(p)
            true
        }
        if (item == null) {
            h.name.text = ""
            h.star?.visibility = View.GONE
            h.bar?.visibility = View.GONE
            return
        }
        h.name.text = item.name
        h.star?.visibility = if (Db.isFav(sourceId, item)) View.VISIBLE else View.GONE
        h.bar?.let { bar ->
            val p = progress?.invoke(item)
            if (p != null && p > 0f) {
                bar.visibility = View.VISIBLE
                bar.progress = (p * 1000).toInt().coerceIn(0, 1000)
            } else {
                bar.visibility = View.GONE
            }
        }

        // Programme en cours sous le nom de la chaîne, ou ligne d'info fournie par l'écran
        h.sub?.let { sub ->
            val api = epgApi
            sub.tag = item.id
            val custom = subtitle
            if (custom != null) {
                val t = custom(item)
                sub.text = t ?: ""
                sub.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
            } else if (api == null || item.kind != Kind.LIVE) {
                sub.visibility = View.GONE
            } else {
                sub.visibility = View.VISIBLE
                val cached = Epg.cached(item.id)
                if (cached != null) {
                    sub.text = Epg.nowAndNext(cached).first?.title ?: ""
                } else {
                    sub.text = ""
                    Epg.prefetch(api, item.id) { l ->
                        if (sub.tag == item.id) sub.text = Epg.nowAndNext(l).first?.title ?: ""
                    }
                }
            }
        }

        h.img?.let { iv ->
            val ph = if (item.kind == Kind.LIVE) R.drawable.ph_live else R.drawable.ph_poster
            if (item.icon.isNullOrBlank() && item.kind != Kind.LIVE && Tmdb.enabled) {
                val tag = "tmdb:" + item.key
                iv.tag = tag
                iv.setImageResource(ph)
                Tmdb.posterAsync(item) { url -> if (iv.tag == tag) Img.load(iv, url, ph) }
            } else {
                Img.load(iv, item.icon, ph)
            }
        }
    }
}

/** Liste des épisodes d'une saison, avec la progression de lecture. */
class EpisodeAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    var items: List<Item> = emptyList()
    var progress: Map<String, Db.Progress> = emptyMap()

    fun set(items: List<Item>, progress: Map<String, Db.Progress>) {
        this.items = items
        this.progress = progress
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
        val bar: ProgressBar = v.findViewById(R.id.bar)
        val num: TextView = v.findViewById(R.id.num)
        val seen: View = v.findViewById(R.id.seen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
        v.findViewById<View>(R.id.frame).clipToOutline = true
        v.setOnFocusChangeListener { view, hasFocus -> Fx.focus(view, hasFocus, 1.02f) }
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val p = progress[e.id]
        h.name.text = e.name
        val extra = e.extra ?: ""
        h.num.text = EPISODE.find(extra)?.groupValues?.get(1)?.let { "É$it" } ?: "É${position + 1}"
        // "Saison 1 · Épisode 3 · 00:42:00" -> "42 min"
        val duration = Meta.duration(extra.split(" · ").getOrNull(2))
        h.sub.text = when {
            p?.finished == true -> listOfNotNull(duration, "Vu").joinToString("  ·  ")
            p != null && p.pos > 0 -> listOfNotNull(duration, "Reprise à ${Ui.time(p.pos)}").joinToString("  ·  ")
            else -> duration ?: ""
        }
        h.seen.visibility = if (p?.finished == true) View.VISIBLE else View.GONE
        if (p != null && !p.finished && p.dur > 0 && p.pos > 0) {
            h.bar.visibility = View.VISIBLE
            h.bar.progress = (p.pos * 1000 / p.dur).toInt().coerceIn(0, 1000)
        } else {
            h.bar.visibility = View.GONE
        }
        Img.load(h.img, e.icon, R.drawable.ph_poster)
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onClick(i)
        }
        h.itemView.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) onLongClick(i)
            true
        }
    }

    companion object {
        private val EPISODE = Regex("""Épisode (\d+)""")
    }
}

/** Petites affiches en ligne (« Tu aimeras aussi »). */
class SmallPosterAdapter(
    private val onClick: (Item) -> Unit,
    private val onFocus: (Item?) -> Unit
) : RecyclerView.Adapter<SmallPosterAdapter.VH>() {

    var items: List<Item> = emptyList()
        set(v) {
            field = v
            notifyDataSetChanged()
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.img)
        val name: TextView = v.findViewById(R.id.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_small, parent, false)
        v.clipToOutline = true
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.name.text = item.name
        h.name.visibility = if (item.icon.isNullOrBlank()) View.VISIBLE else View.GONE
        Img.load(h.img, item.icon, R.drawable.ph_poster)
        h.itemView.setOnClickListener { onClick(item) }
        h.itemView.setOnFocusChangeListener { view, has ->
            Fx.focus(view, has, 1.08f)
            onFocus(if (has) item else null)
        }
    }
}
