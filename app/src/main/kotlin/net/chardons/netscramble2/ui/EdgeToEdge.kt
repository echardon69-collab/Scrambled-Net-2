package net.chardons.netscramble2.ui

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Mise en page « bord à bord » (M6) : la fenêtre dessine sous la barre
 * d'état et sous la barre de navigation — comportement imposé par
 * Android 15+ (targetSdk 35+), rendu volontaire et propre ici, et
 * rétrocompatible jusqu'à la minSdk via WindowCompat.
 *
 * Chaque activité place ses marges via [padSystemBars] : la toolbar
 * reçoit l'inset haut (sa couleur s'étend derrière la barre d'état),
 * le contenu reçoit l'inset bas, et les insets gauche/droite couvrent
 * la barre de navigation latérale en paysage. La transparence des
 * barres et le contraste de leurs icônes sont portés par les thèmes
 * (values/themes.xml et values-night/themes.xml).
 */
object EdgeToEdge {

    /** La fenêtre dessine d'un bord à l'autre de l'écran. */
    fun apply(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * Ajoute les insets des barres système au padding de la vue, en
     * conservant le padding posé en XML (capturé une fois à l'installation).
     * [top] pour la barre d'état (toolbar), [bottom] pour la barre de
     * navigation (zone de contenu). Les insets gauche/droite sont
     * toujours appliqués (barre de navigation en paysage).
     *
     * Les insets top/bottom ajoutent du PADDING : la vue doit pouvoir
     * grandir (hauteur wrap_content, minHeight conservée) — à hauteur
     * fixe, le padding écrase la zone de contenu au lieu de la
     * décaler (c'est le bug des toolbars rognées sous la barre d'état,
     * corrigé dans les trois écrans).
     */
    fun padSystemBars(view: View, top: Boolean = false, bottom: Boolean = false) {
        val baseLeft = view.paddingLeft
        val baseTop = view.paddingTop
        val baseRight = view.paddingRight
        val baseBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + if (top) bars.top else 0,
                baseRight + bars.right,
                baseBottom + if (bottom) bars.bottom else 0
            )
            insets
        }
    }
}
