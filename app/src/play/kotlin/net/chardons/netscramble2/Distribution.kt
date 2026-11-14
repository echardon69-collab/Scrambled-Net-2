package net.chardons.netscramble2

/**
 * Saveur « play » (canaux de distribution du 14 nov. 2026) :
 * l'application est distribuée par le Play Store — c'est le store
 * qui la met à jour. L'auto-mise à jour est COUPÉE à la
 * construction.
 *
 * Politique Play « Use of the REQUEST_INSTALL_PACKAGES permission » :
 * la permission est interdite pour les self-updates. Double verrou
 * de cette saveur : (1) la permission est retirée du manifeste
 * fusionné (src/play/AndroidManifest.xml, tools:node="remove") ;
 * (2) l'écran titre ne vérifie jamais GitHub (UPDATER_ENABLED =
 * false ci-dessous). Le code de l'Updater reste compilé mais
 * inatteignable — et sans la permission, même un chemin erroné
 * serait bloqué par le système.
 *
 * La saveur « github » porte la même classe avec
 * UPDATER_ENABLED = true.
 */
object Distribution {

    /** false : aucun appel réseau vers GitHub, aucun bouton ni installation. */
    const val UPDATER_ENABLED = false
}
