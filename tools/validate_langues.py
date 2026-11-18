#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation des langues du jeu (chantier « processus de traduction »,
feu vert d'eric le 16 nov. 2026 — processus B : GitHub PR + guide
docs/TRADUIRE.md + kit par script).

Contrôle tout ce qui casserait le build ou fausserait l'interface,
pour la base anglaise ET chaque miroir values-xx présent :

  1. XML bien formé et encodage UTF-8 valides ;
  2. clés : une clé inconnue (absente de la base) est une ERREUR —
     le jeu ne l'affichera jamais ; une clé manquante est un
     AVERTISSEMENT pour une langue neuve (Android retombe sur
     l'anglais, traduction partielle permise) mais une ERREUR pour
     le miroir français, référence historique tenue complète ;
  3. tableaux (arrays) : mêmes noms, même nombre d'items — un écart
     décale titres et textes de l'aide et de l'À propos ;
  4. paramètres (%1$d, %2$d, %1$s, %1$d%%) : présents à l'identique
     dans chaque traduction — sinon RuntimeFault au premier écran ;
  5. apostrophes droites échappées (\' et jamais ' nue) : le build
     Android les refuse ;
  6. traduction identique à l'anglais : AVERTISSEMENT (oubli
     probable), sauf les noms propres légitimement égaux.

Les AVERTISSEMENTS n'échouent pas : une langue partiellement
traduite est volontairement admise ; les ERREURS échouent.

Usage : python3 tools/validate_langues.py
"""

import re
import sys
from xml.etree.ElementTree import ParseError, fromstring
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BASE = RES / "values"

# Comptes de la base anglaise au jour du chantier : la parité est
# PINNÉE, un ajout de chaîne au jeu doit la mettre à jour ici en
# conscience (et régénérer les kits, la complétion par delta suit).
# v19 (sélecteur de langue dynamique, 16 nov. 2026) : les
# endonymes lang_fr/lang_en quittent les traductions pour
# AppLanguages.kt — la base passe de 98 à 96 chaînes, miroirs
# compris.
# Lot 17 (péremption, 17 nov. 2026) : version_obsolete,
# version_obsolete_detail et version_obsolete_quitter — la
# base passe de 96 à 99 chaînes, miroirs compris.
# Chantier Apprentissage (17 nov. 2026) : menu_lessons, les
# titres des sept leçons, 23 bulles de guidance, la coche
# « Terminée » et « Continuer » — la base passe de 99 à
# 133 chaînes, miroir FR complet, complétion par delta pour
# les autres langues (kits à régénérer).
PINNED_STRINGS = 133
PINNED_ARRAYS = 4
PINNED_ITEMS = 26

# Noms propres et libellés volontairement identiques dans toute
# langue : l'application et les trois premiers niveaux (Novice,
# Normal, Expert portent le même nom en français). Les endonymes
# des langues (Français, English, Corsu, Elsässisch) ne sont plus
# des strings : depuis le sélecteur dynamique (v19) ils vivent
# dans AppLanguages.kt, identiques en toute langue par nature.
IDENTICAL_OK = {"app_name",
                "skill_novice", "skill_normal", "skill_expert"}

# Paramètres de format Android : %1$d, %2$s… et %% littéral.
PLACEHOLDER_RE = re.compile(r"%\d+\$[sd]|%%")
# Apostrophe droite non échappée (le build la refuse).
BARE_APOSTROPHE_RE = re.compile(r"(?<!\\)'")

errors = []
warnings = []
checks = 0


def check(condition, message, error=True):
    """Compte une vérification ; échec en ERREUR (défaut) ou en
    AVERTISSEMENT. Rend le verdict pour branchement éventuel."""
    global checks
    checks += 1
    if not condition:
        (errors if error else warnings).append(message)
    return bool(condition)


def read_xml(path):
    """Lit un fichier de ressources : rend les strings en dict
    clé -> texte, les arrays en dict nom -> liste d'items. Les
    fichiers sont parsés (jamais de retapage à la main ici)."""
    raw = path.read_bytes()
    text = raw.decode("utf-8")  # UnicodeDecodeError si encodage cassé
    root = fromstring(text)  # ParseError si XML cassé
    strings = {}
    for node in root.findall("string"):
        strings[node.get("name")] = "".join(node.itertext())
    arrays = {}
    for node in root.findall("string-array"):
        arrays[node.get("name")] = [
            "".join(item.itertext()) for item in node.findall("item")]
    return strings, arrays


def placeholders(text):
    return sorted(PLACEHOLDER_RE.findall(text))


def validate_file_pair(name, base_strings, base_arrays,
                        loc_strings, loc_arrays, reference):
    """Contrôle un miroir values-xx contre la base. `reference` :
    le miroir français, tenu complet, ne tolère aucun manquement."""
    tag = name

    # --- Clés des strings. ---
    unknown = sorted(set(loc_strings) - set(base_strings))
    check(not unknown,
          f"{tag} : clés inconnues de la base : {unknown}")
    missing = sorted(set(base_strings) - set(loc_strings))
    check(not missing,
          f"{tag} : clés manquantes (retombée anglaise admise) : "
          f"{missing}", error=reference)
    if missing and not reference:
        warnings.append(
            f"{tag} : traduction partielle, {len(missing)} texte(s) "
            f"restent en anglais (permis)")

    # --- Tableaux : mêmes noms, mêmes comptes. ---
    unknown_arr = sorted(set(loc_arrays) - set(base_arrays))
    check(not unknown_arr,
          f"{tag} : tableaux inconnus de la base : {unknown_arr}")
    missing_arr = sorted(set(base_arrays) - set(loc_arrays))
    check(not missing_arr,
          f"{tag} : tableaux manquants : {missing_arr}", error=reference)
    for arr, items in base_arrays.items():
        if arr in loc_arrays:
            check(len(loc_arrays[arr]) == len(items),
                  f"{tag} : tableau « {arr} » : "
                  f"{len(loc_arrays[arr])} items au lieu de {len(items)}")

    # --- Paramètres, texte par texte (strings puis items). ---
    for key, text in sorted(loc_strings.items()):
        if key in base_strings:
            check(placeholders(text) == placeholders(base_strings[key]),
                  f"{tag} : « {key} » : paramètres différents "
                  f"({placeholders(base_strings[key])} attendus)")
        check(not BARE_APOSTROPHE_RE.search(loc_strings[key]),
              f"{tag} : « {key} » : apostrophe droite non échappée "
              f"(\\' exigé)")
        if key in base_strings and key not in IDENTICAL_OK \
                and loc_strings[key] == base_strings[key]:
            warnings.append(
                f"{tag} : « {key} » identique à l'anglais "
                f"(oubli probable ?)")
    for arr, items in sorted(loc_arrays.items()):
        if arr not in base_arrays:
            continue
        base_items = base_arrays[arr]
        for i, (item, base_item) in enumerate(
                zip(items, base_items), start=1):
            check(placeholders(item) == placeholders(base_item),
                  f"{tag} : « {arr} » item {i} : paramètres différents")
            check(not BARE_APOSTROPHE_RE.search(item),
                  f"{tag} : « {arr} » item {i} : apostrophe droite "
                  f"non échappée (\\' exigé)")
            if item == base_item:
                warnings.append(
                    f"{tag} : « {arr} » item {i} identique à "
                    f"l'anglais (oubli probable ?)")


def main():
    # --- Base anglaise : parse, comptes pinés. ---
    try:
        base_strings, _ = read_xml(BASE / "strings.xml")
    except (UnicodeDecodeError, ParseError) as e:
        raise SystemExit(f"Base anglaise illisible : {e}")
    check(len(base_strings) == PINNED_STRINGS,
          f"Base : {len(base_strings)} strings au lieu de "
          f"{PINNED_STRINGS} — PIN du chantier : si le jeu a "
          f"volontairement évolué, mettre à jour les pins et "
          f"régénérer les kits")
    try:
        _, base_arrays = read_xml(BASE / "arrays.xml")
    except (UnicodeDecodeError, ParseError) as e:
        raise SystemExit(f"Base arrays.xml illisible : {e}")
    n_items = sum(len(v) for v in base_arrays.values())
    check(len(base_arrays) == PINNED_ARRAYS,
          f"Base : {len(base_arrays)} tableaux au lieu de "
          f"{PINNED_ARRAYS}")
    check(n_items == PINNED_ITEMS,
          f"Base : {n_items} items de tableaux au lieu de "
          f"{PINNED_ITEMS}")
    # La base aussi doit échapper ses apostrophes.
    for key, text in sorted(base_strings.items()):
        check(not BARE_APOSTROPHE_RE.search(text),
              f"Base : « {key} » : apostrophe droite non échappée "
              f"(\\' exigé)")
    print(f"Base anglaise : {len(base_strings)} strings, "
          f"{len(base_arrays)} tableaux, {n_items} items.")

    # --- Chaque miroir values-xx (strings.xml ou arrays.xml).
    #     Les dossiers de configuration (night, sw600dp, v27) ne
    #     sont pas des langues ; un code de langue ne commence
    #     jamais par un chiffre. ---
    locales = sorted(
        d.name for d in RES.glob("values-*")
        if d.is_dir()
        and not re.match(r"values-(night|sw\d|v\d)", d.name)
        and ((d / "strings.xml").exists() or (d / "arrays.xml").exists()))
    if not locales:
        sys.exit("Aucun miroir de langue trouvé (values-fr attendu).")
    for name in locales:
        reference = name == "values-fr"
        try:
            loc_strings, _ = read_xml(RES / name / "strings.xml")
        except (UnicodeDecodeError, ParseError) as e:
            errors.append(f"{name} : strings.xml illisible : {e}")
            continue
        try:
            _, loc_arrays = read_xml(RES / name / "arrays.xml")
        except FileNotFoundError:
            loc_arrays = {}
            warnings.append(f"{name} : pas d'arrays.xml (aide et "
                             f"À propos resteront en anglais)")
        except (UnicodeDecodeError, ParseError) as e:
            errors.append(f"{name} : arrays.xml illisible : {e}")
            continue
        if reference:
            check(len(loc_strings) == PINNED_STRINGS,
                  f"{name} : miroir de référence incomplet : "
                  f"{len(loc_strings)} strings au lieu de "
                  f"{PINNED_STRINGS}")
            n_loc = sum(len(v) for v in loc_arrays.values())
            check(n_loc == PINNED_ITEMS,
                  f"{name} : miroir de référence incomplet : "
                  f"{n_loc} items au lieu de {PINNED_ITEMS}")
        validate_file_pair(name, base_strings, base_arrays,
                           loc_strings, loc_arrays, reference)
        print(f"{name} : {len(loc_strings)} strings, "
              f"{len(loc_arrays)} tableaux, "
              f"{sum(len(v) for v in loc_arrays.values())} items — "
              f"contrôlé.")

    print()
    for w in warnings:
        print(f"ATTENTION : {w}")
    if errors:
        for e in errors:
            print(f"ERREUR : {e}")
        sys.exit(f"\nÉCHEC : {len(errors)} erreur(s), "
                f"{len(warnings)} avertissement(s), {checks} vérifications.")
    print(f"\nTOUT VERT : {checks} vérifications "
          f"({len(warnings)} avertissement(s), toléré(s) : "
          f"langues partielles et textes identiques aux noms propres).")


if __name__ == "__main__":
    main()
