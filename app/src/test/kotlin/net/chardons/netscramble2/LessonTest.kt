package net.chardons.netscramble2

import net.chardons.netscramble2.game.Dir
import net.chardons.netscramble2.game.Generator
import net.chardons.netscramble2.game.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du chantier Apprentissage : invariants pédagogiques des
 * graines FIGÉES dans [Lessons] (un terminal pour la première
 * leçon, une connexion qui traverse le bord du tore pour la
 * quatrième, tuiles invisibles pour la cinquième, plateau infini
 * livré résolu) et machine d'étapes [LessonRunner] (attentes,
 * accumulateurs de caméra remis à zéro, Continuer limité aux
 * étapes de lecture). Tout est pur Kotlin — la JVM suffit.
 */
class LessonTest {

    /** Nombre de terminaux (feuilles de réseau) d'un plateau. */
    private fun terminals(board: net.chardons.netscramble2.game.Board): Int =
        (0 until board.size).count { board.isTerminal(it) }

    /** Nombre de tuiles invisibles d'un plateau. */
    private fun hiddenCount(board: net.chardons.netscramble2.game.Board): Int =
        (0 until board.size).count { board.isHidden(it) }

    /**
     * Connexions de la solution qui traversent un bord du tore :
     * chaque arete compte deux fois (une par extremite), on rend
     * le nombre d'aretes.
     */
    private fun crossings(board: net.chardons.netscramble2.game.Board): Int {
        var n = 0
        for (i in 0 until board.size) {
            val x = i % board.width
            val y = i / board.width
            for (d in Dir.entries) {
                if ((board.connections[i] and d.bit) == 0) continue
                val nx = x + d.dx
                val ny = y + d.dy
                if (nx < 0 || nx >= board.width || ny < 0 || ny >= board.height) n++
            }
        }
        return n / 2
    }

    // -------------------------------------------------------------
    // Invariants des graines (toute modification de Lessons.ALL se
    // fait sous la surveillance de ces tests).
    // -------------------------------------------------------------

    @Test
    fun `seven lessons in the validated order`() {
        assertEquals(7, Lessons.ALL.size)
        assertEquals(
            listOf("l1", "l2", "l3", "l4", "l5", "l6", "l7"),
            Lessons.ALL.map { it.id }
        )
    }

    @Test
    fun `lesson 1 seed gives one terminal on 3 by 3 novice`() {
        val board = Lessons.ALL[0].board.build()
        assertEquals(Skill.NOVICE, Lessons.ALL[0].board.skill)
        assertEquals(3, board.width)
        assertEquals(3, board.height)
        assertEquals(Generator.TREE, Lessons.ALL[0].board.generator)
        assertEquals(1, terminals(board))
        assertFalse(board.isSolved())
    }

    @Test
    fun `lesson 2 seed gives three terminals on 5 by 5 novice`() {
        val board = Lessons.ALL[1].board.build()
        assertEquals(Skill.NOVICE, Lessons.ALL[1].board.skill)
        assertEquals(5, board.width)
        assertEquals(5, board.height)
        assertEquals(3, terminals(board))
        assertFalse(board.isSolved())
    }

    @Test
    fun `lesson 3 seed has terminals and a hint target from the start`() {
        val board = Lessons.ALL[2].board.build()
        assertTrue(terminals(board) >= 2)
        assertFalse(board.isSolved())
        assertTrue(board.hintTarget() >= 0)
    }

    @Test
    fun `lesson 3 solve bubble carries two consecutive steps`() {
        val steps = Lessons.ALL[2].steps
        assertEquals(5, steps.size)
        assertEquals(ExpectKind.HINT_SIGNAL, steps[0].kind)
        assertEquals(ExpectKind.HINT_FIX, steps[1].kind)
        assertEquals(ExpectKind.SOLVE, steps[2].kind)
        assertEquals(ExpectKind.VICTORY, steps[3].kind)
        assertEquals(ExpectKind.CONTINUE, steps[4].kind)
        // la bulle de Résoudre reste affichée PENDANT le solveur
        assertEquals(steps[2].bubbleId, steps[3].bubbleId)
    }

    @Test
    fun `lesson 4 seed has a solution crossing the torus edge`() {
        val board = Lessons.ALL[3].board.build()
        assertEquals(Skill.MASTER, Lessons.ALL[3].board.skill)
        assertTrue(Lessons.ALL[3].board.skill.wrap)
        assertEquals(5, board.width)
        assertEquals(5, board.height)
        assertTrue(crossings(board) >= 1)
        assertFalse(board.isSolved())
    }

    @Test
    fun `lesson 5 seed hides at least two tiles on insane`() {
        val board = Lessons.ALL[4].board.build()
        assertEquals(Skill.INSANE, Lessons.ALL[4].board.skill)
        assertEquals(5, board.width)
        assertEquals(5, board.height)
        assertTrue(hiddenCount(board) >= 2)
        assertFalse(board.isSolved())
    }

    @Test
    fun `lesson 6 board is pre solved endless with camera only`() {
        val spec = Lessons.ALL[5].board
        assertEquals(Skill.ENDLESS, spec.skill)
        assertEquals(Skill.ENDLESS_SIZE, spec.width)
        assertEquals(Skill.ENDLESS_SIZE, spec.height)
        assertEquals(Generator.ORIGINAL, spec.generator)
        assertTrue(spec.preSolved)
        assertTrue(spec.cameraOnly)
        val board = spec.build()
        assertTrue(board.isSolved())
        assertTrue(board.serverIndex in 0 until board.size)
        assertEquals(
            listOf(ExpectKind.PAN_X, ExpectKind.PAN_Y, ExpectKind.ZOOM, ExpectKind.CONTINUE),
            Lessons.ALL[5].steps.map { it.kind }
        )
    }

    @Test
    fun `lesson 7 seed plays void rules on 6 by 6`() {
        val board = Lessons.ALL[6].board.build()
        assertEquals(Skill.VOID, Lessons.ALL[6].board.skill)
        assertEquals(Generator.ORIGINAL, Lessons.ALL[6].board.generator)
        assertEquals(6, board.width)
        assertEquals(6, board.height)
        assertTrue(terminals(board) >= 2)
        assertFalse(board.isSolved())
    }

    // -------------------------------------------------------------
    // Machine d'étapes.
    // -------------------------------------------------------------

    @Test
    fun `runner advances on rotate and victory and continue only on continue`() {
        val runner = LessonRunner(Lessons.ALL[0])
        assertEquals(ExpectKind.ROTATE, runner.kind)
        // Continuer ne sort jamais une étape d action
        runner.onContinue()
        assertEquals(0, runner.index)
        // Un déverrouillage ne satisfait pas une attente de rotation
        runner.onEvent(LessonEvent.Locked(0, nowLocked = false))
        assertEquals(0, runner.index)
        runner.onEvent(LessonEvent.Rotated(0))
        assertEquals(1, runner.index)
        assertEquals(ExpectKind.VICTORY, runner.kind)
        runner.onContinue()
        assertEquals(1, runner.index)
        runner.onEvent(LessonEvent.Victory)
        assertEquals(2, runner.index)
        assertEquals(ExpectKind.CONTINUE, runner.kind)
        runner.onEvent(LessonEvent.Rotated(0))
        assertEquals(2, runner.index)
        runner.onContinue()
        assertTrue(runner.finished)
        assertNull(runner.step)
        // Rien ne bouge après le bout de la leçon
        runner.onEvent(LessonEvent.Victory)
        runner.onContinue()
        assertTrue(runner.finished)
    }

    @Test
    fun `runner lock step waits for a lock and unlock step for an unlock`() {
        val runner = LessonRunner(Lessons.ALL[1])
        runner.onEvent(LessonEvent.Rotated(0))
        assertEquals(ExpectKind.LOCK, runner.kind)
        // un déverrouillage ne consomme pas l attente de verrouillage
        runner.onEvent(LessonEvent.Locked(0, nowLocked = false))
        assertEquals(1, runner.index)
        runner.onEvent(LessonEvent.Locked(0, nowLocked = true))
        assertEquals(2, runner.index)
        assertEquals(ExpectKind.VICTORY, runner.kind)
    }

    @Test
    fun `runner hint and solve steps consume their own event only`() {
        val runner = LessonRunner(Lessons.ALL[2])
        assertEquals(ExpectKind.HINT_SIGNAL, runner.kind)
        runner.onEvent(LessonEvent.HintFix(0))
        assertEquals(0, runner.index)
        runner.onEvent(LessonEvent.HintSignal(3))
        assertEquals(1, runner.index)
        assertEquals(ExpectKind.HINT_FIX, runner.kind)
        runner.onEvent(LessonEvent.SolvePressed)
        assertEquals(1, runner.index)
        runner.onEvent(LessonEvent.HintFix(3))
        assertEquals(2, runner.index)
        assertEquals(ExpectKind.SOLVE, runner.kind)
        runner.onEvent(LessonEvent.HintSignal(3))
        assertEquals(2, runner.index)
        runner.onEvent(LessonEvent.SolvePressed)
        assertEquals(3, runner.index)
        assertEquals(ExpectKind.VICTORY, runner.kind)
    }

    @Test
    fun `runner pan x accumulates a full turn before advancing`() {
        val runner = LessonRunner(Lessons.ALL[5])
        assertEquals(ExpectKind.PAN_X, runner.kind)
        // un tour complet du plateau 40, moins une demi tuile
        runner.onEvent(LessonEvent.Scrolled(30f, 0f))
        assertEquals(0, runner.index)
        runner.onEvent(LessonEvent.Scrolled(8f, 0f))
        assertEquals(0, runner.index)
        // le doigt revient un peu : la somme repasse sous le seuil
        runner.onEvent(LessonEvent.Scrolled(-3f, 0f))
        assertEquals(0, runner.index)
        runner.onEvent(LessonEvent.Scrolled(4.6f, 0f))
        assertEquals(1, runner.index)
        assertEquals(ExpectKind.PAN_Y, runner.kind)
    }

    @Test
    fun `runner pan y accumulates and both accumulators reset on advance`() {
        val runner = LessonRunner(Lessons.ALL[5])
        // avancer l'étape horizontale d'un coup
        runner.onEvent(LessonEvent.Scrolled(40f, 0f))
        assertEquals(1, runner.index)
        assertEquals(ExpectKind.PAN_Y, runner.kind)
        // les déplacements de l étape précédente ne comptent plus
        runner.onEvent(LessonEvent.Scrolled(0f, 20f))
        assertEquals(1, runner.index)
        runner.onEvent(LessonEvent.Scrolled(0f, 19f))
        assertEquals(1, runner.index)
        runner.onEvent(LessonEvent.Scrolled(0f, 1f))
        assertEquals(2, runner.index)
        assertEquals(ExpectKind.ZOOM, runner.kind)
    }

    @Test
    fun `runner zoom needs one clean pinch and resets between steps`() {
        val runner = LessonRunner(Lessons.ALL[5])
        runner.onEvent(LessonEvent.Scrolled(40f, 0f))
        runner.onEvent(LessonEvent.Scrolled(0f, 40f))
        assertEquals(ExpectKind.ZOOM, runner.kind)
        // 20 pour cent d amplitude ne suffit pas
        runner.onEvent(LessonEvent.Zoomed(1.2f))
        assertEquals(2, runner.index)
        // cumulés, deux pincements courts valident
        runner.onEvent(LessonEvent.Zoomed(1.2f))
        assertEquals(3, runner.index)
        assertEquals(ExpectKind.CONTINUE, runner.kind)
        runner.onContinue()
        assertTrue(runner.finished)
    }

    @Test
    fun `runner zoom out counts when the floor allows it`() {
        val runner = LessonRunner(Lessons.ALL[5])
        runner.onEvent(LessonEvent.Scrolled(40f, 0f))
        runner.onEvent(LessonEvent.Scrolled(0f, 40f))
        runner.onEvent(LessonEvent.Zoomed(0.5f))
        runner.onEvent(LessonEvent.Zoomed(0.5f))
        assertEquals(3, runner.index)
        assertEquals(ExpectKind.CONTINUE, runner.kind)
    }

    @Test
    fun `runner pan does not leak across opposite axis`() {
        val runner = LessonRunner(Lessons.ALL[5])
        // tout le défilement vertical du monde ne valide pas l étape
        // horizontale, et réciproquement
        runner.onEvent(LessonEvent.Scrolled(0f, 60f))
        assertEquals(0, runner.index)
        runner.onEvent(LessonEvent.Scrolled(39.5f, 0f))
        assertEquals(1, runner.index)
    }

    @Test
    fun `runner zoom constants pin the amplitude thresholds`() {
        assertEquals(1.3f, LessonRunner.ZOOM_IN_DONE)
        assertEquals(0.8f, LessonRunner.ZOOM_OUT_DONE)
    }
}
