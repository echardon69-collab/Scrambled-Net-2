package net.chardons.netscramble2.game

/**
 * Niveaux de difficulté, fidèles à l'enum Skill de BoardView.java de
 * l'original :
 *  - Novice / Normal / Expert : deux branches par cellule, pas de tore ;
 *  - Maître : trois branches et tore (le réseau boucle sur lui-même,
 *    les carrefours à trois voies apparaissent) ;
 *  - Dément (Insane) : comme Maître, plus les cellules câblées masquées.
 *
 * La taille du plateau ne dépend plus du niveau depuis le chantier
 * « tailles fidèles » : elle vient de l'écran, via [Screen.find] puis
 * [Screen.boardSize], exactement comme le système Screen de l'original.
 * Ce que le niveau règle, c'est la complexité du réseau et ses règles
 * (tore, masquage).
 */
enum class Skill(
    /**
     * Nombre maximal d'embranchements par cellule pour le générateur
     * ORIGINAL : 2 jusqu'à Expert, 3 pour Maître et Dément. L'original
     * en faisait autant : c'est ce qui introduit les carrefours à trois
     * voies, et parfois les croix complètes, dans les niveaux avancés.
     */
    val branches: Int,
    /**
     * Tore : les bords du plateau bouclent sur eux-mêmes (Maître et
     * Dément). Sans tore, les câbles ne fuient jamais hors du plateau.
     */
    val wrap: Boolean,
    /**
     * Une cellule possédant au moins autant de connexions est cachée.
     * L'original utilise 9 (jamais atteint : 4 connexions au maximum)
     * pour les niveaux normaux, et 3 pour Dément.
     */
    val hiddenThreshold: Int
) {
    NOVICE(branches = 2, wrap = false, hiddenThreshold = 9),
    NORMAL(branches = 2, wrap = false, hiddenThreshold = 9),
    EXPERT(branches = 2, wrap = false, hiddenThreshold = 9),
    MASTER(branches = 3, wrap = true, hiddenThreshold = 9),
    INSANE(branches = 3, wrap = true, hiddenThreshold = 3);
}
