package net.chardons.netscramble2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import net.chardons.netscramble2.R
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.Dir

/**
 * Le plateau de jeu : vue custom rendue à partir de VectorDrawables.
 *
 * Différences majeures avec l'ancien moteur (SurfaceRunner) :
 *  - PAS de boucle de rendu permanente : on ne redessine que lorsqu'un
 *    événement le justifie (invalidate() à la demande) → énergie ;
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
 *
 * TODO (roadmap) : animation des rotations (ValueAnimator + Choreographer),
 * verrouillage par appui long, « blips » de données animés, cellules
 * « aveugles » (niveau Insane), accessibilité TalkBack.
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Écouteur de tap sur une cellule (index du plateau). */
    var onCellTapped: ((Int) -> Unit)? = null

    private var board: Board? = null
    private var connected: BooleanArray? = null

    // --- Viewport (taille de case courante, en pixels) ---
    private val density = resources.displayMetrics.density
    private val minCellPx = MIN_CELL_DP * density
    private val maxCellPx = MAX_CELL_DP * density
    private var cellPx = 0f
    private var panX = 0f
    private var panY = 0f
    private var viewportInitialized = false

    // --- Peintures (allouées une seule fois, jamais dans onDraw) ---
    private val boardPaint = Paint()

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
    fun update(board: Board, connected: BooleanArray) {
        this.board = board
        this.connected = connected
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

        canvas.save()
        canvas.translate(panX, panY)

        // Fond du plateau (une seule peinture, un seul rect).
        boardPaint.color = ContextCompat.getColor(context, R.color.board_background)
        canvas.drawRect(0f, 0f, b.width * cellPx, b.height * cellPx, boardPaint)

        val serverWired = serverIsFullyWired(b, connected)
        val disconnectedAlpha = (DISCONNECTED_ALPHA * 255).toInt()

        for (y in 0 until b.height) {
            for (x in 0 until b.width) {
                val index = y * b.width + x
                val left = x * cellPx
                val top = y * cellPx
                val isConnected = connected[index]

                // Câble : drawable du masque courant, atténué si déconnecté.
                val dirs = b.currentDirs(index)
                if (dirs != Dir.NO_DIRS) {
                    val tile = tiles[dirs]
                    if (tile != null) {
                        tile.alpha = if (isConnected) 255 else disconnectedAlpha
                        tile.setBounds(left.toInt(), top.toInt(),
                            (left + cellPx).toInt(), (top + cellPx).toInt())
                        tile.draw(canvas)
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
                    equip.setBounds(left.toInt(), top.toInt(),
                        (left + cellPx).toInt(), (top + cellPx).toInt())
                    equip.draw(canvas)
                }
            }
        }
        canvas.restore()
    }

    companion object {
        private const val MIN_CELL_DP = 26f   // jamais de case plus petite que ceci
        private const val MAX_CELL_DP = 96f

        /** Opacité des câbles non reliés au serveur (l'original utilisait
         *  une version grise de chaque bitmap ; ici une seule tuile). */
        private const val DISCONNECTED_ALPHA = 0.55f
    }
}
