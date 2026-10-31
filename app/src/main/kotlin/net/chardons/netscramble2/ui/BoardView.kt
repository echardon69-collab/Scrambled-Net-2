package net.chardons.netscramble2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import net.chardons.netscramble2.R
import net.chardons.netscramble2.game.BlipField
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.Dir
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withSave
import kotlin.random.Random

/**
 * Le plateau de jeu : vue custom rendue à partir de VectorDrawables.
 *
 * Différences majeures avec l'ancien moteur (SurfaceRunner) :
 *  - rendu événementiel : pas de thread de rendu dédié — on redessine
 *    par invalidate() à la demande ; depuis M2 (blips, rotations
 *    animées), une boucle de trame vsync (postInvalidateOnAnimation)
 *    tourne tant que la vue est affichée, et s'arrête hors écran →
 *    énergie maîtrisée ;
 *  - tuiles = VectorDrawables convertis des SVG d'origine (dégradés natifs
 *    API 24+, un seul fichier pour toutes les densités, netteté parfaite à
 *    n'importe quel zoom) — fidèles au look historique du jeu ;
 *  - état connecté/déconnecté : opacité du câble (l'original avait deux
 *    bitmaps par tuile : normal/gris), et images dédiées pour l'équipement
 *    (computer1 écran rouge / computer2 écran vert, server DEL rouges /
 *    server1 DEL vertes), exactement comme Cell.java de l'original ;
 *  - viewport « caméra » : le plateau est un monde virtuel, potentiellement
 *    plus grand que l'écran ; on le déplace (pan) et on le zoome au pinçage,
 *    avec une taille de case minimale garantie.
 *  - appui long : verrouille une cellule (cadre), qui ne tourne plus ;
 *  - cellules cachées (Insane) : câbles masqués jusqu'à ce que le
 *    solveur les règle ou que la partie soit gagnée (révélation complète,
 *    comme l'original qui révèle tout à la fin) ;
 *  - M2, animations de parité :
 *    . les rotations s'animent — le câble seul tourne, l'équipement
 *      reste fixe, exactement comme Cell.doDraw de l'original ;
 *    . des « blips » de données parcourent les câbles alignés
 *      (portage de Cell.advanceBlips / transferBlips : le serveur émet
 *      une génération sur six, le flux tourne aux embranchements, meurt
 *      aux terminaux et disparaît quand on tourne une cellule ; les
 *      écrans des terminaux affichent des lignes de « données », comme
 *      Cell.drawTermData). La boucle de trame (postInvalidateOnAnimation)
 *      ne tourne que tant que la vue est affichée : hors écran, rien
 *      n'est redessiné.
 *
 * TODO (roadmap) : accessibilité TalkBack.
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Écouteur de tap sur une cellule (index du plateau). */
    var onCellTapped: ((Int) -> Unit)? = null

    /** Écouteur d'appui long sur une cellule (verrouillage). */
    var onCellLongPressed: ((Int) -> Unit)? = null

    private var board: Board? = null
    private var connected: BooleanArray? = null
    private var solved = false

    // --- M2 : animations de rotation. Le modèle tourne d'un coup ; la vue
    //     interpole. Pour chaque cellule on garde un angle visé CUMULÉ
    //     (les tours s'enchaînent sans jamais « sauter en arrière » quand
    //     rotations[i] repasse par 0), l'angle courant, et les bornes de
    //     l'animation en cours (interpolation linéaire, comme
    //     Cell.doUpdate de l'original). ---
    private var shownBoard: Board? = null
    private var visualTarget = FloatArray(0)
    private var visualCurrent = FloatArray(0)
    private var animStartMs = LongArray(0)
    private var animFromAngle = FloatArray(0)
    private var animDurationMs = LongArray(0)
    private var lastRotations = IntArray(0)

    // --- M2 : blips de données (modèle pur, voir BlipField) ---
    private var blips: BlipField? = null
    private var blipsLastAdvanceMs = 0L

    /**
     * Réglage « Animation » (M5, parité avec setAnimEnable de
     * l'original) : coupe les blips de données et le bruit des écrans
     * de terminaux. Les rotations restent animées — comme dans
     * l'original, ce réglage n'agit que sur le flux permanent.
     * La valeur est persistée par l'activité (clé « animEnable »).
     */
    var blipsEnabled: Boolean = true
        set(value) {
            field = value
            // Rallumage : reprendre le rendu immédiatement (sinon la
            // boucle de trame, arrêtée, ne redémarre pas d'elle-même).
            if (value) postInvalidateOnAnimation()
        }

    // Bruit visuel des « données » sur les écrans de terminaux.
    private val rng = Random.Default

    // --- Viewport (taille de case courante, en pixels) ---
    private val screenDensity  = resources.displayMetrics.density
    private val minCellPx = MIN_CELL_DP * screenDensity
    private val maxCellPx = MAX_CELL_DP * screenDensity
    private var cellPx = 0f
    private var panX = 0f
    private var panY = 0f
    private var viewportInitialized = false

    // --- Peintures (allouées une seule fois, jamais dans onDraw) ---
    private val boardPaint = Paint()
    private val lockPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = LOCK_BORDER_DP * screenDensity
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val termPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Drawables : 15 tuiles câbles (le masque 0 n'existe pas :
    //     toute cellule de l'arbre couvrant a au moins une connexion) ---
    private val tiles = arrayOfNulls<Drawable>(16)
    private val computerOff: Drawable = load(R.drawable.computer1)
    private val computerOn: Drawable = load(R.drawable.computer2)
    private val serverPartial: Drawable = load(R.drawable.server)
    private val serverFull: Drawable = load(R.drawable.server1)

    init {
        boardPaint.style = Paint.Style.FILL
        // Mutate() : chaque instance peut recevoir sa propre alpha sans
        // affecter les autres usages du même drawable (thème/caches).
        tiles[1] = load(R.drawable.cable1000)      // U
        tiles[2] = load(R.drawable.cable0100)      // R
        tiles[3] = load(R.drawable.cable1100)      // U+R
        tiles[4] = load(R.drawable.cable0010)      // D
        tiles[5] = load(R.drawable.cable1010)      // U+D
        tiles[6] = load(R.drawable.cable0110)      // R+D
        tiles[7] = load(R.drawable.cable1110)      // U+R+D
        tiles[8] = load(R.drawable.cable0001)      // L
        tiles[9] = load(R.drawable.cable1001)      // U+L
        tiles[10] = load(R.drawable.cable0101)     // R+L
        tiles[11] = load(R.drawable.cable1101)     // U+R+L
        tiles[12] = load(R.drawable.cable0011)     // D+L
        tiles[13] = load(R.drawable.cable1011)     // U+D+L
        tiles[14] = load(R.drawable.cable0111)     // R+D+L
        tiles[15] = load(R.drawable.cable1111)     // croix complète
    }

    private fun load(resId: Int): Drawable =
        requireNotNull(ContextCompat.getDrawable(context, resId)) {
            "Drawable introuvable : $resId"
        }.mutate()

    // --- Gestes ---
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val index = hitCell(e.x, e.y) ?: return false
            onCellTapped?.invoke(index)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val index = hitCell(e.x, e.y) ?: return
            onCellLongPressed?.invoke(index)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx
            panY -= dy
            clampPan()
            invalidate()
            return true
        }
    })
    private val zoomDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            cellPx = (cellPx * detector.scaleFactor).coerceIn(minCellPx, maxCellPx)
            clampPan()
            invalidate()
            return true
        }
    })

    /** Rafraîchit l'état du plateau affiché et redessine. */
    fun update(board: Board, connected: BooleanArray, solved: Boolean) {
        this.board = board
        this.connected = connected
        this.solved = solved
        val now = SystemClock.elapsedRealtime()
        if (board !== shownBoard || visualTarget.size != board.size) {
            // Nouveau plateau : tout est posé d'emblée, aucun angle résiduel.
            shownBoard = board
            visualTarget = FloatArray(board.size)
            visualCurrent = FloatArray(board.size)
            animStartMs = LongArray(board.size)
            animFromAngle = FloatArray(board.size)
            animDurationMs = LongArray(board.size)
            lastRotations = board.rotations.copyOf()
            blips = BlipField(board)
            blipsLastAdvanceMs = now
        } else {
            // Détection des rotations survenues depuis le dernier rendu :
            // chaque delta devient une animation de delta × 90°, et fait
            // perdre ses blips à la cellule (comme Cell.rotate de
            // l'original).
            val field = blips
            for (i in visualTarget.indices) {
                val delta = ((board.rotations[i] - lastRotations[i]) % 4 + 4) % 4
                if (delta != 0) {
                    visualTarget[i] += delta * 90f
                    animFromAngle[i] = visualCurrent[i]
                    animStartMs[i] = now
                    animDurationMs[i] = ROTATE_MS_PER_QUARTER * delta
                    field?.clear(i)
                }
            }
            lastRotations = board.rotations.copyOf()
        }
        if (!viewportInitialized) {
            fitBoard()
            viewportInitialized = true
        } else {
            clampPan()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewportInitialized = false
        board?.let { fitBoard() }
        viewportInitialized = true
    }

    /** Ajuste la taille de case pour voir tout le plateau, sans descendre sous le minimum. */
    private fun fitBoard() {
        val b = board ?: return
        cellPx = (minOf(width / b.width.toFloat(), height / b.height.toFloat()))
            .coerceIn(minCellPx, maxCellPx)
        clampPan()
    }

    /** Maintient le plateau dans des limites raisonnables (collé aux bords ou centré). */
    private fun clampPan() {
        val b = board ?: return
        val boardW = b.width * cellPx
        val boardH = b.height * cellPx
        panX = if (boardW <= width) (width - boardW) / 2f
               else panX.coerceIn(width - boardW, 0f)
        panY = if (boardH <= height) (height - boardH) / 2f
               else panY.coerceIn(height - boardH, 0f)
    }

    /** Traduit des coordonnées écran en index de cellule, ou null. */
    private fun hitCell(x: Float, y: Float): Int? {
        val b = board ?: return null
        val cx = ((x - panX) / cellPx).toInt()
        val cy = ((y - panY) / cellPx).toInt()
        if (cx < 0 || cx >= b.width || cy < 0 || cy >= b.height) return null
        return cy * b.width + cx
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        zoomDetector.onTouchEvent(event)
        if (!zoomDetector.isInProgress) tapDetector.onTouchEvent(event)
        return true
    }

    /**
     * Le serveur est-il entièrement câblé ? (Toutes ses connexions courantes
     * aboutissent à un voisin connecté qui pointe vers lui.) Comme dans
     * l'original (Cell.java), on affiche alors les DEL vertes (server1).
     */
    private fun serverIsFullyWired(b: Board, connected: BooleanArray): Boolean {
        val mask = b.currentDirs(b.serverIndex)
        if (mask == Dir.NO_DIRS) return false
        for (dir in Dir.entries) {
            if (!Dir.contains(mask, dir)) continue
            val neighbor = b.neighborIndex(b.serverIndex, dir)
            if (neighbor < 0 || !connected[neighbor]) return false
            if (!Dir.contains(b.currentDirs(neighbor), dir.opposite)) return false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = board ?: return
        val connected = connected ?: return

        // M2 : progression des rotations en cours (interpolation linéaire,
        // comme Cell.doUpdate de l'original).
        val now = SystemClock.elapsedRealtime()
        for (i in visualCurrent.indices) {
            if (animDurationMs[i] > 0L) {
                val t = ((now - animStartMs[i]).toFloat() / animDurationMs[i])
                    .coerceIn(0f, 1f)
                visualCurrent[i] = animFromAngle[i] +
                    (visualTarget[i] - animFromAngle[i]) * t
                if (t >= 1f) {
                    animDurationMs[i] = 0L
                    // Rabot des tours complets : les angles cumulés
                    // restent petits (stabilité du float).
                    if (visualTarget[i] >= 360f || visualTarget[i] <= -360f) {
                        val whole = 360f * (visualTarget[i] / 360f).toInt()
                        visualTarget[i] -= whole
                        visualCurrent[i] -= whole
                    }
                }
            }
        }

        canvas.withTranslation(panX, panY) {
            // Fond du plateau (une seule peinture, un seul rect).
            boardPaint.color = ContextCompat.getColor(context, R.color.board_background)
            drawRect(0f, 0f, b.width * cellPx, b.height * cellPx, boardPaint)

            val serverWired = serverIsFullyWired(b, connected)
            val disconnectedAlpha = (DISCONNECTED_ALPHA * 255).toInt()
            lockPaint.color = ContextCompat.getColor(context, R.color.cell_locked_border)
            val lockInset = lockPaint.strokeWidth / 2f

            for (y in 0 until b.height) {
                for (x in 0 until b.width) {
                    val index = y * b.width + x
                    val left = x * cellPx
                    val top = y * cellPx
                    val isConnected = connected[index]

                    // Cellule cachée (Insane) : câbles masqués tant que la
                    // partie n'est pas gagnée et que le solveur ne les a pas
                    // révélés — l'équipement, lui, reste toujours visible.
                    val hideCables =
                        b.isHidden(index) && !solved && !b.revealed[index]

                    // Câble : drawable du masque courant, atténué si déconnecté.
                    // En animation, on dessine le masque final tourné de
                    // l'écart résiduel : la tuile tourne sous le doigt. Seul
                    // le câble tourne — l'équipement reste fixe (Cell.doDraw).
                    val dirs = b.currentDirs(index)
                    if (dirs != Dir.NO_DIRS && !hideCables) {
                        val tile = tiles[dirs]
                        if (tile != null) {
                            tile.alpha = if (isConnected) 255 else disconnectedAlpha
                            tile.setBounds(
                                left.toInt(), top.toInt(),
                                (left + cellPx).toInt(), (top + cellPx).toInt()
                            )
                            val offset = visualCurrent[index] - visualTarget[index]
                            if (offset != 0f) {
                                withSave {
                                    rotate(offset, left + cellPx / 2f, top + cellPx / 2f)
                                    tile.draw(this)
                                }
                            } else {
                                tile.draw(this)
                            }
                        }
                    }

                    // Équipement : serveur / terminal, par-dessus le câble.
                    val equip: Drawable? = when {
                        index == b.serverIndex ->
                            if (serverWired) serverFull else serverPartial

                        b.isTerminal(index) ->
                            if (isConnected) computerOn else computerOff

                        else -> null
                    }
                    if (equip != null) {
                        equip.alpha = 255
                        equip.setBounds(
                            left.toInt(), top.toInt(),
                            (left + cellPx).toInt(), (top + cellPx).toInt()
                        )
                        equip.draw(this)
                    }

                    // Cellule verrouillée : cadre de repère (l'original avait
                    // un fond dédié ; le cadre suffit et reste discret).
                    if (b.locked[index]) {
                        drawRect(
                            left + lockInset, top + lockInset,
                            left + cellPx - lockInset, top + cellPx - lockInset,
                            lockPaint
                        )
                    }
                }
            }

            // --- M2 : passe des blips de données, séparée des cellules et
            //     sans rognage : un blip qui traverse un bord déborde sur la
            //     cellule voisine (comme l'original, BoardView.doDraw).
            //     M5 : sautée entièrement quand l'animation est coupée
            //     (le modèle BlipField gèle, puis reprend tel quel).
            val field = blips
            if (field != null && blipsEnabled) {
                if (now - blipsLastAdvanceMs >= BLIPS_MS) {
                    field.advance()
                    // Rattrapage borné : si on a raté plus d'une période
                    // (vue revenue à l'écran), on saute au présent au
                    // lieu d'enchaîner les générations manquées.
                    blipsLastAdvanceMs =
                        if (now - blipsLastAdvanceMs >= 2L * BLIPS_MS) now
                        else blipsLastAdvanceMs + BLIPS_MS
                }
                val frac =
                    ((now - blipsLastAdvanceMs).toFloat() / BLIPS_MS).coerceIn(0f, 1f)
                val activeColor = ContextCompat.getColor(context, R.color.blip_active)
                val idleColor = ContextCompat.getColor(context, R.color.blip_idle)
                termPaint.color = ContextCompat.getColor(context, R.color.term_data)
                termPaint.strokeWidth = screenDensity
                val half = cellPx / 2f
                val radius = (cellPx * BLIP_RADIUS_FRACTION).coerceAtLeast(2f)

                for (index in 0 until b.size) {
                    val inc = field.incomingAt(index)
                    val out = field.outgoingAt(index)
                    if (inc == 0 && out == 0) continue
                    // Cellule cachée : pas de câble, pas de blip
                    // (l'équipement reste visible mais ne trahit rien).
                    if (b.isHidden(index) && !solved && !b.revealed[index]) continue

                    val x = (index % b.width) * cellPx
                    val y = (index / b.width) * cellPx
                    val cx = x + half
                    val cy = y + half

                    if (index != b.serverIndex && b.isTerminal(index)) {
                        // Terminaux : le blip approche de l'écran, puis
                        // celui-ci « affiche des données » — lignes
                        // vertes aléatoires, comme Cell.drawTermData.
                        if (inc != 0 && frac < TERM_DATA_SWITCH_FRAC) {
                            blipPaint.color =
                                if (connected[index]) activeColor else idleColor
                            for (dir in Dir.entries) {
                                if (Dir.contains(inc, dir)) {
                                    drawBlip(cx, cy, dir, 1f - frac, half, radius)
                                }
                            }
                        } else if (connected[index] && inc != 0) {
                            var ly = y + cellPx / 3f
                            val bottom = y + cellPx * 0.55f
                            while (ly < bottom) {
                                val l = x + cellPx / 3f
                                val r = x + cellPx / 3f * (1f + rng.nextFloat())
                                drawLine(l, ly, r, ly, termPaint)
                                ly += TERM_LINE_STEP_DP * screenDensity
                            }
                        }
                        continue
                    }

                    // Câbles normaux et serveur : disques. Le serveur
                    // (source des données) émet un blip plus large,
                    // l'équivalent des images « glow » de l'original.
                    blipPaint.color = if (connected[index]) activeColor else idleColor
                    val scale = if (index == b.serverIndex) SERVER_HALO_SCALE else 1f
                    for (dir in Dir.entries) {
                        if (Dir.contains(inc, dir)) {
                            drawBlip(cx, cy, dir, 1f - frac, half, radius * scale)
                        }
                        if (Dir.contains(out, dir)) {
                            drawBlip(cx, cy, dir, frac, half, radius * scale)
                        }
                    }
                }
            }
        }

        // M2 : tant que la vue est affichée, demander la trame suivante
        // (rotations en cours, blips). C'est l'équivalent de la boucle
        // permanente de l'original (setDelay(30)), mais arrêté
        // automatiquement dès que la vue quitte l'écran — ou dès que
        // plus rien ne s'anime (M5 : animation coupée, rotations finies).
        if (blipsEnabled || animDurationMs.any { it > 0L }) {
            postInvalidateOnAnimation()
        }
    }

    /** Dessine un blip sur la jambe [dir] de la cellule centrée en (cx, cy). */
    private fun Canvas.drawBlip(
        cx: Float,
        cy: Float,
        dir: Dir,
        progress: Float,
        half: Float,
        radius: Float
    ) {
        val d = progress * half
        drawCircle(cx + dir.dx * d, cy + dir.dy * d, radius, blipPaint)
    }

    companion object {
        private const val MIN_CELL_DP = 26f   // jamais de case plus petite que ceci
        private const val MAX_CELL_DP = 96f
        private const val LOCK_BORDER_DP = 2f

        /** Opacité des câbles non reliés au serveur (l'original utilisait
         *  une version grise de chaque bitmap ; ici une seule tuile). */
        private const val DISCONNECTED_ALPHA = 0.55f

        // --- M2 : parité des animations de l'original ---

        /** Durée d'un quart de tour animé (l'original : ROTATE_DFLT_TIME). */
        private const val ROTATE_MS_PER_QUARTER = 250L

        /** Période d'avance des blips (l'original : BLIPS_TIME). */
        private const val BLIPS_MS = 300L

        /** Taille d'un blip, en fraction de case. */
        private const val BLIP_RADIUS_FRACTION = 0.08f

        /** Les blips du serveur sont plus larges (« glow » de l'original). */
        private const val SERVER_HALO_SCALE = 2.2f

        /** Fraction de période après laquelle un blip arrivé à un terminal
         *  « s'affiche » sur son écran au lieu d'être dessiné (0,3 dans
         *  Cell.doDrawBlips). */
        private const val TERM_DATA_SWITCH_FRAC = 0.3f

        /** Écart vertical des lignes de « données » des terminaux. */
        private const val TERM_LINE_STEP_DP = 2f
    }
}
