# Traduire Scrambled Net 2

Ce guide s'adresse à toute personne qui veut traduire le jeu dans une
nouvelle langue (le corse est la langue cible actuelle). Il décrit ce
qu'il y a à traduire, les règles de format à respecter, le kit qui
vous est préparé, et le processus complet jusqu'à la mise à jour
distribuée aux joueurs. Aucune connaissance technique n'est exigée
en dehors de l'édition d'un tableur.

## Ce qu'il y a à traduire

Deux familles de textes, toutes deux en anglais à la base :

- **les chaînes de l'interface** (`app/src/main/res/values/strings.xml`)
  : libellés de menus, dialogues, page de victoire, page des records,
  page titre, réglages, démonstration, indices — une centaine de
  textes, la plupart très courts ;
- **les tableaux de textes longs**
  (`app/src/main/res/values/arrays.xml`) : les cinq sections de l'aide
  (titre + texte chacune) et les huit sections de la page « À propos »
  (titre + texte chacune) — vingt-six textes, dont certains de
  plusieurs paragraphes.

Le français (`values-fr/`) est la traduction de référence : complète
et maintenue à jour. Une traduction partielle est permise pour toute
nouvelle langue : Android fait automatiquement retomber chaque texte
absent sur l'anglais, vous pouvez donc livrer le noyau de l'interface
d'abord (menus, victoire, records) et compléter l'aide et l'À propos
ensuite.

Les **noms propres ne se traduisent pas** : Scrambled Net 2, KNetWalk,
Scrambled Net, KDE, Android, GitHub, GPL-2.0, tout comme les noms
d'affichage des langues (Français, English) dans la page Langue.

## Le kit de traduction

Un script du dépôt génère tout ce dont vous avez besoin :

```
python3 tools/preparer_kit_traduction.py co
```

(remplacez `co` par le code de votre langue). Le kit apparaît dans
`kit-traduction-co/` à la racine du dépôt :

- `strings.csv` et `arrays.csv` : le tableau à remplir. Chaque ligne
  porte la clé du texte, le texte anglais à traduire, le contexte
  (à quoi sert le texte, où il apparaît, ses contraintes de longueur)
  et une dernière colonne vide, « votre traduction ». Ouvrez-le dans
  n'importe quel tableur (LibreOffice, Excel, Google Sheets) ;
- `REGLES.md` : les règles de format ci-dessous, à garder sous la main ;
- `GLOSSAIRE.md` : le vocabulaire du jeu, anglais et français, pour
  que tous les traducteurs d'une même langue emploient les mêmes mots ;
- `LISEZMOI.md` : la licence et le mode d'emploi du kit.

Si une traduction de votre langue existe déjà dans le dépôt, le script
ne met dans le kit que les textes qui manquent encore : c'est la
**complétion par delta**, le kit se régénère à chaque évolution du jeu
et ne contient jamais ce qui est déjà traduit.

## Les règles de format

Elles existent parce que le texte est embarqué dans un fichier XML
Android : un format qui casse le build si on les ignore. Les voici,
dans l'ordre d'importance.

1. **Apostrophes** : l'apostrophe droite `'` doit être échappée en
   `\'` (ex. : `l\'eau`). L'apostrophe typographique `’` n'a pas
   besoin d'échappement — mais restez cohérente ou cohérent dans
   tout le fichier. En cas de doute, mettez `\'` partout.
2. **Paramètres** : les séquences `%1$d`, `%2$d`, `%1$s`, `%1$d%%`
   (le pourcentage) sont remplacées au runtime par un nombre, un
   temps, une version… Recopiez-les **à l'identique**, dans le même
   ordre d'apparition logique. Le validateur ci-dessous les vérifie
   une à une.
3. **Sauts de ligne** : `\n\n` saute une ligne (nouveau paragraphe
   dans l'aide), `\n` passe à la ligne. Recopiez-les au même endroit
   logique du texte traduit.
4. **Guillemets** : dans le CSV, utilisez les guillemets de votre
   langue (« » en français) ; l'intégration dans le XML les
   préservera. Le guillemet droit `"` dans un texte XML serait
   échappé par l'intégrateur.
5. **Puces de listes** : l'aide et l'À propos utilisent `•` en début
   de puce, suivi d'un espace — conservez cette présentation.
6. **Clés et URL intouchées** : la colonne « clé » ne se modifie
   jamais ; les URL et adresses se recopient telles quelles.
7. **Longueur** : l'interface est en français plus long que
   l'anglais et tient ; visez le même ordre de grandeur. Les
   libellés de boutons doivent rester courts (un à trois mots).

Le **validateur** du dépôt contrôle tout cela automatiquement :

```
python3 tools/validate_langues.py
```

Il doit afficher « TOUT VERT » avant toute intégration : parité des
clés, paramètres présents, apostrophes échappées, XML bien formé. Il
accepte les traductions partielles (avertissement, pas d'erreur) mais
refuse tout ce qui casserait le build.

## Le glossaire du jeu

Le vocabulaire à employer de façon cohérente (le kit en détaille les
équivalents français) :

- le **plateau** (*board*) : la grille de jeu tout entière ;
- la **tuile** (*tile*, le code dit *cell*) : une case du plateau ;
- le **serveur** (*server*) : la racine du réseau, d'où partent les
  câbles ;
- le **terminal** : un bout de câble à une seule connexion, le but du
  jeu est de tous les relier au serveur ;
- le **tore** (*torus*) : les bords du plateau se rejoignent, les
  câbles bouclent d'un bord à l'autre (Maître et au-delà) ;
- les **câbles invisibles** (*hidden wiring*) : les branchements
  masqués des niveaux Dément, Abîme, Néant ;
- le **brouillard** (*fog*) : la zone visible autour du réseau relié
  au serveur, marque du niveau Néant ;
- les **blips** : les billes de données qui parcourent les câbles,
  le mot est gardé tel quel dans toutes les langues ;
- les **indices** (*hints*) : l'aide à la résolution, son plafond
  (*allowance*) et ses deux stratégies, Signaler (surligner l'erreur)
  et Corriger (la réparer) ;
- les **niveaux** : Novice, Normal, Expert, Maître (*Master*),
  Dément (*Insane*), Infini (*Endless*), Abîme (*Abyss*),
  Néant (*Void*) — traduisez leur sens, le français les a tous
  adaptés.

## Le processus complet

1. **Kit** : eric génère le kit de votre langue (ou vous-même si vous
   avez un clone du dépôt) et vous l'envoie.
2. **Traduction** : vous remplissez la colonne « votre traduction »
   du CSV, à votre rythme. Traduction partielle acceptée.
3. **Relecture IA** : la traduction est relue par une IA avec le
   glossaire et ces règles, y compris une **back-traduction**
   (retraduire vers l'anglais et comparer : c'est ce qui détecte les
   contresens). Vous recevez les remarques, vous tranchez.
4. **Intégration** : eric intègre le CSV dans
   `values-<code>/strings.xml` et `values-<code>/arrays.xml`, ajoute
   la langue à la configuration, et le validateur passe au vert.
5. **Revue GitHub** : la traduction arrive dans le dépôt par une
   *pull request* GitHub, ouverte à vos commentaires. La traduction
   **intégrée au dépôt est couverte par la licence GPL-2.0** du jeu,
   comme toute contribution — c'est le standard des projets libres,
   à connaître avant de commencer.
6. **Distribution** : la CI construit, la release GitHub paraît, et
   l'Updater intégré au jeu propose la mise à jour aux joueurs
   (l'apparaition de la langue dans le sélecteur de Réglages est un
   chantier séparé, déjà inscrit à la feuille de route).

## Compléter après une évolution du jeu

Chaque évolution du jeu peut ajouter des textes. Le kit se régénère
de la même façon et ne contient, par construction, que les nouveaux :
retraduisez simplement les lignes du nouveau kit, l'IA relit le delta,
et le tour est joué.
