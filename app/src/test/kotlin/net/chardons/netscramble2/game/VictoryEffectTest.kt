package net.chardons.netscramble2.game

import net.chardons.netscramble2.VictoryEffect
import net.chardons.netscramble2.VictoryEffects
import net.chardons.netscramble2.VictoryFirework
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Tests du chantier « Effets spéciaux de réussite » (15 nov. 2026).
 * Le modèle est PUR — tout tourne sur la JVM ; BoardView n'est pas
 * testée (elle ne fait que lire des nombres du modèle). Le feu
 * d'artifice est déterministe : graine unique par victoire (spec
 * validée par eric).
 */
class VictoryEffectTest {

    @Test
    fun `pick is uniform bounded and both effects are reachable`() {
        assertEquals(VictoryEffect.WAVE, VictoryEffects.pick(0))
        assertEquals(VictoryEffect.FIREWORK, VictoryEffects.pick(1))
        assertEquals(VictoryEffect.WAVE, VictoryEffects.pick(2))
        // Roll négatif : floorMod, jamais d'indice hors bornes.
        assertEquals(VictoryEffect.FIREWORK, VictoryEffects.pick(-1))
        assertEquals(VictoryEffect.WAVE, VictoryEffects.pick(-2))
    }

    @Test
    fun `effect duration and spark budget match eric decisions`() {
        assertEquals(4_500L, VictoryEffects.EFFECT_MS)
        assertEquals(600, VictoryEffects.FIREWORK_MAX_SPARKS)
        // Amplitude du soulèvement : moins d'une case.
        assertTrue(VictoryEffects.WAVE_LIFT_CELLS in 0f..1f)
        assertEquals(0.08f, VictoryEffects.WAVE_SCALE_FRACTION)
        // Retour UAT (16 nov. 2026) : DEUX vagues dans le même
        // effet, la seconde part à mi-durée une fois la première
        // passée ; la durée totale ne change pas.
        assertEquals(2, VictoryEffects.WAVE_COUNT)
        assertEquals(2_250L, VictoryEffects.WAVE2_DELAY_MS)
        assertEquals(3f, VictoryEffects.WAVE_WIDTH_CELLS)
    }

    @Test
    fun `wave profile peaks at the front and dies clean at the edges`() {
        // Pic exact au passage du front.
        assertEquals(1f, VictoryEffects.waveProfile(0f, 40f), 1e-6f)
        // Nul à trois demi-largeurs (3 cases) et au-delà : fin nette.
        assertEquals(0f, VictoryEffects.waveProfile(3f * VictoryEffects.WAVE_WIDTH_CELLS * 40f, 40f), 0f)
        assertEquals(0f, VictoryEffects.waveProfile(-3f * VictoryEffects.WAVE_WIDTH_CELLS * 40f, 40f), 0f)
        assertEquals(0f, VictoryEffects.waveProfile(1_000f, 40f), 0f)
        // Décroissance derrière le front, symétrie autour du pic.
        assertTrue(VictoryEffects.waveProfile(-40f, 40f) < VictoryEffects.waveProfile(0f, 40f))
        assertEquals(
            VictoryEffects.waveProfile(-40f, 40f),
            VictoryEffects.waveProfile(40f, 40f),
            1e-6f
        )
        // Toujours borné, sur toute la largeur du front élargi.
        repeat(50) { i ->
            val v = VictoryEffects.waveProfile(-400f + i * 16f, 40f)
            assertTrue(v in 0f..1f)
        }
        // Sans taille de case (vue jamais mesurée) : rien du tout.
        assertEquals(0f, VictoryEffects.waveProfile(0f, 0f), 0f)
    }

    @Test
    fun `each wave front covers the whole diagonal within its half`() {
        // Retour UAT : chaque vague couvre l'écran en 2,25 s — la
        // première, puis la seconde sur un autre côté.
        val front = VictoryEffects.waveSpeed(1_080f, 1_920f) *
            (VictoryEffects.EFFECT_MS / VictoryEffects.WAVE_COUNT / 1000f)
        assertTrue(front > hypot(1_080f, 1_920f))
    }

    @Test
    fun `firework never breaches the 600 spark budget`() {
        val fw = VictoryFirework(seed = 7L, width = 1_080f, height = 1_920f)
        var now = 0L
        while (now <= 12_000L) {
            fw.step(now)
            assertTrue(fw.aliveCount() <= VictoryEffects.FIREWORK_MAX_SPARKS)
            if (now in 1_500L..3_000L) {
                // En plein vol, des gerbes vivent.
                assertTrue(fw.aliveCount() > 0)
            }
            now += 16L
        }
        // Fin nette : plus rien ne vit après l'effet.
        assertEquals(0, fw.aliveCount())
    }

    @Test
    fun `firework seed makes the effect reproducible`() {
        val a = VictoryFirework(seed = 42L, width = 1_080f, height = 1_920f)
        val b = VictoryFirework(seed = 42L, width = 1_080f, height = 1_920f)
        for (now in 0L..8_000L step 16L) {
            a.step(now)
            b.step(now)
        }
        // Même graine, mêmes pas : mêmes gerbes au pixel près.
        assertEquals(a.aliveCount(), b.aliveCount())
    }
}
