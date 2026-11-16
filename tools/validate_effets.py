#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « Effets spéciaux de réussite » (15 nov. 2026,
spec validée par eric : tirage aléatoire parmi les effets, 4,5 s,
dialogue de victoire en attente de la fin, skip au toucher, vague
partant d'un point hors de la zone visible qui soulève TOUT le
plateau visible, fusées tirées du bas de la zone, explosion du
plateau reportée en V2). Gâchette : victoire SANS aide — zéro indice
ET solveur non utilisé (WinEvent porte déjà les deux champs) — et
réglage Animation actif ; la victoire aidée garde la fenêtre
actuelle, le solveur reste muet comme dans l'original.

Mise à jour du 16 nov. 2026 (retours UAT d'eric, chantier
« Modifications diverses ») : les effets couvrent l'ENSEMBLE de
l'écran — le feu d'artifice vit en repère écran (toute la vue,
sans pan), la vague soulève aussi le décor autour du plateau ;
la vague est amplifiée (0,70 case, largeur 3 cases, +8 %) et
devient DEUX vagues successives sur des côtés différents, la
seconde partant à mi-effet (2 250 ms), chaque front couvrant
l'écran en 2,25 s ; durée totale inchangée (4,5 s).

Deux parties, comme les validateurs précédents :
  1. Vérification statique : les sources sont PARSÉES.
  2. Miroirs Python : profil de vague, vitesse du front, tirage.

Usage : python3 tools/validate_effets.py
"""

import math
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
TESTS = ROOT / "app/src/test/kotlin/net/chardons/netscramble2/game"

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


ve = read(KOTLIN / "VictoryEffects.kt")
bv = read(KOTLIN / "ui/BoardView.kt")
ma = read(KOTLIN / "MainActivity.kt")
dvm = read(KOTLIN / "DemoViewModel.kt")
da = read(KOTLIN / "DemoActivity.kt")
vt = read(TESTS / "VictoryEffectTest.kt")
readme = read(ROOT / "README.md")

print("=== Chantier Effets spéciaux de réussite — modèle pur ===")

check("Effets : V1 = vague + feu d'artifice, explosion en V2",
      "enum class VictoryEffect { WAVE, FIREWORK }" in ve
      and "EXPLOSION" not in ve)
check("Effets : durée 4,5 s (décision d'eric « plus long »)",
      "EFFECT_MS = 4_500L" in ve)
check("Effets : budget 600 étincelles vivantes",
      "FIREWORK_MAX_SPARKS = 600" in ve)
check("Effets : tirage uniforme borné (floorMod, testable)",
      "fun pick(roll: Int" in ve
      and "Math.floorMod(roll, VictoryEffect.entries.size)" in ve)
check("Effets : profil gaussien, exactement nul aux bornes",
      "fun waveProfile(" in ve and "exp(-d * d)" in ve
      and "d <= -3f || d >= 3f" in ve)
check("Effets : le front couvre l'écran en 2,25 s (par vague)",
      "hypot(visibleW, visibleH) * 1.25f" in ve)
check("Effets : amplitudes UAT (0,70 case, +8 % de tuile, largeur 3)",
      "WAVE_LIFT_CELLS = 0.70f" in ve
      and "WAVE_SCALE_FRACTION = 0.08f" in ve
      and "WAVE_WIDTH_CELLS = 3f" in ve)
check("Effets : DEUX vagues, la seconde part à mi-durée (retour UAT)",
      "WAVE_COUNT = 2" in ve
      and "WAVE2_DELAY_MS = EFFECT_MS / WAVE_COUNT" in ve)
check("Feu d'artifice : pools PRÉ-ALLOUÉS, zéro allocation par trame",
      "private val px = FloatArray(VictoryEffects.FIREWORK_MAX_SPARKS)" in ve
      and count(ve, "inline fun") == 0)
check("Feu d'artifice : graine unique par victoire (reproductible)",
      "Random(seed)" in ve)
check("Feu d'artifice : fusées montées depuis le BAS de la zone",
      "height - rRise[i] * t" in ve)
check("Feu d'artifice : fin nette, fondu 500 ms",
      "FADE_MS = 500L" in ve)
check("Feu d'artifice : teintes ARGB en code (zéro ressource graphique)",
      "0xFFFFD54F.toInt()" in ve and "PALETTE" in ve)
check("Feu d'artifice : lecture par accesseurs (jamais de lambda par trame)",
      "fun sparkSlots()" in ve and "fun isAlive(i: Int)" in ve
      and "fun sparkAlpha(i: Int, nowMs: Long)" in ve)

print()
print("=== BoardView : état, dessin, boucle de trame, toucher ===")

check("BoardView : état de l'effet + écouteur de fin (dialogue)",
      "private var victoryEffect: VictoryEffect? = null" in bv
      and "var onVictoryEffectFinished: (() -> Unit)? = null" in bv)
check("BoardView : la vague part HORS de la zone visible (4 côtés)",
      "worldTop - margin" in bv and "worldLeft - margin" in bv
      and "worldLeft + worldW + margin" in bv
      and "worldTop + worldH + margin" in bv)
check("BoardView : DEUX origines de vague (la seconde autre côté)",
      "wave2OriginX" in bv and "wave2OriginY" in bv
      and "placeWaveOrigin(" in bv)
check("BoardView : vitesse du front posée depuis le modèle",
      "VictoryEffects.waveSpeed(worldW, worldH)" in bv)
check("BoardView : feu d'artifice en repère ÉCRAN (toute la vue, retour UAT)",
      "VictoryFirework(victoryStartMs, width.toFloat(), height.toFloat())" in bv)
check("BoardView : passe cellulaire drawVictoryCell (les deux régimes)",
      "private fun Canvas.drawVictoryCell(" in bv
      and count(bv, "drawVictoryCell(") == 3)
check("BoardView : corps de drawBoardCell inchangé (le wrap n'y touche pas)",
      "private fun Canvas.drawBoardCell(" in bv
      and count(bv, "drawBoardCell(") == 3)
check("BoardView : ombre au sol + translation, léger grossissement",
      "withTranslation(0f, -lift)" in bv
      and "VictoryEffects.WAVE_SCALE_FRACTION * t" in bv
      and "victoryShadowPaint.alpha" in bv)
check("BoardView : le soulèvement lit le profil pur du modèle",
      "VictoryEffects.waveProfile(" in bv
      and "VictoryEffects.WAVE_LIFT_CELLS * cellPx" in bv)
check("BoardView : le décor autour du plateau suit la vague (retour UAT)",
      "private fun Canvas.drawVictoryDecor(" in bv
      and count(bv, "drawVictoryDecor(") >= 2
      and "drawVictoryDecor(decor, now, x * cellPx, top)" in bv)
check("BoardView : skip au toucher (avant tout le reste du geste)",
      "override fun onTouchEvent(event: MotionEvent): Boolean {" in bv
      and count(bv, "endVictoryEffect()") == 3
      and bv.find("if (victoryEffect != null)") < bv.find("zoomDetector.onTouchEvent(event)"))
check("BoardView : l'effet ne survit pas au plateau suivant",
      "L'effet de réussite ne survit PAS au plateau suivant" in bv)
check("BoardView : fin du temps à 4,5 s, écouteur prévenu",
      "now - victoryStartMs >= VictoryEffects.EFFECT_MS" in bv)
check("BoardView : feu d'artifice AU-DESSUS, plein écran SANS pan (UAT)",
      "fun Canvas.drawFireworkOverlay" in bv)
overlay = bv[bv.find("fun Canvas.drawFireworkOverlay"):bv.find("private fun Canvas.drawFogCell")]
check("BoardView : l'overlay vit en repère écran (zéro pan)",
      "+ panX" not in overlay and "+ panY" not in overlay
      and "drawLine(" in overlay and len(overlay) > 400)
check("BoardView : driver d'effet APRÈS les conducteurs épinglés",
      "if (blipsEnabled || animDurationMs.any { it > 0L } || blinkIndex >= 0) {" in bv
      and bv.find("if (panAnimating) {") < bv.rfind("if (victoryEffect != null) {"))
check("BoardView : peintures allouées UNE fois, en champs",
      "private val victoryShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)" in bv
      and "private val victorySparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)" in bv)
on_draw = bv[bv.find("override fun onDraw"):bv.find("// M2 : tant que la vue est affichée")]
check("BoardView : zéro allocation dans le corps de onDraw",
      " Paint(" not in on_draw and " FloatArray(" not in on_draw
      and "drawFireworkOverlay(now)" in on_draw)
check("BoardView : ni SurfaceView, ni OpenGL — Canvas 2D et faux relief",
      "SurfaceView" not in bv and "GLES" not in bv
      and "GLSurface" not in bv)
check("BoardView : aucune ressource ajoutée pour les effets",
      "R.drawable.victory" not in bv and "R.color.victory" not in bv)
# V10 (retour utilisateur du 16 nov. 2026) : pendant la vague, les
# billes restaient au sol pendant que les tuiles se soulevaient —
# affichées hors des connecteurs. La passe des blips reçoit le même
# lift que la passe des cellules.
check("BoardView : vague — les billes suivent les tuiles soulevées (v10)",
      count(bv, "top - waveLiftPx(now, left, top)") == 2
      and count(bv, "drawCellBlips(") == 3)

print()
print("=== MainActivity : gâchette, dialogue en attente ===")

check("MainActivity : gâchette sans aide (zéro indice, solveur non utilisé)",
      "if (event.solverUsed || event.hints > 0 || !animEnabled) {" in ma)
check("MainActivity : l'effet est tiré au sort par le modèle",
      "playVictoryEffect(VictoryEffects.pick())" in ma)
check("MainActivity : le dialogue ATTEND la fin de l'effet",
      "pendingWinEvent" in ma and "onVictoryEffectFinished" in ma
      and "private fun onVictory(event: WinEvent)" in ma)
check("MainActivity : dialogue direct sinon (fenêtre actuelle)",
      count(ma, "showWinDialog(event)") >= 2)
check("MainActivity : l'écouteur est posé une fois, à la création",
      "binding.boardView.onVictoryEffectFinished = {" in ma)

print()
print("=== Démonstration : l'intermède remplace la pause de 1,5 s ===")

check("DemoViewModel : intermède = effet si animation, repli 1,5 s",
      "if (effectsEnabled) VictoryEffects.EFFECT_MS else WIN_PAUSE_MS" in dvm
      and "WIN_PAUSE_MS = 1_500L" in dvm)
check("DemoViewModel : l'activité lui annonce l'état du réglage",
      "fun setEffectsEnabled(enabled: Boolean)" in dvm)
check("DemoActivity : effet à chaque victoire de plateau, tiré au sort",
      "playVictoryEffect(VictoryEffects.pick())" in da and "lastSolved" in da)
check("DemoActivity : l'état d'animation est lu UNE fois et partagé",
      "val anim = Settings.loadAnimEnabled(this)" in da
      and "viewModel.setEffectsEnabled(anim)" in da)
check("DemoActivity : l'effet s'annule au plateau suivant (reset du suivi)",
      "lastSolved = false" in da)

print()
print("=== Tests unitaires ===")

check("VictoryEffectTest : présent, budget, reproductibilité, profil",
      "class VictoryEffectTest" in vt and count(vt, "@Test") >= 5
      and "aliveCount()" in vt and "VictoryFirework(" in vt
      and "waveProfile(" in vt)

print()
print("=== README : feuille de route à jour ===")

check("README : « Effets spéciaux de réussite » livré le 15 nov. 2026",
      "Effets spéciaux de réussite" in readme
      and "vague 2.5D" in readme and "feu d'artifice" in readme
      and "livré le 15 nov. 2026" in readme
      and "explosion du plateau est reportée" in readme)
check("README : « Modifications diverses » livré le 16 nov. 2026 (UAT)",
      "Modifications diverses" in readme
      and "livré le 16 nov. 2026" in readme)

print()
print("=== Miroirs Python ===")


def profile(delta, cell):
    if cell <= 0.0:
        return 0.0
    d = delta / (3.0 * cell)
    if d <= -3.0 or d >= 3.0:
        return 0.0
    return math.exp(-d * d)


check("Miroir profil : pic au front, bornes nettes, symétrie",
      abs(profile(0.0, 40.0) - 1.0) < 1e-9
      and profile(360.0, 40.0) == 0.0
      and profile(-360.0, 40.0) == 0.0
      and abs(profile(-40.0, 40.0) - profile(40.0, 40.0)) < 1e-12
      and 0.0 <= profile(123.0, 40.0) <= 1.0)


def front(visible_w, visible_h, duration_ms=2250):
    return math.hypot(visible_w, visible_h) * 1.25 * duration_ms / 1000.0


check("Miroir vitesse : CHAQUE front dépasse la diagonale en 2,25 s",
      front(1080.0, 1920.0) > math.hypot(1080.0, 1920.0)
      and front(320.0, 240.0) > math.hypot(320.0, 240.0))

check("Miroir tirage : floorMod, jamais d'effet hors bornes",
      all(0 <= r % 2 < 2 for r in (-5, -1, 0, 1, 7, 100)))

print()
if failures:
    print(f"TOUT ROUGE : {len(failures)} échec(s) sur {checks} checks")
    sys.exit(1)
print(f"TOUT VERT : {checks} checks")
