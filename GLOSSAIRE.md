# Glossaire développeur — termes anglais du code

Ce glossaire traduit en français les concepts et les termes anglais employés
dans le code de Scrambled Net 2, pour qu'un développeur francophone qui découvre
le projet comprenne rapidement chaque notion qu'il rencontrera : où elle vit
(classe, énumération, fichier), ce qu'elle désigne et comment elle s'utilise.

Il est voué à être complété : chaque nouvelle notion introduite dans le code
devrait y gagner sa ligne. Les tableaux sont du Markdown pur, éditables dans
n'importe quel éditeur ou sur GitHub directement.

Convention des colonnes :

- **Terme** : l'identifiant anglais tel qu'il apparaît dans le code
  (classe, propriété, fonction, constante ou clé).
- **Définition** : ce que c'est, en français, avec les nuances du projet.
- **Emplacements** : fichier(s), classe(s), énumération(s) où le terme vit
  ou s'utilise.

## Sommaire

1. [Moteur et plateau](#1-moteur-et-plateau)
2. [Niveaux de difficulté (Skill)](#2-niveaux-de-difficulté-skill)
3. [Écran et tailles de plateau (Screen)](#3-écran-et-tailles-de-plateau-screen)
4. [Rendu et caméra (BoardView)](#4-rendu-et-caméra-boardview)
5. [Tuiles : types, couches, états](#5-tuiles--types-couches-états)
6. [Blips et énergie](#6-blips-et-énergie)
7. [Aide à la résolution (indice, sceau, plafond)](#7-aide-à-la-résolution-indice-sceau-plafond)
8. [Effets de victoire](#8-effets-de-victoire)
9. [Démonstration](#9-démonstration)
10. [Sauvegarde et persistance](#10-sauvegarde-et-persistance)
11. [Réglages](#11-réglages)
12. [Sons](#12-sons)
13. [Scores](#13-scores)
14. [Chaînes de caractères (strings)](#14-chaînes-de-caractères-strings)
15. [Péremption](#15-péremption)
16. [Apprentissage](#16-apprentissage)

---

## 1. Moteur et plateau

Le paquet `game/` contient le modèle PUR du jeu : aucune dépendance Android,
testable sur la JVM seule.

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Board` | Le plateau de jeu : le modèle central, pur (aucune dépendance Android). Représente le réseau d'une partie : solution, rotations, verrous, scellées, etc. Entièrement reproductible depuis (dimensions, générateur, graine, racine). | `game/Board.kt`, utilisé partout |
| `cell` | Une case du plateau, c'est-à-dire une tuile. Le code dit presque toujours « cell » ; l'interface dit « tuile ». Index = `y * width + x`. | `game/Board.kt`, `ui/BoardView.kt` |
| `connections` | Tableau des masques de connexions de la SOLUTION, une valeur 4 bits par cellule. Généré une fois pour toutes à la création. | `Board.connections` |
| `rotations` | Tableau des rotations courantes (0 à 3 quarts de tour horaires) de chaque cellule : c'est ce que le joueur modifie. Connexions courantes = `Dir.rotateMask(connections[i], rotations[i])`. | `Board.rotations`, sauvé dans `GameSpec` |
| `Dir` | Les quatre directions cardinales (`UP`, `RIGHT`, `DOWN`, `LEFT`), chacune portant un bit du masque de connexions (1, 2, 4, 8). | `game/Dir.kt` |
| `bit` / `mask` | Un masque de connexions est un entier 4 bits : chaque bit levé = un côté de la cellule par lequel elle se connecte. Ex. `UP.bit or DOWN.bit` (5) = cellule connectée en haut et en bas. | `game/Dir.kt` |
| `NO_DIRS` / `ALL_DIRS` | Masques 0 (aucune connexion, cellule libre) et 15 (croix complète). | `Dir` (companion) |
| `rotateMask(mask, steps)` | Fait tourner un masque de connexions de `steps` quarts de tour horaires : l'opération de base du jeu, tourner une tuile. | `Dir` (companion) |
| `rotatedCW(steps)` | Direction obtenue après `steps` quarts de tour horaires (négatif = anti-horaire). | `Dir` |
| `opposite` | Direction opposée (`UP` <-> `DOWN`, `RIGHT` <-> `LEFT`). | `Dir` |
| `Generator` | Le générateur du réseau d'une partie : `ORIGINAL` (croissance par branchements aléatoires depuis le serveur, traduction de `createNet` de l'original, victoire par terminaux) ou `TREE` (arbre couvrant aléatoire, victoire quand tout est relié). | `game/Board.kt` (enum `Generator`) |
| `seed` | Graine du flux pseudo-aléatoire : toute la partie (génération puis mélange) est déterministe et reproductible depuis la graine. | `Board.seed`, `GameSpec.seed` |
| `scramble` | Le mélange initial : rotations aléatoires de chaque cellule, tirées d'un flux enraciné sur la graine (salé). | `Board.scramble` |
| `root` / `forcedRoot` / `serverIndex` | La racine du réseau, c'est-à-dire la cellule du SERVEUR. `forcedRoot` = racine imposée (-1 = tirée du générateur) ; `serverIndex` = index effectif tiré. Une racine imposée ne consomme AUCUN tirage (piège de relecture des anciennes parties). | `Board`, `GameSpec.root` |
| `wrap` | Tore : les bords du plateau bouclent sur eux-mêmes (Maître, Dément et toute la famille Infini). Sans tore, les câbles ne fuient jamais hors du plateau. | `Board.wrap`, `Skill.wrap` |
| `neighborIndex` | Index de la cellule voisine dans une direction (en tenant compte du tore si `wrap`). | `Board.neighborIndex` |
| `terminal` | Cellule à une seule connexion (un bout de câble). Avec le générateur ORIGINAL, la partie est gagnée quand tous les terminaux sont reliés au serveur. | `Board.isTerminal` |
| `connected` / `connectedCells` | Tableau booléen : quelles cellules sont reliées au serveur avec les rotations COURANTES. C'est l'état « énergie » affiché. | `Board.connectedCells`, `BoardView.update(board, connected, solved)` |
| `isSolved` | Partie gagnée : tous les terminaux reliés (ORIGINAL) ou tout le plateau relié (TREE). | `Board.isSolved` |
| `solutionIsSound` | Garde-fou : la solution générée est réellement résolue à rotation 0. | `Board.solutionIsSound` |
| `Board.generate` | Fabrique du plateau : tire la racine, génère, mélange, endosse le niveau. | `Board` (companion) |

## 2. Niveaux de difficulté (Skill)

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Skill` | L'énumération des niveaux de difficulté, fidèle à l'enum `Skill` de `BoardView.java` de l'original. | `game/Skill.kt` |
| `NOVICE` / `NORMAL` / `EXPERT` | Novice, Normal, Expert : 2 branches par cellule, pas de tore. | `Skill` |
| `MASTER` | Maître : 3 branches et tore (le réseau boucle, carrefours à trois voies). | `Skill` |
| `INSANE` | Dément : comme Maître, plus les cellules câblées masquées. | `Skill` |
| `ENDLESS` / `ABYSS` / `VOID` | Infini, Abîme, Néant : la famille « Infini » (`endless = true`), plateau géant FIXE 40 x 40 en tore, matrice Screen bypassée, générateur ORIGINAL forcé, caméra modulo. Ce qui la gradue : tout (Infini), puis sans les câbles visibles (Abîme), puis uniquement le réseau construit par le joueur dans le brouillard (Néant). | `Skill` |
| `endless` | Propriété d'appartenance à la famille Infini : le code demande au niveau, il ne teste plus « == ENDLESS » un peu partout. | `Skill.endless` |
| `ENDLESS_SIZE` | Côté FIXE du plateau de la famille Infini (40 x 40). | `Skill` (companion) |
| `branches` | Nombre maximal d'embranchements par cellule pour le générateur ORIGINAL (2 jusqu'à Expert, 3 au-delà) : c'est ce qui introduit les T et les croix. | `Skill.branches`, `Board.branches` |
| `hiddenThreshold` | Seuil de masquage (niveaux type Dément) : une cellule avec au moins autant de connexions cache ses câbles. 9 = jamais atteint (4 connexions max). | `Skill.hiddenThreshold`, `Board.hiddenThreshold` |
| `hintReferenceTiles` | Taille de RÉFÉRENCE du niveau pour le plafond d'indices (v12) : le nombre de tuiles d'une matrice MEDIUM de l'original, FIXE par niveau (p. ex. 63 pour Normal). | `Skill` (companion) |

## 3. Écran et tailles de plateau (Screen)

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Screen` | La « matrice » d'écran : quelles dimensions de plateau attribuer selon l'appareil, réplique fidèle de l'enum `Screen` de l'original. | `game/Screen.kt` |
| `SMALL` / `WSMALL` / `MEDIUM` / `WMEDIUM` / `HUGE` | Les cinq matrices, choisies d'après la plus petite dimension de l'écran et son ratio d'aspect (le W initial = écran large, wide). | `Screen` |
| `major` / `minor` | Grand et petit côté du plateau : major = dimension horizontale en paysage, verticale en portrait. | `Screen` |
| `boardSize(skill, landscape)` | Taille (largeur, hauteur) du plateau du niveau demandé : chaque niveau prélève son rectangle dans la matrice ; la famille Infini bypass la matrice (taille fixe). | `Screen.boardSize` |
| `find(widthPx, heightPx)` | Choisit la matrice d'après les dimensions de l'écran (héritier de findMatrix de l'original). | `Screen` (companion) |

## 4. Rendu et caméra (BoardView)

`ui/BoardView.kt` est la vue Android : elle dessine le plateau, anime, gère les
gestes. Le rendu est événementiel : pas de thread dédié, on redessine par
`invalidate()` à la demande ; une boucle de trame vsync
(`postInvalidateOnAnimation`) tourne tant que la vue est affichée.

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `viewport` / `camera` | Le point de vue : le plateau est un monde virtuel potentiellement plus grand que l'écran ; la caméra est la fenêtre qu'on y promène. | `BoardView` (KDoc), `cameraSnapshot` |
| `pan` / `panX` / `panY` | Le déplacement de la caméra dans le monde (décalage en pixels entre monde et écran). Le verbe « panner » = faire glisser. | `BoardView` |
| `zoom` / `cellPx` | Taille courante d'une cellule en pixels : c'est LE zoom de la vue. Borné entre `floorCellPx` et `maxCellPx`. | `BoardView` |
| `MIN_CELL_DP` / `MAX_CELL_DP` | Bornes de taille de case : jamais plus petite que 26 dp, jamais plus grande que 96 dp. | `BoardView` (companion) |
| `floorCellPx` / `maxCellPx` | Traduction en pixels des bornes de zoom pour le plateau courant. `floorCellPx` dépend du plateau : elle garantit aussi qu'au moins une tuile grise encadre le plateau. | `BoardView` |
| `fitBoard` | Cadrage « ajusté » : recentre et adapte le zoom pour que le plateau entier tienne dans la vue. | `BoardView` |
| `clampPan` | Bornage du pan : la caméra ne sort jamais du plateau (niveaux encadrés) et ne découvre jamais plus d'une tuile grise. | `BoardView` |
| `floorMod` / caméra modulo | Reste euclidien : pour la famille Infini, le pan n'est pas borné, dessin et taps passent par le reste euclidien sur l'étendue du plateau, qui se répète pour remplir l'écran (aucune extrémité visible). | `BoardView`, `Board.fogVisibility` |
| `GREY_RING_CELLS` | Nombre de tuiles grises qui encadrent le plateau : la limite du plateau est toujours visible, le gris remplit le reste de la zone visible. | `BoardView` (companion) |
| `ENDLESS_EDGE_MARGIN_CELLS` | Marge du dézoom en famille Infini : le plateau entier ne tient JAMAIS dans la vue. | `BoardView` (companion) |
| `onDraw` / `invalidate` / `postInvalidateOnAnimation` | Le dessin et son déclencheur : `onDraw` redessine, `invalidate` demande un redraw, la boucle de trame vsync s'arrête hors écran (énergie maîtrisée). | `BoardView` |
| `hitCell(x, y)` | Traduit une position écran en index de cellule touchée (passe par la caméra ; modulo en famille Infini). | `BoardView` |
| `onSingleTapUp` / `onLongPress` / `onScroll` / `onScale` | Gestes : tap = tourner une cellule, appui long = verrouiller, scroll = panner, scale = zoomer au pinçage ancré sur le point de pincement. | `BoardView` |
| `FollowMode` (`CENTER`, `VISIBLE`, `HYSTERESIS`) | Modes de suivi d'une cellule par la caméra : centrer la cellule, la rendre visible (bord minimal), ou visible avec hystérésis (pas de re-centrage si déjà visible). | `BoardView.FollowMode`, `followCell` |
| `solverFocus` | Cellule que l'auto-solveur est en train de traiter : la vue la cadre en `VISIBLE`. | `BoardView`, `GameViewModel` |
| `arrivalCamera` / `arrivalCameraPending` | Caméra « d'arrivée » posée par un plateau neuf (p. ex. cadrer la zone révélée au Néant) : `pending` la diffère jusqu'au premier layout, car la vue n'a pas encore été mesurée. | `BoardView` |
| `applyZoomPreset` / `zoomPresetPending` | Préréglage de zoom de la démo (cadrage ajusté FIT) : `pending` fait survivre l'appel posé avant la première mesure jusqu'au `onSizeChanged`. | `BoardView` |
| `restoreCamera` / `cameraSnapshot` | Recharge / photographie la caméra depuis `CameraSpec` (restauration de partie). | `BoardView`, `GameSpec.camera` |
| `waveLiftPx` | Soulèvement en pixels d'une tuile pendant la vague de victoire (0 en dehors de l'effet). | `BoardView`, `VictoryEffects` |
| `frame` / `tick` | La trame : un passage de rendu. Le tick du solveur = délai entre deux coups automatiques. | `BoardView`, `DemoSpeed` |
| `freeze` (gel v8) | Figé : pendant la rotation d'un coup, l'énergie affichée est celle de l'état PRÉCÉDENT (`prevConnected`), jamais un mélange des deux. | `BoardView.update` |

## 5. Tuiles : types, couches, états

Les tuiles sont des VectorDrawables convertis des SVG d'origine ; les 15
orientations du jeu sont des rotations au dessin, pas des fichiers distincts
(pipeline `tools/tiles/` depuis 5 sources : droit, coude, T, croix, bout).

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `empty` / cellule libre | Cellule hors réseau (masque 0) : aucune câble, fond de tuile gris. | `Board.isEmpty`, `emptyTileBitmap` |
| `server` | Le serveur : la racine du réseau, cellule d'où partent tous les câbles. DEL qui respirent (halo). | `Board.serverIndex`, `BoardView` |
| `terminal` / bout | Extrémité de câble (une seule connexion), écran d'ordinateur affichant des lignes de données quand il est alimenté. | `Board.isTerminal`, `TERM_LINE_STEP_DP` |
| `straight` / `turn` / `T` / `cross` | Les formes de câble : droit (2 côtés opposés), coude (2 côtés adjacents), T (embranchement à 3), croix (4). | sources SVG `tools/tiles/` |
| `background` / `background_locked` / `empty` | Les fonds de tuile biseautés « relief » : bleu marbré (normal), bleu plus foncé (verrouillée), gris (libre hors réseau), déclinés jour/nuit (drawable-night). | drawables, `BoardView` |
| `greyOut` | La variante grise des câbles déconnectés (parité de Cell.java : chaque canal RGB à 0,6 x valeur + 100). | pipeline `tools/tiles/` |
| `decor` | Le décor : ce qui n'est ni plateau ni câble (p. ex. la tuile vide dessinée sous la vague de victoire quand elle soulève aussi le décor). | `drawVictoryDecor`, `emptyTileBitmap` |
| `locked` | Cellule verrouillée par le joueur (appui long) : elle ne tourne plus, fond dédié. | `Board.locked`, `toggleLock` |
| `sealed` / `sealedCells` | Cellule scellée par un indice correctif : rotation à l'aplomb jouée, verrouillée pour de bon (invariant : toute scellée est verrouillée). | `Board.sealedCells`, `Board.seal` |
| `hidden` / `isHidden` | Cellule câblée masquée (seuil Dément) : ses câbles sont invisibles jusqu'à révélation. | `Board.isHidden`, `hiddenThreshold` |
| `revealed` / `reveal` | Cellule cachée dont les câbles ont été révélés (par l'indice correctif ou la fin de partie). | `Board.revealed`, `Board.reveal` |
| `touched` | Cellule touchée au moins une fois par le joueur (statistique d'aide). | `Board.touched` |
| `isMisplaced` | Cellule mal orientée au sens fonctionnel : ses connexions courantes diffèrent de la solution (indépendamment du tour complet). | `Board.isMisplaced` |

## 6. Blips et énergie

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `blip` | Une « bille » de données qui parcourt les câbles. Portage de Cell.java de l'original. Le mot est gardé tel quel, y compris dans les commentaires FR. | `game/BlipField.kt`, `BoardView.drawCellBlips` |
| `BlipField` | Le champ de blips du plateau : modèle pur, piloté par la vue (un `advance` toutes les 300 ms, position interpolée entre deux avances). | `game/BlipField.kt` |
| `incoming` / `outgoing` | Blips entrants (du bord vers le centre de la cellule) et sortants (du centre vers le bord) : un masque de directions par cellule. | `BlipField` |
| `leg` (jambe) | Un côté câblé d'une cellule : un blip occupe une jambe, entrante ou sortante. | `BlipField` (KDoc) |
| `advance` | Un pas de simulation : tous les blips avancent d'une demi-cellule ; les transferts vers les voisins sont appliqués. | `BlipField.advance` |
| `generation` | Un cycle d'émission du serveur : avec TREE, une génération sur `SERVER_EVERY_GENERATIONS` (métronome) ; avec ORIGINAL, à-coups irréguliers entre MIN et MAX (effet « électricité » : le rythme signe le moteur). | `BlipField` (companion) |
| `transfer` | Blips en attente de rejoindre la cellule voisine pendant la génération courante. | `BlipField.transfer` |
| `clear` | Tourner une cellule efface ses blips. | `BlipField.clear`, appelé par la vue |
| `BLIPS_MS` | Période d'avance des blips (300 ms, reprise de `BLIPS_TIME` de l'original). | `BoardView` (companion) |
| `BLIP_RADIUS_FRACTION` | Taille d'un blip en fraction de cellule (0,08). | `BoardView` (companion) |
| `jitter` / `JITTER_SEED` | Dispersion pseudo-aléatoire (déterministe, graine fixe) des émissions ORIGINAL. | `BlipField` (companion) |
| `serverBreath` / `flicker` | Respiration du halo du serveur (période 1600 ms) et scintillement de ses DEL (pas 140 ms), dérivés de la graine exposée sans consommer le flux du générateur. | `BoardView` |
| `TERM_DATA_SWITCH_FRAC` / `TERM_LINE_STEP_DP` | Écrans des terminaux : bascule des lignes de « données » et pas des lignes affichées, comme `Cell.drawTermData` de l'original. | `BoardView` (companion) |
| `fog` / `fogVisibility` | Brouillard du Néant : seules les abords (Chebyshev <= `FOG_RADIUS_CELLS`, distance enroulée par floorMod) des cellules CONNECTÉES sont visibles. Jamais gelé ni sauvegardé ; la victoire révèle tout. | `Board.fogVisibility`, `BoardView.drawFogCell`, `FOG_RADIUS_CELLS` |
| `visualTarget` / `visualCurrent` | Angles de rotation visés et courants, CUMULÉS par cellule (les tours s'enchaînent sans sauter en arrière quand `rotations[i]` repasse par 0). La vue interpole entre les deux. | `BoardView` |
| `animDurationMs` / `ROTATE_MS_PER_QUARTER` | Durée d'animation d'une rotation (250 ms par quart de tour, interpolation linéaire comme `Cell.doUpdate`). | `BoardView` |
| `blink` | Le clignotement d'une cellule : bande contrastée (`blink_band`, résolue jour/nuit) qui balaie la tuile 200 ms, utilisé p. ex. à la victoire. | `BoardView.startBlink`, `BLINK_MS` |

## 7. Aide à la résolution (indice, sceau, plafond)

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `HintStrategy` | Stratégie de l'indice : `SIGNAL` (informatif : cadre vert pulsé sur une tuile mal orientée, défaut) ou `FIX` (correctif : la rotation à l'aplomb est jouée et la cellule est scellée). | `GameViewModel.HintStrategy`, `Settings.loadHintStrategy`, strings `hint_strategy_*` |
| `hintTarget` | La cellule choisie par l'indice : d'abord une verrouillée mal orientée, sinon la mieux placée pour être comprise, jamais une scellée. | `Board.hintTarget` |
| `hintAllowance` | Le PLAFOND d'indices d'une partie : pourcentage de la taille de référence du niveau. | `Board` (companion), `GameSpec.hintsAllowed` |
| `hintReferenceTiles` | La base du plafond (v12) : nombre de tuiles FIXE par niveau (matrice MEDIUM de l'original), indépendant du mélange tiré et de l'écran. | `Skill.hintReferenceTiles` |
| `hintsUsed` / `hintsAllowed` | Indices consommés / plafond de la partie. Sentinelle -1 dans une sauvegarde ancienne : plafond recalculé à la restauration. | `GameSpec`, `Board.hintAllowance` |
| `HINT_PULSE_*` / `HINT_ALPHA_*` | Pulsation de l'indice SIGNAL : cadre vert qui pulse 5 s, période 500 ms, alpha entre 90 et 220. | `BoardView` (companion) |
| `solveQueue` | La file du solveur : ordre des coups de l'auto-solveur (largeur depuis le serveur sur la solution), réordonnable sans changer la solution. | `GameSpec.solveQueue`, `GameViewModel` |
| `solverUsed` | Le joueur a utilisé l'auto-solveur ou l'indice dans la partie. | `GameSpec.solverUsed`, string `solver_used` |
| `prevClicked` | Dernière cellule touchée avant la sauvegarde (reprise de l'état d'affichage). | `GameSpec.prevClicked` |

## 8. Effets de victoire

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `VictoryEffect` (`WAVE`, `FIREWORK`) | L'effet tiré au sort à la victoire : vague qui soulève les tuiles, ou feu d'artifice. | `VictoryEffects.kt` |
| `EFFECT_MS` | Durée de l'effet : 4,5 s ; le dialogue de victoire attend, skip au toucher. | `VictoryEffects` |
| `wave` / `placeWaveOrigin` | La vague : soulève les tuiles en traversant le plateau, ORIGINE tirée au sort HORS de la zone visible, deux vagues sur des côtés différents (`WAVE_COUNT`, `WAVE2_DELAY_MS`). | `VictoryEffects`, `BoardView.playVictoryEffect` |
| `waveProfile` / `WAVE_WIDTH_CELLS` / `WAVE_LIFT_CELLS` | Profil du soulèvement : largeur 3 cellules, amplitude 0,70 cellule, mise à l'échelle `WAVE_SCALE_FRACTION`. Miroir Python dans `validate_effets.py`. | `VictoryEffects` |
| `VictoryFirework` | Le feu d'artifice : fusées (`ROCKETS` = 7) montent depuis le bas de la vue, éclatent en étincelles (`sparks`, budget `FIREWORK_MAX_SPARKS` = 600), traînées et fondu. Vit en repère ÉCRAN (toute la surface), pas en repère plateau. | `VictoryEffects.kt`, `BoardView.drawFireworkOverlay` |
| `drawVictoryCell` / `drawVictoryDecor` | Passe de dessin de la victoire : cellules soulevées, puis le décor autour du plateau (la vague soulève aussi les tuiles grises). | `BoardView` |
| `waveLiftPx` appliqué aux blips | v10 : les billes suivent les tuiles soulevées (même soulèvement que la passe des cellules, plus de billes hors des connecteurs). | `BoardView.drawCellBlips` |

## 9. Démonstration

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `DemoDifficulty` (`RANDOM`, `PROGRESSIVE`) | Difficulté de la démo : un niveau coché tiré au sort à chaque plateau, ou les niveaux cochés en ordre croissant (boucle). | `Demo.kt`, `Settings.loadDemoDifficulty`, strings `demo_diff_*` |
| `DemoSpeed` (`SLOW`, `NORMAL`, `FAST`) | Rythme de la démo : délai entre deux coups du solveur (500 / 250 / 100 ms ; NORMAL reprend le tick du jeu). | `Demo.kt`, `Settings.loadDemoSpeed`, strings `demo_speed_*` |
| `demo skill set` / `loadDemoSkillSet` | Les niveaux COCHÉS de la démo (v8) : plusieurs niveaux possibles, au moins une coche (Novice par défaut), persistés en masque de bits. | `Settings.loadDemoSkillSet`, `Persistence.DEMO_SKILL_SET_KEY`, string `demo_skill_title` |
| `demoBoard` / `applyZoomPreset(1f)` | Le plateau de la démo : cadrage TOUJOURS ajusté (FIT) depuis la v10, plus aucun réglage de zoom ; le préréglage posé avant la première mesure attend le premier layout (`zoomPresetPending`). | `DemoActivity`, `BoardView` |
| `demo_hint_panel` / voile | Le bandeau de la démo (niveau + « touchez pour revenir ») : repose sur un voile rectangulaire à 80 % d'opacité posé sur un containeur interne, dégagé de la barre de navigation. | `activity_demo.xml`, `DemoActivity` |
| `demo_level_banner` | La mention « Démonstration niveau X » en bas de l'écran de la démo. | `DemoActivity`, strings `demo_level_banner` |
| `visible` (file de la démo) | Au Néant, la démo ne joue que les tuiles VISIBLES : la file du solveur tourne jusqu'à la première tuile visible dans le brouillard, comme un joueur. | `Demo.kt`, `DemoViewModel` |

## 10. Sauvegarde et persistance

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `GameSpec` | Photographie complète d'une partie en cours, sérialisable en une chaîne compacte : la partie survit à la mort du processus. Seuls les états modifiés par le joueur, l'avancement et la CAMÉRA voyagent : le plateau se régénère depuis (niveau, graine, générateur, dimensions, racine). | `game/GameSpec.kt` |
| `encode` / `decode` | Sérialisation : champs séparés par « \| », version en tête (`v5`, décode aussi `v4`, `v3`, `v2` à l'identique puis réenregistre en v5). | `GameSpec` |
| `CameraSpec` | Caméra sauvegardée : centre de la vue en MILLI-CELLULES (normalisé modulo les côtés du plateau en famille Infini) et zoom en CENTIÈMES de dp. Toujours des ENTIERS : jamais de flottant textuel (les locales écrivent « 1.5 » ou « 1,5 »). | `game/GameSpec.kt`, `MIN_ZOOM_CENTI_DP`, `MAX_ZOOM_CENTI_DP` |
| `Persistence` | Les clés et fichiers SharedPreferences centralisés (fichier historique « MainActivity », repris de l'original) : un seul endroit pour les noms, chaque lecteur passe par `Settings`. | `Persistence.kt` |
| `GAME_SPEC_KEY` (`"gameSpec"`) | La clé de la chaîne `GameSpec` : la partie en cours. | `Persistence` |
| `moves` / `elapsedMs` | Coups joués / chrono de la partie. | `GameSpec`, strings `status_moves`, `status_time` |

## 11. Réglages

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Settings` | L'objet unique d'accès aux réglages (page « Réglages ») : tout passe par lui, jamais de chaîne magique ailleurs. | `Settings.kt`, `SettingsActivity.kt` |
| `load*` / `save*` | Paires de lecture/écriture de chaque réglage : `loadSoundMode`/`saveSoundMode`, `loadAnimEnabled`/`saveAnimEnabled`, `loadHintStrategy`, `loadHintPercent`, `loadDemoDifficulty`, `loadDemoSkillSet`, `loadDemoSpeed`, etc. | `Settings.kt` |
| `SoundMode` (`NONE`, `QUIET`, `FULL`) | Mode sonore : aucun, discret (volume réduit), complet. Défaut FULL. | `audio/SoundEffects.kt`, strings `sounds_*` |
| `animEnabled` | Réglage « animation » : coupe les effets animés (repli sur pause fixe dans la démo, p. ex.). | `Settings.loadAnimEnabled`, strings `anim_*` |
| `hintPercent` / `coerceHintPercent` | Le POURCENTAGE du plafond d'indices : Désactivé (0), 10 % (défaut) ou 20 %. `coerceHintPercent` ramène toute valeur inconnue à 10 (repli). | `Settings.kt`, `Persistence.HINT_PERCENT_KEY`, strings `hint_limit_*` |
| `activity_settings.xml` | La page de réglages : sections sonores, animation, langue, indice (stratégie + plafond), démonstration. | `res/layout/activity_settings.xml` |

## 12. Sons

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Sound` | Les évènements sonores du jeu, comme l'original : `START` (nouvelle partie), `TURN` (rotation par le joueur), `CLICK` (refus : verrouillée ou résolue), `POP` (verrouillage/déverrouillage), `CONNECT` (la connexité vient de changer), `WIN` (résolu, joué après CONNECT). L'auto-solveur ne joue pas `TURN`. | `game/Sound.kt`, `audio/SoundEffects.kt` |

## 13. Scores

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `ScoresStore` | Le magasin des records par niveau : meilleur score en coups, meilleur temps, date. | `ScoresStore.kt`, `ScoresActivity.kt` |
| `Result` (`NONE`, `BEST_MOVES`, `BEST_TIME`, `BEST_BOTH`) | Verdict d'une partie enregistrée : rien, record de coups, record de temps, ou les deux. | `ScoresStore.Result`, strings `best_*` |
| `hintTiles` | Total de tuiles « offertes » par les indices du niveau (statistique d'aide cumulée). | `ScoresStore.hintTiles` |

## 14. Chaînes de caractères (strings)

Deux fichiers miroirs, l'anglais par défaut et le français. La langue suit le
système ou se force (le corse est à la feuille de route).

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `menu_*` | Libellés des menus : `menu_new`, `menu_solve`, `menu_settings`, `menu_stop_solve`, `menu_hint`, `menu_demo`, `menu_scores`, `menu_help`, `menu_about`, etc. | `res/values/strings.xml`, `res/values-fr/strings.xml` |
| `skill_*` | Noms et descriptions des niveaux : `skill_novice`… `skill_void` (+ `_desc`). | strings EN/FR |
| `status_moves` / `status_time` | Compteurs de partie : coups et temps. | strings EN/FR |
| `win_*` / `best_*` | Écran de victoire et records : titre, textes, « record de coups / de temps / des deux ». | strings EN/FR |
| `title_*` | Écran titre : version, licence, chargement, mise à jour disponible, installation. | strings EN/FR |
| `scores_*` | Écran des scores : titres de colonnes, réinitialisation et confirmation. | strings EN/FR |
| `hint_strategy_signal` / `hint_strategy_fix` | Libellés des deux stratégies d'indice (Informatif / Correctif). | strings EN/FR |
| `hint_limit_title` / `hint_limit_zero` / `hint_limit_value` | Le réglage du plafond d'indices : titre, « Désactivé », « %d %% » (10 %, 20 %). | strings EN/FR, layout |
| `hints_section` / `hints_total` / `hints_none` / `hints_n` / `hints_column_tiles` | Compteur d'indices dans la barre d'outils et la boîte d'aide. | strings EN/FR |
| `demo_*` | Démonstration : difficulté (`demo_diff_*`), niveaux cochés (`demo_skill_title`), rythme (`demo_speed_*`), bandeau (`demo_level_banner`), « touchez pour revenir » (`demo_touch_return`). | strings EN/FR |
| `sounds_*` / `anim_*` | Réglages sonores et animation : `sounds_off/quiet/full`, `anim_off/on`. | strings EN/FR |
| `lang_*` | Choix de langue : `lang_system` (suit le système), `lang_fr`, `lang_en`. | strings EN/FR |
| `help_titles` / `help_texts` / `about_titles` / `about_texts` | Tableaux des pages d'aide et d'à-propos (arrays). | `res/values/arrays.xml`, `res/values-fr/arrays.xml` |

---

## 15. Péremption

Date limite d'utilisation des versions de développement (Lot 17) : une
pré-release (versionName de majeure 0) s'autolimite à 5 jours après sa
date de build ; une release officielle (1.x.y et au-delà) n'expire jamais.

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Peremption` | Logique de péremption, pur Kotlin testable JVM : grâce, date limite, règle du numéro, témoin. | `Peremption.kt` |
| `GRACE_JOURS` | Grâce en jours après la date de build (décision d'eric : 5). | `Peremption.kt` |
| `estPreRelease` | Majeure 0 (« 0.x.y ») = pré-release limitée. | `Peremption.kt` |
| `dateLimite` / `dateLimiteDe` | Date limite (build + grâce) ; null pour une release officielle. | `Peremption.kt` |
| `estPerimee` | Périmée si le jour observé (max lexical du jour UTC et du témoin) dépasse strictement la limite : le jour de grâce reste jouable. | `Peremption.kt` |
| `jourIso` | Formatage ISO « yyyy-MM-dd » d'un calendrier. | `Peremption.kt` |
| `FICHIER` / `CLE_TEMOIN` | Témoin de date persisté (fichier dédié « peremption », jamais la sauvegarde de partie). | `Peremption.kt`, `TitleActivity.kt` |
| `DATE_BUILD` | Date de build UTC (ISO yyyy-MM-dd) estampillée par Gradle. | `app/build.gradle.kts`, `BuildConfig` |
| `version_obsolete*` | Chaînes du blocage : titre, phrase d'accompagnement, bouton Quitter (4 langues). | `res/values*/strings.xml` |

---

## 16. Apprentissage

Mode d'initiation (chantier du 17 nov. 2026) : sept leçons interactives
sous le popup Outils uniquement, rejouables à volonté, aucune incidence
sur la partie en cours ni sur les records. Une leçon = un plateau
reproductible + des bulles de guidance + UNE action attendue à la fois.

| Terme | Définition | Emplacements |
| --- | --- | --- |
| `Lesson` / `LessonStep` | Une leçon (id stable, titre, plateau, scénario) et une de ses étapes : bulle affichée + action attendue. | `Lessons.kt` |
| `LessonBoard` | Plateau de leçon reproductible : niveau, dimensions explicites, générateur, graine ; `preSolved` (livré résolu) et `cameraOnly` (aucune tuile jouable). | `Lessons.kt` |
| `build()` | Construit le plateau de leçon ; sur `preSolved`, remise à l'aplomb de chaque tuile par ses propres quarts de tour. | `Lessons.kt` |
| `ExpectKind` | Ce qu'une étape attend : CONTINUE, ROTATE, LOCK, UNLOCK, HINT_SIGNAL, HINT_FIX, SOLVE, VICTORY, PAN_X, PAN_Y, ZOOM. | `Lessons.kt` |
| `LessonRunner` | Machine d'étapes pure : consomme les événements, cumule les déplacements de caméra jusqu'au tour complet, remet ses accumulateurs à zéro à chaque étape. | `Lessons.kt` |
| `LessonEvent` | Événement envoyé au runner par le modèle : rotation, verrouillage, indices, solveur, victoire, défilement (en cellules), pincement (en facteur). | `Lessons.kt`, `LessonViewModel.kt` |
| `bulle` | Texte de guidance affiché dans le bandeau bas de l'écran de leçon ; identifié par son id de chaîne (`lesson_lX_bY`), rendu par le `when` de LessonActivity. | `Lessons.kt`, `LessonActivity.kt`, `res/values*/strings.xml` |
| `coche` | Marque « Terminée » d'une leçon finie, persistée dans le masque `lessonsDone` ; une leçon cochée reste rejouable. | `Persistence.kt`, `Settings.kt`, `LessonsListActivity.kt` |
| `cameraOnly` | Leçon où seule la caméra parle (Infini) : le modèle ignore taps et verrouillage, la vue traduit ses gestes en événements. | `Lessons.kt`, `LessonViewModel.kt` |
| `onCameraScrolled` / `onCameraZoomed` | Listeners d'observation de la caméra de BoardView (deltas en cellules, pincement en facteur), branchés par l'écran de leçon. | `ui/BoardView.kt`, `LessonActivity.kt` |
| `ZOOM_IN_DONE` / `ZOOM_OUT_DONE` | Seuils de pincement validant l'étape ZOOM : 1,3 avant cumulé, ou 0,8 arrière si le plancher le permet. | `Lessons.kt` |

---

## À compléter

Ce glossaire est vivant. Lors d'un chantier ultérieur, ajouter une ligne au
tableau de la section concernée (ou une section nouvelle) : **Terme**, sa
**définition** française, et ses **emplacements** exacts. Garder les
identifiants du code tels quels dans la colonne Terme : c'est lui qu'un
développeur greppe.
