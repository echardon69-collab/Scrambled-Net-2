package net.chardons.netscramble2

import android.content.Context
import androidx.core.content.edit
import net.chardons.netscramble2.game.Skill

/**
 * Records personnels, un par niveau de difficulté : meilleur nombre de
 * coups et temps le plus rapide (comptés seulement si le solveur n'a
 * pas servi), avec la taille du plateau et la date de chaque record.
 *
 * Parité avec l'original (NetScramble.registerScore / ScoreList) :
 * même fichier et clés de préférences — « size » + niveau,
 * « moves » + niveau (l'original : « clicks »), « time » + niveau, et « Date » suffixé — un
 * record n'est battu que strictement, la date est conservée.
 *
 * Chantier Aide à la résolution (15 nov. 2026) : un record n'est PLUS
 * grillé par l'indice — chaque record porte le nombre d'indices de la
 * partie gagnante (clés « movesHints » et « timeHints » suffixées du
 * niveau, zéro si la partie n'en a consommé aucun), et le total
 * d'indices consommés par niveau est cumulé (clé « hintTotal ») pour
 * la rubrique « Indices » de la page records.
 */
class ScoresStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Résultat d'un enregistrement à la victoire. */
    enum class Result { NONE, BEST_MOVES, BEST_TIME, BEST_BOTH }

    /**
     * Un record : valeur (coups ou secondes), taille du plateau,
     * date, et nombre d'indices consommés dans la partie gagnante
     * (zéro : « sans indice »).
     */
    data class Best(
        val ntiles: Int,
        val value: Int,
        val date: Long,
        val hints: Int = 0
    )

    /** Meilleur nombre de coups au niveau [skill], null si aucun record. */
    fun bestMoves(skill: Skill): Best? = best(MOVES, skill)

    /** Meilleur temps (secondes) au niveau [skill], null si aucun record. */
    fun bestTime(skill: Skill): Best? = best(TIME, skill)

    private fun best(kind: String, skill: Skill): Best? {
        val value = prefs.getInt(kind + skill.name, -1)
        if (value < 0) return null
        return Best(
            ntiles = prefs.getInt(SIZE + skill.name, -1),
            value = value,
            date = prefs.getLong(kind + skill.name + DATE, 0L),
            hints = prefs.getInt(kind + HINTS + skill.name, 0)
        )
    }

    /**
     * Total d'indices consommés au niveau [skill], records compris :
     * la statistique de la rubrique « Indices » de la page records.
     */
    fun hintTiles(skill: Skill): Int = prefs.getInt(HINT_TOTAL + skill.name, 0)

    /**
     * Compte des indices consommés à la demande (bouton du menu) :
     * le cumul par niveau, pour la rubrique « Indices » — chaque
     * indice pris compte, qu'il débouche sur une victoire ou pas.
     */
    fun addHintTiles(skill: Skill, count: Int) {
        if (count <= 0) return
        prefs.edit {
            putInt(HINT_TOTAL + skill.name, hintTiles(skill) + count)
        }
    }

    /**
     * Enregistre une victoire, comme dans l'original : ne bat que
     * strictement, les deux records (coups et temps) sont indépendants.
     * L'indice ne grille plus le record : le nombre d'indices de la
     * partie gagnante [hints] voyage avec le record battu.
     *
     * @return ce qui a été battu, [Result.NONE] si rien.
     */
    fun register(
        skill: Skill,
        ntiles: Int,
        moves: Int,
        seconds: Int,
        hints: Int = 0
    ): Result {
        val bestMoves = prefs.getInt(MOVES + skill.name, -1)
        val bestTime = prefs.getInt(TIME + skill.name, -1)
        var result = Result.NONE
        val now = System.currentTimeMillis()
        prefs.edit {
            if (moves > 0 && (bestMoves !in 0..moves)) {
                putInt(SIZE + skill.name, ntiles)
                putInt(MOVES + skill.name, moves)
                putLong(MOVES + skill.name + DATE, now)
                putInt(MOVES + HINTS + skill.name, hints)
                result = Result.BEST_MOVES
            }
            if (seconds > 0 && (bestTime !in 0..seconds)) {
                putInt(SIZE + skill.name, ntiles)
                putInt(TIME + skill.name, seconds)
                putLong(TIME + skill.name + DATE, now)
                putInt(TIME + HINTS + skill.name, hints)
                result = if (result == Result.BEST_MOVES) Result.BEST_BOTH else Result.BEST_TIME
            }
        }
        return result
    }

    /** Efface tous les records (menu de l'écran des scores). */
    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        /** Fichier de préférences, repris de l'original. */
        private const val FILE = "scores"
        private const val SIZE = "size"
        private const val MOVES = "moves"
        private const val TIME = "time"
        private const val DATE = "Date"

        /** Suffixe du nombre d'indices d'un record battu. */
        private const val HINTS = "Hints"

        /** Préfixe du cumul d'indices par niveau. */
        private const val HINT_TOTAL = "hintTotal"
    }
}
