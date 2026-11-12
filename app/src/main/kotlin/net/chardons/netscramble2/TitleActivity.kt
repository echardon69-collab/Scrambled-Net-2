package net.chardons.netscramble2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import net.chardons.netscramble2.databinding.ActivityTitleBinding
import net.chardons.netscramble2.game.GameSpec
import net.chardons.netscramble2.ui.EdgeToEdge
import java.io.File

/**
 * Page titre, plein écran au lancement (roadmap du 7 nov. 2026,
 * spéc arrêtée avec eric le 12 nov.) : icône, nom du jeu, version,
 * licence et remerciements — « À propos » reste un dialogue séparé.
 *
 * Tant que le titre s'affiche, deux travaux avancent sous la barre
 * de progression :
 * - le CHARGEMENT DE LA PARTIE SAUVEGARDÉE : la photographie de
 *   GameSpec est décodée et le plateau reconstruit, exactement le
 *   travail que MainActivity refera à l'entrée — c'est sa validation
 *   réelle (une sauvegarde corrompue est détectée ici et simplement
 *   ignorée : MainActivity en lancera une nouvelle) ;
 * - la VÉRIFICATION DE MISE À JOUR GitHub, à chaque lancement,
 *   CAPÉE à 5 secondes : le jeu n'est jamais retenu au-delà, et une
 *   réponse plus tardive n'est pas exploitée (le prochain lancement
 *   revérifiera).
 *
 * Un affichage minimum (1,2 s) rend le titre lisible. Puis :
 * - mise à jour disponible (répondue à temps) : le joueur choisit —
 *   « Mettre à jour » (téléchargement avec progression, installation
 *   sans confirmation quand Android l'accepte, sinon écran système)
 *   ou « Jouer » ;
 * - sinon : entrée automatique dans le jeu.
 */
class TitleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTitleBinding

    /** Publication plus récente détectée à temps, si elle existe. */
    private var pendingRelease: Updater.Release? = null

    private val installReceiver = object : BroadcastReceiver() {
        // L'Intent de confirmation est créé par le PackageInstaller du
        // système : la diffusion arrive par le PendingIntent d'Updater
        // et ce récepteur est NON EXPORTÉ — aucune autre application
        // ne peut la produire. Un IntentSanitizer lui retirerait les
        // extras (session, paquet) dont l'écran de confirmation
        // système a besoin : le signalement UnsafeIntentLaunch est
        // donc éteint volontairement ici.
        @SuppressLint("UnsafeIntentLaunch")
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE
            )
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android exige sa confirmation : montrer l'écran
                    // système fourni (repli de l'installation sans
                    // confirmation).
                    IntentCompat.getParcelableExtra(
                        intent, Intent.EXTRA_INTENT, Intent::class.java
                    )?.let { startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    // Mise à jour réussie : le système remplace
                    // l'application et la relance tout seul.
                }
                else -> {
                    val message = intent.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE
                    ) ?: ""
                    onInstallFailed(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.apply(window)
        binding = ActivityTitleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.root, top = true, bottom = true)

        val version = Updater.currentVersion(this)
        binding.titleVersion.text = getString(R.string.title_version, version)

        binding.playButton.setOnClickListener { startGame() }
        binding.updateButton.setOnClickListener {
            lifecycleScope.launch { runUpdate() }
        }

        ContextCompat.registerReceiver(
            this,
            installReceiver,
            IntentFilter(Updater.ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        launchFlow(version)
    }

    /**
     * Déroulé du titre : chargement de la partie + vérification de
     * mise à jour, jamais plus de [CHECK_TIMEOUT_MS] au total, un
     * affichage minimum de [MIN_DISPLAY_MS], puis choix ou jeu.
     */
    private fun launchFlow(version: String) {
        lifecycleScope.launch {
            // Affichage minimum, en parallèle des travaux.
            val branding = launch { delay(MIN_DISPLAY_MS) }
            // Chargement de la partie sauvegardée : décodage de la
            // photographie + reconstruction du plateau (validation).
            val gameLoad = async(Dispatchers.Default) { loadSavedGame() }
            binding.titleStatus.setText(R.string.title_checking)
            // Vérification GitHub, capée : une réponse tardive est
            // annulée, elle ne sera pas exploitée.
            val release = withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                Updater.checkLatest()
            }
            gameLoad.await()
            branding.join()
            if (release != null && Updater.isNewer(release.version, version)) {
                showUpdateChoice(release)
            } else {
                startGame()
            }
        }
    }

    /**
     * Charge la partie sauvegardée : décode la photographie de
     * GameSpec (même fichier, même clé que MainActivity) et reconstruit
     * le plateau — le travail réel de la restauration, effectué ici
     * pour la barre de progression et pour valider la sauvegarde.
     * MainActivity refait ce décodage à son entrée : la partie
     * restaurée est celle-ci. Null ou corrompue : rien à faire ici,
     * MainActivity lancera une nouvelle partie.
     */
    private fun loadSavedGame() {
        val saved = getSharedPreferences(Persistence.FILE, MODE_PRIVATE)
            .getString(Persistence.GAME_SPEC_KEY, null) ?: return
        GameSpec.decode(saved)?.buildBoard()
    }

    /** Une mise à jour répondue à temps existe : le joueur choisit. */
    private fun showUpdateChoice(release: Updater.Release) {
        pendingRelease = release
        binding.titleProgress.isVisible = false
        binding.titleStatus.text =
            getString(R.string.title_update_available, release.version)
        binding.updateButton.isVisible = true
        binding.playButton.isVisible = true
    }

    /**
     * « Mettre à jour » : autorisation d'installation si besoin
     * (première fois seulement, écran système), téléchargement de
     * l'APK avec progression, puis installation. Quitter l'écran
     * pendant le téléchargement l'annule (coroutine du cycle de vie).
     */
    private suspend fun runUpdate() {
        val release = pendingRelease ?: return
        if (!Updater.canInstall(this)) {
            binding.titleStatus.setText(R.string.title_install_permission)
            Updater.requestInstallPermission(this)
            return
        }
        binding.updateButton.isEnabled = false
        binding.playButton.isEnabled = false
        binding.titleStatus.text =
            getString(R.string.title_download_status, release.version)
        try {
            val apk = File(cacheDir, Updater.UPDATE_APK_NAME)
            Updater.download(release.apkUrl, apk) { percent ->
                runOnUiThread {
                    if (percent < 0) {
                        binding.titleProgress.isIndeterminate = true
                    } else {
                        binding.titleProgress.isIndeterminate = false
                        binding.titleProgress.setProgressCompat(percent, true)
                    }
                }
            }
            binding.titleStatus.setText(R.string.title_install_status)
            Updater.install(this, apk)
            // Le résultat arrive au récepteur de diffusion.
        } catch (e: Exception) {
            onInstallFailed(e.message ?: "")
        }
    }

    /** Installation impossible : message, et le jeu reste accessible. */
    private fun onInstallFailed(message: String) {
        binding.titleProgress.isVisible = false
        binding.titleStatus.text =
            getString(R.string.title_install_failed, message)
        binding.updateButton.isEnabled = true
        binding.playButton.isEnabled = true
    }

    /** Entrée dans le jeu ; le titre ne revient pas par Retour. */
    private fun startGame() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(installReceiver)
        super.onDestroy()
    }

    companion object {
        /** Affichage minimum du titre : lisibilité de la marque. */
        private val MIN_DISPLAY_MS = 1200.milliseconds

        /**
         * Cap de la vérification de mise à jour : au-delà, on joue et
         * la réponse tardive n'est pas exploitée (décision d'eric).
         */
        private val CHECK_TIMEOUT_MS = 5000.milliseconds
    }
}
