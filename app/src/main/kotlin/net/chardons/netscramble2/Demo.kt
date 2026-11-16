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
 * Difficulté de la démonstration, parmi les niveaux COCHÉS
 * (réglages, v8 — retours du 16 nov. 2026) :
 *  - RANDOM : un niveau coché tiré au sort à chaque plateau ;
 *  - PROGRESSIVE : les niveaux cochés en ordre croissant,
 *    boucle sur la liste — la famille Infini comprise. Une
 *    seule case cochée revient à un niveau fixe.
 */
enum class DemoDifficulty { RANDOM, PROGRESSIVE }

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

/**
 * Prochain coup JOUABLE de la démonstration (retours utilisateurs
 * du 16 nov. 2026, lot 2) : la TÊTE de la file si elle est visible,
 * sinon la file tourne — chaque tuile invisible repasse en FIN de
 * file — jusqu'à la première visible. Les rotations sont
 * indépendantes (aucun coup n'en conditionne un autre) : réordonner
 * la file ne change pas la solution, seulement l'ordre du
 * spectacle.
 *
 * Au Néant, le brouillard est la règle du JOUEUR : la démonstration
 * ne tourne pas ce qu'un joueur ne pourrait pas voir. La file du
 * solveur part du serveur en largeur sur la SOLUTION : la prochaine
 * tuile à jouer touche toujours le réseau déjà en place, donc
 * NORMALEMENT visible d'office — la garde est un filet de sécurité,
 * pas un réordonnancement permanent.
 *
 * [visible] null : tout est visible (les autres niveaux), la tête
 * d'origine, file intacte. File VIDE : -1. Après un tour complet
 * sans AUCUNE tuile visible, la file a retrouvé son ordre d'origine
 * — la tête est renvoyée telle quelle : la progression passe avant
 * l'attente, la démonstration ne se fige jamais.
 *
 * CONTRAT (plantage du 16 nov. 2026, relevé par eric) : la
 * fonction CONSULTE, elle ne consomme JAMAIS — la tête reste la
 * tête, y compris jouable. C'est l'APPELANT qui retire la têke
 * ([ArrayDeque.removeFirst]) quand le coup est joué ou sauté :
 * l'oublier fait tourner la boucle de la démonstration à l'infini
 * sur la première tuile en place, et fige l'écran.
 */
fun nextPlayableMove(queue: ArrayDeque<Int>, visible: BooleanArray?): Int {
    if (queue.isEmpty()) return -1
    if (visible == null) return queue.first()
    val n = queue.size
    repeat(n) {
        val index = queue.removeFirst()
        if (visible[index]) {
            queue.addFirst(index)
            return index
        }
        queue.addLast(index)
    }
    return queue.first()
}
