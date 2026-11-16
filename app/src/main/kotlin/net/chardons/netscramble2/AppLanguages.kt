package net.chardons.netscramble2

/**
 * Langues de l'interface proposées par le sélecteur de la page
 * Réglages (chantier « sélecteur de langue dynamique », v19 du
 * 16 nov. 2026).
 *
 * Jusqu'ici, les radios Français et English étaient FIGÉES dans le
 * layout (v3 du 14 nov. 2026) : une traduction neuve — corse (v17),
 * alsacien (v18) — n'était PAS sélectionnable depuis l'app, seule
 * le permettait le réglage système « langue par application ». Le
 * sélecteur reconstruit désormais sa liste AU RUNTIME depuis ces
 * données : ajouter une langue = un dossier values-xx, la locale
 * dans locales_config.xml, une ligne ici — et elle apparaît aussitôt
 * dans la page, cochable, sans toucher au layout ni à l'activité.
 * validate_selecteur.py épingle les trois en cohérence (locales_
 * config.xml, cette liste, dossiers de traduction) : jamais l'un
 * sans les autres.
 *
 * Les libellés sont des ENDONYMES — la langue se nomme elle-même :
 * « Français », « English », « Corsu », « Elsässisch ». Identiques
 * quelle que soit la langue d'affichage, ils ne vivent PAS dans les
 * fichiers de traduction (les anciennes strings lang_fr/lang_en
 * sont retirées) ; ils restent relus par les relecteurs des langues :
 * dites-nous si un endonyme vous semble mal écrit.
 *
 * Piège du code alsacien (v18) : l'alsacien n'a pas de code Android
 * propre ; il voyage sous la locale gsw-FR (alémanique de France).
 * Le tag COMPLET fait foi dans la correspondance — « gsw » seul ne
 * matche pas, il désignerait aussi l'alémanique suisse.
 */

/** Une langue du jeu : tag BCP-47 complet et endonyme affiché. */
data class AppLanguage(val tag: String, val endonym: String)

object AppLanguages {

    /** Langues du jeu, dans l'ordre d'affichage du sélecteur. */
    val ALL: List<AppLanguage> = listOf(
        AppLanguage("fr", "Français"),
        AppLanguage("en", "English"),
        AppLanguage("co", "Corsu"),
        AppLanguage("gsw-FR", "Elsässisch"),
    )

    /**
     * La langue portée par un tag : correspondance EXACTE, sur le
     * tag complet (jamais la langue seule). Inconnu -> null — la
     * page retombe alors sur « Suivre le système ».
     */
    fun byTag(tag: String): AppLanguage? =
        ALL.firstOrNull { it.tag == tag }
}
