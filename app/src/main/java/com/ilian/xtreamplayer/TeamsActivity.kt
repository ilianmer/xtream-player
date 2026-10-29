package com.ilian.xtreamplayer

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Liste des équipes par compétition : on coche celles qu'on suit. */
class TeamsActivity : BaseActivity() {

    private lateinit var rvLeagues: RecyclerView
    private lateinit var rvTeams: RecyclerView
    private lateinit var tvLeague: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvStatus: TextView

    private val leagues = Sport.LEAGUES.filter { it.sport == "soccer" || it.sport == "basketball" || it.sport == "rugby" }
    private val leagueAdapter = CategoryAdapter(R.layout.item_category, ::onLeagueFocus, ::onLeagueClick)
    private val teamAdapter = TeamAdapter()
    private val loaded = HashMap<String, List<Sport.Side>>()
    private var focused = -1
    private var current = -1
    private var token = 0
    private val selectFocused = Runnable { if (focused != current) select(focused) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teams)
        rvLeagues = findViewById(R.id.rvLeagues)
        rvTeams = findViewById(R.id.rvTeams)
        tvLeague = findViewById(R.id.tvLeague)
        tvCount = findViewById(R.id.tvCount)
        tvStatus = findViewById(R.id.tvStatus)

        rvLeagues.layoutManager = LinearLayoutManager(this)
        rvLeagues.adapter = leagueAdapter
        rvLeagues.itemAnimator = null
        leagueAdapter.items = listOf(Category("mine", "Mes équipes")) + leagues.map { Category(it.path, it.label) }
        rvTeams.layoutManager = GridLayoutManager(this, 6)
        rvTeams.adapter = teamAdapter
        rvTeams.itemAnimator = null

        select(if (Sport.followed().isEmpty()) 1 else 0)
        rvLeagues.post { rvLeagues.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus() }
    }

    private fun onLeagueFocus(p: Int) {
        focused = p
        Bg.main.removeCallbacks(selectFocused)
        Bg.main.postDelayed(selectFocused, 300)
    }

    private fun onLeagueClick(p: Int) {
        Bg.main.removeCallbacks(selectFocused)
        if (p != current) select(p)
        rvTeams.post { rvTeams.getChildAt(0)?.requestFocus() }
    }

    private fun paintCount() {
        val n = Sport.followed().size
        tvCount.text = when (n) {
            0 -> "Aucune équipe suivie"
            1 -> "1 équipe suivie"
            else -> "$n équipes suivies"
        }
    }

    private fun select(p: Int) {
        if (p !in 0 until leagueAdapter.itemCount) return
        current = p
        leagueAdapter.selected = p
        paintCount()
        val t = ++token
        if (p == 0) {
            tvLeague.text = "Mes équipes"
            val mine = Sport.followedSides()
            teamAdapter.set(mine)
            tvStatus.text = if (mine.isEmpty()) "Choisis une compétition à gauche,\npuis coche tes équipes." else ""
            return
        }
        val l = leagues[p - 1]
        tvLeague.text = l.label
        loaded[l.path]?.let {
            teamAdapter.set(it)
            tvStatus.text = if (it.isEmpty()) "Aucune équipe disponible." else ""
            return
        }
        teamAdapter.set(emptyList())
        tvStatus.text = "Chargement des équipes…"
        Bg.task(this, { Sport.teams(l) }) { r ->
            if (t != token) return@task
            r.onSuccess {
                loaded[l.path] = it
                teamAdapter.set(it)
                tvStatus.text = if (it.isEmpty()) "Aucune équipe disponible." else ""
            }.onFailure { tvStatus.text = "Liste indisponible : ${Bg.err(it)}" }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (rvTeams.hasFocus()) {
            rvLeagues.findViewHolderForAdapterPosition(current)?.itemView?.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        Bg.main.removeCallbacks(selectFocused)
        super.onDestroy()
    }

    private inner class TeamAdapter : RecyclerView.Adapter<TeamAdapter.VH>() {
        private var items: List<Sport.Side> = emptyList()

        fun set(l: List<Sport.Side>) {
            items = l
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val crest: View = v.findViewById(R.id.crest)
            val name: TextView = v.findViewById(R.id.name)
            val check: View = v.findViewById(R.id.check)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_team, parent, false)
            v.setOnFocusChangeListener { view, has -> Fx.focus(view, has, 1.06f) }
            v.findViewById<TextView>(R.id.crestText).textSize = 12f
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            h.name.text = s.name
            val on = Sport.isFollowed(s)
            h.itemView.isSelected = on
            h.check.visibility = if (on) View.VISIBLE else View.GONE
            Crest.bind(h.crest, s, 128)
            h.itemView.setOnClickListener {
                val added = Sport.toggleFollow(s)
                h.itemView.isSelected = added
                h.check.visibility = if (added) View.VISIBLE else View.GONE
                paintCount()
            }
        }
    }
}
