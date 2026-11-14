package net.chardons.netscramble2

/**
 * Saveur « github » (canaux de distribution du 14 nov. 2026) :
 * l'application est installée hors Play Store — GitHub Releases
 * publie l'APK, l'auto-mise à jour de l'écran titre est ACTIVE.
 *
 * La saveur « play » porte la même classe avec
 * UPDATER_ENABLED = false : Play interdit
 * REQUEST_INSTALL_PACKAGES pour les self-updates, sa saveur retire
 * la permission du manifeste fusionné et coupe la vérification à la
 * construction. TitleActivity consulte CE drapeau, seule couture
 * entre les deux canaux : le reste du code est identique.
 */
object Distribution {

    /** true : l'écran titre vérifie GitHub et propose la mise à jour. */
    const val UPDATER_ENABLED = true
}
