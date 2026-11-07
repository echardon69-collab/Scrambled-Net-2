package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la persistance de partie (GameSpec) : aller-retour v3,
 * régénération à l'identique pour les deux générateurs,
 * rétrocompatibilité du format v2, robustesse aux données corrompues.
 * La sérialisation est pure — tout tourne sur la JVM.
 */
class GameSpecTest {

    /**
     * Fabrique une photographie d'un plateau après quelques coups. La
     * racine transportée est la consigne de reconstruction du plateau
     * (celle qu'il a reçue à sa création), pas sa position.
     */
    private fun snapshotOf(
        board: Board,
        skill: Skill,
        seed: Long,
        moves: Int = 3,
        elapsedMs: Long = 42_500L,
        solverUsed: Boolean = false,
        prevClicked: Int = -1,
        solveQueue: List<Int> = emptyList()
    ): GameSpec = GameSpec(
        skill = skill,
        seed = seed,
        width = board.width,
        height = board.height,
        generator = board.generator,
        root = board.forcedRoot,
        moves = moves,
        elapsedMs = elapsedMs,
        solverUsed = solverUsed,
        prevClicked = prevClicked,
        solveQueue = solveQueue,
        rotations = board.rotations.copyOf(),
        locked = board.locked.copyOf(),
        revealed = board.revealed.copyOf()
    )

    @Test
    fun `encode then decode preserves every field`() {
        val board = Board.generate(Skill.EXPERT, 7, 11, Generator.TREE, seed = 1234L)
        board.rotateCell(5, 2)
        board.rotateCell(17, 3)
        board.toggleLock(5)
        board.reveal(40)
        val spec = snapshotOf(
            board, Skill.EXPERT, seed = 1234L, prevClicked = 17,
            solveQueue = listOf(3, 7, 42)
        )
        val decoded = GameSpec.decode(spec.encode())
        assertEquals(spec, decoded)
    }

    @Test
    fun `restored board matches the original mid-game board`() {
        val board = Board.generate(Skill.INSANE, 7, 11, Generator.TREE, seed = -987L)
        repeat(10) { i ->
            board.rotateCell(i * 3 % board.size, (i % 3) + 1)
        }
        board.toggleLock(2)
        board.toggleLock(60)
        val restored = snapshotOf(board, Skill.INSANE, seed = -987L).buildBoard()
        // La régénération doit reproduire la solution ET le serveur,
        // puis recopier les coups du joueur par-dessus.
        assertTrue(restored.connections.contentEquals(board.connections))
        assertEquals(board.serverIndex, restored.serverIndex)
        assertTrue(restored.rotations.contentEquals(board.rotations))
        assertTrue(restored.locked.contentEquals(board.locked))
        assertTrue(restored.revealed.contentEquals(board.revealed))
        assertTrue(
            restored.connectedCells().contentEquals(board.connectedCells())
        )
        assertEquals(board.isSolved(), restored.isSolved())
    }

    @Test
    fun `restored original-generator board matches too`() {
        // Le générateur ORIGINAL se régénère aussi à l'identique : sa
        // construction (racines tirées essai par essai) ne dépend que
        // de la graine et des dimensions.
        val board = Board.generate(
            Skill.MASTER, 11, 7, Generator.ORIGINAL, seed = 77L
        )
        repeat(5) { i -> board.rotateCell(i * 7 % board.size, i % 4) }
        val restored = snapshotOf(board, Skill.MASTER, seed = 77L).buildBoard()
        assertTrue(restored.connections.contentEquals(board.connections))
        assertEquals(board.serverIndex, restored.serverIndex)
        assertTrue(restored.rotations.contentEquals(board.rotations))
        assertEquals(board.isSolved(), restored.isSolved())
    }

    @Test
    fun `solved board stays solved after a round trip`() {
        for (generator in Generator.entries) {
            val board = Board.generate(Skill.NORMAL, 9, 7, generator, seed = 5L)
            // Remettre toutes les rotations à zéro = plateau résolu.
            for (i in 0 until board.size) {
                while (board.rotations[i] != 0) board.rotateCell(i, 1)
            }
            assertTrue(
                "Toutes rotations nulles = résolu ($generator)",
                board.isSolved()
            )
            val restored = snapshotOf(board, Skill.NORMAL, seed = 5L).buildBoard()
            assertTrue(restored.isSolved())
        }
    }

    @Test
    fun `empty solver queue survives the round trip`() {
        val spec = snapshotOf(
            Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 1L),
            Skill.NOVICE, seed = 1L, solverUsed = true, solveQueue = emptyList()
        )
        val decoded = GameSpec.decode(spec.encode())
        assertNotNull(decoded)
        assertTrue(decoded!!.solveQueue.isEmpty())
        assertTrue(decoded.solverUsed)
    }

    @Test
    fun `legacy v2 game decodes and rebuilds exactly the old board`() {
        // Une sauvegarde d'avant le chantier tailles : grille carrée du
        // niveau, générateur TREE, serveur central — le code d'alors ne
        // tirait pas la racine du générateur, la consigne de
        // reconstruction l'impose donc sans tirage.
        val seed = 987654321L
        val digits = IntArray(49) { (it * 3) % 4 }
        val v2 = listOf(
            "v2", "NORMAL", seed.toString(), "17", "42000", "false", "-1", "5,9",
            digits.joinToString(""), "0".repeat(49), "0".repeat(49)
        ).joinToString("|")

        val spec = GameSpec.decode(v2)
        assertNotNull(spec)
        val decoded = spec!!
        assertEquals(Skill.NORMAL, decoded.skill)
        assertEquals(Generator.TREE, decoded.generator)
        assertEquals(7, decoded.width)
        assertEquals(7, decoded.height)
        assertEquals(24, decoded.root) // serveur central de la grille 7 x 7
        assertEquals(digits.toList(), decoded.rotations.toList())
        assertEquals(17, decoded.moves)

        // Reconstruction : le flux rejoué est exactement celui de
        // l'ancien code — même solution, même serveur — et les coups du
        // joueur sont recopiés par-dessus.
        val expected = Board.generate(
            Skill.NORMAL, 7, 7, Generator.TREE, seed, forcedRoot = 24
        )
        val restored = decoded.buildBoard()
        assertTrue(restored.connections.contentEquals(expected.connections))
        assertEquals(24, restored.serverIndex)
        assertEquals(digits.toList(), restored.rotations.toList())
    }

    @Test
    fun `decode rejects null, empty and foreign-version strings`() {
        assertNull(GameSpec.decode(null))
        assertNull(GameSpec.decode(""))
        // v3 avec un corps de v2 (11 champs au lieu de 15) : rejet.
        assertNull(GameSpec.decode("v3|NOVICE|1|0|0|false|-1||00000|00000|00000"))
        // Versions inconnues : rejet, silencieux.
        assertNull(GameSpec.decode("v1|NOVICE|1|0|0|false|-1||00000|00000|00000"))
        assertNull(
            GameSpec.decode(
                "v4|NOVICE|1|TREE|5|5|12|0|0|false|-1||00000|00000|00000"
            )
        )
    }

    @Test
    fun `decode rejects corrupted or incoherent data`() {
        val board = Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 1L)
        val good = snapshotOf(board, Skill.NOVICE, seed = 1L).encode()
        assertNotNull(GameSpec.decode(good))
        val rotField = good.split('|')[12]
        assertEquals(25, rotField.length) // NOVICE 5 x 5 : 25 cellules

        // Chaque champ abîmé doit être rejeté, jamais faire crasher.
        assertNull(GameSpec.decode(good.replace("NOVICE", "TREMBLE")))
        assertNull(GameSpec.decode(good.replace("TREE", "PASUNGEN")))
        assertNull(GameSpec.decode(good.replace("false", "peut-être")))

        fun withField(index: Int, value: String): String {
            val parts = good.split('|').toMutableList()
            parts[index] = value
            return parts.joinToString("|")
        }

        assertNull(GameSpec.decode(withField(2, "pas-un-nombre"))) // graine
        assertNull(GameSpec.decode(withField(4, "99")))           // largeur absurde
        assertNull(GameSpec.decode(withField(6, "999")))         // racine hors plateau
        assertNull(GameSpec.decode(withField(6, "-2")))          // racine négative
        assertNull(GameSpec.decode(withField(7, "-3")))          // coups négatifs
        assertNull(GameSpec.decode(withField(10, "999")))        // cellule précédente hors plateau
        assertNull(GameSpec.decode(withField(11, "1,999,x")))    // file du solveur incohérente
        assertNull(GameSpec.decode(withField(12, "0000")))       // rotations trop courtes
        assertNull(GameSpec.decode(withField(12, "0".repeat(26)))) // rotations trop longues
        assertNull( // chiffre impossible
            GameSpec.decode(withField(12, "4" + "0".repeat(24)))
        )
    }

    @Test
    fun `snapshot of two different games decodes to distinct specs`() {
        val first = snapshotOf(
            Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 1L),
            Skill.NOVICE, seed = 1L
        )
        val second = snapshotOf(
            Board.generate(Skill.NOVICE, 5, 5, Generator.ORIGINAL, seed = 2L),
            Skill.NOVICE, seed = 2L
        )
        val decodedFirst = GameSpec.decode(first.encode())!!
        val decodedSecond = GameSpec.decode(second.encode())!!
        assertFalse(decodedFirst == decodedSecond)
        assertSame(Skill.NOVICE, decodedFirst.skill)
        assertEquals(Generator.TREE, decodedFirst.generator)
        assertEquals(Generator.ORIGINAL, decodedSecond.generator)
    }
}
