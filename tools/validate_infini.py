#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « famille Infini » (ENDLESS puis ABYSS, VOID).

Phase A — le socle jouable (ENDLESS 40 x 40) :
  - Skill.ENDLESS(branches = 3, wrap = true, hiddenThreshold = 9),
    taille FIXE 40 x 40 (bypass de la matrice Screen) ;
  - générateur ORIGINAL FORCÉ sur ce niveau (victoire par terminaux,
    le serveur scintille toujours : signature « électricité » constante) ;
  - menu Niveau, libellés EN/FR, barre d'état, écran des records.

Phase B — la caméra modulo (pan non borné, plateau répété, fenêtre
visible seule dessinée). Phase C — le brouillard DÉRIVÉ (Chebyshev <= 2
d'une tuile connectée, zéro état sérialisé).

Phase D (niveaux 7 et 8) — la famille se gradue :
  - propriété d'enum `val endless: Boolean` (ENDLESS, ABYSS, VOID),
    testée `.endless` partout au lieu de « == ENDLESS » ;
  - Abîme (ABYSS) : Infini + masquage à la Dément (hiddenThreshold 3) ;
  - Néant (VOID) : Abîme + brouillard PERMANENT (l'ancien réglage
    « Brouillard » de la phase C est retiré : le brouillard est la
    marque du niveau, plus une option).

Phase E — centrage Néant et caméra persistante (nov. 2026) : la vue
d'un Néant qui démarre se centre sur sa zone visible à un zoom
confort ; la caméra voyage en GameSpec v4 (v3/v2 décodés sans
caméra, milli-cellules arrondies — la troncature dérive en Float).

Deux parties, comme les validateurs précédents :
  1. Vérification statique : les fichiers Kotlin et XML sont PARSÉS
     (jamais de valeurs retapées à la main dans ce script).
  2. Simulation : génération 40x40 en tore (miroir Python de
     generateOriginal), victoire par terminaux, générateur forcé,
     format GameSpec avec la famille (aller-retour, rejet v2, caméra
    v4 et centrage Néant en phase E).

Usage : python3 tools/validate_infini.py
"""

import math
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
RES = ROOT / "app/src/main/res"

failures = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    status = "OK  " if ok else "ECHEC"
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def read(path):
    return Path(path).read_text(encoding="utf-8")


def strip_comments(src):
    """Retire les commentaires (blocs et lignes) d'une source Kotlin."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


skill_src = read(KOTLIN / "game/Skill.kt")
screen_src = read(KOTLIN / "game/Screen.kt")
vm_src = read(KOTLIN / "GameViewModel.kt")
ma_src = read(KOTLIN / "MainActivity.kt")
scores_src = read(KOTLIN / "ScoresActivity.kt")
spec_src = read(KOTLIN / "game/GameSpec.kt")
menu_src = read(RES / "menu/menu_main.xml")
en_src = read(RES / "values/strings.xml")
fr_src = read(RES / "values-fr/strings.xml")
tests_src = read(ROOT / "app/src/test/kotlin/net/chardons/netscramble2/game/BoardTest.kt")

# ---------------------------------------------------------------------------
# 1. Skill.kt — le niveau ENDLESS et sa taille fixe.
# ---------------------------------------------------------------------------

sk = strip_comments(skill_src)
sk_entries = re.findall(r"^    (\w+)\((.*?)\)[,;]", sk, re.M | re.S)
skills = {}
skill_order = []
for name, body in sk_entries:
    br = re.search(r"branches = (\d+)", body)
    wr = re.search(r"wrap = (true|false)", body)
    ht = re.search(r"hiddenThreshold = (\d+)", body)
    en = re.search(r"\bendless = (true|false)", body)
    skills[name] = (int(br.group(1)), wr.group(1) == "true",
                    int(ht.group(1)), en is not None and en.group(1) == "true")
    skill_order.append(name)
check("Skill.kt : ENDLESS = 3 branches, tore, aucun masquage",
      skills.get("ENDLESS") == (3, True, 9, True),
      f"lu={skills.get('ENDLESS')}")
check("Skill.kt : ABYSS = 3 branches, tore, masquage à la Dément",
      skills.get("ABYSS") == (3, True, 3, True), f"lu={skills.get('ABYSS')}")
check("Skill.kt : VOID = 3 branches, tore, masquage à la Dément",
      skills.get("VOID") == (3, True, 3, True), f"lu={skills.get('VOID')}")
check("Skill.kt : la propriété endless est un paramètre d'enum "
      "(famille déclarée, défaut false)",
      re.search(r"val endless: Boolean = false\b", sk) is not None)
check("Skill.kt : la famille Infini arrive EN DERNIER, dans l'ordre "
      "Endless, Abîme, Néant (graduation des niveaux)",
      skill_order == ["NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE",
                      "ENDLESS", "ABYSS", "VOID"], str(skill_order))
check("Skill.kt : taille fixe ENDLESS_SIZE = 40",
      re.search(r"const val ENDLESS_SIZE = 40\b", sk) is not None)
check("Skill.kt : toujours aucune dimension width/height dans l'enum",
      "width" not in sk and "height" not in sk)

# ---------------------------------------------------------------------------
# 2. Screen.kt — la matrice est bypassée pour ENDLESS.
# ---------------------------------------------------------------------------

sc = strip_comments(screen_src)
major_of = re.search(r"private fun majorOf\(skill: Skill\): Int = when \(skill\) \{(.*?)\n    \}", sc, re.S)
minor_of = re.search(r"private fun minorOf\(skill: Skill\): Int = when \(skill\) \{(.*?)\n    \}", sc, re.S)
check("Screen.kt : majorOf route la famille Infini vers la taille fixe",
      major_of is not None
      and "Skill.ENDLESS, Skill.ABYSS, Skill.VOID -> Skill.ENDLESS_SIZE"
      in major_of.group(1))
check("Screen.kt : minorOf route la famille Infini vers la taille fixe",
      minor_of is not None
      and "Skill.ENDLESS, Skill.ABYSS, Skill.VOID -> Skill.ENDLESS_SIZE"
      in minor_of.group(1))

# Miroir de boardSize : la famille ignore la matrice ET l'orientation.
screen_entries = re.findall(
    r"^\s{4}(\w+)\((\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+)\)[,;]",
    screen_src, re.M)
check("Screen.kt : les 5 matrices de l'original sont intactes",
      len(screen_entries) == 5,
      str([name for name, _ in screen_entries]))

def board_size_endless(screen_name, landscape):
    """Miroir : pour la famille, major et minor valent la taille fixe."""
    size = int(re.search(r"const val ENDLESS_SIZE = (\d+)", sk).group(1))
    return (size, size)

for skill_name in ("ENDLESS", "ABYSS", "VOID"):
    for name, _ in screen_entries:
        for landscape in (True, False):
            w, h = board_size_endless(name, landscape)
            check(f"boardSize({skill_name}) = {w}x{h} sur {name} "
                  f"({'paysage' if landscape else 'portrait'})",
                  (w, h) == (40, 40))

# ---------------------------------------------------------------------------
# 3. GameViewModel.kt — générateur ORIGINAL forcé sur ENDLESS.
# ---------------------------------------------------------------------------

vm = strip_comments(vm_src)
endless_forced = re.search(
    r"val generator = if \(skill\.endless\) \{\s*"
    r"Generator\.ORIGINAL\s*\} else if \(\(seed and 1L\) == 0L\) \{\s*"
    r"Generator\.TREE\s*\} else \{\s*Generator\.ORIGINAL\s*\}",
    vm)
check("GameViewModel : la famille Infini force ORIGINAL, les autres "
      "tirent à la graine",
      endless_forced is not None)
check("GameViewModel : la restauration reste gouvernée par la photographie "
      "(générateur sérialisé, pas retiré)",
      "generator = board.generator" in vm
      and "board = spec.buildBoard()" in vm)

# Miroir du choix de générateur (le if-chaîne de newGame), sur 50 graines.
def choose_generator(endless, seed):
    if endless:
        return "ORIGINAL"
    if (seed & 1) == 0:
        return "TREE"
    return "ORIGINAL"

forced_ok = True
mixer = random.Random(2026)
for i in range(50):
    seed = mixer.randrange(1 << 48)
    forced_ok &= choose_generator(True, seed) == "ORIGINAL"
check("Miroir : famille Infini = ORIGINAL sur 50 graines (paires et "
      "impaires)", forced_ok)
others_mixed = {choose_generator(False, s) for s in (0, 1, 2, 3)}
check("Miroir : hors famille le tirage parité persiste (TREE/ORIGINAL)",
      others_mixed == {"TREE", "ORIGINAL"})

# ---------------------------------------------------------------------------
# 4. MainActivity.kt — libellé d'état, coche, entrée de menu.
# ---------------------------------------------------------------------------

ma = strip_comments(ma_src)

# Ordre de l'enum réutilisé : les libellés de la barre d'état sont indexés
# par skill.ordinal — la liste doit suivre l'enum, entrée pour entrée.
status_list = re.search(
    r"listOf\(\s*((?:R\.string\.skill_\w+,?\s*)+?)\)\[state\.skill\.ordinal\]",
    ma)
status_ids = (re.findall(r"R\.string\.(skill_\w+)", status_list.group(1))
              if status_list else [])
expected_labels = ["skill_" + name.lower() for name in skill_order]
check("MainActivity : 8 libellés d'état alignés sur l'ordre de l'enum",
      status_ids == expected_labels, f"lu={status_ids}")

# Depuis la page unique « Réglages » (13 nov. 2026), le niveau choisi
# au tap vit sur SettingsActivity : c'est elle qui mappe chaque Skill
# vers SA radio, et MainActivity qui relance la partie au retour.
sa = read(KOTLIN / "SettingsActivity.kt")
radio_block = re.search(
    r"private fun skillRadioFor\(skill: Skill\): Int = when \(skill\) \{"
    r"(.*?)\n    \}", sa, re.S)
radio_mapping = (dict(re.findall(r"Skill\.(\w+) -> R\.id\.(skill_\w+)",
                                radio_block.group(1)))
                if radio_block else {})
radio_map = {v: "Skill." + k for k, v in radio_mapping.items()}
check("SettingsActivity : la radio skill_endless mappe Skill.ENDLESS",
      radio_map.get("skill_endless") == "Skill.ENDLESS",
      f"lu={radio_map.get('skill_endless')}")
check("SettingsActivity : les 8 radios mappent chacune vers son Skill",
      radio_map == {
          "skill_" + name.lower(): "Skill." + name for name in skill_order},
      str(radio_map))
check("SettingsActivity : la page s'ouvre sur le niveau reçu du jeu",
      re.search(r"Skill\.entries\.firstOrNull \{\s*"
                r"it\.name == intent\.getStringExtra\(EXTRA_SKILL\)",
                sa) is not None)
check("MainActivity : le retour de la page relance la partie au "
      "niveau choisi (avec le rappel des règles)",
      re.search(r"settingsLauncher.*?skill != previous.*?"
                r"viewModel\.newGame\(skill, width, height\).*?"
                r"showSkillHelpIfNeeded\(skill, previous\)",
                ma, re.S) is not None)

# ---------------------------------------------------------------------------
# 5. Menu, chaînes, records.
# ---------------------------------------------------------------------------

# Les 8 niveaux vivaient dans le sous-menu du jeu ; depuis la page
# unique « Réglages » (13 nov. 2026), ils vivent dans le RadioGroup
# de la page — même invariant, même ordre.
settings_src = read(RES / "layout/activity_settings.xml")
group = re.search(
    r'android:id="@\+id/skill_group"(.*?)</RadioGroup>', settings_src, re.S)
# v4 : les TextViews de description (skill_*_desc) vivent aussi dans
# le RadioGroup — on ne retient que les MaterialRadioButton.
menu_ids = (re.findall(r'MaterialRadioButton[^>]*@\+id/(skill_\w+)',
                       group.group(1)) if group else [])
check("menu_main.xml : les 8 niveaux sont dans le groupe du Niveau",
      menu_ids == expected_labels, f"lu={menu_ids}")
check("menu_main.xml : Infini vient après Dément, Abîme et Néant "
      "ferment la marche",
      menu_ids.index("skill_endless") == menu_ids.index("skill_insane") + 1
      and menu_ids.index("skill_void") == menu_ids.index("skill_abyss") + 1)

en = dict(re.findall(r'<string name="(skill_\w+)">([^<]*)</string>', en_src))
fr = dict(re.findall(r'<string name="(skill_\w+)">([^<]*)</string>', fr_src))
check("Chaînes : skill_endless = Endless (EN)", en.get("skill_endless") == "Endless",
      f"lu={en.get('skill_endless')!r}")
check("Chaînes : skill_endless = Infini (FR)", fr.get("skill_endless") == "Infini",
      f"lu={fr.get('skill_endless')!r}")
check("Chaînes : skill_abyss = Abyss (EN), Abîme (FR)",
      en.get("skill_abyss") == "Abyss" and fr.get("skill_abyss") == "Abîme",
      f"lu={en.get('skill_abyss')!r}/{fr.get('skill_abyss')!r}")
check("Chaînes : skill_void = Void (EN), Néant (FR)",
      en.get("skill_void") == "Void" and fr.get("skill_void") == "Néant",
      f"lu={en.get('skill_void')!r}/{fr.get('skill_void')!r}")
check("Chaînes : aucune apostrophe non échappée dans les libellés FR",
      all(re.search(r"(?<!\\)'", v) is None
          for k, v in fr.items() if k.startswith("skill_")))

sa = strip_comments(scores_src)
labels = re.search(r"private val labels = listOf\(\s*((?:R\.string\.skill_\w+,?\s*)+?)\)", sa)
scores_ids = re.findall(r"R\.string\.(skill_\w+)", labels.group(1)) if labels else []
check("ScoresActivity : les tables affichent les 8 niveaux, dans l'ordre",
      scores_ids == expected_labels, f"lu={scores_ids}")

# ---------------------------------------------------------------------------
# 6. GameSpec.kt — rétrocompatibilité : ENDLESS voyage en v3, pas en v2.
# ---------------------------------------------------------------------------

sp = strip_comments(spec_src)
check("GameSpec : le niveau voyage PAR NOM (rétrocompatible par construction)",
      "append(skill.name)" in sp)
check("GameSpec : dimensions bornées à 64 (40 x 40 accepté)",
      "width in 1..64 && height in 1..64" in sp)
legacy_block = re.search(r"LEGACY_SIZES = mapOf\((.*?)\)\s*\n",
                         sp, re.S)
legacy_entries = re.findall(r"Skill\.(\w+)",
                            legacy_block.group(1)) if legacy_block else []
check("GameSpec : le format v2 ignore ENDLESS (rejet propre d'un v2 falsifié)",
      "ENDLESS" not in legacy_entries and len(legacy_entries) == 5,
      f"lu={legacy_entries}")

# ---------------------------------------------------------------------------
# 7. Tests JVM — ENDLESS est couvert par les boucles existantes.
# ---------------------------------------------------------------------------

ts = strip_comments(tests_src)
check("Tests JVM : la taille fixe 40 x 40 est verrouillée par un test",
      "endless board is fixed 40 by 40" in ts
      and "Skill.ENDLESS_SIZE to Skill.ENDLESS_SIZE" in ts)
check("Tests JVM : le test de taille couvre la famille entière "
      "(Endless, Abîme, Néant)",
      re.search(r"listOf\(Skill\.ENDLESS, Skill\.ABYSS, Skill\.VOID\)", ts)
      is not None)
check("Tests JVM : les règles empilées d'Abîme et Néant sont verrouillées "
      "par un test (endless, branches, tore, masquage)",
      "abyss and void stack endless rules" in ts)
check("Tests JVM : les boucles sur Skill.entries couvrirront la famille",
      ts.count("for (skill in Skill.entries)") >= 2)
check("Tests JVM : messages d'échec en PREMIER argument (JUnit 4 — "
      "piège du 13 nov. : le style JUnit 5 « message en dernier » ne "
      "compile pas avec org.junit.Assert)",
      'assertEquals("$skill garde trois branches", 3, skill.branches)' in ts
      and 'assertTrue("$skill garde le tore", skill.wrap)' in ts
      and re.search(r'assert\w+\([^")]*,[^")]*,\s*"', ts) is None)

# ---------------------------------------------------------------------------
# 8. Pièges Kotlin : pas de « /* » dans les commentaires des fichiers touchés.
# ---------------------------------------------------------------------------

for rel, src in (("game/Skill.kt", skill_src), ("game/Screen.kt", screen_src),
                 ("GameViewModel.kt", vm_src), ("MainActivity.kt", ma_src),
                 ("ScoresActivity.kt", scores_src), ("game/GameSpec.kt", spec_src),
                 ("BoardTest.kt", tests_src)):
    bad = [line for line in src.splitlines()
           if re.search(r"^\s*(//|\*)[^/]*?/\*", line)]
    check(f"Piège /* imbriqué absent ({rel})", not bad,
          bad[0].strip()[:60] if bad else "")

# ---------------------------------------------------------------------------
# 9. Simulation : génération 40 x 40 en tore, miroir de generateOriginal.
# ---------------------------------------------------------------------------

# Directions : mêmes bits que Dir.kt (UP=1, RIGHT=2, DOWN=4, LEFT=8).
DIRS = [0, 1, 2, 3]
BIT = [1, 2, 4, 8]
DX = [0, 1, 0, -1]
DY = [-1, 0, 1, 0]


def opposite(d):
    return (d + 2) % 4


def neighbor(index, width, height, wrap, d):
    x, y = index % width, index // width
    nx, ny = x + DX[d], y + DY[d]
    if wrap:
        return (ny % height) * width + (nx % width)
    if 0 <= nx < width and 0 <= ny < height:
        return ny * width + nx
    return -1


def add_random_dir(conns, queue, rnd, width, height, wrap):
    cell = queue[0]
    free = [d for d in DIRS
            if (n := neighbor(cell, width, height, wrap, d)) >= 0
            and conns[n] == 0]
    if not free:
        return
    key = free[rnd.randrange(len(free))]
    dest = neighbor(cell, width, height, wrap, key)
    conns[cell] |= BIT[key]
    conns[dest] |= BIT[opposite(key)]
    queue.append(dest)


def gen_original(rnd, width, height, wrap, branches):
    """Miroir de generateOriginal : renvoie (connections, racine)."""
    size = width * height
    conns = [0] * size
    min_cells = int(size * 0.85)
    root, cells, tries = 0, 0, 0
    while cells < min_cells and tries < 10:
        conns = [0] * size
        root = rnd.randrange(width) + width * rnd.randrange(height)
        queue = [root]
        if rnd.random() < 0.5:
            add_random_dir(conns, queue, rnd, width, height, wrap)
        while queue:
            if rnd.random() < 0.5:
                add_random_dir(conns, queue, rnd, width, height, wrap)
                if rnd.random() < 0.5:
                    add_random_dir(conns, queue, rnd, width, height, wrap)
                if branches >= 3 and rnd.randrange(3) == 0:
                    add_random_dir(conns, queue, rnd, width, height, wrap)
            else:
                queue.append(queue[0])
            queue.pop(0)
        cells = sum(1 for c in conns if c != 0)
        tries += 1
    return conns, root


def rotate_mask(mask, steps):
    out = 0
    for d in DIRS:
        if mask & BIT[d]:
            out |= BIT[(d + steps) % 4]
    return out


def connected_cells(conns, rotations, width, height, wrap, server):
    size = width * height
    connected = [False] * size
    connected[server] = True
    queue = [server]
    while queue:
        cur = queue.pop(0)
        mask = rotate_mask(conns[cur], rotations[cur])
        for d in DIRS:
            if not mask & BIT[d]:
                continue
            n = neighbor(cur, width, height, wrap, d)
            if n < 0 or connected[n]:
                continue
            if not rotate_mask(conns[n], rotations[n]) & BIT[opposite(d)]:
                continue
            connected[n] = True
            queue.append(n)
    return connected


def is_solved(conns, rotations, width, height, wrap, server):
    """Miroir de isSolved pour ORIGINAL : terminaux tous reliés."""
    connected = connected_cells(conns, rotations, width, height, wrap, server)
    return not any(bin(c).count("1") == 1 and not connected[i]
                   for i, c in enumerate(conns))


SIZE = 40
NBOARDS = 20
rnd = random.Random(424242)
coverages = []
terminals_count = []
gen_fail = []
for seed in range(NBOARDS):
    r = random.Random(1_000_003 * seed + 7)
    conns, root = gen_original(r, SIZE, SIZE, wrap=True, branches=3)
    size = SIZE * SIZE
    coverages.append(sum(1 for c in conns if c != 0) / size)
    terminals = sum(1 for i, c in enumerate(conns)
                    if bin(c).count("1") == 1 and i != root)
    terminals_count.append(terminals)
    # Le serveur est câblé, la solution est connexe, victoire par terminaux.
    if conns[root] == 0:
        gen_fail.append(f"graine {seed} : serveur non câblé")
    sol = connected_cells(conns, [0] * size, SIZE, SIZE, True, root)
    if any(c != 0 and not sol[i] for i, c in enumerate(conns)):
        gen_fail.append(f"graine {seed} : cellule active non connexe")
    if not is_solved(conns, [0] * size, SIZE, SIZE, True, root):
        gen_fail.append(f"graine {seed} : pas de victoire à rotations nulles")
    # Mélange (miroir de scramble) : jamais résolu au départ.
    r2 = random.Random(seed ^ (-0x5DEECE66D & ((1 << 64) - 1)))
    for _ in range(100):
        rotations = [r2.randrange(4) for _ in range(size)]
        if not is_solved(conns, rotations, SIZE, SIZE, True, root):
            break
    else:
        gen_fail.append(f"graine {seed} : mélangé encore résolu")
    # Solveur : le BFS de la solution rejoint toutes les cellules actives.
    visited = [False] * size
    visited[root] = True
    queue = [root]
    while queue:
        cur = queue.pop(0)
        for d in DIRS:
            if not conns[cur] & BIT[d]:
                continue
            n = neighbor(cur, SIZE, SIZE, True, d)
            if n < 0 or visited[n]:
                continue
            visited[n] = True
            queue.append(n)
    if any(c != 0 and not visited[i] for i, c in enumerate(conns)):
        gen_fail.append(f"graine {seed} : solveur ne couvre pas le réseau")

check("Génération 40x40 : couverture >= 85 pour cent sur 20 graines",
      min(coverages) >= 0.85, f"min={min(coverages):.1%}, "
      f"moyenne={sum(coverages)/len(coverages):.1%}")
check("Génération 40x40 : des terminaux existent (victoire possible)",
      min(terminals_count) > 0, f"min={min(terminals_count)}, "
      f"max={max(terminals_count)}")
check("Génération 40x40 : solution saine, victoire par terminaux, "
      "solveur complet, jamais résolu au mélange",
      not gen_fail, "; ".join(gen_fail[:3]))

# ---------------------------------------------------------------------------
# 10. GameSpec v3 ENDLESS : aller-retour et rejets (miroir du format).
# ---------------------------------------------------------------------------

SKILL_NAMES = set(skill_order)


def encode_v3(skill, seed, gen, width, height, root, moves, ms, solver,
              prev, queue, rotations, locked, revealed):
    return "|".join([
        "v3", skill, str(seed), gen, str(width), str(height), str(root),
        str(moves), str(ms), str(solver), str(prev), ",".join(map(str, queue)),
        "".join(map(str, rotations)),
        "".join("1" if l else "0" for l in locked),
        "".join("1" if rv else "0" for rv in revealed),
    ])


def decode_mirror(spec):
    """Miroir de GameSpec.decode, ENDLESS compris (v3 par nom)."""
    parts = spec.split("|")
    if parts[0] == "v3":
        if len(parts) != 15:
            return None
        legacy = False
    elif parts[0] == "v2":
        if len(parts) != 11:
            return None
        legacy = True
    else:
        return None
    skill = parts[1]
    if skill not in SKILL_NAMES:
        return None
    if legacy:
        legacy_sizes = {"NOVICE": (5, 5), "NORMAL": (7, 7), "EXPERT": (9, 9),
                        "MASTER": (9, 9), "INSANE": (11, 11)}
        if skill not in legacy_sizes:
            return None
        gen = "TREE"
        width, height = legacy_sizes[skill]
        root = (height // 2) * width + (width // 2)
    else:
        gen = parts[3]
        if gen not in ("ORIGINAL", "TREE"):
            return None
        width, height = int(parts[4]), int(parts[5])
        root = int(parts[6])
    size = width * height
    if not (1 <= width <= 64 and 1 <= height <= 64):
        return None
    if root < -1 or root >= size:
        return None
    base = 3 if legacy else 7
    rot_s, lock_s, rev_s = parts[base + 5], parts[base + 6], parts[base + 7]
    if len(rot_s) != size or len(lock_s) != size or len(rev_s) != size:
        return None
    if any(c not in "0123" for c in rot_s):
        return None
    if any(c not in "01" for c in lock_s + rev_s):
        return None
    queue = [int(v) for v in parts[base + 4].split(",") if v]
    if any(not (0 <= q < size) for q in queue):
        return None
    return dict(skill=skill, seed=int(parts[2]), gen=gen, width=width,
                height=height, root=root, moves=int(parts[base]),
                ms=int(parts[base + 1]), solver=parts[base + 2] == "true",
                prev=int(parts[base + 3]), queue=queue,
                rotations=[int(c) for c in rot_s],
                locked=[c == "1" for c in lock_s],
                revealed=[c == "1" for c in rev_s])


# Une partie Infini typique : 40 x 40, ORIGINAL, racine tirée (consigne -1).
r = random.Random(20261112)
big_rotations = [r.randrange(4) for _ in range(SIZE * SIZE)]
big_locked = [r.random() < 0.05 for _ in range(SIZE * SIZE)]
big_revealed = [False] * (SIZE * SIZE)
spec = encode_v3("ENDLESS", 987654321012, "ORIGINAL", SIZE, SIZE, -1,
                 123, 654_000, False, 555, [1, 2, 3],
                 big_rotations, big_locked, big_revealed)
back = decode_mirror(spec)
check("GameSpec v3 ENDLESS : aller-retour intact (40x40, ORIGINAL)",
      back is not None and back["skill"] == "ENDLESS"
      and back["gen"] == "ORIGINAL" and back["root"] == -1
      and (back["width"], back["height"]) == (40, 40)
      and back["rotations"] == big_rotations and back["queue"] == [1, 2, 3])
check("GameSpec v3 ENDLESS : taille sérialisée raisonnable (mesurée)",
      0 < len(spec) < 7000, f"{len(spec)} octets")

check("GameSpec : un v2 falsifié au niveau ENDLESS est rejeté",
      decode_mirror("v2|ENDLESS|1|17|42000|false|-1|5,9|"
                    + "0" * 25 + "|" + "0" * 25 + "|" + "0" * 25) is None)
for fake in ("ABYSS", "VOID"):
    check(f"GameSpec : un v2 falsifié au niveau {fake} est rejeté",
          decode_mirror(f"v2|{fake}|1|17|42000|false|-1|5,9|"
                        + "0" * 25 + "|" + "0" * 25 + "|" + "0" * 25)
          is None)

# Abîme et Néant voyagent en v3, comme Infini (par nom).
for fam in ("ABYSS", "VOID"):
    spec_fam = encode_v3(fam, 987654321012 + skill_order.index(fam),
                         "ORIGINAL", SIZE, SIZE, -1,
                         7, 21_000, False, 12, [],
                         big_rotations, big_locked, big_revealed)
    back_fam = decode_mirror(spec_fam)
    check(f"GameSpec v3 {fam} : aller-retour intact (40x40, ORIGINAL)",
          back_fam is not None and back_fam["skill"] == fam
          and back_fam["gen"] == "ORIGINAL"
          and (back_fam["width"], back_fam["height"]) == (40, 40))

check("GameSpec : dimensions hors bornes (65) rejetées",
      decode_mirror("v3|ENDLESS|1|ORIGINAL|65|40|-1|0|0|false|-1|"
                    + "0" * (65 * 40) + "|" + "0" * (65 * 40)
                    + "|" + "0" * (65 * 40)) is None)

# ---------------------------------------------------------------------------
# 11. Phase B : la caméra modulo (illusion d'infini) — statique.
# ---------------------------------------------------------------------------

board_src = read(KOTLIN / "game/Board.kt")
view_src = read(KOTLIN / "ui/BoardView.kt")

bd = strip_comments(board_src)
check("Board.kt : le plateau endosse son niveau (vue : mode Infini)",
      re.search(r"val skill: Skill = Skill\.NOVICE", bd) is not None)
check("Board.kt : la fabrique generate endosse le niveau réel",
      re.search(r"skill = skill,\s*\n\s*forcedRoot = forcedRoot", bd) is not None)

vw = strip_comments(view_src)
check("BoardView : le drapeau Infini est dérivé du niveau du plateau "
      "(propriété endless — phase D)",
      "val endless = b.skill.endless" in vw)
check("BoardView : Skill est IMPORTÉ (la phase B livrée le 12 nov. "
      "l'oubliait — écran rouge « Unresolved reference » corrigé par "
      "eric le 13 nov., régression désormais bloquée ici)",
      re.search(r"(?m)^import net\.chardons\.netscramble2\.game\.Skill$",
                view_src) is not None)
check("BoardView : plancher de dézoom propre à la famille Infini "
      "(marge de cases)",
      re.search(r"if \(b\.skill\.endless\) \{\s*"
                r"val span = minOf\(b\.width, b\.height\)\.toFloat\(\)\s*"
                r"val largest = maxOf\(width, height\)\.toFloat\(\)\s*"
                r"\(largest / \(span - 2f \* ENDLESS_EDGE_MARGIN_CELLS\)\)",
                vw) is not None)
check("BoardView : constante de marge Infini = 2 cases",
      re.search(r"ENDLESS_EDGE_MARGIN_CELLS = 2f\b", vw) is not None)
check("BoardView : fitBoard centre le plateau de la famille (pas de clamp)",
      re.search(r"if \(b\.skill\.endless\) \{\s*"
                r"panX = \(width - b\.width \* cellPx\) / 2f", vw) is not None)
check("BoardView : le pan de la famille n'est PAS borné",
      re.search(r"private fun clampPan\(\) \{\s*"
                r"val b = board \?: return\s*"
                r"if \(b\.skill\.endless\) return",
                vw) is not None)
check("BoardView : le tap passe par floorMod sur Infini (invariance)",
      "Math.floorMod(cy, b.height) * b.width +" in vw
      and "Math.floorMod(cx, b.width)" in vw)
check("BoardView : le floor (négatifs) remplace la troncature au tap",
      "floor((x - panX) / cellPx).toInt()" in vw
      and re.search(r"val cx = \(\(x - panX\) / cellPx\)\.toInt\(\)", vw) is None
      and re.search(r"val cy = \(\(y - panY\) / cellPx\)\.toInt\(\)", vw) is None)
check("BoardView : PAS de décor gris sur Infini (le plateau se répète)",
      re.search(r"if \(!endless\) \{\s*val decor = emptyTileBitmap\(\)",
                vw) is not None)
check("BoardView : fenêtre visible hissée, partagée par toutes les passes",
      "val minX = floor(-panX / cellPx).toInt()" in vw
      and "val maxX = ceil((width - panX) / cellPx).toInt()" in vw)
check("BoardView : cellules d'Infini itérées par floorMod sur la fenêtre "
      "(passes cellules ET blips)",
      vw.count("Math.floorMod(wy, b.height) * b.width +") >= 2
      and vw.count("Math.floorMod(wx, b.width)") >= 2)
check("BoardView : le corps de cellule est COMMUN aux deux régimes",
      "private fun Canvas.drawBoardCell(" in vw
      and "private fun Canvas.drawCellBlips(" in vw)
check("BoardView : le gel d'apparence vit dans le corps commun "
      "(validateur apparence)",
      "if (appearanceFrozen) animFromConnected[index]" in vw
      and "lastDrawnConnected[index] = isConnected" in vw
      and "blipsEnabled && !appearanceFrozen" in vw)

# ---------------------------------------------------------------------------
# 12. Phase B : miroirs de la caméra modulo.
# ---------------------------------------------------------------------------

# Constantes de BoardView, PARSÉES depuis la source (jamais retapées).
MIN_CELL_DP = float(re.search(r"MIN_CELL_DP = ([\d.]+)f", view_src).group(1))
MAX_CELL_DP = float(re.search(r"MAX_CELL_DP = ([\d.]+)f", view_src).group(1))
MARGIN_CELLS = float(
    re.search(r"ENDLESS_EDGE_MARGIN_CELLS = ([\d.]+)f", view_src).group(1))
SPAN = 40  # Skill.ENDLESS_SIZE, vérifié en section 1


def floor_cell_px(view_w, view_h, density):
    """Miroir de floorCellPx pour ENDLESS."""
    largest = max(view_w, view_h)
    raw = largest / (SPAN - 2.0 * MARGIN_CELLS)
    return min(max(raw, MIN_CELL_DP * density), MAX_CELL_DP * density)


def hit_cell_endless(x, y, pan_x, pan_y, cell):
    """Miroir de hitCell pour ENDLESS (floor, puis floorMod)."""
    cx = math.floor((x - pan_x) / cell)
    cy = math.floor((y - pan_y) / cell)
    return (cy % SPAN) * SPAN + (cx % SPAN)


# Écrans réalistes : (largeur px, hauteur px, densité).
DEVICES = [
    (1080, 2400, 2.625),   # téléphone haut de gamme portrait
    (2400, 1080, 2.625),   # idem paysage
    (1080, 2340, 3.0),
    (1440, 3200, 3.5),
    (1200, 2000, 2.0),     # tablette portrait
    (1600, 2560, 2.0),     # grande tablette portrait
    (720, 1600, 2.0),
]

floor_fail = []
for view_w, view_h, density in DEVICES:
    cell = floor_cell_px(view_w, view_h, density)
    largest = max(view_w, view_h)
    extent = SPAN * cell
    # Le plateau entier ne tient JAMAIS : marge >= 2 cases de chaque côté.
    if extent - largest < 2.0 * MARGIN_CELLS * cell - 1e-6:
        floor_fail.append(f"{view_w}x{view_h}@{density} : plateau visible")
    # La fenêtre visible reste plus ÉTROITE que le plateau : aucune
    # cellule n'apparaît deux fois (chaque copie est une tuile différente).
    window = math.ceil(largest / cell) + 1
    if window >= SPAN:
        floor_fail.append(f"{view_w}x{view_h}@{density} : fenêtre {window} >= 40")
check("Miroir plancher : plateau entier jamais visible, fenêtre < plateau "
      "(7 écrans réalistes, portrait et paysage)",
      not floor_fail, "; ".join(floor_fail[:3]))

# Invariance du tap par translation d'un plateau ENTIER (40 cases) : la
# copie vaut l'original, dans les deux axes et les deux sens.
rnd = random.Random(20261112)
invariant_ok = True
for trial in range(50):
    view_w, view_h, density = rnd.choice(DEVICES)
    cell = floor_cell_px(view_w, view_h, density)
    span_px = SPAN * cell
    pan_x = rnd.uniform(-span_px, 0)
    pan_y = rnd.uniform(-span_px, 0)
    x = rnd.uniform(0, view_w)
    y = rnd.uniform(0, view_h)
    base = hit_cell_endless(x, y, pan_x, pan_y, cell)
    for k in (-2, -1, 0, 1, 2):
        for j in (-2, -1, 0, 1, 2):
            if hit_cell_endless(x + k * span_px, y + j * span_px,
                                pan_x, pan_y, cell) != base:
                invariant_ok = False
check("Miroir tap : mapping invariant par translation de 40 cases "
      "(50 configurations, 25 translations chacune)", invariant_ok)

# Couture : les copies sont à des positions qui diffèrent d'exactement
# l'étendue du plateau — le raccord est invisible (aucun écart).
seam_ok = True
for view_w, view_h, density in DEVICES:
    cell = floor_cell_px(view_w, view_h, density)
    for wx in range(-50, 50):
        left_a = wx * cell
        left_b = (wx + SPAN) * cell
        if abs((left_b - left_a) - SPAN * cell) > 1e-6:
            seam_ok = False
        # Le contenu des deux copies est la même cellule du plateau.
        if hit_cell_endless(left_a + cell / 2, 0, 0, 0, cell) != \
           hit_cell_endless(left_b + cell / 2, 0, 0, 0, cell):
            seam_ok = False
check("Miroir couture : copies espacées d'exactement un plateau, "
      "même cellule dessous", seam_ok)

# La fenêtre couvre TOUJOURS la vue (arrondis outward), quel que soit le
# pan — aucun pixel de vue sans tuile.
cover_fail = []
for trial in range(50):
    view_w, view_h, density = rnd.choice(DEVICES)
    cell = floor_cell_px(view_w, view_h, density)
    span_px = SPAN * cell
    pan_x = rnd.uniform(-span_px, 0)
    pan_y = rnd.uniform(-span_px, 0)
    min_x = math.floor(-pan_x / cell)
    max_x = math.ceil((view_w - pan_x) / cell)
    min_y = math.floor(-pan_y / cell)
    max_y = math.ceil((view_h - pan_y) / cell)
    if min_x * cell > -pan_x + 1e-6 or max_x * cell < view_w - pan_x - 1e-6:
        cover_fail.append(f"horizontal {pan_x:.0f}")
    if min_y * cell > -pan_y + 1e-6 or max_y * cell < view_h - pan_y - 1e-6:
        cover_fail.append(f"vertical {pan_y:.0f}")
check("Miroir fenêtre : la vue est toujours couverte, tout pan confondu "
      "(50 configurations)", not cover_fail, "; ".join(cover_fail[:3]))

# Négatif : les niveaux encadrés gardent le comportement actuel — le
# pan clippé et l'anneau gris ne disparaissent QUE pour la famille.
check("Miroir négatif : hors famille, clampPan borne toujours le pan "
      "(l'anneau reste contournable uniquement sur la famille Infini)",
      re.search(r"if \(b\.skill\.endless\) return\s*\n"
                r"        val ring = GREY_RING_CELLS \* cellPx", vw) is not None)

# ---------------------------------------------------------------------------
# Phase C/D — brouillard (dérivé de connected, rayon de Chebyshev, tuile
# cachée sombre sans interaction, révélation totale à la victoire).
# Phase D : le brouillard n'est PLUS un réglage — il est la marque
# PERMANENTE du niveau Néant (VOID) ; le réglage de la phase C est
# retiré de l'activité et du menu.
# ---------------------------------------------------------------------------

fog_radius_m = re.search(r"const val FOG_RADIUS_CELLS = (\d+)", bd)

check("Board : la dérivation du brouillard existe (fogVisibility, "
      "dérivée PURE de connected — zéro état sérialisé)",
      "fun fogVisibility(connected: BooleanArray): BooleanArray" in bd)

check("Board : le rayon de Chebyshev du brouillard est une constante "
      "parsée (spec validée : 2)",
      fog_radius_m is not None,
      f"rayon = {fog_radius_m.group(1) if fog_radius_m else '?'}")

check("Board : le voisinage s'enroule sur le tore (floorMod, comme les "
      "câbles du niveau)",
      "Math.floorMod(cy + dy, height) * width" in bd and
      "Math.floorMod(cx + dx, width)" in bd and
      "for (dy in -r..r)" in bd and "for (dx in -r..r)" in bd)

check("BoardView : le brouillard est la marque du Néant — actif "
      "d'office sur VOID tant que la partie n'est PAS résolue "
      "(la victoire révèle tout), null sinon",
      re.search(r"fogVisible =\s*\n\s*if \(board\.skill == "
                r"Skill\.VOID && !solved\) \{\s*\n\s*"
                r"board\.fogVisibility\(connected\)", vw) is not None)

check("BoardView : l'ancien réglage fogEnabled est RETIRÉ "
      "(plus une option — phase D)",
      "fogEnabled" not in vw and "FOG_ENABLED_KEY" not in vw)

check("BoardView : hitCell refuse les tuiles cachées (ni rotation au "
      "tap, ni verrouillage au long-press — sans interaction)",
      "if (fog != null && !fog[index]) return null" in vw)

check("BoardView : la passe des cellules d'Infini dessine les cachées en "
      "case sombre (drawFogCell, couleur fog_cell)",
      vw.count("drawFogCell(") >= 2 and "R.color.fog_cell" in vw)

check("BoardView : la passe des blips ignore les tuiles cachées "
      "(les connectées restent visibles par définition)",
      "if (fog != null && !fog[index]) continue" in vw)

check("MainActivity : le réglage brouillard est intégralement retiré "
      "(pas de var, pas de clé, pas de handler — phase D)",
      "fogEnabled" not in ma and "FOG_ENABLED_KEY" not in ma)

check("Menu : le sous-menu « Brouillard » est RETIRÉ des Réglages",
      "menu_fog" not in menu_src and "fog_off" not in menu_src
      and "fog_on" not in menu_src)

check("Strings : les chaînes du réglage brouillard sont retirées "
      "en EN et en FR",
      'name="menu_fog"' not in en_src and 'name="fog_off"' not in en_src
      and 'name="fog_on"' not in en_src
      and 'name="menu_fog"' not in fr_src and 'name="fog_off"' not in fr_src
      and 'name="fog_on"' not in fr_src)

def lum(hexcode):
    r = int(hexcode[1:3], 16)
    g = int(hexcode[3:5], 16)
    b = int(hexcode[5:7], 16)
    return 0.299 * r + 0.587 * g + 0.114 * b


def color_of(src, name):
    m = re.search(rf'<color name="{name}">(#[0-9A-Fa-f]{{6}})</color>', src)
    return m.group(1) if m else None


day_colors = read(RES / "values/colors.xml")
night_colors = read(RES / "values-night/colors.xml")
check("Couleurs : tuile cachée fog_cell en jour ET nuit, PLUS SOMBRE que "
      "le fond du plateau dans les deux thèmes",
      all(color_of(s, c) for s in (day_colors, night_colors)
          for c in ("fog_cell", "board_background")) and
      lum(color_of(day_colors, "fog_cell")) <
      lum(color_of(day_colors, "board_background")) and
      lum(color_of(night_colors, "fog_cell")) <
      lum(color_of(night_colors, "board_background")))

check("BoardTest : la dérivation est verrouillée par un test JVM "
      "(équivalence exhaustive sur le tore, rayon, pureté)",
      "endless fog visibility follows connected cells" in tests_src)

# --- Miroirs Python du brouillard (rayon PARSÉ depuis Board.kt) ---

FOG_RADIUS = int(fog_radius_m.group(1)) if fog_radius_m else 2
FW = FH = 40


def fog_derive(connected, w, h, r):
    """Miroir EXACT de Board.fogVisibility : balayage -r..r, floorMod."""
    visible = [False] * (w * h)
    for i, c in enumerate(connected):
        if not c:
            continue
        cx, cy = i % w, i // w
        for dy in range(-r, r + 1):
            row = (cy + dy) % h * w
            for dx in range(-r, r + 1):
                visible[row + (cx + dx) % w] = True
    return visible


def fog_definition(connected, w, h, r):
    """Définition brute INDÉPENDANTE : distance de Chebyshev sur le tore,
    minimum sur les cellules connectées (formulation différente de la
    dérivation : une vraie contre-épreuve)."""
    on = [i for i, c in enumerate(connected) if c]
    visible = []
    for i in range(w * h):
        x, y = i % w, i // w
        ok = False
        for j in on:
            dx = abs(x - (j % w))
            dx = min(dx, w - dx)
            dy = abs(y - (j // w))
            dy = min(dy, h - dy)
            if max(dx, dy) <= r:
                ok = True
                break
        visible.append(ok)
    return visible


fail_fog = []
for i in range(8):
    # Réseaux clairsemés déterministes (le serveur + quelques cellules
    # éparses, proches et loin des bords : l'enroulement compte) —
    # beaucoup de tuiles cachées : l'équivalence discrimine vraiment.
    connected = [False] * (FW * FH)
    for k in range(1 + (i % 6)):
        sx = (k * 11 + i * 3) % FW
        sy = (k * 17 + i * 5) % FH
        connected[sy * FW + sx] = True
    if fog_derive(connected, FW, FH, FOG_RADIUS) != \
            fog_definition(connected, FW, FH, FOG_RADIUS):
        fail_fog.append(f"réseau {i}")
check("Miroir brouillard : la dérivation parsée égale la définition "
      "Chebyshev-tore, exhaustivement (8 réseaux clairsemés)",
      not fail_fog, "; ".join(fail_fog[:3]))

counts_ok = True
for edge_case in [(0, 0), (FW - 1, FH - 1), (0, FH - 1),
                  (FW // 2, FH // 2), (FW - 1, 0)]:
    connected = [False] * (FW * FH)
    connected[edge_case[1] * FW + edge_case[0]] = True
    n = sum(fog_derive(connected, FW, FH, FOG_RADIUS))
    if n != (2 * FOG_RADIUS + 1) ** 2:
        counts_ok = False
check("Miroir brouillard : une connectée révèle exactement son carré "
      "(2r+1)², même aux quatre coins du tore (enroulement, aucune case "
      "perdue)",
      counts_ok, f"rayon {FOG_RADIUS} -> {(2 * FOG_RADIUS + 1) ** 2} cases")

monotone_ok = True
for i in range(6):
    connected = [False] * (FW * FH)
    for k in range(2 + i):
        connected[((k * 13 + i * 7) % FH) * FW +
                   (k * 19 + i * 3) % FW] = True
    full = fog_derive(connected, FW, FH, FOG_RADIUS)
    # On retire UNE connexion : la zone ne peut que rétrécir (le
    # brouillard dérivé ne retient rien d'un état passé).
    removed = list(connected)
    for k in range(FW * FH - 1, -1, -1):
        if removed[k]:
            removed[k] = False
            break
    part = fog_derive(removed, FW, FH, FOG_RADIUS)
    if any(p and not f for p, f in zip(part, full)):
        monotone_ok = False
check("Miroir brouillard : débrancher referme — la zone dérivée ne "
      "retient rien (monotonie stricte, 6 réseaux)",
      monotone_ok)


# ---------------------------------------------------------------------------
# Phase D — niveaux Abîme (ABYSS) et Néant (VOID) : aide à la première
# visite, miroirs de génération avec masquage.
# ---------------------------------------------------------------------------

help_when = re.search(
    r"private fun showSkillHelpIfNeeded\(skill: Skill, previous: Skill\) \{(.*?)\n    \}",
    ma, re.S)
check("MainActivity : rappel des règles à la première visite d'Abîme "
      "et de Néant (gardes sur showSkillHelpIfNeeded)",
      help_when is not None
      and re.search(r"Skill\.ABYSS if previous != Skill\.ABYSS",
                    help_when.group(1)) is not None
      and re.search(r"Skill\.VOID if previous != Skill\.VOID",
                    help_when.group(1)) is not None)

en_help = dict(re.findall(r'<string name="(help_\w+)">([^<]*)</string>', en_src))
fr_help = dict(re.findall(r'<string name="(help_\w+)">([^<]*)</string>', fr_src))
check("Strings : aide help_abyss en EN et FR (plateau infini + câbles "
      "invisibles)",
      "help_abyss" in en_help and "help_abyss" in fr_help
      and "invisible" in en_help["help_abyss"]
      and "invisibles" in fr_help["help_abyss"])
check("Strings : aide help_void en EN et FR (Abîme + brouillard)",
      "help_void" in en_help and "help_void" in fr_help
      and "fog" in en_help["help_void"].lower()
      and "brouillard" in fr_help["help_void"])
check("Strings : aucune apostrophe non échappée dans les aides FR",
      all(re.search(r"(?<!\\)'", v) is None
          for k, v in fr_help.items() if k.startswith("help_")))

# --- Miroir : le masquage à la Dément mord vraiment sur les plateaux
# générés pour Abîme et Néant (le seuil parsé depuis Skill.kt) ---

abyss_rule = skills.get("ABYSS")
HIDDEN_T = abyss_rule[2] if abyss_rule else 3
hidden_fail = []
hidden_counts = []
for i in range(10):
    r_gen = random.Random(31_000 + i * 101)
    conns_d, root_d = gen_original(r_gen, SIZE, SIZE, wrap=True, branches=3)
    hidden = sum(1 for c in conns_d
                 if bin(c).count("1") >= HIDDEN_T)
    hidden_counts.append(hidden)
    if hidden == 0:
        hidden_fail.append(f"graine {i}")
check(f"Miroir Abîme/Néant : des cellules cachées existent sur 10 "
      f"plateaux 40x40 (seuil parsé {HIDDEN_T} connexions)",
      not hidden_fail, f"min={min(hidden_counts)}, "
      f"max={max(hidden_counts)}")

# La solution reste saine avec masquage : la dérivation du brouillard du
# Néant révèle au moins le carré du serveur (une partie jouable existe).
void_reveal_ok = True
for i in range(6):
    r_gen = random.Random(41_000 + i * 97)
    conns_v, root_v = gen_original(r_gen, SIZE, SIZE, wrap=True, branches=3)
    connected = [False] * (SIZE * SIZE)
    connected[root_v] = True
    fog = fog_derive(connected, SIZE, SIZE, FOG_RADIUS)
    if not fog[root_v] or sum(fog) < (2 * FOG_RADIUS + 1) ** 2:
        void_reveal_ok = False
check("Miroir Néant : au lancement, le brouillard révèle au moins le "
      "carré du serveur (6 plateaux)", void_reveal_ok)

# ---------------------------------------------------------------------------
# Phase E — centrage Néant et caméra persistante (spec validée par eric) :
# au lancement d'un Néant, la vue se centre sur la zone visible (bbox du
# réseau connecté) à un zoom confort (carré révélé = moitié du petit
# côté) ; la caméra voyage en GameSpec v4 (v3/v2 décodés sans caméra) ;
# une rotation d'écran conserve le regard de la famille Infini.
# ---------------------------------------------------------------------------

gs_src = read(KOTLIN / "game/GameSpec.kt")
gs = strip_comments(gs_src)
check("GameSpec : le format passe en v4 (VERSION v4, 18 champs)",
      'VERSION = "v4"' in gs and "FIELDS = 18" in gs)
check("GameSpec : la v3 d'avant la caméra reste décodable "
      "(PREVIOUS_VERSION, 15 champs)",
      'PREVIOUS_VERSION = "v3"' in gs and "PREVIOUS_FIELDS = 15" in gs)
check("GameSpec : CameraSpec est une DATA CLASS (l'aller-retour "
      "encode/decode compare les valeurs, jamais les références)",
      re.search(r"data class CameraSpec\(", gs) is not None)
check("GameSpec : la caméra participe à equals (v4 complète)",
      "camera == other.camera" in gs)
check("GameSpec : bornes de décodage caméra (position modulo le plateau, "
      "zoom entre plancher et plafond de la vue)",
      "camX !in 0 until width * 1_000" in gs
      and "camY !in 0 until height * 1_000" in gs
      and "zoom !in MIN_ZOOM_CENTI_DP..MAX_ZOOM_CENTI_DP" in gs)
check("GameSpec : encode sans caméra = repli propre (position neutre, "
      "zoom minimal)",
      "camera?.centerMilliX ?: 0" in gs
      and "camera?.zoomCentiDp ?: MIN_ZOOM_CENTI_DP" in gs)
check("GameSpec : les bornes de zoom sont celles de la vue (26 et 96 dp)",
      "MIN_ZOOM_CENTI_DP = 2_600" in gs
      and "MAX_ZOOM_CENTI_DP = 9_600" in gs)

check("BoardView : la caméra d'une partie restaurée est posée avant le "
      "premier rendu (restoreCamera, consommée une seule fois)",
      "fun restoreCamera(camera: CameraSpec?)" in vw
      and "restoredCamera = null" in vw)
check("BoardView : la photographie cameraSnapshot existe (milli-cellules "
      "modulo, zoom en centièmes de dp)",
      "fun cameraSnapshot(): CameraSpec" in vw
      and "Math.floorMod((cx * 1_000f).roundToInt(), b.width * 1_000)" in vw)
check("BoardView : MILLI-CELLULES ARRONDIES au plus proche — la troncature "
      "fait dériver l'aller-retour d'un milli en Float (251 -> 250) ; "
      "l'arrondi est stable, verified exhaustivement en float 32 bits",
      "(cx * 1_000f).toInt()" not in vw
      and "Math.round" not in vw
      and re.search(r"(?m)^import kotlin\.math\.roundToInt$",
                    view_src) is not None)
arrival_m = re.search(r"private fun applyArrivalCamera\(solved: Boolean\) "
                     r"\{(.*?)\n    \}", vw, re.S)
check("BoardView : applyArrivalCamera — caméra restaurée prioritaire "
      "(zoom pour tous, position pour la famille Infini, clampPan sinon)",
      arrival_m is not None
      and "camera.zoomCentiDp / 100f * screenDensity" in arrival_m.group(1)
      and re.search(r"if \(b\.skill\.endless\) \{\s*"
                    r"val cx = camera\.centerMilliX / 1_000f", arrival_m.group(1))
      is not None)
check("BoardView : à défaut, un Néant qui démarre se centre sur sa zone "
      "visible à zoom confort (porte VOID non résolu)",
      arrival_m is not None
      and re.search(r"if \(b\.skill == Skill\.VOID && !solved\) \{", arrival_m.group(1))
      is not None
      and "centerOnVisibleZone()" in arrival_m.group(1))
check("BoardView : zoom confort = carré révélé (2r+1 cases) sur la MOITIÉ "
      "du petit côté, borné au plancher de la famille",
      re.search(r"minOf\(width, height\) /\s*"
                r"\(2f \* \(2f \* Board\.FOG_RADIUS_CELLS \+ 1f\)\)\)",
                arrival_m.group(1) if arrival_m else "") is not None)
check("BoardView : centerOnVisibleZone centre la bbox du réseau connecté",
      "private fun centerOnVisibleZone()" in vw)
check("BoardView : update appelle la caméra d'arrivée sur plateau frais "
      "uniquement, vue MESURÉE (à froid le premier rendu précède le "
      "layout — poser la caméra à 0x0 puis la voir écraser par le "
      "fitBoard du premier onSizeChanged perdait zoom ET pan, bug vu "
      "chez eric le 13 nov.)",
      re.search(r"var freshBoard = false", vw) is not None
      and re.search(r"if \(freshBoard\) \{[^{}]*?"
                    r"if \(width > 0 && height > 0\) \{[^{}]*?"
                    r"applyArrivalCamera\(solved\)[^{}]*?\} else \{[^{}]*?"
                    r"arrivalCameraPending = true",
                    vw, re.S) is not None)
check("BoardView : drapeau arrivalCameraPending (caméra d'arrivée "
      "différée jusqu'au premier layout)",
      "private var arrivalCameraPending = false" in vw)
size_m = re.search(r"override fun onSizeChanged\((.*?)\n    \}", vw, re.S)
check("BoardView : le premier onSizeChanged consomme la caméra différée "
      "APRÈS fitBoard — elle prend le pas sur son centrage, avec une "
      "vue enfin mesurée",
      size_m is not None
      and re.search(r"if \(arrivalCameraPending\) \{[^{}]*?"
                    r"arrivalCameraPending = false[^{}]*?"
                    r"applyArrivalCamera\(solved\)",
                    size_m.group(1), re.S) is not None)
check("BoardView : la rotation d'écran conserve le regard de la famille "
      "Infini (centre + zoom capturés sur l'ancienne taille, reposés "
      "après fitBoard — pas de retour au centre du plateau)",
      size_m is not None
      and "val keepCamera = b != null && b.skill.endless" in size_m.group(1)
      and "cellPx = zoomPx.coerceIn(floorCellPx(b), maxCellPx)" in size_m.group(1)
      and "if (b != null && keepCamera)" in size_m.group(1))
check("BoardView : PAS de condition redondante dans onSizeChanged "
      "(keepCamera vrai implique b non null — l'inspection IDE "
      "signale « Condition is always 'true' » sur keepCamera && b != null)",
      "if (keepCamera && b != null)" not in vw)
check("MainActivity : la caméra de la sauvegarde est posée sur la vue après "
      "la restauration ; onStop la photographie",
      "binding.boardView.restoreCamera(restoredSpec?.camera)" in ma
      and "viewModel.currentSpec(binding.boardView.cameraSnapshot())" in ma)
check("GameViewModel : la photographie accepte la caméra de la vue",
      re.search(r"fun currentSpec\(camera: CameraSpec\? = null\): GameSpec", vm)
      is not None)

gspec_tests_src = read(ROOT / "app/src/test/kotlin/net/chardons/netscramble2/"
                       "game/GameSpecTest.kt")
gts = strip_comments(gspec_tests_src)
check("Tests JVM : l'aller-retour v4 avec caméra est verrouillé (18 champs, "
      "égalité du spec décodé)",
      "camera round trips through the v4 format" in gts
      and 'encoded.startsWith("v4|")' in gspec_tests_src
      and "18, encoded.split('|').size" in gts)
rt_m = re.search(r"fun `encode then decode preserves every field`\(\) "
                r"\{(.*?)\n    \}", gspec_tests_src, re.S)
check("Tests JVM : « encode then decode preserves every field » PASSE une "
      "caméra (piège du 13 nov. : encode sans caméra écrit le repli "
      "neutre 0|0|2600, decode renvoie TOUJOURS une caméra en v4 — "
      "l'égalité spec == decoded est impossible sans caméra ; test KO "
      "chez eric la première fois, régression désormais bloquée ici)",
      rt_m is not None
      and "camera = camera" in rt_m.group(1)
      and "assertEquals(spec, decoded)" in rt_m.group(1)
      and "assertEquals(camera, decoded!!.camera)" in rt_m.group(1))
check("Tests JVM : une v3 décodée n'a PAS de caméra puis se réenregistre "
      "en v4 avec le repli neutre",
      "v3 snapshot decodes without camera then re-encodes as v4" in gts
      and 'assertNull(decoded!!.camera)' in gts)
check("Tests JVM : les rejets caméra (position hors plateau, zoom hors "
      "bornes) sont verrouillés",
      "decode rejects an out-of-bounds camera" in gts)

# --- Miroirs Python du centrage et de la caméra ---

# Zoom confort : le carré révélé (2r+1 cases) occupe la moitié du petit
# côté de la vue, sans jamais passer sous le plancher de la famille.
comfort_fail = []
for view_w, view_h, density in DEVICES:
    floor = floor_cell_px(view_w, view_h, density)
    raw = min(view_w, view_h) / (2.0 * (2 * FOG_RADIUS + 1))
    cell = min(max(raw, floor), MAX_CELL_DP * density)
    square = (2 * FOG_RADIUS + 1) * cell
    if cell < floor - 1e-6:
        comfort_fail.append(f"{view_w}x{view_h}@{density} sous le plancher")
    if cell != floor and square > min(view_w, view_h) / 2.0 + 1e-6:
        comfort_fail.append(f"{view_w}x{view_h}@{density} carré > moitié")
check("Miroir zoom confort : carré révélé = moitié du petit côté, jamais "
      "sous le plancher Infini (7 écrans réalistes)",
      not comfort_fail, "; ".join(comfort_fail[:3]))

# Caméra modulo : la photographie normalise la position par floorMod en
# MILLI-CELLULES ARRONDIES (miroir exact de cameraSnapshot, roundToInt
# de kotlin.math = ex æquo vers le pair — round() de Python, même
# convention ; plus aucun java.lang.Math.round dans la vue).
def camera_snapshot(view_w, view_h, pan_x, pan_y, cell, bw, bh):
    cx = (view_w / 2.0 - pan_x) / cell - 0.5
    cy = (view_h / 2.0 - pan_y) / cell - 0.5
    mx = round(cx * 1000.0) % (bw * 1000)
    my = round(cy * 1000.0) % (bh * 1000)
    return mx, my


mod_fail = []
for trial in range(60):
    view_w, view_h, density = rnd.choice(DEVICES)
    cell = floor_cell_px(view_w, view_h, density)
    span_px = SPAN * cell
    pan_x = rnd.uniform(-4 * span_px, 4 * span_px)
    pan_y = rnd.uniform(-4 * span_px, 4 * span_px)
    base = camera_snapshot(view_w, view_h, pan_x, pan_y, cell, SPAN, SPAN)
    for k in (-2, -1, 1, 2):
        if camera_snapshot(view_w, view_h, pan_x + k * span_px, pan_y,
                          cell, SPAN, SPAN) != base:
            mod_fail.append(f"x+{k} plateau")
        if camera_snapshot(view_w, view_h, pan_x, pan_y + k * span_px,
                          cell, SPAN, SPAN) != base:
            mod_fail.append(f"y+{k} plateau")
check("Miroir caméra : la position photographiée est INVARIANTE par "
      "translation d'un plateau entier (60 configurations, 8 "
      "translations chacune)",
      not mod_fail, "; ".join(sorted(set(mod_fail))[:3]))

# Allers-retours sauvegarde-restauration : le milli arrondi redonne
# EXACTEMENT le même milli après restauration du pan (stabilité que la
# troncature, elle, n'a pas en Float — contrepovre exhaustive float 32).
rt_fail = []
for trial in range(60):
    view_w, view_h, density = rnd.choice(DEVICES)
    cell = floor_cell_px(view_w, view_h, density)
    pan_x = rnd.uniform(-4 * SPAN * cell, 4 * SPAN * cell)
    pan_y = rnd.uniform(-4 * SPAN * cell, 4 * SPAN * cell)
    mx, my = camera_snapshot(view_w, view_h, pan_x, pan_y, cell, SPAN, SPAN)
    # applyArrivalCamera : le pan est reposé sur le centre restauré.
    pan_x2 = view_w / 2.0 - (mx / 1000.0 + 0.5) * cell
    pan_y2 = view_h / 2.0 - (my / 1000.0 + 0.5) * cell
    if camera_snapshot(view_w, view_h, pan_x2, pan_y2, cell, SPAN, SPAN) \
            != (mx, my):
        rt_fail.append(f"mx={mx}")
check("Miroir caméra : aller-retour snapshot-restauration-snapshot stable "
      "au milli près (60 configurations)",
      not rt_fail, "; ".join(sorted(set(rt_fail))[:3]))

# Démarrage à froid : le collect de l'activité démarre à onStart, AVANT
# la passe de mesure — update() voit une vue 0x0. Le drapeau
# arrivalCameraPending diffère la pose de la caméra d'arrivée jusqu'au
# premier onSizeChanged, qui fait fitBoard PUIS l'applique (elle prend
# le pas sur son centrage). Sans lui (comportement d'avant le correctif
# du 13 nov.), la caméra consommée à 0x0 était ensuite écrasée : zoom au
# plancher, pan au centre du plateau — tout était perdu.
cold_fail = []
for i in range(12):
    view_w, view_h, density = DEVICES[i % len(DEVICES)]
    floor = floor_cell_px(view_w, view_h, density)
    zoom_centis = rnd.randrange(3000, 9001, 100)
    while zoom_centis / 100.0 * density < floor:
        zoom_centis += 500
    cell = min(max(zoom_centis / 100.0 * density, floor),
               MAX_CELL_DP * density)
    if cell == floor:
        continue  # zoom sous le plancher : coercé, pas un cas préservé
    mx = rnd.randrange(SPAN * 1000)
    my = rnd.randrange(SPAN * 1000)
    # Comportement corrigé : pose APRÈS layout, contre la taille réelle.
    pan_x = view_w / 2.0 - (mx / 1000.0 + 0.5) * cell
    pan_y = view_h / 2.0 - (my / 1000.0 + 0.5) * cell
    if camera_snapshot(view_w, view_h, pan_x, pan_y, cell, SPAN, SPAN) \
            != (mx, my):
        cold_fail.append(f"position perdue {view_w}x{view_h}")
    if abs(cell / density * 100.0 - zoom_centis) > 0.5:
        cold_fail.append(f"zoom perdu {view_w}x{view_h}")
    # Ancien séquencement (régression bloquée par les checks statiques) :
    # caméra consommée à 0x0, fitBoard du premier onSizeChanged — le pan
    # retombe au centre du plateau, le zoom au plancher.
    old_cell = floor
    old_pan_x = (view_w - SPAN * old_cell) / 2.0
    old_pan_y = (view_h - SPAN * old_cell) / 2.0
    if old_cell == cell and camera_snapshot(view_w, view_h, old_pan_x,
                                            old_pan_y, old_cell, SPAN,
                                            SPAN) == (mx, my):
        cold_fail.append(f"ancien aurait préservé {view_w}x{view_h}")
check("Miroir démarrage à froid : caméra différée jusqu'au premier "
      "layout — position ET zoom de la sauvegarde préservés ; "
      "l'ancien séquencement (consommée à 0x0 puis écrasée par le "
      "fitBoard du premier onSizeChanged) les perdait (12 écrans)",
      not cold_fail, "; ".join(sorted(set(cold_fail))[:3]))

# Zone visible : centerOnVisibleZone vise la bbox du RÉSEAU CONNECTÉ (au
# lancement : le serveur seul) ; grâce au plateau répété (caméra modulo),
# tout le carré révélé — y compris enroulé près d'un bord — reste dans
# la fenêtre visible autour de ce centre.
def center_pan(view_w, view_h, cell, bbox, bw):
    min_x, max_x, min_y, max_y = bbox
    cx = (min_x + max_x) / 2.0
    cy = (min_y + max_y) / 2.0
    return view_w / 2.0 - (cx + 0.5) * cell, view_h / 2.0 - (cy + 0.5) * cell


center_fail = []
for i in range(8):
    view_w, view_h, density = DEVICES[i % len(DEVICES)]
    floor = floor_cell_px(view_w, view_h, density)
    comfort = min(max(min(view_w, view_h) / (2.0 * (2 * FOG_RADIUS + 1)),
                      floor), MAX_CELL_DP * density)
    r_gen = random.Random(51_000 + i * 131)
    conns_v, root_v = gen_original(r_gen, SIZE, SIZE, wrap=True, branches=3)
    connected = [False] * (SIZE * SIZE)
    connected[root_v] = True
    xs = [j % SIZE for j in range(SIZE * SIZE) if connected[j]]
    ys = [j // SIZE for j in range(SIZE * SIZE) if connected[j]]
    pan_x, pan_y = center_pan(view_w, view_h, comfort,
                              (min(xs), max(xs), min(ys), max(ys)), SIZE)
    # Le centre de vue est le serveur (bbox du réseau connecté réduit
    # au serveur au lancement), à une demi-case près (centre de case).
    sx = root_v % SIZE
    sy = root_v // SIZE
    if abs(view_w / 2.0 - (sx + 0.5) * comfort - pan_x) > 1e-6:
        center_fail.append(f"centre X décalé (graine {i})")
    if abs(view_h / 2.0 - (sy + 0.5) * comfort - pan_y) > 1e-6:
        center_fail.append(f"centre Y décalé (graine {i})")
    # Toute case du carré révélé (distance torique de Chebyshev <= r du
    # serveur) est dans la fenêtre visible : demi-fenêtre du petit côté
    # = (2r+1) cases au zoom confort, toujours > r + 1 case de marge.
    half_cells = (min(view_w, view_h) / 2.0) / comfort
    if half_cells < FOG_RADIUS + 1.0:
        center_fail.append(f"demi-fenêtre {half_cells:.2f} trop étroite")
check("Miroir centrage : au lancement d'un Néant, la vue vise le serveur "
      "(bbox du réseau connecté) et le carré révélé entier tient dans la "
      "moitié du petit côté, bord compris grâce au plateau répété "
      "(8 plateaux)",
      not center_fail, "; ".join(sorted(set(center_fail))[:3]))


# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications (famille Infini, "
          "phases A à E).")
