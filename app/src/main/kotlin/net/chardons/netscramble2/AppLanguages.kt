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
 * V21 (chantier « feuille de langue », 18 nov. 2026, demande
 * d'eric : la page prévoit de NOMBREUSES langues, une radio par
 * langue l'obligeait à trop scroller) : le choix ne vit plus dans
 * la page mais dans une feuille Material 3 — la page ne garde
 * qu'une entrée résumé. NOUVEAU champ drapeauPays : pour une
 * langue SECONDAIRE d'un pays (corse, alsacien, futur breton,
 * créoles…), le DRAPEAU NATIONAL du pays s'affiche entre
 * parenthèses après l'endonyme (« Corsu (drapeau de la France) »)
 * — les langues qui parlent pour elles-mêmes (français, anglais,
 * chinois) n'en portent pas (null).
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

/**
 * Une langue du jeu : tag BCP-47 complet, endonyme affiché et,
 * pour une langue SECONDAIRE d'un pays (v21), le tag du drapeau
 * NATIONAL montré entre parenthèses après l'endonyme dans la
 * feuille (drapeau de la France pour le corse et l'alsacien) ;
 * null pour les langues qui parlent pour elles-mêmes (français,
 * anglais, chinois).
 */
data class AppLanguage(
    val tag: String,
    val endonym: String,
    val drapeauPays: String? = null
)

object AppLanguages {

    /** Langues du jeu, dans l'ordre d'affichage du sélecteur. */
    val ALL: List<AppLanguage> = listOf(
        AppLanguage("fr", "Français"),
        AppLanguage("en", "English"),
        // V21 : langues secondaires de France — le drapeau
        // national s'affiche entre parenthèses après l'endonyme.
        AppLanguage("co", "Corsu", drapeauPays = "fr"),
        AppLanguage("gsw-FR", "Elsässisch", drapeauPays = "fr"),
        // Chinois simplifié (18 nov. 2026) : première passe IA
        // expérimentale — relecteur natif attendu avant toute
        // officialisation, comme le corse et l'alsacien.
        AppLanguage("zh-CN", "简体中文"),
    )

    /**
     * La langue portée par un tag : correspondance EXACTE, sur le
     * tag complet (jamais la langue seule). Inconnu -> null — la
     * page retombe alors sur « Suivre le système ».
     */
    fun byTag(tag: String): AppLanguage? =
        ALL.firstOrNull { it.tag == tag }
}
