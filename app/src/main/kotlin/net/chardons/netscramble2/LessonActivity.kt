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
        // dans le dos de l'écran.
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
    }

    companion object {
        /** Index de la leçon dans [Lessons.ALL] (intent extra). */
        const val EXTRA_ORDINAL = "ordinal"
    }
}
