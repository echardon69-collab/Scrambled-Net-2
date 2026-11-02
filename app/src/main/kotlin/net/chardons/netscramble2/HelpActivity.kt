package net.chardons.netscramble2

import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.R as MaterialR
import net.chardons.netscramble2.databinding.ActivityHelpBinding
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * Manuel du jeu : sections « titre + texte » lues depuis les tableaux
 * help_titles / help_texts (EN + FR), comme l'écran d'aide de
 * l'original (HelpActivity de Hermit + R.array), en Material 3.
 * Le contenu décrit le jeu tel qu'il est aujourd'hui : commandes
 * tactiles, verrouillage, niveaux (bords qui se referment, cellules
 * invisibles), records et réglages.
 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M6 : bord à bord — même disposition que les records.
        EdgeToEdge.apply(window)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.helpToolbar, top = true)
        EdgeToEdge.padSystemBars(binding.helpScroll, bottom = true)
        setSupportActionBar(binding.helpToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val titles = resources.getStringArray(R.array.help_titles)
        val texts = resources.getStringArray(R.array.help_texts)
        for (i in titles.indices) {
            binding.helpContent.addView(sectionTitle(titles[i]))
            binding.helpContent.addView(sectionText(texts.getOrElse(i) { "" }))
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
