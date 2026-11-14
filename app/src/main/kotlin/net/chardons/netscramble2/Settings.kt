package net.chardons.netscramble2

import android.content.Context
import androidx.core.content.edit
import net.chardons.netscramble2.audio.SoundMode

/**
 * Réglages de l'application (page unique « Réglages », chantier
 * « refonte réglages » du 13 nov. 2026) : mode sonore et animation.
 *
 * Ces deux réglages vivaient dans les sous-menus du jeu et étaient
 * persistés par MainActivity via getPreferences ; la page unique
 * (SettingsActivity) doit elle AUSSI les lire et les écrire — même
 * fichier, mêmes clés, sinon chaque activité lirait dans son coin.
 * Tout passe donc par ici : le fichier et les clés sont ceux de
 * [Persistence] (le nom historique « MainActivity », repris de
 * l'original), de sorte que les réglages sauvegardés avant la
 * refonte survivent à l'identique.
 */
object Settings {

    /** Mode sonore sauvegardé (même clé que l'original), FULL par défaut. */
    fun loadSoundMode(context: Context): SoundMode {
        val stored = prefs(context).getString(Persistence.SOUND_MODE_KEY, null)
        return SoundMode.entries.firstOrNull { it.name == stored } ?: SoundMode.FULL
    }

    fun saveSoundMode(context: Context, mode: SoundMode) {
        prefs(context).edit {
            putString(Persistence.SOUND_MODE_KEY, mode.name)
        }
    }

    /** Réglage d'animation (même clé que l'original), activé par défaut. */
    fun loadAnimEnabled(context: Context): Boolean =
        prefs(context).getBoolean(Persistence.ANIM_ENABLED_KEY, true)

    fun saveAnimEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(Persistence.ANIM_ENABLED_KEY, enabled)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Persistence.FILE, Context.MODE_PRIVATE)
}
