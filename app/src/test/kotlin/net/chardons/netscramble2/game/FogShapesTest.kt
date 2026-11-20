package net.chardons.netscramble2.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la géométrie du BROUILLARD-NUAGE du Néant (FogShapes).
 *
 * Chantier du 19 nov. 2026 (V2 du 20 nov., retour utilisateurs) :
 * la zone cachée n'est plus une case sombre par tuile mais une
 * couche de nuage — festons qui débordent sur la zone visible, et
 * coins arrondis SUR LA FRONTIÈRE SEULE : le cœur du nuage
 * redevient « les tuiles d'avant », plein, sans trou au centre
 * de quatre cases arrondies qui se rencontrent. Toute la
 * géométrie est PURE : elle se dérive de la visibilité
 * (la sortie de Board.fogVisibility) sur le tore, et tout le hasard
 * est DÉTERMINISTE — sans ça, le bord du nuage scintillerait au pan
 * et au zoom, tuile par tuile.
 *
 * Les attentes sont des PROPRIÉTÉS (frontière, coins, bornes, tore,
 * déterminisme, indice), pas des coordonnées figées : la maquette
 * validée fixe le LOOK, ces tests fixent la DISCIPLINE.
 */
class FogShapesTest {

    /** Visibilité « fait main » : un couloir visible au centre d'un 5×5. */
    private fun corridorVisibility(): Triple<BooleanArray, Int, Int> {
        val w = 5
        val h = 5
        val visible = BooleanArray(w * h)
        // Colonne 2 entièrement visible autour d'un serveur (12) :
        // les cellules 7, 8, 9, 2, 12 (serveur), 17 visibles.
        for (i in intArrayOf(2, 7, 8, 9, 12, 17)) visible[i] = true
        return Triple(visible, w, h)
    }

    @Test
    fun `festons are deterministic - same visibility, same cloud`() {
        val (visible, w, h) = corridorVisibility()
        val a = FogShapes.bumps(visible, w, h, -1)
        val b = FogShapes.bumps(visible, w, h, -1)
        assertEquals(a, b)
        val m1 = FogShapes.cornerRounding(visible, w, h, -1)
        val m2 = FogShapes.cornerRounding(visible, w, h, -1)
        assertTrue(m1.contentEquals(m2))
    }

    @Test
    fun `festons sit exactly on the hidden-visible frontier`() {
        val (visible, w, h) = corridorVisibility()
        val bumps = FogShapes.bumps(visible, w, h, -1)
        assertTrue("la frontière caché/visible porte des festons",
            bumps.isNotEmpty())
        for (c in bumps) {
            // Un feston est centré SUR une arête : une de ses deux
            // coordonnées est ENTIÈRE (l'arête), l'autre un quart ou
            // trois quarts — et il reste dans les bornes du plateau.
            val onVerticalEdge = c.x % 1f == 0f && (c.y % 1f == 0.25f || c.y % 1f == 0.75f)
            val onHorizontalEdge = c.y % 1f == 0f && (c.x % 1f == 0.25f || c.x % 1f == 0.75f)
            assertTrue("feston mal posé : $c", onVerticalEdge || onHorizontalEdge)
            assertTrue("feston hors plateau : $c",
                c.x in 0f..w.toFloat() && c.y in 0f..h.toFloat())
        }
    }

    @Test
    fun `festons stay within the spec radius - about a third of a cell`() {
        val (visible, w, h) = corridorVisibility()
        for ((_, _, r) in FogShapes.bumps(visible, w, h, -1)) {
            assertTrue("rayon trop maigre : $r",
                r >= FogShapes.BUMP_MIN)
            assertTrue("rayon trop pansu : $r",
                r <= FogShapes.BUMP_MAX)
        }
    }

    @Test
    fun `every frontier edge carries exactly two festons`() {
        val (visible, w, h) = corridorVisibility()
        val bumps = FogShapes.bumps(visible, w, h, -1)
        // Frontière attendue : chaque arête caché/visible = 2 festons.
        var edges = 0
        for (i in visible.indices) {
            if (visible[i]) continue
            val x = i % w
            val y = i / w
            for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) {
                val j = Math.floorMod(y + dy, h) * w + Math.floorMod(x + dx, w)
                if (visible[j]) edges++
            }
        }
        assertEquals("deux festons par arête de frontière",
            2 * edges, bumps.size)
    }

    @Test
    fun `no frontier no festons - all visible or all hidden`() {
        val allVisible = BooleanArray(16) { true }
        assertEquals(emptyList<FogShapes.Circle>(),
            FogShapes.bumps(allVisible, 4, 4, -1))
        val allHidden = BooleanArray(16) { false }
        assertEquals(emptyList<FogShapes.Circle>(),
            FogShapes.bumps(allHidden, 4, 4, -1))
    }

    @Test
    fun `revealed hint tile is treated as visible - no feston toward it`() {
        // 3×3 avec la colonne 0 visible, le reste caché — puis on
        // démasque le centre caché (4) comme le fait un indice.
        val visible = BooleanArray(9)
        for (y in 0 until 3) visible[y * 3] = true
        val plain = FogShapes.bumps(visible.clone(), 3, 3, -1)
        val masked = FogShapes.bumps(visible.clone(), 3, 3, 4)
        // Sans indice : les cachées voisines du 4 portent des festons
        // vers lui ; avec l'indice : le 4 est « visible », l'arête
        // devient caché-visible — mais on l'écarte : le seul moment
        // où une tuile cachée se montre, le nuage ne l'habille pas.
        assertTrue(masked.size < plain.size)
    }

    @Test
    fun `interior corners are square - no hole between four hidden cells`() {
        // Retour utilisateurs du 20 nov. : plus de trou au sommet de
        // quatre cachées — seuls les sommets qui TOUCHENT la zone
        // visible s'arrondissent, le cœur reste « les tuiles d'avant ».
        val (visible, w, h) = corridorVisibility()
        val masks = FogShapes.cornerRounding(visible, w, h, -1)
        // La cellule 20 (4, 0) : ses huit voisines toriques (3, 0),
        // (3, 1), (3, 3), (4, 3), (0, 0), (0, 1), (0, 3) sont toutes
        // cachées — AUCUN de ses coins ne s'arrondit.
        assertEquals("une cachée loin du visible garde ses coins carrés",
            0, masks[20])
        // ... mais la frontière arrondit bien quelque part.
        assertTrue("la frontière arrondit au moins un coin",
            masks.any { it != 0 })
    }

    @Test
    fun `corner rounding follows the frontier vertex by vertex`() {
        val (visible, w, h) = corridorVisibility()
        val masks = FogShapes.cornerRounding(visible, w, h, -1)
        // Re-dérivation INDÉPENDANTE : un sommet est arrondi dès que
        // l'une des quatre cases du tore qui le partagent est visible.
        fun touches(vx: Int, vy: Int): Boolean {
            for (dy in intArrayOf(-1, 0)) {
                for (dx in intArrayOf(-1, 0)) {
                    val cx = Math.floorMod(vx + dx, w)
                    val cy = Math.floorMod(vy + dy, h)
                    if (visible[cy * w + cx]) return true
                }
            }
            return false
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (visible[i]) continue
                val m = masks[i]
                assertTrue("coin haut-gauche erroné en ($x,$y)",
                    (m and FogShapes.CORNER_TL != 0) == touches(x, y))
                assertTrue("coin haut-droit erroné en ($x,$y)",
                    (m and FogShapes.CORNER_TR != 0) == touches(x + 1, y))
                assertTrue("coin bas-droit erroné en ($x,$y)",
                    (m and FogShapes.CORNER_BR != 0) ==
                        touches(x + 1, y + 1))
                assertTrue("coin bas-gauche erroné en ($x,$y)",
                    (m and FogShapes.CORNER_BL != 0) == touches(x, y + 1))
            }
        }
    }

    @Test
    fun `corner masks wrap the torus across the board edge`() {
        // 4×4 : seule la case 0 est visible. La cachée 15 (3, 3)
        // enroule par son coin bas-droit sur la case visible 0 :
        // exactement ce coin s'arrondit, aucun autre.
        val visible = BooleanArray(16)
        visible[0] = true
        val masks = FogShapes.cornerRounding(visible, 4, 4, -1)
        assertEquals(FogShapes.CORNER_BR, masks[15])
    }

    @Test
    fun `revealed hint tile rounds the corners that face it`() {
        // 3×3 tout caché, puis l'indice démasque le centre (4) :
        // la case 1 n'arrondit QUE ses deux coins du bas, ceux dont
        // le sommet partage la tuile démasquée.
        val visible = BooleanArray(9)
        assertEquals("sans indice, le nuage reste carré",
            0, FogShapes.cornerRounding(visible, 3, 3, -1)[1])
        val masks = FogShapes.cornerRounding(visible, 3, 3, 4)
        assertEquals(FogShapes.CORNER_BR or FogShapes.CORNER_BL, masks[1])
    }

    @Test
    fun `torus wraps - frontier across the board edge stays in bounds`() {
        // 4×4 PARFAITEMENT enroulé : une seule cellule visible (0),
        // ses trois voisines de TORE (1, 4, 15) révélatrices.
        val visible = BooleanArray(16)
        visible[0] = true
        val bumps = FogShapes.bumps(visible, 4, 4, -1)
        assertTrue(bumps.isNotEmpty())
        for (c in bumps) {
            assertTrue("feston emporté par l'enroulement : $c",
                c.x in 0f..4f && c.y in 0f..4f)
        }
    }

    @Test
    fun `degenerate 1x1 board survives - no crash, bounded shapes`() {
        // Totalement enroule : la cellule est sa propre voisine de
        // tore — pas de frontiere (donc jamais de feston) et le
        // sommet touche une cachee : le masque est NUL.
        val visible = booleanArrayOf(false)
        assertTrue("le plateau enroulé ne porte pas de feston",
            FogShapes.bumps(visible, 1, 1, -1).isEmpty())
        assertEquals(0, FogShapes.cornerRounding(visible, 1, 1, -1)[0])
    }
}
