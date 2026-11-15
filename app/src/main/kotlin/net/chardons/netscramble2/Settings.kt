package net.chardons.netscramble2

import android.content.Context
import androidx.core.content.edit
import net.chardons.netscramble2.audio.SoundMode
import net.chardons.netscramble2.game.Skill

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
 *
 * Chantier Aide à la résolution (15 nov. 2026) : stratégie et plafond
 * de l'indice, difficulté, niveau de départ, zoom et rythme de la
 * démonstration — même fichier, mêmes clés, mêmes règles : chaque
 * lecteur passe par ici, jamais de chaîne magique ailleurs.
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

    // --- Chantier Aide à la résolution ---

    /**
     * Stratégie de l'indice : SIGNAL (informatif, cadre vert pulsé)
     * ou FIX (correctif, rotation + scellement). Par défaut SIGNAL :
     * l'indice montre, il ne joue pas à la place du joueur.
     */
    fun loadHintStrategy(context: Context): HintStrategy {
        val stored = prefs(context).getString(Persistence.HINT_STRATEGY_KEY, null)
        return HintStrategy.entries.firstOrNull { it.name == stored }
            ?: HintStrategy.SIGNAL
    }

    fun saveHintStrategy(context: Context, strategy: HintStrategy) {
        prefs(context).edit {
            putString(Persistence.HINT_STRATEGY_KEY, strategy.name)
        }
    }

    /**
     * Plafond d'indices, en pourcentage des tuiles mal orientées au
     * mélange initial (0 à 50, pas de 5). Par défaut 10 ; zéro
     * désactive l'indice. La valeur est bornée à la relecture :
     * une sauvegarde falsifiée ne contourne jamais le cadre validé.
     */
    fun loadHintPercent(context: Context): Int =
        prefs(context).getInt(Persistence.HINT_PERCENT_KEY, 10).coerceIn(0, 50)

    fun saveHintPercent(context: Context, percent: Int) {
        prefs(context).edit {
            putInt(Persistence.HINT_PERCENT_KEY, percent.coerceIn(0, 50))
        }
    }

    /** Difficulté de la démonstration, PROGRESSIVE par défaut. */
    fun loadDemoDifficulty(context: Context): DemoDifficulty {
        val stored = prefs(context).getString(Persistence.DEMO_DIFFICULTY_KEY, null)
        return DemoDifficulty.entries.firstOrNull { it.name == stored }
            ?: DemoDifficulty.PROGRESSIVE
    }

    fun saveDemoDifficulty(context: Context, difficulty: DemoDifficulty) {
        prefs(context).edit {
            putString(Persistence.DEMO_DIFFICULTY_KEY, difficulty.name)
        }
    }

    /** Niveau de départ de la démonstration, NOVICE par défaut. */
    fun loadDemoSkill(context: Context): Skill {
        val stored = prefs(context).getString(Persistence.DEMO_SKILL_KEY, null)
        return Skill.entries.firstOrNull { it.name == stored } ?: Skill.NOVICE
    }

    fun saveDemoSkill(context: Context, skill: Skill) {
        prefs(context).edit {
            putString(Persistence.DEMO_SKILL_KEY, skill.name)
        }
    }

    /** Zoom de la démonstration (multiplicateur du plancher), FIT par défaut. */
    fun loadDemoZoom(context: Context): DemoZoom {
        val stored = prefs(context).getString(Persistence.DEMO_ZOOM_KEY, null)
        return DemoZoom.entries.firstOrNull { it.name == stored } ?: DemoZoom.FIT
    }

    fun saveDemoZoom(context: Context, zoom: DemoZoom) {
        prefs(context).edit {
            putString(Persistence.DEMO_ZOOM_KEY, zoom.name)
        }
    }

    /** Rythme de la démonstration (délai par coup), NORMAL par défaut. */
    fun loadDemoSpeed(context: Context): DemoSpeed {
        val stored = prefs(context).getString(Persistence.DEMO_SPEED_KEY, null)
        return DemoSpeed.entries.firstOrNull { it.name == stored } ?: DemoSpeed.NORMAL
    }

    fun saveDemoSpeed(context: Context, speed: DemoSpeed) {
        prefs(context).edit {
            putString(Persistence.DEMO_SPEED_KEY, speed.name)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(Persistence.FILE, Context.MODE_PRIVATE)
}
