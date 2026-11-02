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

/**
 * Recherche globale : chaînes, films et séries en même temps, résultats en direct.
 * Un nom d'acteur ou de réalisateur (« Omar Sy », « Nolan ») montre aussi ses films et séries du catalogue.
 */
class SearchActivity : BaseActivity() {

    private lateinit var src: Source
    private lateinit var et: EditText
    private lateinit var hint: TextView
    private lateinit var secPeople: View
    private lateinit var rvPeople: RecyclerView
    private lateinit var results: android.widget.ScrollView
    private val live = Section(Kind.LIVE)
    private val movies = Section(Kind.MOVIE)
    private val series = Section(Kind.SERIES)
    /** Films et séries de la personne choisie (les deux mélangés). */
    private val works = Section(Kind.MOVIE)
    private val peopleAdapter = PeopleAdapter()
    private var token = 0
    private val run = Runnable { search() }

    // Recherche par personne
    private var personToken = 0
    private var worksToken = 0
    private var titlesFound = 0
    private var peoplePending = false
    private var current: People.Person? = null
    /** Personne demandée depuis une fiche (bouton « Avec … ») : ses films s'affichent tout de suite. */
    private var forcedPerson: String? = null
    private var pendingPerson: People.Person? = null
    private val runPeople = Runnable { searchPeople() }
    private val switchPerson = Runnable { pendingPerson?.let { if (it.key != current?.key) choosePerson(it, focusWorks = false) } }

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
        works.bind(R.id.secWorks, R.id.titleWorks, R.id.rvWorks)
        secPeople = findViewById(R.id.secPeople)
        results = findViewById(R.id.results)
        rvPeople = findViewById(R.id.rvPeople)
        rvPeople.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        rvPeople.adapter = peopleAdapter
        rvPeople.itemAnimator = null
        Fx.lockRow(rvPeople)
        // Index des titres préparé pendant qu'on tape (recherche par acteur instantanée ensuite)
        Bg.task(this, { TitleIndex.warm(src) }) { }

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
        val person = intent.getStringExtra(EXTRA_PERSON)?.trim()?.takeIf { it.isNotEmpty() }
        if (person != null) {
            // Depuis une fiche : pas de clavier, les films de la personne arrivent directement
            forcedPerson = person
            et.setText(person)
            et.setSelection(et.text.length)
            Bg.main.removeCallbacks(run)
            search()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            return
        }
        et.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(et.windowToken, 0)
    }

    private fun firstResult(): View? {
        if (secPeople.visibility == View.VISIBLE && peopleAdapter.list.isNotEmpty()) rvPeople.getChildAt(0)?.let { return it }
        return listOf(works, live, movies, series).firstOrNull { it.items.isNotEmpty() && it.shown() }?.rv?.getChildAt(0)
    }

    private fun search() {
        val q = et.text.toString().trim()
        val t = ++token
        // Personne affichée qui ne correspond plus au texte : on la retire
        val cur = current
        if (!People.eligible(q) || (cur != null && !People.matches(q, cur.name))) hidePeople()
        Bg.main.removeCallbacks(runPeople)
        if (People.eligible(q)) {
            peoplePending = true
            Bg.main.postDelayed(runPeople, if (forcedPerson != null) 0L else 650L)
        } else {
            peoplePending = false
        }
        if (q.length < 2 && !(q.isNotEmpty() && q.all { it.isDigit() })) {
            hint.text = "Tape au moins 2 lettres : les résultats arrivent en direct."
            hint.visibility = View.VISIBLE
            listOf(live, movies, series).forEach { it.set(emptyList()) }
            titlesFound = 0
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
            titlesFound = l.size + m.size + s.size
            paintHint(q)
        }
    }

    /** Message sous le champ : rien trouvé (ni titre ni personne), ou recherche en cours. */
    private fun paintHint(q: String) {
        val people = secPeople.visibility == View.VISIBLE
        when {
            titlesFound > 0 || people -> hint.visibility = View.GONE
            peoplePending -> {
                hint.text = "Recherche…"
                hint.visibility = View.VISIBLE
            }
            else -> {
                hint.text = "Aucun résultat pour « $q »."
                hint.visibility = View.VISIBLE
            }
        }
    }

    // ---------- Acteurs et réalisateurs ----------

    private fun hidePeople() {
        personToken++
        worksToken++
        current = null
        peopleAdapter.list = emptyList()
        peopleAdapter.notifyDataSetChanged()
        secPeople.visibility = View.GONE
        works.set(emptyList())
    }

    /** Cherche les personnes qui portent ce nom, puis les films de la plus connue qui en a dans le catalogue. */
    private fun searchPeople() {
        val q = et.text.toString().trim()
        if (!People.eligible(q)) return
        val t = ++personToken
        val want = forcedPerson
        Bg.task(this, {
            val cands = People.candidates(q)
            val ordered = if (want != null) cands.sortedByDescending { normalize(it.name) == normalize(want) } else cands
            var chosen: People.Person? = null
            var found: List<Item> = emptyList()
            for (p in ordered.take(3)) {
                val w = People.works(src, p)
                if (w.isNotEmpty()) {
                    chosen = p
                    found = w
                    break
                }
            }
            Triple(ordered, chosen, found)
        }) { r ->
            if (t != personToken) return@task
            peoplePending = false
            val res = r.getOrNull()
            val chosen = res?.second
            if (res == null || chosen == null) {
                hidePeople()
                if (want != null) {
                    forcedPerson = null
                    hint.text = "Rien avec $want dans ton catalogue pour l'instant."
                    hint.visibility = View.VISIBLE
                } else {
                    paintHint(q)
                }
                return@task
            }
            current = chosen
            peopleAdapter.selected = chosen.key
            peopleAdapter.list = res.first
            peopleAdapter.notifyDataSetChanged()
            rvPeople.scrollToPosition(0)
            secPeople.visibility = View.VISIBLE
            showWorks(chosen, res.third)
            paintHint(q)
            if (want != null) {
                forcedPerson = null
                focusWorks()
            }
        }
    }

    /** Une autre personne est choisie dans la rangée : ses films remplacent les précédents. */
    private fun choosePerson(p: People.Person, focusWorks: Boolean) {
        current = p
        peopleAdapter.selected = p.key
        for (i in 0 until rvPeople.childCount) {
            val v = rvPeople.getChildAt(i)
            val pos = rvPeople.getChildAdapterPosition(v)
            v.isSelected = peopleAdapter.list.getOrNull(pos)?.key == p.key
        }
        val t = ++worksToken
        works.title("${p.title} · recherche…")
        Bg.task(this, { People.works(src, p) }) { r ->
            if (t != worksToken) return@task
            showWorks(p, r.getOrNull().orEmpty())
            if (focusWorks) focusWorks()
        }
    }

    private fun showWorks(p: People.Person, l: List<Item>) {
        val films = l.count { it.kind == Kind.MOVIE }
        val shows = l.size - films
        val count = listOfNotNull(
            if (films > 0) "$films film${if (films > 1) "s" else ""}" else null,
            if (shows > 0) "$shows série${if (shows > 1) "s" else ""}" else null
        ).joinToString(" et ")
        works.set(l, if (l.isEmpty()) "Rien de ${p.name} dans ton catalogue" else "${p.title} · $count", keepVisible = true)
    }

    private fun focusWorks() {
        works.rv.post {
            val first = works.rv.getChildAt(0)
            if (first != null) {
                hideKeyboard()
                first.requestFocus()
            }
        }
    }

    private fun initials(name: String): String =
        name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }

    /** Rangée des personnes : photo ronde, nom et métier ; passer dessus montre leurs films. */
    private inner class PeopleAdapter : RecyclerView.Adapter<PeopleAdapter.VH>() {
        var list: List<People.Person> = emptyList()
        var selected: String? = null

        inner class VH(v: View) : RecyclerView.ViewHolder(v)

        override fun getItemCount() = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_person, parent, false)
            v.findViewById<View>(R.id.photo).apply {
                outlineProvider = Profiles.OVAL
                clipToOutline = true
            }
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val p = list[position]
            val v = h.itemView
            v.findViewById<TextView>(R.id.name).text = p.name
            v.findViewById<TextView>(R.id.role).text = p.role
            v.findViewById<TextView>(R.id.initials).text = initials(p.name)
            Img.load(v.findViewById(R.id.photo), p.photo, android.R.color.transparent, 200)
            v.isSelected = p.key == selected
            v.setOnFocusChangeListener { view, has ->
                Fx.focus(view, has, 1.06f)
                Bg.main.removeCallbacks(switchPerson)
                if (has && p.key != current?.key) {
                    pendingPerson = p
                    Bg.main.postDelayed(switchPerson, 450)
                }
            }
            v.setOnClickListener {
                Bg.main.removeCallbacks(switchPerson)
                if (p.key != current?.key) choosePerson(p, focusWorks = true) else focusWorks()
            }
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
        Bg.main.removeCallbacks(runPeople)
        Bg.main.removeCallbacks(switchPerson)
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

        /** [custom] : titre à la place du nombre de résultats. [keepVisible] : rangée montrée même vide. */
        fun set(l: List<Item>, custom: String? = null, keepVisible: Boolean = false) {
            items = l
            notifyDataSetChanged()
            rv.scrollToPosition(0)
            box.visibility = if (l.isEmpty() && !keepVisible) View.GONE else View.VISIBLE
            rv.visibility = if (l.isEmpty()) View.GONE else View.VISIBLE
            val word = when (kind) {
                Kind.LIVE -> if (l.size > 1) "chaînes" else "chaîne"
                Kind.SERIES -> if (l.size > 1) "séries" else "série"
                else -> if (l.size > 1) "films" else "film"
            }
            title.text = custom ?: "${if (l.size >= 40) "40+" else l.size.toString()} $word"
        }

        fun title(t: String) {
            title.text = t
            box.visibility = View.VISIBLE
        }

        fun shown() = box.visibility == View.VISIBLE && rv.visibility == View.VISIBLE

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
            // Films et séries d'une personne sont mélangés : chacun s'ouvre selon son type
            v.setOnClickListener { open(if (kind == Kind.LIVE) Kind.LIVE else item.kind, items, position) }
            v.setOnLongClickListener {
                val added = Db.toggleFav(src.id, item)
                Ui.toast(this@SearchActivity, if (added) "Ajouté aux favoris" else "Retiré des favoris")
                true
            }
        }
    }

    companion object {
        const val EXTRA_QUERY = "q"
        /** Nom d'une personne (depuis une fiche) : ses films et séries s'affichent directement. */
        const val EXTRA_PERSON = "person"
    }
}
