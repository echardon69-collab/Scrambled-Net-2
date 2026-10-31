package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du plateau : génération, mélange, connexité, wrap.
 * Le moteur étant pur (aucune dépendance Android), tout tourne sur la JVM.
 */
class BoardTest {

    @Test
    fun `generated solution is always fully connected`() {
        for (skill in Skill.entries) {
            repeat(25) { attempt ->
                val board = Board.generate(skill, seed = attempt * 1_000_003L + skill.ordinal)
                assertTrue(
                    "La solution doit être connexe ($skill, essai $attempt)",
                    board.solutionIsSound()
                )
            }
        }
    }

    @Test
    fun `scrambled board is never solved at start`() {
        repeat(50) { attempt ->
            val board = Board.generate(Skill.NOVICE, seed = attempt + 1L)
            assertFalse("Le plateau mélangé ne doit pas être résolu", board.isSolved())
        }
    }

    @Test
    fun `board is solvable by rotating every tile back`() {
        repeat(25) { attempt ->
            val board = Board.generate(Skill.NORMAL, seed = attempt + 42L)
            // Annule les rotations : on retrouve l'arbre couvrant.
            for (i in board.rotations.indices) board.rotations[i] = 0
            assertTrue(board.isSolved())
        }
    }

    @Test
    fun `same seed produces the same board`() {
        val a = Board.generate(Skill.EXPERT, seed = 1234L)
        val b = Board.generate(Skill.EXPERT, seed = 1234L)
        assertTrue(a.connections.contentEquals(b.connections))
        assertTrue(a.rotations.contentEquals(b.rotations))

        val c = Board.generate(Skill.EXPERT, seed = 5678L)
        assertNotEquals(a.connections.toList(), c.connections.toList())
    }

    @Test
    fun `wrap-around boards connect across edges`() {
        val wrapBoard = Board(3, 3, wrap = true, blind = false, seed = 1L)
        // Bord gauche de la ligne 0 : le voisin LEFT est le bord droit.
        assertEquals(2, wrapBoard.neighborIndex(0, Dir.LEFT))
        // Bord haut : le voisin UP est la dernière ligne.
        assertEquals(6, wrapBoard.neighborIndex(0, Dir.UP))

        val plainBoard = Board(3, 3, wrap = false, blind = false, seed = 1L)
        assertEquals(-1, plainBoard.neighborIndex(0, Dir.LEFT))
        assertEquals(-1, plainBoard.neighborIndex(0, Dir.UP))
    }

    @Test
    fun `rotation is cyclic`() {
        val board = Board.generate(Skill.NOVICE, seed = 7L)
        val before = board.currentDirs(0)
        board.rotateCell(0, 4)
        assertEquals(before, board.currentDirs(0))
        board.rotateCell(0, 5) // ≡ +1
        assertEquals(Dir.rotateMask(before, 1), board.currentDirs(0))
    }

    @Test
    fun `server is centered and terminals are leaves`() {
        val board = Board.generate(Skill.NOVICE, seed = 3L)
        assertEquals(2 * 5 + 2, board.serverIndex) // centre d'une grille 5x5
        // Le serveur fait partie de l'arbre : au moins une connexion.
        assertTrue(board.connections[board.serverIndex] != Dir.NO_DIRS)
        for (i in 0 until board.size) {
            if (board.isTerminal(i)) {
                assertEquals(1, Integer.bitCount(board.connections[i]))
            }
        }
    }
}
