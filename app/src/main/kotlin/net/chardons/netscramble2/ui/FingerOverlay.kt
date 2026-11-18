package net.chardons.netscramble2.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withTranslation
import net.chardons.netscramble2.DemoKind
import net.chardons.netscramble2.R
import kotlin.math.min

/**
 * SURIMPRESSION DU DOIGT DE DÉMO (refonte « doigt animé »,
 * validée par eric le 17 nov. 2026) : une main VectorDrawable
 * animée (AnimatedVectorDrawable, UNE instance fraîche par démo,
 * format vectoriel d'animation NATIF Android, aucune dépendance)
 * dessinée au-dessus du plateau, dont le bout de l'index rejoint
 * la cible de l'étape.
 *
 * Répartition des rôles : l'OVERLAY porte le voyage d'approche,
 * l'alpha et les instants de contact (UN ValueAnimator maître
 * linéaire, les phases calculées au fil du temps) ; l'AVD porte
 * le geste du doigt lui-même (l'appui qui s'enfonce puis se
 * relève, l'anneau de progression de l'appui prolongé, l'écart
 * des deux mains du pincement, la flèche de trajectoire du
 * glissement). Les durées des DEUX côtés sont épinglées ENSEMBLE
 * par validate_apprentissage : le contact tombe à la fin de
 * l'appui de l'AVD, jamais au milieu du vol.
 *
 * Le doigt n'intercepte RIEN : aucun clic, aucun toucher, la
 * leçon reste jouable pendant la démo — le joueur pressé qui
 * accomplit le geste fait simplement avancer l'étape, la démo est
 * annulée par la génération suivante.
 */
class FingerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** AVD courant, une instance FRAÎCHE par démo (état remis à zéro). */
    private var avd: AnimatedVectorDrawable? = null

    /** Point d'ancrage (le bout de l'index) en fractions du drawable. */
    private var anchorU = 0.5f
    private var anchorV = ANCHOR_V

    /** Position courante de l'ancrage, en pixels de la vue. */
    private var fx = 0f
    private var fy = 0f

    /** Alpha courant du doigt (0 : invisible, la vue ne dessine rien). */
    private var falpha = 0f

    /** Rotation du doigt : le glissement horizontal pointe à droite. */
    private var rotDeg = 0f

    /** ValueAnimator maître : temps linéaire, phases par genre de geste. */
    private var master: ValueAnimator? = null

    /** Interpolateur du voyage d'approche (freinage à l'arrivée). */
    private val decel = DecelerateInterpolator()

    /** Densité d'écran : les distances de la démo vivent en dp. */
    private val dp = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val d = avd ?: return
        if (falpha <= 0f) return
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return
        val ax = w * anchorU
        val ay = h * anchorV
        canvas.withTranslation(fx - ax, fy - ay) {
            canvas.rotate(rotDeg, ax, ay)
            d.alpha = (falpha * 255f).toInt().coerceIn(0, 255)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
        }
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    /** Annule la démo en cours : le maître s'arrête, le doigt disparaît. */
    fun cancel() {
        master?.cancel()
        master = null
        avd = null
        falpha = 0f
        invalidate()
    }

    /**
     * Joue la démo de [kind] vers la cible ([x], [y]) : [onAct] au
     * contact — l'instant où l'action réelle s'exécute (appui de
     * tuile, bouton d'indice) —, [onPanFrame] à chaque image du
     * glissement (deltas d'écran, le contenu suit le doigt),
     * [onZoomFrame] à chaque image du pincement (facteur de
     * croissance, ancré au centre de la vue). Pincement et
     * glissement n'ont PAS de contact : leur effet est la caméra.
     */
    fun demo(
        kind: DemoKind,
        x: Float,
        y: Float,
        onAct: () -> Unit,
        onPanFrame: (Float, Float) -> Unit,
        onZoomFrame: (Float) -> Unit
    ) {
        cancel()
        val resId = when (kind) {
            DemoKind.TAP_TILE, DemoKind.TAP_BUTTON -> R.drawable.lesson_doigt_appui
            DemoKind.HOLD_TILE -> R.drawable.lesson_doigt_prolonge
            DemoKind.PINCH -> R.drawable.lesson_doigt_pincement
            DemoKind.DRAG_X, DemoKind.DRAG_Y -> R.drawable.lesson_doigt_glissement
            DemoKind.NONE -> return
        }
        val d = ContextCompat.getDrawable(context, resId) as? AnimatedVectorDrawable ?: return
        avd = d
        rotDeg = if (kind == DemoKind.DRAG_X) ROT_DRAG_X else 0f
        anchorU = 0.5f
        anchorV = ANCHOR_V

        val travelMs = TRAVEL_MS
        // Durée du geste : miroir des objectAnimator des AVD.
        val gestureMs = when (kind) {
            DemoKind.TAP_TILE, DemoKind.TAP_BUTTON -> 2f * PRESS_MS
            DemoKind.HOLD_TILE -> RING_MS
            DemoKind.PINCH -> 2f * PINCH_MS
            DemoKind.DRAG_X, DemoKind.DRAG_Y ->
                2f * SWEEP_MS + SWEEP_HOLD_MS
            DemoKind.NONE -> return
        }
        // L'instant du contact : fin de l'appui de l'AVD (tap),
        // fin de l'anneau (appui prolongé). Pincement et
        // glissement n'en ont pas : la caméra est l'effet.
        val actDelayMs = when (kind) {
            DemoKind.TAP_TILE, DemoKind.TAP_BUTTON -> PRESS_MS
            DemoKind.HOLD_TILE -> RING_MS
            else -> 0f
        }
        val totalMs = travelMs + gestureMs + FADE_MS

        // La main entre par le bas droit ; le glissement part du
        // début de sa course.
        val span = SWEEP_DP * dp
        val restX = x + REST_DX_DP * dp
        val restY = y + REST_DY_DP * dp
        val startX = if (kind == DemoKind.DRAG_X) x - span / 2f else x
        val startY = if (kind == DemoKind.DRAG_Y) y - span / 2f else y

        var avdStarted = false
        var acted = false
        var prevFx = startX
        var prevFy = startY
        var prevSep = PINCH_CLOSED_DP

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = totalMs.toLong()
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { anim ->
            val t = anim.animatedFraction * totalMs
            if (t < travelMs) {
                // Voyage d'approche : freinage à l'arrivée, alpha
                // fondu sur les premiers instants.
                val p = decel.getInterpolation(t / travelMs)
                fx = restX + (startX - restX) * p
                fy = restY + (startY - restY) * p
                falpha = min(1f, t / FADE_MS)
            } else {
                fx = x
                fy = y
                falpha = 1f
                if (!avdStarted) {
                    avdStarted = true
                    d.start()
                }
                // Glissement : le doigt balaie, le plateau suit.
                if (kind == DemoKind.DRAG_X || kind == DemoKind.DRAG_Y) {
                    val g = (t - travelMs)
                        .coerceIn(0f, 2f * SWEEP_MS + SWEEP_HOLD_MS)
                    val pos = when {
                        g < SWEEP_MS -> -0.5f + g / SWEEP_MS
                        g < SWEEP_MS + SWEEP_HOLD_MS -> 0.5f
                        else -> 0.5f - (g - SWEEP_MS - SWEEP_HOLD_MS) / SWEEP_MS
                    }
                    if (kind == DemoKind.DRAG_X) fx = x + pos * span
                    if (kind == DemoKind.DRAG_Y) fy = y + pos * span
                    onPanFrame(fx - prevFx, fy - prevFy)
                }
                // Pincement : l'écart des deux doigts anime le zoom.
                if (kind == DemoKind.PINCH) {
                    val g = (t - travelMs).coerceIn(0f, 2f * PINCH_MS)
                    val sep = if (g < PINCH_MS) {
                        PINCH_CLOSED_DP +
                            (PINCH_OPEN_DP - PINCH_CLOSED_DP) * (g / PINCH_MS)
                    } else {
                        PINCH_OPEN_DP -
                            (PINCH_OPEN_DP - PINCH_CLOSED_DP) *
                            ((g - PINCH_MS) / PINCH_MS)
                    }
                    if (prevSep > 0f && sep != prevSep) onZoomFrame(sep / prevSep)
                    prevSep = sep
                }
                // Contact : l'action réelle de la démo, UNE fois.
                if (!acted && actDelayMs > 0f && t >= travelMs + actDelayMs) {
                    acted = true
                    onAct()
                }
            }
            prevFx = fx
            prevFy = fy
            // Fondu de sortie sur les derniers instants.
            if (t > totalMs - FADE_MS) {
                falpha = min(falpha, (totalMs - t) / FADE_MS)
            }
            if (t >= totalMs) falpha = 0f
            invalidate()
        }
        animator.start()
        master = animator
    }

    companion object {
        /** Ancrage du bout de l'index, en fraction du drawable (miroir des tracés : pointe à 11/48). */
        private const val ANCHOR_V = 11f / 48f

        /** Rotation du glissement horizontal : le doigt pointe à droite. */
        private const val ROT_DRAG_X = 90f

        /** Voyage d'approche vers la cible (ms). */
        private const val TRAVEL_MS = 450f

        /** Appui de l'AVD (descente, la moitié du cycle tap) — miroir de lesson_appui_corps. */
        private const val PRESS_MS = 350f

        /** Anneau de l'appui prolongé — miroir de lesson_prolonge_anneau. */
        private const val RING_MS = 1300f

        /** Une direction du pincement — miroir de lesson_pince_gauche/droite. */
        private const val PINCH_MS = 550f

        /** Une direction du balayage du glissement — miroir de lesson_glisse_trajet. */
        private const val SWEEP_MS = 550f

        /** Tenue au bout du balayage (ms). */
        private const val SWEEP_HOLD_MS = 150f

        /** Fondu d'entrée et de sortie (ms). */
        private const val FADE_MS = 200f

        /** La main entre par le bas droit (dp). */
        private const val REST_DX_DP = 90f
        private const val REST_DY_DP = 130f

        /** Amplitude du balayage du glissement (dp). */
        private const val SWEEP_DP = 90f

        /** Écart des deux doigts du pincement, fermé puis ouvert (dp) — miroir de doigts_pincement. */
        private const val PINCH_CLOSED_DP = 36f
        private const val PINCH_OPEN_DP = 48f
    }
}
