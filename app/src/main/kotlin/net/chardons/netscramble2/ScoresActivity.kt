package net.chardons.netscramble2

import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.chardons.netscramble2.databinding.ActivityScoresBinding
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.ui.EdgeToEdge
import kotlin.math.roundToInt

/**
 * Écran des records personnels : meilleur nombre de coups et temps le
 * plus rapide pour chaque niveau de difficulté, avec la date de chaque
 * record — parité avec l'écran « High scores » de l'original
 * (ScoreList), en Material 3. Le menu permet de tout effacer, avec
 * confirmation (l'original effaçait sans demander).
 *
 * Chantier Aide à la résolution (15 nov. 2026) : un record N'EST PLUS
 * grillé par l'indice — chaque valeur porte le nombre d'indices de sa
 * partie (« sans indice » si aucun) — et une rubrique « Indices »
 * cumule les indices consommés, total et détail par niveau.
 */
class ScoresActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScoresBinding
    private lateinit var store: ScoresStore

    /** Libellés des niveaux, dans l'ordre de l'énumération. */
    private val labels = listOf(
        R.string.skill_novice, R.string.skill_normal, R.string.skill_expert,
        R.string.skill_master, R.string.skill_insane, R.string.skill_endless,
        R.string.skill_abyss, R.string.skill_void
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M6 : bord à bord — toolbar sous la barre d'état, contenu au-dessus
        // de la barre de navigation (défile sous elle, clipToPadding off).
        EdgeToEdge.apply(window)
        binding = ActivityScoresBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.scoresToolbar, top = true)
        EdgeToEdge.padSystemBars(binding.scoresScroll, bottom = true)
        setSupportActionBar(binding.scoresToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = ScoresStore(this)
        showScores()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scores, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_scores_reset) {
            confirmReset()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scores_reset_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.clear()
                showScores()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Remplit les deux tables puis la rubrique « Indices ». */
    private fun showScores() {
        fillTable(binding.movesTable, store::bestMoves) { withHints(it.value, it.hints) }
        fillTable(binding.timeTable, store::bestTime) { withHints(it.value, it.hints) }
        fillHintsTable()
    }

    /**
     * Repeuple une table : l'en-tête (première ligne, en XML) est
     * conservée ; une ligne par niveau affiche le libellé suivi de la
     * taille du plateau, la valeur et la date — comme l'original. La
     * valeur porte son nombre d'indices (chantier Aide à la
     * résolution) : « X · sans indice » ou « X · n indices ».
     */
    private fun fillTable(
        table: TableLayout,
        best: (Skill) -> ScoresStore.Best?,
        formatValue: (ScoresStore.Best) -> String
    ) {
        while (table.childCount > HEADER_ROWS) {
            table.removeViewAt(table.childCount - 1)
        }
        for ((index, skill) in Skill.entries.withIndex()) {
            val record = best(skill)
            val label = getString(labels[index]) +
                (record?.takeIf { it.ntiles > 0 }?.let { " (${it.ntiles})" } ?: "")
            val row = TableRow(this)
            row.addView(cell(label))
            row.addView(cell(record?.let { formatValue(it) } ?: NONE))
            row.addView(cell(record?.let { formatDate(it.date) } ?: NONE))
            table.addView(row)
        }
    }

    /**
     * Rubrique « Indices » : total consommé tous niveaux confondus,
     * en évidence, puis une ligne par niveau avec son cumul — chaque
     * indice pris au menu est compté, qu'il débouche sur une victoire
     * ou pas.
     */
    private fun fillHintsTable() {
        binding.hintsTotal.text = getString(
            R.string.hints_total,
            Skill.entries.sumOf { store.hintTiles(it) }
        )
        while (binding.hintsTable.childCount > HEADER_ROWS) {
            binding.hintsTable.removeViewAt(binding.hintsTable.childCount - 1)
        }
        for ((index, skill) in Skill.entries.withIndex()) {
            val row = TableRow(this)
            row.addView(cell(getString(labels[index])))
            row.addView(cell(store.hintTiles(skill).toString()))
            binding.hintsTable.addView(row)
        }
    }

    /**
     * Valeur d'un record accompagnée de ses indices : « X · sans
     * indice » ou « X · n indices » — l'indice ne grille plus le
     * record, il l'accompagne.
     */
    private fun withHints(value: Int, hints: Int): String {
        val suffix = if (hints == 0) {
            getString(R.string.hints_none)
        } else {
            getString(R.string.hints_n, hints)
        }
        return "$value · $suffix"
    }

    private fun cell(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }

    /** Date du record : « -- » si absente, heure si aujourd'hui, date abrégée sinon. */
    private fun formatDate(date: Long): String {
        if (date == 0L) return NONE
        val flags = if (DateUtils.isToday(date)) {
            DateUtils.FORMAT_SHOW_TIME
        } else {
            DateUtils.FORMAT_SHOW_DATE
        }
        return DateUtils.formatDateTime(this, date, flags or DateUtils.FORMAT_ABBREV_ALL)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Ligne d'en-tête construite en XML, conservée au repeuplement. */
        private const val HEADER_ROWS = 1
        private const val NONE = "--"
    }
}
