package net.chardons.netscramble2

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
 * Réglages (page « Réglages », persistés par [Settings]) : niveaux
 * COCHÉS (v8 : au moins une case, Novice par défaut ; une seule
 * case revient à un niveau fixe), difficulté ALÉATOIRE / PROGRESSIVE
 * parmi ces niveaux (radios grisées à coche unique), zoom
 * (multiplicateur du plancher), rythme (délai par coup). Les blips
 * suivent le réglage « Animation » du jeu.
 *
 * Retours utilisateurs du 16 nov. 2026 (lot 2) : la mention
 * « Démonstration niveau X » vit en bas de l'écran, posée à
 * chaque nouveau plateau ; au Néant, le préréglage cadre la zone
 * révélée (voir BoardView.applyZoomPreset).
 *
 * Retours du 16 nov. 2026 au soir (v7) : bandeau et indication
 * « Touchez pour revenir » forment une pile PERMANENTE en bas,
 * dégagée de la barre de navigation par son inset.
 *
 * Retours du 16 nov. 2026, v8 : la pile repose sur un voile
 * rectangulaire qui opacifie les tuiles dessous ; l'énergie d'un
 * coup n'apparaît plus PENDANT la rotation du coup qui la
 * provoque, mais à sa fin (BoardView, un seul chemin — la coupure
 * v7 est retirée) ; les niveaux de la démonstration se cochent aux
 * réglages. V9 : toute rotation en vol est finie d'un coup à
 * l'arrivée d'un coup (l'énergie suit STRICTEMENT la fin de chaque
 * rotation) ; le voile vit sur un containeur INTERNE — l'inset de
 * la barre de navigation est un padding de la pile, il resterait
 * sinon DANS la zone colorée — à 80 % d'opacité, interligne
 * resserré à 5 dp.
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


    /** Effets de réussite : le réglage Animation décide de l'intermède. */
    private var effectsEnabled = true

    /** Dernier état résolu rendu : le passage à résolu déclenche l'effet. */
    private var lastSolved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.apply(window)
        binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.demoBoard, top = true, bottom = true)

        // Retours utilisateurs du 16 nov. 2026 au soir : la pile du
        // bas (bandeau de niveau + « Touchez pour revenir ») reçoit
        // l'inset bas de la barre de navigation — le texte ne se
        // superpose plus aux boutons virtuels, en gestuelle comme en
        // trois boutons.
        EdgeToEdge.padSystemBars(binding.demoBottomStack, bottom = true)

        viewModel = ViewModelProvider(this)[DemoViewModel::class.java]
        // Réglage « Animation », lu UNE fois : il porte les blips ET
        // l'intermède de victoire — repli sur la pause seule (1,5 s)
        // quand il est coupé ; le modèle en est averti.
        val anim = Settings.loadAnimEnabled(this)
        binding.demoBoard.blipsEnabled = anim
        effectsEnabled = anim
        viewModel.setEffectsEnabled(anim)

        // Les dimensions viennent de la même matrice Screen que le
        // jeu (la famille Infini y est fixe 40 x 40).
        viewModel.start(
            Settings.loadDemoDifficulty(this),
            Settings.loadDemoSkillSet(this),
            Settings.loadDemoSpeed(this)
        ) { skill -> boardSize(skill) }

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
    private fun render(state: DemoUiState) {
        val board = state.board ?: return
        // Nouveau plateau : le préréglage de zoom et la mention de
        // niveau se posent une fois, pas à chaque coup — sinon la
        // caméra se battrait avec le suivi du solveur.
        val newBoard = board !== shownBoard
        if (newBoard) {
            // Plateau neuf : le suivi de victoire repart de zéro.
            lastSolved = false
        }
        binding.demoBoard.solverFocus = state.focus
        binding.demoBoard.update(board, state.connected ?: return, state.solved)
        if (newBoard) {
            shownBoard = board
            // Préréglage APRÈS update : la vue connaît le plateau du
            // coup, et son zoom GAGNE sur la caméra d'arrivée — au
            // Néant, la démo cadre la zone révélée au lieu du
            // plateau entier (retours utilisateurs du 16 nov. 2026).
            binding.demoBoard.applyZoomPreset(
                Settings.loadDemoZoom(this).multiplier
            )
            // Mention du niveau en bas de l'écran (retour
            // utilisateurs du 16 nov. 2026) : la démonstration
            // annonce ce qu'elle montre.
            binding.demoLevelBanner.text = getString(
                R.string.demo_level_banner, skillName(board.skill)
            )
        }
        // Caméra discrète : elle ne recentre que si la tuile du coup
        // sort de la zone visible de la vue (retour UAT du 15 nov. 2026).
        if (state.focus >= 0) {
            binding.demoBoard.followCell(state.focus, BoardView.FollowMode.VISIBLE)
        }
        // Intermède (chantier « Effets spéciaux de réussite ») : la
        // victoire d'un plateau de la démo — toujours sans aide —
        // célèbre d'un effet tiré au sort ; la pause du modèle vaut
        // l'effet (4,5 s) quand les animations vivent, 1,5 s sinon.
        // Toucher l'écran referme déjà la démo : skip naturel.
        if (state.solved && !lastSolved && effectsEnabled) {
            binding.demoBoard.playVictoryEffect(VictoryEffects.pick())
        }
        lastSolved = state.solved
    }

    /**
     * Nom AFFICHÉ du niveau, la même chaîne que la page Réglages :
     * la mention en bas de l'écran parle la langue du joueur, pas
     * celle de l'enum.
     */
    private fun skillName(skill: Skill): String = getString(when (skill) {
        Skill.NOVICE -> R.string.skill_novice
        Skill.NORMAL -> R.string.skill_normal
        Skill.EXPERT -> R.string.skill_expert
        Skill.MASTER -> R.string.skill_master
        Skill.INSANE -> R.string.skill_insane
        Skill.ENDLESS -> R.string.skill_endless
        Skill.ABYSS -> R.string.skill_abyss
        Skill.VOID -> R.string.skill_void
    })

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
}
