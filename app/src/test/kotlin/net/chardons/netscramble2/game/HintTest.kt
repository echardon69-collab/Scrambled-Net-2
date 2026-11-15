package net.chardons.netscramble2.game

import net.chardons.netscramble2.DemoSpeed
import net.chardons.netscramble2.DemoZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests du chantier Aide à la résolution : priorités de la cible
 * d'indice, scellement correctif, plafond d'indices, réglages de la
 * démonstration. La logique de Board est pure — tout tourne sur la
 * JVM ; le cycle progressif de la démonstration est testé en miroir
 * (DemoViewModel vit avec Android).
 */
class HintTest {

    /**
     * Plateau NOVICE 5 x 5 remis en solution : toutes les rotations à
     * zéro, rien de touché ni de verrouillé. Les tests rendent ensuite
     * mal orientées les cellules exactes qu'ils veulent viser.
     */
    private fun solvedBoard(): Board {
        val board = Board.generate(Skill.NOVICE, 5, 5, Generator.TREE, seed = 7L)
        for (i in 0 until board.size) {
            while (board.rotations[i] != 0) board.rotateCell(i, 1)
        }
        return board
    }

    /**
     * Plateau résolu au générateur ORIGINAL : il compte des cellules
     * libres (sans câble), que l'indice ne doit jamais viser.
     */
    private fun solvedBoardWithEmpty(): Board {
        val board = Board.generate(Skill.NOVICE, 7, 7, Generator.ORIGINAL, seed = 3L)
        for (i in 0 until board.size) {
            while (board.rotations[i] != 0) board.rotateCell(i, 1)
        }
        return board
    }

    /** Première cellule câblée du plateau. */
    private fun firstCell(board: Board): Int =
        (0 until board.size).first { !board.isEmpty(it) }

    /** Deuxième cellule câblée du plateau. */
    private fun secondCell(board: Board): Int =
        (0 until board.size).filter { !board.isEmpty(it) }[1]

    @Test
    fun `hint target prefers a locked misplaced cell over an unlocked one`() {
        val board = solvedBoard()
        val first = firstCell(board)
        val second = secondCell(board)
        // Deux cellules mal orientées : la seconde verrouillée. La
        // priorité va à la verrouillée, touchée ou non (stratégie
        // actée avec eric au retour UAT du 15 nov. 2026).
        board.rotateCell(first, 1)
        board.rotateCell(second, 1, byPlayer = true)
        board.toggleLock(second)
        assertEquals(second, board.hintTarget())
    }

    @Test
    fun `hint target never submits a well-oriented locked cell`() {
        val board = solvedBoard()
        val first = firstCell(board)
        val second = secondCell(board)
        // Retour UAT : une tuile verrouillée déjà bien orientée ne
        // doit JAMAIS être soumise — la cible est l'autre, mal
        // orientée et non verrouillée.
        board.toggleLock(first)
        board.rotateCell(second, 1)
        assertEquals(second, board.hintTarget())
        // Tout est remis en place : plus aucune cible — l'ancien
        // repli « première verrouillée » est aboli.
        while (board.rotations[second] != 0) board.rotateCell(second, 1)
        assertEquals(-1, board.hintTarget())
    }

    @Test
    fun `hint target prefers three-connector tiles among unlocked ones`() {
        val board = solvedBoard()
        // Tout est mal orienté, rien n'est verrouillé : priorité aux
        // tuiles à 3 connecteurs, la première par index.
        for (i in 0 until board.size) {
            if (!board.isEmpty(i)) board.rotateCell(i, 1)
        }
        val threeConnector = (0 until board.size)
            .filter { !board.isEmpty(it) && Integer.bitCount(board.connections[it]) == 3 }
        assertTrue("Le plateau compte des tuiles à 3 connecteurs", threeConnector.isNotEmpty())
        assertEquals(threeConnector.min(), board.hintTarget())
    }

    @Test
    fun `symmetric tile turned half way stays well oriented`() {
        // Retour UAT : un câble droit tourné de 180° paraît et branche
        // exactement pareil — l'indice ne doit pas le voir comme une
        // erreur. Une tuile symétrique est un masque invariant par
        // une rotation non nulle.
        val board = solvedBoard()
        val cell = (0 until board.size).firstOrNull { c ->
            !board.isEmpty(c) && (1..3).any { k ->
                Dir.rotateMask(board.connections[c], k) == board.connections[c]
            }
        }
        assumeTrue("Le plateau compte une tuile symétrique", cell != null)
        val c = cell!!
        val mask = board.connections[c]
        val steps = (1..3).first { Dir.rotateMask(mask, it) == mask }
        board.rotateCell(c, steps)
        assertFalse(board.isMisplaced(c))
        assertEquals(-1, board.hintTarget())
    }

    @Test
    fun `hint target returns minus one on a solved untouched board`() {
        val board = solvedBoard()
        // Plateau résolu, rien touché ni verrouillé : aucune cible.
        assertEquals(-1, board.hintTarget())
    }

    @Test
    fun `hint target never targets an empty cell`() {
        val board = solvedBoardWithEmpty()
        // Toutes les cellules câblées sont mal orientées : la cible ne
        // peut jamais être une cellule libre du générateur ORIGINAL.
        var empties = 0
        for (i in 0 until board.size) {
            if (board.isEmpty(i)) {
                ++empties
            } else {
                board.rotateCell(i, 1)
            }
        }
        assertTrue("Le plateau ORIGINAL compte bien des cellules libres", empties > 0)
        val target = board.hintTarget()
        assertFalse(board.isEmpty(target))
    }

    @Test
    fun `hint allowance floors keeps at least one and disables at zero`() {
        assertEquals(3, Board.hintAllowance(misplaced = 37, percent = 10))
        assertEquals(18, Board.hintAllowance(misplaced = 37, percent = 50))
        assertEquals(0, Board.hintAllowance(misplaced = 37, percent = 0))
        // Pourcentage non nul : au moins un indice, même sur un petit plateau.
        assertEquals(1, Board.hintAllowance(misplaced = 1, percent = 10))
        assertEquals(1, Board.hintAllowance(misplaced = 9, percent = 10))
        // Le pourcentage est borné à 50.
        assertEquals(18, Board.hintAllowance(misplaced = 37, percent = 90))
    }

    @Test
    fun `sealed cell is locked revealed and frozen`() {
        val board = solvedBoard()
        val first = firstCell(board)
        board.rotateCell(first, 2)
        board.seal(first)
        assertTrue(board.isSealed(first))
        // Invariant du chantier : toute cellule scellée est verrouillée.
        assertTrue(board.locked[first])
        // Une cachée éventuelle est révélée par le scellement.
        assertTrue(board.revealed[first])
        // Le verrou d'une scellée ne bouge plus : refus silencieux.
        assertFalse(board.toggleLock(first))
        assertTrue(board.locked[first])
    }

    @Test
    fun `demo settings expose their effective values`() {
        // Rythme de la démonstration : délai par coup du solveur.
        assertEquals(500, DemoSpeed.SLOW.delayMs)
        assertEquals(250, DemoSpeed.NORMAL.delayMs)
        assertEquals(100, DemoSpeed.FAST.delayMs)
        assertEquals(30, DemoSpeed.LIGHTNING.delayMs)
        // Zoom : multiplicateur du plancher (1 = ajusté à l'écran).
        assertEquals(1f, DemoZoom.FIT.multiplier)
        assertEquals(1.5f, DemoZoom.X1_5.multiplier)
        assertEquals(2f, DemoZoom.X2.multiplier)
        assertEquals(3f, DemoZoom.X3.multiplier)
    }

    @Test
    fun `progressive demo cycles through every level including the endless family`() {
        // Miroir du cycle progressif de la démonstration : départ au
        // niveau choisi, montée d'un cran à chaque plateau, boucle sur
        // les huit niveaux — la famille Infini comprise.
        val levels = Skill.entries
        val start = Skill.NORMAL
        var ordinal = start.ordinal
        val seen = mutableListOf<Skill>()
        repeat(levels.size) {
            seen.add(levels[ordinal % levels.size])
            ordinal += 1
        }
        assertEquals("Les huit niveaux défilent sans doublon", levels.size, seen.distinct().size)
        assertTrue("La famille Infini est incluse", seen.contains(Skill.VOID))
        assertEquals("Le cycle reboucle sur le niveau de départ", start, levels[ordinal % levels.size])
    }
}
