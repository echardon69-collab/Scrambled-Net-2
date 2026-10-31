package net.chardons.netscramble2.game

/**
 * Niveaux de difficulté, fidèles à l'original :
 *  - Novice / Normal / Expert : grilles de plus en plus grandes.
 *  - Master : grille + bords « wrap-around » (le réseau boucle sur lui-même).
 *  - Insane : wrap + cellules « aveugles » (les connexions affichées peuvent
 *    être fausses, le joueur doit raisonner par déduction).
 *
 * TODO (roadmap) : adapter la taille de la grille à la diagonale réelle de
 * l'écran et au zoom, plutôt qu'à des tailles fixes.
 */
enum class Skill(
    val width: Int,
    val height: Int,
    val wrap: Boolean,
    val blind: Boolean
) {
    NOVICE(5, 5, wrap = false, blind = false),
    NORMAL(7, 7, wrap = false, blind = false),
    EXPERT(9, 9, wrap = false, blind = false),
    MASTER(9, 9, wrap = true, blind = false),
    INSANE(11, 11, wrap = true, blind = true);
}
