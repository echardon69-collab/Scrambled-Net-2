package net.chardons.netscramble2.game

import kotlin.random.Random

/**
 * Générateur du réseau d'une partie.
 */
enum class Generator {
    /**
     * Traduction exacte de createNet de l'original (BoardView.java) :
     * croissance par branchements aléatoires depuis le serveur, jusqu'à
     * 10 essais pour couvrir au moins 85 pour cent des cellules. Les
     * cellules restantes sont libres : vides dans le plateau, elles ne
     * portent aucun câble. La partie est gagnée quand tous les
     * terminaux (cellules à une seule connexion) sont reliés — des
     * sections de câble inutilisées ne bloquent pas la victoire.
     */
    ORIGINAL,

    /**
     * Notre générateur historique : arbre couvrant aléatoire (parcours
     * en profondeur randomisé). Toutes les cellules sont actives et la
     * partie est gagnée quand tout le plateau est relié au serveur.
     */
    TREE
}

/**
 * Le plateau de jeu : un modèle PUR (aucune dépendance Android), donc
 * testable sur la JVM et portable tel quel.
 *
 * Représentation :
 *  - [connections] : masque de la SOLUTION pour chaque cellule (réseau
 *    généré une fois pour toutes ; avec le générateur ORIGINAL, une
 *    cellule libre garde le masque 0) ;
 *  - [rotations] : rotation courante (0..3 quarts de tour horaires) de
 *    chaque cellule — c'est ce que le joueur modifie ;
 *  - les connexions courantes d'une cellule = [Dir.rotateMask] de sa
 *    solution par sa rotation.
 *
 * Le plateau est toujours soluble par rotation : en remettant chaque
 * cellule à la rotation 0, on retrouve la solution générée.
 *
 * Tout est reproductible depuis (dimensions, générateur, graine,
 * racine) : la génération et le mélange tirent uniquement du
 * générateur pseudo-aléatoire enraciné sur la graine, ce qui permet à
 * une partie sauvegardée de se reconstruire à l'identique.
 */
class Board(
    val width: Int,
    val height: Int,
    val wrap: Boolean,
    /**
     * Seuil de masquage (niveau Dément) : une cellule avec au moins
     * autant de connexions masque ses câbles. 9 = jamais atteint.
     */
    val hiddenThreshold: Int,
    /** Générateur employé pour créer le réseau. */
    val generator: Generator,
    /**
     * Nombre maximal d'embranchements par cellule (générateur ORIGINAL),
     * repris de l'enum Skill de l'original.
     */
    val branches: Int,
    /**
     * Graine du flux pseudo-aléatoire : la partie entière est
     * reproductible depuis (dimensions, générateur, graine, racine).
     * Exposée (effet « électricité » du 12 nov. 2026) : les rythmes
     * de signature du serveur en dérivent sans consommer le flux
     * du générateur.
     */
    val seed: Long,
    /**
     * Niveau de la partie (endossé depuis le Skill de création). La
     * vue s'en sert pour ses modes de rendu : Infini (ENDLESS)
     * déclenche la caméra modulo du chantier du 12 nov. 2026 (le
     * plateau géant se répète à l'infini, sans anneau gris), les
     * autres niveaux gardent le plateau encadré et borné. Défaut
     * NOVICE pour le constructeur direct (les tests assemblent des
     * plateaux à la main) — la fabrique [generate] endosse toujours
     * le niveau réel.
     */
    val skill: Skill = Skill.NOVICE,
    /**
     * Racine imposée du réseau, ou -1 pour la tirer du générateur. Une
     * racine imposée ne consomme AUCUN tirage : c'est ce que fait la
     * reconstruction des anciennes parties v2, dont le code d'alors
     * plaçait le serveur au centre sans tirer la racine — leur flux
     * aléatoire doit être rejoué exactement. Les parties v3, elles,
     * portent -1 : la régénération retire la racine du flux comme à la
     * création, et retrouve ainsi le même arbre.
     */
    val forcedRoot: Int = -1
) {
    /** Nombre total de cellules. */
    val size: Int = width * height

    /**
     * Index de la cellule du serveur (la racine du réseau). Tiré au
     * hasard, comme l'original qui choisit un serveur au hasard à
     * chaque essai de createNet ; les anciennes parties restaurées
     * imposent la leur.
     */
    val serverIndex: Int

    /** Masque de connexions de la solution, par cellule. */
    val connections = IntArray(size)

    /** Rotation courante (0..3) de chaque cellule, choisie au mélange initial. */
    val rotations = IntArray(size)

    /** Cellules verrouillées par le joueur (appui long). */
    val locked = BooleanArray(size)

    /** Cellules cachées dont les câbles ont été révélés (solveur, fin). */
    val revealed = BooleanArray(size)

    init {
        val rnd = Random(seed)
        serverIndex = generate(rnd, forcedRoot)
        scramble(Random(seed xor SCRAMBLE_SALT))
    }

    // ******************************************************************** //
    // Génération.
    // ******************************************************************** //

    /**
     * Construit le réseau selon le générateur et renvoie l'index de la
     * racine (le serveur).
     */
    private fun generate(rnd: Random, forcedRoot: Int): Int = when (generator) {
        Generator.TREE -> {
            // Racine aléatoire, sauf racine imposée (reconstruction
            // d'une ancienne partie v2 : aucune valeur tirée, le flux
            // du générateur reste celui de l'époque). Une partie v3,
            // elle, garde -1 : la régénération retire la racine du
            // flux exactement comme à la création, et retrouve ainsi
            // le même arbre, au tirage près.
            val root =
                if (forcedRoot in 0 until size) forcedRoot else rnd.nextInt(size)
            generateTree(rnd, root)
            root
        }
        Generator.ORIGINAL -> generateOriginal(rnd)
    }

    /**
     * Arbre couvrant aléatoire (parcours en profondeur randomisé)
     * enraciné en [root] : chaque cellule obtient au moins une connexion,
     * les feuilles de l'arbre (une seule connexion) sont les
     * « terminaux ». Avec la racine centrale imposée (restauration des
     * anciennes parties), le réseau est exactement celui de l'ancien
     * code à même graine.
     */
    private fun generateTree(rnd: Random, root: Int) {
        val visited = BooleanArray(size)
        val stack = ArrayDeque<Int>()
        visited[root] = true
        stack.addLast(root)

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
     * Réplique exacte de createNet de l'original : la croissance part
     * d'une racine tirée au hasard À CHAQUE essai (le serveur bouge
     * tant que le réseau n'est pas assez dense) et se propage par
     * [addRandomDir], en différant parfois la cellule de tête pour
     * éviter les longues lignes droites. Un essai est accepté dès que
     * 85 pour cent des cellules sont actives, au bout de 10 essais au
     * plus — passé ce cap, l'original garde la dernière tentative.
     * Renvoie la racine retenue et laisse sa solution dans
     * [connections].
     */
    private fun generateOriginal(rnd: Random): Int {
        val minCells = (size * 0.85).toInt()
        var root = 0
        var cells = 0
        var tries = 0
        while (cells < minCells && tries < MAX_TRIES) {
            connections.fill(Dir.NO_DIRS)
            root = rnd.nextInt(width) + width * rnd.nextInt(height)

            // File des cellules en attente de connexion, la racine
            // d'abord — et une première extension la moitié du temps,
            // comme l'original.
            val list = ArrayDeque<Int>()
            list.addLast(root)
            if (rnd.nextBoolean()) addRandomDir(list, rnd)

            while (list.isNotEmpty()) {
                if (rnd.nextBoolean()) {
                    // Prolonger la tête de file ; la moitié du temps une
                    // seconde direction ; une troisième seulement pour
                    // trois branches ou plus (une fois sur trois), qui
                    // introduit les carrefours et parfois les croix.
                    addRandomDir(list, rnd)
                    if (rnd.nextBoolean()) addRandomDir(list, rnd)
                    if (branches >= 3 && rnd.nextInt(3) == 0) {
                        addRandomDir(list, rnd)
                    }
                } else {
                    // Différer la tête : la cellule reviendra plus tard,
                    // ce qui évite les filières trop rectilignes.
                    list.addLast(list.first())
                }
                list.removeFirst()
            }

            cells = connections.count { it != Dir.NO_DIRS }
            ++tries
        }
        return root
    }

    /**
     * Réplique de addRandomDir de l'original : relie la tête de [list] à
     * un voisin libre choisi au hasard. Le test de liberté porte sur le
     * masque de connexions — la racine, pas encore reliée, est « libre »
     * elle aussi et peut donc être rattrapée par la croissance,
     * exactement comme dans l'original. La cellule élue rejoint la file.
     */
    private fun addRandomDir(list: ArrayDeque<Int>, rnd: Random) {
        val cell = list.first()
        val free = mutableListOf<Dir>()
        for (dir in Dir.entries) {
            val neighbor = neighborIndex(cell, dir)
            if (neighbor >= 0 && connections[neighbor] == Dir.NO_DIRS) {
                free.add(dir)
            }
        }
        if (free.isEmpty()) return
        val key = free[rnd.nextInt(free.size)]
        val dest = neighborIndex(cell, key)
        connections[cell] = connections[cell] or key.bit
        connections[dest] = connections[dest] or key.opposite.bit
        list.addLast(dest)
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
     * Avec [wrap], les bords bouclent sur eux-mêmes (tore) — bornés au
     * plateau, comme decr et incr de l'original.
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
     * Cellule libre (générateur ORIGINAL) : aucune connexion, aucun
     * câble. Le joueur peut la toucher, il ne se passe rien.
     */
    fun isEmpty(index: Int): Boolean = connections[index] == Dir.NO_DIRS

    /**
     * Cellule cachée (niveau Dément) : elle possède au moins
     * [hiddenThreshold] connexions — embranchement ou carrefour — et le
     * joueur ne voit pas ses câbles ; il doit raisonner par déduction,
     * comme dans l'original (qui la dit « blind »). L'équipement
     * (serveur, terminal) reste visible.
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
     * dans l'original. La rotation précise est recalculée au moment de
     * jouer chaque coup, donc la liste reste valable même si le joueur
     * continue à toucher le plateau pendant que le solveur travaille.
     * Les cellules libres du générateur ORIGINAL n'y figurent jamais :
     * elles ne portent pas de câble.
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

    /**
     * Visibilité du brouillard d'Infini (phase C du chantier, 13 nov.
     * 2026) : une cellule est visible ssi elle est à distance de
     * Chebyshev <= [FOG_RADIUS_CELLS] d'une cellule [connected] au
     * serveur. Dérivation PURE depuis [connected] — aucun état
     * propre, rien à sérialiser : la zone visible s'ouvre quand on
     * connecte et se referme toute seule quand on débranche. Le tore
     * du plateau compte : la distance s'enroule par floorMod, comme
     * les câbles du niveau. La vue ne l'appelle que pour le niveau
     * Infini avec le réglage « Brouillard » actif ; la victoire
     * révèle tout (la vue retire alors le brouillard, la visibilité
     * dérivée n'est jamais gelée ni sauvegardée).
     */
    fun fogVisibility(connected: BooleanArray): BooleanArray {
        val visible = BooleanArray(size)
        val r = FOG_RADIUS_CELLS
        for (i in visible.indices) {
            if (!connected[i]) continue
            val cx = i % width
            val cy = i / width
            for (dy in -r..r) {
                val row = Math.floorMod(cy + dy, height) * width
                for (dx in -r..r) {
                    visible[row + Math.floorMod(cx + dx, width)] = true
                }
            }
        }
        return visible
    }

    /**
     * Partie gagnée, selon le générateur :
     *  - ORIGINAL : tous les terminaux (une seule connexion) sont reliés
     *    au serveur, comme isSolved de l'original — des sections de
     *    câble inutilisées ne bloquent pas la victoire ;
     *  - TREE : tout le plateau est relié au serveur.
     */
    fun isSolved(): Boolean {
        val connected = connectedCells()
        return when (generator) {
            Generator.TREE -> connected.all { it }
            Generator.ORIGINAL -> (0 until size).none { index ->
                Integer.bitCount(connections[index]) == 1 && !connected[index]
            }
        }
    }

    /**
     * La solution théorique est-elle saine (garantie de génération,
     * utilisée par les vérifications) ? Le réseau câblé est connexe
     * depuis le serveur — pour le générateur ORIGINAL, seules les
     * cellules actives comptent, les cellules libres ne font pas partie
     * du réseau.
     */
    fun solutionIsSound(): Boolean {
        val connected = connectedCells(useRotations = false)
        return (0 until size).all { index ->
            when (generator) {
                Generator.TREE -> connected[index]
                Generator.ORIGINAL ->
                    connections[index] == Dir.NO_DIRS || connected[index]
            }
        }
    }

    companion object {
        /**
         * Sel du générateur du mélange. Historique : ne jamais le
         * changer, les parties enregistrées en dépendent.
         */
        private const val SCRAMBLE_SALT = -0x5DEECE66DL

        /** Essais maximum du générateur ORIGINAL (l'original : 10). */
        private const val MAX_TRIES = 10

        /**
         * Rayon de Chebyshev du brouillard d'Infini (phase C) : une
         * cellule est visible ssi à cette distance au plus d'une
         * cellule connectée au serveur (spec validée le 12 nov. 2026).
         */
        const val FOG_RADIUS_CELLS = 2

        /**
         * Fabrique un plateau mélangé : niveau [skill] (règles, branches),
         * dimensions explicites (le niveau ne les porte plus), réseau
         * construit par [generator], le tout reproductible depuis la
         * graine. [forcedRoot] impose la racine (restauration).
         */
        fun generate(
            skill: Skill,
            width: Int,
            height: Int,
            generator: Generator,
            seed: Long,
            forcedRoot: Int = -1
        ): Board = Board(
            width = width,
            height = height,
            wrap = skill.wrap,
            hiddenThreshold = skill.hiddenThreshold,
            generator = generator,
            branches = skill.branches,
            seed = seed,
            skill = skill,
            forcedRoot = forcedRoot
        )
    }
}
