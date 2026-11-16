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
import net.chardons.netscramble2.game.Generator
import net.chardons.netscramble2.game.Skill

/**
 * État observable de la démonstration, publié à l'UI via un
 * StateFlow : plateau, connexité, résolution (le rendu clignote la
 * dernière tuile à la victoire) et tuile du coup en cours du
 * solveur (curseur rouge).
 */
data class DemoUiState(
    val board: Board? = null,
    val connected: BooleanArray? = null,
    val solved: Boolean = false,
    /** Tuile du coup EN COURS du solveur (index), -1 si aucun. */
    val focus: Int = -1
) {
    // Tableau dans une data class : on compare les contenus, pas les
    // références.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DemoUiState) return false
        return board == other.board &&
                connected.contentEquals(other.connected) &&
                solved == other.solved &&
                focus == other.focus
    }

    override fun hashCode(): Int {
        var result = board.hashCode()
        result = 31 * result + (connected?.contentHashCode() ?: 0)
        result = 31 * result + solved.hashCode()
        result = 31 * result + focus
        return result
    }
}

/**
 * Modèle de la DÉMONSTRATION (chantier Aide à la résolution, 15 nov.
 * 2026) : une boucle autonome qui mélange un plateau puis le
 * résout, coup par coup, exactement comme le solveur du jeu — la
 * victoire clignote (le rendu s'en charge), une pause, le plateau
 * suivant. Le rythme est réglable ([DemoSpeed]).
 *
 * Écarts assumés avec le jeu :
 *  - AUCUN son (écart à statuer avec eric) ;
 *  - le coup du solveur est dupliqué (~40 lignes du jeu) plutôt que
 *    de partager un moteur : la démo ne doit JAMAIS retoucher l'état
 *    d'une partie réelle, un modèle séparé n'a rien à synchroniser ;
 *  - le générateur suit les mêmes règles que le jeu (famille Infini
 *    : ORIGINAL forcé ; sinon tirage à la graine).
 */
class DemoViewModel : ViewModel() {

    private var board: Board? = null

    /** Difficulté choisie (réglage de la page « Réglages »). */
    private var difficulty = DemoDifficulty.PROGRESSIVE

    /** Niveau de départ (FIXED et PROGRESSIVE). */
    private var startSkill = Skill.NOVICE

    /**
     * Dimensions d'un niveau, fournies par l'activité : la
     * démonstration ignore l'écran autant que possible, c'est la
     * même matrice Screen que le jeu.
     */
    private var sizeFor: (Skill) -> Pair<Int, Int> = { _ -> 7 to 7 }

    /** Curseur du cycle progressif (ordinal du prochain niveau). */
    private var progressiveOrdinal = 0

    /** Délai entre deux coups du solveur (réglage du rythme). */
    private var tickMs = DemoSpeed.NORMAL.delayMs

    /** Départ de la pause de célébration, 0 si pas en célébration. */
    private var celebrateStartMs = 0L

    /** Effet d'intermède autorisé : le réglage Animation en décide. */
    private var effectsEnabled = true

    /** Démonstration gelée quand l'app n'est plus visible. */
    private var paused = false

    /** File des coups restant à jouer (null = inactif). */
    private var solveQueue: ArrayDeque<Int>? = null

    /** Tuile du dernier coup joué (curseur rouge). */
    private var focusIndex = -1

    private val _uiState = MutableStateFlow(DemoUiState())
    val uiState: StateFlow<DemoUiState> = _uiState.asStateFlow()

    /**
     * Lance la boucle : un plateau premier, puis un coup de solveur
     * par tick jusqu'à la victoire, une pause de célébration, le
     * plateau suivant — indéfiniment, tant que l'activité vit.
     */
    fun start(
        difficulty: DemoDifficulty,
        startSkill: Skill,
        speed: DemoSpeed,
        sizes: (Skill) -> Pair<Int, Int>
    ) {
        this.difficulty = difficulty
        this.startSkill = startSkill
        this.sizeFor = sizes
        this.tickMs = speed.delayMs
        this.progressiveOrdinal = startSkill.ordinal
        nextBoard()
        paused = false
        viewModelScope.launch {
            while (isActive) {
                delay(tickMs.milliseconds)
                if (!paused) step()
            }
        }
    }

    /** Gèle la boucle quand l'activité quitte l'écran, la libère au retour. */
    fun setPaused(p: Boolean) {
        paused = p
    }

    /** L'activité annonce le réglage Animation : avec ou sans intermède. */
    fun setEffectsEnabled(enabled: Boolean) {
        effectsEnabled = enabled
    }

    /**
     * Un pas de la boucle : la célébration attend sa fin puis passe
     * au plateau suivant ; sinon UN coup de solveur — déverrouiller
     * puis tourner, comme le jeu, les cellules déjà en place sautées.
     * File vide : plateau résolu, la célébration commence.
     */
    private fun step() {
        val b = board ?: return
        if (celebrateStartMs != 0L) {
            // Intermède (chantier « Effets spéciaux de réussite ») :
            // la pause vaut l'effet tiré au sort — 4,5 s — quand les
            // animations vivent ; repli sur la pause seule sinon.
            val pauseMs = if (effectsEnabled) VictoryEffects.EFFECT_MS else WIN_PAUSE_MS
            if (System.currentTimeMillis() - celebrateStartMs >= pauseMs) {
                celebrateStartMs = 0L
                nextBoard()
            }
            return
        }
        val queue = solveQueue ?: return
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            val steps = (4 - b.rotations[index]) % 4
            when {
                b.locked[index] -> {
                    b.toggleLock(index)
                    queue.addFirst(index)
                    focusIndex = index
                    publish()
                    return
                }
                steps != 0 -> {
                    b.rotateCell(index, steps)
                    b.reveal(index)
                    focusIndex = index
                    publish()
                    return
                }
                // Déjà en place : au suivant.
            }
        }
        solveQueue = null
        focusIndex = -1
        celebrateStartMs = System.currentTimeMillis()
        publish()
    }

    /**
     * Plateau suivant selon la difficulté : FIXED garde le niveau
     * choisi ; RANDOM en tire un ; PROGRESSIVE démarre au niveau
     * choisi, monte d'un cran et boucle sur les huit niveaux — la
     * famille Infini comprise. Générateur et dimensions comme le
     * jeu (famille Infini : ORIGINAL forcé).
     */
    private fun nextBoard() {
        val skill = when (difficulty) {
            DemoDifficulty.FIXED -> startSkill
            DemoDifficulty.RANDOM -> Skill.entries[Random.nextInt(Skill.entries.size)]
            DemoDifficulty.PROGRESSIVE -> {
                val s = Skill.entries[progressiveOrdinal % Skill.entries.size]
                progressiveOrdinal += 1
                s
            }
        }
        val (width, height) = sizeFor(skill)
        val seed = Random.nextLong()
        val generator = if (skill.endless) {
            Generator.ORIGINAL
        } else if ((seed and 1L) == 0L) {
            Generator.TREE
        } else {
            Generator.ORIGINAL
        }
        val b = Board.generate(skill, width, height, generator, seed)
        board = b
        solveQueue = ArrayDeque(b.solveMoves())
        focusIndex = -1
        if (b.isSolved()) {
            // Graine exceptionnelle : plateau déjà en solution, la
            // célébration enchaîne directement.
            celebrateStartMs = System.currentTimeMillis()
        }
        publish()
    }

    private fun publish() {
        val b = board ?: return
        _uiState.value = DemoUiState(
            board = b,
            connected = b.connectedCells(),
            solved = b.isSolved(),
            focus = focusIndex
        )
    }

    companion object {
        /** Pause de célébration après chaque victoire, en millisecondes. */
        private const val WIN_PAUSE_MS = 1_500L
    }
}
