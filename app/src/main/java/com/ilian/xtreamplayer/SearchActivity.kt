package com.ilian.xtreamplayer

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Recherche globale : chaînes, films et séries en même temps, résultats en direct. */
class SearchActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var et: EditText
    private lateinit var hint: TextView
    private val live = Section(Kind.LIVE)
    private val movies = Section(Kind.MOVIE)
    private val series = Section(Kind.SERIES)
    private var token = 0
    private val run = Runnable { search() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        src = Session.source() ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_search)
        et = findViewById(R.id.etQuery)
        hint = findViewById(R.id.tvHint)
        live.bind(R.id.secLive, R.id.titleLive, R.id.rvLive)
        movies.bind(R.id.secMovies, R.id.titleMovies, R.id.rvMovies)
        series.bind(R.id.secSeries, R.id.titleSeries, R.id.rvSeries)

        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Bg.main.removeCallbacks(run)
                Bg.main.postDelayed(run, 350)
            }
        })
        et.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                Bg.main.removeCallbacks(run)
                search()
                Bg.main.postDelayed({ firstResult()?.requestFocus() }, 250)
                true
            } else false
        }
        // Bas depuis le champ : premier résultat
        et.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) {
                val f = firstResult()
                if (f != null) {
                    hideKeyboard()
                    f.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }
        findViewById<View>(R.id.btnPhone).setOnClickListener {
            Ui.phoneInput(this, "Recherche", listOf(PhoneInput.Field("q", "Que cherches-tu ?", et.text.toString()))) { m ->
                et.setText(m["q"].orEmpty())
                et.setSelection(et.text.length)
            }
        }
        intent.getStringExtra(EXTRA_QUERY)?.let { et.setText(it) }
        et.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)
    }

    private fun firstResult(): View? = listOf(live, movies, series).firstOrNull { it.items.isNotEmpty() }?.rv?.getChildAt(0)

    private fun search() {
        val q = et.text.toString().trim()
        val t = ++token
        if (q.length < 2 && !(q.isNotEmpty() && q.all { it.isDigit() })) {
            hint.text = "Tape au moins 2 lettres : les résultats arrivent en direct."
            hint.visibility = View.VISIBLE
            listOf(live, movies, series).forEach { it.set(emptyList()) }
            return
        }
        hint.text = "Recherche…"
        Bg.task(this, {
            Triple(
                Db.page(src.id, Kind.LIVE, null, q, 0, 40),
                Db.page(src.id, Kind.MOVIE, null, q, 0, 60),
                Db.page(src.id, Kind.SERIES, null, q, 0, 60)
            )
        }) { r ->
            if (t != token) return@task
            val (l, m, s) = r.getOrNull() ?: Triple(emptyList(), emptyList(), emptyList())
            live.set(l)
            movies.set(m)
            series.set(s)
            val n = l.size + m.size + s.size
            hint.text = if (n == 0) "Aucun résultat pour « $q »." else ""
            hint.visibility = if (n == 0) View.VISIBLE else View.GONE
        }
    }

    private fun open(kind: Kind, list: List<Item>, i: Int) {
        val item = list[i]
        when (kind) {
            Kind.LIVE -> Ui.play(this, ItemList.of(list), i)
            Kind.SERIES -> startActivity(Intent(this, SeriesActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
            else -> startActivity(Intent(this, MovieActivity::class.java).putExtra(MainActivity.EXTRA_ITEM, item.toJson().toString()))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour depuis les résultats : on revient au champ de recherche
        if (!et.hasFocus() && et.text.isNotEmpty()) {
            et.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(run)
        super.onDestroy()
    }

    /** Une rangée de résultats (chaînes, films ou séries). */
    private inner class Section(val kind: Kind) : RecyclerView.Adapter<Section.VH>() {
        var items: List<Item> = emptyList()
        lateinit var rv: RecyclerView
        private lateinit var box: View
        private lateinit var title: TextView

        fun bind(boxId: Int, titleId: Int, rvId: Int) {
            box = findViewById(boxId)
            title = findViewById(titleId)
            rv = findViewById(rvId)
            rv.layoutManager = LinearLayoutManager(this@SearchActivity, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = this
            rv.itemAnimator = null
            Fx.lockRow(rv)
        }

        fun set(l: List<Item>) {
            items = l
            notifyDataSetChanged()
            rv.scrollToPosition(0)
            box.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            val word = when (kind) {
                Kind.LIVE -> if (l.size > 1) "chaînes" else "chaîne"
                Kind.SERIES -> if (l.size > 1) "séries" else "série"
                else -> if (l.size > 1) "films" else "film"
            }
            title.text = "${if (l.size >= 40) "40+" else l.size.toString()} $word"
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (kind == Kind.LIVE) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == 0) R.layout.item_search_channel else R.layout.item_card
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            if (viewType == 1) {
                v.clipToOutline = true
                Fx.shine(v)
            }
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, if (viewType == 0) 1.04f else 1.08f) }
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val item = items[position]
            val v = h.itemView
            if (kind == Kind.LIVE) {
                v.findViewById<TextView>(R.id.name).text = item.name
                v.findViewById<TextView>(R.id.sub).text = item.num?.let { "Chaîne $it" } ?: ""
                Img.load(v.findViewById(R.id.img), item.icon, R.drawable.ph_live)
            } else {
                val noImage = item.icon.isNullOrBlank()
                val name = v.findViewById<TextView>(R.id.name)
                name.text = Tmdb.cleanTitle(item.name).first
                name.visibility = if (noImage) View.VISIBLE else View.GONE
                v.findViewById<View>(R.id.sub).visibility = View.GONE
                v.findViewById<View>(R.id.bar).visibility = View.GONE
                v.findViewById<View>(R.id.badge).visibility = View.GONE
                v.findViewById<View>(R.id.overlay).visibility = if (noImage) View.VISIBLE else View.GONE
                Img.load(v.findViewById<ImageView>(R.id.img), item.icon, R.drawable.ph_poster, 300)
            }
            v.setOnClickListener { open(kind, items, position) }
            v.setOnLongClickListener {
                val added = Db.toggleFav(src.id, item)
                Ui.toast(this@SearchActivity, if (added) "Ajouté aux favoris" else "Retiré des favoris")
                true
            }
        }
    }

    companion object {
        const val EXTRA_QUERY = "q"
    }
}
