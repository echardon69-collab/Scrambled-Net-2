package net.chardons.netscramble2.game

/**
 * GEOMETRIE DU BROUILLARD-NUAGE du Néant (VOID) — la partie PURE,
 * sans aucune dépendance Android (testable sur JVM, comme Board).
 *
 * Chantier « brouillard-nuage », décision d'eric du 19 nov. 2026 :
 * fini les tuiles rectangulaires pleines — le brouillard devient une
 * COUCHE DE NUAGE continue qui recouvre la zone cachée d'un seul
 * tenant et DEBORDE légèrement sur les tuiles visibles. Trois
 * ingrédients, dans l'ordre de dessin de la vue :
 *
 *  1. la SILHOUETTE : chaque cellule cachée y contribue un carré aux
 *     coins arrondis (la vue fait addRoundRect, rayon en fraction de
 *     cellule) — la forme pleine, opaque, couleur fog_cell ;
 *  2. les FESTONS ([bumps]) : sur chaque frontière caché/visible, des
 *     cercles centrés SUR l'arête — ils débordent d'un tiers de
 *     cellule environ du côté visible et donnent au bord son allure
 *     de nuage festonné. La vue les fusionne dans la même silhouette
 *     (winding : l'union se remplit d'un bloc) ;
 *  3. les COINS de la silhouette ([cornerRounding]) — V2 du 20
 *     nov. 2026, retour utilisateurs d'eric : la frontière festive
 *     reste, mais le cœur du nuage REDEVIENT « les tuiles d'avant ».
 *     Un coin de cellule cachée ne s'arrondit que si son sommet
 *     touche la zone visible (ou la tuile démasquée) ; l'intérieur
 *     est donc PLEIN, sans le petit trou au centre de quatre cases
 *     arrondies qui se rencontrent.
 *
 * Les coordonnées sont en UNITÉS DE CELLULES (0..width, 0..height) :
 * indépendantes du zoom, la vue les amène aux pixels par CTM. Le
 * plateau est un TORE (famille Infini) : le voisinage s'enroule par
 * floorMod exactement comme Board.fogVisibility.
 *
 * DÉTERMINISME : tout le placement passe par [mix], un hachage
 * stable — la même carte de brouillard produit toujours la même
 * nébulosité, image après image, sans scintillement au pan ni au
 * zoom. Aucun état : tout se dérive de `visible` (la sortie de
 * Board.fogVisibility) à chaque coup.
 *
 * `revealed` est la cellule DÉMASQUÉE par un indice (hintIndex côté
 * vue) : elle est traitée comme visible — sa silhouette, ses festons
 * et les coins qui la touchent sont écartés pour la laisser montrer la tuile
 * qu'elle désigne (seul moment où une tuile cachée se montre).
 */
object FogShapes {

    /** Cercle en unités de cellules : centre (x, y) et rayon. */
    data class Circle(val x: Float, val y: Float, val radius: Float)

    /** Rayon minimal d'un feston (fraction de cellule). */
    const val BUMP_MIN = 0.20f

    /** Rayon maximal d'un feston (fraction de cellule). */
    const val BUMP_MAX = 0.34f

    /**
     * Bits du masque de [cornerRounding] : un bit par coin à
     * arrondir, dans le repère écran de la vue (y vers le bas),
     * même ordre que les rayons de Path.addRoundRect.
     */
    const val CORNER_TL = 1
    const val CORNER_TR = 2
    const val CORNER_BR = 4
    const val CORNER_BL = 8

    /**
     * FESTONS : un cercle par quart et par milieu d'arête de
     * frontière (deux par arête, aux abscisses 1/4 et 3/4), rayon
     * tiré de l'index de la cellule cachée et de la direction —
     * grands et petits alternent comme sur la maquette validée.
     * Débordement côté visible : le rayon (≤ [BUMP_MAX] ≈ 1/3 de
     * cellule), c'est la spec d'eric (« les limites du brouillard
     * peuvent être courbes et dépasser légèrement sur les tuiles »).
     */
    fun bumps(
        visible: BooleanArray,
        width: Int,
        height: Int,
        revealed: Int
    ): List<Circle> {
        val open = mutableListOf<Circle>()
        // Ordre de balayage FIXE (index, puis direction) : la liste
        // est reproductible byte à byte — les tests JVM la
        // verrouillent.
        // Directions dans l'ordre de déclaration de Dir (UP, RIGHT,
        // DOWN, LEFT) : mêmes vecteurs que Dir, sans dépendre de lui
        // (aucun état de plateau ici, juste de la géométrie).
        val dxs = intArrayOf(0, 1, 0, -1)
        val dys = intArrayOf(-1, 0, 1, 0)
        for (i in visible.indices) {
            if (visible[i] || i == revealed) continue
            val x = i % width
            val y = i / width
            for (d in 0 until 4) {
                val nx = Math.floorMod(x + dxs[d], width)
                val ny = Math.floorMod(y + dys[d], height)
                val neighbor = ny * width + nx
                // Frontière = caché BORDE visible (tore : l'arête du
                // bord opposé est une arête ordinaire). La tuile
                // DÉMASQUÉE par l'indice ne compte PAS : le seul
                // moment où une tuile cachée se montre, le nuage ne
                // l'habille pas de ses festons.
                if (!visible[neighbor] || neighbor == revealed) continue
                // L'arête (x,y)-(x+1,y+1) selon d porte deux festons.
                val ex = (x + maxOf(0, dxs[d])).toFloat()
                val ey = (y + maxOf(0, dys[d])).toFloat()
                val r1 = BUMP_MIN + (BUMP_MAX - BUMP_MIN) * mix(i * 4 + d, 1)
                val r2 = BUMP_MIN + (BUMP_MAX - BUMP_MIN) * mix(i * 4 + d, 2)
                if (dys[d] == 0) {
                    // Arête verticale : festons aux 1/4 et 3/4 de la hauteur.
                    open.add(Circle(ex, ey + 0.25f, r1))
                    open.add(Circle(ex, ey + 0.75f, r2))
                } else {
                    // Arête horizontale : aux 1/4 et 3/4 de la largeur.
                    open.add(Circle(ex + 0.25f, ey, r1))
                    open.add(Circle(ex + 0.75f, ey, r2))
                }
            }
        }
        return open
    }

    /**
     * COINS de la silhouette : pour chaque cellule cachée, le masque
     * de ses quatre coins à ARRONDIR (V2 du 20 nov. 2026 — retour
     * utilisateurs : la frontière seule s'arrondit, le cœur du
     * nuage redevient plein, « les tuiles d'avant »). Un coin ne
     * s'arrondit que si l'une des QUATRE cases qui partagent son
     * sommet est visible ou démasquée : au centre de quatre cases
     * cachées le masque est NUL, jamais de trou.
     */
    fun cornerRounding(
        visible: BooleanArray,
        width: Int,
        height: Int,
        revealed: Int
    ): IntArray {
        val masks = IntArray(visible.size)
        for (i in visible.indices) {
            if (visible[i] || i == revealed) continue
            val x = i % width
            val y = i / width
            val left = Math.floorMod(x - 1, width)
            val right = Math.floorMod(x + 1, width)
            val up = Math.floorMod(y - 1, height)
            val down = Math.floorMod(y + 1, height)
            var m = 0
            if (vertexTouchesVisible(visible, width, revealed, up, y, left, x)) {
                m = m or CORNER_TL
            }
            if (vertexTouchesVisible(visible, width, revealed, up, y, x, right)) {
                m = m or CORNER_TR
            }
            if (vertexTouchesVisible(visible, width, revealed, y, down, x, right)) {
                m = m or CORNER_BR
            }
            if (vertexTouchesVisible(visible, width, revealed, y, down, left, x)) {
                m = m or CORNER_BL
            }
            masks[i] = m
        }
        return masks
    }

    /**
     * Sommet partagé par quatre cases — (col1, row1), (col2, row1),
     * (col1, row2), (col2, row2), déjà enroulées sur le tore.
     */
    private fun vertexTouchesVisible(
        visible: BooleanArray,
        width: Int,
        revealed: Int,
        row1: Int, row2: Int,
        col1: Int, col2: Int
    ): Boolean {
        for (row in intArrayOf(row1, row2)) {
            for (col in intArrayOf(col1, col2)) {
                val idx = row * width + col
                if (visible[idx] || idx == revealed) return true
            }
        }
        return false
    }

    /**
     * Hachage uniforme stable (aucun Random : la nébulosité doit être
     * IDENTIQUE à chaque image et entre plateformes) → [0, 1).
     */
    private fun mix(seed: Int, salt: Int): Float {
        var h = seed * -0x61C88647 + salt * -0x3361D2AF
        h = h xor (h ushr 13)
        h *= -0x3D4D3BF9
        h = h xor (h ushr 16)
        return ((h ushr 8) and 0xFFFF).toFloat() / 65536f
    }
}
