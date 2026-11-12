package net.chardons.netscramble2.game

import kotlin.random.Random

/**
 * Champ de « blips » de données qui parcourent les câbles du plateau.
 * Portage fidèle de Cell.java de l'original (advanceBlips /
 * transferBlips) :
 *
 *  - un blip occupe une « jambe » de câble d'une cellule, soit entrant
 *    (il va du bord vers le centre), soit sortant (du centre vers le
 *    bord) ; il avance d'une demi-cellule à chaque [advance] ;
 *  - arrivé au centre, un blip entrant repart sur toutes les autres
 *    jambes câblées : le flux « tourne » aux embranchements ;
 *  - un blip sortant traversant le bord rejoint la cellule voisine si son
 *    câble est aligné, sinon il disparaît ;
 *  - le serveur émet de nouveaux blips sur toutes ses jambes câblées :
 *    une génération sur [SERVER_EVERY_GENERATIONS] strictement avec le
 *    générateur TREE (métronome), par à-coups irréguliers avec
 *    ORIGINAL (effet « électricité », décision d'eric du 12 nov.
 *    2026 : le rythme d'émission signe le moteur qui a créé le
 *    plateau) ;
 *  - tourner une cellule efface ses blips ([clear], appelé par la vue) ;
 *  - un terminal (une seule jambe) absorbe : le blip entrant y meurt.
 *
 * Comme Board, c'est un modèle PUR (aucune dépendance Android), donc
 * testable sur la JVM. La vue pilote le temps : un [advance] toutes les
 * 300 ms dans l'original (BoardView.BLIPS_TIME), et interpole la
 * position entre deux avances pour un mouvement fluide.
 */
class BlipField(private val board: Board) {

    /** Blips entrants par cellule : masque de directions. */
    private val incoming = IntArray(board.size)

    /** Blips sortants par cellule : masque de directions. */
    private val outgoing = IntArray(board.size)

    /** Blips à transférer aux voisins pendant la génération courante. */
    private val transfer = IntArray(board.size)

    /** Numéro de génération, incrémenté à chaque [advance]. */
    var generation = 0
        private set

    /**
     * Génération de la prochaine émission du serveur (générateur
     * ORIGINAL uniquement) : chaque émission tire l'écart suivant
     * dans [ORIGINAL_EMISSION_MIN_GENERATIONS]..
     * [ORIGINAL_EMISSION_MAX_GENERATIONS] sur [jitter].
     */
    private var nextServerEmission = 0

    /**
     * Source des écarts d'émission du serveur ORIGINAL : instance
     * DISTINCTE, enracinée sur la graine de la partie, qui ne
     * consomme jamais le flux du générateur de plateau — la partie
     * reste reproductible au tirage près.
     */
    private val jitter = Random(board.seed xor JITTER_SEED)

    init {
        if (board.generator == Generator.ORIGINAL) {
            nextServerEmission = jitter.nextInt(
                ORIGINAL_EMISSION_MIN_GENERATIONS,
                ORIGINAL_EMISSION_MAX_GENERATIONS + 1
            )
        }
    }

    /** Efface les blips d'une cellule (elle vient d'être tournée). */
    fun clear(index: Int) {
        incoming[index] = 0
        outgoing[index] = 0
        transfer[index] = 0
    }

    /**
     * Fait avancer tous les blips d'une génération, en deux phases comme
     * l'original : d'abord, sur chaque cellule, les blips sortants sont
     * marqués pour transfert et les blips entrants ressortent sur les
     * autres jambes câblées ; ensuite seulement, les transferts sont
     * appliqués aux voisins alignés. L'ordre importe : un blip ne saute
     * jamais deux cellules en une génération.
     */
    fun advance() {
        // Signature « électricité » : le rythme d'émission du serveur
        // identifie le générateur. TREE reste un métronome strict
        // (une génération sur six, comme l'original) ; ORIGINAL émet
        // par à-coups irréguliers — la croissance par essais de
        // createNet — d'écarts tirés de la graine (reproductibles).
        val serverEmitsThisGeneration =
            if (board.generator == Generator.TREE) {
                generation % SERVER_EVERY_GENERATIONS == 0
            } else if (generation >= nextServerEmission) {
                nextServerEmission = generation + jitter.nextInt(
                    ORIGINAL_EMISSION_MIN_GENERATIONS,
                    ORIGINAL_EMISSION_MAX_GENERATIONS + 1
                )
                true
            } else {
                false
            }
        // Phase 1 : chaque cellule consomme ses blips entrants, marque
        // ses blips sortants pour le transfert, et le serveur émet.
        for (index in 0 until board.size) {
            val dirs = board.currentDirs(index)

            // Les blips sortants sur une jambe câblée partent chez le voisin.
            var toTransfer = 0
            for (dir in Dir.entries) {
                if ((outgoing[index] and dir.bit) != 0 && Dir.contains(dirs, dir)) {
                    toTransfer = toTransfer or dir.bit
                }
            }
            outgoing[index] = 0

            // Les blips entrants arrivent au centre et repartent sur
            // toutes les autres jambes câblées.
            if (incoming[index] != 0) {
                for (dir in Dir.entries) {
                    if ((incoming[index] and dir.bit) == 0 && Dir.contains(dirs, dir)) {
                        outgoing[index] = outgoing[index] or dir.bit
                    }
                }
            }
            incoming[index] = 0

            // Le serveur émet sur toutes ses jambes câblées, au rythme
            // signé par son générateur (voir ci-dessus).
            if (index == board.serverIndex && serverEmitsThisGeneration) {
                for (dir in Dir.entries) {
                    if (Dir.contains(dirs, dir)) {
                        outgoing[index] = outgoing[index] or dir.bit
                    }
                }
            }
            transfer[index] = toTransfer
        }

        // Phase 2 : transfert effectif aux voisins alignés.
        for (index in 0 until board.size) {
            if (transfer[index] == 0) continue
            for (dir in Dir.entries) {
                if (!Dir.contains(transfer[index], dir)) continue
                val neighbor = board.neighborIndex(index, dir)
                if (neighbor < 0) continue
                if (Dir.contains(board.currentDirs(neighbor), dir.opposite)) {
                    incoming[neighbor] = incoming[neighbor] or dir.opposite.bit
                }
            }
            transfer[index] = 0
        }

        ++generation
    }

    /** Blips entrants d'une cellule (masque de directions), pour le dessin. */
    fun incomingAt(index: Int): Int = incoming[index]

    /** Blips sortants d'une cellule (masque de directions), pour le dessin. */
    fun outgoingAt(index: Int): Int = outgoing[index]

    companion object {
        /** Le serveur TREE émet une génération sur six (Cell.advanceBlips). */
        const val SERVER_EVERY_GENERATIONS = 6

        /** Écart d'émission minimal du serveur ORIGINAL (générations). */
        const val ORIGINAL_EMISSION_MIN_GENERATIONS = 2

        /** Écart d'émission maximal du serveur ORIGINAL (générations). */
        const val ORIGINAL_EMISSION_MAX_GENERATIONS = 10

        /**
         * Sel décoratif de la graine : le flux des écarts d'émission
         * ne partage aucune valeur avec le flux du générateur.
         */
        private const val JITTER_SEED = 0x5EED01L
    }
}
