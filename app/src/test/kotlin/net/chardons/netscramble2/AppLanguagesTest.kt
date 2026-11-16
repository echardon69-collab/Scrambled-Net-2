package net.chardons.netscramble2

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des données du sélecteur de langue (chantier « sélecteur de
 * langue dynamique », v19 du 16 nov. 2026). Les données sont aussi
 * épinglées par validate_selecteur.py, qui vérifie en plus leur
 * cohérence avec locales_config.xml — jamais l'une sans l'autre :
 * le validateur sur les sources, ces tests sur le comportement.
 */
class AppLanguagesTest {

    @Test
    fun listsAllFourLanguagesInDisplayOrder() {
        // Ordre d'affichage du sélecteur : continuité de la page v3
        // (Français avant English), puis les langues ajoutées.
        assertEquals(
            listOf("fr", "en", "co", "gsw-FR"),
            AppLanguages.ALL.map { it.tag }
        )
        assertEquals(
            listOf("Français", "English", "Corsu", "Elsässisch"),
            AppLanguages.ALL.map { it.endonym }
        )
    }

    @Test
    fun tagsAreValidBcp47() {
        // Chaque tag se laisse lire par le moteur de locales — un
        // tag mal formé casserait forLanguageTags silencieusement.
        AppLanguages.ALL.forEach { lang ->
            val locale = Locale.forLanguageTag(lang.tag)
            assertTrue("tag illisible : " + lang.tag,
                       locale.language.isNotEmpty())
        }
    }

    @Test
    fun alsatianTagCarriesItsRegion() {
        // Piège v18 : l'alsacien voyage sous gsw-FR, JAMAIS « gsw »
        // seul — la langue sans région matcherait aussi l'alémanique
        // suisse, et la radio ne cocherait plus la locale posée.
        val alsace = AppLanguages.byTag("gsw-FR")
        assertEquals("Elsässisch", alsace?.endonym)
        assertNull(AppLanguages.byTag("gsw"))
    }

    @Test
    fun byTagIsExactMatchOnly() {
        // La correspondance est EXACTE, sur le tag complet : ni la
        // langue seule, ni la casse, ni le vide ne matchent.
        assertEquals("Français", AppLanguages.byTag("fr")?.endonym)
        assertNull(AppLanguages.byTag(""))
        assertNull(AppLanguages.byTag("zz"))
        assertNull(AppLanguages.byTag("FR"))
        assertNull(AppLanguages.byTag("fr-FR"))
    }

    @Test
    fun endonymsAreNotBlankAndDistinct() {
        val endonyms = AppLanguages.ALL.map { it.endonym }
        assertTrue(endonyms.none { it.isBlank() })
        assertEquals(endonyms.size, endonyms.toSet().size)
    }
}
