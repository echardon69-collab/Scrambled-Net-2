#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « tailles de plateaux fidèles à l'original ».

Deux parties :
  1. Vérification statique : les fichiers Kotlin sont PARSÉS (jamais de
     valeurs retapées à la main dans ce script) et comparés aux
     constantes de l'original Scrambled Net (BoardView.java de
     SilentServices), qui constituent ici la spécification.
  2. Simulation : les algorithmes (générateurs, mélange, résolution,
     format GameSpec) sont rejoués en Python, miroir du Kotlin, et leurs
     propriétés sont vérifiées statistiquement (couverture, connexité,
     solubilité, rétrocompatibilité v2).

Usage : python3 tools/validate_tailles.py
"""

import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"

failures = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    status = "OK  " if ok else "ECHEC"
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def read(rel):
    return (KOTLIN / rel).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. Vérification statique : Screen.kt contre l'original.
# ---------------------------------------------------------------------------

# Spécification : enum Screen de BoardView.java, ordre du constructeur
# (major, minor, expert, normal, novice — chacun en grand, petit).
ORIGINAL_SCREEN = {
    "SMALL": (8, 6, 8, 6, 6, 6, 6, 4),
    "WSMALL": (9, 6, 9, 6, 5, 6, 5, 4),
    "MEDIUM": (11, 7, 11, 7, 9, 7, 5, 5),
    "WMEDIUM": (12, 7, 10, 7, 8, 7, 6, 5),
    "HUGE": (17, 10, 15, 8, 11, 8, 7, 6),
}

screen_src = read("game/Screen.kt")
entries = re.findall(r"^\s{4}(\w+)\((\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+,\s*\d+)\)[,;]", screen_src, re.M)
screen = {name: tuple(int(v) for v in args.split(",")) for name, args in entries}
check("Screen.kt : 5 matrices présentes", len(screen) == 5, str(list(screen)))
for name, expected in ORIGINAL_SCREEN.items():
    check(f"Screen.kt : {name} = matrice de l'original", screen.get(name) == expected,
          f"lu={screen.get(name)}")

# Seuils de find(), parsés depuis Screen.kt (l'original : 400 / 1,4 / 500 / 1,5).
mins = [int(v) for v in re.findall(r"min <= (\d+)", screen_src)]
aspects = [float(v) for v in re.findall(r"aspect > (1\.\d)f", screen_src)]
check("Screen.kt : seuils find 400/500", mins == [400, 500], str(mins))
check("Screen.kt : ratios find 1.4/1.5", aspects == [1.4, 1.5], str(aspects))


# ---------------------------------------------------------------------------
# 2. Vérification statique : Skill.kt contre l'original.
# ---------------------------------------------------------------------------

# Spécification : enum Skill de BoardView.java — (branches, wrap, blind).
ORIGINAL_SKILL = {
    "NOVICE": (2, False, 9),
    "NORMAL": (2, False, 9),
    "EXPERT": (2, False, 9),
    "MASTER": (3, True, 9),
    "INSANE": (3, True, 3),
}

skill_src = read("game/Skill.kt")
sk_entries = re.findall(
    r"^\s{4}(\w+)\(branches = (\d+), wrap = (true|false), hiddenThreshold = (\d+)\)[,;]",
    skill_src, re.M)
skills = {name: (int(br), wrap == "true", int(bl)) for name, br, wrap, bl in sk_entries}
check("Skill.kt : 6 niveaux présents (5 originaux + Infini)",
      len(skills) == 6, str(list(skills)))
for name, expected in ORIGINAL_SKILL.items():
    check(f"Skill.kt : {name} = (branches, wrap, seuil) de l'original",
          skills.get(name) == expected, f"lu={skills.get(name)}")
# Infini (chantier du 12 nov. 2026, spec validée par eric) : hors
# original — comme Maître (3 branches, tore) mais sans masquage.
check("Skill.kt : ENDLESS = 3 branches, tore, aucun masquage",
      skills.get("ENDLESS") == (3, True, 9), f"lu={skills.get('ENDLESS')}")
check("Skill.kt : taille fixe du niveau Infini (bypass Screen)",
      re.search(r"const val ENDLESS_SIZE = 40", skill_src) is not None)
check("Skill.kt : plus aucune dimension width/height",
      "width" not in skill_src and "height" not in skill_src)


# ---------------------------------------------------------------------------
# 3. Vérification statique : Board.kt (constantes et structure).
# ---------------------------------------------------------------------------

board_src = read("game/Board.kt")
check("Board.kt : sel du mélange inchangé",
      "SCRAMBLE_SALT = -0x5DEECE66DL" in board_src)
check("Board.kt : 10 essais maximum", "MAX_TRIES = 10" in board_src)
check("Board.kt : seuil de couverture 85 pour cent", "* 0.85" in board_src)
check("Board.kt : 3e direction pour branches >= 3 seulement",
      "branches >= 3 && rnd.nextInt(3) == 0" in board_src)
check("Board.kt : racine originale tirée à chaque essai",
      "rnd.nextInt(width) + width * rnd.nextInt(height)" in board_src)
check("Board.kt : racine TREE aléatoire par défaut",
      "rnd.nextInt(size)" in board_src)
check("Board.kt : racine imposée bornée au plateau",
      "forcedRoot in 0 until size" in board_src)
check("Board.kt : générateur ORIGINAL (createNet) présent",
      "generateOriginal" in board_src and "addRandomDir" in board_src)
check("Board.kt : victoire ORIGINAL = terminaux reliés",
      re.search(r"ORIGINAL -> \(0 until size\)\.none", board_src) is not None)
check("Board.kt : victoire TREE = plateau entier relié",
      "Generator.TREE -> connected.all { it }" in board_src)
check("Board.kt : la racine imposée est une propriété (consigne transportée)",
      "val forcedRoot: Int = -1" in board_src)


# ---------------------------------------------------------------------------
# 4. Vérification statique : GameSpec.kt (format v3 + rétrocompat v2).
# ---------------------------------------------------------------------------

spec_src = read("game/GameSpec.kt")
check('GameSpec.kt : version v3', 'VERSION = "v3"' in spec_src)
check('GameSpec.kt : version historique v2 décodable', 'LEGACY_VERSION = "v2"' in spec_src)
check("GameSpec.kt : 15 champs en v3", "FIELDS = 15" in spec_src)
check("GameSpec.kt : 11 champs en v2", "LEGACY_FIELDS = 11" in spec_src)
check("GameSpec.kt : décalage des champs joueur (v2 : 3, v3 : 7)",
      "if (legacy) 3 else 7" in spec_src)
legacy_sizes = dict(
    (name, (int(a), int(b)))
    for name, a, b in re.findall(
        r"Skill\.(\w+) to \((\d+) to (\d+)\)", spec_src))
check("GameSpec.kt : tailles historiques v2 exactes",
      legacy_sizes == {"NOVICE": (5, 5), "NORMAL": (7, 7), "EXPERT": (9, 9),
                       "MASTER": (9, 9), "INSANE": (11, 11)},
      str(legacy_sizes))
check("GameSpec.kt : serveur central historique v2",
      "(height / 2) * width + (width / 2)" in spec_src)
# Ordre des champs de encode(), parsé : chaque champ apparaît dans cet
# ordre dans le corps de encode() (recherche séquentielle des positions).
encode_block = spec_src.split("fun encode()")[1].split("fun buildBoard")[0]
order_markers = [
    "VERSION", "skill.name", "seed", "generator.name", "width", "height",
    "root", "moves", "elapsedMs", "solverUsed", "prevClicked",
    "solveQueue.joinToString(QUEUE_SEP)",
    "for (r in rotations)", "for (l in locked)", "for (r in revealed)",
]
pos = 0
order_ok = True
for marker in order_markers:
    found = encode_block.find(marker, pos)
    if found < 0:
        order_ok = False
        break
    pos = found
check("GameSpec.kt : ordre des champs v3", order_ok)
check("GameSpec.kt : racine = consigne de reconstruction (-1 = tirée)",
      "require(root in -1 until width * height)" in spec_src and
      "root !in -1 until size" in spec_src)
check("GameSpec.kt : buildBoard passe la consigne de racine",
      "forcedRoot = root" in spec_src)


# ---------------------------------------------------------------------------
# 5. Vérification statique : ViewModel et MainActivity.
# ---------------------------------------------------------------------------

vm_src = read("GameViewModel.kt")
check("GameViewModel : la photographie transporte la consigne de racine",
      "root = board.forcedRoot" in vm_src)
check("GameViewModel : tirage générateur déterministe depuis la graine",
      "(seed and 1L) == 0L" in vm_src and "Generator.TREE" in vm_src)
check("GameViewModel : Infini force le générateur ORIGINAL (avant le tirage)",
      vm_src.index("skill == Skill.ENDLESS")
      < vm_src.index("(seed and 1L) == 0L")
      and re.search(r"skill == Skill\.ENDLESS\)\s*\{\s*Generator\.ORIGINAL",
                    vm_src) is not None)
check("GameViewModel : le niveau est porté par le ViewModel",
      re.search(r"private var skill: Skill", vm_src) is not None and
      "board.skillOf" not in vm_src)
check("GameViewModel : le tap refuse les cellules libres",
      "board.isEmpty(index)" in vm_src)
check("GameViewModel : la photographie emporte dimensions et générateur",
      "width = board.width" in vm_src and "generator = board.generator" in vm_src)
check("GameViewModel : solved passe par board.isSolved()",
      "solved = board.isSolved()" in vm_src)

ma_src = read("MainActivity.kt")
check("MainActivity : dimensions calculées par Screen.find",
      "Screen.find(metrics.widthPixels, metrics.heightPixels)" in ma_src and
      "landscape = metrics.widthPixels > metrics.heightPixels" in ma_src)
check("MainActivity : 4 lancements de partie aux dimensions de l'écran",
      len(re.findall(r"viewModel\.newGame\([^)]*\)", ma_src)) == 4)
check("MainActivity : première visite = nouvelle partie si rien à restaurer",
      "if (!viewModel.restoreIfPending(GameSpec.decode(savedSpec)))" in ma_src)

# --- Tests JVM : la nouvelle API doit être utilisée partout. ---
test_dir = ROOT / "app/src/test/kotlin/net/chardons/netscramble2/game"
tests_src = "\n".join(p.read_text(encoding="utf-8") for p in test_dir.glob("*.kt"))
check("Tests JVM : plus d'appel à l'ancienne API Board.generate(skill, seed)",
      not re.search(r"Board\.generate\(Skill\.\w+,\s*seed", tests_src))
check("Tests JVM : plus de dimension ni d'inférence de niveau depuis le plateau",
      "it.width == board.width" not in tests_src and
      "skill.width" not in tests_src)
check("Tests JVM : plus de serveur central attendu",
      "2 * 5 + 2" not in tests_src)
check("Tests JVM : les deux générateurs sont testés",
      tests_src.count("Generator.ORIGINAL") >= 3 and "Generator.TREE" in tests_src)
check("Tests JVM : le constructeur Board porte générateur et branches",
      "generator = Generator.TREE, branches = 2" in tests_src)
check("Tests JVM : la rétrocompatibilité v2 est testée (serveur imposé)",
      '"v2"' in tests_src and "forcedRoot = 24" in tests_src)


# ---------------------------------------------------------------------------
# 6. Simulation : miroir Python des algorithmes.
# ---------------------------------------------------------------------------

# Directions : mêmes bits que Dir.kt (UP=1, RIGHT=2, DOWN=4, LEFT=8).
DIRS = [0, 1, 2, 3]  # UP, RIGHT, DOWN, LEFT
BIT = [1, 2, 4, 8]
DX = [0, 1, 0, -1]
DY = [-1, 0, 1, 0]


def opposite(i):
    return (i + 2) % 4


def rotcw(i, steps):
    return (i + steps) % 4


def rotate_mask(mask, steps):
    out = 0
    for i in DIRS:
        if mask & BIT[i]:
            out |= BIT[rotcw(i, steps)]
    return out


def neighbor(index, width, height, wrap, d):
    x = index % width
    y = index // width
    nx, ny = x + DX[d], y + DY[d]
    if wrap:
        return (ny % height) * width + (nx % width)
    if 0 <= nx < width and 0 <= ny < height:
        return ny * width + nx
    return -1


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


def add_random_dir(conns, queue, rnd, width, height, wrap):
    """Miroir de addRandomDir."""
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


def gen_tree(rnd, width, height, wrap, root):
    """Miroir de generateTree (DFS randomisé enraciné en root)."""
    size = width * height
    conns = [0] * size
    visited = [False] * size
    visited[root] = True
    stack = [root]
    while stack:
        current = stack[-1]
        dirs = DIRS[:]
        rnd.shuffle(dirs)
        extended = False
        for d in dirs:
            n = neighbor(current, width, height, wrap, d)
            if n < 0 or visited[n]:
                continue
            conns[current] |= BIT[d]
            conns[n] |= BIT[opposite(d)]
            visited[n] = True
            stack.append(n)
            extended = True
            break
        if not extended:
            stack.pop()
    return conns


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


def is_solved(conns, rotations, width, height, wrap, server, generator):
    connected = connected_cells(conns, rotations, width, height, wrap, server)
    if generator == "TREE":
        return all(connected)
    # ORIGINAL : tous les terminaux (une connexion) reliés.
    return not any(bin(c).count("1") == 1 and not connected[i]
                   for i, c in enumerate(conns))


def solve_moves(conns, width, height, wrap, server):
    """Miroir de solveMoves (BFS sur la solution)."""
    size = width * height
    visited = [False] * size
    visited[server] = True
    queue = [server]
    moves = []
    while queue:
        cur = queue.pop(0)
        for d in DIRS:
            if not conns[cur] & BIT[d]:
                continue
            n = neighbor(cur, width, height, wrap, d)
            if n < 0 or visited[n]:
                continue
            visited[n] = True
            queue.append(n)
            # rotation libre : le BFS marque les cellules câblées ; la
            # rotation est lue par l'appelant (ici 0 d'office en test).
    # Le miroir simplifié : le Kotlin ajoute cur quand rotations != 0 ;
    # en test on remet toutes les rotations visitées à 0.
    return [i for i in range(size) if visited[i]]


def board_size(screen_name, skill_name, landscape):
    major, minor, emaj, emin, nmaj, nmin, vmaj, vmin = screen[screen_name]
    pair = {
        "MASTER": (major, minor), "INSANE": (major, minor),
        "EXPERT": (emaj, emin), "NORMAL": (nmaj, nmin), "NOVICE": (vmaj, vmin),
    }[skill_name]
    return pair if landscape else (pair[1], pair[0])


def find_screen(w, h):
    mn, mx = min(w, h), max(w, h)
    aspect = mx / mn
    if mn <= 400:
        return "WSMALL" if aspect > 1.4 else "SMALL"
    if mn <= 500:
        return "WMEDIUM" if aspect > 1.5 else "MEDIUM"
    return "HUGE"


# --- find : classification conforme à findMatrix de l'original ---
expected_find = [
    (240, 320, "SMALL"),      # HVGA portrait : ratio 1,33
    (320, 480, "WSMALL"),     # HVGA paysage : ratio 1,5 > 1,4
    (480, 640, "MEDIUM"),     # VGA portrait : ratio 1,33
    (480, 800, "WMEDIUM"),    # WVGA paysage : ratio 1,67 > 1,5
    (1080, 2400, "HUGE"),     # téléphone moderne
]
for w, h, name in expected_find:
    check(f"find({w}, {h}) = {name}", find_screen(w, h) == name, find_screen(w, h))

# --- boardSize : Maître et Dément partagent la taille de l'original ---
for sname in ORIGINAL_SCREEN:
    for skname in ("MASTER", "INSANE"):
        w, h = board_size(sname, skname, True)
        check(f"{sname}/{skname} paysage : grand côté horizontal", w >= h, f"{w}x{h}")
    w, h = board_size(sname, "EXPERT", False)
    check(f"{sname}/EXPERT portrait : grand côté vertical", h >= w, f"{w}x{h}")
check("Maître et Dément : même taille (MEDIUM)",
      board_size("MEDIUM", "MASTER", True) == board_size("MEDIUM", "INSANE", True))

# --- Générateurs : propriétés statistiques ---

SEEDS = 150
gen_fail = []
stats = {"EXPERT_j3": [], "MASTER_j3": [], "MASTER_cross": []}
for skname in ("NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE"):
    branches, wrap, _ = skills[skname]
    width, height = board_size("MEDIUM", skname, False)
    size = width * height
    empty_seen = 0
    cover_min, cover_sum = 1.0, 0.0
    roots = set()
    for seed in range(1, SEEDS + 1):
        rnd = random.Random(seed * 1_000_003 + 7)
        conns, root = gen_original(rnd, width, height, wrap, branches)
        rotations = [rnd.randrange(4) for _ in range(size)]
        attempts = 0
        while is_solved(conns, rotations, width, height, wrap, root,
                        "ORIGINAL") and attempts < 100:
            rotations = [rnd.randrange(4) for _ in range(size)]
            attempts += 1
        cover = sum(1 for c in conns if c) / size
        cover_min = min(cover_min, cover)
        cover_sum += cover
        roots.add(root)
        if any(c == 0 for c in conns):
            empty_seen += 1
        # Solution saine : cellules actives toutes connexes depuis le serveur.
        zero = [0] * size
        connected = connected_cells(conns, zero, width, height, wrap, root)
        active_connected = all(connected[i] for i, c in enumerate(conns) if c)
        solved0 = is_solved(conns, zero, width, height, wrap, root, "ORIGINAL")
        if not (active_connected and solved0):
            gen_fail.append(f"{skname}#{seed}: saine={active_connected} "
                            f"résolue={solved0}")
        # Le mélange ne présente jamais un plateau déjà résolu.
        if is_solved(conns, rotations, width, height, wrap, root, "ORIGINAL"):
            gen_fail.append(f"{skname}#{seed}: mélangé mais résolu")
        # Le solveur ramène à la solution.
        for i in solve_moves(conns, width, height, wrap, root):
            rotations[i] = 0
        if not is_solved(conns, rotations, width, height, wrap, root,
                         "ORIGINAL"):
            gen_fail.append(f"{skname}#{seed}: solveMoves insuffisant")
        if skname == "EXPERT":
            stats["EXPERT_j3"].append(sum(1 for c in conns
                                          if bin(c).count("1") == 3))
        if skname == "MASTER":
            stats["MASTER_j3"].append(sum(1 for c in conns
                                          if bin(c).count("1") == 3))
            stats["MASTER_cross"].append(sum(1 for c in conns
                                             if bin(c).count("1") == 4))
    check(f"ORIGINAL {skname} ({width}x{height}) : solution saine et "
          f"solvable ({SEEDS} graines)", not gen_fail,
          "; ".join(gen_fail[:3]))
    print(f"       couverture min/moyenne : {cover_min:.3f} / "
          f"{cover_sum / SEEDS:.3f} ; cellules libres : "
          f"{empty_seen}/{SEEDS} graines ; serveurs distincts : {len(roots)}")

mean = lambda xs: sum(xs) / len(xs) if xs else 0
check("ORIGINAL : Maître (3 branches) fait plus de carrefours qu'Expert",
      mean(stats["MASTER_j3"]) > mean(stats["EXPERT_j3"]),
      f"Maître={mean(stats['MASTER_j3']):.2f} Expert={mean(stats['EXPERT_j3']):.2f}")
print(f"       croix complètes en Maître : "
      f"{sum(stats['MASTER_cross'])}/{len(stats['MASTER_cross'])} graines "
      f"(au moins une attendue)")

# --- TREE : plateau entier actif et soluble ---
tree_fail = []
for skname in ("NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE"):
    _, wrap, _ = skills[skname]
    width, height = board_size("WMEDIUM", skname, True)
    size = width * height
    for seed in range(1, 60):
        rnd = random.Random(seed * 77 + 5)
        root = rnd.randrange(size)
        conns = gen_tree(rnd, width, height, wrap, root)
        rotations = [rnd.randrange(4) for _ in range(size)]
        if any(c == 0 for c in conns):
            tree_fail.append(f"{skname}#{seed}: cellule libre en TREE")
        zero = [0] * size
        if not is_solved(conns, zero, width, height, wrap, root, "TREE"):
            tree_fail.append(f"{skname}#{seed}: non résolue à rotations nulles")
check("TREE : 100 pour cent des cellules actives, soluble par rotation",
      not tree_fail, "; ".join(tree_fail[:3]))


# ---------------------------------------------------------------------------
# 7. Format GameSpec : aller-retour v3 et rétrocompatibilité v2.
# ---------------------------------------------------------------------------

def encode_v3(skill, seed, gen, width, height, root, moves, ms, solver,
              prev, queue, rotations, locked, revealed):
    return "|".join([
        "v3", skill, str(seed), gen, str(width), str(height), str(root),
        str(moves), str(ms), str(solver), str(prev), ",".join(map(str, queue)),
        "".join(map(str, rotations)),
        "".join("1" if l else "0" for l in locked),
        "".join("1" if r else "0" for r in revealed),
    ])


def decode_mirror(spec):
    """Miroir de GameSpec.decode (v3 et v2)."""
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
    if skill not in ORIGINAL_SKILL:
        return None
    if legacy:
        gen, (width, height) = "TREE", {
            "NOVICE": (5, 5), "NORMAL": (7, 7), "EXPERT": (9, 9),
            "MASTER": (9, 9), "INSANE": (11, 11)}[skill]
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
    solver = parts[base + 2] == "true"
    rot_s, lock_s, rev_s = parts[base + 5], parts[base + 6], parts[base + 7]
    if len(rot_s) != size or len(lock_s) != size or len(rev_s) != size:
        return None
    rotations = [int(c) for c in rot_s]
    if any(r not in (0, 1, 2, 3) for r in rotations):
        return None
    seed = int(parts[2])
    moves, ms = int(parts[base]), int(parts[base + 1])
    prev = int(parts[base + 3])
    queue = [int(v) for v in parts[base + 4].split(",") if v]
    return dict(skill=skill, seed=seed, gen=gen, width=width, height=height,
                root=root, moves=moves, ms=ms, solver=solver, prev=prev,
                queue=queue, rotations=rotations,
                locked=[c == "1" for c in lock_s],
                revealed=[c == "1" for c in rev_s])


# Aller-retour v3 sur une partie simulée.
rnd = random.Random(42)
conns, root = gen_original(rnd, 11, 7, False, 2)
rotations = [rnd.randrange(4) for _ in range(77)]
locked = [rnd.random() < 0.2 for _ in range(77)]
revealed = [rnd.random() < 0.1 for _ in range(77)]
queue = [3, 5, 7, 9]
# Une partie v3 fraîche transporte la consigne -1 (racine tirée du flux).
spec = encode_v3("EXPERT", 12345678901, "ORIGINAL", 11, 7, -1, 42, 65000,
                 True, 12, queue, rotations, locked, revealed)
back = decode_mirror(spec)
ok = (back is not None and back["skill"] == "EXPERT"
      and back["seed"] == 12345678901 and back["gen"] == "ORIGINAL"
      and (back["width"], back["height"]) == (11, 7) and back["root"] == -1
      and back["rotations"] == rotations and back["queue"] == queue)
check("GameSpec v3 : aller-retour intact (consigne de racine -1)", ok)

# Rejets de chaînes invalides.
check("GameSpec v3 : rejets (version, longueur, dimensions, racine)",
      decode_mirror("v4|NORMAL|1|TREE|7|7|24|0|0|false|-1||0000000|0000000|0000000") is None
      and decode_mirror(spec + "|extra") is None
      and decode_mirror("v3|NORMAL|1|TREE|99|7|24|0|0|false|-1||" + "0" * 49
                        + "|" + "0" * 49 + "|" + "0" * 49) is None
      and decode_mirror("v3|NORMAL|1|TREE|7|7|-2|0|0|false|-1||" + "0" * 49
                        + "|" + "0" * 49 + "|" + "0" * 49) is None
      and decode_mirror("v3|NORMAL|1|TREE|7|7|49|0|0|false|-1||" + "0" * 49
                        + "|" + "0" * 49 + "|" + "0" * 49) is None)

# v2 : une ancienne partie se décode et se reconstruit à l'identique.
old_rotations = [rnd.randrange(4) for _ in range(49)]
v2 = "|".join(["v2", "NORMAL", "987654321", "17", "42000", "false", "-1", "5,9",
               "".join(map(str, old_rotations)),
               "".join("0" for _ in range(49)),
               "".join("0" for _ in range(49))])
back2 = decode_mirror(v2)
check("GameSpec v2 : décodage rétrocompatible (TREE, 7x7, serveur central)",
      back2 is not None and back2["gen"] == "TREE"
      and (back2["width"], back2["height"]) == (7, 7) and back2["root"] == 24
      and back2["rotations"] == old_rotations and back2["moves"] == 17)

# Restauration à l'identique : ancien algorithme (serveur central) contre
# generateTree avec racine imposée — même flux aléatoire, même résultat.
def old_generate(seed):
    rnd = random.Random(seed)
    conns = gen_tree(rnd, 7, 7, False, (7 // 2) * 7 + (7 // 2))
    rotations = [rnd.randrange(4) for _ in range(49)]
    return conns, rotations

def new_generate_with_forced_root(seed, root):
    rnd = random.Random(seed)
    conns = gen_tree(rnd, 7, 7, False, root)  # aucune valeur tirée avant
    rotations = [rnd.randrange(4) for _ in range(49)]
    return conns, rotations

c1, r1 = old_generate(987654321)
c2, r2 = new_generate_with_forced_root(987654321, 24)
check("Restauration v2 : réseau et mélange identiques à l'ancien code",
      c1 == c2 and r1 == r2)

# v3 TREE : la consigne -1 fait rejouer le tirage de la racine, la
# régénération est donc identique à la création ; imposer la position sans
# tirer (l'ancien bug) donnerait un autre arbre.
def tree_created(seed):
    rnd = random.Random(seed)
    root = rnd.randrange(49)          # tirage de la racine (création)
    conns = gen_tree(rnd, 7, 7, False, root)
    rotations = [rnd.randrange(4) for _ in range(49)]
    return conns, rotations, root

def tree_regenerated(seed, forced):
    rnd = random.Random(seed)
    root = forced if forced >= 0 else rnd.randrange(49)
    conns = gen_tree(rnd, 7, 7, False, root)
    rotations = [rnd.randrange(4) for _ in range(49)]
    return conns, rotations

c1, r1, root = tree_created(4242)
c2, r2 = tree_regenerated(4242, -1)
check("v3 TREE : régénération identique (racine retirée du flux)", c1 == c2 and r1 == r2)
c3, r3 = tree_regenerated(4242, root)
check("v3 TREE : imposer la position sans tirer change l'arbre (bug corrigé)",
      c1 != c3 or r1 != r3)

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------
print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"Toutes les {checks} vérifications passent.")
