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
import net.chardons.netscramble2.VictoryEffect
import net.chardons.netscramble2.VictoryEffects
import net.chardons.netscramble2.VictoryFirework
import net.chardons.netscramble2.game.BlipField
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.CameraSpec
import net.chardons.netscramble2.game.Dir
import net.chardons.netscramble2.game.Generator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withSave
import net.chardons.netscramble2.game.Skill
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
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

    /**
     * Mode Apprentissage (chantier du 17 nov. 2026) : défilement
     * de la caméra observé, deltas en CELLULES (translation de la
     * fenêtre sur le monde). La leçon Infini s'en sert pour
     * mesurer le tour complet du plateau ; le jeu, lui, n'écoute
     * rien — le listener reste null, aucun coût.
     */
    var onCameraScrolled: ((Float, Float) -> Unit)? = null

    /** Pincement observé, en FACTEUR de croissance (1f = rien). */
    var onCameraZoomed: ((Float) -> Unit)? = null

    private var board: Board? = null
    private var connected: BooleanArray? = null

    // État publié par l'update PRÉCÉDENT : le gel v8 (retours du
    // 16 nov. 2026) fige le plateau ENTIER sur lui pendant la
    // rotation du coup courant — l'énergie du coup apparaît quand
    // sa rotation finit, jamais pendant.
    private var prevConnected: BooleanArray? = null
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
    //     coup (l'état publié par l'update PRÉCÉDENT, capturé avant
    //     l'écrasement) : toutes les tuiles concernées basculent au
    //     même moment quand la rotation s'achève. Sans cela, les
    //     autres tuiles changeaient d'apparence immédiatement au tap,
    //     la tuile pivot gardant encore son ancien état — le réseau
    //     paraissait à moitié à jour pendant l'animation. ---
    private var animFromConnected = BooleanArray(0)
    private var animFromServerWired = false

    // --- M2 : blips de données (modèle pur, voir BlipField) ---
    private var blips: BlipField? = null
    private var blipsLastAdvanceMs = 0L

    // --- Mise en évidence de la tuile manipulée (décision d'eric du
    //     13 nov. 2026 : « les deux ») : reprise des deux mécanismes de
    //     l'original (Cell.java de Ian Cameron Smith). Le BLINK est la
    //     bande diagonale contrastée (blanche la nuit, ardoise le
    //     jour — l'original la dessinait blanche sur son plateau
    //     sombre) qui balaie la tuile en 200 ms —
    //     feedback visuel d'une action refusée (tuile verrouillée ou
    //     libre), et clignement de la dernière tuile manipulée à la
    //     victoire. Le CURSEUR DE FOCUS est le cadre rouge translucide
    //     de l'original, réservé chez nous à l'auto-solveur : il suit
    //     les coups programmés, un coup par tick. Pas de focus au doigt
    //     ni de navigation clavier : le tap agit immédiatement, un
    //     curseur persistant serait du bruit visuel. ---
    private var blinkIndex = -1
    private var blinkStartMs = 0L
    private var lastTurnedCell = -1
    private val blinkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Couleur de la bande selon le thème : l'original la dessinait
    // blanche sur son plateau sombre ; notre thème jour est clair
    // (cellules vides #E6E6E6 -> #CFCFCF) où le blanc serait
    // invisible (retour UAT du 13 nov.) — ardoise le jour (R.color
    // .blink_band), blanc la nuit. Résolue une fois : le changement
    // de thème du système recrée l'activité (pas de configChanges
    // uiMode au manifest).
    private val blinkBandColor = ContextCompat.getColor(context, R.color.blink_band)
    private val focusPaint = Paint()

    // --- Chantier Aide à la résolution : cadre vert pulsé de
    //     l'indice, couleur résolue au constructeur selon le thème
    //     (blinkBandColor pattern : le changement de thème recrée
    //     l'activité). ---
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintFrameColor = ContextCompat.getColor(context, R.color.hint_frame)

    /**
     * Tuile du coup EN COURS de l'auto-solveur (index), -1 si aucun :
     * posé par l'activité à chaque état, dessiné en cadre rouge
     * translucide par-dessus la tuile. Transitoire, jamais sérialisé.
     */
    var solverFocus = -1

    /**
     * Tuile visée par le dernier indice (index), -1 si aucune : cadre
     * vert pulsé pendant [HINT_PULSE_MS], par-dessus tout — et même
     * sous le brouillard du Néant : l'indice désigne ce qu'il faut
     * corriger, c'est bien le seul moment où une tuile cachée se
     * montre. Expire de lui-même, transitoire, jamais sérialisé.
     * Posé par l'activité à chaque état.
     */
    var hintIndex = -1
        set(value) {
            field = value
            if (field >= 0) {
                hintStartMs = SystemClock.elapsedRealtime()
                postInvalidateOnAnimation()
            }
        }

    /**
     * (Re)arme la mise en évidence d'indice : cadre vert pulsé sur
     * [index] pendant [HINT_PULSE_MS], ou l'efface (-1). Seule
     * entrée de l'activité — les publications du modèle ne touchent
     * PAS à la pulsation (retour UAT du 15 nov. 2026 : réarmée à
     * chaque tick du chrono, elle ne s'expire jamais et un nouvel
     * appui reste invisible). Elle vit ses cinq secondes, touche
     * du joueur comprise, et chaque demande la relance — même
     * cible, même effet.
     */
    fun showHint(index: Int) {
        hintIndex = index
        if (index < 0) postInvalidateOnAnimation()
    }

    /** Départ de la pulsation du dernier indice posé. */
    private var hintStartMs = 0L

    /**
     * Démarre le clignement d'une tuile : la bande diagonale
     * (blinkBandColor, contrastée dans les deux thèmes) la balaie en
     * [BLINK_MS] (feedback négatif ou clignement de victoire). La
     * boucle de trame reste active tant qu'il vit.
     */
    private fun startBlink(index: Int) {
        blinkIndex = index
        blinkStartMs = SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

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
     * Visibilité courante du brouillard du NÉANT (VOID), DÉRIVÉE de
     * [connected] à chaque update (zéro état sérialisé : rien ne
     * survit à une sauvegarde, la zone se referme d'elle-même au
     * débranchement). Non null uniquement quand le brouillard
     * s'applique : niveau Néant, partie non encore gagnée (la victoire
     * révèle tout). Abîme et Infini ne portent PAS de brouillard —
     * depuis les niveaux dédiés (13 nov. 2026), le brouillard n'est
     * plus un réglage : il est la marque du Néant.
     */
    private var fogVisible: BooleanArray? = null

    /**
     * Caméra d'une partie RESTAURÉE : posée par l'activité juste
     * après la restauration, consommée au premier rendu du plateau
     * restauré (voir [applyArrivalCamera]) — jamais après : les
     * nouveaux coups ne « téléportent » jamais le regard, le pan
     * libre de la famille Infini reste celui du joueur.
     */
    private var restoredCamera: CameraSpec? = null

    /** Caméra de la sauvegarde, posée avant le premier rendu restauré. */
    fun restoreCamera(camera: CameraSpec?) {
        restoredCamera = camera
    }

    /**
     * Caméra d'arrivée DÉFÉRÉE : le premier rendu d'un plateau frais
     * précède souvent la première passe de mesure (le collect de
     * l'activité démarre à onStart, le layout n'a pas encore eu lieu).
     * Une vue de taille nulle ne sait poser ni pan ni zoom : sans ce
     * drapeau, la caméra d'arrivée serait calculée contre 0x0 puis
     * ÉCRASÉE par le fitBoard du premier onSizeChanged — zoom ET
     * position perdus. Le drapeau la garde en réserve jusqu'à ce
     * premier layout.
     */
    private var arrivalCameraPending = false

    /**
     * Préréglage de zoom de la démo posé AVANT la première mesure
     * (v10) : la vue n'a pas encore de dimensions, le cadrage attend
     * le premier onSizeChanged — même mécanique que la caméra
     * d'arrivée ci-dessus. Sans lui, le cadrage posé au démarrage de
     * la démo était perdu au premier plateau (retour utilisateur du
     * 16 nov. 2026).
     */
    private var zoomPresetPending = 0f

    /**
     * Photographie de la caméra COURANTE (position du centre de la
     * vue en cellules + zoom), pour la persistance : la partie
     * reprendra là où le joueur regardait, au zoom qu'il avait
     * choisi.
     */
    fun cameraSnapshot(): CameraSpec {
        val b = board
            ?: return CameraSpec(0, 0, (MIN_CELL_DP * 100).toInt())
        if (cellPx <= 0f || width == 0 || height == 0) {
            return CameraSpec(0, 0, (MIN_CELL_DP * 100).toInt())
        }
        // Normalisation modulo les côtés : pour la famille Infini et
        // sa caméra modulo, une infinité de pans équivalents ne
        // s'écrit que d'une façon ; pour les niveaux encadrés, la
        // position ne sert à rien au décodage mais reste ainsi dans
        // les bornes du format. Les milli-cellules sont ARRONDIES au
        // plus proche (pas tronquées) : la troncature fait dériver
        // l'aller-retour sauvegarde-restauration d'un milli en Float
        // (251 -> 250, etc.) ; l'arrondi, lui, est stable au milli
        // (roundToInt de kotlin.math — contre-preuve exhaustive en
        // float 32 bits, ex æquo vers le pair compris).
        val cx = (width / 2f - panX) / cellPx - 0.5f
        val cy = (height / 2f - panY) / cellPx - 0.5f
        val mx = Math.floorMod((cx * 1_000f).roundToInt(), b.width * 1_000)
        val my = Math.floorMod((cy * 1_000f).roundToInt(), b.height * 1_000)
        val zoom = (cellPx / screenDensity * 100f).toInt()
            .coerceIn((MIN_CELL_DP * 100).toInt(), (MAX_CELL_DP * 100).toInt())
        return CameraSpec(centerMilliX = mx, centerMilliY = my, zoomCentiDp = zoom)
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

    // --- Pan animé du suivi de caméra (chantier Aide à la
    //     résolution) : interpolation dans onDraw, recalculée à
    //     chaque trame pendant PAN_FOLLOW_MS, terminée au clamp. ---
    private var panAnimating = false
    private var panFromX = 0f
    private var panFromY = 0f
    private var panTargetX = 0f
    private var panTargetY = 0f
    private var panAnimStartMs = 0L

    // --- Chantier « Effets spéciaux de réussite » (15 nov. 2026) ---
    /** Effet en cours, null aucun : victoire sans aide ou intermède de la démo. */
    private var victoryEffect: VictoryEffect? = null
    /** Départ de l'effet (elapsedRealtime) : 4,5 s, skip au toucher. */
    private var victoryStartMs = 0L
    /** Origine de la vague, HORS de la zone visible (côté tiré au sort). */
    private var waveOriginX = 0f
    private var waveOriginY = 0f
    /** Origine de la SECONDE vague (retour UAT : elle suit la première). */
    private var wave2OriginX = 0f
    private var wave2OriginY = 0f
    /** Vitesse du front, posée depuis le modèle pur. */
    private var waveSpeedPx = 0f
    /** Feu d'artifice en cours (simulation pure, jamais sérialisée). */
    private var firework: VictoryFirework? = null
    /** Prévenu à la fin de l'effet : le dialogue de victoire attendait. */
    var onVictoryEffectFinished: (() -> Unit)? = null

    // --- Peintures (allouées une seule fois, jamais dans onDraw) ---
    private val boardPaint = Paint()
    private val fogPaint = Paint()
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val termPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val victoryShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val victorySparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

    /**
     * Fond des cellules scellées par un indice correctif (visuel
     * INTÉRIM : rose, à statuer avec eric — la tuile définitive
     * devra se distinguer du simple verrou).
     */
    private val tileBgSealed: Drawable = load(R.drawable.tile_background_sealed)
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
        // Effet de réussite : ombre au sol de la vague, opaque —
        // l'alpha suit le soulèvement, à chaque dessin.
        victoryShadowPaint.style = Paint.Style.FILL
        victoryShadowPaint.color = 0xFF101820.toInt()
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
            // Blink : feedback VISUEL du refus (tuile verrouillée ou
            // libre du générateur ORIGINAL), en plus du clic sec du
            // ViewModel. Le prédicat est le MÊME que celui du
            // ViewModel (garde-fou validate_focus : les deux doivent
            // rester identiques).
            val b = board
            if (b != null && (b.locked[index] || b.isEmpty(index))) {
                startBlink(index)
            }
            onCellTapped?.invoke(index)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val index = hitCell(e.x, e.y) ?: return
            // Blink : appui long sur une tuile libre, comme
            // l'original — feedback négatif (le ViewModel reste
            // silencieux sur ce cas, aucun POP).
            if (board?.isEmpty(index) == true) startBlink(index)
            onCellLongPressed?.invoke(index)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            panX -= dx
            panY -= dy
            clampPan()
            // Mode Apprentissage : la fenêtre a voyagé de -dx/-dy en
            // pixels d'écran, soit la même chose en cellules une
            // fois ramenée à la taille de case courante.
            if (cellPx > 0f) {
                onCameraScrolled?.invoke(-dx / cellPx, -dy / cellPx)
            }
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
            // Mode Apprentissage : le pincement est observé AVANT
            // l'écrasement de cellPx, en facteur de croissance.
            onCameraZoomed?.invoke(growth)
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
        // Le gel v8 fige sur l'état publié par l'update PRÉCÉDENT :
        // capturé en local avant l'écrasement par l'état courant.
        prevConnected = this.connected
        this.connected = connected
        // Clignement de victoire : il porte sur la DERNIÈRE tuile
        // manipulée (joueur ou solveur) — capturée avant que la
        // transition ci-dessous ne la consomme.
        val wasSolved = this.solved
        this.solved = solved
        var freshBoard = false
        // Une taille de case héritée d'un plateau précédent ne doit jamais
        // rester sous le plancher du plateau courant.
        cellPx = cellPx.coerceAtLeast(floorCellPx(board))
        // Brouillard du NÉANT (VOID) : visibilité DÉRIVÉE, recalculée
        // à chaque coup — s'ouvre quand on connecte, se referme quand
        // on débranche ; la victoire révèle tout. Le serveur étant
        // toujours connecté, il y a toujours une zone visible. Les
        // autres niveaux de la famille Infini ne portent pas de
        // brouillard : il est la marque du Néant, pas un réglage.
        fogVisible =
            if (board.skill == Skill.VOID && !solved) {
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
            animFromServerWired = false
            blips = BlipField(board)
            blipsLastAdvanceMs = now
            // Plateau neuf : aucune tuile manipulée à cligner.
            lastTurnedCell = -1
            freshBoard = true
            // L'effet de réussite ne survit PAS au plateau suivant :
            // annulation MUETTE, sans prévenir l'écouteur — le
            // dialogue éventuel suivra la victoire suivante.
            victoryEffect = null
            firework = null
        } else {
            // Détection des rotations survenues depuis le dernier rendu :
            // chaque delta devient une animation de delta × 90°, et fait
            // perdre ses blips à la cellule (comme Cell.rotate de
            // l'original).
            val field = blips
            var rotating = false
            for (i in visualTarget.indices) {
                if (((board.rotations[i] - lastRotations[i]) % 4 + 4) % 4 != 0) {
                    rotating = true
                    break
                }
            }
            if (rotating) {
                // Un coup arrive pendant que des rotations animent
                // (démo rapide, solveur du jeu) : TOUTE rotation en
                // vol est FINIE d’un coup — la cellule du nouveau coup
                // comme les AUTRES encore en vol. Sans cela (v8), le
                // gel — recapturé à chaque coup — relâchait l’énergie
                // du coup précédent pendant que SA rotation volait
                // encore : l’anomalie « mise en évidence PENDANT la
                // rotation » (retour du 16 nov. 2026). Même rabot des
                // tours complets qu’à la fin naturelle : les angles
                // restent dans le rabot du rendu ci-dessous.
                for (i in visualTarget.indices) {
                    if (animDurationMs[i] > 0L) {
                        animDurationMs[i] = 0L
                        visualCurrent[i] = visualTarget[i]
                        if (visualTarget[i] >= 360f || visualTarget[i] <= -360f) {
                            val whole = 360f * (visualTarget[i] / 360f).toInt()
                            visualTarget[i] -= whole
                            visualCurrent[i] -= whole
                        }
                    }
                }
            }
            for (i in visualTarget.indices) {
                val delta = ((board.rotations[i] - lastRotations[i]) % 4 + 4) % 4
                if (delta != 0) {
                    visualTarget[i] += delta * 90f
                    animFromAngle[i] = visualCurrent[i]
                    animStartMs[i] = now
                    animDurationMs[i] = ROTATE_MS_PER_QUARTER * delta
                    field?.clear(i)
                    // Dernière tuile manipulée (joueur ou solveur) :
                    // c'est elle que clignera la victoire.
                    lastTurnedCell = i
                    rotating = true
                }
            }
            if (rotating) {
                // Un coup peut changer l’énergie d’une branche entière :
                // c’est le PLATEAU ENTIER qui est gelé sur l’état
                // publié d’AVANT ce coup — celui du coup précédent,
                // dont la rotation en vol vient d’être finie
                // ci-dessus. L’énergie — câbles énergisés, écrans de
                // terminaux, DEL du serveur, blips — bascule partout
                // au même moment, une fois la rotation finie : jamais
                // PENDANT la rotation du coup qui la provoque.
                val prev = prevConnected
                if (prev != null) {
                    animFromConnected = prev.copyOf()
                    animFromServerWired = serverIsFullyWired(board, prev)
                }
            }
            lastRotations = board.rotations.copyOf()
        }
        // Clignement de victoire : la DERNIÈRE tuile manipulée (joueur
        // ou solveur) cligne quand le plateau bascule résolu — comme
        // le blink(changedCell) de l'original à chaque victoire. Une
        // partie restaurée déjà résolue ne cligne pas : aucune tuile
        // n'a été manipulée depuis la mise en place du plateau.
        if (solved && !wasSolved && lastTurnedCell >= 0) {
            startBlink(lastTurnedCell)
        }
        if (!viewportInitialized) {
            fitBoard()
            viewportInitialized = true
        } else {
            clampPan()
        }
        if (freshBoard) {
            if (width > 0 && height > 0) {
                applyArrivalCamera(solved)
            } else {
                // Premier rendu AVANT le layout : poser la caméra ici
                // la calculerait contre une vue de taille nulle, et le
                // fitBoard du premier onSizeChanged l'écraserait juste
                // après (zoom ET pan perdus). Elle attend le layout.
                arrivalCameraPending = true
            }
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Avant tout, le regard de la famille Infini : le centre de la
        // caméra en cellules et le zoom courant, calculés sur
        // l'ancienne taille (panX/panY ne bougent pas ici). Une
        // rotation d'écran ne « téléporte » plus le regard au centre
        // du plateau — on reprend là où l'on regardait.
        val b = board
        val keepCamera = b != null && b.skill.endless && cellPx > 0f &&
            oldw > 0 && oldh > 0
        val cx = if (keepCamera) (oldw / 2f - panX) / cellPx - 0.5f else 0f
        val cy = if (keepCamera) (oldh / 2f - panY) / cellPx - 0.5f else 0f
        val zoomPx = if (keepCamera) cellPx else 0f
        super.onSizeChanged(w, h, oldw, oldh)
        viewportInitialized = false
        board?.let { fitBoard() }
        viewportInitialized = true
        if (b != null && keepCamera) {
            // Le zoom est préservé (borné au nouveau plancher), le pan
            // reposé sur le centre mémorisé.
            cellPx = zoomPx.coerceIn(floorCellPx(b), maxCellPx)
            panX = width / 2f - (cx + 0.5f) * cellPx
            panY = height / 2f - (cy + 0.5f) * cellPx
        }
        if (arrivalCameraPending) {
            // Premier layout terminé : la caméra d'arrivée du plateau
            // posé avant mesure (partie restaurée ou Néant qui démarre)
            // se pose MAINTENANT, avec une vue qui a enfin sa taille.
            // Après le fitBoard : elle prend le pas sur son centrage.
            arrivalCameraPending = false
            applyArrivalCamera(solved)
        }
        if (zoomPresetPending > 0f) {
            // Premier layout terminé : le préréglage de zoom de la
            // démo posé avant mesure se pose MAINTENANT, après le
            // fitBoard ET la caméra d'arrivée (le Néant garde sa
            // zone révélée cadrée, comme en cours de partie).
            val pending = zoomPresetPending
            zoomPresetPending = 0f
            applyZoomPreset(pending)
        }
    }

    /**
     * Plancher de dézoom. Deux régimes :
     *  - niveaux encadrés (tous sauf la famille Infini) : le plateau
     *    plus une tuile
     *    grise de chaque côté couvre exactement la largeur de la vue.
     *    En dessous, on découvrirait d'autres tuiles grises
     *    horizontalement, ce qui est exclu ; la hauteur n'entre pas dans
     *    le plancher — le gris remplit la zone visible, le pan vertical
     *    reste libre. Borné par les tailles min/max de case : si le
     *    plancher dépasse la taille max (plateau très étroit), le repli
     *    recentre et le gris remplit aussi horizontalement.
     *  - famille Infini (Endless, Abîme, Néant) : le plateau ENTIER ne
     *    doit jamais tenir dans
     *    la vue — l'illusion d'un réseau sans bornes sauterait. Le
     *    dézoom est borné pour que l'étendue du plateau dépasse la plus
     *    grande dimension de la vue d'au moins
     *    [ENDLESS_EDGE_MARGIN_CELLS] cases de chaque côté ; la fenêtre
     *    visible couvre alors toujours MOINS de cases que le plateau
     *    (chaque tuile n'y apparaît qu'une fois). Recalculé à chaque
     *    redimensionnement (onSizeChanged repasse par fitBoard).
     */
    private fun floorCellPx(b: Board): Float =
        if (b.skill.endless) {
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
        if (b.skill.endless) {
            panX = (width - b.width * cellPx) / 2f
            panY = (height - b.height * cellPx) / 2f
            return
        }
        clampPan()
    }

    /**
     * Caméra d'ARRIVÉE sur un plateau fraîchement posé, APRÈS le
     * fitBoard/clampPan de [update] (elle prend le pas sur leur
     * centrage). Priorité : la caméra d'une partie restaurée
     * (emplacement et zoom de la sauvegarde — le zoom pour tous les
     * niveaux, la position pour la famille Infini, la seule au pan
     * libre ; les niveaux encadrés se re-clipent tout seuls). À
     * défaut, un Néant qui démarre se centre sur sa zone visible —
     * le serveur et son carré révélé — à un zoom confortable : le
     * joueur ne cherche JAMAIS le réseau. Le reste de la famille
     * garde le centrage plateau de fitBoard.
     */
    private fun applyArrivalCamera(solved: Boolean) {
        val b = board ?: return
        val camera = restoredCamera
        restoredCamera = null
        if (camera != null) {
            cellPx = (camera.zoomCentiDp / 100f * screenDensity)
                .coerceIn(floorCellPx(b), maxCellPx)
            if (b.skill.endless) {
                val cx = camera.centerMilliX / 1_000f
                val cy = camera.centerMilliY / 1_000f
                panX = width / 2f - (cx + 0.5f) * cellPx
                panY = height / 2f - (cy + 0.5f) * cellPx
            } else {
                clampPan()
            }
            return
        }
        if (b.skill == Skill.VOID && !solved) {
            // Zoom confort du Néant : le carré révélé — (2r+1) cases
            // de côté, r = rayon de Chebyshev du brouillard — occupe
            // la MOITIÉ du petit côté de la vue (spéc validée par
            // eric : min / (2 x (2r+1))). Borné par le plancher de la
            // famille : l'illusion d'infini ne se négocie que vers le
            // haut, jamais vers le bas.
            cellPx = (minOf(width, height) /
                (2f * (2f * Board.FOG_RADIUS_CELLS + 1f)))
                .coerceIn(floorCellPx(b), maxCellPx)
            centerOnVisibleZone()
        }
    }

    /**
     * Centre la vue sur la zone visible du Néant : le centre de la
     * boîte englobante du réseau connecté (au lancement, le réseau
     * connecté se réduit au serveur et son carré révélé). Sur le
     * tore, la boîte est calculée sans dénouage d'enroulement : la
     * zone connectée d'un départ de partie est trop petite pour le
     * subir, et le pan reste libre — le joueur peut toujours glisser
     * vers ce qu'il cherche.
     */
    private fun centerOnVisibleZone() {
        val b = board ?: return
        val zone = connected ?: return
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (i in zone.indices) {
            if (!zone[i]) continue
            val x = i % b.width
            val y = i / b.width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (minX > maxX) return
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        panX = width / 2f - (cx + 0.5f) * cellPx
        panY = height / 2f - (cy + 0.5f) * cellPx
    }

    /**
     * Maintient le plateau dans des limites raisonnables : le pan
     * s'arrête à une tuile grise au-delà du plateau (la limite est
     * toujours visible, jamais contournée). Si le plateau et son anneau
     * tiennent entièrement dans la vue, il est centré et le gris remplit
     * la zone visible.
     *
     * Infini (famille ENDLESS, ABYSS, VOID) : RIEN à borner — le plateau
     * se répète (caméra modulo), il n'existe aucune limite à atteindre ;
     * le pan reste libre dans les deux sens, indéfiniment.
     */
    private fun clampPan() {
        val b = board ?: return
        if (b.skill.endless) return
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
     * Régimes du suivi de caméra du chantier Aide à la résolution.
     * CENTER recentre toujours sur la tuile. VISIBLE ne recentre que
     * si la tuile est ENTIÈREMENT hors de la vue (indice, solveur et
     * démonstration : un plateau entièrement visible ne bouge
     * JAMAIS — retour UAT d'eric du 15 nov. 2026 : l'ancien test de
     * visibilité inversait la convention du pan et croyait hors vue
     * les tuiles de la marge). HYSTERESIS ne recentre que si la
     * tuile sort du rectangle central de la vue, de 30 à 70 pour
     * cent.
     */
    enum class FollowMode { CENTER, VISIBLE, HYSTERESIS }

    /**
     * Suit la tuile [index] avec la caméra selon [mode] : pan ANIMÉ
     * sur [PAN_FOLLOW_MS], interpolé dans onDraw. Sans effet sur une
     * vue pas encore mesurée.
     */
    fun followCell(index: Int, mode: FollowMode) {
        val b = board ?: return
        if (index < 0 || index >= b.size) return
        if (cellPx <= 0f || width == 0 || height == 0) return
        val cellLeft = (index % b.width) * cellPx
        val cellTop = (index / b.width) * cellPx
        val cx = cellLeft + cellPx / 2f
        val cy = cellTop + cellPx / 2f
        when (mode) {
            FollowMode.CENTER -> {
                // Recentrage systématique : l'indice désigne, la vue suit.
            }
            FollowMode.VISIBLE -> {
                // Convention du pan ADDITIVE (écran = contenu + pan) :
                // la plage visible en coordonnées contenu est
                // [-pan, taille - pan]. Toute cellule qui la coupe,
                // même en partie, est visible : la caméra ne bouge
                // pas (correctif UAT — l'ancien test comparait à
                // [pan, pan + taille] et croyait hors vue les tuiles
                // de la marge d'un plateau ajusté centré).
                if (cellLeft + cellPx + panX >= 0f && cellLeft + panX <= width &&
                    cellTop + cellPx + panY >= 0f && cellTop + panY <= height
                ) return // visible, même en partie : la caméra ne bouge pas
            }
            FollowMode.HYSTERESIS -> {
                // Rectangle central 30–70 % de la vue, ramené en
                // coordonnées contenu par la même convention.
                if (cx + panX >= width * 0.3f && cx + panX <= width * 0.7f &&
                    cy + panY >= height * 0.3f && cy + panY <= height * 0.7f
                ) return // encore dans le rectangle central
            }
        }
        panFromX = panX
        panFromY = panY
        panTargetX = width / 2f - cx
        panTargetY = height / 2f - cy
        // Cible BORNÉE aux mêmes limites que clampPan : le pan animé
        // survit au clampPan du prochain update() sans claquement de
        // retour (la famille Infini garde son pan libre).
        if (!b.skill.endless) {
            val ring = GREY_RING_CELLS * cellPx
            val boardW = b.width * cellPx
            val boardH = b.height * cellPx
            if (boardW + 2f * ring > width) {
                panTargetX = panTargetX.coerceIn(width - boardW - ring, ring)
            }
            if (boardH + 2f * ring > height) {
                panTargetY = panTargetY.coerceIn(height - boardH - ring, ring)
            }
        }
        panAnimStartMs = SystemClock.elapsedRealtime()
        panAnimating = true
        postInvalidateOnAnimation()
    }

    /**
     * Préréglage de zoom de la démonstration : multiplicateur du
     * plancher. FIT (1f) ajuste le plateau à la vue (fitBoard) ;
     * au-delà, la case vaut le plancher multiplié, bornée au maximum,
     * et la vue se recentre : la caméra de la démo suit les coups du
     * solveur (followCell), pas un pan hérité.
     *
     * Retours utilisateurs du 16 nov. 2026 (lot 2) : au Néant,
     * ajuster le plateau ENTIER ne montrait presque que du
     * brouillard. La démo cadre la ZONE RÉVÉLÉE : la case vaut le
     * zoom confort du Néant (le carré révélé occupe la moitié du
     * petit côté, spec validée par eric) MULTIPLIÉ par le réglage,
     * bornée au plancher et au maximum — à ×2 le carré révélé
     * remplit le petit côté de la vue. La caméra d'arrivée a posé
     * son cadrage juste avant ([update]) : ce préréglage la
     * survole, la caméra de suivi prend ensuite le relais au fil
     * du réseau.
     *
     * V10 (retours utilisateurs du 16 nov. 2026) : le réglage de
     * zoom de la démo est retiré — la démo appelle toujours FIT.
     * Si la vue n'a pas encore de dimensions (premier rendu AVANT
     * le layout), le préréglage attend la première mesure via
     * [zoomPresetPending] au lieu d'être perdu : le cadrage
     * s'applique au PREMIER plateau, pas seulement au deuxième.
     */
    fun applyZoomPreset(multiplier: Float) {
        val b = board ?: return
        if (width == 0 || height == 0) {
            // Premier rendu AVANT le layout : le préréglage attend la
            // première mesure (il n'était appliqué qu'au deuxième
            // plateau, retour utilisateur du 16 nov. 2026).
            zoomPresetPending = multiplier
            return
        }
        zoomPresetPending = 0f
        if (b.skill == Skill.VOID && !solved) {
            cellPx = ((minOf(width, height) /
                (2f * (2f * Board.FOG_RADIUS_CELLS + 1f))) * multiplier)
                .coerceIn(floorCellPx(b), maxCellPx)
            centerOnVisibleZone()
            return
        }
        if (multiplier <= 1f || width == 0 || height == 0) {
            fitBoard()
            return
        }
        cellPx = (floorCellPx(b) * multiplier)
            .coerceIn(floorCellPx(b), maxCellPx)
        if (b.skill.endless) {
            panX = (width - b.width * cellPx) / 2f
            panY = (height - b.height * cellPx) / 2f
        } else {
            clampPan()
        }
    }

    // --- Mode Apprentissage, pilotes de démo du doigt (refonte
    //     « doigt animé », 17 nov. 2026) : la DÉMO bouge la caméra
    //     POUR DE VRAI — le contenu glisse sous le doigt, le zoom
    //     s'ancre au centre de la vue — mais SANS invoquer les
    //     listeners du joueur : le voyage d'une consigne ne se
    //     mesure qu'aux gestes du joueur (LessonRunner), jamais à
    //     la démonstration (garde-fou validate_apprentissage). ---

    /**
     * Centre ÉCRAN d'une tuile (coordonnées de cette vue), null si
     * la vue ou la tuile n'est pas encore mesurable : le doigt de
     * démo vise ce point.
     */
    fun cellCenter(index: Int): FloatArray? {
        val b = board ?: return null
        if (index < 0 || index >= b.size) return null
        if (cellPx <= 0f || width == 0 || height == 0) return null
        val x = panX + (index % b.width) * cellPx + cellPx / 2f
        val y = panY + (index / b.width) * cellPx + cellPx / 2f
        return floatArrayOf(x, y)
    }

    /**
     * Pan de démo, en PIXELS d'écran : le contenu suit le doigt
     * (même convention que le geste du joueur, sans son
     * l'inversion de onScroll qui traduit le doigt en cellules).
     * Aucun listener : l'accumulateur de la consigne reste au
     * joueur.
     */
    fun demoPanBy(dxPx: Float, dyPx: Float) {
        if (cellPx <= 0f) return
        panX += dxPx
        panY += dyPx
        panAnimating = false
        clampPan()
        invalidate()
    }

    /**
     * Zoom de démo par FACTEUR, ancré au CENTRE de la vue (le
     * pincement du doigt s'y joue), borné plancher/plafond — même
     * mathématique que onScale, aucun listener.
     */
    fun demoZoomBy(ratio: Float) {
        val b = board ?: return
        if (cellPx <= 0f || ratio <= 0f) return
        val newCellPx = (cellPx * ratio).coerceIn(floorCellPx(b), maxCellPx)
        val growth = newCellPx / cellPx
        if (growth == 1f) return
        val focusX = width / 2f
        val focusY = height / 2f
        panX = focusX - (focusX - panX) * growth
        panY = focusY - (focusY - panY) * growth
        cellPx = newCellPx
        panAnimating = false
        clampPan()
        invalidate()
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
        val index = if (b.skill.endless) {
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
        // Effet de réussite en cours : tout toucher le termine (skip,
        // décision d'eric) — un plateau résolu n'a rien à perdre.
        if (victoryEffect != null) {
            endVictoryEffect()
            return true
        }
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

        // Pan animé du suivi de caméra (indice, solveur, démo) :
        // interpolation linéaire sur PAN_FOLLOW_MS, terminée au
        // clamp — les niveaux encadrés se re-clipent tout seuls, la
        // famille Infini garde son pan libre.
        if (panAnimating) {
            val t = ((now - panAnimStartMs).toFloat() / PAN_FOLLOW_MS)
                .coerceIn(0f, 1f)
            panX = panFromX + (panTargetX - panFromX) * t
            panY = panFromY + (panTargetY - panFromY) * t
            if (t >= 1f) {
                panAnimating = false
                clampPan()
            }
        }

        // Tant qu'une rotation est en cours quelque part, le plateau
        // ENTIER garde l'apparence d'avant le coup (gelé dans update) :
        // toutes les tuiles concernées par le coup basculent ensemble
        // quand la dernière rotation s'achève.
        val appearanceFrozen = animDurationMs.any { it > 0L }
        val endless = b.skill.endless

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
                                drawVictoryDecor(decor, now, x * cellPx, top)
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
                        if (fog != null && !fog[index] && index != hintIndex) {
                            drawFogCell(wx * cellPx, wy * cellPx)
                        } else {
                            drawVictoryCell(
                                b, connected, appearanceFrozen, serverWired,
                                solved, now, index, wx * cellPx, wy * cellPx
                            )
                        }
                    }
                }
            } else {
                for (y in 0 until b.height) {
                    for (x in 0 until b.width) {
                        drawVictoryCell(
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
                            // Vague de victoire (v10) : les billes
                            // suivent la tuile SOULEVÉE, même lift que
                            // la passe des cellules — sans cela elles
                            // restaient au sol, hors des connecteurs.
                            val left = wx * cellPx
                            val top = wy * cellPx
                            drawCellBlips(
                                field, b, connected, solved,
                                index,
                                left, top - waveLiftPx(now, left, top),
                                frac, activeColor, idleColor, half, radius
                            )
                        }
                    }
                } else {
                    for (index in 0 until b.size) {
                        // Vague de victoire (v10) : même lift que la
                        // passe des cellules — les billes suivent la
                        // tuile soulevée.
                        val left = (index % b.width) * cellPx
                        val top = (index / b.width) * cellPx
                        drawCellBlips(
                            field, b, connected, solved, index,
                            left, top - waveLiftPx(now, left, top),
                            frac, activeColor, idleColor, half, radius
                        )
                    }
                }
            }
        }

        // Chantier « Effets spéciaux de réussite » : le feu d'artifice
        // vit AU-DESSUS du plateau (repère écran, monde + pan) — la
        // vague, elle, agit dans la passe cellulaire. Puis la fin du
        // temps : profil partout nul, étincelles mortes — l'effet
        // s'arrête net et prévient l'activité (dialogue en attente).
        canvas.drawFireworkOverlay(now)
        if (victoryEffect != null && now - victoryStartMs >= VictoryEffects.EFFECT_MS) {
            endVictoryEffect()
        }

        // M2 : tant que la vue est affichée, demander la trame suivante
        // (rotations en cours, blips). C'est l'équivalent de la boucle
        // permanente de l'original (setDelay(30)), mais arrêté
        // automatiquement dès que la vue quitte l'écran — ou dès que
        // plus rien ne s'anime (M5 : animation coupée, rotations finies).
        // Le blink vit 200 ms : la trame reste active jusqu'à sa fin.
        if (blipsEnabled || animDurationMs.any { it > 0L } || blinkIndex >= 0) {
            postInvalidateOnAnimation()
        }
        // Chantier Aide à la résolution : le cadre d'indice vit tant
        // que sa pulsation n'a pas expiré, et le pan animé du suivi
        // de caméra tant qu'il n'est pas posé — la boucle de trame
        // reste active pour eux, indépendamment des blips.
        if (hintIndex >= 0) {
            postInvalidateOnAnimation()
        }
        if (panAnimating) {
            postInvalidateOnAnimation()
        }
        // Chantier « Effets spéciaux de réussite » : l'effet vit
        // jusqu'à son terme — ou son skip au toucher.
        if (victoryEffect != null) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Joue l'effet de réussite [effect], tiré au sort par l'activité
     * (spec validée par eric : 4,5 s, skip au toucher, dialogue en
     * attente de la fin). La vague part d'un point HORS de la zone
     * visible — côté tiré au sort, marge de 0,2 fois le petit côté —
     * et traverse la fenêtre en soulevant TOUT le plateau visible
     * (onde de choc) ; le feu d'artifice tire ses fusées du bas de
     * la zone. L'effet vit dans le repère du pan (monde), borné à
     * la fenêtre visible : la famille Infini ne paie que l'écran.
     *
     * Retour UAT d'eric (16 nov. 2026) : l'effet couvre désormais
     * l'ENSEMBLE de l'écran du téléphone — la vague soulève aussi
     * le décor autour du plateau et devient DEUX vagues successives
     * sur des côtés différents, plus amples ; le feu d'artifice vit
     * en repère ÉCRAN, toute la vue, sans pan de centrage. Le chrome
     * autour de la vue (barre d'outils, barre d'état) reste hors
     * effet : l'animation appartient au jeu.
     */
    fun playVictoryEffect(effect: VictoryEffect) {
        if (board == null) return
        victoryEffect = effect
        victoryStartMs = SystemClock.elapsedRealtime()
        firework = null
        val worldLeft = -panX
        val worldTop = -panY
        val worldW = width.toFloat()
        val worldH = height.toFloat()
        if (effect == VictoryEffect.WAVE) {
            val margin = 0.2f * minOf(width, height)
            // Retour UAT d'eric (16 nov. 2026) : DEUX vagues — la
            // seconde sur un AUTRE côté (jamais le même), lancée à
            // mi-effet une fois la première passée. La seconde est
            // posée d'abord puis recopiée : l'outil de placement
            // écrit dans les champs de la première origine.
            val first = rng.nextInt(4)
            val second = (first + 1 + rng.nextInt(3)) % 4
            placeWaveOrigin(second, worldLeft, worldTop, worldW, worldH, margin)
            wave2OriginX = waveOriginX
            wave2OriginY = waveOriginY
            placeWaveOrigin(first, worldLeft, worldTop, worldW, worldH, margin)
            waveSpeedPx = VictoryEffects.waveSpeed(worldW, worldH)
        } else {
            waveSpeedPx = 0f
            // Retour UAT : le feu d'artifice vit en repère ÉCRAN —
            // toute la vue, PAS la seule zone du plateau (le pan de
            // centrage ne le décale plus).
            firework = VictoryFirework(victoryStartMs, width.toFloat(), height.toFloat())
        }
        postInvalidateOnAnimation()
    }

    /**
     * Pose l'origine d'une vague sur le côté [side], HORS de la
     * zone visible — marge de 0,2 fois le petit côté (spec eric).
     */
    private fun placeWaveOrigin(
        side: Int,
        worldLeft: Float,
        worldTop: Float,
        worldW: Float,
        worldH: Float,
        margin: Float
    ) {
        when (side) {
            0 -> {
                waveOriginX = worldLeft + rng.nextFloat() * worldW
                waveOriginY = worldTop - margin
            }
            1 -> {
                waveOriginX = worldLeft + worldW + margin
                waveOriginY = worldTop + rng.nextFloat() * worldH
            }
            2 -> {
                waveOriginX = worldLeft + rng.nextFloat() * worldW
                waveOriginY = worldTop + worldH + margin
            }
            else -> {
                waveOriginX = worldLeft - margin
                waveOriginY = worldTop + rng.nextFloat() * worldH
            }
        }
    }

    /**
     * Termine l'effet en cours — temps écoulé ou contact (skip au
     * toucher, décision d'eric) — et prévient l'écouteur : le
     * dialogue de victoire attendait cette fin. L'annulation par un
     * plateau NOUVEAU (update) ne passe pas ici : elle est muette,
     * le dialogue éventuel suivra la victoire suivante.
     */
    private fun endVictoryEffect() {
        if (victoryEffect == null) return
        victoryEffect = null
        firework = null
        postInvalidateOnAnimation()
        onVictoryEffectFinished?.invoke()
    }

    /**
     * Soulèvement par la vague, en pixels de monde : cloche du
     * profil autour du front, ramenée à une fraction de la taille
     * de case. Retour UAT d'eric : DEUX vagues par animation — la
     * seconde part à mi-effet d'un autre côté, et une tuile prend
     * le MAX des deux fronts (jamais la somme : l'ampleur reste
     * bornée). Nul hors vagues, nul une fois le front passé — la
     * fin de l'effet ne saute jamais.
     */
    private fun waveLiftPx(now: Long, left: Float, top: Float): Float {
        if (victoryEffect != VictoryEffect.WAVE) return 0f
        val elapsedMs = (now - victoryStartMs).coerceAtLeast(0L)
        val cx = left + cellPx / 2f
        val cy = top + cellPx / 2f
        val front1 = waveSpeedPx * (elapsedMs / 1000f)
        val p1 = VictoryEffects.waveProfile(
            hypot(cx - waveOriginX, cy - waveOriginY) - front1, cellPx
        )
        var p = p1
        if (elapsedMs >= VictoryEffects.WAVE2_DELAY_MS) {
            val front2 = waveSpeedPx * ((elapsedMs - VictoryEffects.WAVE2_DELAY_MS) / 1000f)
            val p2 = VictoryEffects.waveProfile(
                hypot(cx - wave2OriginX, cy - wave2OriginY) - front2, cellPx
            )
            if (p2 > p) p = p2
        }
        return p * VictoryEffects.WAVE_LIFT_CELLS * cellPx
    }

    /**
     * Passe cellulaire des effets de réussite : pendant la vague, la
     * tuile se lève au passage du front — translation, léger
     * grossissement, ombre au sol à l'emplacement de base — et le
     * corps du dessin ([drawBoardCell]) n'y change pas d'un iota ;
     * sans soulèvement, dessin direct. Les deux régimes d'itération
     * (fenêtre visible modulo, plateau entier) passent ici.
     */
    private fun Canvas.drawVictoryCell(
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
        val lift = waveLiftPx(now, left, top)
        if (lift <= 0f) {
            drawBoardCell(b, connected, appearanceFrozen, serverWired, solved, now, index, left, top)
            return
        }
        val t = (lift / (VictoryEffects.WAVE_LIFT_CELLS * cellPx)).coerceIn(0f, 1f)
        victoryShadowPaint.alpha = (t * 130f).toInt()
        drawRect(left, top, left + cellPx, top + cellPx, victoryShadowPaint)
        withTranslation(0f, -lift) {
            withSave {
                val cx = left + cellPx / 2f
                val cy = top + cellPx / 2f
                val scale = 1f + VictoryEffects.WAVE_SCALE_FRACTION * t
                translate(cx, cy)
                scale(scale, scale)
                translate(-cx, -cy)
                drawBoardCell(b, connected, appearanceFrozen, serverWired, solved, now, index, left, top)
            }
        }
    }

    /**
     * Décor soulevé par la vague — même traitement que les tuiles
     * ([drawVictoryCell]) : ombre au sol, translation, léger
     * grossissement, dessin direct hors vague. Retour UAT : la
     * vague balaye TOUT l'écran, le décor autour du plateau
     * bouge avec les tuiles.
     */
    private fun Canvas.drawVictoryDecor(bitmap: Bitmap, now: Long, left: Float, top: Float) {
        val lift = waveLiftPx(now, left, top)
        if (lift <= 0f) {
            drawBitmap(bitmap, left, top, null)
            return
        }
        val t = (lift / (VictoryEffects.WAVE_LIFT_CELLS * cellPx)).coerceIn(0f, 1f)
        victoryShadowPaint.alpha = (t * 130f).toInt()
        drawRect(left, top, left + cellPx, top + cellPx, victoryShadowPaint)
        withTranslation(0f, -lift) {
            withSave {
                val cx = left + cellPx / 2f
                val cy = top + cellPx / 2f
                val scale = 1f + VictoryEffects.WAVE_SCALE_FRACTION * t
                translate(cx, cy)
                scale(scale, scale)
                translate(-cx, -cy)
                drawBitmap(bitmap, left, top, null)
            }
        }
    }

    /**
     * Feu d'artifice : fusées (trace claire) puis gerbes d'étincelles
     * colorées, AU-DESSUS du plateau. Retour UAT d'eric : l'effet
     * couvre TOUT l'écran du téléphone — la simulation vit en
     * repère ÉCRAN (toute la vue, sans pan) : le centrage du
     * plateau ne le confine ni ne le décale plus. La simulation
     * avance ici, une fois par trame ; peintures et pool
     * pré-alloués : zéro allocation par trame, jamais de lambda
     * dans la boucle.
     */
    private fun Canvas.drawFireworkOverlay(now: Long) {
        val fw = firework ?: return
        if (victoryEffect != VictoryEffect.FIREWORK) return
        fw.step(now)
        victorySparkPaint.style = Paint.Style.STROKE
        victorySparkPaint.strokeWidth = FIREWORK_STROKE_DP * screenDensity
        victorySparkPaint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until fw.rocketCount()) {
            if (!fw.rocketVisible(i, now)) continue
            victorySparkPaint.color = ROCKET_COLOR
            victorySparkPaint.alpha = 230
            drawLine(
                fw.rocketX(i), fw.rocketY(i, now),
                fw.rocketX(i), fw.rocketTrailY(i, now),
                victorySparkPaint
            )
        }
        for (j in 0 until fw.sparkSlots()) {
            if (!fw.isAlive(j)) continue
            victorySparkPaint.color = fw.sparkColor(j)
            victorySparkPaint.alpha = fw.sparkAlpha(j, now)
            drawLine(
                fw.sparkX(j), fw.sparkY(j),
                fw.sparkTrailX(j), fw.sparkTrailY(j),
                victorySparkPaint
            )
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
        // normale (une cellule libre verrouillée reste grise) —
        // et la cellule SCELLÉE d'un indice correctif avant la
        // verrouillée (visuel intérim rose, à statuer avec eric).
        // Une cellule libre DU plateau (générateur ORIGINAL)
        // reçoit le fond empty, exactement comme Cell.doDraw
        // de l'original (Dir.FREE -> Image.EMPTY) : elle se
        // fond dans le décor hors plateau.
        val bg = when {
            dirs == Dir.NO_DIRS -> tileBgEmpty
            b.isSealed(index) -> tileBgSealed
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

        // --- Mise en évidence, par-dessus tout (câbles et équipement).
        // Curseur de focus de l'auto-solveur : cadre rouge translucide,
        // trait épais d'un huitième de la largeur de case, rectangle
        // amputé d'un pixel — exactement le focus de l'original.
        if (index == solverFocus) {
            focusPaint.style = Paint.Style.STROKE
            focusPaint.color = FOCUS_RED
            focusPaint.strokeWidth = cellPx / 8f
            drawRect(left, top, left + cellPx - 1f, top + cellPx - 1f, focusPaint)
        }
        // Indice (chantier Aide à la résolution) : cadre vert pulsé,
        // par-dessus tout, qui vit HINT_PULSE_MS puis s'efface de
        // lui-même — le joueur a le temps de voir, la vue n'a pas
        // d'état à entretenir au-delà de la pulsation.
        if (index == hintIndex) {
            val age = now - hintStartMs
            if (age < HINT_PULSE_MS) {
                val pulse = 0.5f - 0.5f * cos(
                    (2.0 * Math.PI * (age % HINT_PULSE_PERIOD_MS) /
                        HINT_PULSE_PERIOD_MS).toFloat()
                )
                hintPaint.style = Paint.Style.STROKE
                hintPaint.color = hintFrameColor
                hintPaint.alpha = (HINT_ALPHA_MIN +
                    (HINT_ALPHA_MAX - HINT_ALPHA_MIN) * pulse).toInt()
                hintPaint.strokeWidth = cellPx / 8f
                drawRect(left, top, left + cellPx - 1f, top + cellPx - 1f, hintPaint)
            } else {
                hintIndex = -1
            }
        }
        // Blink : bande diagonale contrastée qui balaie la tuile en
        // BLINK_MS — les deux phases de l'original (le trait part du
        // bord bas, achève la diagonale, sort au coin opposé).
        if (index == blinkIndex) {
            val pos = ((now - blinkStartMs).toFloat() / BLINK_MS) * (cellPx * 2f)
            if (pos < cellPx * 2f) {
                blinkPaint.style = Paint.Style.STROKE
                blinkPaint.strokeWidth = BLINK_STROKE_DP * screenDensity
                blinkPaint.color = blinkBandColor
                if (pos < cellPx) {
                    drawLine(left, top + pos, left + pos, top, blinkPaint)
                } else {
                    val hp = pos - cellPx
                    drawLine(left + hp, top + cellPx, left + cellPx, top + hp, blinkPaint)
                }
            } else {
                blinkIndex = -1
            }
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

        // --- Mise en évidence de la tuile manipulée (13 nov. 2026) :
        //     constantes de l'original (Cell.java de Ian Cameron Smith),
        //     reprises telles quelles. ---
        /** Durée du blink : la bande balaie la tuile en 200 ms. */
        private const val BLINK_MS = 200L

        /** Épaisseur du trait du blink : 5 dp, comme l'original. */
        private const val BLINK_STROKE_DP = 5f

        /** Rouge translucide du curseur de focus : 38 pour cent d'opacité. */
        private const val FOCUS_RED = 0x60FF0000

        // --- Effets spéciaux de réussite (15 nov. 2026) ---
        /** Trait des étincelles et fusées du feu d'artifice : 4 dp. */
        private const val FIREWORK_STROKE_DP = 4f

        /** Trace des fusées, plus vive que les étincelles. */
        private const val ROCKET_COLOR = 0xFFF6F0D8.toInt()

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

        // --- Chantier Aide à la résolution (15 nov. 2026) ---

        /** Vie du cadre d'indice : cinq secondes, puis extinction. */
        private const val HINT_PULSE_MS = 5_000L

        /** Période de la pulsation du cadre (alpha min vers max vers min). */
        private const val HINT_PULSE_PERIOD_MS = 500L

        /** Alpha minimal du cadre d'indice pendant la pulsation. */
        private const val HINT_ALPHA_MIN = 90

        /** Alpha maximal du cadre d'indice. */
        private const val HINT_ALPHA_MAX = 220

        /** Durée du pan animé du suivi de caméra (indice, solveur, démo). */
        private const val PAN_FOLLOW_MS = 300L
    }
}
