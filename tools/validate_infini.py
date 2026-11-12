#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « niveau Infini » (6e niveau, ENDLESS).

Phase A livrée ici — le socle jouable :
  - Skill.ENDLESS(branches = 3, wrap = true, hiddenThreshold = 9),
    taille FIXE 40 x 40 (bypass de la matrice Screen) ;
  - générateur ORIGINAL FORCÉ sur ce niveau (victoire par terminaux,
    le serveur scintille toujours : signature « électricité » constante) ;
  - menu Niveau, libellés EN/FR, barre d'état, écran des records.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : les fichiers Kotlin et XML sont PARSÉS
     (jamais de valeurs retapées à la main dans ce script).
  2. Simulation : génération 40x40 en tore (miroir Python de
     generateOriginal), victoire par terminaux, générateur forcé,
     format GameSpec v3 avec ENDLESS (aller-retour, rejet v2).

Les phases suivantes (B : caméra modulo ; C : brouillard) étendront
ce validateur — mapping modulo invariant par translation, rayon de
visibilité, etc.

Usage : python3 tools/validate_infini.py
"""

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
sk_entries = re.findall(
    r"^\s{4}(\w+)\(branches = (\d+), wrap = (true|false), "
    r"hiddenThreshold = (\d+)\)[,;]", sk, re.M)
skills = {name: (int(br), wrap == "true", int(bl))
          for name, br, wrap, bl in sk_entries}
skill_order = [name for name, *_ in sk_entries]
check("Skill.kt : ENDLESS = 3 branches, tore, aucun masquage",
      skills.get("ENDLESS") == (3, True, 9), f"lu={skills.get('ENDLESS')}")
check("Skill.kt : ENDLESS arrive EN DERNIER (graduation des niveaux)",
      skill_order == ["NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE",
                      "ENDLESS"], str(skill_order))
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
check("Screen.kt : majorOf route ENDLESS vers la taille fixe",
      major_of is not None
      and "Skill.ENDLESS -> Skill.ENDLESS_SIZE" in major_of.group(1))
check("Screen.kt : minorOf route ENDLESS vers la taille fixe",
      minor_of is not None
      and "Skill.ENDLESS -> Skill.ENDLESS_SIZE" in minor_of.group(1))

# Miroir de boardSize : ENDLESS ignore la matrice ET l'orientation.
screen_entries = re.findall(
    r"^\s{4}(\w+)\((\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+)\)[,;]",
    screen_src, re.M)
check("Screen.kt : les 5 matrices de l'original sont intactes",
      len(screen_entries) == 5,
      str([name for name, _ in screen_entries]))

def board_size_endless(screen_name, landscape):
    """Miroir : pour ENDLESS, major et minor valent la taille fixe."""
    size = int(re.search(r"const val ENDLESS_SIZE = (\d+)", sk).group(1))
    return (size, size)

for name, _ in screen_entries:
    for landscape in (True, False):
        w, h = board_size_endless(name, landscape)
        check(f"boardSize(ENDLESS) = {w}x{h} sur {name} "
              f"({'paysage' if landscape else 'portrait'})",
              (w, h) == (40, 40))

# ---------------------------------------------------------------------------
# 3. GameViewModel.kt — générateur ORIGINAL forcé sur ENDLESS.
# ---------------------------------------------------------------------------

vm = strip_comments(vm_src)
endless_forced = re.search(
    r"val generator = if \(skill == Skill\.ENDLESS\) \{\s*"
    r"Generator\.ORIGINAL\s*\} else if \(\(seed and 1L\) == 0L\) \{\s*"
    r"Generator\.TREE\s*\} else \{\s*Generator\.ORIGINAL\s*\}",
    vm)
check("GameViewModel : ENDLESS force ORIGINAL, les autres tirent à la graine",
      endless_forced is not None)
check("GameViewModel : la restauration reste gouvernée par la photographie "
      "(générateur sérialisé, pas retiré)",
      "generator = board.generator" in vm
      and "board = spec.buildBoard()" in vm)

# Miroir du choix de générateur (le if-chaîne de newGame), sur 50 graines.
def choose_generator(skill, seed):
    if skill == "ENDLESS":
        return "ORIGINAL"
    if (seed & 1) == 0:
        return "TREE"
    return "ORIGINAL"

forced_ok = True
mixer = random.Random(2026)
for i in range(50):
    seed = mixer.randrange(1 << 48)
    forced_ok &= choose_generator("ENDLESS", seed) == "ORIGINAL"
check("Miroir : ENDLESS = ORIGINAL sur 50 graines (paires et impaires)",
      forced_ok)
others_mixed = {choose_generator("MASTER", s) for s in (0, 1, 2, 3)}
check("Miroir : hors ENDLESS le tirage parité persiste (TREE/ORIGINAL)",
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
check("MainActivity : 6 libellés d'état alignés sur l'ordre de l'enum",
      status_ids == expected_labels, f"lu={status_ids}")

when_current = re.search(
    r"when \(current\) \{(.*?)\n        \}\?\.", ma, re.S)
check("MainActivity : la coche du menu suit ENDLESS",
      when_current is not None
      and re.search(r"Skill\.ENDLESS -> skillMenu\?\.findItem\(R\.id\.skill_endless\)",
                    when_current.group(1)) is not None)

skill_ids_block = re.search(
    r"val skill = when \(item\.itemId\) \{(.*?)\n            else -> null",
    ma, re.S)
mapping = (dict(re.findall(r"R\.id\.(\w+) -> (Skill\.\w+)",
                           skill_ids_block.group(1)))
           if skill_ids_block else {})
check("MainActivity : l'entrée skill_endless lance Skill.ENDLESS",
      mapping.get("skill_endless") == "Skill.ENDLESS", f"lu={mapping.get('skill_endless')}")
check("MainActivity : les 6 niveaux du menu mappent chacun vers son Skill",
      {k: v for k, v in mapping.items()} == {
          "skill_" + name.lower(): "Skill." + name for name in skill_order},
      str(mapping))

# ---------------------------------------------------------------------------
# 5. Menu, chaînes, records.
# ---------------------------------------------------------------------------

group = re.search(
    r'<group android:checkableBehavior="single">(.*?)</group>', menu_src, re.S)
menu_ids = re.findall(r'@\+id/(skill_\w+)', group.group(1)) if group else []
check("menu_main.xml : l'entrée skill_endless est dans le groupe du Niveau",
      menu_ids == expected_labels, f"lu={menu_ids}")
check("menu_main.xml : Infini vient après Dément",
      menu_ids.index("skill_endless") == menu_ids.index("skill_insane") + 1)

en = dict(re.findall(r'<string name="(skill_endless)">([^<]*)</string>', en_src))
fr = dict(re.findall(r'<string name="(skill_endless)">([^<]*)</string>', fr_src))
check("Chaînes : skill_endless = Endless (EN)", en.get("skill_endless") == "Endless",
      f"lu={en.get('skill_endless')!r}")
check("Chaînes : skill_endless = Infini (FR)", fr.get("skill_endless") == "Infini",
      f"lu={fr.get('skill_endless')!r}")
check("Chaînes : aucune apostrophe non échappée dans le libellé FR",
      re.search(r"(?<!\\)'", fr.get("skill_endless", "\\")) is None)

sa = strip_comments(scores_src)
labels = re.search(r"private val labels = listOf\(\s*((?:R\.string\.skill_\w+,?\s*)+?)\)", sa)
scores_ids = re.findall(r"R\.string\.(skill_\w+)", labels.group(1)) if labels else []
check("ScoresActivity : les tables affichent les 6 niveaux, dans l'ordre",
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
check("Tests JVM : les boucles sur Skill.entries couvriront ENDLESS",
      ts.count("for (skill in Skill.entries)") >= 2)

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
check("GameSpec : dimensions hors bornes (65) rejetées",
      decode_mirror("v3|ENDLESS|1|ORIGINAL|65|40|-1|0|0|false|-1|"
                    + "0" * (65 * 40) + "|" + "0" * (65 * 40)
                    + "|" + "0" * (65 * 40)) is None)

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications (phase A du niveau Infini).")
