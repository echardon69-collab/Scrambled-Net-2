package net.chardons.netscramble2

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import net.chardons.netscramble2.databinding.ActivityLessonsListBinding
import net.chardons.netscramble2.ui.EdgeToEdge

/**
 * LISTE des leçons du mode Apprentissage (chantier du 17 nov.
 * 2026) : les sept leçons dans l'ordre validé, chacune son titre,
 * la coche « Terminée » à côté de celles déjà faites — persistée
 * ([Settings]), relue à chaque retour : finir une leçon puis
 * revenir la montre cochée. Les leçons sont REJOUABLES à volonté
 * : une leçon terminée reste ouverte, la coche ne verrouille rien.
 *
 * Patron de ScoresActivity : toolbar avec retour, contenu en
 * ScrollView, lignes construites en code — les leçons vivent
 * dans [Lessons.ALL], la liste ne fait que les montrer.
 */
class LessonsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLessonsListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.apply(window)
        binding = ActivityLessonsListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.padSystemBars(binding.lessonsToolbar, top = true)
        EdgeToEdge.padSystemBars(binding.lessonsScroll, bottom = true)
        setSupportActionBar(binding.lessonsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fillRows()
    }

    override fun onStart() {
        super.onStart()
        // La coche d'une leçon finie pendant que la liste était en
        // arrière-plan : la liste se repeuple au retour.
        fillRows()
    }

    /**
     * Titre AFFICHÉ d'une leçon : [Lesson.titleId] porte un
     * IDENTIFIANT de chaîne (Lessons.kt est du pur Kotlin, il ne
     * peut pas référencer R.string) — la résolution identifiant
     * vers ressource vit ici, même when que les bulles de
     * LessonActivity (validate_apprentissage épingle la
     * complétude : les sept titres ont leur branche).
     */
    private fun titleText(lesson: Lesson): String = getString(when (lesson.titleId) {
        "lesson_1_title" -> R.string.lesson_1_title
        "lesson_2_title" -> R.string.lesson_2_title
        "lesson_3_title" -> R.string.lesson_3_title
        "lesson_4_title" -> R.string.lesson_4_title
        "lesson_5_title" -> R.string.lesson_5_title
        "lesson_6_title" -> R.string.lesson_6_title
        "lesson_7_title" -> R.string.lesson_7_title
        else -> R.string.menu_lessons
    })

    /** Repeuple les lignes : titre, coche, clic vers la leçon. */
    private fun fillRows() {
        val rows = binding.lessonRows
        rows.removeAllViews()
        val done = Settings.loadLessonsDoneMask(this)
        for ((index, lesson) in Lessons.ALL.withIndex()) {
            rows.addView(row(index, lesson, done))
        }
    }

    /**
     * Une ligne : le titre de la leçon, la coche « Terminée » si
     * le bit du masque est posé (second TextView, à droite), le
     * clic ouvre la leçon (la coche ne verrouille JAMAIS une
     * leçon déjà faite : elle se rejoue).
     */
    private fun row(index: Int, lesson: Lesson, doneMask: Int): LinearLayout {
        val context = this
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(
            resources.getDimensionPixelSize(R.dimen.lesson_row_padding),
            resources.getDimensionPixelSize(R.dimen.lesson_row_padding_vertical),
            resources.getDimensionPixelSize(R.dimen.lesson_row_padding),
            resources.getDimensionPixelSize(R.dimen.lesson_row_padding_vertical)
        )
        row.isClickable = true
        row.isFocusable = true
        val title = TextView(context)
        // Apparence en code, patron de ScoresActivity.cell : une
        // ressource attr ne s'installe PAS par setTextAppearance
        // (qui veut un STYLE, piège lint du correctif v2).
        title.textSize = 16f
        title.text = titleText(lesson)
        title.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        row.addView(title)
        if ((doneMask shr index) and 1 == 1) {
            val done = TextView(context)
            done.textSize = 14f
            done.text = getString(R.string.lesson_completed)
            row.addView(done)
        }
        row.setOnClickListener {
            startActivity(
                Intent(this, LessonActivity::class.java)
                    .putExtra(LessonActivity.EXTRA_ORDINAL, index)
            )
        }
        return row
    }
}
