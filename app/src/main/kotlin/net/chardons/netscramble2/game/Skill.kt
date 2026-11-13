package net.chardons.netscramble2.game

/**
 * Niveaux de difficulté, fidèles à l'enum Skill de BoardView.java de
 * l'original :
 *  - Novice / Normal / Expert : deux branches par cellule, pas de tore ;
 *  - Maître : trois branches et tore (le réseau boucle sur lui-même,
 *    les carrefours à trois voies apparaissent) ;
 *  - Dément (Insane) : comme Maître, plus les cellules câblées masquées ;
 *  - Infini (Endless) : comme Maître, sur un plateau géant FIXE de
 *    40 x 40 (voir [ENDLESS_SIZE]) qui ignore la matrice Screen — le
 *    réseau y est toujours généré par le générateur ORIGINAL du jeu
 *    d'origine (victoire par terminaux, comme à l'origine) ;
 *  - Abîme (Abyss) : comme Infini, plus les cellules câblées masquées
 *    (le masquage à la Dément, sur le plateau sans bornes) ;
 *  - Néant (Void) : comme Abîme, plus le brouillard dérivé (seules
 *    les abords des tuiles connectées au serveur sont visibles —
 *    phase C du chantier, permanente sur ce niveau).
 *
 * La famille « Infini » (propriété [endless]) partage le plateau 40 x 40
 * en tore, la caméra modulo et le générateur ORIGINAL forcé ; ce qui
 * la gradue, c'est ce que le joueur VOIT : tout, puis sans les câbles
 * (Abîme), puis uniquement le réseau qu'il construit (Néant).
 *
 * La taille du plateau ne dépend plus du niveau depuis le chantier
 * « tailles fidèles » : elle vient de l'écran, via [Screen.find] puis
 * [Screen.boardSize], exactement comme le système Screen de l'original.
 * Ce que le niveau règle, c'est la complexité du réseau et ses règles
 * (tore, masquage). Seule exception : la famille Infini, dont la taille
 * est fixe et carrée quelle que soit la matrice de l'écran.
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
    val hiddenThreshold: Int,
    /**
     * Famille « Infini » (ENDLESS, ABYSS, VOID) : plateau géant FIXE
     * 40 x 40 en tore (voir [ENDLESS_SIZE]), matrice Screen bypassée,
     * générateur ORIGINAL forcé, caméra modulo dans la vue. Le nom du
     * niveau ne se teste plus « == ENDLESS » un peu partout : la vue,
     * le modèle et l'écran demandent au niveau lui-même s'il est de
     * cette famille.
     */
    val endless: Boolean = false
) {
    NOVICE(branches = 2, wrap = false, hiddenThreshold = 9),
    NORMAL(branches = 2, wrap = false, hiddenThreshold = 9),
    EXPERT(branches = 2, wrap = false, hiddenThreshold = 9),
    MASTER(branches = 3, wrap = true, hiddenThreshold = 9),
    INSANE(branches = 3, wrap = true, hiddenThreshold = 3),
    ENDLESS(branches = 3, wrap = true, hiddenThreshold = 9,
        endless = true),
    ABYSS(branches = 3, wrap = true, hiddenThreshold = 3,
        endless = true),
    VOID(branches = 3, wrap = true, hiddenThreshold = 3,
        endless = true);

    companion object {
        /**
         * Côté du plateau de la famille Infini, FIXE et carré (40 x 40) :
         * contrairement aux autres niveaux, la taille ne vient pas de
         * la matrice Screen de l'écran. La caméra modulo (phase B du
         * chantier Infini) boucle sur ce plateau géant pour donner
         * l'illusion d'un réseau sans bornes.
         */
        const val ENDLESS_SIZE = 40
    }
}
