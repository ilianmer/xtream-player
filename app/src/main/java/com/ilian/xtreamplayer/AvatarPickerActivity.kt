package com.ilian.xtreamplayer

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Création de l'avatar, avec l'aperçu en direct : à gauche l'avatar en grand, qui change à chaque
 * personnage ou couleur survolé ; à droite la galerie par thème, le fond et l'anneau.
 * OK valide ce qui est survolé ; Retour garde ce qui a été validé (rien validé : l'ancien avatar reste).
 */
class AvatarPickerActivity : BaseActivity() {

    private var id = 0L
    private lateinit var preview: View
    private lateinit var glow: View
    private lateinit var name: TextView
    private lateinit var grid: RecyclerView

    /** Ce qui est validé (enregistré). */
    private var avatar: Avatars.Avatar = Avatars.ALL.first()
    private var bg: Int? = null
    private var ring = 0

    /** Ligne de la grille : titre de thème ou avatar. */
    private class Entry(val theme: String?, val avatar: Avatars.Avatar?)

    private val entries = ArrayList<Entry>()
    private val ease = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)

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
        glow = findViewById(R.id.glow)
        name = findViewById(R.id.pickName)
        grid = findViewById(R.id.grid)
        findViewById<TextView>(R.id.pickWho).text = p.name
        findViewById<TextView>(R.id.pickTitle).text = "L'avatar de ${p.name}"
        avatar = Avatars.of(p)
        bg = Avatars.BACKGROUNDS.indexOfFirst { it === Avatars.bgOf(p) }.takeIf { it >= 0 }
        ring = Math.floorMod(p.color, Profiles.COLORS.size)
        findViewById<View>(R.id.btnDone).apply {
            setOnClickListener { finish() }
            setOnFocusChangeListener { v, has ->
                Fx.focus(v, has, 1.05f)
                if (has) show(avatar, bg, ring, pop = false)
            }
        }
        show(avatar, bg, ring, pop = false)
        buildSwatches()

        for (t in Avatars.THEMES) {
            entries.add(Entry(t, null))
            for (a in Avatars.ALL.filter { it.theme == t }) entries.add(Entry(null, a))
        }
        val cols = 6
        val lm = GridLayoutManager(this, cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (entries[position].theme != null) cols else 1
        }
        grid.layoutManager = lm
        grid.itemAnimator = null
        grid.adapter = Adapter()
        // Focus de départ : l'avatar actuel
        val start = entries.indexOfFirst { it.avatar?.id == avatar.id }.coerceAtLeast(1)
        grid.scrollToPosition(start)
        grid.post {
            grid.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus()
                ?: grid.postDelayed({ grid.findViewHolderForAdapterPosition(start)?.itemView?.requestFocus() }, 120)
        }
        // Arrivée : l'aperçu éclot
        preview.scaleX = 0.8f
        preview.scaleY = 0.8f
        preview.alpha = 0f
        preview.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(460)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.3f)).start()
    }

    /** Aperçu en grand : personnage [a], fond [bgIndex] (null : le sien), anneau [ringIndex]. */
    private fun show(a: Avatars.Avatar, bgIndex: Int?, ringIndex: Int, pop: Boolean = true) {
        val b = bgIndex?.let { Avatars.BACKGROUNDS.getOrNull(it) }
        preview.background = AvatarDrawable(a, Profiles.COLORS[ringIndex], b)
        name.text = "${a.name} · ${a.theme}"
        glow.post { Profiles.glow(glow, b?.top ?: a.top) }
        if (pop) {
            preview.animate().cancel()
            preview.scaleX = 0.9f
            preview.scaleY = 0.9f
            preview.animate().scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }
    }

    // ---------- Fond et anneau ----------

    private fun swatch(top: Int, bottom: Int, label: String?): FrameLayout {
        val d = resources.displayMetrics.density
        val box = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            foreground = getDrawable(R.drawable.profile_ring)
            clipChildren = false
        }
        val dot = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)).apply {
                shape = GradientDrawable.OVAL
            }
        }
        box.addView(dot, FrameLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.CENTER))
        if (label != null) {
            box.addView(TextView(this, null, 0, R.style.Semi).apply {
                text = label
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.white))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        box.setOnFocusChangeListener { v, has -> Fx.focus(v, has, 1.2f) }
        return box
    }

    /** Pastille choisie : petit anneau blanc autour. */
    private fun mark(row: LinearLayout, selected: Int) {
        val d = resources.displayMetrics.density
        for (i in 0 until row.childCount) {
            val dot = (row.getChildAt(i) as FrameLayout).getChildAt(0)
            (dot.background as? GradientDrawable)?.setStroke(if (i == selected) (2 * d).toInt() else 0, getColor(R.color.white))
        }
    }

    private fun buildSwatches() {
        val d = resources.displayMetrics.density
        val bgRow = findViewById<LinearLayout>(R.id.bgRow)
        val ringRow = findViewById<LinearLayout>(R.id.ringRow)
        val size = (34 * d).toInt()
        // Fond : « Auto » (celui du personnage), puis la palette
        val auto = swatch(0xFF59607A.toInt(), 0xFF2A2F44.toInt(), "A")
        auto.setOnFocusChangeListener { v, has ->
            Fx.focus(v, has, 1.2f)
            if (has) show(avatar, null, ring)
        }
        auto.setOnClickListener {
            bg = null
            Avatars.setBg(id, null)
            mark(bgRow, 0)
            Ui.toast(this, "Fond de l'avatar : automatique")
        }
        bgRow.addView(auto, LinearLayout.LayoutParams(size, size))
        Avatars.BACKGROUNDS.forEachIndexed { i, b ->
            val v = swatch(b.top, b.bottom, null)
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.2f)
                if (has) show(avatar, i, ring)
            }
            v.setOnClickListener {
                bg = i
                Avatars.setBg(id, i)
                mark(bgRow, i + 1)
                Ui.toast(this, "Fond « ${b.name} » choisi")
            }
            bgRow.addView(v, LinearLayout.LayoutParams(size, size))
        }
        mark(bgRow, (bg ?: -1) + 1)
        // Anneau : couleurs du profil
        Profiles.COLORS.forEachIndexed { i, c ->
            val v = swatch(c, c, null)
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.2f)
                if (has) show(avatar, bg, i)
            }
            v.setOnClickListener {
                ring = i
                Db.profileById(id)?.let { p -> Db.updateProfile(p.id, p.name, i) }
                mark(ringRow, i)
                Ui.toast(this, "Anneau ${Profiles.COLOR_NAMES[i].lowercase()} choisi")
            }
            ringRow.addView(v, LinearLayout.LayoutParams(size, size))
        }
        mark(ringRow, ring)
        // Quand on quitte les couleurs, l'aperçu revient à ce qui est validé
        for (row in listOf(bgRow, ringRow)) row.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && old.parent === row && new != null && new.parent !== bgRow && new.parent !== ringRow && !isInGrid(new)) {
                show(avatar, bg, ring, pop = false)
            }
        }
    }

    private fun isInGrid(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === grid) return true
            p = p.parent
        }
        return false
    }

    private fun pick(a: Avatars.Avatar) {
        avatar = a
        Avatars.set(id, a.id)
        show(a, bg, ring)
        grid.adapter?.notifyDataSetChanged()
        Ui.toast(this, "« ${a.name} » choisi. Règle le fond et l'anneau si tu veux, puis Terminé.")
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size
        override fun getItemViewType(position: Int) = if (entries[position].theme != null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val d = resources.displayMetrics.density
            val v = if (viewType == 0) {
                TextView(this@AvatarPickerActivity, null, 0, R.style.Section).apply {
                    setPadding((6 * d).toInt(), (12 * d).toInt(), 0, (2 * d).toInt())
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
            v.findViewById<View>(R.id.check).visibility = if (a.id == avatar.id) View.VISIBLE else View.GONE
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.16f)
                // Aperçu en direct, sans rien valider
                if (has) show(a, bg, ring)
            }
            v.setOnClickListener { pick(a) }
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
