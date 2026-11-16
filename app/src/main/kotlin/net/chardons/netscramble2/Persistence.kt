package net.chardons.netscramble2

/**
 * Clés de persistance partagées entre activités.
 *
 * La partie en cours vivait dans les préférences PROPRES à l'activité
 * (getPreferences) : le fichier s'appelait donc « MainActivity », du
 * simple nom de sa classe. La page titre (TitleActivity) doit à son
 * tour lire cette photographie — même fichier, même clé — sans que
 * MainActivity ne change de comportement. Les constantes vivent ici
 * pour éviter la duplication de chaînes magiques entre les deux.
 *
 * Le nom du fichier est FIGÉ sur la valeur historique : les parties
 * sauvegardées avant ce chantier restent lisibles à l'identique.
 */
object Persistence {

    /** Fichier de préférences de la partie en cours (nom historique). */
    const val FILE = "MainActivity"

    /** Clé de la photographie de partie sérialisée (GameSpec.encode). */
    const val GAME_SPEC_KEY = "gameSpec"

    /**
     * Clé du mode sonore, reprise de l'original. Vit dans le MÊME
     * fichier (historique) que la partie : les réglages sauvegardés
     * avant la page unique « Réglages » restent lisibles à
     * l'identique (la page réglages écrit via [Settings], jamais
     * dans un fichier qui lui serait propre).
     */
    const val SOUND_MODE_KEY = "soundMode"

    /** Clé du réglage d'animation, reprise de l'original (cf. ci-dessus). */
    const val ANIM_ENABLED_KEY = "animEnable"

    // --- Chantier Aide à la résolution : réglages indice et
    //     démonstration, même fichier historique que le reste. ---

    /** Clé de la stratégie d'indice (SIGNAL ou FIX). */
    const val HINT_STRATEGY_KEY = "hintStrategy"

    /** Clé du plafond d'indices en pourcentage (0 à 50, pas de 5). */
    const val HINT_PERCENT_KEY = "hintPercent"

    /** Clé de la difficulté de la démonstration (FIXED, RANDOM, PROGRESSIVE). */
    const val DEMO_DIFFICULTY_KEY = "demoDifficulty"

    /**
     * Clé du MASQUE des niveaux cochés de la démonstration (v8) :
     * un bit par niveau (1 shl Skill.ordinal), Novice seul par
     * défaut. L'ancienne clé demoSkill (un seul niveau) est
     * ignorée : ancien « FIXED » introuvable → défaut PROGRESSIVE.
     */
    const val DEMO_SKILL_SET_KEY = "demoSkillSet"

    /** Clé du rythme de la démonstration (délai par coup du solveur). */
    const val DEMO_SPEED_KEY = "demoSpeed"
}
