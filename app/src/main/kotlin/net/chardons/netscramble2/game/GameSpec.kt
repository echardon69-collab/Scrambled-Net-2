package net.chardons.netscramble2.game

/**
 * Photographie complète d'une partie en cours, sérialisable en une
 * chaîne compacte pour la persistance : la partie survit à la mort du
 * processus (kill système, mémoire basse).
 *
 * Le plateau est entièrement reproductible depuis (niveau, graine,
 * générateur, dimensions, racine) — la génération et le mélange sont
 * déterministes — donc seuls les états modifiés par le joueur
 * (rotations, verrous, cellules révélées) et l'avancement général
 * (coups, chrono, solveur, file du solveur, cellule précédemment
 * touchée) voyagent.
 *
 * Format « v3 », champs séparés par « | » :
 *   v3|NIVEAU|graine|GÉNÉRATEUR|largeur|hauteur|racine|coups|millisecondes|solveur|précédente|file|rotations|verrous|révélations
 * où les rotations sont un chiffre 0 à 3 par cellule (sans séparateur),
 * les verrous et révélations un 0 ou 1 par cellule, et la file du
 * solveur des indices séparés par des virgules (vide si inactive). La
 * « racine » est une consigne de reconstruction : -1 = retirée du flux
 * du générateur comme à la création ; sinon imposée sans tirer (les
 * anciennes v2 réenregistrées, serveur central).
 *
 * Le format « v2 » d'avant le chantier tailles (grilles carrées par
 * niveau, générateur arbre, serveur central) reste décodable à
 * l'identique : une partie sauvegardée avant la mise à jour se
 * restaure exactement, puis se réenregistre en v3.
 *
 * [decode] renvoie null sur toute donnée invalide : une chaîne
 * corrompue ou d'une version future ne casse jamais le lancement,
 * elle est simplement ignorée (nouvelle partie).
 */
class GameSpec(
    val skill: Skill,
    val seed: Long,
    val width: Int,
    val height: Int,
    val generator: Generator,
    /**
     * Consigne de reconstruction de la racine du réseau, figée à la
     * création : -1 = la racine est retirée du flux du générateur à
     * la régénération, exactement comme à la création (parties du
     * format v3) ; sinon la racine est imposée sans consommer de
     * tirage — le cas des parties de l'ancien format v2, dont le code
     * d'alors plaçait le serveur au centre sans rien tirer. C'est ce
     * qui garantit la régénération à l'identique dans les deux cas.
     */
    val root: Int,
    val moves: Int,
    val elapsedMs: Long,
    val solverUsed: Boolean,
    val prevClicked: Int,
    val solveQueue: List<Int>,
    val rotations: IntArray,
    val locked: BooleanArray,
    val revealed: BooleanArray
) {
    init {
        require(width in 1..64 && height in 1..64)
        require(rotations.size == width * height)
        require(locked.size == rotations.size)
        require(revealed.size == rotations.size)
        require(root in -1 until width * height)
    }

    /** Sérialise la partie. Chaîne courte (quelques centaines d'octets). */
    fun encode(): String = buildString {
        append(VERSION).append(SEP)
        append(skill.name).append(SEP)
        append(seed).append(SEP)
        append(generator.name).append(SEP)
        append(width).append(SEP)
        append(height).append(SEP)
        append(root).append(SEP)
        append(moves).append(SEP)
        append(elapsedMs).append(SEP)
        append(solverUsed).append(SEP)
        append(prevClicked).append(SEP)
        append(solveQueue.joinToString(QUEUE_SEP)).append(SEP)
        for (r in rotations) append(r)
        append(SEP)
        for (l in locked) append(if (l) '1' else '0')
        append(SEP)
        for (r in revealed) append(if (r) '1' else '0')
    }

    /**
     * Reconstruit le plateau photographié : la génération déterministe
     * recrée la solution et le mélange d'origine (dimensions,
     * générateur et racine compris), puis les rotations, verrous et
     * révélations du joueur sont recopiés par-dessus.
     */
    fun buildBoard(): Board {
        val board = Board.generate(
            skill, width, height, generator, seed, forcedRoot = root
        )
        rotations.copyInto(board.rotations)
        locked.copyInto(board.locked)
        revealed.copyInto(board.revealed)
        return board
    }

    // Tableaux dans une classe : on compare les contenus, pas les références.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameSpec) return false
        return skill == other.skill &&
                seed == other.seed &&
                width == other.width &&
                height == other.height &&
                generator == other.generator &&
                root == other.root &&
                moves == other.moves &&
                elapsedMs == other.elapsedMs &&
                solverUsed == other.solverUsed &&
                prevClicked == other.prevClicked &&
                solveQueue == other.solveQueue &&
                rotations.contentEquals(other.rotations) &&
                locked.contentEquals(other.locked) &&
                revealed.contentEquals(other.revealed)
    }

    override fun hashCode(): Int {
        var result = skill.hashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + generator.hashCode()
        result = 31 * result + root
        result = 31 * result + moves
        result = 31 * result + elapsedMs.hashCode()
        result = 31 * result + solverUsed.hashCode()
        result = 31 * result + prevClicked
        result = 31 * result + solveQueue.hashCode()
        result = 31 * result + rotations.contentHashCode()
        result = 31 * result + locked.contentHashCode()
        result = 31 * result + revealed.contentHashCode()
        return result
    }

    companion object {
        private const val VERSION = "v3"
        private const val LEGACY_VERSION = "v2"
        private const val SEP = '|'
        private const val QUEUE_SEP = ","
        private const val FIELDS = 15
        private const val LEGACY_FIELDS = 11

        /**
         * Tailles historiques du format v2 : grilles carrées par niveau,
         * celles de l'ancien enum Skill. Une v2 se restaure toujours
         * avec le générateur TREE (le seul de l'époque) et son serveur
         * central d'alors.
         */
        private val LEGACY_SIZES = mapOf(
            Skill.NOVICE to (5 to 5),
            Skill.NORMAL to (7 to 7),
            Skill.EXPERT to (9 to 9),
            Skill.MASTER to (9 to 9),
            Skill.INSANE to (11 to 11)
        )

        /** Analyse une chaîne sauvegardée ; null si absente ou invalide. */
        fun decode(spec: String?): GameSpec? {
            if (spec == null) return null
            val parts = spec.split(SEP)
            return when (parts.firstOrNull()) {
                VERSION ->
                    if (parts.size == FIELDS) decodeFields(parts, legacy = false) else null
                LEGACY_VERSION ->
                    if (parts.size == LEGACY_FIELDS) decodeFields(parts, legacy = true) else null
                else -> null
            }
        }

        /**
         * Analyse commune aux deux versions : en v3, générateur,
         * dimensions et racine sont explicites ; en v2, ils sont
         * reconstitués (générateur TREE, grille carrée du niveau, serveur
         * central). Les champs du joueur sont décalés d'un nombre de
         * positions propre à chaque version.
         */
        private fun decodeFields(parts: List<String>, legacy: Boolean): GameSpec? {
            val skill = Skill.entries.firstOrNull { it.name == parts[1] } ?: return null

            val generator: Generator
            val width: Int
            val height: Int
            val root: Int
            if (legacy) {
                generator = Generator.TREE
                val legacySize = LEGACY_SIZES[skill] ?: return null
                width = legacySize.first
                height = legacySize.second
                root = (height / 2) * width + (width / 2)
            } else {
                generator = Generator.entries.firstOrNull { it.name == parts[3] }
                    ?: return null
                width = parts[4].toIntOrNull() ?: return null
                height = parts[5].toIntOrNull() ?: return null
                root = parts[6].toIntOrNull() ?: return null
            }
            if (width !in 1..64 || height !in 1..64) return null
            val size = width * height
            if (root !in -1 until size) return null

            // Position des champs du joueur selon la version : en v2,
            // coups|ms|solveur|précédente|file|rotations|verrous|révélations
            // suivent NIVEAU et graine ; en v3, ils suivent en plus
            // générateur, dimensions et racine.
            val base = if (legacy) 3 else 7
            val solverUsed = when (parts[base + 2]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val rotStr = parts[base + 5]
            val lockStr = parts[base + 6]
            val revStr = parts[base + 7]
            if (rotStr.length != size || lockStr.length != size || revStr.length != size) {
                return null
            }
            val rotations = IntArray(size)
            for (i in 0 until size) {
                val digit = rotStr[i] - '0'
                if (digit !in 0..3) return null
                rotations[i] = digit
            }
            val locked = BooleanArray(size)
            val revealed = BooleanArray(size)
            for (i in 0 until size) {
                val lockBit = lockStr[i]
                val revBit = revStr[i]
                if (lockBit != '0' && lockBit != '1') return null
                if (revBit != '0' && revBit != '1') return null
                locked[i] = lockBit == '1'
                revealed[i] = revBit == '1'
            }
            return try {
                val seed = parts[2].toLong()
                val moves = parts[base].toInt()
                val elapsedMs = parts[base + 1].toLong()
                val prevClicked = parts[base + 3].toInt()
                val queue = parts[base + 4].split(QUEUE_SEP)
                    .filter { it.isNotEmpty() }
                    .map { it.toInt() }
                // Cohérence des données : toute incohérence est rejetée.
                if (moves < 0 || elapsedMs < 0) return null
                if (prevClicked < -1 || prevClicked >= size) return null
                if (queue.any { it !in 0..<size }) return null
                GameSpec(
                    skill = skill,
                    seed = seed,
                    width = width,
                    height = height,
                    generator = generator,
                    root = root,
                    moves = moves,
                    elapsedMs = elapsedMs,
                    solverUsed = solverUsed,
                    prevClicked = prevClicked,
                    solveQueue = queue,
                    rotations = rotations,
                    locked = locked,
                    revealed = revealed
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
