package net.chardons.netscramble2.game

/**
 * Les quatre directions cardinales, chacune associée à un bit d'un masque
 * de connexions. Une cellule du plateau porte un masque 4 bits décrivant
 * les côtés par lesquels elle se connecte à ses voisins.
 *
 * Exemple : UP.bit or DOWN.bit (5) = cellule connectée en haut et en bas.
 */
enum class Dir(val bit: Int, val dx: Int, val dy: Int) {
    UP(1, 0, -1),
    RIGHT(2, 1, 0),
    DOWN(4, 0, 1),
    LEFT(8, -1, 0);

    /** Direction opposée (UP <-> DOWN, RIGHT <-> LEFT). */
    val opposite: Dir get() = entries[(ordinal + 2) % 4]

    /**
     * Direction après [steps] rotations d'un quart de tour dans le sens
     * horaire. [steps] peut être négatif (sens anti-horaire).
     */
    fun rotatedCW(steps: Int = 1): Dir = entries[((ordinal + steps) % 4 + 4) % 4]

    companion object {
        /** Masque : aucune connexion. */
        const val NO_DIRS = 0

        /** Masque : les quatre directions (croix complète). */
        const val ALL_DIRS = 15

        /** Le masque contient-il cette direction ? */
        fun contains(mask: Int, dir: Dir): Boolean = (mask and dir.bit) != 0

        /**
         * Fait tourner un masque de connexions de [steps] quarts de tour
         * horaires. C'est l'opération de base du jeu : tourner une tuile.
         */
        fun rotateMask(mask: Int, steps: Int): Int {
            var result = NO_DIRS
            for (dir in entries) {
                if (contains(mask, dir)) {
                    result = result or dir.rotatedCW(steps).bit
                }
            }
            return result
        }
    }
}
