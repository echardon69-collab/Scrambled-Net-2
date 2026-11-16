package net.chardons.netscramble2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.CameraSpec
import net.chardons.netscramble2.game.GameSpec
import net.chardons.netscramble2.game.Generator
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.game.Sound


/**
 * Stratégie de l'indice (réglage du chantier Aide à la résolution) :
 * SIGNAL signale la cellule fautive (cadre vert pulsé, informatif),
 * FIX la corrige (rotation à l'aplomb, révélation si cachée, puis
 * scellée).
 */
enum class HintStrategy { SIGNAL, FIX }


/**
 * Victoire signalée une fois à l'UI : l'activité enregistre les records
 * et montre le dialogue de victoire — pas si le solveur a servi, comme
 * dans l'original (qui ne « se vante » pas non plus dans ce cas).
 * L'indice, lui, ne grille PAS le record : chaque record porte son
 * nombre d'indices consommés.
 */
data class WinEvent(
    val skill: Skill,
    val moves: Int,
    val seconds: Int,
    val solverUsed: Boolean,
    val ntiles: Int,
    /** Indices consommés dans la partie gagnée. */
    val hints: Int = 0
)


/**
 * État observable du jeu, publié à l'UI via un StateFlow.
 */
data class GameUiState(
    val skill: Skill = Skill.NORMAL,
    val board: Board? = null,
    /** Cellules actuellement connectées au serveur (pour le rendu). */
    val connected: BooleanArray? = null,
    val moves: Int = 0,
    val elapsedMs: Long = 0,
    val solved: Boolean = false,
    /** L'auto-solveur a-t-il été utilisé ? (les scores ne comptent plus). */
    val solverUsed: Boolean = false,
    /** L'auto-solveur travaille-t-il en ce moment ? */
    val solving: Boolean = false,
    /**
     * Tuile du coup EN COURS de l'auto-solveur (index), -1 si aucun :
     * le curseur rouge de la vue suit les coups programmés, un coup par
     * tick, comme le focus de l'original. Transitoire : jamais sérialisé
     * dans GameSpec, remis à -1 à la fin ou à l'arrêt du solveur.
     */
    val solverFocus: Int = -1,
    /**
     * Indices restants au plafond de la partie (badge du menu) :
     * plafond moins consommés, jamais négatif.
     */
    val hintRemaining: Int = 0,
    /**
     * Cellule visée par le dernier indice (index), -1 si aucune :
     * cadre vert pulsé de la vue. Transitoire : jamais sérialisé
     * dans GameSpec, expire visuellement de lui-même.
     */
    val hintIndex: Int = -1,
    /**
     * Génération de la mise en évidence d'indice : incrémenté à
     * CHAQUE demande (nouvelle cible ou répétition) et aux
     * changements de partie. C'est lui qui (re)arme la pulsation
     * de la vue — jamais les publications du chrono (retour UAT du
     * 15 nov. 2026 : la pulsation repartait à chaque tick et un
     * nouvel appui restait invisible). Transitoire, jamais sérialisé.
     */
    val hintRequestId: Int = 0
) {
    // Tableau dans une data class : on compare les contenus, pas les références.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameUiState) return false
        return skill == other.skill &&
                board == other.board &&
                connected.contentEquals(other.connected) &&
                moves == other.moves &&
                elapsedMs == other.elapsedMs &&
                solved == other.solved &&
                solverUsed == other.solverUsed &&
                solving == other.solving &&
                solverFocus == other.solverFocus &&
                hintRemaining == other.hintRemaining &&
                hintIndex == other.hintIndex &&
                hintRequestId == other.hintRequestId
    }

    override fun hashCode(): Int {
        var result = skill.hashCode()
        result = 31 * result + board.hashCode()
        result = 31 * result + (connected?.contentHashCode() ?: 0)
        result = 31 * result + moves
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + solved.hashCode()
        result = 31 * result + solverUsed.hashCode()
        result = 31 * result + solving.hashCode()
        result = 31 * result + solverFocus
        result = 31 * result + hintRemaining
        result = 31 * result + hintIndex
        result = 31 * result + hintRequestId
        return result
    }
}

/**
 * Détient l'état du jeu et survit aux rotations de l'écran et à la
 * recréation de l'activité (contrairement à l'ancien code, qui portait tout
 * dans l'Activity et sauvegardait à la main dans des Bundles). Depuis M4,
 * la partie survit aussi à la mort du processus : l'activité photographie
 * la partie à chaque onStop ([currentSpec]) et la restaure au lancement
 * ([restoreIfPending]) ; le chrono et le solveur gèlent en arrière-plan
 * ([setClockPaused]).
 */
class GameViewModel : ViewModel() {

    private var seed: Long = newSeed()

    /**
     * Niveau de la partie en cours. Depuis le chantier tailles, le
     * niveau ne se déduit plus du plateau (les dimensions viennent de
     * l'écran) : il est porté ici, et voyage dans la photographie.
     */
    private var skill: Skill = Skill.NORMAL

    /**
     * Plateau d'attente, aussitôt remplacé : l'activité restaure la
     * partie sauvegardée ou en lance une neuve aux dimensions de
     * l'écran, dans tous les cas dès l'onCreate.
     */
    private var board: Board =
        Board.generate(Skill.NORMAL, 7, 7, Generator.TREE, seed)
    private var prevClicked = -1
    private var moves = 0
    private var elapsedMs = 0L
    private var solverUsed = false

    /**
     * Indices consommés dans la partie (chantier Aide à la
     * résolution) et plafond autorisé : sentinelle -1 = partie née
     * avant le chantier, recalculée à la restauration.
     */
    private var hintsUsed = 0
    private var hintsAllowed = -1

    /** Cellule visée par le dernier indice (transitoire, jamais sérialisé). */
    private var hintFocusIndex = -1

    /**
     * Génération de la mise en évidence d'indice : incrémenté à chaque
     * demande et aux changements de partie, publié comme
     * [GameUiState.hintRequestId] — l'activité (re)arme la pulsation
     * de la vue sur son changement, lui seul.
     */
    private var hintPulseId = 0

    /** File des coups restant à jouer par l'auto-solveur (null = inactif). */
    private var solveQueue: ArrayDeque<Int>? = null

    /**
     * Tuile du dernier coup JOUÉ par l'auto-solveur (déverrouillage ou
     * rotation) : le curseur rouge de la vue reste dessus pendant
     * l'animation du coup, puis saute au suivant. Transitoire.
     */
    private var solverFocusIndex = -1

    /** Jeu gelé quand l'app n'est plus visible (chrono et solveur). */
    private var clockPaused = false

    /** Une restauration de partie a-t-elle déjà eu lieu (par instance) ? */
    private var restored = false

    /**
     * Victoires à usage de l'UI (dialogue + records), une par partie.
     */
    private val _winEvents = MutableSharedFlow<WinEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val winEvents: SharedFlow<WinEvent> = _winEvents.asSharedFlow()

    /**
     * Événements sonores, repris de l'original : rotation, refus,
     * verrou, changement de connexité, victoire, nouvelle partie.
     * L'UI les collecte de façon lifecycle-aware et les joue selon le
     * mode choisi par le joueur (voir SoundEffects).
     */
    private val _soundEvents = MutableSharedFlow<Sound>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val soundEvents: SharedFlow<Sound> = _soundEvents.asSharedFlow()

    /**
     * Nombre de cellules connectées à la dernière publication.
     * -1 = plateau neuf (aucun son de connexion à la première publication).
     */
    private var lastConnectedCount = -1

    private val _uiState = MutableStateFlow(refresh())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    init {
        // Horloge de jeu : ne publie que toutes les 250 ms (comme l'original),
        // en tirant parti de la coroutine (annulable automatiquement).
        // L'auto-solveur, quand il est actif, y joue aussi un coup par tick :
        // on voit le réseau se construire, comme dans l'original.
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                // En arrière-plan (ou une fois résolu), tout gèle : le chrono
                // et le solveur n'avancent que pendant que l'app est visible,
                // comme l'état PAUSED de l'original.
                if (!clockPaused && (!solvedNow || solveQueue != null)) {
                    // Une fois résolu, le chrono gèle, mais le solveur
                    // achève SA file : le coup qui résout le plateau
                    // laisse la file vide non close — sans ce passage,
                    // le curseur rouge resterait sur la dernière tuile
                    // pour toujours (retour UAT du 13 nov.).
                    val wasSolved = solvedNow
                    playOneSolveMoveIfNeeded()
                    if (!wasSolved) {
                        elapsedMs += TICK_MS.inWholeMilliseconds
                    }
                    publish()
                }
            }
        }
    }

    /**
     * Nouvelle partie au niveau [skill], sur un plateau de [width] x
     * [height] — dimensions choisies par l'activité d'après l'écran
     * (Screen.find, comme findMatrix de l'original) et figées pour
     * toute la partie, sauf pour la famille Infini (Endless, Abîme,
     * Néant) dont la taille est fixe (40 x 40). Le générateur du
     * réseau est tiré à pile ou face depuis la graine (donc
     * reproductible à la restauration) : notre arbre couvrant, ou la
     * croissance par branchements de l'original — sauf pour la
     * famille Infini, qui FORCE le générateur ORIGINAL : victoire
     * par terminaux comme à l'origine, et la signature « électricité »
     * du serveur (scintillement) devient la carte d'identité constante
     * de la famille.
     */
    fun newGame(skill: Skill, width: Int, height: Int, hintPercent: Int = 10) {
        seed = newSeed()
        this.skill = skill
        // Famille Infini (Endless, Abîme, Néant) : générateur ORIGINAL
        // forcé — victoire par terminaux, scintillement constant du
        // serveur (signature de la famille) ; le tirage à la graine
        // reste intact pour les cinq autres niveaux.
        val generator = if (skill.endless) {
            Generator.ORIGINAL
        } else if ((seed and 1L) == 0L) {
            Generator.TREE
        } else {
            Generator.ORIGINAL
        }
        board = Board.generate(skill, width, height, generator, seed)
        prevClicked = -1
        moves = 0
        elapsedMs = 0
        solverUsed = false
        solveQueue = null
        solverFocusIndex = -1 // partie neuve : aucun curseur de solveur
        // Plafond d'indices de la partie (chantier Aide à la
        // résolution) : pourcentage du nombre de tuiles mal orientées
        // au mélange initial. Zéro (réglage « Désactivé ») éteint
        // l'indice pour toute la partie.
        hintsUsed = 0
        hintsAllowed = Board.hintAllowance(misplacedTiles(board), hintPercent)
        hintFocusIndex = -1
        ++hintPulseId
        // Plateau neuf : pas de son de connexion, juste le son de départ.
        lastConnectedCount = -1
        emit(Sound.START)
        publish()
    }

    /**
     * Le joueur a touché une cellule : rotation horaire d'un quart de tour.
     * Comme dans l'original, les coups répétés sur la même cellule ne
     * comptent qu'une fois (l'interface tactile ne tourne qu'horaire), et
     * une cellule verrouillée ne tourne pas — elle répond d'un clic sec.
     */
    fun onCellTapped(index: Int) {
        if (solvedNow || board.locked[index] || board.isEmpty(index)) {
            // Cellule verrouillée ou libre du générateur ORIGINAL (aucun
            // câble à tourner) : clic sec, comme une cellule verrouillée.
            emit(Sound.CLICK)
            return
        }
        board.rotateCell(index, 1, byPlayer = true)
        if (index != prevClicked) {
            ++moves
            prevClicked = index
        }
        emit(Sound.TURN)
        publish()
    }

    /**
     * Appui long : verrouille / déverrouille la cellule, comme dans
     * l'original — un repère visuel pour les tuiles dont le joueur est sûr.
     * Une cellule scellée par un indice correctif ne bouge plus :
     * refus silencieux, sans le son de verrou.
     */
    fun onCellLongPressed(index: Int) {
        // Rien à verrouiller sur une cellule libre (générateur ORIGINAL).
        if (solvedNow || board.isEmpty(index)) return
        if (!board.toggleLock(index)) return
        emit(Sound.POP)
        publish()
    }

    /**
     * Indice à la demande du joueur (bouton « Indice » du menu) :
     * plafonné au quota de la partie, refusé silencieusement une fois
     * le plateau résolu ou pendant le passage du solveur. La cible
     * suit les priorités validées ([Board.hintTarget]).
     */
    fun onHint(strategy: HintStrategy) {
        if (solvedNow || solveQueue != null) return
        if (hintsAllowed <= 0 || hintsUsed >= hintsAllowed) return
        val index = board.hintTarget()
        if (index < 0) return
        if (strategy == HintStrategy.FIX) {
            val steps = (4 - board.rotations[index]) % 4
            if (steps != 0) {
                board.rotateCell(index, steps)
                moves += hintMoveCost(steps)
            }
            if (board.isHidden(index)) board.reveal(index)
            board.seal(index)
        }
        ++hintsUsed
        hintFocusIndex = index
        ++hintPulseId
        publish()
    }

    /**
     * Coût en coups d'une correction d'indice : autant de coups que
     * quarts de tour joués. PROVISOIRE — comptage à statuer avec
     * l'utilisateur (dernière étape du chantier).
     */
    private fun hintMoveCost(steps: Int): Int = steps

    /**
     * Tuiles mal orientées d'un plateau, au sens FONCTIONNEL de
     * Board.isMisplaced (une tuile symétrique bien branchée ne
     * compte pas) — c'est la mesure du plafond d'indices.
     */
    private fun misplacedTiles(b: Board): Int =
        (0 until b.size).count { b.isMisplaced(it) }

    /**
     * Auto-solveur, comme dans l'original : il joue un coup par tick (la
     * progression est visible), un second appel l'arrête. Toute partie où
     * il a servi ne compte pas au score. Une cellule verrouillée sur son
     * passage est d'abord déverrouillée (c'est son coup du tour), puis
     * tournée au coup suivant. Décision d'eric du 16 nov. 2026 : il ne
     * RÉVÈLE PAS les cellules cachées qu'il règle — une tuile masquée
     * (Dément, Abîme, Néant) reste masquée, seule la victoire dévoile
     * le plateau (l'indice correctif, lui, révèle toujours : c'est un
     * coup montré, pas joué).
     */
    fun toggleSolver() {
        val queue = solveQueue
        if (queue != null) {
            solveQueue = null
            solverFocusIndex = -1 // arrêt manuel : le curseur disparaît
            publish()
            return
        }
        if (solvedNow) return
        solverUsed = true
        solveQueue = ArrayDeque(board.solveMoves())
        publish()
    }

    /**
     * Joue au plus un coup du solveur : soit déverrouiller une cellule
     * verrouillée, soit l'amener à son orientation de solution (rotation
     * recalculée au dernier moment : robuste même si le joueur a touché
     * la cellule entre-temps). Les cellules déjà en place sont sautées.
     * Pas de révélation ici (décision d'eric du 16 nov. 2026) : le
     * masque d'une cellule cachée ne lève qu'à la victoire.
     */
    private fun playOneSolveMoveIfNeeded() {
        val queue = solveQueue ?: return
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val steps = (4 - board.rotations[index]) % 4
            when {
                board.isSealed(index) -> {
                    // Cellule scellée par un indice correctif : déjà en
                    // place et verrouillée pour de bon — au suivant.
                }
                board.locked[index] -> {
                    board.toggleLock(index)
                    // Déverrouillée, mais pas encore tournée : la cellule
                    // repasse EN TÊTE de la file (chaque cellule n'y figure
                    // qu'une fois — sans ça, le solveur s'arrêterait avant
                    // la fin sur un plateau qui compte des verrous).
                    queue.addFirst(index)
                    solverFocusIndex = index
                    return
                }
                steps != 0 -> {
                    board.rotateCell(index, steps)
                    solverFocusIndex = index
                    return
                }
                // Déjà en place : le joueur l'a tournée lui-même, au suivant.
            }
        }
        solveQueue = null // file épuisée : le plateau est résolu
        solverFocusIndex = -1 // le curseur rouge disparaît avec le solveur
    }

    /**
     * Gèle ou libère le jeu quand l'app quitte ou revient au premier
     * plan (appelé par l'activité dans onStart et onStop). Parité avec
     * l'état PAUSED de l'original, qui arrêtait le chrono.
     */
    fun setClockPaused(paused: Boolean) {
        clockPaused = paused
    }

    /**
     * Photographie sérialisable de la partie en cours (persistance).
     * La caméra vient de la VUE (la seule à connaître pan et zoom) :
     * l'activité la fournit à l'enregistrement, en onStop.
     */
    fun currentSpec(camera: CameraSpec? = null): GameSpec = GameSpec(
        skill = skill,
        seed = seed,
        width = board.width,
        height = board.height,
        generator = board.generator,
        root = board.forcedRoot,
        moves = moves,
        elapsedMs = elapsedMs,
        solverUsed = solverUsed,
        prevClicked = prevClicked,
        solveQueue = solveQueue?.toList() ?: emptyList(),
        rotations = board.rotations.copyOf(),
        locked = board.locked.copyOf(),
        revealed = board.revealed.copyOf(),
        sealedCells = board.sealedCells.copyOf(),
        touched = board.touched.copyOf(),
        hintsUsed = hintsUsed,
        hintsAllowed = hintsAllowed,
        camera = camera
    )

    /**
     * Restaure une partie sauvegardée sur disque. Un seul effet par
     * instance de ViewModel : les rotations d'écran réutilisent le même
     * ViewModel, forcément plus à jour que la photographie du dernier
     * onStop — seules les restaurations après mort du processus
     * s'appliquent. La restauration est silencieuse : ni son, ni
     * événement de victoire, ni compteur de connexité à comparer.
     */
    fun restoreIfPending(spec: GameSpec?, hintPercent: Int = 10): Boolean {
        if (restored) return true
        restored = true
        if (spec == null) return false
        seed = spec.seed
        skill = spec.skill
        board = spec.buildBoard()
        prevClicked = spec.prevClicked
        moves = spec.moves
        elapsedMs = spec.elapsedMs
        solverUsed = spec.solverUsed
        hintsUsed = spec.hintsUsed
        hintsAllowed = spec.hintsAllowed
        if (hintsAllowed < 0) {
            // Partie née avant le chantier (v4 ou moins) : le plafond
            // est recalculé sur le mélange initial du plateau, en le
            // régénérant proprement depuis la graine — pas sur l'état
            // courant, qui dépend des coups déjà joués.
            val pristine = Board.generate(
                spec.skill, spec.width, spec.height, spec.generator, spec.seed,
                forcedRoot = spec.root
            )
            hintsAllowed = Board.hintAllowance(misplacedTiles(pristine), hintPercent)
        }
        solveQueue = spec.solveQueue.takeIf { it.isNotEmpty() }
            ?.let { ArrayDeque(it) }
        // Le curseur repart de rien : le premier tick de la file
        // restaurée posera la tuile de son coup (les coups déjà joués
        // ne se rejouent pas, le curseur n'a pas d'historique).
        solverFocusIndex = -1
        hintFocusIndex = -1
        ++hintPulseId
        lastConnectedCount = board.connectedCells().count { it }
        _uiState.value = refresh()
        return true
    }

    private val solvedNow: Boolean
        get() = _uiState.value.solved

    private fun newSeed(): Long = Random.nextLong()

    /** Envoie un événement sonore à l'UI (jamais bloquant). */
    private fun emit(sound: Sound) {
        _soundEvents.tryEmit(sound)
    }

    private fun publish() {
        val state = refresh()
        // Sons de connexion / victoire, comme dans l'original : CONNECT à
        // chaque variation de connexité (dans les deux sens), WIN quand le
        // réseau vient d'être résolu (après CONNECT).
        val count = state.connected?.count { it } ?: 0
        val previousCount = lastConnectedCount
        lastConnectedCount = count
        if (previousCount >= 0) {
            if (count != previousCount) emit(Sound.CONNECT)
            if (!_uiState.value.solved && state.solved) {
                emit(Sound.WIN)
                // Une seule victoire par partie : l'UI la transforme en
                // dialogue et, si le solveur n'a pas servi, en records.
                _winEvents.tryEmit(
                    WinEvent(
                        skill = state.skill,
                        moves = moves,
                        seconds = (elapsedMs / 1000L).toInt(),
                        solverUsed = solverUsed,
                        ntiles = board.size,
                        hints = hintsUsed
                    )
                )
            }
        }
        _uiState.value = state
    }

    private fun refresh(): GameUiState {
        val connected = board.connectedCells()
        return GameUiState(
            skill = skill,
            board = board,
            connected = connected,
            moves = moves,
            elapsedMs = elapsedMs,
            solved = board.isSolved(),
            solverUsed = solverUsed,
            solving = solveQueue != null,
            solverFocus = solverFocusIndex,
            hintRemaining = (hintsAllowed - hintsUsed).coerceAtLeast(0),
            hintIndex = hintFocusIndex,
            hintRequestId = hintPulseId
        )
    }

    companion object {
        private val TICK_MS = 250.milliseconds
    }
}
