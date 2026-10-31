Scrambled Net 2
===============

Refonte moderne de Scrambled Net (jeu de puzzle « désembrouille-réseau »,
portage Android du jeu KDE KNetWalk) :

- un seul projet Gradle (l'ancienne usine à bibliothèques HermitAndroid /
  HermitLibrary a été supprimée — seules les classes réellement utilisées
  ont été réintégrées) ;
- Kotlin, Views + Material 3 ;
- graphismes : 19 VectorDrawables convertis automatiquement depuis les SVG
  d'origine (15 tuiles câbles, computer1/computer2, server/server1) avec
  leurs dégradés natifs API 24+ (<aapt:attr>) — fidèles au look historique,
  un seul fichier pour toutes les densités, netteté parfaite à tout zoom,
  ~68 Ko au total contre ~1,1 Mo de SVG ;
- rendu événementiel (aucune boucle à 33 fps) : économe en batterie ;
- viewport « caméra » : plateau virtuel déplaçable au doigt, zoom au
  pinçage, taille de case minimale garantie (26 dp) ;
- entrées tactiles uniquement (smartphones et tablettes) ;
- i18n conservée et étendue (anglais de base + français) ;
- moteur de jeu pur (aucune dépendance Android) couvert de tests unitaires
  JVM ;
- zéro permission Android, aucun tracking → éligible F-Droid.

Origine et licence
------------------
Basé sur Scrambled Net © 2007–2010 Ian Cameron Smith (Moonblink),
© 2014 Michael Mueller (SilentServices), licence GPL-2.0 (voir LICENSE
et NOTICE). Ce projet en est un travail dérivé, donc GPL-2.0.

Prérequis
---------
- JDK 21 (JDK 17 minimum pour l'AGP)
- Android Studio (recommandé) ou SDK Android avec `sdkmanager`
- Le wrapper Gradle est versionné (build reproductible, exigence F-Droid) :
  `./gradlew` télécharge Gradle 9.8.2 et vérifie son empreinte SHA-256
  (`distributionSha256Sum` dans gradle/wrapper/gradle-wrapper.properties).

Versions retenues (octobre 2026, voir gradle/libs.versions.toml)
----------------------------------------------------------------
- Gradle 9.8.2, plugin Android 9.4.1, Kotlin 2.4.21
- compileSdk / targetSdk 36, minSdk 24 (Android 7.0 — dégradés natifs)

Identifiant
-----------
applicationId = `net.chardons.netscramble2` (définitif — ne plus le changer
une fois publié sur F-Droid).

Commandes utiles
----------------
    ./gradlew testDebugUnitTest    # tests unitaires (aucun émulateur requis)
    ./gradlew assembleDebug        # APK de debug
    ./gradlew assembleRelease      # APK de release (R8 + shrinkResources)

Publication F-Droid : checklist
-------------------------------
1. Icône adaptative (mipmap-anydpi-v26) en remplacement du vecteur
   provisoire `ic_launcher`.
2. Build reproductible : wrapper Gradle versionné avec empreinte vérifiée
   (fait) ; signatures en local uniquement (les keystores ne sont pas
   versionnés, voir .gitignore).
3. Remplir les métadonnées (résumé, description, captures, licence GPL-2.0,
   lien du dépôt) et soumettre le build à fdroiddata.

Structure
---------
    app/src/main/kotlin/net/chardons/netscramble2/
        game/     moteur pur : Dir, Board (génération/mélange/connexité),
                  BlipField (blips de données), Skill, Sound (événements
                  sonores), GameSpec (sérialisation de partie)
        audio/    SoundEffects : SoundPool basse latence, six sons courts
                  synthétisés (aucun échantillon de l'original réutilisé)
        ui/       BoardView : rendu VectorDrawable + viewport pan/zoom,
                  animations de rotation et blips (M2)
        MainActivity.kt, GameViewModel.kt
        ScoresStore.kt, ScoresActivity.kt : records personnels par
                  niveau (M4)
        HelpActivity.kt : manuel du jeu en sections (M5)
    app/src/main/res/drawable/   19 tuiles VectorDrawable converties des
                                  SVG d'origine (cable*, computer*, server*)
    app/src/main/res/raw/        6 effets sonores WAV synthétisés
                                  (start, turn, click, pop, connect, win)
    app/src/test/                tests unitaires JVM du moteur

Feuille de route (validée)
--------------------------
- M0 amorçage / build vert (fait)
- M1 moteur complet : cellules cachées, verrouillage, auto-solveur (livré)
- M2 rendu de parité : animations de rotation, « blips » de données (livré)
- M3 sons modernes : SoundPool + 6 sons synthétisés, menu
  Désactivés/Discrets/Complets persisté (livré)
- M4 scores / persistance : records par niveau, écran des records,
  partie sauvegardée (survit à la mort du processus), chrono gelé en
  arrière-plan, dialogue de victoire (livré)
- M5 aide / réglages : manuel du jeu, réglage Animation
  (les blips seuls, comme l'original), rappels Maître/Dément au
  changement de niveau (livré)
- M6 finition UI/UX
- Phase bonus : seed partageable, undo, tailles custom, thèmes daltoniens
