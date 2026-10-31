package net.chardons.netscramble2.game

/**
 * Photographie complète d'une partie en cours, sérialisable en une
 * chaîne compacte pour la persistance : la partie survit à la mort du
 * processus (kill système, mémoire basse).
 *
 * Le plateau est entièrement reproductible depuis (niveau, graine) —
 * la génération et le mélange sont déterministes — donc seuls les
 * états modifiés par le joueur (rotations, verrous, cellules
 * révélées) et l'avancement général (clics, chrono, solveur,
 * file du solveur, cellule précédemment touchée) voyagent.
 *
 * Format « v1 », champs séparés par « | » :
 *   v1|NIVEAU|graine|clics|millisecondes|solveur|précédente|file|rotations|verrous|révélations
 * où les rotations sont un chiffre 0 à 3 par cellule (sans séparateur),
 * les verrous et révélations un 0 ou 1 par cellule, et la file du
 * solveur des indices séparés par des virgules (vide si inactive).
 *
 * [decode] renvoie null sur toute donnée invalide : une chaîne
 * corrompue ou d'une version future ne casse jamais le lancement,
 * elle est simplement ignorée (nouvelle partie).
 */
class GameSpec(
    val skill: Skill,
    val seed: Long,
    val clicks: Int,
    val elapsedMs: Long,
    val solverUsed: Boolean,
    val prevClicked: Int,
    val solveQueue: List<Int>,
    val rotations: IntArray,
    val locked: BooleanArray,
    val revealed: BooleanArray
) {
    init {
        require(rotations.size == skill.width * skill.height)
        require(locked.size == rotations.size)
        require(revealed.size == rotations.size)
    }

    /** Sérialise la partie. Chaîne courte (quelques centaines d'octets). */
    fun encode(): String = buildString {
        append(VERSION).append(SEP)
        append(skill.name).append(SEP)
        append(seed).append(SEP)
        append(clicks).append(SEP)
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
     * recrée la solution et le mélange d'origine, puis les rotations,
     * verrous et révélations du joueur sont recopiés par-dessus.
     */
    fun buildBoard(): Board {
        val board = Board.generate(skill, seed)
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
                clicks == other.clicks &&
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
        result = 31 * result + clicks
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
        private const val VERSION = "v1"
        private const val SEP = '|'
        private const val QUEUE_SEP = ","
        private const val FIELDS = 11

        /** Analyse une chaîne sauvegardée ; null si absente ou invalide. */
        fun decode(spec: String?): GameSpec? {
            if (spec == null) return null
            val parts = spec.split(SEP)
            if (parts.size != FIELDS || parts[0] != VERSION) return null
            val skill = Skill.entries.firstOrNull { it.name == parts[1] } ?: return null
            val solverUsed = when (parts[5]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val rotStr = parts[8]
            val lockStr = parts[9]
            val revStr = parts[10]
            val size = skill.width * skill.height
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
                val clicks = parts[3].toInt()
                val elapsedMs = parts[4].toLong()
                val prevClicked = parts[6].toInt()
                val queue = parts[7].split(QUEUE_SEP)
                    .filter { it.isNotEmpty() }
                    .map { it.toInt() }
                // Cohérence des données : toute incohérence est rejetée.
                if (clicks < 0 || elapsedMs < 0) return null
                if (prevClicked < -1 || prevClicked >= size) return null
                if (queue.any { it !in 0..<size }) return null
                GameSpec(
                    skill = skill,
                    seed = seed,
                    clicks = clicks,
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
