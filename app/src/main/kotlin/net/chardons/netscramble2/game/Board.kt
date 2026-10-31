package net.chardons.netscramble2.game

import kotlin.random.Random

/**
 * Le plateau de jeu : un modèle PUR (aucune dépendance Android), donc
 * testable sur la JVM et portable tel quel.
 *
 * Représentation :
 *  - [connections] : masque de la SOLUTION pour chaque cellule (arbre couvrant
 *    du réseau, généré une fois pour toutes) ;
 *  - [rotations] : rotation courante (0..3 quarts de tour horaires) de chaque
 *    cellule — c'est ce que le joueur modifie ;
 *  - les connexions courantes d'une cellule = [Dir.rotateMask] de sa solution
 *    par sa rotation.
 *
 * Le plateau est toujours soluble par rotation : en remettant chaque cellule
 * à la rotation 0, on retrouve l'arbre couvrant complet.
 */
class Board(
    val width: Int,
    val height: Int,
    val wrap: Boolean,
    /**
     * Seuil de masquage (niveau Insane) : une cellule avec au moins
     * autant de connexions masque ses câbles. 9 = jamais atteint.
     */
    val hiddenThreshold: Int,
    seed: Long
) {
    /** Nombre total de cellules. */
    val size: Int = width * height

    /** Index de la cellule du serveur (le cœur du réseau, au centre). */
    val serverIndex: Int = (height / 2) * width + (width / 2)

    /** Masque de connexions de la solution, par cellule. */
    val connections = IntArray(size)

    /** Rotation courante (0..3) de chaque cellule, choisie au mélange initial. */
    val rotations = IntArray(size)

    /** Cellules verrouillées par le joueur (appui long). */
    val locked = BooleanArray(size)

    /** Cellules cachées dont les câbles ont été révélés (solveur, fin). */
    val revealed = BooleanArray(size)

    init {
        generate(Random(seed))
        scramble(Random(seed xor -0x5DEECE66DL))
    }

    // ******************************************************************** //
    // Génération.
    // ******************************************************************** //

    /**
     * Construit un arbre couvrant aléatoire du plateau (parcours en profondeur
     * randomisé) : chaque cellule obtient au moins une connexion, le serveur
     * est la racine. Les feuilles de l'arbre (une seule connexion) sont les
     * « terminaux ».
     */
    private fun generate(rnd: Random) {
        val visited = BooleanArray(size)
        val stack = ArrayDeque<Int>()
        visited[serverIndex] = true
        stack.addLast(serverIndex)

        while (stack.isNotEmpty()) {
            val current = stack.last()
            val dirs = Dir.entries.shuffled(rnd)
            var extended = false
            for (dir in dirs) {
                val neighbor = neighborIndex(current, dir)
                if (neighbor < 0 || visited[neighbor]) continue
                connections[current] = connections[current] or dir.bit
                connections[neighbor] = connections[neighbor] or dir.opposite.bit
                visited[neighbor] = true
                stack.addLast(neighbor)
                extended = true
                break
            }
            if (!extended) stack.removeLast()
        }
    }

    /**
     * Mélange le plateau en tournant chaque cellule aléatoirement, en
     * s'assurant de ne jamais présenter un plateau déjà résolu.
     */
    private fun scramble(rnd: Random) {
        var attempts = 0
        do {
            for (i in rotations.indices) rotations[i] = rnd.nextInt(4)
            ++attempts
        } while (isSolved() && attempts < 100)
    }

    // ******************************************************************** //
    // Requêtes.
    // ******************************************************************** //

    /**
     * Index du voisin dans la direction [dir], -1 s'il n'existe pas.
     * Avec [wrap], les bords bouclent sur eux-mêmes (tore).
     */
    fun neighborIndex(index: Int, dir: Dir): Int {
        val x = index % width
        val y = index / width
        val nx = x + dir.dx
        val ny = y + dir.dy
        return when {
            wrap -> (Math.floorMod(ny, height)) * width + Math.floorMod(nx, width)
            nx in 0 until width && ny in 0 until height -> ny * width + nx
            else -> -1
        }
    }

    /** Connexions courantes d'une cellule (solution tournée par sa rotation). */
    fun currentDirs(index: Int): Int = Dir.rotateMask(connections[index], rotations[index])

    /** Fait tourner une cellule de [steps] quarts de tour horaires. */
    fun rotateCell(index: Int, steps: Int) {
        rotations[index] = ((rotations[index] + steps) % 4 + 4) % 4
    }

    /** Une cellule terminale : une seule connexion et ce n'est pas le serveur. */
    fun isTerminal(index: Int): Boolean =
        index != serverIndex && Integer.bitCount(connections[index]) == 1

    /**
     * Cellule cachée (niveau Insane) : elle possède au moins
     * [hiddenThreshold] connexions — embranchement ou carrefour — et le
     * joueur ne voit pas ses câbles ; il doit raisonner par déduction,
     * comme dans l'original (qui la dit « blind »). L'équipement (serveur,
     * terminal) reste visible.
     */
    fun isHidden(index: Int): Boolean =
        Integer.bitCount(connections[index]) >= hiddenThreshold

    /** Verrouille / déverrouille une cellule (appui long). */
    fun toggleLock(index: Int) {
        locked[index] = !locked[index]
    }

    /** Révèle les câbles d'une cellule cachée (solveur, fin de partie). */
    fun reveal(index: Int) {
        revealed[index] = true
    }

    /**
     * Liste des cellules que l'auto-solveur doit tourner pour remettre le
     * plateau en solution, dans l'ordre d'un parcours en largeur depuis le
     * serveur : le réseau se construit visuellement depuis le cœur, comme
     * dans l'original. La rotation précise est recalculée au moment de jouer
     * chaque coup, donc la liste reste valable même si le joueur continue
     * à toucher le plateau pendant que le solveur travaille.
     */
    fun solveMoves(): List<Int> {
        val visited = BooleanArray(size)
        val queue = ArrayDeque<Int>()
        val moves = mutableListOf<Int>()
        visited[serverIndex] = true
        queue.addLast(serverIndex)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (rotations[current] != 0) moves.add(current)
            // Parcours de la SOLUTION (pas des rotations courantes) : l'ordre
            // est stable, indépendant des coups déjà joués.
            val mask = connections[current]
            for (dir in Dir.entries) {
                if (!Dir.contains(mask, dir)) continue
                val neighbor = neighborIndex(current, dir)
                if (neighbor < 0 || visited[neighbor]) continue
                visited[neighbor] = true
                queue.addLast(neighbor)
            }
        }
        return moves
    }

    /**
     * Cellules connectées au serveur, compte tenu des rotations courantes.
     * Parcours en largeur : une arête existe si les deux cellules se
     * déclarent mutuellement (masques courants symétriques).
     */
    fun connectedCells(useRotations: Boolean = true): BooleanArray {
        val connected = BooleanArray(size)
        val queue = ArrayDeque<Int>()
        connected[serverIndex] = true
        queue.addLast(serverIndex)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val mask = if (useRotations) currentDirs(current) else connections[current]
            for (dir in Dir.entries) {
                if (!Dir.contains(mask, dir)) continue
                val neighbor = neighborIndex(current, dir)
                if (neighbor < 0 || connected[neighbor]) continue
                val neighborMask =
                    if (useRotations) currentDirs(neighbor) else connections[neighbor]
                if (!Dir.contains(neighborMask, dir.opposite)) continue
                connected[neighbor] = true
                queue.addLast(neighbor)
            }
        }
        return connected
    }

    /** Partie gagnée : toutes les cellules sont reliées au serveur. */
    fun isSolved(): Boolean = connectedCells().all { it }

    /**
     * La solution théorique est-elle bien connexe (garantie de génération,
     * utilisée par les tests) ?
     */
    fun solutionIsSound(): Boolean = connectedCells(useRotations = false).all { it }

    companion object {
        /** Fabrique un plateau mélangé pour le niveau [skill] avec une graine. */
        fun generate(skill: Skill, seed: Long): Board =
            Board(skill.width, skill.height, skill.wrap, skill.hiddenThreshold, seed)
    }
}
