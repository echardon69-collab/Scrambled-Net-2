package net.chardons.netscramble2

import net.chardons.netscramble2.game.Board
import net.chardons.netscramble2.game.Generator
import net.chardons.netscramble2.game.Skill

/**
 * MODE APPRENTISSAGE (chantier du 17 nov. 2026, spec validée par
 * eric) : sept leçons interactives, chacune un vrai mini-plateau
 * aux règles du niveau visé, des bulles de guidance posées dans le
 * bandeau bas (patron du bandeau de la démonstration) et UNE
 * action attendue à la fois. L'accès vit sous le popup Outils du
 * jeu ; les leçons sont rejouables à volonté, la coche
 * d'acquisition persistée à côté des parties (aucun record, aucune
 * incidence sur la partie en cours).
 *
 * Ce fichier est PUR Kotlin : les leçons (graines, dimensions,
 * niveaux) et la machine d'étapes ([ LessonRunner]) vivent ici,
 * testables en JVM — [ LessonTest] épingle les invariants
 * pédagogiques des graines (leçon du tore : une connexion de la
 * solution traverse le bord, etc.). Les textes des bulles vivent
 * dans les ressources (strings lesson_*), désignés par [LessonStep]
 * par leur IDENTIFIANT ; LessonActivity fait la correspondance
 * identifiant vers R.string (validate_apprentissage vérifie la
 * complétude : chaque identifiant utilisé a sa chaîne EN+FR et sa
 * branche dans le when de l'activité).
 *
 * Décisions d'eric : leçons interactives dédiées (pas de coach en
 * jeu) ; accès par le menu Outils SEULEMENT ; découpage libre des
 * concepts par scénario ; leçon Infini = SON scénario en version
 * enrichie — plateau 40 x 40 PRÉ-RÉSOLU, le joueur déplace la
 * fenêtre jusqu'à RETROUVER le serveur (tour horizontal puis tour
 * vertical), puis pince pour zoomer : la propriété torique est
 * VÉCUE, il n'y a pas de mini-puzzle sur cette leçon.
 */

/**
 * Ce qu'une étape ATTEND du joueur pour passer à la suivante.
 *
 *  - [CONTINUE] : le joueur lit la bulle puis touche « Continuer » ;
 *  - [ROTATE] : n'importe quelle rotation JOUÉE (la bulle guide) ;
 *  - [LOCK] / [UNLOCK] : un verrouillage / déverrouillage joué ;
 *  - [HINT_SIGNAL] : le bouton Indice de la leçon (informatif) ;
 *  - [HINT_FIX] : le bouton Indice de la leçon (correctif, scellé) ;
 *  - [SOLVE] : le bouton Résoudre de la leçon (jamais scoré) ;
 *  - [VICTORY] : le plateau est résolu ;
 *  - [PAN_X] / [PAN_Y] : la fenêtre a voyagé d'un TOUR complet du
 *    plateau dans cette direction (leçon Infini) ;
 *  - [ZOOM] : un pincement significatif a été fait.
 */
enum class ExpectKind {
    CONTINUE,
    ROTATE,
    LOCK,
    UNLOCK,
    HINT_SIGNAL,
    HINT_FIX,
    SOLVE,
    VICTORY,
    PAN_X,
    PAN_Y,
    ZOOM
}

/**
 * Une étape de leçon : la bulle affichée ([bubbleId], identifiant
 * de la chaîne lesson_* dans les ressources) et son attente.
 * Un même texte peut porter DEUX étapes (leçon des aides : la
 * bulle de Résoudre reste affichée PENDANT le passage du solveur,
 * la conclusion n'arrive qu'à la victoire).
 */
data class LessonStep(val bubbleId: String, val kind: ExpectKind)

/**
 * Plateau d'une leçon, entièrement reproductible : niveau (règles :
 * tore, masquage), dimensions explicites — le moteur accepte des
 * tailles hors matrice Screen, une leçon vit sur 3 x 3 ou 5 x 5
 * quand le jeu vrai ne le pourrait pas — générateur et graine
 * fixes, choisis par un script d'invariants (voir [ LessonTest]).
 *
 * [preSolved] : plateau livré RÉSOLU (leçon Infini : la
 * démonstration se fait sur un réseau propre — tourner une tuile
 * n'y est PAS le sujet, la caméra l'est). [cameraOnly] : la leçon
 * ne tourne et ne verrouille RIEN (leçon Infini) — le modèle
 * ignore les appuis, seule la caméra parle.
 */
data class LessonBoard(
    val skill: Skill,
    val width: Int,
    val height: Int,
    val generator: Generator,
    val seed: Long,
    val preSolved: Boolean = false,
    val cameraOnly: Boolean = false
) {
    /**
     * Construit le plateau de la leçon : génération déterministe,
     * puis, sur [preSolved], remise à l'aplomb de chaque tuile par
     * ses propres quarts de tour (API publique du moteur, aucun
     * état interne touché) — le réseau ressort connecté, énergisé,
     * tel un plateau juste gagné.
     */
    fun build(): Board {
        val b = Board.generate(skill, width, height, generator, seed)
        if (preSolved) {
            for (i in 0 until b.size) {
                val steps = (4 - b.rotations[i]) % 4
                if (steps != 0) b.rotateCell(i, steps)
            }
        }
        return b
    }
}

/**
 * Une leçon : identifiant stable (le validateur l'épingle),
 * identifiant du titre affiché dans la liste, plateau et scénario.
 */
data class Lesson(
    val id: String,
    val titleId: String,
    val board: LessonBoard,
    val steps: List<LessonStep>
)

/**
 * Les sept leçons, dans l'ordre validé par eric : la base, la
 * méthode, les aides, puis les concepts exotiques un à un. Chaque
 * leçon ne suppose que ce qui la précède. Les graines sont FIGÉES
 * par les tests d'invariants ([ LessonTest]) : toute modification
 * doit re-valider la situation pédagogique.
 */
object Lessons {

    /** Leçon 1 — tourner, connecter, gagner (3 x 3, un terminal). */
    private fun lesson1() = Lesson(
        id = "l1",
        titleId = "lesson_1_title",
        board = LessonBoard(
            skill = Skill.NOVICE, width = 3, height = 3,
            generator = Generator.TREE, seed = 13L
        ),
        steps = listOf(
            LessonStep("lesson_l1_b1", ExpectKind.ROTATE),
            LessonStep("lesson_l1_b2", ExpectKind.VICTORY),
            LessonStep("lesson_l1_b3", ExpectKind.CONTINUE)
        )
    )

    /** Leçon 2 — la méthode, proche en proche, puis verrouiller. */
    private fun lesson2() = Lesson(
        id = "l2",
        titleId = "lesson_2_title",
        board = LessonBoard(
            skill = Skill.NOVICE, width = 5, height = 5,
            generator = Generator.TREE, seed = 3L
        ),
        steps = listOf(
            LessonStep("lesson_l2_b1", ExpectKind.ROTATE),
            LessonStep("lesson_l2_b2", ExpectKind.LOCK),
            LessonStep("lesson_l2_b3", ExpectKind.VICTORY),
            LessonStep("lesson_l2_b4", ExpectKind.CONTINUE)
        )
    )

    /** Leçon 3 — les aides : indice informatif, correctif, Résoudre. */
    private fun lesson3() = Lesson(
        id = "l3",
        titleId = "lesson_3_title",
        board = LessonBoard(
            skill = Skill.NOVICE, width = 5, height = 5,
            generator = Generator.TREE, seed = 2L
        ),
        steps = listOf(
            LessonStep("lesson_l3_b1", ExpectKind.HINT_SIGNAL),
            LessonStep("lesson_l3_b2", ExpectKind.HINT_FIX),
            LessonStep("lesson_l3_b3", ExpectKind.SOLVE),
            LessonStep("lesson_l3_b3", ExpectKind.VICTORY),
            LessonStep("lesson_l3_b4", ExpectKind.CONTINUE)
        )
    )

    /** Leçon 4 — le tableau torique : une connexion traverse le bord. */
    private fun lesson4() = Lesson(
        id = "l4",
        titleId = "lesson_4_title",
        board = LessonBoard(
            skill = Skill.MASTER, width = 5, height = 5,
            generator = Generator.TREE, seed = 4L
        ),
        steps = listOf(
            LessonStep("lesson_l4_b1", ExpectKind.CONTINUE),
            LessonStep("lesson_l4_b2", ExpectKind.VICTORY),
            LessonStep("lesson_l4_b3", ExpectKind.CONTINUE)
        )
    )

    /** Leçon 5 — les tuiles invisibles : déduire puis verrouiller. */
    private fun lesson5() = Lesson(
        id = "l5",
        titleId = "lesson_5_title",
        board = LessonBoard(
            skill = Skill.INSANE, width = 5, height = 5,
            generator = Generator.TREE, seed = 5L
        ),
        steps = listOf(
            LessonStep("lesson_l5_b1", ExpectKind.ROTATE),
            LessonStep("lesson_l5_b2", ExpectKind.VICTORY),
            LessonStep("lesson_l5_b3", ExpectKind.CONTINUE)
        )
    )

    /**
     * Leçon 6 — le tableau infini, scénario d'eric version
     * enrichie : plateau 40 x 40 PRÉ-RÉSOLU, tour HORIZONTAL puis
     * tour VERTICAL jusqu'à RETROUVER le serveur, puis pincements
     * zoom/dézoom. Le joueur ne connecte RIEN : la caméra est la
     * leçon, la propriété torique se VIT (le plateau se répète à
     * l'infini, jamais d'anneau gris — rendu modulo existant).
     */
    private fun lesson6() = Lesson(
        id = "l6",
        titleId = "lesson_6_title",
        board = LessonBoard(
            skill = Skill.ENDLESS, width = Skill.ENDLESS_SIZE,
            height = Skill.ENDLESS_SIZE,
            generator = Generator.ORIGINAL, seed = 6006L,
            preSolved = true, cameraOnly = true
        ),
        steps = listOf(
            LessonStep("lesson_l6_b1", ExpectKind.PAN_X),
            LessonStep("lesson_l6_b2", ExpectKind.PAN_Y),
            LessonStep("lesson_l6_b3", ExpectKind.ZOOM),
            LessonStep("lesson_l6_b4", ExpectKind.CONTINUE)
        )
    )

    /** Leçon 7 — le brouillard : chaque connexion repousse la brume. */
    private fun lesson7() = Lesson(
        id = "l7",
        titleId = "lesson_7_title",
        board = LessonBoard(
            skill = Skill.VOID, width = 6, height = 6,
            generator = Generator.ORIGINAL, seed = 2L
        ),
        steps = listOf(
            LessonStep("lesson_l7_b1", ExpectKind.ROTATE),
            LessonStep("lesson_l7_b2", ExpectKind.VICTORY),
            LessonStep("lesson_l7_b3", ExpectKind.CONTINUE)
        )
    )

    val ALL: List<Lesson> = listOf(
        lesson1(), lesson2(), lesson3(), lesson4(),
        lesson5(), lesson6(), lesson7()
    )
}

/**
 * Événement envoyé à la machine d'étapes par le modèle de la
 * leçon (LessonViewModel) : action jouée, indice demandé, plateau
 * résolu, ou déplacement de caméra (leçons Infini) — les deltas
 * de défilement s'expriment en CELLULES, le zoom en FACTEUR.
 */
sealed interface LessonEvent {
    data class Rotated(val index: Int) : LessonEvent
    data class Locked(val index: Int, val nowLocked: Boolean) : LessonEvent
    data class HintSignal(val index: Int) : LessonEvent
    data class HintFix(val index: Int) : LessonEvent
    object SolvePressed : LessonEvent
    object Victory : LessonEvent
    data class Scrolled(val dxCells: Float, val dyCells: Float) : LessonEvent
    data class Zoomed(val ratio: Float) : LessonEvent
}

/**
 * Machine d'étapes d'une leçon : chaque étape attend son événement,
 * les déplacements de caméra s'accumulent jusqu'au tour complet du
 * plateau, le pincement jusqu'à l'amplitude requise. Les
 * accumulateurs se REMETTENT À ZÉRO à chaque changement d'étape :
 * le voyage se mesure depuis le DÉBUT de la consigne, pas depuis
 * le début de la leçon.
 *
 * PURE : aucun Android ici, [ LessonTest] rejoue chaque attente.
 */
class LessonRunner(private val lesson: Lesson) {

    /** Index de l'étape courante, size = leçon terminée. */
    var index: Int = 0
        private set

    private var accX = 0f
    private var accY = 0f
    private var zoom = 1f
    private var zoomMax = 1f
    private var zoomMin = 1f

    val finished: Boolean get() = index >= lesson.steps.size
    val step: LessonStep? get() = lesson.steps.getOrNull(index)
    val kind: ExpectKind? get() = step?.kind

    /**
     * Le bouton « Continuer » : seule voie de sortie d'une étape de
     * lecture. N'avance JAMAIS une étape d'action.
     */
    fun onContinue() {
        if (step?.kind == ExpectKind.CONTINUE) advance()
    }

    /** Consomme un événement ; avance l'étape s'il la satisfait. */
    fun onEvent(event: LessonEvent) {
        val s = step ?: return
        val ok = when (s.kind) {
            ExpectKind.CONTINUE -> false
            ExpectKind.ROTATE ->
                event is LessonEvent.Rotated
            ExpectKind.LOCK ->
                event is LessonEvent.Locked && event.nowLocked
            ExpectKind.UNLOCK ->
                event is LessonEvent.Locked && !event.nowLocked
            ExpectKind.HINT_SIGNAL ->
                event is LessonEvent.HintSignal
            ExpectKind.HINT_FIX ->
                event is LessonEvent.HintFix
            ExpectKind.SOLVE ->
                event is LessonEvent.SolvePressed
            ExpectKind.VICTORY ->
                event is LessonEvent.Victory
            ExpectKind.PAN_X -> {
                if (event is LessonEvent.Scrolled) {
                    accX += event.dxCells
                    // Un tour complet, à une demi-tuile près : le
                    // rendu modulo ramène exactement au repère à
                    // width ; la tolérance couvre le doigt.
                    abs(accX) >= lesson.board.width - 0.5f
                } else false
            }
            ExpectKind.PAN_Y -> {
                if (event is LessonEvent.Scrolled) {
                    accY += event.dyCells
                    abs(accY) >= lesson.board.height - 0.5f
                } else false
            }
            ExpectKind.ZOOM -> {
                if (event is LessonEvent.Zoomed) {
                    // Amplitude CUMULÉE du pincement : sur le mode
                    // Infini le plancher de dézoom colle au
                    // cadrage ajusté, seul le zoom AVANT compte —
                    // un pincement net (30 %) valide l'étape, le
                    // dézoom vaut s'il est accessible.
                    zoom *= event.ratio
                    zoomMax = max(zoomMax, zoom)
                    zoomMin = min(zoomMin, zoom)
                    zoomMax >= ZOOM_IN_DONE || zoomMin <= ZOOM_OUT_DONE
                } else false
            }
        }
        if (ok) advance()
    }

    private fun advance() {
        index += 1
        // Le voyage de la NOUVELLE consigne part de zéro : les
        // accumulateurs ne portent JAMAIS le déplacement de
        // l'étape précédente.
        accX = 0f
        accY = 0f
        zoom = 1f
        zoomMax = 1f
        zoomMin = 1f
    }

    companion object {
        /** Pincement validant : 30 % de zoom avant cumulé. */
        const val ZOOM_IN_DONE = 1.3f

        /** Ou 20 % de zoom arrière, quand le plancher le permet. */
        const val ZOOM_OUT_DONE = 0.8f
    }
}

/** [abs] et [max]/[min] flottants de la machine d'étapes. */
private fun abs(v: Float) = if (v < 0f) -v else v
private fun max(a: Float, b: Float) = if (a >= b) a else b
private fun min(a: Float, b: Float) = if (a <= b) a else b
