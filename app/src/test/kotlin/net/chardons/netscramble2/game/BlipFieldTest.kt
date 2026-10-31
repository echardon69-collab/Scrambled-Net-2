package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du champ de blips (portage de Cell.advanceBlips /
 * transferBlips de l'original).
 *
 * Le plateau 3×3 « fait main » construit un réseau connu : le serveur
 * (centre, index 4) en T vers le haut (1), le bas (7) et un terminal à
 * droite (5). Les attentes ont été vérifiées par simulation de
 * l'algorithme, génération par génération.
 */
class BlipFieldTest {

    /** Plateau 3×3 : serveur en T, deux bouts de chaîne, un terminal. */
    private fun tBoard(): Board {
        val board = Board(3, 3, wrap = false, hiddenThreshold = 9, seed = 1L)
        // On écrase la génération aléatoire par un réseau connu
        // (connections et rotations sont des tableaux mutables).
        for (i in 0 until board.size) {
            board.connections[i] = Dir.NO_DIRS
            board.rotations[i] = 0
        }
        board.connections[4] = Dir.UP.bit or Dir.RIGHT.bit or Dir.DOWN.bit
        board.connections[1] = Dir.UP.bit or Dir.DOWN.bit
        board.connections[7] = Dir.UP.bit or Dir.DOWN.bit
        board.connections[5] = Dir.LEFT.bit
        return board
    }

    @Test
    fun `server emits on all its legs every six generations`() {
        val board = tBoard()
        val field = BlipField(board)
        val serverDirs = board.currentDirs(board.serverIndex)

        // Génération 0 : le serveur émet sur toutes ses jambes.
        field.advance()
        assertEquals(serverDirs, field.outgoingAt(board.serverIndex))

        // Puis plus rien pendant cinq générations…
        repeat(5) { field.advance() }
        assertEquals(Dir.NO_DIRS, field.outgoingAt(board.serverIndex))

        // …et il réémet à la sixième.
        field.advance()
        assertEquals(serverDirs, field.outgoingAt(board.serverIndex))
    }

    @Test
    fun `blips transfer to aligned neighbors`() {
        val board = tBoard()
        val field = BlipField(board)
        field.advance()                       // émission
        field.advance()                       // transfert aux voisins

        // Le blip montant arrive vu d'en haut (direction DOWN), etc.
        assertEquals(Dir.DOWN.bit, field.incomingAt(1))
        assertEquals(Dir.UP.bit, field.incomingAt(7))
        assertEquals(Dir.LEFT.bit, field.incomingAt(5))
    }

    @Test
    fun `blips turn at chain ends and terminals absorb them`() {
        val board = tBoard()
        val field = BlipField(board)
        repeat(3) { field.advance() }        // émission, transfert, rebond

        // Les bouts de chaîne renvoient le flux vers l'extérieur…
        assertEquals(Dir.UP.bit, field.outgoingAt(1))
        assertEquals(Dir.DOWN.bit, field.outgoingAt(7))
        // …et le terminal (une seule jambe) absorbe : rien ne ressort.
        assertEquals(Dir.NO_DIRS, field.outgoingAt(5))
    }

    @Test
    fun `a misaligned rotated cell stops the flow`() {
        val board = tBoard()
        val field = BlipField(board)
        repeat(8) { field.advance() }

        // La cellule 1 est tournée d'un quart de tour : son câble ne
        // pointe plus vers le serveur. Comme dans l'original, tourner
        // une cellule efface aussi ses blips.
        board.rotateCell(1, 1)
        field.clear(1)
        assertEquals(Dir.NO_DIRS, field.incomingAt(1))
        assertEquals(Dir.NO_DIRS, field.outgoingAt(1))

        // Jusqu'à la prochaine émission du serveur et son transfert.
        repeat(6) { field.advance() }

        // Plus rien n'arrive à la cellule désalignée…
        assertEquals(Dir.NO_DIRS, field.incomingAt(1))
        // …mais les branches alignées vivent toujours.
        assertEquals(Dir.UP.bit, field.incomingAt(7))
        assertEquals(Dir.LEFT.bit, field.incomingAt(5))
    }

    @Test
    fun `every cell of a solved board eventually sees a blip`() {
        val board = Board.generate(Skill.NOVICE, seed = 20261031L)
        // On remet le plateau en solution : tous les câbles sont alignés.
        for (i in 0 until board.size) {
            if (board.rotations[i] != 0) board.rotateCell(i, (4 - board.rotations[i]) % 4)
        }
        assertTrue(board.isSolved())

        val field = BlipField(board)
        val seen = BooleanArray(board.size)
        // Le serveur émet une génération sur six ; les blips se propagent
        // vers l'extérieur le long de l'arbre couvrant, en tournant à
        // chaque embranchement. 120 générations couvrent largement un
        // plateau 5×5.
        repeat(120) {
            field.advance()
            for (i in 0 until board.size) {
                if (field.incomingAt(i) != 0 || field.outgoingAt(i) != 0) seen[i] = true
            }
        }
        assertTrue(
            "Toutes les cellules doivent voir passer des blips",
            seen.all { it }
        )
    }

    @Test
    fun `clear empties a cell`() {
        val board = tBoard()
        val field = BlipField(board)
        field.advance()
        field.advance()
        assertTrue(field.incomingAt(1) != 0)

        field.clear(1)
        assertEquals(Dir.NO_DIRS, field.incomingAt(1))
        assertEquals(Dir.NO_DIRS, field.outgoingAt(1))
        assertFalse(field.incomingAt(1) != 0 || field.outgoingAt(1) != 0)
    }
}
