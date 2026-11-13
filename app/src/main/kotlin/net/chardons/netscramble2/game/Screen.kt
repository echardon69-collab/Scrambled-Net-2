package net.chardons.netscramble2.game

/**
 * Tailles de plateaux selon l'écran, réplique fidèle de l'enum Screen de
 * BoardView.java de l'original (Scrambled Net de SilentServices).
 *
 * L'original ne jouait pas sur des grilles carrées fixes : chaque écran
 * recevait une « matrice » (SMALL, WSMALL, MEDIUM, WMEDIUM, HUGE) choisie
 * d'après sa plus petite dimension et son ratio d'aspect (findMatrix),
 * puis chaque niveau de difficulté y prélevait son propre rectangle.
 * Le grand côté (major) est la dimension horizontale en paysage,
 * verticale en portrait. Maître et Dément partagent la même taille de
 * grille : Maître ajoute le tore, Dément le masquage des câbles, mais
 * la géométrie est la même.
 */
enum class Screen(
    /** Grand côté des plateaux Maître et Dément. */
    val major: Int,
    /** Petit côté des plateaux Maître et Dément. */
    val minor: Int,
    /** Plateau Expert : grand côté puis petit côté. */
    val expertMajor: Int,
    val expertMinor: Int,
    /** Plateau Normal : grand côté puis petit côté. */
    val normalMajor: Int,
    val normalMinor: Int,
    /** Plateau Novice : grand côté puis petit côté. */
    val noviceMajor: Int,
    val noviceMinor: Int
) {
    SMALL(8, 6, 8, 6, 6, 6, 6, 4),
    WSMALL(9, 6, 9, 6, 5, 6, 5, 4),
    MEDIUM(11, 7, 11, 7, 9, 7, 5, 5),
    WMEDIUM(12, 7, 10, 7, 8, 7, 6, 5),
    HUGE(17, 10, 15, 8, 11, 8, 7, 6);

    /** Grand côté du plateau du niveau [skill]. */
    private fun majorOf(skill: Skill): Int = when (skill) {
        Skill.MASTER, Skill.INSANE -> major
        Skill.EXPERT -> expertMajor
        Skill.NORMAL -> normalMajor
        Skill.NOVICE -> noviceMajor
        // Famille Infini (Endless, Abîme, Néant) : la matrice est
        // bypassée, la taille est fixe.
        Skill.ENDLESS, Skill.ABYSS, Skill.VOID -> Skill.ENDLESS_SIZE
    }

    /** Petit côté du plateau du niveau [skill]. */
    private fun minorOf(skill: Skill): Int = when (skill) {
        Skill.MASTER, Skill.INSANE -> minor
        Skill.EXPERT -> expertMinor
        Skill.NORMAL -> normalMinor
        Skill.NOVICE -> noviceMinor
        // Famille Infini (Endless, Abîme, Néant) : la matrice est
        // bypassée, la taille est fixe.
        Skill.ENDLESS, Skill.ABYSS, Skill.VOID -> Skill.ENDLESS_SIZE
    }

    /**
     * Taille (largeur, hauteur) du plateau du niveau [skill] : en paysage
     * le grand côté est horizontal, en portrait, il est vertical — comme
     * getBoardWidth et getBoardHeight de l'original.
     */
    fun boardSize(skill: Skill, landscape: Boolean): Pair<Int, Int> =
        if (landscape) majorOf(skill) to minorOf(skill)
        else minorOf(skill) to majorOf(skill)

    companion object {
        /**
         * Choisit la matrice adaptée à un écran, réplique exacte de
         * findMatrix de l'original : au plus 400 px de petit côté, les
         * écrans larges (ratio supérieur à 1,4) prennent la variante W ;
         * au plus 500 px, la bascule se fait à 1,5 ; au-delà, HUGE.
         */
        fun find(widthPx: Int, heightPx: Int): Screen {
            val min = minOf(widthPx, heightPx)
            val max = maxOf(widthPx, heightPx)
            val aspect = max.toFloat() / min
            return when {
                min <= 400 -> if (aspect > 1.4f) WSMALL else SMALL
                min <= 500 -> if (aspect > 1.5f) WMEDIUM else MEDIUM
                else -> HUGE
            }
        }
    }
}
