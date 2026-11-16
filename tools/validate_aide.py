#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « Aide à la résolution » (15 nov. 2026).

Trois fonctionnalités actées avec eric :
  1. INDICE — bouton toujours visible avec badge du plafond restant
     (« Indice (n) »). Stratégie SIGNAL : cadre vert pulsé sur la tuile
     à corriger, priorités (1) touchée+verrouillée mal orientée,
     (2) touchée non verrouillée, (3) verrouillée jamais touchée,
     (4) première mal orientée en ordre BFS du solveur. Stratégie
     FIX : rotation à l'aplomb + révélation si cachée + tuile SCELLÉE
     (verrouillage définitif, fond rose intérim à statuer). Plafond =
     hintAllowance(base, percent) : pourcentage de la TAILLE DE
     RÉFÉRENCE du niveau (Skill.hintReferenceTiles, matrice MEDIUM —
     v12, retour utilisateur du 16 nov. 2026 : un même niveau offre
     TOUJOURS le même nombre d'indices, quel que soit le mélange ou
     l'écran), Désactivé/10/20 seulement (v11, défaut 10 ; borne
     moteur 0–50 conservée en défense), floor, minimum 1 si >0,
     0 = désactivé. Les records ne sont PAS grillés par l'indice :
     chaque record porte son nombre (« sans indice » si 0), et une
     rubrique « Indices » totalise les tuiles par niveau. Le coût en
     coups d'une correction (hintMoveCost) est PROVISOIRE — comptage
     à statuer avec eric en dernière étape.
  2. RÉSOUDRE — déménagé sous le bouton « Outils » (popup) ; le
     solveur lui-même est inchangé, mais il ne touche plus jamais une
     tuile scellée (garde isSealed avant le bloc locked).
  3. DÉMONSTRATION — DemoActivity plein écran, boucle plateau →
     résolution → victoire (blink) → pause 1,5 s → suivant. Niveaux
     COCHÉS (v8 : cases à cocher, minima une), difficulté
     RANDOM/PROGRESSIVE parmi ces niveaux,
     rythme 500/250 (défaut)/100/30 ms, caméra à hystérésis (recentrer
     seulement si la tuile sort du rectangle central 30–70 %). V10
     (retour utilisateur du 16 nov. 2026) : le réglage de zoom est
     retiré — cadrage TOUJOURS ajusté, y compris au PREMIER plateau.
     AUCUN son — écart voulu, à valider avec eric. Tout contact
     referme la page.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : les sources sont PARSÉES (jamais de
     valeurs retapées à la main dans ce script).
  2. Miroirs Python : priorités de hintTarget sur des plateaux
     construits, bornes de hintAllowance, invariants du scellement,
     cycle progressif de la démonstration.

Usage : python3 tools/validate_aide.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
TESTS = ROOT / "app/src/test/kotlin/net/chardons/netscramble2/game"
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


def count(st, needle):
    return st.count(needle)


b = read(KOTLIN / "game/Board.kt")
gs = read(KOTLIN / "game/GameSpec.kt")
sk = read(KOTLIN / "game/Skill.kt")
vm = read(KOTLIN / "GameViewModel.kt")
ma = read(KOTLIN / "MainActivity.kt")
bv = read(KOTLIN / "ui/BoardView.kt")
sc = read(KOTLIN / "SettingsActivity.kt")
st = read(KOTLIN / "Settings.kt")
pe = read(KOTLIN / "Persistence.kt")
ss = read(KOTLIN / "ScoresStore.kt")
sa = read(KOTLIN / "ScoresActivity.kt")
dm = read(KOTLIN / "Demo.kt")
dvm = read(KOTLIN / "DemoViewModel.kt")
da = read(KOTLIN / "DemoActivity.kt")
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
menu_main = read(RES / "menu/menu_main.xml")
menu_tools = read(RES / "menu/menu_tools.xml")
layout_demo = read(RES / "layout/activity_demo.xml")
layout_scores = read(RES / "layout/activity_scores.xml")
layout_settings = read(RES / "layout/activity_settings.xml")
strings_en = read(RES / "values/strings.xml")
strings_fr = read(RES / "values-fr/strings.xml")
hint_test = read(TESTS / "HintTest.kt")
spec_test = read(TESTS / "GameSpecTest.kt")
demo_test = read(TESTS / "DemoTest.kt")

# ---------------------------------------------------------------------------
# 1. Board — scellement, tuiles touchées, cible d'indice, plafond.
# ---------------------------------------------------------------------------
check("Board : rotateCell porte byPlayer et marque touched",
      "fun rotateCell(index: Int, steps: Int, byPlayer: Boolean = false)" in b
      and "if (byPlayer) touched[index] = true" in b)

check("Board : toggleLock renvoie Boolean et refuse une scellée en premier",
      "fun toggleLock(index: Int): Boolean" in b
      and b.find("if (sealedCells[index]) return false") < b.find("locked[index] = !locked[index]"))

check("Board : seal() verrouille, révèle et marque scellée",
      "fun seal(index: Int)" in b
      and "sealedCells[index] = true" in b
      and count(b, "locked[index] = true") >= 1
      and "revealed[index] = true" in b)

check("Board : isSealeed/isSealed exposés", "fun isSealed(index: Int)" in b)

hint_fn = b[b.find("fun hintTarget(): Int"):b.find("fun hintTarget(): Int") + 1400]
check("Board : isMisplaced FONCTIONNEL (masque courant != masque de solution, UAT)",
      "fun isMisplaced(index: Int): Boolean" in b
      and "currentDirs(index) != connections[index]" in b)
check("Board : hintTarget priorité 1 = verrouillée mal orientée (stratégie UAT)",
      "firstOrNull { locked[it] && isMisplaced(it) }" in hint_fn)
check("Board : hintTarget priorité 2 = non verrouillée, 3 connecteurs d'abord (stratégie UAT)",
      "if (locked[i] || !isMisplaced(i)) continue" in hint_fn
      and "Integer.bitCount(connections[i]) == 3" in hint_fn)
check("Board : priorités 1 puis 2 dans cet ordre d'écriture",
      0 <= hint_fn.find("firstOrNull { locked[it] && isMisplaced(it) }")
      < hint_fn.find("Integer.bitCount(connections[i]) == 3"))
check("Board : PLUS DE repli première touchée/première verrouillée (UAT : tuiles en place soumises)",
      "firstOrNull { touched[it] }" not in hint_fn
      and "firstOrNull { locked[it] }" not in hint_fn)
check("Board : hintTarget ne teste plus les rotations brutes (tuiles symétriques comprises)",
      "rotations[it] != 0" not in hint_fn and "rotations[i] != 0" not in hint_fn)
check("Board : repli final -1 (aucune tuile bien orientée soumise)",
      "return fallback" in hint_fn)

allow_fn = b[b.find("fun hintAllowance"):b.find("fun hintAllowance") + 300]
check("Board : hintAllowance coercée 0–50",
      "percent.coerceIn(0, 50)" in allow_fn)
check("Board : hintAllowance nulle si pourcentage nul (désactivé)",
      "if (p == 0) return 0" in allow_fn)
check("Board : hintAllowance floor puis minimum 1",
      "(base * p / 100).coerceAtLeast(1)" in allow_fn
      and "fun hintAllowance(base: Int, percent: Int)" in allow_fn)
check("Skill : tailles de référence du plafond = matrice MEDIUM (v12, qualif. redondantes retirées v13)",
      "fun hintReferenceTiles" in sk
      and "NOVICE -> 25" in sk and "NORMAL -> 63" in sk
      and "EXPERT -> 77" in sk
      and "MASTER, INSANE -> 77" in sk
      and "ENDLESS, ABYSS, VOID -> ENDLESS_SIZE * ENDLESS_SIZE" in sk
      and "Skill." not in sk.split("fun hintReferenceTiles")[1].split("}")[0])
check("Settings : filet niveaux démo via ifEmpty (lint IDE, v13)",
      "skills.ifEmpty { listOf(Skill.NOVICE) }" in st)

# ---------------------------------------------------------------------------
# 2. GameSpec — format v5 (22 champs), rétrocompatibilité v4/v3/v2.
# ---------------------------------------------------------------------------
check("GameSpec : VERSION v5, FIELDS 22",
      'private const val VERSION = "v5"' in gs and "private const val FIELDS = 22" in gs)
check("GameSpec : PREVIOUS v4/18, V3 15, LEGACY 11",
      'private const val PREVIOUS_VERSION = "v4"' in gs
      and "private const val PREVIOUS_FIELDS = 18" in gs
      and "private const val V3_FIELDS = 15" in gs
      and "private const val LEGACY_FIELDS = 11" in gs)
check("GameSpec : base caméra v5 = 19, v4 = 15",
      "private const val CAMERA_BASE = 19" in gs
      and "private const val CAMERA_BASE_PREVIOUS = 15" in gs)
check("GameSpec : ligne pivot v2/autres inchangée",
      "val base = if (legacy) 3 else 7" in gs)
check("GameSpec : encode écrit scellées|touchées|hintsUsed|hintsAllowed avant caméra",
      gs.find("for (s in sealedCells)") < gs.find("for (t in touched)")
      < gs.find("append(hintsUsed)") < gs.find("append(hintsAllowed)")
      < gs.find("private const val CAMERA_BASE") and True)
enc_hint_pos = gs.find("for (s in sealedCells)")
enc_cam_pos = gs.find("CameraSpec(centerMilliX")
check("GameSpec : les quatre nouveaux champs précèdent la caméra à l'encodage",
      0 < enc_hint_pos < enc_cam_pos)

check("GameSpec : décode v5 depuis parts[15..18]",
      "val sealStr = parts[15]" in gs and "val touchStr = parts[16]" in gs
      and "hintsUsed = parts[17]" in gs and "hintsAllowed = parts[18]" in gs)
check("GameSpec : rejet d'une scellée sans verrouillée",
      "if (sealBit == '1' && !locked[i]) return null" in gs)
check("GameSpec : sentinelle hintsAllowed -1 acceptée (re-encodage v4 possible)",
      "hintsAllowed < -1" in gs and "hintsUsed < 0" in gs)
check("GameSpec : PAS de rejet croisé hintsUsed > hintsAllowed",
      "hintsUsed > hintsAllowed" not in gs)
check("GameSpec : versions antérieures = tableaux vides + hintsUsed 0 / plafond -1",
      "hintsAllowed = -1" in gs and "hintsUsed = 0" in gs)
check("GameSpec : dispatch 5/4/3/2 conservé",
      "decodeFields(parts, version = 5)" in gs and "decodeFields(parts, version = 4)" in gs
      and "decodeFields(parts, version = 3)" in gs and "decodeFields(parts, version = 2)" in gs)
check("GameSpec : base caméra choisie selon la version",
      "if (version >= 5) CAMERA_BASE else CAMERA_BASE_PREVIOUS" in gs)
check("GameSpec : equals/hashCode couvrent les 4 nouveaux champs",
      "sealedCells.contentEquals" in gs and "touched.contentEquals" in gs
      and "hintsUsed == other.hintsUsed" in gs and "hintsAllowed == other.hintsAllowed" in gs
      and "sealedCells.contentHashCode()" in gs)
check("GameSpec : buildBoard applique scellées et touchées",
      "sealedCells.copyInto(board.sealedCells)" in gs
      and "touched.copyInto(board.touched)" in gs)
check("GameSpec : ni solverFocus ni blink (épingle)",
      "solverFocus" not in gs and "blink" not in gs)
check("GameSpec : l'init adresse this.sealedCells/this.touched "
      "(le paramètre nullable masque la propriété — erreur de "
      "compilation chez eric le 15 nov. 2026)",
      "require(this.sealedCells.size" in gs and "require(this.touched.size" in gs)

# ---------------------------------------------------------------------------
# 3. GameViewModel — indice, garde du solveur, plafond, publication.
# ---------------------------------------------------------------------------
check("VM : enum HintStrategy { SIGNAL, FIX } au niveau racine",
      "enum class HintStrategy { SIGNAL, FIX }" in vm)
check("VM : WinEvent porte le nombre d'indices de la partie",
      re.search(r"class WinEvent\([^)]*hints", vm) is not None)
check("VM : GameUiState porte hintRemaining et hintIndex",
      "val hintRemaining: Int" in vm and "val hintIndex: Int" in vm)
check("VM : GameUiState porte hintRequestId (génération, UAT)",
      "val hintRequestId: Int = 0" in vm)
check("VM : equals/hashCode couvrent hintRequestId",
      "hintRequestId == other.hintRequestId" in vm
      and "31 * result + hintRequestId" in vm)
check("VM : compteur de génération hintPulseId incrémenté (onHint/newGame/restore)",
      count(vm, "++hintPulseId") == 3,
      f"trouvé {count(vm, '++hintPulseId')}")
check("VM : refresh publie hintRequestId = hintPulseId",
      "hintRequestId = hintPulseId" in vm)
eq_pos = vm.find("hintRemaining == other.hintRemaining")
sf_pos = vm.find("solverFocus == other.solverFocus")
check("VM : equals étendu APRÈS solverFocus (ordre épinglé)",
      0 < sf_pos < eq_pos)
check("VM : hashCode étendu après solverFocus",
      vm.find("31 * result + solverFocus") < vm.find("31 * result + hintRemaining"))

hint_vm = vm[vm.find("fun onHint"):vm.find("fun onHint") + 700]
check("VM : onHint refuse si résolue ou solveur en cours",
      "if (solvedNow || solveQueue != null) return" in hint_vm)
check("VM : onHint refuse si plafond épuisé ou désactivé",
      "if (hintsAllowed <= 0 || hintsUsed >= hintsAllowed) return" in hint_vm)
check("VM : onHint sans cible → rien",
      "if (index < 0) return" in hint_vm)
check("VM : FIX tourne à l'aplomb et compte hintMoveCost",
      "val steps = (4 - board.rotations[index]) % 4" in hint_vm
      and "moves += hintMoveCost(steps)" in hint_vm)
check("VM : FIX révèle une cachée puis scelle",
      hint_vm.find("board.isHidden(index)") < hint_vm.find("board.seal(index)"))
check("VM : SIGNAL ne touche pas au plateau (pas de seal dans la branche)",
      hint_vm.count("board.seal(index)") == 1)
check("VM : hintMoveCost isolé et PROVISOIRE",
      "private fun hintMoveCost(steps: Int): Int = steps" in vm
      and "PROVISOIRE" in vm)
check("VM : newGame prend hintPercent (défaut 10) et calcule le plafond",
      "hintPercent: Int = 10" in vm
      and "hintsAllowed = Board.hintAllowance(Skill.hintReferenceTiles(skill), hintPercent)" in vm
      and "misplacedTiles" not in vm)
check("VM : restoreIfPending recalcule un plafond v4 absent (< 0)",
      "if (hintsAllowed < 0)" in vm
      and "Skill.hintReferenceTiles(spec.skill), hintPercent" in vm
      and "pristine" not in vm)
check("VM : refresh publie hintRemaining plancher 0 et hintIndex",
      "hintRemaining = (hintsAllowed - hintsUsed).coerceAtLeast(0)" in vm
      and "hintIndex = hintFocusIndex" in vm)
check("VM : publish envoie hints = hintsUsed au WinEvent",
      "hints = hintsUsed" in vm)
check("VM : onCellTapped marque la tuile comme touchée (byPlayer)",
      "board.rotateCell(index, 1, byPlayer = true)" in vm)
check("VM : l'appui long ne bascule le verrou que si toggleLock accepte",
      re.search(r"onCellLongPressed[\s\S]{0,400}?toggleLock\(", vm) is not None)
play = vm[vm.find("fun playOneSolveMoveIfNeeded"):vm.find("fun playOneSolveMoveIfNeeded") + 900]
check("VM : garde isSealed AVANT le bloc locked du solveur",
      0 < play.find("board.isSealed(index) -> {") < play.find("board.locked[index] -> {"))
check("VM : épingle solverFocusIndex = -1 == 5 occurrences",
      count(vm, "solverFocusIndex = -1") == 5,
      f"trouvé {count(vm, 'solverFocusIndex = -1')}")
check("VM : épingle solverFocusIndex = index == 2 occurrences",
      count(vm, "solverFocusIndex = index") == 2,
      f"trouvé {count(vm, 'solverFocusIndex = index')}")
check("VM : bloc locked du solveur inchangé (locked→toggleLock→addFirst→focus)",
      re.search(r"board\.locked\[index\] -> \{[\s\S]{0,400}?toggleLock[\s\S]{0,400}?addFirst[\s\S]{0,400}?solverFocusIndex = index", vm) is not None)

# ---------------------------------------------------------------------------
# 4. MainActivity — badge, popup Outils, suivi caméra, records.
# ---------------------------------------------------------------------------
check("MainActivity : exactement 4 appels newGame",
      count(ma, "viewModel.newGame(") == 4,
      f"trouvé {count(ma, 'viewModel.newGame(')}")
check("MainActivity : chaque newGame + restore passent loadHintPercent (5 occurrences)",
      count(ma, "Settings.loadHintPercent(this)") == 5
      and ma.count("loadHintPercent") == 5,
      f"trouvé {count(ma, 'Settings.loadHintPercent(this)')}")
prep = ma[ma.find("fun onPrepareOptionsMenu"):ma.find("fun onPrepareOptionsMenu") + 700]
check("MainActivity : badge dynamique « Indice (n) » au onPrepare (syntaxe propriété)",
      "menu.findItem(R.id.menu_hint)?.title =" in prep
      and "R.string.menu_hint_count" in prep)
check("MainActivity : bouton Indice inactif si résolue/solveur/plafond à 0",
      "menu_hint)?.isEnabled" in prep and "hintRemaining > 0" in prep)
check("MainActivity : le titre de Résoudre n'est plus posé au onPrepare",
      "menu_solve" not in prep)
mh = ma[ma.find("R.id.menu_hint -> {"):ma.find("R.id.menu_hint -> {") + 600]
check("MainActivity : branche Indice lit la stratégie puis délègue à onHint",
      "loadHintStrategy" in mh and "viewModel.onHint(" in mh)
check("MainActivity : compteur de tuiles d'indice incrémenté si plafond décrémenté",
      "addHintTiles" in mh)
mt = ma[ma.find("R.id.menu_tools -> {"):ma.find("R.id.menu_tools -> {") + 1300]
check("MainActivity : popup Outils ancré sur le bouton (repli toolbar)",
      "PopupMenu" in mt and "R.id.menu_tools" in mt and "binding.toolbar" in mt)
check("MainActivity : le popup inflate menu_tools et y renvoie les clics",
      "R.menu.menu_tools" in mt and "setOnMenuItemClickListener" in mt)
md = ma[ma.find("R.id.menu_demo -> {"):ma.find("R.id.menu_demo -> {") + 300]
check("MainActivity : branche Démonstration ouvre DemoActivity",
      "DemoActivity::class.java" in md)
check("MainActivity : suivi caméra VISIBLE pour l'indice (plateau visible = immobile, UAT)",
      "followCell(state.hintIndex, BoardView.FollowMode.VISIBLE)" in ma
      and "followCell(state.hintIndex, BoardView.FollowMode.CENTER)" not in ma)
check("MainActivity : pulsation indice armée sur NOUVELLE génération, pas à chaque render (UAT)",
      "state.hintRequestId != lastHintRequestId" in ma
      and "binding.boardView.showHint(state.hintIndex)" in ma
      and "binding.boardView.hintIndex = state.hintIndex" not in ma)
check("MainActivity : suivi caméra VISIBLE pour le solveur",
      "followCell(state.solverFocus, BoardView.FollowMode.VISIBLE)" in ma)
check("MainActivity : menu re-rendu quand le badge change",
      re.search(r"hintRemaining != lastHintRemaining[\s\S]{0,80}?invalidateOptionsMenu", ma) is not None)
check("MainActivity : register enregistre les indices de la victoire",
      "event.hints" in ma and "scoresStore.register(" in ma)
check("MainActivity : la branche menu_solve (popup) reste branchée au solveur",
      "R.id.menu_solve ->" in ma and "toggleSolver" in ma)
check("MainActivity : « when » porte item.itemId en sujet (règle CI du 15 nov.)",
      "when (item.itemId)" in ma and "item.itemId == R.id" not in ma)
check("MainActivity : aucun setTitle sur MenuItem (syntaxe propriété, CI)",
      "?.setTitle(" not in ma)

# ---------------------------------------------------------------------------
# 5. BoardView — tuile scellée, cadre pulsé, suivi caméra, zoom démo.
# ---------------------------------------------------------------------------
check("BoardView : priorité des fonds empty > sealed > locked > normal",
      bv.find("dirs == Dir.NO_DIRS -> tileBgEmpty") < bv.find("b.isSealed(index) -> tileBgSealed")
      < bv.find("b.locked[index] -> tileBgLocked"))
check("BoardView : drawable scellée résolue depuis les ressources",
      "tile_background_sealed" in bv)
check("BoardView : cadre vert pulsé HINT_PULSE_MS, alpha borné 90..220",
      "HINT_PULSE_MS = 5_000L" in bv and "HINT_ALPHA_MIN = 90" in bv
      and "HINT_ALPHA_MAX = 220" in bv and "HINT_PULSE_PERIOD_MS = 500L" in bv)
check("BoardView : le cadre expire tout seul après 5 s",
      "hintIndex = -1" in bv[bv.find("if (index == hintIndex)"):])
check("BoardView : hintIndex change → postInvalidate (setter)",
      re.search(r"set\(value\)[\s\S]{0,300}?postInvalidate", bv) is not None)
check("BoardView : showHint expose l'armement de la pulsation à l'activité",
      "fun showHint(index: Int)" in bv)
check("BoardView : trois modes de suivi (CENTER, VISIBLE, HYSTÉRÉSIS)",
      "enum class FollowMode { CENTER, VISIBLE, HYSTERESIS }" in bv)
follow = bv[bv.find("fun followCell"):bv.find("fun followCell") + 2200]
check("BoardView : VISIBLE = recentrer seulement si la tuile est entièrement hors vue",
      "FollowMode.VISIBLE" in follow and follow.count("return") >= 2)
check("BoardView : test de visibilité dans la convention du pan ADDITIVE (correctif UAT)",
      "cellLeft + panX <= width" in bv and "cellTop + panY <= height" in bv
      and "cellLeft + cellPx >= panX &&" not in bv)
check("BoardView : HYSTERESIS ramené dans la même convention (correctif UAT)",
      "cx + panX >= width * 0.3f" in bv and "cx >= panX +" not in bv)
check("BoardView : cible du pan bornée aux limites de clampPan (pas de claquement)",
      "panTargetX = panTargetX.coerceIn(" in bv and "panTargetY = panTargetY.coerceIn(" in bv)
check("BoardView : HYSTÉRÉSIS = rectangle central 30–70 %",
      "0.3f" in follow and "0.7f" in follow)
check("BoardView : pan animé PAN_FOLLOW_MS interpolé dans onDraw",
      "PAN_FOLLOW_MS = 300L" in bv and "panAnimating" in bv
      and bv.find("if (panAnimating)") < bv.find("val appearanceFrozen"))
check("BoardView : applyZoomPreset — ≤1 ajuste, sinon plancher × multiplicateur borné",
      "fun applyZoomPreset(multiplier: Float)" in bv and "fitBoard()" in bv
      and "maxCellPx" in bv)
check("BoardView : exception brouillard pour la tuile d'indice",
      "if (fog != null && !fog[index] && index != hintIndex)" in bv)
check("BoardView : épingle ligne driver inchangée",
      "if (blipsEnabled || animDurationMs.any { it > 0L } || blinkIndex >= 0)" in bv)
driver_pos = bv.find("if (blipsEnabled || animDurationMs.any { it > 0L } || blinkIndex >= 0)")
check("BoardView : drivers additifs (indice, pan) APRÈS la ligne épinglée",
      bv.find("if (hintIndex >= 0)", driver_pos) != -1
      and bv.find("if (panAnimating)", driver_pos) != -1)
check("BoardView : épingle floorMod ×2 (niveaux toriques)",
      count(bv, "Math.floorMod(wy, b.height) * b.width +") == 2)
check("BoardView : curseur solveur toujours publié par l'activité",
      "binding.boardView.solverFocus = state.solverFocus" in ma)

# ---------------------------------------------------------------------------
# 6. Persistance + réglages.
# ---------------------------------------------------------------------------
for key, val in [("HINT_STRATEGY_KEY", "hintStrategy"), ("HINT_PERCENT_KEY", "hintPercent"),
                 ("DEMO_DIFFICULTY_KEY", "demoDifficulty"), ("DEMO_SKILL_SET_KEY", "demoSkillSet"),
                 ("DEMO_SPEED_KEY", "demoSpeed")]:
    check(f"Persistence : clé {key} = \"{val}\"", f'const val {key} = "{val}"' in pe)
check("Persistence/Settings : jamais le mot interdit (épingle réglages)",
      "LANGUAGE" not in st and "LANGUAGE" not in pe)
for fn in ["loadHintStrategy", "saveHintStrategy", "loadHintPercent", "saveHintPercent",
           "loadDemoDifficulty", "saveDemoDifficulty",
           "loadDemoSkillSet", "saveDemoSkillSet",
           "loadDemoSkillSet", "saveDemoSkillSet", "loadDemoSpeed", "saveDemoSpeed"]:
    check(f"Settings : {fn} présent", f"fun {fn}(" in st)
check("Settings : hintPercent borné au cadre 0/10/20, repli 10 (v11)",
      count(st, "coerceHintPercent") >= 2
      and "0, 10, 20 -> percent" in st and "else -> 10" in st
      and "coerceIn(0, 50)" not in st)
check("Settings : base du plafond documentée (taille de référence MEDIUM, v12)",
      "TAILLE DE RÉFÉRENCE" in st
      and "matrice MEDIUM" in st and "Skill.hintReferenceTiles" in st
      and "MÉLANGE INITIAL" not in st)
check("Settings : défauts SIGNAL/10/PROGRESSIVE/NOVICE/NORMAL (v10 : sans zoom)",
      "HintStrategy.SIGNAL" in st and "10" in st and "DemoDifficulty.PROGRESSIVE" in st
      and "Skill.NOVICE" in st and "DemoSpeed.NORMAL" in st
      and "loadDemoZoom" not in st and "saveDemoZoom" not in st)

groups = ["hintStrategyGroup", "hintLimitGroup", "demoDiffGroup",
          "demoSpeedGroup"]
for g in groups:
    check(f"SettingsActivity : groupe {g} coché AVANT l'écoute",
          0 < sc.find(f"binding.{g}.check(") < sc.find(f"binding.{g}.setOnCheckedChangeListener"))
check("SettingsActivity : radios 10 et 20 portées au runtime via hint_limit_value (v11)",
      "bindHintLimitRadios" in sc and "R.string.hint_limit_value" in sc
      and "listOf(10, 20)" in sc and "5..50" not in sc
      and "hint_limit_5" not in sc and "hint_limit_50" not in sc
      and "hintPercentFor" in sc)
check("SettingsActivity : la radio 0 porte hint_limit_zero, pas les autres",
      layout_settings.count('@string/hint_limit_zero') == 1)
check("Layout réglages : 26 radios + 8 cases, maxWidth 320dp (v11 : radios de plafond retirées)",
      layout_settings.count("<com.google.android.material.radiobutton.MaterialRadioButton") == 26
      and layout_settings.count("<com.google.android.material.checkbox.MaterialCheckBox") == 8
      and layout_settings.count('android:maxWidth="320dp"') == 42
      and "demo_zoom" not in layout_settings
      and "hint_limit_5" not in layout_settings
      and "hint_limit_50" not in layout_settings)
check("Layout réglages : plafond d'indices à 3 radios Désactivé/10/20 (v11)",
      layout_settings.count('@+id/hint_limit_0"') == 1
      and layout_settings.count('@+id/hint_limit_10"') == 1
      and layout_settings.count('@+id/hint_limit_20"') == 1
      and layout_settings.count("@+id/hint_limit_") == 4)
check("Layout réglages : 8 RadioGroups au total (v10 : le groupe de zoom est retiré)",
      layout_settings.count("<RadioGroup") == 8)
for gid, n in [("hint_strategy_group", 2), ("hint_limit_group", 3), ("demo_diff_group", 2),
               ("demo_speed_group", 3)]:
    m = re.search(rf'@\+id/{gid}"[\s\S]*?</RadioGroup>', layout_settings)
    check(f"Layout réglages : {gid} porte {n} radios",
          m is not None and m.group(0).count("<com.google.android.material.radiobutton.MaterialRadioButton") == n)
check("Layout réglages : les 8 CASES de niveau réutilisent skill_* (v8)",
      re.search(r'@\+id/demo_skill_checks"[\s\S]*?</LinearLayout>', layout_settings).group(0).count("@string/skill_") == 8)
# V8 (retours du 16 nov. 2026) : niveaux de la démo en cases à
# cocher — cochées avant l'écoute, la dernière jamais décochable,
# radios Aléatoire/Progressif grisées à coche unique.
check("SettingsActivity : niveaux démo cochés AVANT l'écoute (v8)",
      0 < sc.find("Settings.loadDemoSkillSet(this)")
      < sc.find("cb.setOnCheckedChangeListener"))
check("SettingsActivity : la dernière coche ne se décoche jamais (v8)",
      "if (checked.isEmpty()) {" in sc and "changed.isChecked = true" in sc)
check("SettingsActivity : radios de difficulté grisées à coche unique (v8)",
      "refreshDemoDifficultyAvailability" in sc and "isEnabled = multi" in sc
      and sc.count("binding.demoDiffRandom.isEnabled") == 1
      and sc.count("binding.demoDiffProgressive.isEnabled") == 1)

# ---------------------------------------------------------------------------
# 7. ScoresStore + page Records.
# ---------------------------------------------------------------------------
check("ScoresStore : Best porte hints (défaut 0)",
      re.search(r"data class Best\([\s\S]{0,200}hints: Int = 0", ss) is not None)
check("ScoresStore : clés additives Hint par record + total par niveau",
      '"Hints"' in ss and '"hintTotal"' in ss)
reg_fn = ss[ss.find("fun register("):ss.find("fun register(") + 400]
check("ScoresStore : register(skill, ntiles, moves, seconds, hints)",
      "skill: Skill" in reg_fn and "ntiles: Int" in reg_fn and "moves: Int" in reg_fn
      and "seconds: Int" in reg_fn and "hints: Int = 0" in reg_fn)
check("ScoresStore : addHintTiles cumule, hintTiles relit",
      "fun addHintTiles(" in ss and "fun hintTiles(" in ss)
check("ScoresStore : clear() efface aussi les clés d'indices",
      ss.find("fun clear(") < ss.rfind('"Hints"') or '"Hints"' in ss[ss.find("fun clear("):])
check("ScoresActivity : format « X · sans indice » / « X · N indices »",
      "R.string.hints_none" in sa and "R.string.hints_n" in sa)
check("ScoresActivity : rubrique Indices (total + table par niveau)",
      "fillHintsTable" in sa and "R.string.hints_total" in sa and "binding.hintsTable" in sa)
check("Layout records : section Indices (titre, total, table)",
      '@string/hints_section' in layout_scores and "@+id/hints_total" in layout_scores
      and '@+id/hints_table"' in layout_scores)

# ---------------------------------------------------------------------------
# 8. Menus + manifeste.
# ---------------------------------------------------------------------------
check("menu_main : menu_solve ABSENT (déménagé sous Outils)",
      "menu_solve" not in menu_main)
check("menu_main : Indice toujours visible, Outils si place",
      'android:id="@+id/menu_hint"' in menu_main
      and re.search(r'@\+id/menu_hint"[\s\S]{0,120}?showAsAction="always"', menu_main) is not None
      and re.search(r'@\+id/menu_tools"[\s\S]{0,120}?showAsAction="ifRoom"', menu_main) is not None)
check("menu_main : une seule balise menu (épingle réglages)",
      menu_main.count("<menu") == 1)
check("menu_tools : Résoudre + Démonstration, une seule balise menu",
      menu_tools.count("<menu") == 1 and 'R.id.menu_solve"' not in menu_tools
      and "@+id/menu_solve" in menu_tools and "@+id/menu_demo" in menu_tools)
check("Manifeste : DemoActivity non exportée, étiquette et parent",
      re.search(r'<activity\s+android:name="\.DemoActivity"[\s\S]{0,200}?android:exported="false"', manifest) is not None
      and 'android:label="@string/menu_demo"' in manifest
      and 'android:parentActivityName=".MainActivity"' in manifest)

# ---------------------------------------------------------------------------
# 9. Démonstration.
# ---------------------------------------------------------------------------
check("Demo : DemoDifficulty RANDOM/PROGRESSIVE, FIXED retiré (v8)",
      "enum class DemoDifficulty { RANDOM, PROGRESSIVE }" in dm
      and "FIXED" not in dm)
check("Demo : DemoSpeed 500/250/100 ms, Éclair RETIRÉ (v5, retour d'eric)",
      "SLOW(500)" in dm and "NORMAL(250)" in dm and "FAST(100)" in dm
      and "LIGHTNING" not in dm and "demo_speed_lightning" not in dm)
check("Demo : le réglage de zoom est RETIRÉ (v10, retour utilisateur)",
      "DemoZoom" not in dm and "demo_zoom" not in dm)
check("DemoViewModel : pause victoire 1,5 s",
      "WIN_PAUSE_MS = 1_500L" in dvm)
check("DemoViewModel : progressive = les niveaux COCHÉS en ordre croissant (v8)",
      "progressiveOrdinal % skillSet.size" in dvm
      and "skillSet[Random.nextInt(skillSet.size)]" in dvm)
check("DemoActivity : la démo démarre sur les niveaux COCHÉS (v8)",
      "Settings.loadDemoSkillSet(this)" in da)
check("DemoViewModel : générateur comme le jeu (Infini → ORIGINAL, sinon bit de graine)",
      "skill.endless" in dvm and "(seed and 1L) == 0L" in dvm)
check("DemoViewModel : ordre épingle Infini avant le bit de graine",
      dvm.find("skill.endless") < dvm.find("(seed and 1L) == 0L"))
check("DemoViewModel : AUCUN son (écart voulu)",
      "Sound" not in dvm and "beep" not in dvm and "sound" not in dvm)
check("DemoViewModel : délai en Duration, pas l'overload Long (règle CI)",
      "delay(tickMs.milliseconds)" in dvm
      and "kotlin.time.Duration.Companion.milliseconds" in dvm)
check("DemoActivity : tout contact referme la page (dispatch, pas de listener)",
      "dispatchTouchEvent" in da and "finish()" in da
      and "MotionEvent.ACTION_DOWN" in da
      and "setOnTouchListener" not in da)
check("DemoActivity : blips selon le réglage Animation",
      "Settings.loadAnimEnabled(this)" in da)
check("DemoActivity : cadrage TOUJOURS ajusté à chaque nouveau plateau (v10)",
      "applyZoomPreset(1f)" in da and "loadDemoZoom" not in da
      and "board !== shownBoard" in da)
check("DemoActivity : caméra ne recentre que si la tuile sort de la vue (UAT)",
      "BoardView.FollowMode.VISIBLE" in da
      and "BoardView.FollowMode.HYSTERESIS" not in da)
# V7 (retours utilisateurs du 16 nov. 2026 au soir) : le hint vit
# SOUS le bandeau de niveau, en PERMANENCE — le fondu est retiré
# (il ne réapparaissait qu'à la première partie de la session).
check("DemoActivity : indication « Touchez pour revenir » permanente (v7)",
      "fadeTouchHint" not in da and "TOUCH_HINT_MS" not in da
      and "FADE_MS" not in da)
check("Layout démo : le hint vit SOUS le bandeau, pile du bas",
      "@+id/demo_bottom_stack" in layout_demo
      and layout_demo.find("@+id/demo_level_banner")
      < layout_demo.find("@+id/demo_touch_hint"))
check("DemoActivity : pile du bas dégagée de la barre de navigation",
      "EdgeToEdge.padSystemBars(binding.demoBottomStack, bottom = true)" in da)
check("Demo : gel d'apparence UN SEUL chemin BoardView, coupure v7 retirée (v8)",
      "appearanceFreezeEnabled" not in bv and "appearanceFreezeEnabled" not in da
      and "val appearanceFrozen = animDurationMs.any { it > 0L }" in bv)
check("Layout démo : demo_root/demo_board/demo_touch_hint",
      "@+id/demo_root" in layout_demo and "@+id/demo_board" in layout_demo
      and "@+id/demo_touch_hint" in layout_demo)

# Lot 2 — retours utilisateurs du 16 nov. 2026 (mention de niveau,
# tuiles invisibles, brouillard du Néant).
check("Demo : nextPlayableMove — tête visible, invisibles en fin de file",
      "fun nextPlayableMove(queue: ArrayDeque<Int>, visible: BooleanArray?)" in dm
      and "queue.addLast(index)" in dm and "queue.addFirst(index)" in dm)
check("DemoViewModel : la démo ne révèle plus les tuiles masquées (UAT)",
      "reveal(" not in dvm)
check("DemoViewModel : au Néant, seules les tuiles VISIBLES sont jouées",
      "b.fogVisibility(b.connectedCells())" in dvm
      and "nextPlayableMove(queue, fog)" in dvm
      and "Demo.nextPlayableMove" not in dvm)
check("DemoActivity : mention « Démonstration niveau X » en bas de l'écran",
      "@+id/demo_level_banner" in layout_demo
      and "binding.demoLevelBanner.text" in da
      and "R.string.demo_level_banner" in da and "skillName" in da)
check("DemoActivity : le préréglage de zoom est posé APRÈS update",
      da.find("binding.demoBoard.applyZoomPreset(") > da.find("binding.demoBoard.update("))
solve_block = vm[vm.find("fun playOneSolveMoveIfNeeded"):vm.find("fun playOneSolveMoveIfNeeded") + 1600]
check("VM : le solveur ne révèle plus les tuiles masquées (décision d'eric)",
      "reveal(" not in solve_block
      and "board.rotateCell(index, steps)" in solve_block)
check("VM : l'indice correctif révèle toujours (coup montré, pas joué)",
      "if (board.isHidden(index)) board.reveal(index)" in vm)
check("DemoViewModel : le coup déjà en place CONSOMME la tête (plantage corrigé)",
      "queue.removeFirst()" in dvm and count(dvm, "queue.removeFirst()") == 1
      and "queue.addFirst(index)" not in dvm
      and "else -> queue.removeFirst()" in dvm)
preset = bv[bv.find("fun applyZoomPreset"):bv.find("fun applyZoomPreset") + 1800]
check("BoardView : préréglage démo — le Néant cadre la zone révélée",
      "Skill.VOID" in preset and "centerOnVisibleZone()" in preset
      and "maxCellPx" in preset and "Board.FOG_RADIUS_CELLS" in preset)
check("BoardView : le préréglage posé avant mesure attend le premier layout (v10)",
      "private var zoomPresetPending = 0f" in bv
      and "zoomPresetPending = multiplier" in bv
      and count(bv, "applyZoomPreset(pending)") == 1
      and bv.find("zoomPresetPending") < bv.find("applyArrivalCamera(solved)"))

# ---------------------------------------------------------------------------
# 10. Chaînes EN + FR.
# ---------------------------------------------------------------------------
hint_keys = ["menu_hint", "menu_hint_count", "menu_tools", "menu_demo",
             "hint_strategy_signal", "hint_strategy_fix", "hint_limit_title",
             "hint_limit_zero", "hint_limit_value",
             "demo_diff_random", "demo_diff_progressive", "demo_skill_title",
             "demo_speed_title", "demo_speed_slow",
             "demo_speed_normal", "demo_speed_fast", "demo_level_banner",
             "hints_section", "hints_total", "hints_none", "hints_n",
             "hints_column_tiles", "demo_touch_return"]
for k in hint_keys:
    check(f"Strings EN+FR : {k}", f'name="{k}"' in strings_en and f'name="{k}"' in strings_fr)
# V5 (retour d'eric du 15 nov. 2026) : la radio Éclair est retirée
# du layout ET des strings — plus de chaîne orpheline.
# V8 : la radio « Niveau fixe » est retirée du layout ET des
# strings (cocher une seule case la remplace) ; le français dit
# « Progressif », pas « Progressive » (retour d'eric).
check("Strings : la radio Niveau fixe est retirée (v8)",
      'demo_diff_fixed' not in strings_en
      and 'demo_diff_fixed' not in strings_fr
      and 'demo_diff_fixed' not in layout_settings)
check("Strings FR : le mode se nomme Progressif (v8)",
      '<string name="demo_diff_progressive">Progressif</string>' in strings_fr)
check("Strings : la radio Éclair est retirée (v5, retour d'eric)",
      'demo_speed_lightning' not in strings_en
      and 'demo_speed_lightning' not in strings_fr
      and 'demo_speed_lightning' not in layout_settings)
en_keys = set(re.findall(r'<string name="([a-z_0-9]+)"', strings_en))
fr_keys = set(re.findall(r'<string name="([a-z_0-9]+)"', strings_fr))
check("Strings : EN et FR portent exactement les mêmes clés", en_keys == fr_keys,
      f"seules EN {sorted(en_keys - fr_keys)}, seules FR {sorted(fr_keys - en_keys)}")
fr_bodies = re.findall(r'<string name="[^"]+">([\s\S]*?)</string>', strings_fr)
en_bodies = re.findall(r'<string name="[^"]+">([\s\S]*?)</string>', strings_en)
check("Strings FR : apostrophes droites échappées dans les éléments",
      all(re.search(r"[^\\]'", b) is None for b in fr_bodies))
check("Strings EN : apostrophes droites échappées dans les éléments",
      all(re.search(r"[^\\]'", b) is None for b in en_bodies))

# ---------------------------------------------------------------------------
# 11. Tests JVM.
# ---------------------------------------------------------------------------
check("HintTest : présent, porte priorités, plafond, scellement, démo",
      "hintTarget" in hint_test and "hintAllowance" in hint_test
      and "sealed" in hint_test.lower() and "DemoSpeed" in hint_test
      and "LIGHTNING" not in hint_test)
check("HintTest : au moins 10 tests",
      count(hint_test, "@Test") >= 10)
check("DemoTest : présent — file jouable, brouillard du Néant, résolution",
      "class DemoTest" in demo_test and "nextPlayableMove" in demo_test
      and "fogVisibility" in demo_test and "isSolved" in demo_test
      and count(demo_test, "@Test") >= 5)
check("DemoTest : régression du plantage — la boucle vide sa file",
      "the step loop consumes its queue and finishes the board" in demo_test
      and "queue.removeFirst()" in demo_test)
check("GameSpecTest : format v5 (22 champs, préfixe v5)",
      'camera round trips through the v5 format' in spec_test
      and '22, encoded.split(\u0027|\u0027).size' in spec_test
      and 'startsWith("v5|")' in spec_test)
check("GameSpecTest : champs d'indice + rejets",
      "hint fields round trip through the v5 format" in spec_test
      and "v4 snapshot decodes with hint fallbacks then re-encodes as v5" in spec_test
      and "v3 snapshot decodes without camera then re-encodes as v5" in spec_test)
hint_test_spec = spec_test[spec_test.find("hint fields round trip through the v5 format"):
                           spec_test.find("hint fields round trip through the v5 format") + 1600]
check("GameSpecTest : le test d'indices porte une caméra "
      "(TU KO chez eric : l'égalité spec == decoded est impossible "
      "sans caméra, le décode v5 en renvoie TOUJOURS une — piège v4 "
      "documenté)",
      "camera = CameraSpec(" in hint_test_spec
      and "camera = null" not in hint_test_spec)

# ---------------------------------------------------------------------------
# 12. Miroirs Python.
# ---------------------------------------------------------------------------
def allowance(base, percent):
    p = max(0, min(50, percent))
    if p == 0:
        return 0
    return max(1, base * p // 100)

check("Miroir hintAllowance : floor/minimum-1/désactivé/bornes",
      allowance(10, 50) == 5 and allowance(10, 0) == 0
      and allowance(10, 90) == 5 and allowance(3, 18) == 1
      and allowance(100, 10) == 10 and allowance(1, 50) == 1
      and allowance(7, 25) == 1 and allowance(0, 10) == 1)
check("Miroir plafond v12 : comptes fixes par niveau (référence MEDIUM)",
      allowance(25, 10) == 2 and allowance(25, 20) == 5
      and allowance(63, 10) == 6 and allowance(63, 20) == 12
      and allowance(77, 10) == 7 and allowance(77, 20) == 15
      and allowance(1600, 10) == 160 and allowance(1600, 20) == 320)

class FakeBoard:
    """Miroir minimal de Board : rotations, drapeaux, masques Dir."""
    BITS = [1, 2, 4, 8]  # UP, RIGHT, DOWN, LEFT — quart de tour horaire.

    def __init__(self, n, rotations, locked, touched, sealed, empty, bfs, conns):
        self.n = n
        self.rotations = rotations
        self.locked = locked
        self.touched = touched
        self.sealed = sealed
        self.empty = empty
        self.bfs = bfs
        self.conns = conns

    def is_empty(self, i):
        return self.empty[i]

    def current_dirs(self, i):
        out = 0
        for k, bit in enumerate(self.BITS):
            if self.conns[i] & bit:
                out |= self.BITS[(k + self.rotations[i]) % 4]
        return out

    def is_misplaced(self, i):
        # FONCTIONNEL : masque courant != masque de solution (une
        # tuile symétrique bien tournée ne compte pas).
        return not self.is_empty(i) and self.current_dirs(i) != self.conns[i]

    def solve_moves(self):
        return [i for i in self.bfs if not self.is_empty(i) and self.rotations[i] != 0]

    def hint_target(self):
        # Stratégie actée au retour UAT du 15 nov. 2026 : (1) les
        # verrouillées mal orientées, (2) sinon les non verrouillées,
        # tuiles à 3 connecteurs d'abord, puis la première par index.
        for it in range(self.n):
            if self.locked[it] and self.is_misplaced(it):
                return it
        fallback = -1
        for i in range(self.n):
            if self.locked[i] or not self.is_misplaced(i):
                continue
            if bin(self.conns[i]).count("1") == 3:
                return i
            if fallback < 0:
                fallback = i
        return fallback

def fb(**kw):
    n = kw.get("n", 6)
    d = dict(n=n, rotations=[0] * n, locked=[False] * n, touched=[False] * n,
             sealed=[False] * n, empty=[False] * n, bfs=list(range(n)),
             conns=[1] * n)
    d.update(kw)
    return FakeBoard(**d)

check("Miroir hintTarget : priorité 1 = verrouillée mal orientée (UAT)",
      fb(rotations=[1, 1, 0, 0, 0, 0], locked=[False, True, False, False, False, False],
         touched=[True, False, False, False, False, False]).hint_target() == 1
      and fb(rotations=[0, 2, 0, 0, 0, 0], locked=[True, False, False, False, False, False],
         touched=[False, True, False, False, False, False]).hint_target() == 1)
check("Miroir hintTarget : une verrouillée bien orientée n'est jamais soumise (UAT)",
      fb(rotations=[0, 1, 0, 0, 0, 0], locked=[True, False, False, False, False, False]).hint_target() == 1
      and fb(locked=[True, False, False, False, False, False]).hint_target() == -1)
check("Miroir hintTarget : parmi les non verrouillées, 3 connecteurs d'abord",
      fb(rotations=[1, 1, 0, 0, 0, 0], conns=[1, 7, 1, 1, 1, 1]).hint_target() == 1
      and fb(rotations=[1, 1, 0, 0, 0, 0], conns=[7, 1, 1, 1, 1, 1]).hint_target() == 0)
check("Miroir hintTarget : sans 3 connecteurs, première mal orientée par index",
      fb(rotations=[0, 1, 1, 0, 0, 0]).hint_target() == 1)
check("Miroir hintTarget : les cellules libres ne comptent jamais",
      fb(rotations=[0, 1, 0, 0, 0, 0], conns=[0, 1, 1, 1, 1, 1],
         empty=[True, False, False, False, False, False]).hint_target() == 1)
check("Miroir hintTarget : tuile symétrique bien tournée = pas de cible",
      fb(rotations=[2, 0, 0, 0, 0, 0], conns=[5, 1, 1, 1, 1, 1]).hint_target() == -1
      and fb(rotations=[1, 0, 0, 0, 0, 0], conns=[15, 1, 1, 1, 1, 1]).hint_target() == -1)

class SealBoard:
    """Miroir du scellement : toggleLock refuse, invariant scellé⊂verrouillé."""
    def __init__(self):
        self.locked = [False, False]
        self.sealed = [False, False]

    def toggle_lock(self, i):
        if self.sealed[i]:
            return False
        self.locked[i] = not self.locked[i]
        return True

    def seal(self, i):
        self.sealed[i] = True
        self.locked[i] = True

sb = SealBoard()
sb.locked[0] = True
sb.seal(0)
check("Miroir scellement : toggleLock refusé sur scellée, invariant tenu",
      sb.toggle_lock(0) is False and sb.locked[0] is True
      and sb.toggle_lock(1) is True and sb.locked[1] is True
      and all(s <= l for s, l in zip(sb.sealed, sb.locked)))

skills = ["NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE", "ENDLESS", "ABYSS", "VOID"]
start = 2
seq = [skills[(start + k) % 8] for k in range(11)]
check("Miroir démo progressive : monte d'un cran depuis le niveau choisi puis boucle (Infini incluse)",
      seq == ["EXPERT", "MASTER", "INSANE", "ENDLESS", "ABYSS", "VOID",
              "NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE"])

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------
print()
if failures:
    print(f"ECHEC : {len(failures)}/{checks} checks en échec")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"TOUT VERT : {checks} checks")
