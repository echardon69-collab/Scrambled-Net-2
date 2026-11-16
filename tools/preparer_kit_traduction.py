#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Préparation du kit de traduction (chantier « processus de
traduction », feu vert d'eric le 16 nov. 2026 — processus B :
GitHub PR + guide docs/TRADUIRE.md + relecture IA).

Génère depuis les sources tout ce qu'un traducteur doit recevoir,
sans qu'il ait besoin de comprendre le dépôt :

  - strings.csv / arrays.csv : le tableau à remplir — une ligne par
    texte, avec sa clé, le texte anglais (tel quel, échappements
    compris : \', \n, %1$d — le traducteur recopie ces séquences),
    le contexte (le commentaire du fichier source, qui dit où vit
    le texte et ses contraintes) et une colonne vide « Traduction » ;
  - REGLES.md : les règles de format maison (apostrophes échappées,
    paramètres recopiés, sauts de ligne, puces, clés intouchées) ;
  - GLOSSAIRE.md : le vocabulaire du jeu, anglais et français ;
  - LISEZMOI.md : le mode d'emploi du kit et la licence GPL-2.0.

COMPLÉTION PAR DELTA : si la langue visée possède déjà
values-<code>/strings.xml, le kit ne contient que ce qui manque —
les clés absentes du fichier existant, et les tableaux (arrays)
entiers si leur nombre d'items ne colle plus à la base (le jeu a
évolué : tout l'array est à revoir). Un kit complet (langue neuve)
et un kit de complétion se génèrent par la MÊME commande.

Usage :
  python3 tools/preparer_kit_traduction.py <code_langue>

Exemples :
  python3 tools/preparer_kit_traduction.py co     # kit corse complet
  python3 tools/preparer_kit_traduction.py fr     # complétion du miroir FR

Sortie : kit-traduction-<code>/ à la racine du dépôt. Le kit est un
livrable, jamais une source : rien n'est écrit dans app/src.
"""

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BASE = RES / "values"

# Code de langue Android : deux ou trois lettres minuscules,
# éventuellement une variante (-haw en serait une autre).
LANG_RE = re.compile(r"^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$")

# Élément <string name="cle">texte</string>, possiblement sur
# plusieurs lignes (l'aide et la licence en sont).
STRING_RE = re.compile(
    r"<string\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", re.S)
# Élément <string-array name="nom"> avec ses items.
ARRAY_RE = re.compile(
    r"<string-array\s+name=\"([^\"]+)\">(.*?)</string-array>", re.S)
ITEM_RE = re.compile(r"<item>(.*?)</item>", re.S)
COMMENT_RE = re.compile(r"<!--(.*?)-->", re.S)


def normalize(text):
    """Blancs du XML source ramenés à des espaces simples : les
    vrais sauts de ligne du texte sont les séquences \\n, que le
    traducteur recopie ; le pliage des lignes du fichier n'est
    que de la présentation (Android le fait de toute façon)."""
    return re.sub(r"\s+", " ", text).strip()


def parse_with_context(path, pattern):
    """Parcourt un fichier de ressources dans l'ordre, en portant à
    chaque élément le dernier bloc de commentaire lu avant lui —
    c'est le contexte remis au traducteur. Rend une liste de tuples
    (clé/nom, texte brut échappé, contexte)."""
    source = path.read_text(encoding="utf-8")
    items = []
    context = ""
    # On balaie commentaires et éléments dans l'ordre du fichier :
    # le commentaire précède l'élément qu'il documente.
    tokens = []
    for m in COMMENT_RE.finditer(source):
        tokens.append((m.start(), "comment", m.group(1)))
    for m in pattern.finditer(source):
        tokens.append((m.start(), "element", m))
    tokens.sort(key=lambda t: t[0])
    for _, kind, payload in tokens:
        if kind == "comment":
            context = normalize(payload)
        else:
            m = payload
            items.append((m.group(1), m.group(2), context))
    return items


def load_strings(path):
    """Clés de strings déjà traduites dans une langue (delta)."""
    if not path.exists():
        return set()
    source = path.read_text(encoding="utf-8")
    return {m.group(1) for m in STRING_RE.finditer(source)}


def load_arrays(path):
    """Arrays déjà traduits : nom -> nombre d'items (delta)."""
    if not path.exists():
        return {}
    source = path.read_text(encoding="utf-8")
    return {
        m.group(1): len(ITEM_RE.findall(m.group(2)))
        for m in ARRAY_RE.finditer(source)
    }


def write_csv(path, header, rows):
    """UTF-8 avec BOM : Excel ouvre les accents sans question."""
    with open(path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)


REGLES = r"""# Règles de format — à respecter dans la colonne « Traduction »

Le texte est embarqué dans un fichier XML Android : ces règles
existent pour que le build ne casse pas. Le validateur du dépôt
(validate_langues.py) les vérifie une à une avant intégration.

1. **Apostrophes** : l'apostrophe droite `'` s'échappe en `\'`
   (ex. : `l\'eau`). L'apostrophe typographique `’` ne demande rien,
   mais restez cohérent dans tout le fichier. Au doute : `\'`.
2. **Paramètres** : `%1$d`, `%2$d`, `%1$s`, `%1$d%%` sont remplacés
   au runtime (nombre, temps, version, pourcentage). Recopiez-les à
   l'identique, dans le même ordre logique.
3. **Sauts de ligne** : `\n\n` = nouveau paragraphe, `\n` = à la
   ligne. Recopiez-les à l'endroit logique de votre texte.
4. **Guillemets** : ceux de votre langue dans le texte traduit.
5. **Puces** : conservez `•` suivi d'un espace en début de puce.
6. **Clés, URL, noms propres** : intouchés — recopiés tels quels.
7. **Longueur** : visez l'ordre de grandeur du français, plus bref
   que l'anglais reste le meilleur dénominateur commun ; les
   libellés de boutons tiennent en un à trois mots.

Guide complet : docs/TRADUIRE.md à la racine du dépôt.
"""

GLOSSAIRE = r"""# Glossaire du jeu — employez ces termes partout

| Anglais | Français de référence | Le concept |
| --- | --- | --- |
| board | plateau | la grille de jeu tout entière |
| tile, cell | tuile | une case du plateau |
| server | serveur | la racine du réseau, d'où partent les câbles |
| terminal | terminal | bout de câble à une seule connexion, le but est de tous les relier |
| torus (edges wrap around) | tore | les bords du plateau bouclent l'un sur l'autre |
| hidden wiring | câbles invisibles | branchements masqués (Dément, Abîme, Néant) |
| fog | brouillard | zone visible autour du réseau relié (Néant) |
| blips | blips | billes de données dans les câbles — mot gardé tel quel |
| hint, hint allowance | indice, plafond d'indices | l'aide à la résolution et son quota |
| Highlight the error / Fix the error | Signaler l'erreur / Corriger l'erreur | les deux stratégies d'indice |
| Novice / Normal / Expert | Novice / Normal / Expert | les trois premiers niveaux |
| Master | Maître | tore, trois branches |
| Insane | Dément | Maître + câbles invisibles |
| Endless | Infini | plateau géant fixe 40 x 40, sans bords visibles |
| Abyss | Abîme | Infini + câbles invisibles |
| Void | Néant | Abîme + brouillard |
| Moves / Time | Coups / Temps | les compteurs de partie et records |
| Skill level | Niveau de difficulté | le sélecteur de niveau |
| High scores, personal best | Records, records personnels | la page des records |
| Solver, auto-solver | Solveur, auto-solveur | l'outil Résoudre (les parties où il sert ne sont pas comptées) |
"""

LISEZMOI = r"""# Kit de traduction Scrambled Net 2

Ce kit contient tout ce qu'il y a à traduire pour une langue :

- `strings.csv` — l'interface (menus, victoire, records, réglages,
  démo, indices…). Remplissez la colonne « Traduction ».
- `arrays.csv` — l'aide du jeu et la page « À propos ».
- `REGLES.md` — les règles de format, à lire avant de commencer.
- `GLOSSAIRE.md` — le vocabulaire, pour des choix cohérents.

Comment procéder :

1. Ouvrez les CSV dans un tableur (LibreOffice, Excel, Google
   Sheets), remplissez la colonne « Traduction », ne touchez à rien
   d'autre. Traduction partielle acceptée : ce qui n'est pas
   traduit reste en anglais dans le jeu.
2. Renvoyez les CSV remplis. La relecture (IA, avec back-traduction
   vers l'anglais pour détecter les contresens) vous revient, vous
   tranchez.
3. L'intégration et la revue se font sur GitHub (pull request), la
   traduction est publiée avec les mises à jour du jeu.

Licence : Scrambled Net 2 est un logiciel libre GPL-2.0 ; la
traduction intégrée au dépôt est GPL-2.0 comme toute contribution —
le standard des projets libres, à connaître avant de commencer.
"""


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    code = sys.argv[1]
    if not LANG_RE.match(code):
        sys.exit(f"Code de langue invalide : {code!r} "
                 "(attendu : 'co', 'fr', 'pt-br'…)")

    strings_xml = BASE / "strings.xml"
    arrays_xml = BASE / "arrays.xml"
    if not strings_xml.exists() or not arrays_xml.exists():
        sys.exit("Sources introuvables : values/strings.xml ou "
                 "values/arrays.xml manquant.")

    base_strings = parse_with_context(strings_xml, STRING_RE)
    base_arrays = parse_with_context(arrays_xml, ARRAY_RE)
    # Items de chaque array, dans l'ordre du fichier, avec le
    # contexte porté par l'array.
    array_items = []
    for name, body, context in base_arrays:
        for i, m in enumerate(ITEM_RE.finditer(body), start=1):
            array_items.append((name, i, m.group(1), context))

    target = RES / f"values-{code}"
    done_keys = load_strings(target / "strings.xml")
    done_arrays = load_arrays(target / "arrays.xml")

    # Compte d'items de chaque array dans la base, pour le delta.
    base_array_counts = {
        name: len(ITEM_RE.findall(body))
        for name, body, _ in base_arrays
    }

    # Delta : clés absentes seulement ; array ENTIER s'il manque ou
    # si le compte d'items ne colle plus (le jeu a évolué : tout
    # l'array est à revoir, on ne peut pas apparier des items
    # anonymes un à un).
    kit_strings = [
        (key, text, context, "")
        for key, text, context in base_strings
        if key not in done_keys
    ]
    kit_arrays = []
    for name, i, text, context in array_items:
        if done_arrays.get(name) == base_array_counts.get(name):
            continue  # array déjà complet dans la langue
        ctx = context
        if name in done_arrays:
            ctx = (ctx + " — array à revoir : le jeu a évolué, "
                   "nombre d'items changé").strip()
        kit_arrays.append((name, i, text, ctx, ""))

    out = ROOT / f"kit-traduction-{code}"
    out.mkdir(exist_ok=True)
    write_csv(out / "strings.csv",
              ["Clé", "Texte anglais (ne pas modifier)", "Contexte",
               "Traduction"], kit_strings)
    write_csv(out / "arrays.csv",
              ["Tableau", "Item", "Texte anglais (ne pas modifier)",
               "Contexte", "Traduction"], kit_arrays)
    (out / "REGLES.md").write_text(REGLES, encoding="utf-8")
    (out / "GLOSSAIRE.md").write_text(GLOSSAIRE, encoding="utf-8")
    (out / "LISEZMOI.md").write_text(LISEZMOI, encoding="utf-8")

    mode = "complétion (delta)" if done_keys or done_arrays \
        else "langue nouvelle (kit complet)"
    n_arrays_done = sum(
        1 for name, count in base_array_counts.items()
        if done_arrays.get(name) == count)
    print(f"Kit « {code} » généré dans {out.name}/ — {mode}")
    print(f"  strings : {len(kit_strings)} à traduire "
          f"(sur {len(base_strings)}, déjà traduits : {len(done_keys)})")
    total_items = len(array_items)
    print(f"  arrays  : {len(kit_arrays)} items à traduire "
          f"(sur {total_items}, tableaux déjà complets : {n_arrays_done})")
    print("Rappel : python3 tools/validate_langues.py doit être vert "
          "avant intégration.")


if __name__ == "__main__":
    main()
