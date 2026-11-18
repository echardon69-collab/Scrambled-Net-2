package net.chardons.netscramble2

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.LocaleListCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import net.chardons.netscramble2.audio.SoundMode
import net.chardons.netscramble2.databinding.ActivitySettingsBinding
import net.chardons.netscramble2.game.Screen
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
 *
 * V4 (retour d'eric du 14 nov. 2026, nouveau sujet) : sous chaque
 * niveau, le joueur voit la TAILLE RÉELLE du plateau sur cet écran
 * et les règles qui distinguent le niveau. La taille suit la même
 * matrice Screen que le jeu (chantier « tailles fidèles » : les
 * mêmes metrics, le même prédicat d'orientation que
 * MainActivity.boardSize, la famille Infini y passe aussi — sa
 * taille y est fixe 40 x 40) ; les règles sont des strings par
 * niveau, EN et FR, avec la taille en arguments de format.
 *
 * V5 (chantier Aide à la résolution, 15 nov. 2026) : deux nouvelles
 * sections — « Indice » (stratégie SIGNAL ou FIX ; plafond de 0
 * (désactivé) à 50 pour cent des tuiles mal orientées, par pas de 5,
 * les libellés des pourcentages posés au runtime) et
 * « Démonstration » (difficulté fixe, aléatoire ou progressive,
 * niveau de départ, zoom, rythme). Choix immédiats, persistés par
 * [Settings] (mêmes clés et fichier historiques) ; le jeu relit le
 * plafond à chaque nouvelle partie, la démonstration ses réglages à
 * chaque lancement.
 *
 * V6 (chantier « sélecteur de langue dynamique », 16 nov. 2026) :
 *  les radios des langues ne sont plus figées dans le layout — elles
 *  naissent AU RUNTIME depuis [AppLanguages], une par langue du jeu,
 *  libellée par son endonyme (« Français », « English », « Corsu »,
 *  « Elsässisch ») ; seule « Suivre le système » reste statique. Une
 *  traduction neuve (corse v17, alsacien v18…) devient sélectionnable
 *  sans toucher à la page ; le tag COMPLET (gsw-FR, jamais « gsw »
 *  seul) fait foi dans la correspondance radio <-> locale.
 *
 * V7 (chantier « drapeaux du sélecteur », Lot 15 du 16 nov. 2026) :
 *  chaque radio de langue porte le DRAPEAU choisi par eric — France,
 *  Union Jack, tête de Maure corse, Alsace historique, Chine — à gauche de
 *  son endonyme (drawable 15 dp de haut, régénéré par tools/flags/
 *  convert-flags.sh depuis svg/flags). La correspondance langue ->
 *  drawable est EXPLICITE ([langFlagFor]) : pas de getIdentifier
 *  (réflexion, et le tag « gsw-FR » n'est pas un nom de ressource
 *  Android légal pour une recherche dynamique) — une langue ajoutée
 *  sans drapeau reste affichée, simplement nue.
 *
 * V21 (chantier « feuille de langue », 18 nov. 2026, demande
 *  d'eric : l'app agrandit sa famille de langues, la page ne peut
 *  plus scroller d'une radio par langue) : la section Langue ne
 *  garde qu'UNE entrée résumé — libellé et drapeau de la langue en
 *  vigueur, ou « Suivre le système » — qui ouvre une FEUILLE
 *  Material 3 ([showLangSheet], BottomSheetDialog, layout
 *  sheet_langs.xml) plafonnée à 360 dp : au-delà, c'est la feuille
 *  qui déroule en interne, jamais la page. « Suivre le système »
 *  reste en TÊTE de la feuille ; les langues suivent dans l'ordre
 *  d'affichage d'[AppLanguages]. Le REGISTRE d'ids de la v19
 *  disparaît : chaque item porte son tag par lambda, et la coche
 *  initiale compare au tag complet renvoyé par appcompat (aucune
 *  clé maison, aucun état parallèle). NOUVEAU, demande d'eric :
 *  pour une langue SECONDAIRE d'un pays (corse, alsacien, futur
 *  breton, créoles…), le DRAPEAU NATIONAL s'affiche entre
 *  parenthèses après l'endonyme (« Corsu (drapeau de la France) »),
 *  champ drapeauPays d'AppLanguage, inséré dans le libellé par
 *  ImageSpan ([endonymAvecPays]).
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
        // V21 : la section Langue ne garde qu'une entrée résumé,
        // reliée à l'état RÉEL d'appcompat — le libellé et le
        // drapeau de la langue en vigueur, ou « Suivre le
        // système » si l'app n'force rien (aucune clé maison) ;
        // l'entrée ouvre la feuille Material 3 au toucher.
        bindLangEntry(currentLanguageTag())

        // V5 : sections « Indice » et « Démonstration » — mêmes coches
        // initiales AVANT l'écoute (patron V3), puis libellés du
        // plafond posés au runtime (une seule string à argument).
        binding.hintStrategyGroup.check(
            if (Settings.loadHintStrategy(this) == HintStrategy.FIX) {
                R.id.hint_strategy_fix
            } else {
                R.id.hint_strategy_signal
            }
        )
        binding.hintLimitGroup.check(hintLimitRadioFor(Settings.loadHintPercent(this)))
        bindHintLimitRadios()
        binding.demoDiffGroup.check(
            when (Settings.loadDemoDifficulty(this)) {
                DemoDifficulty.RANDOM -> R.id.demo_diff_random
                DemoDifficulty.PROGRESSIVE -> R.id.demo_diff_progressive
            }
        )
        // Niveaux COCHÉS (v8) : la coche précède l'écoute ci-dessous,
        // rien n'est persisté au seul chargement de la page.
        val demoSkills = Settings.loadDemoSkillSet(this)
        for (cb in demoSkillCheckBoxes()) {
            cb.isChecked = demoSkills.contains(cb.skill())
        }
        refreshDemoDifficultyAvailability()
        binding.demoSpeedGroup.check(
            when (Settings.loadDemoSpeed(this)) {
                DemoSpeed.SLOW -> R.id.demo_speed_slow
                DemoSpeed.NORMAL -> R.id.demo_speed_normal
                DemoSpeed.FAST -> R.id.demo_speed_fast
                // Exhaustif : une sauvegarde ancienne qui désigne
                // l'Éclair (v5, retiré) retombe sur NORMAL en amont,
                // dans Settings.loadDemoSpeed — pas de else redondant.
            }
        )

        // Descriptions des niveaux (v4) : taille réelle de l'écran
        // + règles propres à chaque niveau.
        bindSkillDescriptions()

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
        binding.langCurrent.setOnClickListener { showLangSheet() }
        // V5 : indice et démonstration — choix immédiats, persistés
        // par Settings (mêmes clés et fichier historiques).
        binding.hintStrategyGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveHintStrategy(
                this,
                if (checkedId == R.id.hint_strategy_fix) HintStrategy.FIX
                else HintStrategy.SIGNAL
            )
        }
        binding.hintLimitGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveHintPercent(this, hintPercentFor(checkedId))
        }
        binding.demoDiffGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveDemoDifficulty(
                this,
                when (checkedId) {
                    R.id.demo_diff_random -> DemoDifficulty.RANDOM
                    else -> DemoDifficulty.PROGRESSIVE
                }
            )
        }
        for (cb in demoSkillCheckBoxes()) {
            cb.setOnCheckedChangeListener { _, _ -> persistDemoSkillSet(cb) }
        }
        binding.demoSpeedGroup.setOnCheckedChangeListener { _, checkedId ->
            Settings.saveDemoSpeed(
                this,
                when (checkedId) {
                    R.id.demo_speed_slow -> DemoSpeed.SLOW
                    R.id.demo_speed_fast -> DemoSpeed.FAST
                    else -> DemoSpeed.NORMAL
                }
            )
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
     * Remplit les 8 descriptions de niveaux (v4) : la TAILLE RÉELLE
     * du plateau sur cet écran — matrice Screen du chantier « tailles
     * fidèles », les mêmes metrics et le même prédicat d'orientation
     * que MainActivity.boardSize (la famille Infini y passe aussi :
     * sa taille y est fixe) — préfixée aux règles du niveau (branches,
     * tore, câbles invisibles, brouillard). L'alignement de chaque
     * description sur le LIBELLÉ de sa radio se lit au runtime
     * (compoundPaddingStart) : la largeur de l'indicateur Material
     * varie avec le thème et la densité — rien de retapé, même
     * philosophie que les insets EdgeToEdge.
     */
    private fun bindSkillDescriptions() {
        val metrics = resources.displayMetrics
        val landscape = metrics.widthPixels > metrics.heightPixels
        val screen = Screen.find(metrics.widthPixels, metrics.heightPixels)
        for (skill in Skill.entries) {
            val (width, height) = screen.boardSize(skill, landscape)
            val (radio, desc) = skillViewsFor(skill)
            desc.text = getString(skillDescFor(skill), width, height)
            desc.setPaddingRelative(radio.compoundPaddingStart, 0, 0, 0)
        }
    }

    /** Radio et description d'un niveau (liaisons ViewBinding). */
    private fun skillViewsFor(skill: Skill): Pair<MaterialRadioButton, TextView> =
        when (skill) {
            Skill.NOVICE -> binding.skillNovice to binding.skillNoviceDesc
            Skill.NORMAL -> binding.skillNormal to binding.skillNormalDesc
            Skill.EXPERT -> binding.skillExpert to binding.skillExpertDesc
            Skill.MASTER -> binding.skillMaster to binding.skillMasterDesc
            Skill.INSANE -> binding.skillInsane to binding.skillInsaneDesc
            Skill.ENDLESS -> binding.skillEndless to binding.skillEndlessDesc
            Skill.ABYSS -> binding.skillAbyss to binding.skillAbyssDesc
            Skill.VOID -> binding.skillVoid to binding.skillVoidDesc
        }

    /** Texte de description du niveau : la taille en arguments. */
    private fun skillDescFor(skill: Skill): Int = when (skill) {
        Skill.NOVICE -> R.string.skill_novice_desc
        Skill.NORMAL -> R.string.skill_normal_desc
        Skill.EXPERT -> R.string.skill_expert_desc
        Skill.MASTER -> R.string.skill_master_desc
        Skill.INSANE -> R.string.skill_insane_desc
        Skill.ENDLESS -> R.string.skill_endless_desc
        Skill.ABYSS -> R.string.skill_abyss_desc
        Skill.VOID -> R.string.skill_void_desc
    }

    /**
     * Langue en vigueur, telle qu'appcompat la connaît : le tag
     * COMPLET de la première locale appliquée (« fr », « en »,
     * « gsw-FR »), ou une chaîne vide si l'application suit le
     * système. Source de vérité UNIQUE du réglage : le stockage
     * appcompat (restitué au démarrage, avant tout affichage) — la
     * page n'a PAS de clé maison qui pourrait diverger de la langue
     * réellement rendue. V6 : le tag complet fait foi — la langue
     * seule (« gsw ») matcherait l'alémanique suisse autant que
     * l'alsacien.
     */
    private fun currentLanguageTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) "" else locales[0]?.toLanguageTag() ?: ""
    }

    /**
     * V21 : relie l'entrée résumé de la section Langue (une seule
     * ligne dans la page) à l'état RÉEL d'appcompat — seule source
     * de vérité, aucune clé maison. Tag vide ou inconnu ->
     * « Suivre le système » (texte seul, la pile de locales est
     * vide ou parle une langue non listée) ; sinon l'endonyme de
     * la langue, portant son drapeau à gauche et, pour une langue
     * secondaire d'un pays, le drapeau NATIONAL entre parenthèses
     * ([endonymAvecPays]).
     */
    private fun bindLangEntry(tag: String) {
        val lang = AppLanguages.byTag(tag)
        if (lang == null) {
            binding.langCurrent.text = getString(R.string.lang_system)
            binding.langCurrent.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, 0, 0
            )
        } else {
            binding.langCurrent.text = endonymAvecPays(lang)
            // Surcharge (Int, Int, Int, Int) : ids de ressources,
            // 0 = pas de drawable (la surcharge Drawable prend des
            // objets, pas des ids — null y est réservé).
            val flag = langFlagFor(lang.tag)
            if (flag != 0) {
                binding.langCurrent.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    flag, 0, 0, 0
                )
                // 8 dp -> pixels : la ressource n'existe pas pour
                // un drawable posé au runtime, conversion inline.
                binding.langCurrent.compoundDrawablePadding =
                    (8 * resources.displayMetrics.density).toInt()
            }
        }
    }

    /**
     * V21 : la feuille Material 3 qui remplace la liste de radios
     * (demande d'eric : la page prévoit de NOMBREUSES langues). Le
     * ScrollView de sheet_langs.xml plafonne la feuille à 360 dp —
     * au-delà, c'est la feuille qui déroule en interne, jamais la
     * page. « Suivre le système » reste en TÊTE, puis les langues
     * dans l'ordre d'affichage d'[AppLanguages.ALL]. Choisir
     * applique la langue (appcompat stocke et recrée la pile pour
     * l'appliquer partout) et referme la feuille.
     */
    private fun showLangSheet() {
        val sheet = BottomSheetDialog(this)
        // Racine NON NULLE (lint : elle résout les LayoutParams du
        // LinearLayout racine de la feuille — une racine null les
        // perdrait) mais attachToRoot FALSE : la feuille ne
        // s'attache PAS à l'activité, elle attend le setContentView
        // du BottomSheetDialog qui la coiffe de ses propres params.
        val contenu = layoutInflater.inflate(
            R.layout.sheet_langs, binding.root, false
        )
        val liste = contenu.findViewById<LinearLayout>(R.id.lang_sheet_list)
        val courant = currentLanguageTag()
        liste.addView(
            langSheetItem(
                liste, getString(R.string.lang_system), 0, courant.isEmpty()
            ) {
                applyLanguage("")
                sheet.dismiss()
            }
        )
        AppLanguages.ALL.forEach { lang ->
            liste.addView(
                langSheetItem(
                    liste, endonymAvecPays(lang), langFlagFor(lang.tag),
                    courant == lang.tag
                ) {
                    applyLanguage(lang.tag)
                    sheet.dismiss()
                }
            )
        }
        sheet.setContentView(contenu)
        sheet.show()
    }

    /**
     * V21 : un item de la feuille — MaterialRadioButton gonflé
     * depuis item_lang_radio.xml : libellé, drapeau à gauche,
     * coche si la langue est celle en vigueur, et le choix
     * appliqué au toucher. AttachToRoot FALSE puis addView, piège
     * conservé de la correction du 17 nov. 2026 (il tient toujours
     * dans la feuille) : avec TRUE, LayoutInflater.inflate RENVOIE
     * LE PARENT (la liste), et le cast en MaterialRadioButton
     * plantait en ClassCastException. Avec FALSE, inflate renvoie
     * bien la racine gonflée avec ses LayoutParams DÉRIVÉS du
     * parent passé en racine ([racine]) — le passer à null les
     * ferait
     * disparaître, et addView y poserait les défauts du
     * LinearLayout (hauteur match_parent).
     */
    private fun langSheetItem(
        racine: LinearLayout, libelle: CharSequence, drapeau: Int,
        coche: Boolean, choix: () -> Unit
    ): MaterialRadioButton {
        val item = layoutInflater.inflate(
            R.layout.item_lang_radio, racine, false
        ) as MaterialRadioButton
        item.text = libelle
        item.isChecked = coche
        if (drapeau != 0) {
            // V7 : drapeau de la langue, à gauche du libellé.
            item.setCompoundDrawablesRelativeWithIntrinsicBounds(
                drapeau, 0, 0, 0
            )
            // 8 dp -> pixels : la ressource n'existe pas pour
            // un drawable posé au runtime, conversion inline.
            item.compoundDrawablePadding =
                (8 * resources.displayMetrics.density).toInt()
        }
        item.setOnClickListener { choix() }
        return item
    }

    /**
     * V21 (demande d'eric) : le libellé d'une langue dans la
     * feuille — l'endonyme puis, pour une langue SECONDAIRE d'un
     * pays (corse, alsacien, futur breton, créoles…), le DRAPEAU
     * NATIONAL entre parenthèses (« Corsu (drapeau de la
     * France) ») : il situe d'un coup d'œil les langues qui
     * partagent un pays. Le drapeau s'insère DANS le texte par
     * ImageSpan : l'alignement de (DynamicDrawableSpan)
     * ALIGN_CENTER n'existe qu'à partir de l'API 29, minSdk 24
     * impose le constructeur simple (alignement bas). Le drapeau
     * garde la HAUTEUR uniforme de 15 dp des drapeaux composés,
     * largeur au ratio intrinsèque ; plus petit que la ligne de
     * texte, il n'épaissit pas la rangée.
     */
    private fun endonymAvecPays(lang: AppLanguage): CharSequence {
        val pays = lang.drapeauPays ?: return lang.endonym
        val flag = langFlagFor(pays)
        if (flag == 0) return lang.endonym
        val drapeau = AppCompatResources.getDrawable(this, flag)
            ?: return lang.endonym
        val dens = resources.displayMetrics.density
        val h = (15 * dens).toInt()
        drapeau.setBounds(
            0, 0, drapeau.intrinsicWidth * h / drapeau.intrinsicHeight, h
        )
        val sb = SpannableStringBuilder(lang.endonym)
        sb.append(" (")
        val debut = sb.length
        sb.append(" ")
        sb.setSpan(ImageSpan(drapeau), debut, sb.length,
                   Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(")")
        return sb
    }

    /**
     * V7 : drawable du drapeau porté par une langue du sélecteur
     * (et, via [endonymAvecPays], par le suffixe de drapeau
     * national des langues secondaires d'un pays — langFlagFor("fr")
     * EST le drapeau de la France). Correspondance EXPLICITE (voir
     * l'en-tête V7) : le tag complet fait foi, inconnu -> 0, l'item
     * s'affiche alors sans drapeau. RÉUTILISÉE par la feuille V21 :
     * les items nés au runtime la consultent comme les radios
     * d'antan.
     */
    private fun langFlagFor(tag: String): Int = when (tag) {
        "fr" -> R.drawable.flag_fr
        "en" -> R.drawable.flag_en
        "co" -> R.drawable.flag_co
        "gsw-FR" -> R.drawable.flag_gsw_fr
        "zh-CN" -> R.drawable.flag_zh
        "es" -> R.drawable.flag_es
        "pt" -> R.drawable.flag_pt
        "it" -> R.drawable.flag_it
        else -> 0
    }

    /**
     * Applique la langue choisie DANS LA FEUILLE, par son tag
     * COMPLET (gsw-FR jamais « gsw » seul) : liste vide = « Suivre
     * le système », sans forçage ; appcompat stocke le choix
     * (autoStoreLocales avant Android 13, réglage système « langue
     * par application » ensuite) et recrée la pile d'activités pour
     * l'appliquer partout — toutes nos activités sont des
     * AppCompatActivity, le ViewModel du jeu survit à la recréation,
     * et la feuille se referme sans attendre.
     */
    private fun applyLanguage(tag: String) {
        val locales = if (tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** Radio du plafond d'indices pour un pourcentage (0 sinon). */
    private fun hintLimitRadioFor(percent: Int): Int = when (percent) {
        // V11 : le cadre validé ne propose plus que 0/10/20.
        10 -> R.id.hint_limit_10
        20 -> R.id.hint_limit_20
        else -> R.id.hint_limit_0
    }

    /** Pourcentage porté par une radio du plafond (0 si inconnu). */
    private fun hintPercentFor(checkedId: Int): Int = when (checkedId) {
        // V11 : Désactivé/10/20, rien d'autre.
        R.id.hint_limit_10 -> 10
        R.id.hint_limit_20 -> 20
        else -> 0
    }

    /**
     * Libellés du plafond d'indices (v5) : une seule string à
     * argument (hint_limit_value, « n % »), posée au runtime sur
     * les radios 10 et 20 (v11 : les seules proposées avec
     * Désactivé) — la radio 0 porte son propre libellé
     * (« Désactivé ») en XML.
     */
    private fun bindHintLimitRadios() {
        for (percent in listOf(10, 20)) {
            val radio = binding.hintLimitGroup.findViewById<MaterialRadioButton>(
                hintLimitRadioFor(percent)
            )
            radio.text = getString(R.string.hint_limit_value, percent)
        }
    }

    /** Les 8 CASES de niveaux de la démonstration (v8). */
    private fun demoSkillCheckBoxes(): List<CompoundButton> = listOf(
        binding.demoSkillNovice, binding.demoSkillNormal,
        binding.demoSkillExpert, binding.demoSkillMaster,
        binding.demoSkillInsane, binding.demoSkillEndless,
        binding.demoSkillAbyss, binding.demoSkillVoid
    )

    /** Niveau porté par une case de la démonstration. */
    private fun CompoundButton.skill(): Skill = when (id) {
        R.id.demo_skill_novice -> Skill.NOVICE
        R.id.demo_skill_normal -> Skill.NORMAL
        R.id.demo_skill_expert -> Skill.EXPERT
        R.id.demo_skill_master -> Skill.MASTER
        R.id.demo_skill_insane -> Skill.INSANE
        R.id.demo_skill_endless -> Skill.ENDLESS
        R.id.demo_skill_abyss -> Skill.ABYSS
        R.id.demo_skill_void -> Skill.VOID
        else -> Skill.NOVICE
    }

    /**
     * Persiste le masque des niveaux cochés. La DERNIÈRE coche ne
     * se décoche jamais (retour à minima un niveau, v8) : la case
     * se recoche d'elle-même ; les radios de difficulté suivent.
     */
    private fun persistDemoSkillSet(changed: CompoundButton) {
        val checked = demoSkillCheckBoxes().filter { it.isChecked }.map { it.skill() }
        if (checked.isEmpty()) {
            changed.isChecked = true
            return
        }
        Settings.saveDemoSkillSet(this, checked)
        refreshDemoDifficultyAvailability()
    }

    /**
     * Une seule case cochée revient à un niveau fixe : les radios
     * Aléatoire/Progressif gardent leur valeur visible mais se
     * grisent (v8).
     */
    private fun refreshDemoDifficultyAvailability() {
        val multi = demoSkillCheckBoxes().count { it.isChecked } >= 2
        binding.demoDiffRandom.isEnabled = multi
        binding.demoDiffProgressive.isEnabled = multi
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
