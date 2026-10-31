package net.chardons.netscramble2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.Skill


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
    val solverUsed: Boolean = false
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
                solverUsed == other.solverUsed
    }

    override fun hashCode(): Int {
        var result = skill.hashCode()
        result = 31 * result + board.hashCode()
        result = 31 * result + (connected?.contentHashCode() ?: 0)
        result = 31 * result + clicks
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + solved.hashCode()
        result = 31 * result + solverUsed.hashCode()
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
 *  - records par niveau de difficulté (meilleurs clics / meilleur temps) ;
 *  - auto-solveur animé, annulation du score si utilisé.
 */
class GameViewModel : ViewModel() {

    private var board: Board = Board.generate(Skill.NORMAL, newSeed())
    private var prevClicked = -1
    private var clicks = 0
    private var elapsedMs = 0L
    private var solverUsed = false

    private val _uiState = MutableStateFlow(refresh())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    init {
        // Horloge de jeu : ne publie que toutes les 250 ms (comme l'original),
        // en tirant parti de la coroutine (annulable automatiquement).
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (!solvedNow) {
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
        publish()
    }

    /**
     * Le joueur a touché une cellule : rotation horaire d'un quart de tour.
     * Comme dans l'original, les clics répétés sur la même cellule ne
     * comptent qu'une fois (l'interface tactile ne tourne qu'horaire).
     */
    fun onCellTapped(index: Int) {
        if (solvedNow || board.locked[index]) return
        board.rotateCell(index, 1)
        if (index != prevClicked) {
            ++clicks
            prevClicked = index
        }
        publish()
    }

    private val solvedNow: Boolean
        get() = _uiState.value.solved

    private fun newSeed(): Long = Random.nextLong()

    private fun publish() {
        _uiState.value = refresh()
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
            solverUsed = solverUsed
        )
    }

    private fun Board.skillOf(): Skill = Skill.entries.first {
        it.width == width && it.height == height && it.wrap == wrap && it.blind == blind
    }

    companion object {
        private val TICK_MS = 250.milliseconds
    }
}
