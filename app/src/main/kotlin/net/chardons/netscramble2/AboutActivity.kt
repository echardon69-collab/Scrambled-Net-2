package net.chardons.netscramble2

import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import net.chardons.netscramble2.databinding.ActivityAboutBinding
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * Page autonome « À propos » (refonte du 14 nov. 2026) : la
 * fenêtre de dialogue de l'original devient une page au patron
 * maison des Records, de l'Aide et des Réglages — toolbar
 * Material 3 + ScrollView bord à bord. Le contenu vient des
 * tableaux about_titles / about_texts (EN + FR), comme l'aide le
 * fait depuis help_titles / help_texts.
 *
 * Sept sections : le jeu ; origine et licence (chaîne complète
 * KNetWalk, Scrambled Net, Scrambled Net 2) ; participer
 * (signaler un bug, contacter l'auteur, proposer une traduction,
 * proposer un skin — les adresses réelles ne sont pas encore
 * disponibles, chaque proposition affiche « lien à venir », voir
 * l'évolution « Renseigner les liens de À propos » de la
 * feuille de route) ; puis les quatre listes de fonctionnalités
 * par rapport au jeu d'origine : nouvelles, modifiées,
 * conservées, retirées (et pourquoi).
 *
 * La version de l'application s'affiche en tête, posée au
 * runtime par le même canal que la page titre (title_version +
 * Updater.currentVersion) — jamais codée dans les strings, qui
 * changeraient à chaque livraison.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M6 : bord à bord — même disposition que l'aide.
        EdgeToEdge.apply(window)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.aboutToolbar, top = true)
        EdgeToEdge.padSystemBars(binding.aboutScroll, bottom = true)
        setSupportActionBar(binding.aboutToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Version en tête, lue au runtime comme sur la page
        // titre : une string figée deviendrait fausse à la
        // prochaine livraison.
        val version = Updater.currentVersion(this)
        binding.aboutContent.addView(sectionTitle(getString(R.string.app_name)))
        binding.aboutContent.addView(sectionText(getString(R.string.title_version, version)))

        val titles = resources.getStringArray(R.array.about_titles)
        val texts = resources.getStringArray(R.array.about_texts)
        for (i in titles.indices) {
            binding.aboutContent.addView(sectionTitle(titles[i]))
            binding.aboutContent.addView(sectionText(texts.getOrElse(i) { "" }))
        }
    }

    private fun sectionTitle(text: String): TextView =
        TextView(this).applyTextAppearance(MaterialR.attr.textAppearanceTitleMedium).apply {
            this.text = text
            setPadding(0, dp(16), 0, dp(4))
        }

    private fun sectionText(text: String): TextView =
        TextView(this).applyTextAppearance(MaterialR.attr.textAppearanceBodyMedium).apply {
            this.text = text
        }

    /**
     * Applique un attribut de typographie "Material 3" résolu via le thème
     * (cohérent en jour comme en nuit, sans référence directe à un style).
     */
    private fun TextView.applyTextAppearance(attr: Int): TextView {
        val value = TypedValue()
        if (theme.resolveAttribute(attr, value, true) && value.resourceId != 0) {
            setTextAppearance(value.resourceId)
        }
        return this
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
