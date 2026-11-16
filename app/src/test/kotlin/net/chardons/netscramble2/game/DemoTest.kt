package net.chardons.netscramble2.game

import net.chardons.netscramble2.nextPlayableMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du lot 2 des retours utilisateurs (16 nov. 2026) : la
 * démonstration joue comme un JOUEUR — au Néant, elle ne touche que
 * les tuiles que le brouillard laisse voir. Le cœur est pur
 * ([nextPlayableMove]) ; la boucle de la démonstration est rejouée
 * ici coup à coup sur le modèle du jeu, sans Android.
 */
class DemoTest {

    @Test
    fun `next playable move returns the head untouched without fog`() {
        val queue = ArrayDeque(listOf(3, 7, 1, 5))
        assertEquals(3, nextPlayableMove(queue, null))
        assertEquals(listOf(3, 7, 1, 5), queue.toList())
    }

    @Test
    fun `next playable move defers invisible tiles to the back`() {
        val queue = ArrayDeque(listOf(3, 7, 1, 5))
        val visible = BooleanArray(8) { it == 1 || it == 5 }
        assertEquals(1, nextPlayableMove(queue, visible))
        // Les invisibles (3 puis 7) passent en fin de file ; l'ordre
        // relatif de toutes les tuiles est préservé.
        assertEquals(listOf(1, 5, 3, 7), queue.toList())
    }

    @Test
    fun `next playable move restores order after a full invisible cycle`() {
        val queue = ArrayDeque(listOf(2, 4, 6))
        val visible = BooleanArray(8)
        // Aucune tuile visible : un tour complet rend la file à son
        // ordre d'origine et la tête est renvoyée — la progression
        // passe avant l'attente, la démonstration ne se fige jamais.
        assertEquals(2, nextPlayableMove(queue, visible))
        assertEquals(listOf(2, 4, 6), queue.toList())
    }

    @Test
    fun `next playable move on an empty queue is minus one`() {
        val queue = ArrayDeque<Int>()
        assertEquals(-1, nextPlayableMove(queue, null))
        assertEquals(-1, nextPlayableMove(queue, BooleanArray(4)))
    }

    @Test
    fun `the step loop consumes its queue and finishes the board`() {
        // Régression du plantage du 16 nov. 2026 (relevé par eric) :
        // la boucle de la démonstration CONSULTE la file sans la
        // consommer — le coup « déjà en place » doit retirer la
        // tête, sinon la boucle tourne à l'infini sur la première
        // tuile résolue et l'écran se fige. Deux plateaux, dont les
        // deux premiers coups sont VERROUILLÉS au départ : la boucle
        // déverrouille d'abord, tourne ensuite, et la file se vide
        // — chaque cellule n'y figure qu'une seule fois.
        for (seed in 21L..22L) {
            val b = Board.generate(Skill.INSANE, 9, 7, Generator.ORIGINAL, seed = seed)
            val moves = b.solveMoves()
            assertTrue("le plateau mélangé doit avoir des coups à jouer", moves.isNotEmpty())
            moves.take(2).forEach { b.toggleLock(it) }
            val queue = ArrayDeque(moves)
            var ticks = 0
            while (queue.isNotEmpty() && ticks < 10_000) {
                ticks++
                val index = nextPlayableMove(queue, null)
                val steps = (4 - b.rotations[index]) % 4
                when {
                    b.locked[index] -> b.toggleLock(index)
                    steps != 0 -> b.rotateCell(index, steps)
                    // Déjà en place : au suivant.
                    else -> queue.removeFirst()
                }
            }
            assertTrue("la file doit se VIDER, pas tourner en rond (graine $seed)", queue.isEmpty())
            assertTrue("le plateau doit être résolu (graine $seed)", b.isSolved())
        }
    }

    @Test
    fun `the demo never rotates a tile under the Void fog`() {
        // Trois Néant complets, graines différentes : chaque coup de
        // la boucle (file du solveur + garde brouillard, visibilité
        // dérivée recalculée avant chaque coup) tombe sur une tuile
        // VISIBLE au moment de la jouer — et la partie finit résolue.
        for (seed in 11L..13L) {
            val b = Board.generate(Skill.VOID, 40, 40, Generator.ORIGINAL, seed = seed)
            val queue = ArrayDeque(b.solveMoves())
            var guard = 0
            while (queue.isNotEmpty() && guard < 10_000) {
                guard++
                val fog = b.fogVisibility(b.connectedCells())
                val index = nextPlayableMove(queue, fog)
                if (index < 0) break
                val steps = (4 - b.rotations[index]) % 4
                if (steps != 0) {
                    assertTrue(
                        "coup joué sous le brouillard (graine $seed, tuile $index)",
                        fog[index]
                    )
                    b.rotateCell(index, steps)
                } else {
                    // Déjà en place : au suivant.
                    queue.removeFirst()
                }
            }
            assertTrue("la démonstration doit finir par résoudre (graine $seed)", b.isSolved())
        }
    }
}
