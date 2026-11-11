#!/usr/bin/env python3
"""Validation du chantier « l'apparence suit la rotation ».

Correctif : pendant l'animation de rotation d'une cellule (250 ms par
quart de tour), la cellule gardait déjà le bon masque mais basculait
IMMÉDIATEMENT à son apparence finale (câble énergisé au lieu de grisé,
écran de terminal allumé, DEL pleines du serveur, blips reçus) alors
qu'elle n'avait pas fini de tourner. Le correctif gèle l'apparence sur
celle d'avant le coup jusqu'à la fin de la rotation.

Ce script PARSE les sources (aucun littéral retapé à la main) et
SIMULE la logique d'affichage image par image.
"""

import re
import sys

BASE = "app/src/main/kotlin/net/chardons/netscramble2"
checks = 0
failures = []


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def check(label, ok):
    global checks
    checks += 1
    print(f"[{'OK  ' if ok else 'FAIL'}] {label}")
    if not ok:
        failures.append(label)


view_src = read(f"{BASE}/ui/BoardView.kt")
vm_src = read(f"{BASE}/GameViewModel.kt")

# ---------------------------------------------------------------------------
# 1. Vérification statique : BoardView.kt.
# ---------------------------------------------------------------------------

check("BoardView : les 4 états d'apparence gelée sont déclarés",
      "private var animFromConnected = BooleanArray(0)" in view_src
      and "private var lastDrawnConnected = BooleanArray(0)" in view_src
      and "private var animFromServerWired = false" in view_src
      and "private var lastDrawnServerWired = false" in view_src)

check("BoardView : le gel est réinitialisé à chaque nouveau plateau",
      view_src.count("animFromConnected = BooleanArray(board.size)") == 1
      and view_src.count("lastDrawnConnected = BooleanArray(board.size)") == 1
      and "animFromServerWired = false" in view_src
      and "lastDrawnServerWired = false" in view_src)

check("BoardView : la détection de rotation fige l'apparence d'avant le coup",
      "animFromConnected[i] = lastDrawnConnected[i]" in view_src
      and "animFromServerWired = lastDrawnServerWired" in view_src)

check("BoardView : le gel ne s'applique qu'au serveur quand c'est lui qui tourne",
      "if (i == board.serverIndex)" in view_src)

check("BoardView : l'apparence dessinée passe par l'état gelé en rotation",
      "if (animDurationMs[index] > 0L) animFromConnected[index]" in view_src
      and "else connected[index]" in view_src)

check("BoardView : l'état dessiné est mémorisé pour le prochain gel",
      "lastDrawnConnected[index] = isConnected" in view_src)

check("BoardView : plus de lecture directe de l'état relié pour l'apparence",
      "val isConnected = connected[index]" not in view_src)

check("BoardView : l'icône du serveur est gelée pendant sa rotation",
      "if (animDurationMs[b.serverIndex] > 0L) animFromServerWired" in view_src
      and "lastDrawnServerWired = serverWired" in view_src)

check("BoardView : aucun blip dessiné sur une cellule en rotation",
      "if (animDurationMs[index] > 0L) continue" in view_src)

check("BoardView : les blips sont toujours vidés au début du coup",
      "field?.clear(i)" in view_src)

check("BoardView : la rotation reste animée 250 ms par quart de tour",
      "ROTATE_MS_PER_QUARTER = 250L" in view_src)

# ---------------------------------------------------------------------------
# 2. Vérification statique : le comportement du modèle est inchangé.
# ---------------------------------------------------------------------------

check("GameViewModel : le modèle tourne toujours instantanément au tap",
      "board.rotateCell(index, 1)" in vm_src)
check("GameViewModel : le coup est toujours publié immédiatement",
      vm_src.count("publish()") >= 5)

# ---------------------------------------------------------------------------
# 3. Miroir : simulation image par image d'une rotation.
# ---------------------------------------------------------------------------

ROTATE_MS = 250  # par quart de tour (ROTATE_MS_PER_QUARTER)


class Cellule:
    """Miroir d'une cellule animée : angle, apparence gelée, état modèle."""

    def __init__(self, connected):
        self.anim_remaining_ms = 0
        self.model_connected = connected
        self.frozen_connected = connected
        self.last_drawn_connected = connected


def tap(cell, new_connected):
    """Début de coup : le modèle tourne, l'apparence est gelée."""
    cell.anim_remaining_ms = ROTATE_MS
    cell.model_connected = new_connected
    cell.frozen_connected = cell.last_drawn_connected


def frame(cell, dt_ms):
    """Avance de dt_ms puis rendu : renvoie l'apparence dessinée."""
    cell.anim_remaining_ms = max(0, cell.anim_remaining_ms - dt_ms)
    drawn = (cell.frozen_connected if cell.anim_remaining_ms > 0
             else cell.model_connected)
    cell.last_drawn_connected = drawn
    return drawn


# Cas 1 : la rotation CONNECTE la cellule (le bug signalé). L'apparence
# doit rester GRISE pendant toute la rotation (trames 50 à 200 ms), puis
# s'allumer à la trame de fin (250 ms), comme le fait le Kotlin
# (t >= 1 -> animDurationMs = 0 -> état du modèle).
c = Cellule(connected=False)
appearances = []
tap(c, new_connected=True)
for _ in range(6):
    appearances.append(frame(c, 50))  # trames à 50, 100, ..., 300 ms
check("Miroir : reliée au final, mais grise jusqu'à la fin de la rotation",
      appearances == [False, False, False, False, True, True])

# Cas 2 : la rotation DÉCONNECTE la cellule (le cas symétrique). La
# cellule reste énergisée pendant toute la rotation, puis s'éteint.
c = Cellule(connected=True)
appearances = []
tap(c, new_connected=False)
for _ in range(6):
    appearances.append(frame(c, 50))
check("Miroir : déconnectée au final, mais énergisée jusqu'à la fin",
      appearances == [True, True, True, True, False, False])

# Cas 3 : deux coups enchaînés sur la même cellule (180 degrés). Le gel
# doit se poursuivre sans jamais laisser voir un état intermédiaire.
c = Cellule(connected=False)
tap(c, new_connected=True)
seen = [frame(c, 100)]      # trame à 100 ms : apparence d'avant le coup
tap(c, new_connected=True)  # 2e quart de tour avant la fin du 1er :
seen.append(frame(c, 100))  # le gel repart de l'état réellement vu
seen.append(frame(c, 100))  # trame à 300 ms du 2e coup : encore gelée
seen.append(frame(c, 100))  # trame à 400 ms : bascule enfin
check("Miroir : coups enchaînés, l'apparence d'avant le coup tient jusqu'au bout",
      seen == [False, False, False, True])

# Cas 4 : après la bascule, l'état dessiné rattrape le modèle (les coups
# suivants gèlent sur la VRAIE apparence vue à l'écran, pas une ancienne).
c = Cellule(connected=False)
tap(c, new_connected=True)
for _ in range(6):
    frame(c, 50)              # bascule effective à la 5e trame
tap(c, new_connected=False)   # nouveau coup : le gel part de l'état vu
seen = [frame(c, 50) for _ in range(6)]
check("Miroir : le 2e coup gèle sur l'apparence réellement vue à l'écran",
      seen == [True, True, True, True, False, False])

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
