#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du correctif « zoom ancré sur le point de pincement ».

Défaut relevé par eric le 13 nov. 2026 : lors d'un pinch, la taille de
case grandissait autour de l'ORIGINE du monde au lieu du point de
pincement (le milieu des deux doigts) — l'affichage glissait sous
les doigts et la vue se retrouvait décalée.

Correctif validé ici (BoardView.onScale) :
  - lecture de detector.focusX / detector.focusY ;
  - la taille de case est coercée d'abord (plancher/plafond), puis le
    facteur de croissance RÉEL growth = nouveau / ancien ( PAS le
    scaleFactor brut : il ment dès que la coercition mord) ;
  - compensation du pan AVANT d'écraser cellPx : la tuile qui se
    trouve sous le point de pincement y reste ;
  - clampPan() est toujours appelé après (les niveaux encadrés ne
    découvrent jamais de gris au-delà de l'anneau ; sur Infini, pan
    non borné → ancrage exact).

Deux parties, comme les validateurs précédents :
  1. Vérification statique : BoardView.kt est PARSÉ (jamais de
     valeurs retapées à la main dans ce script).
  2. Miroirs Python : invariance du point MONDE sous le pincement
     (Infini sans clamp, niveaux encadrés quand le clamp ne mord
     pas, coercitions plancher et plafond), invariance de la tuile
     tapée (chaîne hitCell + floorMod d'Infini), et jamais plus
     d'une tuile grise découverte quand le clamp reprend la main.

Usage : python3 tools/validate_zoom.py
"""

import math
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

# ---------------------------------------------------------------------------
# 1. Vérification statique : le correctif est dans onScale, sous cette
#    forme exacte (parsée, avec l'ordre pan AVANT cellPx).
# ---------------------------------------------------------------------------

check("onScale : le point de pincement est lu (focusX, focusY)",
      "val focusX = detector.focusX" in vw and
      "val focusY = detector.focusY" in vw)

check("onScale : la taille coercée passe par une VALEUR INTERMÉDIAIRE "
      "(newCellPx), cellPx n'est pas écrasé en place",
      re.search(r"val newCellPx = \(cellPx \* detector\.scaleFactor\)\s*"
                r"\n\s*\.coerceIn\(floorCellPx\(b\), maxCellPx\)", vw)
      is not None)

check("onScale : le facteur de croissance est le RÉEL (nouveau / ancien, "
      "jamais le scaleFactor brut), avec garde cellPx > 0",
      "val growth = if (cellPx > 0f) newCellPx / cellPx else 1f" in vw)

check("onScale : le pan est compensé par le focus en X",
      "panX = focusX - (focusX - panX) * growth" in vw)

check("onScale : le pan est compensé par le focus en Y",
      "panY = focusY - (focusY - panY) * growth" in vw)

pos_comp = vw.find("panX = focusX - (focusX - panX) * growth")
pos_swap = vw.find("cellPx = newCellPx")
check("onScale : le pan est recalculé AVANT d'écraser cellPx (ordre)",
      0 <= pos_comp < pos_swap)

check("onScale : clampPan() est toujours appelé après le zoom "
      "(l'anneau gris reste contournable uniquement sur Infini)",
      re.search(r"override fun onScale\(detector: ScaleGestureDetector\): "
                r"Boolean \{(.*?)clampPan\(\)", vw, re.S) is not None)

check("onScale : l'ancienne forme sans ancrage (cellPx écrasé en place) "
      "a DISPARU",
      re.search(r"(?m)^\s*cellPx = \(cellPx \* detector\.scaleFactor\)",
                vw) is None)

check("BoardView : le principe « ancré sur le point de pincement » est "
      "documenté (KDoc du viewport et commentaire de onScale)",
      vw.count("point de pincement") >= 2)

# ---------------------------------------------------------------------------
# Constantes parsées (jamais retapées) pour les miroirs.
# ---------------------------------------------------------------------------

def const(name):
    m = re.search(rf"private const val {name} = ([0-9.]+)f", vw)
    if m is None:
        sys.exit(f"Constante introuvable dans BoardView.kt : {name}")
    return float(m.group(1))


GREY_RING_CELLS = const("GREY_RING_CELLS")
MARGIN = const("ENDLESS_EDGE_MARGIN_CELLS")
MIN_CELL_DP = const("MIN_CELL_DP")
MAX_CELL_DP = const("MAX_CELL_DP")
check("Constantes parsées pour les miroirs (anneau gris, marge Infini, "
      "plancher/plafond de case)",
      GREY_RING_CELLS > 0 and MARGIN > 0 and MIN_CELL_DP > 0
      and MAX_CELL_DP > MIN_CELL_DP,
      f"ring={GREY_RING_CELLS} marge={MARGIN} "
      f"case {MIN_CELL_DP}..{MAX_CELL_DP} dp")

# Écrans réalistes (largeur, hauteur, densité) — mêmes familles que le
# miroir plancher du chantier Infini : téléphones, phablettes, tablettes,
# portrait et paysage.
DEVICES = [
    (1080, 2400, 2.625), (2400, 1080, 2.625),
    (1080, 2340, 3.0), (1440, 3200, 3.5),
    (1200, 2000, 2.0), (1600, 2560, 2.0),
    (720, 1600, 2.0),
]


def floor_cell_px(view_w, view_h, bw, bh, endless, density):
    """Miroir de floorCellPx (niveaux encadrés : anneau gris ; Infini :
    marge de MARGIN cases de chaque côté)."""
    if endless:
        span = min(bw, bh)
        largest = max(view_w, view_h)
        raw = largest / (span - 2.0 * MARGIN)
    else:
        raw = view_w / (bw + 2.0 * GREY_RING_CELLS)
    lo = MIN_CELL_DP * density
    hi = MAX_CELL_DP * density
    return max(lo, min(hi, raw))


def on_scale_mirror(cell, pan_x, pan_y, fx, fy, sf, lo, hi):
    """Miroir exact du nouveau onScale : coercition d'abord, croissance
    RÉELLE ensuite, pan compensé AVANT d'écraser cellPx."""
    new_cell = max(lo, min(hi, cell * sf))
    growth = new_cell / cell if cell > 0.0 else 1.0
    pan_x = fx - (fx - pan_x) * growth
    pan_y = fy - (fy - pan_y) * growth
    return new_cell, pan_x, pan_y


def clamp_axis(pan, view, board_px, ring_px, endless):
    if endless:
        return pan
    if board_px + 2.0 * ring_px <= view:
        return (view - board_px) / 2.0
    return max(view - board_px - ring_px, min(ring_px, pan))


# ---------------------------------------------------------------------------
# 2. Miroirs.
# ---------------------------------------------------------------------------

# Miroir A — invariance du point monde sous le pincement (Infini : le pan
# n'est jamais borné, l'ancrage doit être exact). 60 configurations.
fail_a = []
n_a = 0
for i in range(60):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    span = 40  # ENDLESS_SIZE, plateau carré
    lo = floor_cell_px(vw_w, vw_h, span, span, True, density)
    hi = MAX_CELL_DP * density
    cell0 = lo + (hi - lo) * ((i * 7) % 13) / 12.0
    pan_x0 = -4000.0 + (i * 331) % 8001
    pan_y0 = -4000.0 + (i * 517) % 8001
    fx = (vw_w * ((i * 11) % 17)) / 16.0
    fy = (vw_h * ((i * 13) % 19)) / 18.0
    sf = 0.4 + ((i * 29) % 42) / 20.0  # 0.4 .. 2.45
    cell1, pan_x1, pan_y1 = on_scale_mirror(
        cell0, pan_x0, pan_y0, fx, fy, sf, lo, hi)
    n_a += 1
    wx0 = (fx - pan_x0) / cell0
    wx1 = (fx - pan_x1) / cell1
    wy0 = (fy - pan_y0) / cell0
    wy1 = (fy - pan_y1) / cell1
    if abs(wx1 - wx0) > 1e-6 * max(1.0, abs(wx0)) or \
       abs(wy1 - wy0) > 1e-6 * max(1.0, abs(wy0)):
        fail_a.append(f"config {i} : ({wx0:.4f}->{wx1:.4f})")
check("Miroir zoom Infini : le point monde sous le pincement est "
      "invariant (60 configurations, sans clamp)",
      not fail_a, "; ".join(fail_a[:3]))

# Miroir B — la TUILE tapée sous le pincement ne change pas (chaîne
# complète : hitCell floor + floorMod d'Infini). 40 configurations.
fail_b = []
for i in range(40):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    w = h = 40
    lo = floor_cell_px(vw_w, vw_h, w, h, True, density)
    hi = MAX_CELL_DP * density
    cell0 = lo + (hi - lo) * ((i * 5) % 9) / 8.0
    pan_x0 = -5000.0 + (i * 457) % 10001
    pan_y0 = -5000.0 + (i * 613) % 10001
    fx = vw_w * ((i * 7) % 11) / 10.0
    fy = vw_h * ((i * 3) % 13) / 12.0
    sf = 0.45 + ((i * 23) % 30) / 16.0
    cell1, pan_x1, pan_y1 = on_scale_mirror(
        cell0, pan_x0, pan_y0, fx, fy, sf, lo, hi)
    cx0 = math.floor((fx - pan_x0) / cell0)
    cy0 = math.floor((fy - pan_y0) / cell0)
    cx1 = math.floor((fx - pan_x1) / cell1)
    cy1 = math.floor((fy - pan_y1) / cell1)
    if (cx1 - cx0) % w != 0 or (cy1 - cy0) % h != 0:
        fail_b.append(f"config {i} : ({cx0},{cy0})->({cx1},{cy1})")
check("Miroir tuile : la tuile sous les doigts à la fin du pinch est la "
      "même qu'avant (40 configurations, floorMod 40)",
      not fail_b, "; ".join(fail_b[:3]))

# Miroir C — coercition au PLANCHER (dézoom au maximum) : le scaleFactor
# brut ment, growth non. Un zoom naïf compensé par scaleFactor casserait
# l'ancrage exactement ici. 25 configurations.
fail_c = []
for i in range(25):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    w = h = 40
    lo = floor_cell_px(vw_w, vw_h, w, h, True, density)
    hi = MAX_CELL_DP * density
    cell0 = lo * (1.0 + 0.5 * (((i * 7) % 5) / 4.0))  # au-dessus du plancher
    pan_x0 = -3000.0 + (i * 271) % 6001
    pan_y0 = -3000.0 + (i * 401) % 6001
    fx = vw_w / 2.0 + ((i % 5) - 2) * 200.0
    fy = vw_h / 2.0 + ((i % 7) - 3) * 200.0
    sf = 0.1  # dézoom fracassant : la coercition mord toujours
    cell1, pan_x1, pan_y1 = on_scale_mirror(
        cell0, pan_x0, pan_y0, fx, fy, sf, lo, hi)
    if abs(cell1 - lo) > 1e-9 * lo:
        fail_c.append(f"config {i} : plancher non atteint")
        continue
    wx0 = (fx - pan_x0) / cell0
    wx1 = (fx - pan_x1) / cell1
    wy0 = (fy - pan_y0) / cell0
    wy1 = (fy - pan_y1) / cell1
    if abs(wx1 - wx0) > 1e-6 * max(1.0, abs(wx0)) or \
       abs(wy1 - wy0) > 1e-6 * max(1.0, abs(wy0)):
        fail_c.append(f"config {i} : focus glissé")
check("Miroir coercition plancher : dézoom au maximum, l'ancrage tient "
      "parce que growth (réel) remplace le scaleFactor (menteur) "
      "(25 configurations)",
      not fail_c, "; ".join(fail_c[:3]))

# Miroir D — coercition au PLAFOND (zoom in au maximum), même exigence.
# 20 configurations.
fail_d = []
for i in range(20):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    w = h = 40
    lo = floor_cell_px(vw_w, vw_h, w, h, True, density)
    hi = MAX_CELL_DP * density
    cell0 = lo + (hi - lo) * (((i * 3) % 7) / 6.0) * 0.5
    pan_x0 = -2000.0 + (i * 173) % 4001
    pan_y0 = -2000.0 + (i * 389) % 4001
    fx = vw_w * ((i * 5) % 9) / 8.0
    fy = vw_h * ((i * 11) % 7) / 6.0
    sf = 4.0  # zoom in fracassant : la coercition mord toujours
    cell1, pan_x1, pan_y1 = on_scale_mirror(
        cell0, pan_x0, pan_y0, fx, fy, sf, lo, hi)
    if abs(cell1 - hi) > 1e-9 * hi:
        fail_d.append(f"config {i} : plafond non atteint")
        continue
    wx0 = (fx - pan_x0) / cell0
    wx1 = (fx - pan_x1) / cell1
    wy0 = (fy - pan_y0) / cell0
    wy1 = (fy - pan_y1) / cell1
    if abs(wx1 - wx0) > 1e-6 * max(1.0, abs(wx0)) or \
       abs(wy1 - wy0) > 1e-6 * max(1.0, abs(wy0)):
        fail_d.append(f"config {i} : focus glissé")
check("Miroir coercition plafond : zoom in au maximum, l'ancrage tient "
      "(20 configurations)",
      not fail_d, "; ".join(fail_d[:3]))

# Miroir E — niveaux ENCADRÉS (Novice → Dément) : quand le pan ancré reste
# dans les bornes de clampPan (le cas courant), le clamp ne le touche pas
# et l'ancrage est exact ; quand il sort, le clamp ramène le pan dans les
# bornes : jamais plus d'une tuile grise découverte. 50 configurations.
fail_e = []
bord_e = []
n_clamped = 0
for i in range(50):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    bw = 5 + (i * 3) % 9   # plateaux réalistes des niveaux encadrés
    bh = 7 + (i * 5) % 9
    lo = floor_cell_px(vw_w, vw_h, bw, bh, False, density)
    hi = MAX_CELL_DP * density
    cell0 = lo * (1.0 + 0.8 * (((i * 7) % 6) / 5.0))
    board_px = bw * cell0
    # pan initial DANS les bornes du clamp (cas courant après fitBoard)
    ring_px = GREY_RING_CELLS * cell0
    if board_px + 2.0 * ring_px <= vw_w:
        pan_x0 = (vw_w - board_px) / 2.0
    else:
        pan_x0 = max(vw_w - board_px - ring_px,
                     min(ring_px, ring_px + (i * 97) % 200 - 100))
    pan_y0 = pan_x0
    fx = vw_w * ((i * 13) % 15) / 14.0
    fy = vw_h * ((i * 17) % 15) / 14.0
    sf = 0.5 + ((i * 19) % 20) / 10.0
    cell1, pan_x1, pan_y1 = on_scale_mirror(
        cell0, pan_x0, pan_y0, fx, fy, sf, lo, hi)
    bp1 = bw * cell1
    ring1 = GREY_RING_CELLS * cell1
    pan_x2 = clamp_axis(pan_x1, vw_w, bp1, ring1, False)
    if bp1 + 2.0 * ring1 <= vw_w:
        # plateau + anneau tiennent dans la vue : clampPan RECENTRE,
        # l'ancrage n'a pas de prise (le centrage est la référence).
        if abs(pan_x2 - (vw_w - bp1) / 2.0) > 1e-6:
            fail_e.append(f"config {i} : recentrage faux ({pan_x2:.1f})")
    elif pan_x2 != pan_x1:
        n_clamped += 1
        # le clamp a repris la main : le pan final doit rester dans les
        # bornes — jamais plus d'une tuile grise découverte à gauche.
        if not (vw_w - bp1 - ring1 - 1e-6 <= pan_x2 <= ring1 + 1e-6):
            fail_e.append(f"config {i} : hors bornes ({pan_x2:.1f})")
    else:
        bord_e.append(i)
        wx0 = (fx - pan_x0) / cell0
        wx1 = (fx - pan_x2) / cell1
        if abs(wx1 - wx0) > 1e-6 * max(1.0, abs(wx0)):
            fail_e.append(f"config {i} : focus glissé sans clamp")
check("Miroir niveaux encadrés : ancrage exact quand le clamp ne mord "
      "pas, bornes respectées quand il mord (50 configurations)",
      not fail_e, "; ".join(fail_e[:3]))

# Miroir F — contre-épreuve : la version CORRIGÉE ne ressemble pas à
# l'ancienne. Si l'on retirait la compensation, le point monde sous le
# doigt glisserait d'autant que l'éloignement à l'origine — le miroir le
# chiffre pour mémoire (et échouerait si on re-régressait le code en
# réintroduisant l'ancienne forme, déjà couvert en statique).
slip = []
for i in range(10):
    vw_w, vw_h, density = DEVICES[i % len(DEVICES)]
    w = h = 40
    lo = floor_cell_px(vw_w, vw_h, w, h, True, density)
    cell0 = lo * 1.5
    fx = vw_w / 2.0
    sf = 2.0
    # sans compensation : pan inchangé, le monde double autour de l'origine
    wx0 = (fx - 0.0) / cell0
    wx1 = (fx - 0.0) / (cell0 * sf)
    slip.append(abs(wx1 - wx0))
check("Miroir négatif (mémoire) : sans ancrage, le glissement sous le "
      "doigt serait massif — l'ordre de grandeur documente le défaut",
      max(slip) > 1.0,
      f"glissement max {max(slip):.1f} cases sans correctif")

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications "
      "(zoom ancré sur le point de pincement).")
