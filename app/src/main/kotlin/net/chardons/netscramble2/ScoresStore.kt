package net.chardons.netscramble2

import android.content.Context
import androidx.core.content.edit
import net.chardons.netscramble2.game.Skill

/**
 * Records personnels, un par niveau de difficulté : meilleur nombre de
 * clics et temps le plus rapide (comptés seulement si le solveur n'a
 * pas servi), avec la taille du plateau et la date de chaque record.
 *
 * Parité avec l'original (NetScramble.registerScore / ScoreList) :
 * même fichier et clés de préférences — « size » + niveau,
 * « clicks » + niveau, « time » + niveau, et « Date » suffixé — un
 * record n'est battu que strictement, la date est conservée.
 */
class ScoresStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Résultat d'un enregistrement à la victoire. */
    enum class Result { NONE, BEST_CLICKS, BEST_TIME, BEST_BOTH }

    /** Un record : valeur (clics ou secondes), taille du plateau, date. */
    data class Best(val ntiles: Int, val value: Int, val date: Long)

    /** Meilleur nombre de clics au niveau [skill], null si aucun record. */
    fun bestClicks(skill: Skill): Best? = best(CLICKS, skill)

    /** Meilleur temps (secondes) au niveau [skill], null si aucun record. */
    fun bestTime(skill: Skill): Best? = best(TIME, skill)

    private fun best(kind: String, skill: Skill): Best? {
        val value = prefs.getInt(kind + skill.name, -1)
        if (value < 0) return null
        return Best(
            ntiles = prefs.getInt(SIZE + skill.name, -1),
            value = value,
            date = prefs.getLong(kind + skill.name + DATE, 0L)
        )
    }

    /**
     * Enregistre une victoire, comme dans l'original : ne bat que
     * strictement, les deux records (clics et temps) sont indépendants.
     *
     * @return ce qui a été battu, [Result.NONE] si rien.
     */
    fun register(skill: Skill, ntiles: Int, clicks: Int, seconds: Int): Result {
        val bestClicks = prefs.getInt(CLICKS + skill.name, -1)
        val bestTime = prefs.getInt(TIME + skill.name, -1)
        var result = Result.NONE
        val now = System.currentTimeMillis()
        prefs.edit {
            if (clicks > 0 && (bestClicks !in 0..clicks)) {
                putInt(SIZE + skill.name, ntiles)
                putInt(CLICKS + skill.name, clicks)
                putLong(CLICKS + skill.name + DATE, now)
                result = Result.BEST_CLICKS
            }
            if (seconds > 0 && (bestTime !in 0..seconds)) {
                putInt(SIZE + skill.name, ntiles)
                putInt(TIME + skill.name, seconds)
                putLong(TIME + skill.name + DATE, now)
                result = if (result == Result.BEST_CLICKS) Result.BEST_BOTH else Result.BEST_TIME
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
        private const val CLICKS = "clicks"
        private const val TIME = "time"
        private const val DATE = "Date"
    }
}
