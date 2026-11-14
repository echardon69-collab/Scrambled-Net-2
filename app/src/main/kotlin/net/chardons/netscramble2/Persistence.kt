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
}
