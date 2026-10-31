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
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.game.Sound


/**
 * État observable du jeu, publié à l'UI via un StateFlow.
 */
data class GameUiState(
    val skill: Skill = Skill.NORMAL,
    val board: Board? = null,
    /** Cellules actuellement connectées au serveur (pour le rendu). */
    val connected: BooleanArray? = null,
    val clicks: Int = 0,
    val elapsedMs: Long = 0,
    val solved: Boolean = false,
    /** L'auto-solveur a-t-il été utilisé ? (les scores ne comptent plus). */
    val solverUsed: Boolean = false,
    /** L'auto-solveur travaille-t-il en ce moment ? */
    val solving: Boolean = false
) {
    // Tableau dans une data class : on compare les contenus, pas les références.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameUiState) return false
        return skill == other.skill &&
                board == other.board &&
                connected.contentEquals(other.connected) &&
                clicks == other.clicks &&
                elapsedMs == other.elapsedMs &&
                solved == other.solved &&
                solverUsed == other.solverUsed &&
                solving == other.solving
    }

    override fun hashCode(): Int {
        var result = skill.hashCode()
        result = 31 * result + board.hashCode()
        result = 31 * result + (connected?.contentHashCode() ?: 0)
        result = 31 * result + clicks
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + solved.hashCode()
        result = 31 * result + solverUsed.hashCode()
        result = 31 * result + solving.hashCode()
        return result
    }
}

/**
 * Détient l'état du jeu et survit aux rotations de l'écran et à la
 * recréation de l'activité (contrairement à l'ancien code, qui portait tout
 * dans l'Activity et sauvegardait à la main dans des Bundles).
 *
 * TODO (roadmap) :
 *  - sauvegarde persistante de la partie (fichier) pour survivre à un kill
 *    du processus par le système ;
 *  - pause du chrono quand l'app passe en arrière-plan ;
 *  - records par niveau de difficulté (meilleurs clics / meilleur temps).
 */
class GameViewModel : ViewModel() {

    private var board: Board = Board.generate(Skill.NORMAL, newSeed())
    private var prevClicked = -1
    private var clicks = 0
    private var elapsedMs = 0L
    private var solverUsed = false

    /** File des coups restant à jouer par l'auto-solveur (null = inactif). */
    private var solveQueue: ArrayDeque<Int>? = null

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
                if (!solvedNow) {
                    playOneSolveMoveIfNeeded()
                    elapsedMs += TICK_MS.inWholeMilliseconds
                    publish()
                }
            }
        }
    }

    fun newGame(skill: Skill) {
        board = Board.generate(skill, newSeed())
        prevClicked = -1
        clicks = 0
        elapsedMs = 0
        solverUsed = false
        solveQueue = null
        // Plateau neuf : pas de son de connexion, juste le son de départ.
        lastConnectedCount = -1
        emit(Sound.START)
        publish()
    }

    /**
     * Le joueur a touché une cellule : rotation horaire d'un quart de tour.
     * Comme dans l'original, les clics répétés sur la même cellule ne
     * comptent qu'une fois (l'interface tactile ne tourne qu'horaire), et
     * une cellule verrouillée ne tourne pas — elle répond d'un clic sec.
     */
    fun onCellTapped(index: Int) {
        if (solvedNow || board.locked[index]) {
            emit(Sound.CLICK)
            return
        }
        board.rotateCell(index, 1)
        if (index != prevClicked) {
            ++clicks
            prevClicked = index
        }
        emit(Sound.TURN)
        publish()
    }

    /**
     * Appui long : verrouille / déverrouille la cellule, comme dans
     * l'original — un repère visuel pour les tuiles dont le joueur est sûr.
     */
    fun onCellLongPressed(index: Int) {
        if (solvedNow) return
        board.toggleLock(index)
        emit(Sound.POP)
        publish()
    }

    /**
     * Auto-solveur, comme dans l'original : il joue un coup par tick (la
     * progression est visible), un second appel l'arrête. Toute partie où
     * il a servi ne compte pas au score. Une cellule verrouillée sur son
     * passage est d'abord déverrouillée (c'est son coup du tour), puis
     * tournée au coup suivant ; les cellules cachées qu'il règle sont
     * révélées.
     */
    fun toggleSolver() {
        val queue = solveQueue
        if (queue != null) {
            solveQueue = null
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
     */
    private fun playOneSolveMoveIfNeeded() {
        val queue = solveQueue ?: return
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val steps = (4 - board.rotations[index]) % 4
            when {
                board.locked[index] -> {
                    board.toggleLock(index)
                    return
                }
                steps != 0 -> {
                    board.rotateCell(index, steps)
                    board.reveal(index)
                    return
                }
                // Déjà en place : le joueur l'a tournée lui-même, au suivant.
            }
        }
        solveQueue = null // file épuisée : le plateau est résolu
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
            if (!_uiState.value.solved && state.solved) emit(Sound.WIN)
        }
        _uiState.value = state
    }

    private fun refresh(): GameUiState {
        val connected = board.connectedCells()
        val solved = connected.all { it }
        return GameUiState(
            skill = board.skillOf(),
            board = board,
            connected = connected,
            clicks = clicks,
            elapsedMs = elapsedMs,
            solved = solved,
            solverUsed = solverUsed,
            solving = solveQueue != null
        )
    }

    private fun Board.skillOf(): Skill = Skill.entries.first {
        it.width == width && it.height == height && it.wrap == wrap &&
            it.hiddenThreshold == hiddenThreshold
    }

    companion object {
        private val TICK_MS = 250.milliseconds
    }
}
