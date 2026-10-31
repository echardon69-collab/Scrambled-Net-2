package net.chardons.netscramble2.game

/**
 * Effets sonores du jeu. Le [net.chardons.netscramble2.GameViewModel]
 * émet ces sons à des moments précis, repris de l'original :
 *
 * - [START] : nouvelle partie ;
 * - [TURN] : rotation effective d'une cellule par le joueur ;
 * - [CLICK] : refus — appui sur une cellule verrouillée ou partie déjà
 *   résolue (le « clic sec » de l'original, jamais pour une vraie
 *   rotation) ;
 * - [POP] : verrouillage / déverrouillage d'une cellule (appui long) ;
 * - [CONNECT] : la connexité du réseau vient de changer (connexion ou
 *   déconnexion) ;
 * - [WIN] : le réseau vient d'être résolu (joué après [CONNECT], comme
 *   dans l'original).
 *
 * L'auto-solveur, comme dans l'original, ne joue pas [TURN] : ses
 * rotations directes ne produisent que [CONNECT] puis [WIN].
 */
enum class Sound {
    /** Nouvelle partie. */
    START,

    /** Rotation d'une cellule par le joueur. */
    TURN,

    /** Refus : cellule verrouillée ou partie déjà résolue. */
    CLICK,

    /** Verrouillage / déverrouillage d'une cellule. */
    POP,

    /** La connexité du réseau vient de changer. */
    CONNECT,

    /** Le réseau est résolu (joué après [CONNECT]). */
    WIN
}
