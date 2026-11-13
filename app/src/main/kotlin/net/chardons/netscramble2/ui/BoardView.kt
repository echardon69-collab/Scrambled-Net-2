package net.chardons.netscramble2.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
import net.chardons.netscramble2.game.Generator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withSave
import net.chardons.netscramble2.game.Skill
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
 *  - état connecté/déconnecté : câbles en deux jeux de drawables
 *    générés par le pipeline tools/tiles/ a partir de 5 sources SVG
 *    seulement (droit, coude, T, croix, bout — les 15 orientations du
 *    jeu sont des rotations au dessin, pas des fichiers distincts) ;
 *    la variante grise (parité greyOut de Cell.java de l'original :
 *    chaque canal RGB à 0,6 × valeur + 100, alpha inchangé — un gris
 *    clair opaque et uniforme) est produite par le même pipeline, et
 *    les images dédiées restent pour l'équipement
 *    (computer1 écran rouge / computer2 écran vert, server DEL rouges /
 *    server1 DEL vertes), exactement comme Cell.java de l'original ;
 *  - fonds de tuile biseautés « relief » (clair haut/gauche, sombre
 *    bas/droite) : marbré bleu clair, bleu plus foncé pour les cellules
 *    verrouillées, gris pour les cellules libres hors réseau — la parité
 *    des images background / background_locked / empty de l'original,
 *    déclinée en jour et nuit (drawable-night). Ce fond gris déborde du
 *    plateau : au moins une tuile grise l'encadre (la limite du plateau
 *    est toujours visible), le gris remplit le reste de la zone visible,
 *    et le dézoom est borné pour ne jamais découvrir plus d'une tuile
 *    grise horizontalement ;
 *  - viewport « caméra » : le plateau est un monde virtuel, potentiellement
 *    plus grand que l'écran ; on le déplace (pan) et on le zoome au pinçage
 *    — ancré sur le point de pincement : la tuile sous les doigts y reste —
 *    avec une taille de case minimale garantie. Niveau Infini (ENDLESS) :
 *    caméra « modulo » — le pan n'y est pas borné, dessin et taps passent
 *    par le reste euclidien (floorMod) sur l'étendue du plateau, qui se
 *    répète pour remplir l'écran : aucune extrémité n'est jamais visible,
 *    et le dézoom est borné pour que le plateau entier ne tienne JAMAIS
 *    dans la vue (phase B du chantier Infini, 12 nov. 2026).
 *  - appui long : verrouille une cellule (fond de tuile dédié, comme
 *    background_locked.png), qui ne tourne plus ;
 *  - cellules cachées (Insane) : câbles masqués jusqu'à ce que le
 *    solveur les règle ou que la partie soit gagnée (révélation complète,
 *    comme l'original qui révèle tout à la fin) ;
 *  - M2, animations de parité :
 *    . les rotations s'animent — le câble seul tourne, l'équipement
 *      reste fixe, exactement comme Cell.doDraw de l'original ;
 *    . des « blips » de données parcourent les câbles alignés
 *      (portage de Cell.advanceBlips / transferBlips : le serveur émet
 *      à son rythme — métronome pour TREE, à-coups pour ORIGINAL,
 *      effet « électricité » — le flux tourne aux embranchements,
 *      meurt aux terminaux et disparaît quand on tourne une
 *      cellule ; les écrans des terminaux affichent des lignes de
 *      « données », comme Cell.drawTermData). La boucle de trame
 *      (postInvalidateOnAnimation) ne tourne que tant que la vue
 *      est affichée : hors écran, rien n'est redessiné.
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

    // --- Correctif « l'apparence suit la rotation » : le modèle tourne
    //     instantanément, mais la bascule visuelle d'énergie (câble grisé
    //     ou énergisé, écran de terminal, DEL du serveur, blips) ne se
    //     fait qu'à la fin de la rotation — et pas seulement pour la
    //     cellule qui tourne : un coup peut alimenter ou priver d'énergie
    //     une branche entière. Tant qu'une rotation est en cours
    //     quelque part, TOUT le plateau garde l'apparence d'avant le
    //     coup (l'état effectivement dessiné à la trame précédente) :
    //     toutes les tuiles concernées basculent au même moment quand
    //     la rotation s'achève. Sans cela, les autres tuiles changeaient
    //     d'apparence immédiatement au tap, la tuile pivot gardant
    //     encore son ancien état — le réseau paraissait à moitié à
    //     jour pendant l'animation. ---
    private var animFromConnected = BooleanArray(0)
    private var lastDrawnConnected = BooleanArray(0)
    private var animFromServerWired = false
    private var lastDrawnServerWired = false

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

    /**
     * Réglage « Brouillard » (phase C du chantier Infini, 13 nov.
     * 2026) : limite la visibilité du niveau Infini aux abords des
     * tuiles connectées au serveur (Chebyshev <= FOG_RADIUS_CELLS,
     * sur le tore). NOUVELLES PARTIES SEULEMENT : l'activité pose la
     * valeur au lancement d'une partie et n'y retouche plus jamais —
     * basculer le réglage en cours de partie ne change rien à chaud
     * (spec validée le 12 nov. 2026). OFF par défaut.
     */
    var fogEnabled: Boolean = false

    /**
     * Visibilité courante du brouillard, DÉRIVÉE de [connected] à
     * chaque update (zéro état sérialisé : rien ne survit à une
     * sauvegarde, la zone se referme d'elle-même au débranchement).
     * Non null uniquement quand le brouillard s'applique : réglage
     * actif, niveau Infini, partie non encore gagnée (la victoire
     * révèle tout).
     */
    private var fogVisible: BooleanArray? = null

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
    private val fogPaint = Paint()
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val termPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // --- Drawables câbles : 5 formes de base seulement (droit, coude,
    //     T, croix, bout — le masque 0 n'a pas de forme : une cellule
    //     libre du générateur ORIGINAL ne porte aucun câble et le
    //     dessin la court-circuite avant de chercher sa forme), en deux
    //     versions : normale (reliée au serveur, dessin sombre) et grise
    //     (déconnectée, parité greyOut de l'original : chaque canal RGB
    //     à 0,6 x valeur + 100, alpha inchangé). Les variantes grises
    //     sont générées par tools/tiles/convert_tiles.py depuis les
    //     mêmes sources SVG que les normales (svg/tiles/), pas par un
    //     filtre de couleur a l'exécution ; les 15 orientations du jeu
    //     sont obtenues au dessin par canvas.rotate (shapeOf/rotOf). ---
    private val tileShapes = arrayOf(
        load(R.drawable.cable_straight),   // 0 : droit (U+D)
        load(R.drawable.cable_elbow),      // 1 : coude (U+R)
        load(R.drawable.cable_ttee),       // 2 : T (U+R+D)
        load(R.drawable.cable_cross),      // 3 : croix complète
        load(R.drawable.cable_stub)        // 4 : bout (serveur sans fil)
    )
    private val tileShapesGrey = arrayOf(
        load(R.drawable.cable_straight_grey),
        load(R.drawable.cable_elbow_grey),
        load(R.drawable.cable_ttee_grey),
        load(R.drawable.cable_cross_grey),
        load(R.drawable.cable_stub_grey)
    )

    // Forme de base de chaque masque de directions, index 1 à 15
    // (U=1, R=2, D=4, L=8 ; un quart de tour horaire fait passer le
    // masque m au masque ((m shl 1) or (m ushr 3)) and 15).
    private val shapeOf = intArrayOf(
        -1,                                   // 0 : cellule libre, jamais indexée ici
        4, 4, 1, 4, 0,                        // 1-5 : bout, bout, coude, bout, droit
        1, 2, 4, 1, 0,                        // 6-10 : coude, T, bout, coude, droit
        2, 1, 2, 2, 3                        // 11-15 : T, coude, T, T, croix
    )

    // Rotation a appliquer a la forme de base pour obtenir le masque,
    // en degrés horaires (0, 90, 180, 270).
    private val rotOf = intArrayOf(
        0,                                    // 0 : inutilisé
        0, 90, 0, 180, 0,                     // 1-5
        90, 0, 270, 270, 90,                  // 6-10
        270, 180, 180, 90, 0                 // 11-15
    )
    private val computerOff: Drawable = load(R.drawable.computer1)
    private val computerOn: Drawable = load(R.drawable.computer2)
    private val serverPartial: Drawable = load(R.drawable.server)
    private val serverFull: Drawable = load(R.drawable.server1)

    // --- Fonds de tuile « relief » (parité background /
    //     background_locked / empty de l'original) ; la variante nuit est
    //     choisie par la configuration (drawable-night). ---
    private val tileBg: Drawable = load(R.drawable.tile_background)
    private val tileBgLocked: Drawable = load(R.drawable.tile_background_locked)
    private val tileBgEmpty: Drawable = load(R.drawable.tile_background_empty)

    // --- Cache du décor gris : la tuile « empty » pré-rendue en bitmap à
    //     la taille de case courante. Le décor peut couvrir plusieurs
    //     centaines de cases ; un blit de bitmap par case est bien plus
    //     économique qu'un rendu VectorDrawable. Régénéré quand la
    //     taille (entière) change, donc seulement pendant un zoom. ---
    private var emptyTileCache: Bitmap? = null
    private var emptyTileCachePx = 0

    init {
        boardPaint.style = Paint.Style.FILL
        fogPaint.style = Paint.Style.FILL
        // Les drawables de formes (normales et grises) sont déjà
        // chargés au moment de leur déclaration ; ils ne portent plus
        // de filtre de couleur, la variante grise est un drawable
        // distinct généré par le pipeline tools/tiles/.
        //
        // Garde-fou : les tables shapeOf/rotOf sont écrites à la main ;
        // on vérifie une bonne fois, à la construction, que chaque
        // masque 1..15 est bien obtenu en pivotant sa forme de base
        // de rotOf quarts de tour horaires (toute erreur dans ces
        // littéraux produirait des formes fausses en silence).
        require(shapeOf.size == 16 && rotOf.size == 16) {
            "Tables shapeOf/rotOf : 16 entrées attendues"
        }
        val baseMaskOf = intArrayOf(5, 3, 7, 15, 1)
        for (mask in 1..15) {
            var m = baseMaskOf[shapeOf[mask]]
            repeat((rotOf[mask] / 90) % 4) { m = ((m shl 1) or (m ushr 3)) and 15 }
            require(m == mask) {
                "Tables shapeOf/rotOf incorrectes pour le masque $mask"
            }
        }
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
            val b = board ?: return false
            // Plancher de dézoom : plateau + anneau gris = largeur de la
            // vue, jamais au-dessous (on ne découvrirait que du gris).
            // Le zoom est ANCRÉ sur le point de pincement (milieu des
            // deux doigts) : la tuile qui se trouve sous les doigts y
            // reste. Sans la compensation du pan ci-dessous, le monde
            // grandirait autour de son origine et l'affichage glisserait
            // sous les doigts à chaque pincement (défaut relevé par
            // eric le 13 nov. 2026). Le pan est recalculé avec l'ANCIEN
            // cellPx (growth = nouveau / ancien) AVANT de l'écraser.
            // clampPan() peut ensuite reprendre la main pour ne pas
            // découvrir de gris sur les niveaux encadrés ; sur Infini
            // (pan non borné), l'ancrage est exact à la tuile près.
            val focusX = detector.focusX
            val focusY = detector.focusY
            val newCellPx = (cellPx * detector.scaleFactor)
                .coerceIn(floorCellPx(b), maxCellPx)
            val growth = if (cellPx > 0f) newCellPx / cellPx else 1f
            panX = focusX - (focusX - panX) * growth
            panY = focusY - (focusY - panY) * growth
            cellPx = newCellPx
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
        // Une taille de case héritée d'un plateau précédent ne doit jamais
        // rester sous le plancher du plateau courant.
        cellPx = cellPx.coerceAtLeast(floorCellPx(board))
        // Brouillard d'Infini (phase C) : visibilité DÉRIVÉE, recalculée
        // à chaque coup — s'ouvre quand on connecte, se referme quand
        // on débranche ; la victoire révèle tout. Le serveur étant
        // toujours connecté, il y a toujours une zone visible.
        fogVisible =
            if (fogEnabled && board.skill == Skill.ENDLESS && !solved) {
                board.fogVisibility(connected)
            } else {
                null
            }
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
            animFromConnected = BooleanArray(board.size)
            lastDrawnConnected = BooleanArray(board.size)
            animFromServerWired = false
            lastDrawnServerWired = false
            blips = BlipField(board)
            blipsLastAdvanceMs = now
        } else {
            // Détection des rotations survenues depuis le dernier rendu :
            // chaque delta devient une animation de delta × 90°, et fait
            // perdre ses blips à la cellule (comme Cell.rotate de
            // l'original).
            val field = blips
            var rotating = false
            for (i in visualTarget.indices) {
                val delta = ((board.rotations[i] - lastRotations[i]) % 4 + 4) % 4
                if (delta != 0) {
                    visualTarget[i] += delta * 90f
                    animFromAngle[i] = visualCurrent[i]
                    animStartMs[i] = now
                    animDurationMs[i] = ROTATE_MS_PER_QUARTER * delta
                    field?.clear(i)
                    rotating = true
                }
            }
            if (rotating) {
                // Un coup peut changer l'énergie d'une branche entière :
                // c'est le PLATEAU ENTIER qui est gelé sur l'apparence
                // d'avant le coup (état effectivement dessiné à la
                // trame précédente). L'énergie — câbles énergisés,
                // écrans de terminaux, DEL du serveur, blips — bascule
                // partout au même moment, une fois la rotation finie.
                animFromConnected = lastDrawnConnected.copyOf()
                animFromServerWired = lastDrawnServerWired
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

    /**
     * Plancher de dézoom. Deux régimes :
     *  - niveaux encadrés (tous sauf Infini) : le plateau plus une tuile
     *    grise de chaque côté couvre exactement la largeur de la vue.
     *    En dessous, on découvrirait d'autres tuiles grises
     *    horizontalement, ce qui est exclu ; la hauteur n'entre pas dans
     *    le plancher — le gris remplit la zone visible, le pan vertical
     *    reste libre. Borné par les tailles min/max de case : si le
     *    plancher dépasse la taille max (plateau très étroit), le repli
     *    recentre et le gris remplit aussi horizontalement.
     *  - Infini (ENDLESS) : le plateau ENTIER ne doit jamais tenir dans
     *    la vue — l'illusion d'un réseau sans bornes sauterait. Le
     *    dézoom est borné pour que l'étendue du plateau dépasse la plus
     *    grande dimension de la vue d'au moins
     *    [ENDLESS_EDGE_MARGIN_CELLS] cases de chaque côté ; la fenêtre
     *    visible couvre alors toujours MOINS de cases que le plateau
     *    (chaque tuile n'y apparaît qu'une fois). Recalculé à chaque
     *    redimensionnement (onSizeChanged repasse par fitBoard).
     */
    private fun floorCellPx(b: Board): Float =
        if (b.skill == Skill.ENDLESS) {
            val span = minOf(b.width, b.height).toFloat()
            val largest = maxOf(width, height).toFloat()
            (largest / (span - 2f * ENDLESS_EDGE_MARGIN_CELLS))
                .coerceIn(minCellPx, maxCellPx)
        } else {
            (width / (b.width + 2f * GREY_RING_CELLS))
                .coerceIn(minCellPx, maxCellPx)
        }

    /**
     * Taille de case par défaut : le plancher. Les niveaux encadrés
     * apparaissent centrés, entourés d'au moins une tuile grise
     * horizontalement, le gris remplissant le reste de la zone
     * visible. Infini : le plateau déborde forcément de la vue, on le
     * pose centré — il se répète de part et d'autre pour donner un plan
     * sans bornes.
     */
    private fun fitBoard() {
        val b = board ?: return
        cellPx = floorCellPx(b)
        if (b.skill == Skill.ENDLESS) {
            panX = (width - b.width * cellPx) / 2f
            panY = (height - b.height * cellPx) / 2f
            return
        }
        clampPan()
    }

    /**
     * Maintient le plateau dans des limites raisonnables : le pan
     * s'arrête à une tuile grise au-delà du plateau (la limite est
     * toujours visible, jamais contournée). Si le plateau et son anneau
     * tiennent entièrement dans la vue, il est centré et le gris remplit
     * la zone visible.
     *
     * Infini (ENDLESS) : RIEN à borner — le plateau se répète (caméra
     * modulo), il n'existe aucune limite à atteindre ; le pan reste
     * libre dans les deux sens, indéfiniment.
     */
    private fun clampPan() {
        val b = board ?: return
        if (b.skill == Skill.ENDLESS) return
        val ring = GREY_RING_CELLS * cellPx
        val boardW = b.width * cellPx
        val boardH = b.height * cellPx
        panX = if (boardW + 2f * ring <= width) {
            (width - boardW) / 2f
        } else {
            panX.coerceIn(width - boardW - ring, ring)
        }
        panY = if (boardH + 2f * ring <= height) {
            (height - boardH) / 2f
        } else {
            panY.coerceIn(height - boardH - ring, ring)
        }
    }

    /**
     * Tuile « empty » pré-rendue en bitmap à la taille de case courante
     * (cache régénéré seulement quand la taille entière change, donc
     * pendant un zoom). Le décor peut couvrir des centaines de cases :
     * un blit de bitmap par case est bien plus économique qu'un rendu
     * VectorDrawable par case et par trame.
     */
    private fun emptyTileBitmap(): Bitmap? {
        if (cellPx <= 0f) return null
        val size = cellPx.toInt().coerceAtLeast(1)
        var cache = emptyTileCache
        if (cache == null || emptyTileCachePx != size) {
            cache = createBitmap(size, size)
            tileBgEmpty.setBounds(0, 0, size, size)
            tileBgEmpty.draw(Canvas(cache))
            emptyTileCache = cache
            emptyTileCachePx = size
        }
        return cache
    }

    /**
     * Traduit des coordonnées écran en index de cellule, ou null hors
     * plateau. Infini (ENDLESS) : caméra modulo — la tuile sous le
     * doigt est la tuile « reste euclidien » (floorMod) de la colonne
     * et de la ligne monde ; le plateau se répète, une copie vaut
     * l'autre (le mapping est invariant par translation d'un plateau
     * entier). Le floor (et non la troncature) compte pour les
     * coordonnées négatives : à gauche ou au-dessus de l'origine du
     * pan, la tuile est celle d'avant, pas celle d'après.
     */
    private fun hitCell(x: Float, y: Float): Int? {
        val b = board ?: return null
        val cx = floor((x - panX) / cellPx).toInt()
        val cy = floor((y - panY) / cellPx).toInt()
        val index = if (b.skill == Skill.ENDLESS) {
            Math.floorMod(cy, b.height) * b.width +
                Math.floorMod(cx, b.width)
        } else {
            if (cx < 0 || cx >= b.width || cy < 0 || cy >= b.height) return null
            cy * b.width + cx
        }
        // Brouillard : une tuile cachée est SANS interaction — ni
        // rotation au tap, ni verrouillage au long-press.
        val fog = fogVisible
        if (fog != null && !fog[index]) return null
        return index
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

        // Tant qu'une rotation est en cours quelque part, le plateau
        // ENTIER garde l'apparence d'avant le coup (gelé dans update) :
        // toutes les tuiles concernées par le coup basculent ensemble
        // quand la dernière rotation s'achève.
        val appearanceFrozen = animDurationMs.any { it > 0L }
        val endless = b.skill == Skill.ENDLESS

        // --- Fenêtre visible, en cases « monde » (repère du pan),
        //     arrondis vers l'extérieur : la vue n'affiche jamais
        //     moins que ce qu'elle montre. Infini n'itère QUE cette
        //     fenêtre (le plateau en compte 1 600 — on n'en dessine
        //     que ce qui est à l'écran, et le plancher de dézoom la
        //     garantit toujours plus étroite que le plateau : chaque
        //     tuile n'y apparaît qu'une seule fois).
        val minX = floor(-panX / cellPx).toInt()
        val maxX = ceil((width - panX) / cellPx).toInt()
        val minY = floor(-panY / cellPx).toInt()
        val maxY = ceil((height - panY) / cellPx).toInt()

        canvas.withTranslation(panX, panY) {
            // --- Décor : tuiles grises partout dans la zone visible hors
            //     plateau (parité findMatrix/cellMatrix de l'original,
            //     qui couvrait tout l'écran de cellules, plateau centré).
            //     Le plancher de dézoom et le clamp de pan garantissent
            //     qu'on ne voit jamais plus d'une tuile grise
            //     horizontalement au-delà du plateau ; verticalement le
            //     gris remplit la zone visible.
            //     Infini : PAS de décor — le plateau se répète pour
            //     remplir la zone visible, aucun « hors plateau »
            //     n'existe (c'est l'illusion d'un plan sans bornes).
            boardPaint.color = ContextCompat.getColor(context, R.color.board_background)
            if (cellPx > 0f) {
                // Filet de sécurité sur toute la zone visible : normalement
                // recouvert par les tuiles, il masque tout interstice
                // sub-pixel (zoom fractionnaire) entre tuiles voisines.
                drawRect(
                    minX * cellPx, minY * cellPx,
                    maxX * cellPx, maxY * cellPx, boardPaint
                )

                if (!endless) {
                    val decor = emptyTileBitmap()
                    if (decor != null) {
                        for (y in minY until maxY) {
                            val top = y * cellPx
                            val inBoardRow = y in 0 until b.height
                            for (x in minX until maxX) {
                                if (inBoardRow && x in 0 until b.width) continue
                                drawBitmap(decor, x * cellPx, top, null)
                            }
                        }
                    }
                }
            }

            val serverWiredNow = serverIsFullyWired(b, connected)
            // Pendant une rotation, le serveur garde aussi son
            // apparence d'avant le coup (DEL pleines ou partielles) :
            // c'est le gel du plateau entier, pas seulement le sien.
            val serverWired =
                if (appearanceFrozen) animFromServerWired
                else serverWiredNow
            lastDrawnServerWired = serverWired

            // --- Passe des cellules. Deux régimes d'itération, un seul
            //     corps (drawBoardCell) : les niveaux encadrés parcourent
            //     tout le plateau (petit) ; Infini ne parcourt que la
            //     fenêtre visible et en dérive chaque index par floorMod
            //     — le plateau se répète sans couture (les positions
            //     sont des multiples exacts de la taille de case dans
            //     un repère continu, aucun raccord ne se voit).
            if (endless) {
                // Brouillard (phase C) : sur Infini seul, une tuile
                // cachée se dessine en case sombre pleine — ni câble,
                // ni équipement, ni blip. Le brouillard est dérivé par
                // INDEX (floorMod) : toutes les copies visibles d'une
                // même tuile partagent sa visibilité, sans couture.
                val fog = fogVisible
                for (wy in minY until maxY) {
                    for (wx in minX until maxX) {
                        val index =
                            Math.floorMod(wy, b.height) * b.width +
                                Math.floorMod(wx, b.width)
                        if (fog != null && !fog[index]) {
                            drawFogCell(wx * cellPx, wy * cellPx)
                        } else {
                            drawBoardCell(
                                b, connected, appearanceFrozen, serverWired,
                                solved, now, index, wx * cellPx, wy * cellPx
                            )
                        }
                    }
                }
            } else {
                for (y in 0 until b.height) {
                    for (x in 0 until b.width) {
                        drawBoardCell(
                            b, connected, appearanceFrozen, serverWired,
                            solved, now,
                            y * b.width + x, x * cellPx, y * cellPx
                        )
                    }
                }
            }

            // --- M2 : passe des blips de données, séparée des cellules et
            //     sans rognage : un blip qui traverse un bord déborde sur la
            //     cellule voisine (comme l'original, BoardView.doDraw).
            //     M5 : sautée entièrement quand l'animation est coupée
            //     (le modèle BlipField gèle, puis reprend tel quel).
            //     Gel d'apparence : sautée aussi pendant une rotation —
            //     pas de flux de données visible tant que le réseau
            //     n'a pas fini de basculer.
            val field = blips
            if (field != null && blipsEnabled && !appearanceFrozen) {
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

                // Deux régimes d'itération, un seul corps (drawCellBlips) :
                // tout le plateau pour les niveaux encadrés ; la fenêtre
                // visible modulo pour Infini — chaque COPIE visible d'une
                // cellule porte ses blips, et un blip qui traverse un bord
                // du tore (le modèle boucle déjà) apparaît au bord opposé
                // de la copie voisine : aucune couture fonctionnelle.
                if (endless) {
                    // Brouillard : pas de blips sur les tuiles cachées
                    // (les connectées, elles, sont visibles par
                    // définition — le flux anime toujours le réseau).
                    val fog = fogVisible
                    for (wy in minY until maxY) {
                        for (wx in minX until maxX) {
                            val index =
                                Math.floorMod(wy, b.height) * b.width +
                                    Math.floorMod(wx, b.width)
                            if (fog != null && !fog[index]) continue
                            drawCellBlips(
                                field, b, connected, solved,
                                index,
                                wx * cellPx, wy * cellPx,
                                frac, activeColor, idleColor, half, radius
                            )
                        }
                    }
                } else {
                    for (index in 0 until b.size) {
                        drawCellBlips(
                            field, b, connected, solved, index,
                            (index % b.width) * cellPx,
                            (index / b.width) * cellPx,
                            frac, activeColor, idleColor, half, radius
                        )
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

    /**
     * Dessine une tuile CACHÉE par le brouillard d'Infini : case
     * pleine sombre, ressource couleur fog_cell déclinée en jour et
     * nuit (values-night), comme le fond du plateau. Aucun câble,
     * aucun équipement, aucun blip — et hitCell refuse l'interaction
     * dessus : la tuile cachée est purement décorative, la zone
     * visible se découvre en construisant le réseau.
     */
    private fun Canvas.drawFogCell(left: Float, top: Float) {
        fogPaint.color = ContextCompat.getColor(context, R.color.fog_cell)
        drawRect(left, top, left + cellPx, top + cellPx, fogPaint)
    }

    /**
     * Dessine une cellule du plateau — fond biseauté, câble puis
     * équipement — à la position monde ([left], [top]), dans le repère
     * du pan. Le corps est COMMUN à tous les niveaux ; seule la boucle
     * appelante change : itération entière du plateau pour les niveaux
     * encadrés, fenêtre visible modulo pour Infini (la même cellule du
     * plateau peut être dessinée plusieurs fois, une par « copie »
     * visible — elles sont identiques, le plateau se répète).
     */
    private fun Canvas.drawBoardCell(
        b: Board,
        connected: BooleanArray,
        appearanceFrozen: Boolean,
        serverWired: Boolean,
        solved: Boolean,
        now: Long,
        index: Int,
        left: Float,
        top: Float
    ) {
        // Apparence effective : pendant une rotation, chaque
        // cellule garde son état d'avant le coup. Le câble
        // bascule gris ou énergisé, et le terminal s'allume
        // ou s'éteint, seulement une fois la rotation finie.
        val isConnected =
            if (appearanceFrozen) animFromConnected[index]
            else connected[index]
        lastDrawnConnected[index] = isConnected

        // Cellule cachée (Insane) : câbles masqués tant que la
        // partie n'est pas gagnée et que le solveur ne les a pas
        // révélés — l'équipement, lui, reste toujours visible.
        val hideCables =
            b.isHidden(index) && !solved && !b.revealed[index]

        // Câble : drawable du masque courant, version grise si
        // déconnecté. En animation, on dessine le masque final
        // tourné de l'écart résiduel : la tuile tourne sous le
        // doigt. Seul le câble tourne — l'équipement reste
        // fixe (Cell.doDraw).
        val dirs = b.currentDirs(index)

        // Fond de tuile biseauté, parité background /
        // background_locked / empty de l'original. Priorité
        // comme Cell.doDraw : libre avant verrouillée avant
        // normale (une cellule libre verrouillée reste grise).
        // Une cellule libre DU plateau (générateur ORIGINAL)
        // reçoit le fond empty, exactement comme Cell.doDraw
        // de l'original (Dir.FREE -> Image.EMPTY) : elle se
        // fond dans le décor hors plateau.
        val bg = when {
            dirs == Dir.NO_DIRS -> tileBgEmpty
            b.locked[index] -> tileBgLocked
            else -> tileBg
        }
        bg.setBounds(
            left.toInt(), top.toInt(),
            (left + cellPx).toInt(), (top + cellPx).toInt()
        )
        bg.draw(this)

        if (dirs != Dir.NO_DIRS && !hideCables) {
            // Parité greyOut de l'original : variante grise
            // (chaque canal RGB à 0,6 × valeur + 100,
            // générée par le pipeline tools/tiles/) quand
            // la cellule n'est pas reliée au serveur,
            // dessin sombre normal sinon. La forme de base
            // est tournée au dessin vers l'orientation du
            // masque (rotOf), composée avec l'écart résiduel
            // d'animation : la tuile tourne sous le doigt.
            val shape = if (isConnected) tileShapes[shapeOf[dirs]]
                        else tileShapesGrey[shapeOf[dirs]]
            shape.setBounds(
                left.toInt(), top.toInt(),
                (left + cellPx).toInt(), (top + cellPx).toInt()
            )
            val angle = rotOf[dirs] +
                (visualCurrent[index] - visualTarget[index])
            if (angle != 0f) {
                withSave {
                    rotate(angle, left + cellPx / 2f, top + cellPx / 2f)
                    shape.draw(this)
                }
            } else {
                shape.draw(this)
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
            // Effet « électricité » : le serveur respire au
            // rythme de son générateur (TREE métronome,
            // ORIGINAL scintillement) tant que les
            // animations vivent ; coupées, ou pendant le
            // gel d'apparence d'une rotation, il redevient
            // statique.
            equip.alpha =
                if (index == b.serverIndex &&
                    blipsEnabled && !appearanceFrozen) {
                    serverBreathAlpha(b, now)
                } else {
                    255
                }
            equip.setBounds(
                left.toInt(), top.toInt(),
                (left + cellPx).toInt(), (top + cellPx).toInt()
            )
            equip.draw(this)
        }
    }

    /**
     * Dessine les blips de la cellule [index] à la position monde
     * ([x], [y]), dans le repère du pan. Corps commun aux deux régimes
     * d'itération de la passe des blips (plateau entier, ou fenêtre
     * visible modulo pour Infini — chaque copie visible d'une cellule
     * est dessinée avec ses propres blips).
     */
    private fun Canvas.drawCellBlips(
        field: BlipField,
        b: Board,
        connected: BooleanArray,
        solved: Boolean,
        index: Int,
        x: Float,
        y: Float,
        frac: Float,
        activeColor: Int,
        idleColor: Int,
        half: Float,
        radius: Float
    ) {
        val inc = field.incomingAt(index)
        val out = field.outgoingAt(index)
        if (inc == 0 && out == 0) return
        // Cellule cachée : pas de câble, pas de blip
        // (l'équipement reste visible mais ne trahit rien).
        if (b.isHidden(index) && !solved && !b.revealed[index]) return

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
            return
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

    /**
     * Effet « électricité » (décision d'eric du 12 nov. 2026) : le
     * serveur RESPIRE, et son rythme identifie le moteur qui a créé
     * le plateau — TREE : pulsation chorale, métronomique (l'arbre
     * couvrant ordonné) ; ORIGINAL : scintillement irrégulier (les
     * essais de createNet). Seule la LUMINANCE est modulée, jamais
     * la teinte : rouge et vert gardent leur sens (éteint / câblé),
     * et la signature survit aux thèmes daltoniens comme au gris.
     */
    private fun serverBreathAlpha(b: Board, now: Long): Int {
        val span = (255 - SERVER_BREATH_MIN_ALPHA).toFloat()
        val value = when (b.generator) {
            Generator.TREE -> {
                val phase = (now % SERVER_BREATH_PERIOD_MS).toFloat() /
                    SERVER_BREATH_PERIOD_MS
                val pulsation = (2.0 * Math.PI * phase).toFloat()
                0.5f - 0.5f * cos(pulsation)
            }

            Generator.ORIGINAL -> {
                val step = now / SERVER_FLICKER_STEP_MS
                val frac = (now % SERVER_FLICKER_STEP_MS).toFloat() /
                    SERVER_FLICKER_STEP_MS
                val from = flickerValue(b.seed, step)
                val to = flickerValue(b.seed, step + 1L)
                from + (to - from) * frac
            }
        }
        return (SERVER_BREATH_MIN_ALPHA + span * value).toInt()
    }

    /**
     * Valeur pseudo-aléatoire dans [0, 1[ du pas de scintillement
     * [step] du serveur ORIGINAL : hachage SplitMix64 de
     * (graine, pas) — déterministe, sans état à entretenir.
     *
     * Les deux constantes du mélange (nombre d'or 64 bits et
     * finisseur de SplitMix64) ont leur bit de poids fort à 1 :
     * écrites en complément à deux NÉGATIF — la forme que Kotlin
     * accepte, les littéraux hexadécimaux étant signés (c'est le
     * même motif de bits que 0x9E3779B97F4A7C15 et
     * 0xBF58476D1CE4E5B9).
     */
    private fun flickerValue(seed: Long, step: Long): Float {
        var z = seed xor (step * -0x61C8864680B583EBL)
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
        z = z xor (z ushr 31)
        return (z ushr 40) / (1L shl 24).toFloat()
    }

    companion object {
        private const val MIN_CELL_DP = 26f   // jamais de case plus petite que ceci
        private const val MAX_CELL_DP = 96f

        /** Tuiles grises encadrant le plateau (au moins une, et le pan
         *  s'arrête dessus : la limite du plateau est toujours visible). */
        private const val GREY_RING_CELLS = 1f

        /** Infini : marge garantie, en cases, de part et d'autre de la
         *  plus grande dimension de la vue — le plateau entier ne tient
         *  JAMAIS à l'écran (c'est l'illusion d'un plan sans bornes).
         *  Le plancher de dézoom d'Infini découle de cette marge. */
        private const val ENDLESS_EDGE_MARGIN_CELLS = 2f

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

        // --- Effet « électricité » : signature du serveur ---

        /** Période de respiration du serveur TREE (métronome strict). */
        private const val SERVER_BREATH_PERIOD_MS = 1600L

        /** Durée d'un pas de scintillement du serveur ORIGINAL. */
        private const val SERVER_FLICKER_STEP_MS = 140L

        /** Basse de la respiration : le serveur ne paraît jamais éteint. */
        private const val SERVER_BREATH_MIN_ALPHA = 170
    }
}
