package net.chardons.netscramble2

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.chardons.netscramble2.audio.SoundEffects
import net.chardons.netscramble2.audio.SoundMode
import net.chardons.netscramble2.databinding.ActivityMainBinding
import net.chardons.netscramble2.game.GameSpec
import net.chardons.netscramble2.game.Screen
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * Activité unique. Le stateflow du ViewModel est collecté de façon
 * lifecycle-aware : l'affichage se met en pause tout seul quand l'app
 * passe en arrière-plan (plus de timers à gérer à la main).
 *
 * TODO (phase bonus) : seed partageable, tailles custom,
 * thèmes daltoniens.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: GameViewModel
    private lateinit var soundEffects: SoundEffects
    private lateinit var scoresStore: ScoresStore
    private var soundMode = SoundMode.FULL
    private var animEnabled = true
    private var lastSolving = false

    /**
     * Page unique « Réglages » (refonte du 13 nov. 2026) : le niveau
     * de difficulté CHOISI revient ici en résultat — même circuit que
     * l'ancien sous-menu (nouvelle partie + rappel des règles des
     * niveaux spéciaux). La page a déjà demandé confirmation si une
     * partie était en cours.
     */
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val name = result.data?.getStringExtra(SettingsActivity.EXTRA_SKILL)
            ?: return@registerForActivityResult
        val skill = Skill.entries.firstOrNull { it.name == name }
            ?: return@registerForActivityResult
        val previous = viewModel.uiState.value.skill
        if (skill != previous) {
            val (width, height) = boardSize(skill)
            viewModel.newGame(skill, width, height)
            showSkillHelpIfNeeded(skill, previous)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M6 : bord à bord — la toolbar passe sous la barre d'état, le
        // plateau reste au-dessus de la barre de navigation.
        EdgeToEdge.apply(window)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.toolbar, top = true)
        EdgeToEdge.padSystemBars(binding.statusRow)
        EdgeToEdge.padSystemBars(binding.boardContainer, bottom = true)
        setSupportActionBar(binding.toolbar)
        // Pas de titre sur l'écran de jeu : la toolbar ne porte que les
        // boutons d'action (Material 3). Sans ceci, setSupportActionBar
        // remet le label de l'activité (app_name) comme titre.
        supportActionBar?.setDisplayShowTitleEnabled(false)

        viewModel = ViewModelProvider(this)[GameViewModel::class.java]

        // Partie précédente : survit à la mort du processus. Appelé avant
        // toute collecte ; sans effet sur un ViewModel déjà vivant
        // (rotation d'écran), qui est forcément plus à jour. Sinon,
        // première visite : une partie Normal aux dimensions de l'écran.
        val savedSpec = getSharedPreferences(Persistence.FILE, MODE_PRIVATE)
            .getString(Persistence.GAME_SPEC_KEY, null)
        val restoredSpec = GameSpec.decode(savedSpec)
        if (viewModel.restoreIfPending(restoredSpec)) {
            // Caméra de la sauvegarde : la vue la posera au premier
            // rendu du plateau restauré (emplacement + zoom d'avant
            // l'arrêt ; une v3 n'en a pas, la vue repose la sienne).
            binding.boardView.restoreCamera(restoredSpec?.camera)
        } else {
            val (width, height) = boardSize(Skill.NORMAL)
            viewModel.newGame(Skill.NORMAL, width, height)
        }

        // Mode sonore persisté (même clé que l'original), puis lecteur.
        soundMode = Settings.loadSoundMode(this)
        soundEffects = SoundEffects(this)
        scoresStore = ScoresStore(this)

        // Réglage « Animation » (même clé que l'original, activé par défaut).
        animEnabled = Settings.loadAnimEnabled(this)
        binding.boardView.blipsEnabled = animEnabled

        binding.boardView.onCellTapped = { viewModel.onCellTapped(it) }
        binding.boardView.onCellLongPressed = { viewModel.onCellLongPressed(it) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
        // Les sons ne sont joués que pendant que l'app est visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.soundEvents.collect { sound ->
                    soundEffects.play(sound, soundMode)
                }
            }
        }
        // Victoire : enregistre les records et montre le dialogue.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.winEvents.collect { event -> showWinDialog(event) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setClockPaused(false)
        // Retour de la page « Réglages » (ou simple reprise) : les deux
        // réglages qui s'y changent (sons, animation) sont relus — la
        // page les persiste, c'est ici qu'ils prennent effet.
        soundMode = Settings.loadSoundMode(this)
        animEnabled = Settings.loadAnimEnabled(this)
        binding.boardView.blipsEnabled = animEnabled
    }

    override fun onStop() {
        // Parité avec l'état PAUSED de l'original : le jeu gèle, et la
        // partie est photographiée pour survivre à une mort du processus.
        viewModel.setClockPaused(true)
        // Même fichier et même clé que la page titre (Persistence) :
        // les sauvegardes antérieures restent lisibles à l'identique.
        getSharedPreferences(Persistence.FILE, MODE_PRIVATE).edit {
            // La caméra voyage avec la partie (v4) : reprise à
            // l'emplacement et au zoom de la sauvegarde.
            putString(
                Persistence.GAME_SPEC_KEY,
                viewModel.currentSpec(binding.boardView.cameraSnapshot()).encode()
            )
        }
        super.onStop()
    }

    private fun render(state: GameUiState) {
        val board = state.board ?: return
        // Curseur de focus de l'auto-solveur : la tuile du coup en
        // cours, avant le rendu (cadre rouge de la vue).
        binding.boardView.solverFocus = state.solverFocus
        binding.boardView.update(board, state.connected ?: return, state.solved)
        binding.statusMoves.text = getString(R.string.status_moves, state.moves)
        binding.statusSkill.text = getString(
            listOf(
                R.string.skill_novice, R.string.skill_normal, R.string.skill_expert,
                R.string.skill_master, R.string.skill_insane, R.string.skill_endless,
                R.string.skill_abyss, R.string.skill_void
            )[state.skill.ordinal]
        )
        binding.statusTime.text = getString(R.string.status_time, formatTime(state.elapsedMs))
        // Le menu « Résoudre » devient « Arrêter le solveur » pendant le travail.
        if (state.solving != lastSolving) {
            lastSolving = state.solving
            invalidateOptionsMenu()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    /**
     * Taille du plateau pour [skill] sur l'écran courant, comme
     * findMatrix puis Screen.getBoardWidth et getBoardHeight de
     * l'original : la matrice dépend de la plus petite dimension et du
     * ratio d'aspect, le grand côté du plateau est horizontal en
     * paysage, vertical en portrait. Figée à la création de la partie —
     * tourner l'écran en cours de partie ne régénère rien, la caméra
     * du plateau s'occupe du reste.
     */
    private fun boardSize(skill: Skill): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        return Screen.find(metrics.widthPixels, metrics.heightPixels)
            .boardSize(skill, landscape)
    }

    /**
     * Lance [action] directement s'il n'y a rien à perdre (plateau
     * vierge ou déjà gagné), sinon demande confirmation d'abord : un
     * clic malheureux sur « Nouvelle partie » ou un changement de
     * niveau ne doit plus effacer une partie en cours (roadmap du
     * 10 nov. 2026). Le dialogue rappelle l'avancement (coups, temps),
     * le bouton de confirmation porte le libellé [confirmRes], Annuler
     * referme sans rien toucher.
     */
    private fun confirmIfInProgress(
        titleRes: Int,
        confirmRes: Int,
        action: () -> Unit
    ) {
        val state = viewModel.uiState.value
        if (state.solved || state.moves == 0) {
            action()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(
                getString(
                    R.string.confirm_progress_message,
                    state.moves,
                    formatTime(state.elapsedMs)
                )
            )
            .setPositiveButton(confirmRes) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Les réglages (niveau, sons, animation) vivent sur la page
        // unique « Réglages » : plus aucune coche à poser ici.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Titre du solveur selon son état (démarré / arrêté).
        menu.findItem(R.id.menu_solve)?.setTitle(
            if (viewModel.uiState.value.solving) R.string.menu_stop_solve
            else R.string.menu_solve
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_new -> {
                // Confirmation si une partie est en jeu (roadmap du
                // 10 nov. 2026) : un clic malheureux sur le bouton
                // « Nouvelle partie » ne doit plus perdre un plateau
                // en cours. Plateau vierge ou gagné : rien à perdre.
                confirmIfInProgress(
                    R.string.confirm_new_title,
                    R.string.win_new
                ) {
                    val current = viewModel.uiState.value.skill
                    val (width, height) = boardSize(current)
                    viewModel.newGame(current, width, height)
                }
                true
            }
            R.id.menu_settings -> {
                // Page unique « Réglages » : l'état courant du jeu
                // passe à la page (niveau en cours, avancement) pour
                // ses coches initiales et sa protection de niveau.
                val state = viewModel.uiState.value
                settingsLauncher.launch(
                    Intent(this, SettingsActivity::class.java)
                        .putExtra(SettingsActivity.EXTRA_SKILL, state.skill.name)
                        .putExtra(SettingsActivity.EXTRA_MOVES, state.moves)
                        .putExtra(SettingsActivity.EXTRA_SOLVED, state.solved)
                        .putExtra(SettingsActivity.EXTRA_ELAPSED_MS, state.elapsedMs)
                )
                true
            }
            R.id.menu_solve -> {
                val starting = !viewModel.uiState.value.solving
                viewModel.toggleSolver()
                if (starting) {
                    // Comme dans l'original : avertir que la partie est grillée.
                    Snackbar.make(binding.root, R.string.solver_used, Snackbar.LENGTH_LONG)
                        .show()
                }
                true
            }
            R.id.menu_scores -> {
                startActivity(Intent(this, ScoresActivity::class.java))
                true
            }
            R.id.menu_help -> {
                startActivity(Intent(this, HelpActivity::class.java))
                true
            }
            R.id.menu_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Dialogue de victoire, comme dans l'original (reportWin) : temps,
     * coups, message de record le cas échéant, et « Nouvelle partie ».
     * Si le solveur a servi, aucun record n'est enregistré et l'original
     * ne montre rien — on reste sur ce comportement.
     */
    private fun showWinDialog(event: WinEvent) {
        if (event.solverUsed) return
        val result = scoresStore.register(
            event.skill, event.ntiles, event.moves, event.seconds
        )
        val recordMessage = when (result) {
            ScoresStore.Result.BEST_MOVES -> R.string.best_moves_text
            ScoresStore.Result.BEST_TIME -> R.string.best_time_text
            ScoresStore.Result.BEST_BOTH -> R.string.best_both_text
            ScoresStore.Result.NONE -> 0
        }
        val message = buildString {
            append(getString(R.string.win_text, formatTime(event.seconds * 1000L), event.moves))
            if (recordMessage != 0) {
                append("\n\n").append(getString(recordMessage))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (recordMessage != 0) R.string.win_pbest_title else R.string.win_title)
            .setMessage(message)
            .setPositiveButton(R.string.win_new) { _, _ ->
                val current = viewModel.uiState.value.skill
                val (width, height) = boardSize(current)
                viewModel.newGame(current, width, height)
            }
            .setNegativeButton(R.string.win_continue, null)
            .show()
    }

    /**
     * Comme dans l'original (startGame) : en arrivant au niveau Maître
     * ou Dément, un message rappelle les règles spéciales (bords qui
     * se referment, câbles invisibles). La famille Infini a ses
     * propres rappels : Abîme ajoute les câbles invisibles au plateau
     * infini, Néant y ajoute le brouillard. Pas de rappel en
     * descendant d'un niveau dont les règles sont déjà connues.
     */
    private fun showSkillHelpIfNeeded(skill: Skill, previous: Skill) {
        val message = when (skill) {
            Skill.VOID if previous != Skill.VOID -> R.string.help_void
            Skill.ABYSS if previous != Skill.ABYSS && previous != Skill.VOID ->
                R.string.help_abyss
            Skill.INSANE if previous != Skill.INSANE -> R.string.help_insane
            Skill.MASTER if previous != Skill.MASTER && previous != Skill.INSANE ->
                R.string.help_master
            else -> 0
        }
        if (message != 0) {
            MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onDestroy() {
        soundEffects.release()
        super.onDestroy()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
