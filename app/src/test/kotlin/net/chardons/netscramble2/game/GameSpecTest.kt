package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la persistance de partie (GameSpec) : aller-retour complet,
 * reconstruction du plateau, robustesse aux données corrompues. La
 * sérialisation est pure — tout tourne sur la JVM.
 */
class GameSpecTest {

    /** Fabrique une photographie d'un plateau après quelques coups. */
    private fun snapshotOf(
        board: Board,
        seed: Long,
        clicks: Int = 3,
        elapsedMs: Long = 42_500L,
        solverUsed: Boolean = false,
        prevClicked: Int = -1,
        solveQueue: List<Int> = emptyList()
    ): GameSpec = GameSpec(
        skill = Skill.entries.first {
            it.width == board.width && it.height == board.height &&
                it.wrap == board.wrap && it.hiddenThreshold == board.hiddenThreshold
        },
        seed = seed,
        clicks = clicks,
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
        val board = Board.generate(Skill.EXPERT, seed = 1234L)
        board.rotateCell(5, 2)
        board.rotateCell(17, 3)
        board.toggleLock(5)
        board.reveal(40)
        val spec = snapshotOf(
            board, seed = 1234L, prevClicked = 17,
            solveQueue = listOf(3, 7, 42)
        )
        val decoded = GameSpec.decode(spec.encode())
        assertEquals(spec, decoded)
    }

    @Test
    fun `restored board matches the original mid-game board`() {
        val board = Board.generate(Skill.INSANE, seed = -987L)
        repeat(10) { i ->
            board.rotateCell(i * 3 % board.size, (i % 3) + 1)
        }
        board.toggleLock(2)
        board.toggleLock(60)
        val restored = snapshotOf(board, seed = -987L).buildBoard()
        assertTrue(restored.rotations.contentEquals(board.rotations))
        assertTrue(restored.locked.contentEquals(board.locked))
        assertTrue(restored.revealed.contentEquals(board.revealed))
        assertTrue(
            restored.connectedCells().contentEquals(board.connectedCells())
        )
        assertEquals(board.isSolved(), restored.isSolved())
    }

    @Test
    fun `restored board regenerates the identical solution (deterministic seed)`() {
        val board = Board.generate(Skill.MASTER, seed = 77L)
        val restored = snapshotOf(board, seed = 77L).buildBoard()
        // La solution régénérée doit être identique à l'originale, au masque près.
        assertTrue(restored.connections.contentEquals(board.connections))
        assertEquals(board.serverIndex, restored.serverIndex)
    }

    @Test
    fun `solved board stays solved after a round trip`() {
        val board = Board.generate(Skill.NORMAL, seed = 5L)
        // Remettre toutes les rotations à zéro = plateau résolu.
        for (i in 0 until board.size) {
            while (board.rotations[i] != 0) board.rotateCell(i, 1)
        }
        assertTrue(board.isSolved())
        val restored = snapshotOf(board, seed = 5L).buildBoard()
        assertTrue(restored.isSolved())
    }

    @Test
    fun `empty solver queue survives the round trip`() {
        val spec = snapshotOf(
            Board.generate(Skill.NOVICE, seed = 1L), seed = 1L,
            solverUsed = true, solveQueue = emptyList()
        )
        val decoded = GameSpec.decode(spec.encode())
        assertNotNull(decoded)
        assertTrue(decoded!!.solveQueue.isEmpty())
        assertTrue(decoded.solverUsed)
    }

    @Test
    fun `decode rejects null, empty and foreign-version strings`() {
        assertNull(GameSpec.decode(null))
        assertNull(GameSpec.decode(""))
        assertNull(GameSpec.decode("v0|NOVICE|1|0|0|false|-1||00000|00000|00000"))
        assertNull(GameSpec.decode("v2|NOVICE|1|0|0|false|-1||00000|00000|00000"))
    }

    @Test
    fun `decode rejects corrupted or incoherent data`() {
        val spec = snapshotOf(Board.generate(Skill.NOVICE, seed = 1L), seed = 1L)
        val good = spec.encode()
        val rotField = good.split('|')[8]
        assertEquals(25, rotField.length) // NOVICE : 5 x 5 cellules

        // Chaque champ abîmé doit être rejeté, jamais faire crasher.
        assertNull(GameSpec.decode(good.replace("NOVICE", "TREMBLE")))
        assertNull(GameSpec.decode(good.replace("false", "peut-être")))

        fun withRotations(rot: String): String {
            val parts = good.split('|').toMutableList()
            parts[8] = rot
            return parts.joinToString("|")
        }

        assertNull(GameSpec.decode(withRotations("0000")))                 // trop court
        assertNull(GameSpec.decode(withRotations("0".repeat(26))))         // trop long
        assertNull(GameSpec.decode(withRotations("4" + "0".repeat(24))))  // chiffre impossible

        fun withField(index: Int, value: String): String {
            val parts = good.split('|').toMutableList()
            parts[index] = value
            return parts.joinToString("|")
        }

        assertNull(GameSpec.decode(withField(2, "pas-un-nombre")))  // graine
        assertNull(GameSpec.decode(withField(3, "-3")))            // clics négatifs
        assertNull(GameSpec.decode(withField(6, "999")))           // cellule précédente hors plateau
        assertNull(GameSpec.decode(withField(7, "1,999,x")))       // file du solveur incohérente
    }

    @Test
    fun `snapshot of two different games decodes to distinct specs`() {
        val first = snapshotOf(Board.generate(Skill.NOVICE, seed = 1L), seed = 1L)
        val second = snapshotOf(Board.generate(Skill.NOVICE, seed = 2L), seed = 2L)
        val decodedFirst = GameSpec.decode(first.encode())!!
        val decodedSecond = GameSpec.decode(second.encode())!!
        assertFalse(decodedFirst == decodedSecond)
        assertSame(Skill.NOVICE, decodedFirst.skill)
    }
}
