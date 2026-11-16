package net.chardons.netscramble2

/**
 * Réglages du mode Démonstration (chantier Aide à la résolution,
 * 15 nov. 2026) : purs enums sans dépendance Android, persistés par
 * [Settings] via les clés de [Persistence].
 *
 * La démonstration enchaîne les plateaux : chacun est mélangé puis
 * résolu coup par coup par le solveur (visible, curseur rouge), la
 * victoire clignote, une pause, le plateau suivant. Toute
 * interaction de l'écran la referme : la partie du joueur, elle,
 * n'a pas bougé.
 */

/**
 * Difficulté de la démonstration :
 *  - FIXED : toujours le niveau choisi (réglage « Niveau ») ;
 *  - RANDOM : un niveau tiré au sort à chaque plateau ;
 *  - PROGRESSIVE : départ au niveau choisi, montée d'un cran à
 *    chaque plateau, boucle sur les huit niveaux — la famille Infini
 *    comprise.
 */
enum class DemoDifficulty { FIXED, RANDOM, PROGRESSIVE }

/**
 * Rythme de la démonstration : délai entre deux coups du solveur,
 * en millisecondes. NORMAL reprend le tick du solveur du jeu
 * (250 ms). V5 (retour d'eric du 15 nov. 2026) : le rythme Éclair
 * (30 ms) est retiré — défilement illisible ; une sauvegarde qui le
 * désigne encore retombe sur NORMAL sans crash (Settings, repli).
 */
enum class DemoSpeed(val delayMs: Long) {
    SLOW(500),
    NORMAL(250),
    FAST(100)
}

/**
 * Zoom de la démonstration : multiplicateur du plancher de dézoom.
 * FIT (1f) ajuste le plateau à la vue ; au-delà, la vue cadre plus
 * serré et suit le solveur (caméra à hystérésis).
 */
enum class DemoZoom(val multiplier: Float) {
    FIT(1f),
    X1_5(1.5f),
    X2(2f),
    X3(3f)
}
