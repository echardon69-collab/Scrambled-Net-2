package net.chardons.netscramble2.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import net.chardons.netscramble2.R
import net.chardons.netscramble2.game.Sound

/**
 * Volume appliqué aux effets sonores. Repris de l'original
 * ('HermitAndroid.Player.makeSound') : DISCRET = 0,3, COMPLET = 1,0.
 */
enum class SoundMode {
    /** Aucun son. */
    NONE,

    /** Sons discrets (volume réduit). */
    QUIET,

    /** Sons complets (volume nominal), mode par défaut. */
    FULL
}

/**
 * Petit lecteur d'effets sonores basé sur [SoundPool] : latence faible,
 * jusqu'à quatre sons simultanés (rotation + connexions peuvent se
 * chevaucher). Les six échantillons sont chargés une seule fois à la
 * création ; [release] libère SoundPool (dans 'onDestroy').
 *
 * Les fichiers wav dans 'res/raw/' sont des sons courts synthétisés — aucun
 * échantillon de l'original n'est réutilisé.
 */
class SoundEffects(context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids: Map<Sound, Int> = mapOf(
        Sound.START to pool.load(context, R.raw.start, PRIORITY),
        Sound.TURN to pool.load(context, R.raw.turn, PRIORITY),
        Sound.CLICK to pool.load(context, R.raw.click, PRIORITY),
        Sound.POP to pool.load(context, R.raw.pop, PRIORITY),
        Sound.CONNECT to pool.load(context, R.raw.connect, PRIORITY),
        Sound.WIN to pool.load(context, R.raw.win, PRIORITY)
    )

    /**
     * Joue un son selon le mode choisi. Sans effet si le mode est
     * [SoundMode.NONE] ou si le son n'est pas encore chargé (SoundPool
     * renvoie alors 0, comme dans l'original au tout premier lancement).
     */
    fun play(sound: Sound, mode: SoundMode) {
        if (mode == SoundMode.NONE) return
        val id = ids[sound] ?: return
        val volume = if (mode == SoundMode.QUIET) QUIET_VOLUME else FULL_VOLUME
        pool.play(id, volume, volume, PRIORITY, NO_LOOP, NORMAL_RATE)
    }

    /** Libère les ressources de SoundPool. À appeler dans 'onDestroy'. */
    fun release() {
        pool.release()
    }

    companion object {
        /** Volume du mode « Discret », repris de l'original. */
        const val QUIET_VOLUME = 0.3f

        /** Volume nominal du mode « Complet ». */
        const val FULL_VOLUME = 1.0f

        private const val MAX_STREAMS = 4
        private const val PRIORITY = 1
        private const val NO_LOOP = 0
        private const val NORMAL_RATE = 1f
    }
}
