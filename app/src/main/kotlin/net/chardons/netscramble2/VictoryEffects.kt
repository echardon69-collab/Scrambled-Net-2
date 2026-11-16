package net.chardons.netscramble2

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Effet de réussite joué à une victoire SANS aide (chantier
 * « Effets spéciaux de réussite », spec validée par eric le
 * 15 nov. 2026) : tiré au sort, 4,5 s, skip au toucher, dialogue
 * de victoire en attente de la fin. V1 = VAGUE + FEU D'ARTIFICE ;
 * l'explosion du plateau est reportée en V2.
 */
enum class VictoryEffect { WAVE, FIREWORK }

/**
 * Modèle pur des effets de réussite : constantes, tirage,
 * profil de vague, vitesse du front. La vue lit ces nombres,
 * elle ne les redéfinit pas — tout se teste en JVM.
 */
object VictoryEffects {

    /** Durée de l'effet : 4,5 s (décision d'eric : « plus long »). */
    const val EFFECT_MS = 4_500L

    /**
     * Deux vagues par effet (retour UAT d'eric du 16 nov. 2026) :
     * la seconde suit la première, une fois la première passée.
     */
    const val WAVE_COUNT = 2

    /** Départ de la seconde vague : à mi-effet, la première est passée. */
    const val WAVE2_DELAY_MS = EFFECT_MS / WAVE_COUNT

    /** Budget d'étincelles vivantes, infranchissable. */
    const val FIREWORK_MAX_SPARKS = 600

    /**
     * Demi-largeur du profil de vague : 3 cases (retour UAT du
     * 16 nov. 2026 : vague plus large, plus de tuiles en mouvement).
     */
    const val WAVE_WIDTH_CELLS = 3f

    /**
     * Amplitude du soulèvement : 0,70 taille de case (retour UAT du
     * 16 nov. 2026 : les tuiles bougent nettement plus).
     */
    const val WAVE_LIFT_CELLS = 0.70f

    /** Grossissement de la tuile soulevée : 8 pour cent. */
    const val WAVE_SCALE_FRACTION = 0.08f

    /**
     * Tirage uniforme parmi les effets, BORNÉ : floorMod, jamais
     * d'effet hors bornes — et sans toucher au hasard de la vue.
     */
    fun pick(roll: Int = Random.nextInt(VictoryEffect.entries.size)) =
        VictoryEffect.entries[Math.floorMod(roll, VictoryEffect.entries.size)]

    /**
     * Profil de la vague au point [delta] du front : cloche
     * gaussienne de demi-largeur 3 cases, EXACTEMENT nulle à
     * trois demi-largeurs — les tuiles se lèvent à l'approche,
     * culminent au passage, se reposent proprement, et la fin de
     * l'effet ne saute jamais d'un cran.
     */
    fun waveProfile(delta: Float, cellPx: Float): Float {
        if (cellPx <= 0f) return 0f
        val d = delta / (WAVE_WIDTH_CELLS * cellPx)
        if (d <= -3f || d >= 3f) return 0f
        return exp(-d * d)
    }

    /**
     * Vitesse du front : il couvre la diagonale de la zone visible
     * en [durationMs] — marge de 1,25 : même la tuile de coin la
     * plus lointaine se soulève avant la fin de sa vague. Par
     * défaut, UNE VAGUE traverse l'écran en une moitié d'effet
     * ([WAVE_COUNT] vagues par effet, retour UAT d'eric).
     */
    fun waveSpeed(
        visibleW: Float,
        visibleH: Float,
        durationMs: Long = EFFECT_MS / WAVE_COUNT
    ): Float =
        (hypot(visibleW, visibleH) * 1.25f) / (durationMs / 1000f)
}

/**
 * Feu d'artifice : simulation PURE — graine unique par victoire,
 * budget d'étincelles infranchissable, zéro allocation une fois
 * posé. La vue ne fait que lire : fusées, étincelles, positions —
 * jamais de lambda par trame, jamais d'objet créé au vol.
 */
class VictoryFirework(seed: Long, private val width: Float, private val height: Float) {

    private val rng = Random(seed)

    /** Fusées : 7, montées depuis le BAS de la zone visible. */
    private val rX = FloatArray(ROCKETS)
    private val rBurstY = FloatArray(ROCKETS)
    private val rDelayMs = LongArray(ROCKETS)
    private val rRise = FloatArray(ROCKETS)
    private val rColor = IntArray(ROCKETS)
    private val rDone = BooleanArray(ROCKETS)

    /** Pool d'étincelles pré-alloué : le budget est un plancher INFRANCHISSABLE. */
    private val px = FloatArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val py = FloatArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val vx = FloatArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val vy = FloatArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val sColor = IntArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val bornMs = LongArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val lifeMs = LongArray(VictoryEffects.FIREWORK_MAX_SPARKS)
    private val alive = BooleanArray(VictoryEffects.FIREWORK_MAX_SPARKS)

    /** Prochain slot libre du pool, en anneau : réutilisation garantie. */
    private var head = 0

    /** Étincelles vivantes : jamais plus de FIREWORK_MAX_SPARKS. */
    private var aliveSparks = 0

    private var started = false
    private var firstMs = 0L
    private var lastMs = 0L

    /** Gravité, calée à la hauteur de la zone visible. */
    private val gravity = height * 1.15f

    init {
        // Étalement des tirs : les fusées partent échelonnées.
        val spreadMs = (VictoryEffects.EFFECT_MS - 1_200L).coerceAtLeast(500L)
        for (i in 0 until ROCKETS) {
            rX[i] = rng.nextFloat() * width
            // Éclat entre 12 et 42 pour cent de la hauteur.
            rBurstY[i] = height * (0.12f + 0.30f * rng.nextFloat())
            rDelayMs[i] = (i.toLong() * spreadMs) / ROCKETS
            rRise[i] = (height - rBurstY[i]) / (0.45f + 0.25f * rng.nextFloat())
            rColor[i] = PALETTE[i % PALETTE.size]
        }
    }

    /**
     * Avance la simulation à [nowMs] (elapsedRealtime) : premier
     * appel = ancrage, pas de saut au rattrapage. Le pas est BORNÉ
     * à 50 ms : un retour d'arrière-plan n'explose pas les gerbes.
     */
    fun step(nowMs: Long) {
        if (!started) {
            started = true
            firstMs = nowMs
            lastMs = nowMs
            return
        }
        val dtMs = (nowMs - lastMs).coerceIn(0L, 50L)
        lastMs = nowMs
        val tMs = nowMs - firstMs
        val t = tMs / 1000f
        // Fusées : montée depuis le bas de la zone, éclat à l'altitude.
        for (i in 0 until ROCKETS) {
            if (rDone[i] || tMs < rDelayMs[i]) continue
            if (height - rRise[i] * t <= rBurstY[i]) {
                rDone[i] = true
                burst(i, nowMs)
            }
        }
        // Étincelles : balistique + fondu ; pool en anneau.
        val dt = dtMs / 1000f
        for (i in 0 until VictoryEffects.FIREWORK_MAX_SPARKS) {
            if (!alive[i]) continue
            if (nowMs - bornMs[i] >= lifeMs[i] || py[i] > height * 1.05f) {
                alive[i] = false
                aliveSparks--
                continue
            }
            vy[i] += gravity * dt
            px[i] += vx[i] * dt
            py[i] += vy[i] * dt
        }
    }

    /**
     * Éclat de la fusée [rocket] : n étincelles (40 à 70) tirées
     * dans le pool en anneau — un slot vivant est réutilisé, le
     * budget reste un plancher infranchissable.
     */
    private fun burst(rocket: Int, nowMs: Long) {
        val n = 40 + rng.nextInt(31)
        for (k in 0 until n) {
            val slot = head
            head = (head + 1) % VictoryEffects.FIREWORK_MAX_SPARKS
            if (alive[slot]) aliveSparks--
            val angle = rng.nextFloat() * 6.2831855f
            val speed = (rBurstY[rocket].coerceAtLeast(1f)) *
                (0.12f + 0.35f * rng.nextFloat()) * 4.2f
            px[slot] = rX[rocket]
            py[slot] = rBurstY[rocket]
            vx[slot] = cos(angle) * speed
            vy[slot] = sin(angle) * speed
            sColor[slot] = PALETTE[(k + rocket) % PALETTE.size]
            bornMs[slot] = nowMs
            lifeMs[slot] = 1_200L + rng.nextLong(600L)
            alive[slot] = true
            aliveSparks++
        }
    }

    /** Étincelles vivantes à cet instant. */
    fun aliveCount(): Int = aliveSparks

    /** Nombre de fusées (les indices vont de 0 à rocketCount). */
    fun rocketCount(): Int = ROCKETS

    /** Faux si la fusée n'a pas décollé ou a déjà éclaté. */
    fun rocketVisible(i: Int, nowMs: Long): Boolean {
        if (!started) return false
        val t = nowMs - firstMs
        return !rDone[i] && t >= rDelayMs[i]
    }

    /** Position horizontale de la fusée. */
    fun rocketX(i: Int): Float = rX[i]

    /** Altitude de la fusée : montée à vitesse constante. */
    fun rocketY(i: Int, nowMs: Long): Float = height - rRise[i] * ((nowMs - firstMs) / 1000f)

    /** Bas de la traînée de la fusée. */
    fun rocketTrailY(i: Int, nowMs: Long): Float =
        rocketY(i, nowMs) + rocketY(i, nowMs).coerceAtLeast(1f) * TRAIL_S

    /** Taille du pool : la vue itère SANS lambda. */
    fun sparkSlots(): Int = VictoryEffects.FIREWORK_MAX_SPARKS

    /** Faux si le slot est libre. */
    fun isAlive(i: Int): Boolean = alive[i]

    fun sparkX(i: Int): Float = px[i]

    fun sparkY(i: Int): Float = py[i]

    fun sparkTrailX(i: Int): Float = px[i] - vx[i] * TRAIL_S

    fun sparkTrailY(i: Int): Float = py[i] - vy[i] * TRAIL_S

    fun sparkColor(i: Int): Int = sColor[i]

    /** Alpha de l'étincelle : fondu des 500 dernières ms de sa vie. */
    fun sparkAlpha(i: Int, nowMs: Long): Int {
        val remain = lifeMs[i] - (nowMs - bornMs[i])
        if (remain <= 0L) return 0
        if (remain > FADE_MS) return 255
        return ((remain * 255L) / FADE_MS).toInt()
    }

    companion object {
        private const val ROCKETS = 7
        private const val TRAIL_S = 0.03f
        private const val FADE_MS = 500L
        private val PALETTE = intArrayOf(
            0xFFFFD54F.toInt(), 0xFF81C784.toInt(), 0xFF64B5F6.toInt(),
            0xFFFF8A65.toInt(), 0xFFE6EE9C.toInt()
        )
    }
}
