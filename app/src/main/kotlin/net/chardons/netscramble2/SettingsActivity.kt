package net.chardons.netscramble2

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.chardons.netscramble2.audio.SoundMode
import net.chardons.netscramble2.databinding.ActivitySettingsBinding
import net.chardons.netscramble2.game.Skill
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * Page unique « Réglages » (refonte du 13 nov. 2026) : les réglages
 * qui vivaient en sous-menus en cascade du jeu — niveau de
 * difficulté, sons, animation — sont visibles d'un coup d'œil sur
 * une seule page, déroulante si besoin, et se changent directement.
 * Même patron que les records et l'aide : toolbar Material 3 +
 * ScrollView bord à bord.
 *
 * Sons et animation s'appliquent et se persistent immédiatement
 * ([Settings], mêmes clés et fichier historiques) — le jeu
 * rechargera le réglage à son retour (onStart). Le niveau de
 * difficulté, lui, RELANCE une partie : la protection du jeu est
 * conservée à l'identique — si une partie est en cours (coups
 * joués, non résolue), un dialogue de confirmation rappelle
 * l'avancement ; Annuler referme la page sans rien changer (la
 * coche revient au niveau courant), confirmer referme en renvoyant
 * le niveau choisi à MainActivity, qui lance la partie et montre
 * le rappel des règles des niveaux spéciaux.
 *
 * V3 (retour d'eric du 14 nov. 2026) :
 *  - BUG des valeurs non affichées corrigé : l'ancien helper
 *    cochait TOUJOURS le groupe du niveau, même pour passer un id
 *    de sons ou d'animation ; or RadioGroup.check ignore
 *    silencieusement tout id étranger au groupe, et les sections
 *    sons et animation s'ouvraient donc JAMAIS cochées. Chaque
 *    groupe reçoit désormais SA propre coche, AVANT l'écoute.
 *  - Nouvelle section « Langue » : l'application suit le système,
 *    ou force le français ou l'anglais. Le choix vit DANS appcompat
 *    (setApplicationLocales) : stocké par la bibliothèque avant
 *    Android 13 (autoStoreLocales, cf. manifeste) et porté par le
 *    réglage système « langue par application » ensuite ; la pile
 *    d'activités se recrée pour l'appliquer (le ViewModel du jeu
 *    survit). AUCUNE clé maison : une seule source de vérité.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Niveau de la partie en cours (coche initiale, retour à Annuler). */
    private var currentSkill: Skill = Skill.NORMAL

    /** Partie en cours : protège le changement de niveau. */
    private var moves = 0
    private var solved = false
    private var elapsedMs = 0L

    /** True pendant les coches programmatiques (pas d'écoute). */
    private var suppressSkillCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M6 : bord à bord — même disposition que les records et l'aide.
        EdgeToEdge.apply(window)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.settingsToolbar, top = true)
        EdgeToEdge.padSystemBars(binding.settingsScroll, bottom = true)
        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // État courant du jeu, passé par l'écran de jeu : la page
        // s'ouvre cochée sur les valeurs en vigueur.
        currentSkill = Skill.entries.firstOrNull {
            it.name == intent.getStringExtra(EXTRA_SKILL)
        } ?: Skill.NORMAL
        moves = intent.getIntExtra(EXTRA_MOVES, 0)
        solved = intent.getBooleanExtra(EXTRA_SOLVED, false)
        elapsedMs = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)

        // Coches initiales AVANT l'écoute : elles ne déclenchent
        // rien. V3 : CHAQUE groupe reçoit sa propre coche — l'ancien
        // helper cochait toujours le groupe du niveau et les ids
        // étrangers (sons, animation) étaient ignorés en silence.
        binding.skillGroup.check(skillRadioFor(currentSkill))
        binding.soundGroup.check(
            when (Settings.loadSoundMode(this)) {
                SoundMode.NONE -> R.id.sounds_off
                SoundMode.QUIET -> R.id.sounds_quiet
                SoundMode.FULL -> R.id.sounds_full
            }
        )
        binding.animGroup.check(
            if (Settings.loadAnimEnabled(this)) R.id.anim_on else R.id.anim_off
        )
        binding.langGroup.check(langRadioFor(currentLanguageTag()))

        // Niveau : protégé (relance une partie). Sons et animation :
        // choix immédiat, persisté aux clés historiques — MainActivity
        // relit au retour (onStart). Langue : appliquée par appcompat,
        // qui la stocke et recrée la pile pour l'appliquer.
        binding.skillGroup.setOnCheckedChangeListener { _, _ ->
            if (suppressSkillCheck) return@setOnCheckedChangeListener
            val picked = skillForChecked()
            if (picked != null && picked != currentSkill) {
                changeSkill(picked)
            }
        }
        binding.soundGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveSoundMode(
                this,
                when (checkedId) {
                    R.id.sounds_off -> SoundMode.NONE
                    R.id.sounds_quiet -> SoundMode.QUIET
                    else -> SoundMode.FULL
                }
            )
        }
        binding.animGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveAnimEnabled(this, checkedId == R.id.anim_on)
        }
        binding.langGroup.setOnCheckedChangeListener { _, checkedId ->
            applyLanguage(checkedId)
        }
    }

    /**
     * Changer de niveau relance une partie : plateau vierge ou déjà
     * gagné, rien à perdre — retour immédiat à l'écran de jeu, qui
     * lancera la nouvelle partie. Partie en cours : le MÊME dialogue
     * de confirmation que le jeu (avancement rappelé, Annuler ne
     * touche à rien et remet la coche sur le niveau courant).
     */
    private fun changeSkill(skill: Skill) {
        val apply = {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_SKILL, skill.name)
            )
            finish()
        }
        if (solved || moves == 0) {
            apply()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_change_title)
            .setMessage(
                getString(
                    R.string.confirm_progress_message, moves, formatTime(elapsedMs)
                )
            )
            .setPositiveButton(R.string.confirm_change_ok) { _, _ -> apply() }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // Annuler : le niveau courant ne bouge pas, la coche
                // revient dessus sans redéclencher l'écoute.
                checkSkillRadio(skillRadioFor(currentSkill))
            }
            .show()
    }

    /**
     * Coche programmatique du NIVEAU : n'écoute pas le groupe (ici
     * l'écoute est déjà posée — contrairement aux coches initiales
     * de onCreate, posées avant l'écoute). V3 : l'helper ne porte
     * plus QUE le niveau, son ancien nom laissait croire qu'il
     * cochait n'importe quel groupe.
     */
    private fun checkSkillRadio(id: Int) {
        suppressSkillCheck = true
        try {
            binding.skillGroup.check(id)
        } finally {
            suppressSkillCheck = false
        }
    }

    /** Niveau actuellement coché dans la section, null si aucun. */
    private fun skillForChecked(): Skill? = Skill.entries.firstOrNull {
        binding.skillGroup.checkedRadioButtonId == skillRadioFor(it)
    }

    private fun skillRadioFor(skill: Skill): Int = when (skill) {
        Skill.NOVICE -> R.id.skill_novice
        Skill.NORMAL -> R.id.skill_normal
        Skill.EXPERT -> R.id.skill_expert
        Skill.MASTER -> R.id.skill_master
        Skill.INSANE -> R.id.skill_insane
        Skill.ENDLESS -> R.id.skill_endless
        Skill.ABYSS -> R.id.skill_abyss
        Skill.VOID -> R.id.skill_void
    }

    /**
     * Langue en vigueur, telle qu'appcompat la connaît : le tag
     * court de la première langue appliquée (« fr », « en »), ou
     * une chaîne vide si l'application suit le système. Source de
     * vérité UNIQUE du réglage : le stockage appcompat (restitué au
     * démarrage, avant tout affichage) — la page n'a PAS de clé
     * maison qui pourrait diverger de la langue réellement rendue.
     */
    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else locales[0]?.language ?: ""
    }

    /** Radio de la langue en vigueur (suivi du système par défaut). */
    private fun langRadioFor(tag: String): Int = when (tag) {
        "fr" -> R.id.lang_fr
        "en" -> R.id.lang_en
        else -> R.id.lang_system
    }

    /**
     * Applique la langue choisie : appcompat stocke le choix et
     * recrée la pile d'activités pour l'appliquer partout (toutes
     * nos activités sont des AppCompatActivity, le ViewModel du jeu
     * survit à la recréation). Liste VIDE = suivre la langue du
     * système, aucun forçage.
     */
    private fun applyLanguage(checkedId: Int) {
        val locales = when (checkedId) {
            R.id.lang_fr -> LocaleListCompat.forLanguageTags("fr")
            R.id.lang_en -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Même format de temps que la barre d'état du jeu (mm:ss). */
    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        /** Niveau courant de la partie, puis niveau CHOISI au retour. */
        const val EXTRA_SKILL = "skill"

        /** Coups joués de la partie en cours (protection du niveau). */
        const val EXTRA_MOVES = "moves"

        /** Partie résolue (pas de confirmation à donner dans ce cas). */
        const val EXTRA_SOLVED = "solved"

        /** Temps écoulé de la partie en cours (dialogue). */
        const val EXTRA_ELAPSED_MS = "elapsedMs"
    }
}
