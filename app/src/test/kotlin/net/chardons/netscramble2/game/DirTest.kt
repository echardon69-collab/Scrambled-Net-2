package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du modèle de directions et de rotation des masques.
 */
class DirTest {

    @Test
    fun oppositeIsSymmetric() {
        assertEquals(Dir.DOWN, Dir.UP.opposite)
        assertEquals(Dir.UP, Dir.DOWN.opposite)
        assertEquals(Dir.LEFT, Dir.RIGHT.opposite)
        assertEquals(Dir.RIGHT, Dir.LEFT.opposite)
    }

    @Test
    fun clockwiseRotation() {
        assertEquals(Dir.RIGHT, Dir.UP.rotatedCW(1))
        assertEquals(Dir.DOWN, Dir.RIGHT.rotatedCW(1))
        assertEquals(Dir.LEFT, Dir.DOWN.rotatedCW(1))
        assertEquals(Dir.UP, Dir.LEFT.rotatedCW(1))
        // Un tour complet : identité.
        assertEquals(Dir.UP, Dir.UP.rotatedCW(4))
        // Sens anti-horaire.
        assertEquals(Dir.LEFT, Dir.UP.rotatedCW(-1))
    }

    @Test
    fun maskRotation() {
        val upOnly = Dir.UP.bit
        assertEquals(Dir.RIGHT.bit, Dir.rotateMask(upOnly, 1))
        assertEquals(Dir.LEFT.bit, Dir.rotateMask(upOnly, -1))

        // Une croix complète reste une croix complète.
        assertEquals(Dir.ALL_DIRS, Dir.rotateMask(Dir.ALL_DIRS, 1))
        assertEquals(Dir.ALL_DIRS, Dir.rotateMask(Dir.ALL_DIRS, 4))

        // La rotation préserve le nombre de connexions.
        val lShape = Dir.UP.bit or Dir.RIGHT.bit
        val rotated = Dir.rotateMask(lShape, 1)
        assertEquals(2, Integer.bitCount(rotated))
        assertTrue(Dir.contains(rotated, Dir.RIGHT))
        assertTrue(Dir.contains(rotated, Dir.DOWN))
    }

    @Test
    fun contains() {
        val mask = Dir.UP.bit or Dir.LEFT.bit
        assertTrue(Dir.contains(mask, Dir.UP))
        assertTrue(Dir.contains(mask, Dir.LEFT))
        assertFalse(Dir.contains(mask, Dir.RIGHT))
        assertFalse(Dir.contains(mask, Dir.DOWN))
    }
}
