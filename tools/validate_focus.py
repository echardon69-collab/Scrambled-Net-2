#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « mise en évidence de la tuile manipulée ».

Décision d'eric du 13 nov. 2026 : « Les deux » — reprendre de
l'original (Cell.java / BoardView.java de Ian Cameron Smith,
dépôt SilentServices/Scrambled-Net) :
  1. le BLINK : bande diagonale contrastée (blanche la nuit, ardoise
     le jour — l'original la dessinait blanche sur son plateau
     sombre ; retour UAT du 13 nov. : invisible sur les cellules
     vides claires du thème jour) qui balaie la tuile en 200 ms,
     feedback VISUEL d'une action refusée (tap sur tuile verrouillée
     ou libre, appui long sur tuile libre) + clignement de la
     dernière tuile manipulée à la victoire (option validée par
     eric : « Oui, inclus ») ;
  2. le CURSEUR DE FOCUS de l'auto-solveur : cadre rouge
     translucide (0x60FF0000, ~38 % d'opacité), trait épais d'un
     huitième de case, qui suit les coups programmés un coup par
     tick et disparaît à la fin ou à l'arrêt. Pas de focus au doigt,
     pas de D-pad (hors périmètre).

Correctifs du retour UAT du 13 nov. (v2) :
  - une cellule verrouillée DÉVERROUILLÉE par le solveur repasse en
    TÊTE de la file (chaque cellule n'y figure qu'une fois : sans
    ça, le solveur s'arrêtait avant la fin sur un plateau à
    verrous) ;
  - une fois le plateau résolu, le solveur ACHÈVE sa file (le coup
    final laisse la file vide non close ; le tick gelait tout et le
    cadre rouge restait pour toujours) — le chrono, lui, gèle ;
  - solverFocus entre dans equals/hashCode de GameUiState (un état
    qui ne change QUE par le focus doit notifier la vue).

Les deux sont TRANSITOIRES : jamais sérialisés dans GameSpec
(pas de bump de format), jamais comptés au score.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : BoardView.kt, GameViewModel.kt et
     MainActivity.kt sont PARSÉS (jamais de valeurs retapées à la
     main dans ce script).
  2. Miroirs Python : géométrie exacte de la bande (les deux phases
     balayent la tuile, jamais au-delà), épaisseur du cadre à toutes
     tailles de case, prédicat de refus identique vue/ViewModel,
     trajectoire du focus solveur sur une file complète.

Usage : python3 tools/validate_focus.py
"""

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


def read(path):
    return Path(path).read_text(encoding="utf-8")


vw = read(KOTLIN / "ui/BoardView.kt")
vm = read(KOTLIN / "GameViewModel.kt")
ma = read(KOTLIN / "MainActivity.kt")
gs = read(KOTLIN / "game/GameSpec.kt")
colors_day = read(ROOT / "app/src/main/res/values/colors.xml")
colors_night = read(ROOT / "app/src/main/res/values-night/colors.xml")

# --- 1. Vérification statique ---------------------------------------------

# Constantes de l'original, reprises telles quelles.
check("BoardView : constante BLINK_MS = 200 (HIGHLIGHT_TIME de l'original)",
      "private const val BLINK_MS = 200L" in vw)
check("BoardView : trait du blink = 5 dp (5f de l'original)",
      "private const val BLINK_STROKE_DP = 5f" in vw)
check("BoardView : rouge translucide du focus = 0x60FF0000 (Cell.java)",
      "private const val FOCUS_RED = 0x60FF0000" in vw)

# État transitoire de la vue.
check("BoardView : état du blink (index + départ) déclaré",
      "private var blinkIndex = -1" in vw
      and "private var blinkStartMs = 0L" in vw)
check("BoardView : solverFocus posé par l'activité (var, défaut -1)",
      "var solverFocus = -1" in vw)
check("BoardView : startBlink relance la trame (le blink vit 200 ms)",
      re.search(r"private fun startBlink\(index: Int\) \{[^{}]*?"
                r"blinkIndex = index[^{}]*?"
                r"postInvalidateOnAnimation\(\)", vw, re.S) is not None)
check("BoardView : la boucle de trame reste active tant qu'un blink vit",
      re.search(r"if \(blipsEnabled \|\| animDurationMs\.any \{ it > 0L \} "
                r"\|\| blinkIndex >= 0\) \{", vw) is not None)

# Déclencheurs du blink : refus au tap (verrouillée ou libre) et long-press libre.
check("BoardView : le tap refusé cligne — prédicat verrouillée OU libre, "
      "AVANT l'appel au ViewModel (le clic sec reste joué par lui)",
      re.search(r"onSingleTapUp[^{}]*?\{[^{}]*?hitCell[^{}]*?"
                r"if \(b != null && \(b\.locked\[index\] \|\| "
                r"b\.isEmpty\(index\)\)\) \{[^{}]*?"
                r"startBlink\(index\)", vw, re.S) is not None)
check("BoardView : l'appui long sur une tuile LIBRE cligne (comme "
      "l'original — le ViewModel reste silencieux sur ce cas)",
      re.search(r"onLongPress[^{}]*?\{[^{}]*?"
                r"if \(board\?\.isEmpty\(index\) == true\) "
                r"startBlink\(index\)", vw, re.S) is not None)

# Victoire : la DERNIÈRE tuile manipulée cligne, une seule fois par partie.
check("BoardView : la vue trace la dernière tuile manipulée "
      "(joueur ou solveur) dans la détection des rotations",
      re.search(r"if \(delta != 0\) \{[^{}]*?lastTurnedCell = i",
                vw, re.S) is not None)
check("BoardView : plateau neuf remet la trace à -1 (une partie "
      "restaurée déjà résolue ne cligne pas)",
      re.search(r"lastTurnedCell = -1\n            freshBoard = true", vw)
      is not None)
check("BoardView : clignement de victoire sur la dernière tuile "
      "manipulée, transition wasSolved -> solved uniquement",
      re.search(r"val wasSolved = this\.solved", vw) is not None
      and re.search(r"if \(solved && !wasSolved && lastTurnedCell >= 0\) "
                    r"\{[^{}]*?startBlink\(lastTurnedCell\)", vw, re.S)
      is not None)

# Rendu : les deux mécanismes, par-dessus tout.
cell_m = re.search(r"private fun Canvas\.drawBoardCell\((.*?)\n    \}",
                   vw, re.S)
check("BoardView : drawBoardCell parsée", cell_m is not None)
cell_body = cell_m.group(1) if cell_m else ""
check("BoardView : cadre de focus rouge translucide, trait case/8, "
      "rectangle amputé d'un pixel — exactement l'original",
      "index == solverFocus" in cell_body
      and "focusPaint.color = FOCUS_RED" in cell_body
      and "focusPaint.strokeWidth = cellPx / 8f" in cell_body
      and "left + cellPx - 1f" in cell_body)
check("BoardView : le focus n'est dessiné qu'au trait (STROKE), "
      "jamais en remplissage",
      "focusPaint.style = Paint.Style.STROKE" in cell_body)
check("BoardView : bande du blink en DEUX phases diagonales, bornée "
      "à la tuile (le trait sort au coin opposé, jamais au-delà)",
      "if (pos < cellPx * 2f)" in cell_body
      and "drawLine(left, top + pos, left + pos, top, blinkPaint)" in cell_body
      and "drawLine(left + hp, top + cellPx, left + cellPx, top + hp, "
          "blinkPaint)" in cell_body
      and "blinkPaint.strokeWidth = BLINK_STROKE_DP * screenDensity" in cell_body
      and "blinkPaint.color = blinkBandColor" in cell_body)
check("BoardView : le blink s'éteint de lui-même après son passage",
      re.search(r"\} else \{\s*blinkIndex = -1\s*\}", cell_body) is not None)

# Couleur de la bande, contrastée dans les DEUX thèmes (retour UAT :
# le blanc de l'original est invisible sur les cellules vides claires
# du thème jour — #E6E6E6 -> #CFCFCF).
check("BoardView : couleur de la bande résolue du thème "
      "(blinkBandColor, jamais de blanc en dur)",
      "private val blinkBandColor = ContextCompat.getColor("
      "context, R.color.blink_band)" in vw)
day_band = re.search(r'<color name="blink_band">(\S+)</color>',
                     colors_day)
night_band = re.search(r'<color name="blink_band">(\S+)</color>',
                       colors_night)
check("Ressources : blink_band déclarée jour ET nuit, contrastes "
      "inversés (ardoise le jour sur fond clair, blanc la nuit)",
      day_band is not None and night_band is not None
      and day_band.group(1) != night_band.group(1)
      and day_band.group(1) != "#FFFFFFFF"
      and night_band.group(1) == "#FFFFFFFF")

# ViewModel : solverFocus.
check("GameViewModel : GameUiState porte solverFocus (défaut -1)",
      re.search(r"val solverFocus: Int = -1", vm) is not None)
check("GameViewModel : solverFocus compte dans equals ET hashCode "
      "(un état qui ne change que par le focus notifie la vue)",
      re.search(r"solving == other\.solving &&\s*\n\s*"
                r"solverFocus == other\.solverFocus", vm) is not None
      and re.search(r"result = 31 \* result \+ solving\.hashCode\(\)"
                    r"\s*\n\s*result = 31 \* result \+ solverFocus",
                    vm) is not None)
check("GameViewModel : refresh publie solverFocus = solverFocusIndex",
      "solverFocus = solverFocusIndex" in vm)
check("GameViewModel : chaque coup du solveur pose le focus "
      "(déverrouillage ET rotation)",
      vm.count("solverFocusIndex = index") == 2)
check("GameViewModel : une cellule DÉVERROUILLÉE repasse en tête de "
      "la file (elle ne la quitte qu'une fois tournée — retour UAT : "
      "le solveur s'arrêtait avant la fin sur un plateau à verrous)",
      re.search(r"board\.locked\[index\] -> \{[^{}]*?"
                r"board\.toggleLock\(index\)[^{}]*?"
                r"queue\.addFirst\(index\)[^{}]*?"
                r"solverFocusIndex = index", vm, re.S) is not None)
check("GameViewModel : le tick achève la file du solveur même une "
      "fois résolu (!solvedNow OU file non close), le chrono gèle "
      "seul (retour UAT : le cadre restait sur la dernière tuile)",
      re.search(r"if \(!clockPaused && \(!solvedNow \|\| "
                r"solveQueue != null\)\) \{[^{}]*?"
                r"val wasSolved = solvedNow[^{}]*?"
                r"playOneSolveMoveIfNeeded\(\)[^{}]*?"
                r"if \(!wasSolved\) \{[^{}]*?"
                r"elapsedMs \+= TICK_MS\.inWholeMilliseconds",
                vm, re.S) is not None)
check("GameViewModel : le focus disparaît quand la file s'épuise "
      "(le plateau vient d'être résolu)",
      re.search(r"solveQueue = null // file épuisée[^{}]*?"
                r"solverFocusIndex = -1", vm, re.S) is not None)
check("GameViewModel : le focus disparaît à l'arrêt manuel du solveur",
      re.search(r"if \(queue != null\) \{[^{}]*?solveQueue = null"
                r"[^{}]*?solverFocusIndex = -1[^{}]*?publish\(\)",
                vm, re.S) is not None)
check("GameViewModel : partie neuve et restauration repartent sans "
      "focus (le premier tick posera la tuile de SON coup)",
      vm.count("solverFocusIndex = -1") == 5,
      f"occurrences: {vm.count('solverFocusIndex = -1')}")

# Câblage de l'activité, et rien dans la sauvegarde.
check("MainActivity : render pose le solverFocus sur la vue avant "
      "le rendu",
      re.search(r"binding\.boardView\.solverFocus = state\.solverFocus",
                ma) is not None)
check("GameSpec : RIEN de transitoire dedans — ni solverFocus ni "
      "blink (pas de bump de format)",
      "solverFocus" not in gs and "blink" not in gs.lower())

# --- 2. Miroirs Python ------------------------------------------------------

# Géométrie de la bande (miroir exact du dessin) : aux bornes de
# chaque phase, le trait reste DANS la tuile ; en 200 ms pile, il
# vient juste de sortir (blinkIndex repasse à -1).
BLINK_MS = 200


def blink_line(t_ms, cell):
    """Miroir du bloc de dessin : (x1, y1, x2, y2) du trait, None si fini."""
    pos = (t_ms / BLINK_MS) * (cell * 2.0)
    if pos >= cell * 2.0:
        return None
    if pos < cell:
        return (0.0, pos, pos, 0.0)
    hp = pos - cell
    return (hp, cell, cell, hp)


geo_fail = []
for cell in (26.0 * 2.75, 96.0 * 0.85, 64.0, 120.0):
    for t, expected_in in ((0, True), (50, True), (99.99, True),
                           (100, True), (150, True), (199.99, True),
                           (200, False)):
        line = blink_line(t, cell)
        if expected_in and line is None:
            geo_fail.append(f"t={t}ms cell={cell}: trait disparu trop tôt")
        if not expected_in and line is not None:
            geo_fail.append(f"t={t}ms cell={cell}: blink survit à 200 ms")
        if line is not None:
            for coord in line:
                if coord < -1e-6 or coord > cell + 1e-6:
                    geo_fail.append(f"t={t}ms cell={cell}: hors tuile {line}")
                    break
check("Miroir blink : les deux phases balayent exactement la tuile, "
      "le trait vit 200 ms pile et ne sort jamais de la case "
      "(4 tailles, 7 instants)",
      not geo_fail, "; ".join(geo_fail[:3]))

# La phase 1 va du coin haut-gauche au coin bas-haut-diagonale,
# la phase 2 du coin bas-gauche au coin haut-droit : la jonction à
# t = 100 ms est la MÊME anti-diagonale (continuité du balayage).
c = 64.0
l1 = blink_line(99.999, c)
l2 = blink_line(100.0, c)
check("Miroir blink : jonction continue des phases à mi-course "
      "(l'anti-diagonale complète)",
      l2 == (0.0, c, c, 0.0)
      and l1 is not None
      and all(abs(a - b) < 1e-3 for a, b in zip(l1, (0.0, c, c, 0.0))))

# Épaisseur du cadre : un huitième de la case, comme l'original —
# assez épais pour se voir à toutes les tailles de case autorisées.
stroke_fail = []
for density in (0.85, 1.0, 2.0, 2.75, 3.5):
    for cell_dp in (26, 30, 48, 64, 96):
        cell_px = cell_dp * density
        stroke = cell_px / 8.0
        if stroke < 1.0:
            stroke_fail.append(f"{cell_dp}dp@{density}: trait invisible")
        if stroke > cell_px / 2.0:
            stroke_fail.append(f"{cell_dp}dp@{density}: trait étouffe la case")
check("Miroir cadre : trait = case/8, visible et proportionné sur "
      "toute la plage de zoom (26 à 96 dp, 5 densités)",
      not stroke_fail, "; ".join(stroke_fail[:3]))

# Prédicat de refus : la vue et le ViewModel doivent refuser les
# MÊMES tuiles (garde-fou de la duplication). Le ViewModel refuse
# au solvedNow près (après victoire, la vue ne cligne pas : le
# dialogue est là, décision de spec).
vm_refuse = re.search(r"fun onCellTapped\(index: Int\) \{[^{}]*?"
                      r"if \(solvedNow \|\| board\.locked\[index\] \|\| "
                      r"board\.isEmpty\(index\)\)", vm, re.S) is not None
view_refuse = re.search(r"b != null && \(b\.locked\[index\] \|\| "
                        r"b\.isEmpty\(index\)\)", vw) is not None
check("Miroir prédicat : le refus de la vue (locked OU empty) est le "
      "prédicat du ViewModel, sans le solvedNow (pas de blink après "
      "victoire)",
      vm_refuse and view_refuse)

# Trajectoire du focus solveur : miroir de playOneSolveMoveIfNeeded
# sur une file complète — le focus suit chaque coup JOUÉ (les tuiles
# déjà en place sont sautées, pas de focus fantôme), -1 à la fin.
class FakeBoard:
    def __init__(self, rotations, locked):
        self.rotations = rotations
        self.locked = locked
        self.unlocked_log = []
        self.rotated_log = []
        self.revealed_log = []

    def toggle_lock(self, i):
        self.locked[i] = not self.locked[i]
        self.unlocked_log.append(i)

    def rotate_cell(self, i, steps):
        self.rotations[i] = (self.rotations[i] + steps) % 4
        self.rotated_log.append(i)

    def reveal(self, i):
        self.revealed_log.append(i)


def play_all(rotations, locked, queue):
    """Miroir de playOneSolveMoveIfNeeded : liste des focus successifs."""
    board = FakeBoard(rotations, locked)
    q = list(queue)
    focus = []
    while q:
        index = q.pop(0)
        steps = (4 - board.rotations[index]) % 4
        if board.locked[index]:
            board.toggle_lock(index)
            q.insert(0, index)  # addFirst : retournée au coup suivant
            focus.append(index)
        elif steps != 0:
            board.rotate_cell(index, steps)
            board.reveal(index)
            focus.append(index)
    focus.append(-1)  # file épuisée : le curseur disparaît
    return focus


trajectory_fail = []
# Une tuile à tourner de 3 quarts (focus), une déjà en place (sautée :
# PAS de focus), une à tourner de 2 quarts (focus).
focus_seq = play_all([1, 0, 2], [False, False, False], [0, 1, 2])
if focus_seq != [0, 2, -1]:
    trajectory_fail.append(f"attendu [0, 2, -1], obtenu {focus_seq}")
# Toutes en place : aucun coup joué, juste la disparition.
focus_seq = play_all([0, 0, 0], [False] * 3, [0, 1, 2])
if focus_seq != [-1]:
    trajectory_fail.append(f"file sautée : attendu [-1], obtenu {focus_seq}")
# Verrouillée en file UNE SEULE FOIS (comme solveMoves : chaque cellule
# n'y figure qu'une fois) : déverrouillage (focus), repasse en tête,
# rotation au coup suivant (focus). Sans le addFirst, la cellule
# resterait déverrouillée jamais tournée — retour UAT du 13 nov.
focus_seq = play_all([1], [True], [0])
if focus_seq != [0, 0, -1]:
    trajectory_fail.append(f"verrouillée : attendu [0, 0, -1], obtenu "
                           f"{focus_seq}")
# Deux verrouillées entrecroisées avec les rotations de la file :
# le solveur va jusqu'au bout, chaque cellule déverrouillée PUIS tournée.
focus_seq = play_all([1, 2], [True, True], [0, 1])
if focus_seq != [0, 0, 1, 1, -1]:
    trajectory_fail.append(f"deux verrouillées : attendu [0, 0, 1, 1, "
                           f"-1], obtenu {focus_seq}")
check("Miroir solveur : le focus suit chaque coup JOUÉ (tuiles déjà "
      "en place sautées, verrouillée = déverrouillage puis rotation "
      "de la MÊME cellule), et disparaît à la fin (-1)",
      not trajectory_fail, "; ".join(trajectory_fail[:2]))

# --- Bilan -----------------------------------------------------------------

print()
if failures:
    print(f"ÉCHEC : {len(failures)}/{checks} vérifications en échec "
          f"(mise en évidence de la tuile).")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications (mise en "
      f"évidence de la tuile manipulée : blink + focus auto-solveur).")
