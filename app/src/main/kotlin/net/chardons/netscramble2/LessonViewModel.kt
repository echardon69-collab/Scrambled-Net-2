package net.chardons.netscramble2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import net.chardons.netscramble2.game.Board

/**
 * État observable d'une leçon, publié à l'UI via un StateFlow :
 * plateau vivant, connexité, résolution, bulle courante
 * ([bubbleId] vers R.string, [kind] pilote les boutons), tuile du
 * coup en cours du solveur, cible d'indice et sa génération de
 * pulsation (patron de [GameUiState]), coche quand la leçon est
 * terminée.
 */
data class LessonUiState(
    val board: Board? = null,
    val connected: BooleanArray? = null,
    val solved: Boolean = false,
    val focus: Int = -1,
    val bubbleId: String? = null,
    val kind: ExpectKind? = null,
    val hintIndex: Int = -1,
    val hintRequestId: Int = 0,
    val lessonDone: Boolean = false,
    val demoKind: DemoKind = DemoKind.NONE,
    val demoIndex: Int = -1,
    val demoRequestId: Int = 0
) {
    // Tableau dans une data class : on compare les contenus, pas les
    // références.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LessonUiState) return false
        return board == other.board &&
                connected.contentEquals(other.connected) &&
                solved == other.solved &&
                focus == other.focus &&
                bubbleId == other.bubbleId &&
                kind == other.kind &&
                hintIndex == other.hintIndex &&
                hintRequestId == other.hintRequestId &&
                lessonDone == other.lessonDone &&
                demoKind == other.demoKind &&
                demoIndex == other.demoIndex &&
                demoRequestId == other.demoRequestId
    }

    override fun hashCode(): Int {
        var result = board.hashCode()
        result = 31 * result + (connected?.contentHashCode() ?: 0)
        result = 31 * result + solved.hashCode()
        result = 31 * result + focus
        // hashCode() de la nullable-safe Any? (0 si null) : pas de
        // double sécurité ?: 0 (correctif v2, remarque lint).
        result = 31 * result + bubbleId.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + hintIndex
        result = 31 * result + hintRequestId
        result = 31 * result + lessonDone.hashCode()
        result = 31 * result + demoKind.hashCode()
        result = 31 * result + demoIndex
        result = 31 * result + demoRequestId
        return result
    }
}

/**
 * Modèle d'une LEÇON du mode Apprentissage (chantier du 17 nov.
 * 2026) : un plateau reproductible ([Lessons]), une machine
 * d'étapes ([LessonRunner]) et UNE action attendue à la fois. Le
 * modèle traduit les gestes du joueur en [LessonEvent] ; quand
 * l'étape est satisfaite la bulle suivante arrive.
 *
 * Écarts assumés avec le jeu (la leçon n'est PAS une partie) :
 *  - AUCUN son, comme la démonstration ;
 *  - le bouton Résoudre de la leçon 3 joue le MÊME solveur que le
 *    jeu (un coup par tick, curseur rouge) mais ne marque RIEN :
 *    aucune partie n'existe, la leçon ne compte nulle part ;
 *  - l'indice correctif de la leçon 3 révèle une tuile masquée,
 *    comme celui du jeu : c'est un coup montré, pas joué ;
 *  - les leçons cameraOnly (Infini) ignorent taps et verrouillage :
 *    seule la caméra parle, la vue traduit ses gestes en événements.
 */
class LessonViewModel : ViewModel() {

    private var board: Board? = null
    private lateinit var lesson: Lesson
    private var runner: LessonRunner? = null

    /** File des coups restant au solveur de la leçon (null = inactif). */
    private var solveQueue: ArrayDeque<Int>? = null

    /** Tuile du dernier coup du solveur (curseur rouge). */
    private var focusIndex = -1

    /** Cible du dernier indice, -1 si aucune. */
    private var hintIndex = -1

    /** Génération de pulsation d'indice, incrémentée à CHAQUE demande. */
    private var hintPulseId = 0

    // --- Démo du doigt (refonte « doigt animé ») : armée UNE fois
    //     par étape, exécutée SANS nourrir la machine. ---

    /** Index de la dernière étape publiée : -1 force l'armement du premier. */
    private var lastStepIndex = -1

    /** Geste de la démo courante (NONE : rien à montrer). */
    private var armedDemoKind = DemoKind.NONE

    /** Tuile visée par la démo courante, -1 si aucune. */
    private var armedDemoIndex = -1

    /** Génération de la démo, incrémentée à CHAQUE changement d'étape. */
    private var demoArmId = 0

    /** Solveur gelé quand l'app n'est plus visible. */
    private var paused = false

    private val _uiState = MutableStateFlow(LessonUiState())
    val uiState: StateFlow<LessonUiState> = _uiState.asStateFlow()

    /**
     * Ouvre la leçon : plateau construit (pré-résolu ou mélangé selon
     * la leçon), machine d'étapes à zéro, première bulle posée.
     */
    fun start(lesson: Lesson) {
        this.lesson = lesson
        board = lesson.board.build()
        runner = LessonRunner(lesson)
        solveQueue = null
        focusIndex = -1
        hintIndex = -1
        lastStepIndex = -1
        armedDemoKind = DemoKind.NONE
        armedDemoIndex = -1
        paused = false
        publish()
    }

    /** Gèle le solveur quand l'activité quitte l'écran, le libère au retour. */
    fun setPaused(p: Boolean) {
        paused = p
    }

    /**
     * Tap sur une tuile : la leçon cameraOnly l'ignore (leçon
     * Infini — la caméra est la leçon, pas les tuiles) ; tuile
     * verrouillée, scellée ou libre : rien (le jeu verrouille par le
     * tap simple, la leçon VERROUILLE par l'appui long : un tap de
     * trop ne scelle rien). Sinon un quart de tour JOUÉ, comme au
     * jeu, et la victoire éventuelle.
     */
    fun onCellTapped(index: Int) {
        val r = runner ?: return
        if (lesson.board.cameraOnly) return
        val b = board ?: return
        if (b.isSolved() || b.locked[index] || b.isSealed(index) || b.isEmpty(index)) {
            return
        }
        b.rotateCell(index, 1, byPlayer = true)
        r.onEvent(LessonEvent.Rotated(index))
        checkVictory()
        publish()
    }

    /**
     * Appui long : verrouillage / déverrouillage joué (la leçon des
     * verrous vit au fil du geste, sans restriction), la machine
     * consomme selon son attente (LOCK ou UNLOCK).
     */
    fun onCellLongPressed(index: Int) {
        val r = runner ?: return
        if (lesson.board.cameraOnly) return
        val b = board ?: return
        if (b.isSolved() || b.isEmpty(index)) return
        if (!b.toggleLock(index)) return
        r.onEvent(LessonEvent.Locked(index, b.locked[index]))
        publish()
    }

    /**
     * Bouton Indice de la leçon : SIGNAL (cadre pulsé sur la cible
     * du moteur, informative) ou FIX (correctif scellé, copie de
     * GameViewModel.onHint : remise à l'aplomb, révélation d'une
     * tuile masquée, scellement — un coup montré, pas joué).
     */
    fun onHintButton() {
        val r = runner ?: return
        val b = board ?: return
        when (r.kind) {
            ExpectKind.HINT_SIGNAL -> {
                val index = b.hintTarget()
                if (index < 0) return
                hintIndex = index
                ++hintPulseId
                r.onEvent(LessonEvent.HintSignal(index))
                publish()
            }
            ExpectKind.HINT_FIX -> {
                val index = b.hintTarget()
                if (index < 0) return
                val steps = (4 - b.rotations[index]) % 4
                if (steps != 0) b.rotateCell(index, steps)
                if (b.isHidden(index)) b.reveal(index)
                b.seal(index)
                hintIndex = index
                ++hintPulseId
                r.onEvent(LessonEvent.HintFix(index))
                checkVictory()
                publish()
            }
            else -> return
        }
    }

    /**
     * Bouton Résoudre de la leçon (leçon des aides) : le MÊME
     * solveur que le jeu, un coup par tick, curseur rouge — la
     * partie n'existe pas, rien n'est marqué. La machine d'étapes
     * consomme l'appui, la victoire vient à la fin de la file.
     */
    fun onSolveButton() {
        val r = runner ?: return
        val b = board ?: return
        if (r.kind != ExpectKind.SOLVE || b.isSolved()) return
        r.onEvent(LessonEvent.SolvePressed)
        solveQueue = ArrayDeque(b.solveMoves())
        publish()
        viewModelScope.launch {
            while (isActive && solveQueue != null) {
                delay(SOLVE_TICK_MS.milliseconds)
                if (!paused) solveStep()
            }
        }
    }

    /**
     * Un coup du solveur, copie du pas de la démonstration
     * (DemoViewModel.step, sans célébration) : déverrouiller puis
     * tourner, comme le jeu, les tuiles déjà en place sautées. La
     * branche ELSE consomme explicitement la tête ; le coup joué la
     * laisse en place, elle se retirera au tick suivant une fois la
     * tuile résolue. Il ne RÉVÈLE PAS les tuiles masquées (décision
     * d'eric du 16 nov. 2026) : seule la victoire dévoile.
     */
    private fun solveStep() {
        val r = runner ?: return
        val b = board ?: return
        val queue = solveQueue ?: return
        while (queue.isNotEmpty()) {
            val index = queue.first()
            val steps = (4 - b.rotations[index]) % 4
            when {
                b.locked[index] -> {
                    b.toggleLock(index)
                    focusIndex = index
                    publish()
                    return
                }
                steps != 0 -> {
                    b.rotateCell(index, steps)
                    focusIndex = index
                    publish()
                    return
                }
                else -> queue.removeFirst()
            }
        }
        solveQueue = null
        focusIndex = -1
        r.onEvent(LessonEvent.Victory)
        publish()
    }

    /** Défilement de la caméra (leçon Infini), deltas en cellules. */
    fun onCameraScrolled(dxCells: Float, dyCells: Float) {
        val r = runner ?: return
        r.onEvent(LessonEvent.Scrolled(dxCells, dyCells))
        publish()
    }

    /** Pincement (leçon Infini), facteur de croissance. */
    fun onCameraZoomed(ratio: Float) {
        val r = runner ?: return
        r.onEvent(LessonEvent.Zoomed(ratio))
        publish()
    }

    /** Bouton « Continuer » : seule sortie d'une étape de lecture. */
    fun onContinue() {
        val r = runner ?: return
        r.onContinue()
        publish()
    }

    /** La victoire alimente la machine (attente VICTORY) si le plateau est résolu. */
    private fun checkVictory() {
        val r = runner ?: return
        val b = board ?: return
        if (b.isSolved()) r.onEvent(LessonEvent.Victory)
    }

    /**
     * Démo du doigt, appelée par la vue à l'instant du contact :
     * l'action s'exécute VRAIMENT (quart de tour, verrou, pulsation
     * d'indice) mais la machine d'étapes n'est PAS nourrie — seul
     * le geste du joueur consomme l'attente. Les attentes de
     * bouton se contentent de la pulsation : l'effet réel (le
     * correctif, le solveur) est PRÉCISÉMENT ce que le joueur doit
     * presser, l'exécuter à sa place viderait l'étape de son sens
     * et pourrait résoudre le plateau sous la consigne Résoudre.
     */
    fun demoAct(kind: DemoKind, index: Int) {
        when (kind) {
            DemoKind.TAP_TILE -> demoTapAt(index)
            DemoKind.HOLD_TILE -> demoLongPressAt(index)
            DemoKind.TAP_BUTTON -> demoHintButton()
            else -> return
        }
    }

    /**
     * Démo de l'appui simple : un quart de tour RÉEL, montré au
     * doigt. Garde du joueur reprise telle quelle (tuile
     * verrouillée, scellée, libre ou vide : rien ne bouge) ; la
     * victoire n'est PAS vérifiée : la démo ne consomme rien.
     */
    private fun demoTapAt(index: Int) {
        val b = board ?: return
        if (lesson.board.cameraOnly) return
        if (index < 0 || index >= b.size) return
        if (b.isSolved() || b.locked[index] || b.isSealed(index) || b.isEmpty(index)) return
        b.rotateCell(index, 1, byPlayer = true)
        publish()
    }

    /**
     * Démo du verrouillage : verrou RÉEL sur la cible (une tuile à
     * l'aplomb, choisie par [demoTarget] — verrouiller une tuile
     * fausse rendrait le plateau insoluble).
     */
    private fun demoLongPressAt(index: Int) {
        val b = board ?: return
        if (lesson.board.cameraOnly) return
        if (index < 0 || index >= b.size) return
        if (b.isSolved() || b.isEmpty(index) || b.locked[index] || b.isSealed(index)) return
        if (!b.toggleLock(index)) return
        publish()
    }

    /**
     * Démo du bouton Indice : le MÊME signe que pour le joueur (la
     * cible du moteur, pulsation armée par sa génération), sans
     * nourrir la machine — le correctif et le solveur restent au
     * geste du joueur.
     */
    private fun demoHintButton() {
        val r = runner ?: return
        val b = board ?: return
        if (r.kind != ExpectKind.HINT_SIGNAL && r.kind != ExpectKind.HINT_FIX) return
        val index = b.hintTarget()
        if (index < 0) return
        hintIndex = index
        ++hintPulseId
        publish()
    }

    private fun publish() {
        val b = board ?: return
        val r = runner ?: return
        // Armement de la démo : à CHAQUE changement d'étape (la
        // génération avance même vers NONE — l'activité annule la
        // démo en cours, la leçon refermée ne lâche pas un doigt
        // orphelin sur l'étape suivante).
        if (r.index != lastStepIndex) {
            lastStepIndex = r.index
            val step = r.step
            if (step == null) {
                armedDemoKind = DemoKind.NONE
                armedDemoIndex = -1
            } else {
                armedDemoKind = demoFor(step.kind)
                armedDemoIndex =
                    if (lesson.board.cameraOnly) -1 else demoTarget(b, step.kind)
            }
            ++demoArmId
        }
        _uiState.value = LessonUiState(
            board = b,
            connected = b.connectedCells(),
            solved = b.isSolved(),
            focus = focusIndex,
            bubbleId = r.step?.bubbleId,
            kind = r.kind,
            hintIndex = hintIndex,
            hintRequestId = hintPulseId,
            lessonDone = r.finished,
            demoKind = armedDemoKind,
            demoIndex = armedDemoIndex,
            demoRequestId = demoArmId
        )
    }

    companion object {
        /** Délai entre deux coups du solveur de leçon, en millisecondes. */
        private const val SOLVE_TICK_MS = 250L
    }
}
