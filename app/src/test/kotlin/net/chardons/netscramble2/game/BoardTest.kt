package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du plateau : génération (les DEUX générateurs du chantier
 * tailles), mélange, connexité, wrap, dimensions Screen. Le moteur
 * étant pur (aucune dépendance Android), tout tourne sur la JVM.
 *
 * Les dimensions viennent désormais de Screen : les essais prennent la
 * matrice MEDIUM en portrait (comme un écran VGA+), sauf quand un test
 * veut une taille précise.
 */
class BoardTest {

    /** Dimensions du niveau sur la matrice MEDIUM, en portrait. */
    private fun dims(skill: Skill): Pair<Int, Int> =
        Screen.MEDIUM.boardSize(skill, landscape = false)

    @Test
    fun `generated solution is always sound, for both generators`() {
        for (skill in Skill.entries) {
            for (generator in Generator.entries) {
                val (width, height) = dims(skill)
                repeat(25) { attempt ->
                    val board = Board.generate(
                        skill, width, height, generator,
                        seed = attempt * 1_000_003L + skill.ordinal
                    )
                    assertTrue(
                        "La solution doit être saine ($skill/$generator, essai $attempt)",
                        board.solutionIsSound()
                    )
                }
            }
        }
    }

    @Test
    fun `tree generator wires every cell`() {
        repeat(10) { attempt ->
            val board = Board.generate(
                Skill.NORMAL, 9, 7, Generator.TREE, seed = attempt + 1L
            )
            assertFalse(
                "Aucune cellule libre avec le générateur TREE",
                (0 until board.size).any { board.isEmpty(it) }
            )
        }
    }

    @Test
    fun `original generator leaves free cells somewhere`() {
        // La croissance de l'original exige 85 pour cent de cellules
        // actives, pas 100 : sur une centaine de parties, certaines
        // doivent contenir des cellules libres (environ une sur quatre,
        // vérifié en simulation). Une cellule libre ne porte aucun
        // câble, dans les deux sens du terme.
        var withFree = 0
        repeat(100) { attempt ->
            val board = Board.generate(
                Skill.NORMAL, 9, 7, Generator.ORIGINAL,
                seed = attempt * 100_003L + 1L
            )
            for (i in 0 until board.size) {
                assertEquals(
                    board.connections[i] == Dir.NO_DIRS,
                    board.isEmpty(i)
                )
            }
            if ((0 until board.size).any { board.isEmpty(it) }) ++withFree
        }
        assertTrue("Des cellules libres doivent exister quelque part", withFree > 0)
    }

    @Test
    fun `scrambled board is never solved at start`() {
        for (generator in Generator.entries) {
            repeat(50) { attempt ->
                val board = Board.generate(
                    Skill.NOVICE, 5, 5, generator, seed = attempt + 1L
                )
                assertFalse(
                    "Le plateau mélangé ne doit pas être résolu ($generator)",
                    board.isSolved()
                )
            }
        }
    }

    @Test
    fun `board is solvable by rotating every tile back`() {
        for (generator in Generator.entries) {
            repeat(25) { attempt ->
                val board = Board.generate(
                    Skill.NORMAL, 9, 7, generator, seed = attempt + 42L
                )
                // Annule les rotations : on retrouve la solution
                // (l'arbre couvrant, ou le réseau de l'original).
                for (i in board.rotations.indices) board.rotations[i] = 0
                assertTrue(
                    "Toutes rotations nulles = résolu ($generator)",
                    board.isSolved()
                )
            }
        }
    }

    @Test
    fun `same seed produces the same board`() {
        val a = Board.generate(Skill.EXPERT, 7, 11, Generator.TREE, seed = 1234L)
        val b = Board.generate(Skill.EXPERT, 7, 11, Generator.TREE, seed = 1234L)
        assertTrue(a.connections.contentEquals(b.connections))
        assertTrue(a.rotations.contentEquals(b.rotations))
        assertEquals(a.serverIndex, b.serverIndex)

        val c = Board.generate(Skill.EXPERT, 7, 11, Generator.TREE, seed = 5678L)
        assertNotEquals(a.connections.toList(), c.connections.toList())
    }

    @Test
    fun `wrap-around boards connect across edges`() {
        val wrapBoard = Board(
            3, 3, wrap = true, hiddenThreshold = 9,
            generator = Generator.TREE, branches = 2, seed = 1L
        )
        // Bord gauche de la ligne 0 : le voisin LEFT est le bord droit.
        assertEquals(2, wrapBoard.neighborIndex(0, Dir.LEFT))
        // Bord haut : le voisin UP est la dernière ligne.
        assertEquals(6, wrapBoard.neighborIndex(0, Dir.UP))

        val plainBoard = Board(
            3, 3, wrap = false, hiddenThreshold = 9,
            generator = Generator.TREE, branches = 2, seed = 1L
        )
        assertEquals(-1, plainBoard.neighborIndex(0, Dir.LEFT))
        assertEquals(-1, plainBoard.neighborIndex(0, Dir.UP))
    }

    @Test
    fun `rotation is cyclic`() {
        val board = Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 7L)
        val before = board.currentDirs(0)
        board.rotateCell(0, 4)
        assertEquals(before, board.currentDirs(0))
        board.rotateCell(0, 5) // ≡ +1
        assertEquals(Dir.rotateMask(before, 1), board.currentDirs(0))
    }

    @Test
    fun `server is inside the board and terminals are leaves`() {
        for (generator in Generator.entries) {
            val board = Board.generate(
                Skill.NORMAL, 9, 7, generator, seed = 3L
            )
            assertTrue(board.serverIndex in 0 until board.size)
            // Le serveur fait partie du réseau : au moins une connexion,
            // quel que soit le générateur (la croissance part de lui).
            assertTrue(
                "Le serveur doit être câblé ($generator)",
                board.connections[board.serverIndex] != Dir.NO_DIRS
            )
            for (i in 0 until board.size) {
                if (board.isTerminal(i)) {
                    assertEquals(1, Integer.bitCount(board.connections[i]))
                }
            }
        }
    }

    @Test
    fun `insane hidden cells are those with three or more connections`() {
        // Équivalence exacte, cellule par cellule (sémantique de
        // l'original : cachée ⇔ au moins 3 connexions) ; et au moins
        // une cellule cachée doit exister sur l'ensemble des tirages —
        // un arbre couvrant d'une grille 7 x 11 contient toujours des
        // embranchements (vérifié en simulation).
        var hiddenSomewhere = false
        repeat(25) { attempt ->
            val board = Board.generate(
                Skill.INSANE, 7, 11, Generator.TREE,
                seed = attempt * 202_610_31L + 20261031L
            )
            var hiddenCount = 0
            for (i in 0 until board.size) {
                assertEquals(
                    Integer.bitCount(board.connections[i]) >= 3,
                    board.isHidden(i)
                )
                if (board.isHidden(i)) ++hiddenCount
            }
            if (hiddenCount > 0) hiddenSomewhere = true
        }
        assertTrue("Des cellules cachées doivent exister", hiddenSomewhere)
    }

    @Test
    fun `non-insane levels have no hidden cells`() {
        for (skill in listOf(Skill.NOVICE, Skill.NORMAL, Skill.EXPERT, Skill.MASTER)) {
            val (width, height) = dims(skill)
            val board = Board.generate(
                skill, width, height, Generator.TREE, seed = 99L
            )
            assertFalse(
                "Aucune cellule cachée hors niveau Dément ($skill)",
                (0 until board.size).any { board.isHidden(it) }
            )
        }
    }

    @Test
    fun `lock toggles per cell`() {
        val board = Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 11L)
        assertFalse(board.locked[3])
        board.toggleLock(3)
        assertTrue(board.locked[3])
        board.toggleLock(3)
        assertFalse(board.locked[3])
    }

    @Test
    fun `solver move list solves the board`() {
        for (skill in Skill.entries) {
            for (generator in Generator.entries) {
                repeat(10) { attempt ->
                    val (width, height) = dims(skill)
                    val board = Board.generate(
                        skill, width, height, generator,
                        seed = attempt * 7_654_321L + skill.ordinal
                    )
                    // Comme le ViewModel : rotation recalculée au moment
                    // de jouer, robuste même si l'état a changé.
                    for (index in board.solveMoves()) {
                        board.rotateCell(index, (4 - board.rotations[index]) % 4)
                        board.reveal(index)
                    }
                    assertTrue(
                        "Le solveur doit finir par résoudre ($skill/$generator)",
                        board.isSolved()
                    )
                }
            }
        }
    }

    @Test
    fun `endless board is fixed 40 by 40 on every screen matrix`() {
        // Infini : la matrice Screen est bypassée — la taille est fixe
        // et carrée, quelle que soit la matrice de l'écran (et son
        // orientation, d'où le tore bien réel du niveau).
        for (screen in Screen.entries) {
            for (landscape in listOf(true, false)) {
                assertEquals(
                    Skill.ENDLESS_SIZE to Skill.ENDLESS_SIZE,
                    screen.boardSize(Skill.ENDLESS, landscape)
                )
            }
        }
        // Les règles du niveau : trois branches et tore, aucun masquage
        // (le masquage à la Dément reste le propre de Dément).
        assertEquals(3, Skill.ENDLESS.branches)
        assertTrue(Skill.ENDLESS.wrap)
        assertEquals(9, Skill.ENDLESS.hiddenThreshold)
    }

    @Test
    fun `board carries its skill`() {
        // La fabrique endosse toujours le niveau réel : la vue s'en
        // sert pour ses modes de rendu (caméra modulo d'Infini).
        for (skill in Skill.entries) {
            val board = Board.generate(
                skill, 9, 7, Generator.TREE, seed = 5L
            )
            assertSame(skill, board.skill)
        }
        // Le constructeur direct (assemblage à la main dans les tests)
        // garde le niveau par défaut.
        val direct = Board(
            3, 3, wrap = true, hiddenThreshold = 9,
            generator = Generator.TREE, branches = 2, seed = 1L
        )
        assertSame(Skill.NOVICE, direct.skill)
    }

    @Test
    fun `screen sizes follow the original matrix`() {
        // Grand côté horizontal en paysage, vertical en portrait ;
        // Maître et Dément partagent la taille (valeurs de
        // BoardView.java de l'original).
        assertEquals(11 to 7, Screen.MEDIUM.boardSize(Skill.MASTER, landscape = true))
        assertEquals(7 to 11, Screen.MEDIUM.boardSize(Skill.INSANE, landscape = false))
        assertEquals(
            Screen.MEDIUM.boardSize(Skill.MASTER, true),
            Screen.MEDIUM.boardSize(Skill.INSANE, true)
        )
        assertEquals(15 to 8, Screen.HUGE.boardSize(Skill.EXPERT, landscape = true))
        assertEquals(8 to 15, Screen.HUGE.boardSize(Skill.EXPERT, landscape = false))
        assertEquals(9 to 7, Screen.MEDIUM.boardSize(Skill.NORMAL, landscape = true))
        assertEquals(6 to 7, Screen.HUGE.boardSize(Skill.NOVICE, landscape = false))
        // find : mêmes seuils que findMatrix (400 / 1,4 / 500 / 1,5).
        assertEquals(Screen.SMALL, Screen.find(240, 320))
        assertEquals(Screen.WSMALL, Screen.find(320, 480))
        assertEquals(Screen.MEDIUM, Screen.find(480, 640))
        assertEquals(Screen.WMEDIUM, Screen.find(480, 800))
        assertEquals(Screen.HUGE, Screen.find(1080, 2400))
    }
}
