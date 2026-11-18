package net.chardons.netscramble2

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.chardons.netscramble2.databinding.ActivityLessonBinding
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.ui.BoardView
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * LEÇON du mode Apprentissage (chantier du 17 nov. 2026), plein
 * écran comme la démonstration : un plateau reproductible, UNE
 * bulle à la fois dans le bandeau bas (voile du patron de la
 * démo), et le bouton que la bulle appelle — Continuer pour les
 * étapes de lecture, Indice puis Résoudre pour la leçon des
 * aides. Le retour système referme sans confirmation : une leçon
 * se rejoue à volonté depuis la liste.
 *
 * Le bouton Coche : la leçon finie est persistée par
 * [Settings.saveLessonDone] PUIS l'écran se referme — la coche
 * attend le retour à la liste.
 *
 * Leçon Infini (cameraOnly) : les taps et appuis longs sont
 * ignorés par le modèle ; la VUE traduit ses propres gestes de
 * caméra en événements ([BoardView.onCameraScrolled],
 * [BoardView.onCameraZoomed]). Le plateau 40 x 40 arrive
 * PRÉ-RÉSOLU cadré sur le SERVEUR : le joueur va le perdre de vue
 * d'un tour complet de fenêtre, le retrouver — c'est la leçon.
 */
class LessonActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLessonBinding
    private lateinit var viewModel: LessonViewModel

    /** Leçon jouée, index dans [Lessons.ALL]. */
    private var ordinal = 0

    /** Plateau actuellement affiché : le cadrage ne se repose qu'à un NOUVEAU plateau. */
    private var shownBoard: Board? = null

    /** Dernière génération de pulsation d'indice rendue. */
    private var lastHintRequestId = 0

    /** Dernière valeur de leçon terminée rendue (la sauvegarde ne se pose qu'une fois). */
    private var lastLessonDone = false

    /** Dernière génération de démo rendue : le doigt se pose une fois par étape. */
    private var lastDemoRequestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.apply(window)
        binding = ActivityLessonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.lessonBoard, top = true, bottom = true)
        // Le bandeau du bas reçoit l'inset bas de la barre de
        // navigation (patron de la démo v9) : le texte ne se
        // superpose pas aux boutons virtuels.
        EdgeToEdge.padSystemBars(binding.lessonBottomStack, bottom = true)

        ordinal = (intent.getIntExtra(EXTRA_ORDINAL, 0))
            .coerceIn(0, Lessons.ALL.size - 1)
        val lesson = Lessons.ALL[ordinal]

        viewModel = ViewModelProvider(this)[LessonViewModel::class.java]
        binding.lessonBoard.blipsEnabled = Settings.loadAnimEnabled(this)

        // Gestes du joueur : rotation, verrouillage, et caméra pour
        // la leçon Infini.
        binding.lessonBoard.onCellTapped = { index -> viewModel.onCellTapped(index) }
        binding.lessonBoard.onCellLongPressed = { index -> viewModel.onCellLongPressed(index) }
        binding.lessonBoard.onCameraScrolled = { dx, dy -> viewModel.onCameraScrolled(dx, dy) }
        binding.lessonBoard.onCameraZoomed = { ratio -> viewModel.onCameraZoomed(ratio) }

        // Boutons du bandeau : chaque bulle n'en montre que le sien.
        binding.lessonHintBtn.setOnClickListener { viewModel.onHintButton() }
        binding.lessonSolveBtn.setOnClickListener { viewModel.onSolveButton() }
        binding.lessonContinueBtn.setOnClickListener { viewModel.onContinue() }

        viewModel.start(lesson)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setPaused(false)
    }

    override fun onStop() {
        // Le solveur de leçon gèle en arrière-plan : aucun coup joué
        // dans le dos de l'écran ; le doigt de démo n'y survit pas
        // non plus (il réapparaîtrait figé au retour).
        binding.lessonFinger.cancel()
        viewModel.setPaused(true)
        super.onStop()
    }

    private fun render(state: LessonUiState) {
        val board = state.board ?: return
        val lesson = Lessons.ALL[ordinal]
        val newBoard = board !== shownBoard
        binding.lessonBoard.solverFocus = state.focus
        binding.lessonBoard.update(board, state.connected ?: return, state.solved)
        if (newBoard) {
            shownBoard = board
            // Préréglage APRÈS update (patron de la démo) : cadrage
            // ajusté — la vue l'applique au premier layout si le
            // rendu arrive avant la mesure.
            binding.lessonBoard.applyZoomPreset(1f)
            if (lesson.board.cameraOnly) {
                // Leçon Infini : la fenêtre DEMARRE cadrée sur le
                // serveur — le post passe APRÈS le premier layout,
                // la caméra d'accueil est posée une fois pour
                // toutes ; c'est elle que le tour complet va
                // retrouver.
                binding.lessonBoard.post {
                    binding.lessonBoard.followCell(
                        board.serverIndex, BoardView.FollowMode.CENTER
                    )
                }
            }
        }
        // Caméra discrète du solveur (patron de la démo).
        if (state.focus >= 0) {
            binding.lessonBoard.followCell(state.focus, BoardView.FollowMode.VISIBLE)
        }
        // Pulsation d'indice : (re)armée par sa génération, jamais
        // par les publications du solveur (patron du jeu).
        if (state.hintRequestId != lastHintRequestId) {
            lastHintRequestId = state.hintRequestId
            binding.lessonBoard.showHint(state.hintIndex)
            if (state.hintIndex >= 0) {
                binding.lessonBoard.followCell(state.hintIndex, BoardView.FollowMode.VISIBLE)
            }
        }
        // Bulle courante — chaque identifiant a sa chaîne EN+FR
        // (validate_apprentissage épingle la complétude).
        binding.lessonBubble.text = when (state.bubbleId) {
            "lesson_l1_b1" -> getString(R.string.lesson_l1_b1)
            "lesson_l1_b2" -> getString(R.string.lesson_l1_b2)
            "lesson_l1_b3" -> getString(R.string.lesson_l1_b3)
            "lesson_l2_b1" -> getString(R.string.lesson_l2_b1)
            "lesson_l2_b2" -> getString(R.string.lesson_l2_b2)
            "lesson_l2_b3" -> getString(R.string.lesson_l2_b3)
            "lesson_l2_b4" -> getString(R.string.lesson_l2_b4)
            "lesson_l3_b1" -> getString(R.string.lesson_l3_b1)
            "lesson_l3_b2" -> getString(R.string.lesson_l3_b2)
            "lesson_l3_b3" -> getString(R.string.lesson_l3_b3)
            "lesson_l3_b4" -> getString(R.string.lesson_l3_b4)
            "lesson_l4_b1" -> getString(R.string.lesson_l4_b1)
            "lesson_l4_b2" -> getString(R.string.lesson_l4_b2)
            "lesson_l4_b3" -> getString(R.string.lesson_l4_b3)
            "lesson_l5_b1" -> getString(R.string.lesson_l5_b1)
            "lesson_l5_b2" -> getString(R.string.lesson_l5_b2)
            "lesson_l5_b3" -> getString(R.string.lesson_l5_b3)
            "lesson_l6_b1" -> getString(R.string.lesson_l6_b1)
            "lesson_l6_b2" -> getString(R.string.lesson_l6_b2)
            "lesson_l6_b3" -> getString(R.string.lesson_l6_b3)
            "lesson_l6_b4" -> getString(R.string.lesson_l6_b4)
            "lesson_l7_b1" -> getString(R.string.lesson_l7_b1)
            "lesson_l7_b2" -> getString(R.string.lesson_l7_b2)
            "lesson_l7_b3" -> getString(R.string.lesson_l7_b3)
            else -> ""
        }
        // Boutons : chaque étape ne montre que le sien.
        val kind = state.kind
        binding.lessonHintBtn.visibility =
            if (kind == ExpectKind.HINT_SIGNAL || kind == ExpectKind.HINT_FIX) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        binding.lessonSolveBtn.visibility =
            if (kind == ExpectKind.SOLVE) android.view.View.VISIBLE else android.view.View.GONE
        binding.lessonContinueBtn.visibility =
            if (kind == ExpectKind.CONTINUE) android.view.View.VISIBLE else android.view.View.GONE
        // Leçon finie : la coche se persiste UNE fois, l'écran se
        // referme — le retour à la liste montre la coche.
        if (state.lessonDone && !lastLessonDone) {
            lastLessonDone = true
            Settings.saveLessonDone(this, ordinal)
            finish()
        }
        // Démo du doigt : armée UNE fois par étape (la génération
        // avance aussi vers NONE — l'étape de lecture annule la
        // démo en cours, aucun doigt orphelin sur la bulle suivante).
        if (state.demoRequestId != lastDemoRequestId) {
            lastDemoRequestId = state.demoRequestId
            binding.lessonFinger.cancel()
            if (!state.lessonDone && state.demoKind != DemoKind.NONE) {
                armDemoWhenReady(state)
            }
        }
    }

    /**
     * Attend que la vue du plateau soit MESURÉE avant de viser la
     * cible (la première démo s'arme au onCreate, avant le premier
     * layout : cellCenter répond alors null — on repose la demande
     * à la trame suivante, la génération protège des étalons).
     */
    private fun armDemoWhenReady(state: LessonUiState) {
        binding.lessonBoard.post {
            if (lastDemoRequestId != state.demoRequestId || state.lessonDone) return@post
            val target = demoTargetPoint(state)
            if (target == null) {
                armDemoWhenReady(state)
                return@post
            }
            binding.lessonFinger.demo(
                state.demoKind, target[0], target[1],
                onAct = { viewModel.demoAct(state.demoKind, state.demoIndex) },
                onPanFrame = { dx, dy -> binding.lessonBoard.demoPanBy(dx, dy) },
                onZoomFrame = { ratio -> binding.lessonBoard.demoZoomBy(ratio) }
            )
        }
    }

    /**
     * Le point visé par la démo : le centre de la tuile cible
     * ([BoardView.cellCenter]), le bouton que l'étape montre, ou
     * le centre de la vue pour les gestes de caméra. Null tant que
     * la cible n'est pas mesurable.
     */
    private fun demoTargetPoint(state: LessonUiState): FloatArray? = when (state.demoKind) {
        DemoKind.TAP_TILE, DemoKind.HOLD_TILE ->
            binding.lessonBoard.cellCenter(state.demoIndex)
        DemoKind.TAP_BUTTON -> when (state.kind) {
            ExpectKind.HINT_SIGNAL, ExpectKind.HINT_FIX ->
                buttonCenter(binding.lessonHintBtn)
            ExpectKind.SOLVE -> buttonCenter(binding.lessonSolveBtn)
            else -> null
        }
        DemoKind.PINCH, DemoKind.DRAG_X, DemoKind.DRAG_Y -> {
            val root = binding.lessonRoot
            floatArrayOf(root.width / 2f, root.height / 2.2f)
        }
        DemoKind.NONE -> null
    }

    /** Centre d'un bouton du bandeau, en coordonnées de la vue racine. */
    private fun buttonCenter(button: android.view.View): FloatArray {
        val rootLoc = IntArray(2)
        val btnLoc = IntArray(2)
        binding.lessonRoot.getLocationInWindow(rootLoc)
        button.getLocationInWindow(btnLoc)
        return floatArrayOf(
            btnLoc[0] + button.width / 2f - rootLoc[0],
            btnLoc[1] + button.height / 2f - rootLoc[1]
        )
    }

    companion object {
        /** Index de la leçon dans [Lessons.ALL] (intent extra). */
        const val EXTRA_ORDINAL = "ordinal"
    }
}
