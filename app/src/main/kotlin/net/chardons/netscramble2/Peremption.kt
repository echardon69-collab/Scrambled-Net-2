package net.chardons.netscramble2

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Lot 17 — péremption des versions de développement (16-17 nov.
 * 2026). Décisions d'eric :
 *
 *  1. GRÂCE : 5 jours après la date de build (pas 90).
 *  2. ÉCRAN A : blocage pur + bouton Quitter, PAS de proposition
 *     de mise à jour.
 *  3. RÈGLE DU NUMÉRO : versionName de majeure 0 (« 0.x.y ») =
 *     pré-release limitée ; « 1.x.y » et au-delà = release
 *     officielle, illimitée.
 *  4. Message avec phrase d'accompagnement (chaînes + layout,
 *     côté TitleActivity).
 *
 * Gradle n'estampille QUE la date de build
 * (BuildConfig.DATE_BUILD, ISO « yyyy-MM-dd », UTC) ; toute la
 * logique vit ICI, en pur Kotlin testable sur JVM. Seuls
 * java.util.Calendar et java.util.TimeZone sont utilisés :
 * java.time est interdit sans desugaring (minSdk 24).
 *
 * Les dates voyagent en ISO « yyyy-MM-dd » zéro-paddées : la
 * comparaison LEXICALE est alors la comparaison chronologique —
 * propriété épinglée par PeremptionTest.
 */
object Peremption {

    /** Grâce en jours après la date de build (décision 1 : 5). */
    const val GRACE_JOURS = 5

    /**
     * Témoin de date persisté par TitleActivity : fichier DÉDIÉ,
     * jamais Persistence.FILE — la sauvegarde de partie n'est pas
     * mélangée à la péremption.
     */
    const val FICHIER = "peremption"
    const val CLE_TEMOIN = "temoin"

    /** Format imposé aux dates : ISO « yyyy-MM-dd », zéro-paddé. */
    private val ISO = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** Majeure 0 = pré-release limitée (décision 3). */
    fun estPreRelease(versionName: String): Boolean =
        versionName.trim().startsWith("0.")

    /**
     * Date limite = date de build + [graceJours]. Le jour de la
     * limite est INCLUS : il reste jouable, l'expiration tombe le
     * lendemain (estPerimee). Calendrier NON TOLÉRANT : une date
     * impossible (2026-13-01, 2026-02-30) est refusée par
     * exception, jamais silencieusement corrigée.
     */
    fun dateLimite(dateBuild: String, graceJours: Int = GRACE_JOURS): String {
        if (!ISO.matches(dateBuild)) {
            throw IllegalArgumentException(
                "Date de build attendue en ISO yyyy-MM-dd : « $dateBuild »"
            )
        }
        val (annee, mois, jour) = dateBuild.split("-")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.isLenient = false
        cal.set(annee.toInt(), mois.toInt() - 1, jour.toInt())
        cal.add(Calendar.DAY_OF_MONTH, graceJours)
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                             cal.get(Calendar.YEAR),
                             cal.get(Calendar.MONTH) + 1,
                             cal.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * Date limite de CETTE version, ou null si release officielle
     * (décision 3) : « 1.x.y » et au-delà n'expirent jamais.
     */
    fun dateLimiteDe(versionName: String, dateBuild: String): String? =
        if (estPreRelease(versionName)) dateLimite(dateBuild) else null

    /**
     * La version est-elle périmée ? Une limite null ou vide ne
     * l'est jamais (release officielle). Le jour observé est le
     * MAX LEXICAL du jour UTC et du témoin persisté : reculer
     * l'horloge du téléphone ne réouvre pas l'application.
     * Périmée = jour STRICTEMENT postérieur à la limite : le jour
     * de grâce est inclus, la limite reste jouable (décisions 1
     * et 2). Comparaison lexicale : les ISO zéro-paddées sont
     * ordonnées comme la chronologie.
     */
    fun estPerimee(dateLimite: String?, aujourdHui: String,
                   temoin: String?): Boolean {
        if (dateLimite.isNullOrEmpty()) return false
        val jour = maxOf(aujourdHui, temoin ?: "")
        return jour > dateLimite
    }

    /**
     * Jour du calendrier donné, formaté ISO « yyyy-MM-dd ».
     * TitleActivity l'appelle sur un Calendar UTC : le téléphone
     * peut être réglé sur un autre fuseau.
     */
    fun jourIso(cal: Calendar): String =
        String.format(Locale.ROOT, "%04d-%02d-%02d",
                      cal.get(Calendar.YEAR),
                      cal.get(Calendar.MONTH) + 1,
                      cal.get(Calendar.DAY_OF_MONTH))
}
