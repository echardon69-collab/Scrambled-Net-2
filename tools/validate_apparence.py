#!/usr/bin/env python3
"""Validation du chantier « l'apparence suit la rotation ».

Correctif : pendant l'animation de rotation d'une cellule (250 ms par
quart de tour), la cellule gardait déjà le bon masque mais basculait
IMMÉDIATEMENT à son apparence finale (câble énergisé au lieu de grisé,
écran de terminal allumé, DEL pleines du serveur, blips reçus) alors
qu'elle n'avait pas fini de tourner. Premier correctif : gel de la
cellule qui tourne jusqu'à la fin de sa rotation.

Extension (retest utilisateur : « le problème est le même avec les
AUTRES tuiles que celle qui tourne ») : un coup peut alimenter ou
priver d'énergie une branche entière, et les autres tuiles basculaient
QU'AU TAP — la tuile pivot gardant encore son ancien état, le réseau
paraissait à moitié à jour pendant l'animation. Le gel s'étend donc au
PLATEAU ENTIER : tant qu'une rotation est en cours quelque part,
toutes les tuiles gardent l'apparence d'avant le coup (câbles gris ou
énergisés, écrans, DEL du serveur, blips), et tout bascule au même
moment quand la rotation s'achève.

V8b (retest du 16 nov. 2026 : « la connexion se met encore en
évidence PENDANT la rotation, en démonstration ») : le snap v8 ne
terminait que la rotation de la cellule du NOUVEAU coup ; les
rotations en vol sur d'AUTRES cellules continuaient d'animer, et
le gel — recapturé à chaque coup — relâchait l'énergie du coup
précédent pendant que SA rotation volait encore. Désormais, à
l'arrivée d'un coup, TOUTE rotation en vol est finie d'un coup,
AVANT de lancer les nouvelles rotations.

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

check("BoardView : les 2 états d'apparence gelée sont déclarés (v8)",
      "private var animFromConnected = BooleanArray(0)" in view_src
      and "private var animFromServerWired = false" in view_src
      and "lastDrawnConnected" not in view_src
      and "lastDrawnServerWired" not in view_src)

check("BoardView : le gel est réinitialisé à chaque nouveau plateau (v8)",
      view_src.count("animFromConnected = BooleanArray(board.size)") == 1
      and "animFromServerWired = false" in view_src)

check("BoardView : la détection de rotation fige l'apparence "
      "du PLATEAU ENTIER sur l'état publié du coup PRÉCÉDENT (v8)",
      "animFromConnected = prev.copyOf()" in view_src
      and "animFromServerWired = serverIsFullyWired(board, prev)" in view_src
      and "var rotating = false" in view_src
      and "if (rotating) {" in view_src)

check("BoardView : plus de gel limité à la seule cellule qui tourne",
      "animFromConnected[i] = lastDrawnConnected[i]" not in view_src
      and "if (i == board.serverIndex)" not in view_src)

check("BoardView : le drapeau de gel couvre TOUTE rotation en cours",
      "val appearanceFrozen = animDurationMs.any { it > 0L }" in view_src)

# V8 (retours du 16 nov. 2026) : l'énergie doit apparaître APRÈS la fin
# de la rotation, jamais PENDANT. Une rotation en vol est snappée à sa
# fin (angle final + rabot des tours complets) quand un nouveau coup
# arrive ; la coupure v7 (flag appearanceFreezeEnabled) est retirée —
# un seul chemin de gel, dans BoardView.
# V8b : le snap porte sur TOUTE rotation en vol (pas seulement la
# cellule du nouveau coup) et PRÉCÈDE la boucle des nouvelles
# rotations — sans quoi le gel recapturé relâche l'énergie du coup
# précédent pendant que sa rotation vole encore.
check("BoardView : TOUTE rotation en vol snappée à l'arrivée d'un coup, avant les nouvelles rotations (v8b)",
      view_src.count("visualCurrent[i] = visualTarget[i]") == 1
      and "appearanceFreezeEnabled" not in view_src
      and 0 < view_src.find("animDurationMs[i] = 0L")
      < view_src.find("if (delta != 0) {"))

check("BoardView : l'apparence dessinée passe par l'état gelé "
      "pendant une rotation",
      "if (appearanceFrozen) animFromConnected[index]" in view_src
      and "else connected[index]" in view_src)

check("BoardView : plus de lecture directe de l'état relié "
      "pour l'apparence",
      "val isConnected = connected[index]" not in view_src)

check("BoardView : l'icône du serveur est gelée pendant une rotation (v8)",
      "if (appearanceFrozen) animFromServerWired" in view_src
      and "lastDrawnServerWired" not in view_src)

check("BoardView : aucune passe de blips pendant une rotation",
      "blipsEnabled && !appearanceFrozen" in view_src
      and "if (animDurationMs[index] > 0L) continue" not in view_src)

check("BoardView : les blips sont toujours vidés au début du coup",
      "field?.clear(i)" in view_src)

check("BoardView : la rotation reste animée 250 ms par quart de tour",
      "ROTATE_MS_PER_QUARTER = 250L" in view_src)

check("BoardView : la boucle de trame reste active pendant les rotations",
      "blipsEnabled || animDurationMs.any { it > 0L }" in view_src)

# ---------------------------------------------------------------------------
# 2. Vérification statique : le comportement du modèle est inchangé.
# ---------------------------------------------------------------------------

check("GameViewModel : le modèle tourne toujours instantanément au tap",
      "board.rotateCell(index, 1, byPlayer = true)" in vm_src)
check("GameViewModel : le coup est toujours publié immédiatement",
      vm_src.count("publish()") >= 5)

# ---------------------------------------------------------------------------
# 3. Miroir : simulation image par image d'une rotation.
#    Le gel est celui du PLATEAU ENTIER : toutes les cellules d'un coup
#    basculent ensemble, à la fin de la rotation.
# ---------------------------------------------------------------------------

ROTATE_MS = 250  # par quart de tour (ROTATE_MS_PER_QUARTER)


class Plateau:
    """Miroir du plateau : rotation en cours (une à la fois), état
    modèle instantané, gel de TOUTES les apparences sur l'état
    PUBLIÉ d'avant le coup — celui du coup précédent (v8) : une
    rotation en vol est finie d'un coup au coup suivant, donc
    l'énergie du coup N apparaît quand SA rotation finit."""

    def __init__(self, connected):
        self.anim_ms = 0
        self.model = list(connected)
        self.frozen = list(connected)
        self.drawn = list(connected)

    def tap(self, new_model):
        """Début de coup : le modèle tourne aussitôt, le plateau ENTIER
        est gelé sur l'état publié d'avant ce coup (snap v8 de la
        rotation en vol, s'il y en avait une)."""
        self.anim_ms = ROTATE_MS
        self.frozen = list(self.model)
        self.model = list(new_model)

    def frame(self, dt_ms):
        """Avance de dt_ms puis rendu : renvoie les apparences
        dessinées, cellule par cellule."""
        self.anim_ms = max(0, self.anim_ms - dt_ms)
        self.drawn = (list(self.frozen) if self.anim_ms > 0
                      else list(self.model))
        return list(self.drawn)


# Cas 1 : la rotation CONNECTE la cellule pivot (le bug signalé).
# L'apparence doit rester GRISE pendant toute la rotation (trames 50 à
# 200 ms), puis s'allumer à la trame de fin (250 ms), comme le fait le
# Kotlin (t >= 1 -> animDurationMs = 0 -> état du modèle).
p = Plateau([False])
p.tap([True])
appearances = [p.frame(50) for _ in range(6)]  # trames 50..300 ms
check("Miroir : pivot relié au final, mais grise jusqu'à la fin "
      "de la rotation",
      appearances == [[False]] * 4 + [[True]] * 2)

# Cas 2 : la rotation DÉCONNECTE la cellule pivot (cas symétrique).
# Elle reste énergisée pendant toute la rotation, puis s'éteint.
p = Plateau([True])
p.tap([False])
appearances = [p.frame(50) for _ in range(6)]
check("Miroir : pivot déconnecté au final, mais énergisé jusqu'à la fin",
      appearances == [[True]] * 4 + [[False]] * 2)

# Cas 3 (retest utilisateur) : le coup alimente AUSSI une AUTRE tuile
# que celle qui tourne. L'autre tuile ne doit PAS s'allumer au tap :
# elle bascule EN MÊME TEMPS que la pivot, à la fin de la rotation.
# (Avant l'extension, elle s'allumait immédiatement — le réseau
# paraissait à moitié à jour pendant l'animation.)
p = Plateau([False, False])
p.tap([True, True])
appearances = [p.frame(50) for _ in range(6)]
check("Miroir : l'AUTRE tuile alimentée bascule en même temps que la pivot",
      appearances == [[False, False]] * 4 + [[True, True]] * 2)

# Cas 4 (retest utilisateur, symétrique) : le coup déconnecte la pivot
# et une branche entière derrière elle. Toute la branche reste
# énergisée pendant la rotation, puis s'éteint ensemble.
p = Plateau([True, True, True])
p.tap([False, False, False])
appearances = [p.frame(50) for _ in range(6)]
check("Miroir : une branche entière coupée s'éteint d'un seul tenant",
      appearances == [[True, True, True]] * 4
      + [[False, False, False]] * 2)

# Cas 5 : une tuile ÉPARGNÉE par le coup (reliée avant et après) ne
# clignote jamais : gel = son état courant, modèle = son état courant.
p = Plateau([True, True])
p.tap([True, False])  # la pivot reste reliée, l'autre se déconnecte
appearances = [p.frame(50) for _ in range(6)]
check("Miroir : une tuile épargnée par le coup ne clignote pas",
      appearances == [[True, True]] * 4 + [[True, False]] * 2)

# Cas 6 : deux coups enchaînés sur la même cellule (180 degrés). En
# v8, le snap termine la 1re rotation d'un coup au 2e coup : le gel
# du 2e coup part de l'état publié du 1er (déjà alimenté), jamais
# d'état intermédiaire visible.
p = Plateau([False])
p.tap([True])            # 1er quart de tour
seen = [p.frame(100)]    # trame à 100 ms : apparence d'avant le coup
p.tap([True])            # 2e quart de tour avant la fin du 1er :
seen.append(p.frame(100))  # snap : le gel part de l'état du 1er coup
seen.append(p.frame(100))  # trame à 300 ms du 2e coup : encore gelée
seen.append(p.frame(100))  # trame à 400 ms : bascule enfin
check("Miroir : coups enchaînés (v8), le snap laisse voir l'état "
      "du coup précédent, jamais un intermédiaire",
      seen == [[False]] + [[True]] * 3)

# Cas 7 : après la bascule, l'état dessiné rattrape le modèle (les
# coups suivants gèlent sur la VRAIE apparence vue à l'écran).
p = Plateau([False])
p.tap([True])
for _ in range(6):
    p.frame(50)          # bascule effective à la 5e trame
p.tap([False])           # nouveau coup : le gel part de l'état vu
seen = [p.frame(50) for _ in range(6)]
check("Miroir : le 2e coup gèle sur l'apparence réellement vue à l'écran",
      seen == [[True]] * 4 + [[False]] * 2)

# Cas 8 : les DEL du serveur suivent le même gel de plateau entier —
# le coup débranche la dernière DEL : elles basculent après la
# rotation, pas au tap.
p = Plateau([True])
p.tap([False])
appearances = [p.frame(50) for _ in range(6)]
check("Miroir : les DEL du serveur basculent aussi après la rotation",
      appearances == [[True]] * 4 + [[False]] * 2)

# Cas 9 (v8b, retest) : coups enchaînés sur deux cellules
# DIFFÉRENTES, plus vite que la rotation (démo rapide). Le snap
# termine la 1re rotation au coup suivant : l'énergie du 1er coup
# apparaît à CET instant (fin de SA rotation), jamais pendant ; et
# l'énergie du 2e coup n'apparaît qu'à la fin de la sienne.
p = Plateau([False, False])
p.tap([True, False])    # coup 1 : cellule a reliée, rotation en vol
seen = [p.frame(100)]   # pendant SA rotation : rien ne s'allume
p.tap([True, True])     # coup 2 : snap de la rotation 1, gel sur l'état publié
seen.append(p.frame(100))  # a allumé (rotation 1 finie), b pas encore
seen.append(p.frame(100))  # rotation 2 en vol : b encore gris
seen.append(p.frame(100))  # fin de la rotation 2 : b allumé
check("Miroir : cellules différentes, l'énergie suit la FIN de chaque rotation (v8b)",
      seen == [[False, False], [True, False], [True, False], [True, True]])

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
