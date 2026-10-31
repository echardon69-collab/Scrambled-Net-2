package net.chardons.netscramble2.game

/**
 * Niveaux de difficulté, fidèles à l'original :
 *  - Novice / Normal / Expert : grilles de plus en plus grandes.
 *  - Master : grille + bords « wrap-around » (le réseau boucle sur lui-même).
 *  - Insane : wrap + cellules cachées : exactement comme dans
 *    l'original (BoardView.java de Scrambled Net, qui parlait de « blind »),
 *    toute cellule dont le nombre de connexions atteint [hiddenThreshold]
 *    masque ses câbles.
 *
 * TODO (roadmap) : adapter la taille de la grille à la diagonale réelle de
 * l'écran et au zoom, plutôt qu'à des tailles fixes.
 */
enum class Skill(
    val width: Int,
    val height: Int,
    val wrap: Boolean,
    /**
     * Une cellule possédant au moins autant de connexions est cachée.
     * L'original utilise 9 (jamais atteint : 4 connexions au maximum)
     * pour les niveaux normaux, et 3 pour Insane.
     */
    val hiddenThreshold: Int
) {
    NOVICE(5, 5, wrap = false, hiddenThreshold = 9),
    NORMAL(7, 7, wrap = false, hiddenThreshold = 9),
    EXPERT(9, 9, wrap = false, hiddenThreshold = 9),
    MASTER(9, 9, wrap = true, hiddenThreshold = 9),
    INSANE(11, 11, wrap = true, hiddenThreshold = 3);
}
