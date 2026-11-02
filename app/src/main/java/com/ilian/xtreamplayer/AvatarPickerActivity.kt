package com.ilian.xtreamplayer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Galerie d'avatars : les personnages rangés par thème ; l'aperçu en haut suit le focus, OK choisit. */
class AvatarPickerActivity : BaseActivity() {

    private var id = 0L
    private lateinit var preview: View
    private lateinit var name: TextView
    private var current: String? = null

    /** Ligne de la grille : titre de thème ou avatar. */
    private class Entry(val theme: String?, val avatar: Avatars.Avatar?)

    private val entries = ArrayList<Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra(EXTRA_PROFILE, 0L)
        val p = Db.profileById(id) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_avatar_picker)
        preview = findViewById(R.id.preview)
        preview.outlineProvider = Profiles.OVAL
        name = findViewById(R.id.pickName)
        findViewById<TextView>(R.id.pickTitle).text = "Choisis l'avatar de ${p.name}"
        current = Avatars.of(p).id
        Avatars.byId(current)?.let { show(it, animate = false) }

        for (t in Avatars.THEMES) {
            entries.add(Entry(t, null))
            for (a in Avatars.ALL.filter { it.theme == t }) entries.add(Entry(null, a))
        }
        val grid = findViewById<RecyclerView>(R.id.grid)
        val cols = 9
        val lm = GridLayoutManager(this, cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (entries[position].theme != null) cols else 1
        }
        grid.layoutManager = lm
        grid.itemAnimator = null
        grid.adapter = Adapter()
        // Focus de départ : l'avatar actuel
        val start = entries.indexOfFirst { it.avatar?.id == current }.coerceAtLeast(1)
        grid.scrollToPosition(start)
        grid.post {
            grid.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus()
                ?: grid.postDelayed({ grid.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }, 120)
        }
    }

    private fun show(a: Avatars.Avatar, animate: Boolean) {
        preview.background = AvatarDrawable(a)
        name.text = "${a.name} · ${a.theme}"
        if (animate) {
            preview.animate().cancel()
            preview.scaleX = 0.86f
            preview.scaleY = 0.86f
            preview.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    private fun pick(a: Avatars.Avatar) {
        Avatars.set(id, a.id)
        Ui.toast(this, "Avatar « ${a.name} » choisi")
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size
        override fun getItemViewType(position: Int) = if (entries[position].theme != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = resources.displayMetrics.density
            val v = if (viewType == 0) {
                TextView(this@AvatarPickerActivity, null, 0, R.style.Section).apply {
                    setPadding((6 * d).toInt(), (14 * d).toInt(), 0, (4 * d).toInt())
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            } else {
                LayoutInflater.from(parent.context).inflate(R.layout.item_avatar, parent, false).also {
                    it.findViewById<View>(R.id.avatar).outlineProvider = Profiles.OVAL
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            if (e.theme != null) {
                (h.itemView as TextView).text = e.theme
                return
            }
            val a = e.avatar ?: return
            val v = h.itemView
            v.findViewById<View>(R.id.avatar).background = AvatarDrawable(a)
            v.findViewById<View>(R.id.check).visibility = if (a.id == current) View.VISIBLE else View.GONE
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.16f)
                if (has) show(a, animate = true)
            }
            v.setOnClickListener { pick(a) }
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
