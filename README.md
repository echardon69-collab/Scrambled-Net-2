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
                  animations de rotation et blips (M2) ;
                  EdgeToEdge : insets bord à bord partagés (M6)
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
- M6 finition UI/UX : edge-to-edge (barres système transparentes,
  contraste des icônes jour/nuit, retour prédictif), marges adaptées
  aux tablettes ≥ 600dp (livré)
- « À propos » : page autonome à la place de la fenêtre — licence
  complète (KNetWalk, Scrambled Net, Scrambled Net 2), propositions
  de participation (bug, contact, traduction, skin) et listes des
  fonctionnalités nouvelles, modifiées, conservées et retirées par
  rapport au jeu d'origine (livré le 14 nov. 2026)
- Renseigner les liens de À propos : les quatre propositions de
  participation (signaler un bug, contacter l'auteur, proposer une
  traduction, proposer un skin) affichent « lien à venir » en
  attendant les adresses réelles (à venir)
- Processus de traduction : kit généré par script depuis les sources
  (tableau clé|texte|contexte, glossaire, règles de format, licence),
  traducteur sur GitHub PR, relecture IA, complétion par delta à
  chaque évolution, contrôles CI ; guide docs/,
  preparer_kit_traduction.py et validate_langues.py (étude actée le
  14 nov. 2026) ; outillage LIVRÉ le 16 nov. 2026 — guide du
  traducteur docs/TRADUIRE.md, kit généré par script (tableau
  clé|texte|contexte, glossaire, règles, licence, complétion par
  delta) et validateur de langues (parité des clés 98 + 26,
  paramètres, apostrophes échappées, XML bien formé) ; processus B
  acté : GitHub PR + relecture IA (back-traduction) ; premier
  traduction corse menée par l'IA le 16 nov. 2026 (première
  passe livrée dans values-co, relecture par le locuteur corse
  d'eric à venir)
- Langue corse : traduction du jeu en corse, ajoutée à la roadmap
  le 16 nov. 2026 ; portée par le processus de traduction ci-dessus
  (kit, relecture, complétion par delta, contrôles CI). Première
  passe IA LIVRÉE le 16 nov. 2026 (values-co complet, 98 chaînes +
  26 textes, locales_config) : pas de traducteur trouvé, eric a
  confié la traduction à l'IA ; RELECTURE par le testeur/relecteur
  corse d'eric à venir, ses corrections réintègrent par le même
  canal. Le sélecteur de langue dynamique (rendre le corse
  sélectionnable dans la page Réglages, en plus du réglage système
  « langue par application » qui le propose dès à présent) reste à
  venir
- Aide à la résolution : Indice (plafond paramétrable, informatif ou
  correctif) + Résoudre sous Outils + Démonstration en boucle
  (difficulté, zoom, rythme) (livré le 15 nov. 2026)
- Effets spéciaux de réussite : animation de récompense à la
  victoire d'un plateau résolu sans aide — vague 2.5D (onde de choc
  qui soulève les tuiles, origine hors de la zone visible) ou
  feu d'artifice (fusées depuis le bas de la zone), tirés au sort, 4,5 s,
  dialogue de victoire en attente, skip au toucher — et intermède
  entre les plateaux de la démonstration (il remplace la pause de
  1,5 s ; repli sur la pause si l'animation est coupée). Absorbe
  l'ancien sujet « animation vague de tuiles à la victoire »
  (nouveauté SN2, liberté totale de design) ; l'explosion du plateau est reportée
  en V2 (livré le 15 nov. 2026)
- Modifications diverses : premier lot issu des retours UAT —
  les effets spéciaux couvrent désormais l'ensemble de l'écran du
  téléphone (le feu d'artifice vit en repère écran pleine vue,
  la vague soulève aussi le décor autour du plateau) ; la vague
  est amplifiée et une seconde vague la suit dans la même
  animation, sur un côté différent (livré le 16 nov. 2026) ;
  deuxième lot issu des retours utilisateurs — mention
  « Démonstration niveau X » en bas de l'écran de la démonstration,
  les tuiles invisibles (Dément, Abîme, Néant) restent invisibles
  (ni la démonstration, ni l'outil Résoudre ne révèle plus leurs
  câbles : seule la victoire dévoile le plateau, l'indice
  correctif révèle toujours), et au Néant la démonstration cadre
  sa zone révélée en ne touchant que les tuiles visibles, comme
  un joueur (livré le 16 nov. 2026) ; troisième lot — la pile du
  bas de la démo (bandeau de niveau + « touchez pour revenir »,
  affiché en permanence) est dégagée de la barre de navigation
  Android et repose sur un voile rectangulaire à 80 % d'opacité
  qui opacifie les tuiles dessous, posé sur un containeur interne
  (l'inset de la barre reste hors de la zone colorée), interligne
  resserré à 5 dp ; l'énergie d'un coup — câbles énergisés,
  écrans, DEL du serveur, blips — n'apparaît jamais PENDANT la
  rotation du coup qui la provoque, seulement après sa fin (à
  l'arrivée d'un coup, toute rotation en vol est terminée d'un
  coup) ; le jeu et son outil Résoudre bénéficient du même
  correctif ; les niveaux de la démonstration se cochent
  (plusieurs niveaux possibles, Novice par défaut, toujours au
  moins une coche, radios Aléatoire/Progressif grisées à coche
  unique, Progressif par défaut) ; quatrième lot — le réglage de
  zoom de la démonstration est retiré (cadrage TOUJOURS ajusté,
  appliqué dès le PREMIER plateau : le préréglage posé avant la
  première mesure attend le premier layout) et les billes de la
  vague de victoire suivent les tuiles soulevées (même soulèvement
  que la passe des cellules, plus de billes hors des connecteurs) ;
  cinquième lot — le réglage du plafond d'indices ne propose plus
  que Désactivé, 10 % (défaut) et 20 % (le pourcentage s'applique
  au nombre de tuiles mal orientées au mélange initial, base
  désormais documentée dans le code) ; le mode panic/travail est
  retiré de la roadmap, sans intérêt pour les utilisateurs ; la
  traduction en langue corse entre à la roadmap ; sixième lot —
  le nombre d'indices est FIXE par niveau : le pourcentage du
  plafond s'applique à la taille de référence du niveau (matrice
  MEDIUM de l'original), ni le mélange tiré ni l'écran de
  l'appareil ne le font varier (livré le 16 nov. 2026) ; septième lot — glossaire développeur : GLOSSAIRE.md à la
  racine traduit en français les concepts et termes anglais du code
  (plateau, tuiles, caméra, blips, indices, réglages…), avec leurs
  emplacements, à compléter à chaque chantier (livré le 16 nov. 2026)
- ID unique par plateau : identifiant généré par plateau, porte
  le partage de la graine (absorbe l'ancien sujet « seed
  partageable ») ; utilisation exacte à préciser : partage,
  reprise, records ? (à venir)
- Thèmes daltoniens : palettes du jeu adaptées aux
  dyschromatopsies (détails à venir)
- Date limite d'utilisation : les versions qui ne sont pas des
  releases officielles du jeu (les versions 0.x.y par exemple)
  portent une date limite d'utilisation ; au-delà de cette date,
  le jeu cesse de fonctionner et la page de chargement affiche le
  message « version obsolète ! » (à venir)
