package net.chardons.netscramble2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests de péremption des versions de développement (Lot 17, v22
 * du 17 nov. 2026). Décisions d'eric : grâce de 5 jours, écran A
 * (blocage pur + Quitter), majeure 0 = pré-release limitée,
 * message avec phrase d'accompagnement. validate_peremption.py
 * épingle les sources ; ces tests épingle le COMPORTEMENT — jamais
 * l'un sans l'autre.
 *
 * Propriété clef de tout le lot : les dates ISO « yyyy-MM-dd »
 * zéro-paddées se comparent LEXICALEMENT comme chronologiquement,
 * c'est ce qui autorise estPerimee à ne jamais convertir.
 */
class PeremptionTest {

    // --- Décision 3 : règle du numéro --------------------------------

    @Test
    fun majeureZeroEstPreRelease() {
        assertTrue(Peremption.estPreRelease("0.1.0"))
        assertTrue(Peremption.estPreRelease("0.10.3"))
    }

    @Test
    fun releasesOfficiellesNeSontPasPreRelease() {
        assertFalse(Peremption.estPreRelease("1.0.0"))
        assertFalse(Peremption.estPreRelease("10.2.0"))
    }

    @Test
    fun versionEntoureeDEspacesEstTrimmed() {
        assertTrue(Peremption.estPreRelease(" 0.2.0 "))
        assertFalse(Peremption.estPreRelease(" 1.0.0 "))
    }

    // --- Décision 1 : grâce de 5 jours, rollovers réels ---------------

    @Test
    fun graceDeCinqJoursSansRollover() {
        assertEquals("2026-11-22", Peremption.dateLimite("2026-11-17"))
    }

    @Test
    fun rolloverDeMois() {
        assertEquals("2026-12-03", Peremption.dateLimite("2026-11-28"))
    }

    @Test
    fun rolloverDAnnee() {
        assertEquals("2027-01-04", Peremption.dateLimite("2026-12-30"))
    }

    @Test
    fun fevrierBissextile() {
        // 2028 : bissextile (29 jours) — 26 + 5 = 29 + 2.
        assertEquals("2028-03-02", Peremption.dateLimite("2028-02-26"))
    }

    @Test
    fun fevrierNonBissextile() {
        // 2027 : 28 jours — 26 + 5 = 28 + 5.
        assertEquals("2027-03-03", Peremption.dateLimite("2027-02-26"))
    }

    @Test
    fun dateNonIsoRefuseeParException() {
        listOf("17-11-2026", "2026/11/17", "20261117",
               "2026-13-01", "2026-02-30", "").forEach { invalide ->
            try {
                Peremption.dateLimite(invalide)
                fail("Attendu IllegalArgumentException : « $invalide »")
            } catch (_: IllegalArgumentException) {
                // Refusé, comme voulu.
            }
        }
    }

    @Test
    fun dateLimiteDeNullPourUneReleaseOfficielle() {
        assertNull(Peremption.dateLimiteDe("1.0.0", "2026-11-17"))
        assertEquals("2026-11-22",
                     Peremption.dateLimiteDe("0.1.0", "2026-11-17"))
    }

    // --- Décisions 1 et 2 : jour de grâce inclus, témoin ---------------

    @Test
    fun jourDeGraceInclusLaLimiteResteJouable() {
        // La limite elle-même se joue, l'expiration tombe le
        // LENDEMAIN.
        assertFalse(
            Peremption.estPerimee("2026-11-22", "2026-11-22", null))
        assertTrue(
            Peremption.estPerimee("2026-11-22", "2026-11-23", null))
    }

    @Test
    fun temoinSurvitAuRetourEnArriereDeLHorloge() {
        // Témoin postérieur au jour affiché : c'est LUI qui
        // compte, la limite est dépassée malgré l'horloge
        // revenue en arrière.
        assertTrue(
            Peremption.estPerimee("2026-11-22", "2026-11-20", "2026-11-25"))
    }

    @Test
    fun temoinMoindreQueLeJourEstIgnore() {
        assertFalse(
            Peremption.estPerimee("2026-11-22", "2026-11-20", "2026-11-18"))
    }

    @Test
    fun releaseSansLimiteNestJamaisPerimee() {
        assertFalse(Peremption.estPerimee(null, "2026-11-20", "2026-11-25"))
        assertFalse(Peremption.estPerimee("", "2026-11-20", "2026-11-25"))
        assertFalse(Peremption.estPerimee(null, "2030-01-01", null))
    }

    @Test
    fun datesIsoOrdonneesLexicalementCommeChronologiquement() {
        // Propriété sur laquelle repose estPerimee : zéro-paddées,
        // les ISO se comparent en texte comme dans le temps. Les
        // dates sont CALCULÉES par dateLimite, jamais des
        // constantes que l'inspection replierait en booléen.
        val novembre = Peremption.dateLimite("2026-11-02")
        val quinzaine = Peremption.dateLimite("2026-11-10")
        val anneeSuivante = Peremption.dateLimite("2027-01-01")
        assertTrue(novembre < quinzaine)
        assertTrue(quinzaine < anneeSuivante)
        assertTrue(anneeSuivante > novembre)
    }

    @Test
    fun jourIsoFormatteAnneeMoisJour() {
        val cal = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(2026, java.util.Calendar.NOVEMBER, 17)
        assertEquals("2026-11-17", Peremption.jourIso(cal))
    }
}
