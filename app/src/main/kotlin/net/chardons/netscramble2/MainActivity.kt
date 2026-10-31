package net.chardons.netscramble2

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
import net.chardons.netscramble2.game.Skill

/**
 * Activité unique. Le stateflow du ViewModel est collecté de façon
 * lifecycle-aware : l'affichage se met en pause tout seul quand l'app
 * passe en arrière-plan (plus de timers à gérer à la main).
 *
 * TODO (roadmap) : écran d'aide, écran de réglages (animation),
 * edge-to-edge, adaptivité tablette.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: GameViewModel
    private lateinit var soundEffects: SoundEffects
    private lateinit var scoresStore: ScoresStore
    private var soundMode = SoundMode.FULL
    private var lastSolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        viewModel = ViewModelProvider(this)[GameViewModel::class.java]

        // Partie précédente : survit à la mort du processus. Appelé avant
        // toute collecte ; sans effet sur un ViewModel déjà vivant
        // (rotation d'écran), qui est forcément plus à jour.
        val savedSpec = getPreferences(MODE_PRIVATE).getString(GAME_SPEC_KEY, null)
        viewModel.restoreIfPending(GameSpec.decode(savedSpec))

        // Mode sonore persisté (même clé que l'original), puis lecteur.
        soundMode = loadSoundMode()
        soundEffects = SoundEffects(this)
        scoresStore = ScoresStore(this)

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
    }

    override fun onStop() {
        // Parité avec l'état PAUSED de l'original : le jeu gèle, et la
        // partie est photographiée pour survivre à une mort du processus.
        viewModel.setClockPaused(true)
        getPreferences(MODE_PRIVATE).edit {
            putString(GAME_SPEC_KEY, viewModel.currentSpec().encode())
        }
        super.onStop()
    }

    private fun render(state: GameUiState) {
        val board = state.board ?: return
        binding.boardView.update(board, state.connected ?: return, state.solved)
        binding.statusClicks.text = getString(R.string.status_clicks, state.clicks)
        binding.statusSkill.text = getString(
            listOf(
                R.string.skill_novice, R.string.skill_normal, R.string.skill_expert,
                R.string.skill_master, R.string.skill_insane
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Coche le niveau courant.
        val skillMenu = menu.findItem(R.id.menu_skill).subMenu
        val current = viewModel.uiState.value.skill
        when (current) {
            Skill.NOVICE -> skillMenu?.findItem(R.id.skill_novice)
            Skill.NORMAL -> skillMenu?.findItem(R.id.skill_normal)
            Skill.EXPERT -> skillMenu?.findItem(R.id.skill_expert)
            Skill.MASTER -> skillMenu?.findItem(R.id.skill_master)
            Skill.INSANE -> skillMenu?.findItem(R.id.skill_insane)
        }?.isChecked = true
        // Coche le mode sonore courant.
        val soundMenu = menu.findItem(R.id.menu_sounds).subMenu
        val modeItem = when (soundMode) {
            SoundMode.NONE -> R.id.sounds_off
            SoundMode.QUIET -> R.id.sounds_quiet
            SoundMode.FULL -> R.id.sounds_full
        }
        soundMenu?.findItem(modeItem)?.isChecked = true
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
        val skill = when (item.itemId) {
            R.id.skill_novice -> Skill.NOVICE
            R.id.skill_normal -> Skill.NORMAL
            R.id.skill_expert -> Skill.EXPERT
            R.id.skill_master -> Skill.MASTER
            R.id.skill_insane -> Skill.INSANE
            else -> null
        }
        return when {
            item.itemId == R.id.menu_new -> {
                viewModel.newGame(viewModel.uiState.value.skill)
                true
            }
            item.itemId == R.id.sounds_off || item.itemId == R.id.sounds_quiet ||
                item.itemId == R.id.sounds_full -> {
                item.isChecked = true
                soundMode = when (item.itemId) {
                    R.id.sounds_off -> SoundMode.NONE
                    R.id.sounds_quiet -> SoundMode.QUIET
                    else -> SoundMode.FULL
                }
                getPreferences(MODE_PRIVATE).edit {
                    putString(SOUND_MODE_KEY, soundMode.name)
                }
                true
            }
            item.itemId == R.id.menu_solve -> {
                val starting = !viewModel.uiState.value.solving
                viewModel.toggleSolver()
                if (starting) {
                    // Comme dans l'original : avertir que la partie est grillée.
                    Snackbar.make(binding.root, R.string.solver_used, Snackbar.LENGTH_LONG)
                        .show()
                }
                true
            }
            skill != null -> {
                item.isChecked = true
                viewModel.newGame(skill)
                true
            }
            item.itemId == R.id.menu_scores -> {
                startActivity(Intent(this, ScoresActivity::class.java))
                true
            }
            item.itemId == R.id.menu_help -> {
                showStub(R.string.menu_help)
                true
            }
            item.itemId == R.id.menu_about -> {
                showAbout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * Dialogue de victoire, comme dans l'original (reportWin) : temps,
     * clics, message de record le cas échéant, et « Nouvelle partie ».
     * Si le solveur a servi, aucun record n'est enregistré et l'original
     * ne montre rien — on reste sur ce comportement.
     */
    private fun showWinDialog(event: WinEvent) {
        if (event.solverUsed) return
        val result = scoresStore.register(
            event.skill, event.ntiles, event.clicks, event.seconds
        )
        val recordMessage = when (result) {
            ScoresStore.Result.BEST_CLICKS -> R.string.best_clicks_text
            ScoresStore.Result.BEST_TIME -> R.string.best_time_text
            ScoresStore.Result.BEST_BOTH -> R.string.best_both_text
            ScoresStore.Result.NONE -> 0
        }
        val message = buildString {
            append(getString(R.string.win_text, formatTime(event.seconds * 1000L), event.clicks))
            if (recordMessage != 0) {
                append("\n\n").append(getString(recordMessage))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (recordMessage != 0) R.string.win_pbest_title else R.string.win_title)
            .setMessage(message)
            .setPositiveButton(R.string.win_new) { _, _ ->
                viewModel.newGame(viewModel.uiState.value.skill)
            }
            .setNegativeButton(R.string.win_continue, null)
            .show()
    }

    /**
     * Mode sonore sauvegardé (même clé que l'original), FULL par défaut.
     */
    private fun loadSoundMode(): SoundMode {
        val stored = getPreferences(MODE_PRIVATE).getString(SOUND_MODE_KEY, null)
        return SoundMode.entries.firstOrNull { it.name == stored } ?: SoundMode.FULL
    }

    override fun onDestroy() {
        soundEffects.release()
        super.onDestroy()
    }

    private fun showStub(labelRes: Int) {
        Snackbar.make(
            binding.root,
            getString(R.string.not_implemented_yet, getString(labelRes)),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        /** Clé SharedPreferences du mode sonore, reprise de l'original. */
        private const val SOUND_MODE_KEY = "soundMode"

        /** Clé de la partie en cours photographiée pour persistance. */
        private const val GAME_SPEC_KEY = "gameSpec"
    }
}
