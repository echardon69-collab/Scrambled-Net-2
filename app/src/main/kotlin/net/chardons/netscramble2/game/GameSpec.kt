package net.chardons.netscramble2.game

/**
 * Photographie complète d'une partie en cours, sérialisable en une
 * chaîne compacte pour la persistance : la partie survit à la mort du
 * processus (kill système, mémoire basse).
 *
 * Le plateau est entièrement reproductible depuis (niveau, graine,
 * générateur, dimensions, racine) — la génération et le mélange sont
 * déterministes — donc seuls les états modifiés par le joueur
 * (rotations, verrous, cellules révélées), l'avancement général
 * (coups, chrono, solveur, file du solveur, cellule précédemment
 * touchée) et la CAMÉRA voyagent.
 *
 * La caméra ([CameraSpec], chantier « centrage Néant ») : position du
 * centre de la vue en coordonnées de cellules et zoom en dp — une
 * représentation indépendante de l'écran, de la densité et de
 * l'orientation. Elle est utile aux trois niveaux de la famille
 * Infini (pan libre, caméra modulo) ; pour les niveaux encadrés,
 * seule la position ne sert à rien ([ clampPan ] de la vue recentre),
 * le zoom reste pertinent.
 *
 * Format « v5 », champs séparés par « | » :
 *   v5|NIVEAU|graine|GÉNÉRATEUR|largeur|hauteur|racine|coups|millisecondes|solveur|précédente|file|rotations|verrous|révélations|scellées|touchées|indices|plafond|camX|camY|zoom
 * où les rotations sont un chiffre 0 à 3 par cellule (sans séparateur),
 * les verrous, révélations, scellées et touchées un 0 ou 1 par cellule,
 * la file du solveur des indices séparés par des virgules (vide si
 * inactive), « indices » et « plafond » les compteurs du chantier
 * Aide à la résolution (le plafond vaut -1 pour une partie née avant,
 * à recalculer à la restauration), et la caméra trois ENTIERS
 * (piège maison : jamais de flottant textuel, les locales écrivent
 * « 1.5 » ou « 1,5 ») : position en MILLI-CELLULES, zoom en
 * CENTIÈMES de dp. La « racine » est une consigne de
 * reconstruction : -1 = retirée du flux du générateur comme à la
 * création ; sinon imposée sans tirer (les anciennes v2
 * réenregistrées, serveur central).
 *
 * Le format « v4 » d'avant l'aide à la résolution (18 champs, sans
 * scellées, touchées, indices ni plafond) reste décodable à
 * l'identique : les scellées et touchées repartent à vide, aucune
 * indice n'a été consommé et le plafond vaut la sentinelle -1
 * (recalculée à la restauration) — puis la partie se réenregistre
 * en v5.
 *
 * Le format « v3 » d'avant la caméra (mêmes champs sans les trois
 * derniers) reste décodable à l'identique : une partie sauvegardée
 * avant la mise à jour se restaure exactement — sans caméra, la vue
 * repose la sienne (centrage Néant pour un Néant, centrage plateau
 * pour le reste de la famille) — puis se réenregistre en v5.
 *
 * Le format « v2 » d'avant le chantier tailles (grilles carrées par
 * niveau, générateur arbre, serveur central) reste décodable à
 * l'identique, lui aussi sans caméra.
 *
 * [ decode ] renvoie null sur toute donnée invalide : une chaîne
 * corrompue ou d'une version future ne casse jamais le lancement,
 * elle est simplement ignorée (nouvelle partie).
 */
/**
 * Photographie de la caméra de la vue, la seule partie de la
 * sauvegarde qui ne décrit pas le plateau : position du CENTRE de la
 * vue en coordonnées de cellules (milli-cellules, normalisée modulo
 * les côtés du plateau — pour la famille Infini et sa caméra modulo,
 * c'est la forme canonique d'une infinité de pans équivalents) et
 * niveau de zoom en centièmes de dp (indépendant de la densité).
 * Encodée en ENTIERS : jamais de flottant textuel.
 */
data class CameraSpec(
    val centerMilliX: Int,
    val centerMilliY: Int,
    val zoomCentiDp: Int
) {
    init {
        require(centerMilliX >= 0 && centerMilliY >= 0)
        require(zoomCentiDp > 0)
    }
}

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
    val revealed: BooleanArray,
    /**
     * Cellules scellées par un indice correctif (v5). Paramètre sans
     * « val » : la propriété effective repart à vide si absent, le
     * cas des parties décodées des formats v4 et moins.
     */
    sealedCells: BooleanArray? = null,
    /** Cellules touchées par le joueur (v5), même mécanique. */
    touched: BooleanArray? = null,
    /** Indices consommés dans cette partie (v5). */
    val hintsUsed: Int = 0,
    /**
     * Plafond d'indices de la partie (v5). Sentinelle -1 : partie
     * née avant le chantier, plafond recalculé à la restauration.
     */
    val hintsAllowed: Int = -1,
    /**
     * Caméra de la vue au moment de la photographie (v4). Absente des
     * sauvegardes v3/v2 décodées : la vue repose alors la sienne.
     */
    val camera: CameraSpec? = null
) {
    /** Cellules scellées effectives (vides si la photographie est plus ancienne). */
    val sealedCells: BooleanArray = sealedCells ?: BooleanArray(width * height)

    /** Cellules touchées effectives (vides si la photographie est plus ancienne). */
    val touched: BooleanArray = touched ?: BooleanArray(width * height)

    init {
        require(width in 1..64 && height in 1..64)
        require(rotations.size == width * height)
        require(locked.size == rotations.size)
        require(revealed.size == rotations.size)
        // « this. » obligatoire : dans un bloc init, le paramètre de
        // construction nullable du même nom MASQUE la propriété
        // effective (piège vérifié par la CI d'eric le 15 nov. 2026).
        require(this.sealedCells.size == rotations.size)
        require(this.touched.size == rotations.size)
        require(root in -1 until width * height)
        require(hintsUsed >= 0)
        require(hintsAllowed >= -1)
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
        // Chantier Aide à la résolution (v5) : scellées, touchées,
        // indices consommés, plafond. Le plafond est écrit tel quel,
        // sentinelle -1 comprise (une partie v4 réenregistrée le
        // reste, il sera recalculé à la prochaine restauration).
        append(SEP)
        for (s in sealedCells) append(if (s) '1' else '0')
        append(SEP)
        for (t in touched) append(if (t) '1' else '0')
        append(SEP)
        append(hintsUsed)
        append(SEP)
        append(hintsAllowed)
        // Caméra : toujours écrite (v5). Une photographie sans caméra
        // (défensive : l'activité en fournit toujours une) garde le
        // zoom minimal, une position neutre.
        append(SEP)
        append(camera?.centerMilliX ?: 0)
        append(SEP)
        append(camera?.centerMilliY ?: 0)
        append(SEP)
        append(camera?.zoomCentiDp ?: MIN_ZOOM_CENTI_DP)
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
        sealedCells.copyInto(board.sealedCells)
        touched.copyInto(board.touched)
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
                revealed.contentEquals(other.revealed) &&
                sealedCells.contentEquals(other.sealedCells) &&
                touched.contentEquals(other.touched) &&
                hintsUsed == other.hintsUsed &&
                hintsAllowed == other.hintsAllowed &&
                camera == other.camera
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
        result = 31 * result + sealedCells.contentHashCode()
        result = 31 * result + touched.contentHashCode()
        result = 31 * result + hintsUsed
        result = 31 * result + hintsAllowed
        result = 31 * result + camera.hashCode()
        return result
    }

    companion object {
        private const val VERSION = "v5"
        private const val PREVIOUS_VERSION = "v4"
        private const val V3_VERSION = "v3"
        private const val LEGACY_VERSION = "v2"
        private const val SEP = '|'
        private const val QUEUE_SEP = ","
        private const val FIELDS = 22
        private const val PREVIOUS_FIELDS = 18
        private const val V3_FIELDS = 15
        private const val LEGACY_FIELDS = 11

        /**
         * Zoom minimal en centièmes de dp (MIN_CELL_DP de la vue) :
         * borne BASSE du zoom accepté au décodage — le plancher de la
         * famille Infini, jamais contourné par une sauvegarde falsifiée.
         */
        const val MIN_ZOOM_CENTI_DP = 2_600

        /** Zoom maximal en centièmes de dp (MAX_CELL_DP de la vue). */
        const val MAX_ZOOM_CENTI_DP = 9_600

        /** Position des trois champs caméra dans le format v5. */
        private const val CAMERA_BASE = 19

        /** Position des trois champs caméra dans le format v4. */
        private const val CAMERA_BASE_PREVIOUS = 15

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
                    if (parts.size == FIELDS) decodeFields(parts, version = 5) else null
                PREVIOUS_VERSION ->
                    if (parts.size == PREVIOUS_FIELDS) decodeFields(parts, version = 4) else null
                V3_VERSION ->
                    if (parts.size == V3_FIELDS) decodeFields(parts, version = 3) else null
                LEGACY_VERSION ->
                    if (parts.size == LEGACY_FIELDS) decodeFields(parts, version = 2) else null
                else -> null
            }
        }

        /**
         * Analyse commune aux quatre versions : en v3 et plus,
         * générateur, dimensions et racine sont explicites ; en v2,
         * ils sont reconstitués (générateur TREE, grille carrée du
         * niveau, serveur central). Les champs du joueur sont décalés
         * d'un nombre de positions propre à chaque version.
         */
        private fun decodeFields(parts: List<String>, version: Int): GameSpec? {
            val legacy = version <= 2
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
            // Chantier Aide à la résolution (v5 uniquement) : scellées
            // et touchées, indices consommés et plafond. Toute cellule
            // scellée sans être verrouillée est une incohérence : la
            // partie est rejetée, jamais de confiance. Les versions
            // précédentes repartent à vide, plafond en sentinelle -1
            // (recalculé à la restauration).
            val sealedCells: BooleanArray
            val touched: BooleanArray
            val hintsUsed: Int
            val hintsAllowed: Int
            if (version >= 5) {
                val sealStr = parts[15]
                val touchStr = parts[16]
                if (sealStr.length != size || touchStr.length != size) return null
                sealedCells = BooleanArray(size)
                touched = BooleanArray(size)
                for (i in 0 until size) {
                    val sealBit = sealStr[i]
                    val touchBit = touchStr[i]
                    if (sealBit != '0' && sealBit != '1') return null
                    if (touchBit != '0' && touchBit != '1') return null
                    if (sealBit == '1' && !locked[i]) return null
                    sealedCells[i] = sealBit == '1'
                    touched[i] = touchBit == '1'
                }
                hintsUsed = parts[17].toIntOrNull() ?: return null
                hintsAllowed = parts[18].toIntOrNull() ?: return null
                if (hintsUsed < 0 || hintsAllowed < -1) return null
            } else {
                sealedCells = BooleanArray(size)
                touched = BooleanArray(size)
                hintsUsed = 0
                hintsAllowed = -1
            }
            // Caméra : en v4 et v5 (trois champs supplémentaires).
            // Une position hors plateau (milli-cellules >= côté x 1000,
            // modulo exclu) ou un zoom hors bornes rejette la partie,
            // comme toute autre incohérence — jamais de confiance.
            val cameraBase = if (version >= 5) CAMERA_BASE else CAMERA_BASE_PREVIOUS
            val camera: CameraSpec? =
                if (!legacy && version >= 4) {
                    val camX = parts[cameraBase].toIntOrNull() ?: return null
                    val camY = parts[cameraBase + 1].toIntOrNull() ?: return null
                    val zoom = parts[cameraBase + 2].toIntOrNull() ?: return null
                    if (camX !in 0 until width * 1_000) return null
                    if (camY !in 0 until height * 1_000) return null
                    if (zoom !in MIN_ZOOM_CENTI_DP..MAX_ZOOM_CENTI_DP) return null
                    CameraSpec(centerMilliX = camX, centerMilliY = camY, zoomCentiDp = zoom)
                } else {
                    null
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
                    revealed = revealed,
                    sealedCells = sealedCells,
                    touched = touched,
                    hintsUsed = hintsUsed,
                    hintsAllowed = hintsAllowed,
                    camera = camera
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}
