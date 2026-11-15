package net.chardons.netscramble2

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import net.chardons.netscramble2.databinding.ActivityDemoBinding
import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.Screen
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.ui.BoardView
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * DÉMONSTRATION en plein écran (chantier Aide à la résolution, 15
 * nov. 2026) : plateaux enchaînés, chacun mélangé puis résolu coup
 * par coup par le solveur (visible, curseur rouge), victoire
 * clignotée, pause, suivant — indéfiniment. TOUTE interaction la
 * referme : la partie du joueur n'a pas bougé d'un iota.
 *
 * Réglages (page « Réglages », persistés par [Settings]) : difficulté
 * FIXE / ALÉATOIRE / PROGRESSIVE (départ au niveau choisi, montée
 * d'un cran, boucle sur les huit niveaux — la famille Infini
 * comprise), zoom (multiplicateur du plancher), rythme (délai par
 * coup). Les blips suivent le réglage « Animation » du jeu.
 *
 * Écarts assumés : AUCUN son (à statuer avec eric) ; la caméra ne
 * recentre que si la tuile du coup sort de la zone visible de la
 * vue (retour UAT d'eric du 15 nov. 2026).
 */
class DemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDemoBinding
    private lateinit var viewModel: DemoViewModel

    /** Plateau actuellement affiché : le zoom ne se repose qu'à un NOUVEAU plateau. */
    private var shownBoard: Board? = null

    /** Indication « Touchez pour revenir » : ~4 secondes puis fondu. */
    private var hintShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.apply(window)
        binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.demoBoard, top = true, bottom = true)

        viewModel = ViewModelProvider(this)[DemoViewModel::class.java]
        binding.demoBoard.blipsEnabled = Settings.loadAnimEnabled(this)

        // Les dimensions viennent de la même matrice Screen que le
        // jeu (la famille Infini y est fixe 40 x 40).
        viewModel.start(
            Settings.loadDemoDifficulty(this),
            Settings.loadDemoSkill(this),
            Settings.loadDemoSpeed(this)
        ) { skill -> boardSize(skill) }

        fadeTouchHint()

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
        // La démonstration gèle en arrière-plan : pas de coups joués
        // dans le dos de l'écran. Elle ne photographie RIEN : rien à
        // restaurer, aucune partie n'est créée.
        viewModel.setPaused(true)
        super.onStop()
    }

    /**
     * TOUTE interaction referme la démonstration : la partie du
     * joueur, elle, n'a pas bougé. Le DISPATCH voit passer chaque
     * événement AVANT les vues : un toucher sur le plateau ne fait
     * pas tourner une tuile au passage, contrairement à un listener
     * du root (la vue du plateau consomme sinon le toucher) ou au
     * onTouchEvent de l'activité (qui ne voit que les événements
     * qu'aucune vue n'a pris).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Indication « Touchez pour revenir » : affichée d'emblée, elle
     * reste ~[TOUCH_HINT_MS] puis fond sur une seconde — le joueur
     * qui vient d'ouvrir la démo sait comment en sortir, sans
     * indication permanente.
     */
    private fun fadeTouchHint() {
        if (hintShown) return
        hintShown = true
        binding.demoTouchHint.alpha = 0.8f
        lifecycleScope.launch {
            delay(TOUCH_HINT_MS.milliseconds)
            if (isFinishing || isDestroyed) return@launch
            binding.demoTouchHint.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .start()
        }
    }

    private fun render(state: DemoUiState) {
        val board = state.board ?: return
        // Nouveau plateau : le préréglage de zoom se repose une fois,
        // pas à chaque coup — sinon la caméra se battrait avec le
        // suivi du solveur.
        if (board !== shownBoard) {
            shownBoard = board
            binding.demoBoard.applyZoomPreset(
                Settings.loadDemoZoom(this).multiplier
            )
        }
        binding.demoBoard.solverFocus = state.focus
        binding.demoBoard.update(board, state.connected ?: return, state.solved)
        // Caméra discrète : elle ne recentre que si la tuile du coup
        // sort de la zone visible de la vue (retour UAT du 15 nov. 2026).
        if (state.focus >= 0) {
            binding.demoBoard.followCell(state.focus, BoardView.FollowMode.VISIBLE)
        }
    }

    /**
     * Taille du plateau pour [skill] : la même matrice Screen que
     * MainActivity.boardSize — la démonstration joue les mêmes
     * plateaux que le jeu sur cet écran.
     */
    private fun boardSize(skill: Skill): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        return Screen.find(metrics.widthPixels, metrics.heightPixels)
            .boardSize(skill, landscape)
    }

    companion object {
        /** Durée d'affichage de l'indication, avant le fondu. */
        private const val TOUCH_HINT_MS = 4_000L

        /** Durée du fondu de l'indication. */
        private const val FADE_MS = 1_000L
    }
}
