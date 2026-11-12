#!/usr/bin/env python3
"""Validation du chantier « effet électricité » (TREE vs ORIGINAL).

Décision d'eric du 12 nov. 2026 : le joueur doit identifier d'un coup
d'œil le moteur qui a généré le plateau (le générateur est tiré à la
graine — pair TREE, impair ORIGINAL — donc jamais connu d'avance).
Périmètre arrêté : 1 + 2, sans révélation à la génération.

  1. SIGNATURE AU SERVEUR (BoardView) : le serveur respire, et son
     rythme signe le moteur — TREE : pulsation chorale métronomique
     (l'arbre couvrant ordonné) ; ORIGINAL : scintillement irrégulier
     (les essais de createNet). Seule la LUMINANCE (alpha) est
     modulée, jamais la teinte : rouge/vert gardent leur sens, la
     signature survit aux thèmes daltoniens. Coupée quand les
     animations sont coupées ou pendant le gel d'apparence.

  2. RYTHME D'ÉMISSION DES BLIPS (BlipField) : TREE garde le métronome
     strict de l'original (une génération sur six) ; ORIGINAL émet par
     à-coups, d'écarts tirés d'une instance DISTINCTE enracinée sur la
     graine (jamais le flux du générateur de plateau : la partie reste
     reproductible).

Ce script PARSE les sources (aucun littéral retapé à la main) et
SIMULE les deux logiques en miroir : ordonnanceur d'émission pour les
deux générateurs, respiration du serveur (bornes, hachage SplitMix64
porté à l'identique, déterminisme).
"""

import math
import random
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


board_src = read(f"{BASE}/game/Board.kt")
blip_src = read(f"{BASE}/game/BlipField.kt")
view_src = read(f"{BASE}/ui/BoardView.kt")

# ---------------------------------------------------------------------------
# 1. Board : la graine est exposée (lectures de signature, pas de flux).
# ---------------------------------------------------------------------------
constructor = re.search(r"class Board\(.*?\) \{", board_src, re.S).group(0)
check("Board : la graine est une propriété du CONSTRUCTEUR (val seed: Long)",
      re.search(r"^\s+val seed: Long,", constructor, re.M) is not None)
check("Board : le paramètre nu n'y figure plus (la factory garde le sien)",
      re.search(r"^\s+seed: Long,", constructor, re.M) is None)

# ---------------------------------------------------------------------------
# 2. BlipField : le rythme d'émission signe le générateur.
# ---------------------------------------------------------------------------
check("Blips : TREE garde le métronome strict de l'original",
      "generation % SERVER_EVERY_GENERATIONS == 0" in blip_src)
check("Blips : ORIGINAL émet par écarts (nextServerEmission)",
      "generation >= nextServerEmission" in blip_src)
check("Blips : les écarts sont tirés de la graine, instance DISTINCTE",
      "Random(board.seed xor JITTER_SEED)" in blip_src
      and "val seed: Long" in board_src)
check("Blips : l'écart suivant est retiré à CHAQUE émission (bornes)",
      "ORIGINAL_EMISSION_MIN_GENERATIONS,\n"
      "                    ORIGINAL_EMISSION_MAX_GENERATIONS + 1\n"
      "                )" in blip_src)

every = int(re.search(r"SERVER_EVERY_GENERATIONS = (\d+)", blip_src).group(1))
gap_min = int(re.search(
    r"ORIGINAL_EMISSION_MIN_GENERATIONS = (\d+)", blip_src).group(1))
gap_max = int(re.search(
    r"ORIGINAL_EMISSION_MAX_GENERATIONS = (\d+)", blip_src).group(1))
check("Blips : constantes (TREE 1/6, ORIGINAL écarts 2 à 10)",
      every == 6 and gap_min == 2 and gap_max == 10)
check("Blips : premier écart tiré à la création, ORIGINAL seulement",
      re.search(r"if \(board\.generator == Generator\.ORIGINAL\) \{\s*"
                r"nextServerEmission = jitter\.nextInt", blip_src) is not None)

# Miroir de l'ordonnanceur : TREE strict, ORIGINAL irrégulier mais borné.
# (Le tirage exact de kotlin.random n'est pas reproduit : un générateur
#  stand-in fixe valide l'ALGORITHME — bornes, premier écart, cadence.)
rng = random.Random(20261112)
draws = [rng.randint(gap_min, gap_max) for _ in range(50)]

def mirror_emissions(generator):
    generations = []
    next_emission = draws[0] if generator == "ORIGINAL" else 0
    k = 1
    for generation in range(60):
        emit = (generation % every == 0 if generator == "TREE"
                else generation >= next_emission)
        if emit:
            generations.append(generation)
            if generator == "ORIGINAL":
                next_emission = generation + draws[k]
                k += 1
    return generations

tree_emits = mirror_emissions("TREE")
check("Miroir : TREE émet exactement aux générations 0, 6, 12, 18, ...",
      tree_emits == list(range(0, 60, every)))
orig_emits = mirror_emissions("ORIGINAL")
gaps = [b - a for a, b in zip(orig_emits, orig_emits[1:])]
first = orig_emits[0]
check("Miroir : ORIGINAL commence dans les bornes",
      gap_min <= first <= gap_max)
check("Miroir : chaque écart ORIGINAL reste dans les bornes 2 à 10",
      all(gap_min <= g <= gap_max for g in gaps))
check("Miroir : les écarts ORIGINAL sont bien irréguliers",
      len(set(gaps)) >= 3)
check("Miroir : la cadence moyenne d'ORIGINAL ressemble à TREE",
      abs(sum(gaps) / len(gaps) - every) <= 2)

# ---------------------------------------------------------------------------
# 3. BoardView : le serveur respire, signature des deux générateurs.
# ---------------------------------------------------------------------------
check("Vue : la respiration du serveur existe (serverBreathAlpha)",
      "private fun serverBreathAlpha(b: Board, now: Long): Int" in view_src)
check("Vue : TREE pulse au cosinus, période dédiée",
      "SERVER_BREATH_PERIOD_MS" in view_src
      and "cos(pulsation)" in view_src)
check("Vue : ORIGINAL scintille par pas interpolés (flickerValue)",
      "SERVER_FLICKER_STEP_MS" in view_src
      and "flickerValue(b.seed, step)" in view_src
      and "flickerValue(b.seed, step + 1L)" in view_src)
check("Vue : la teinte n'est JAMAIS touchée (alpha seul)",
      "setTint" not in view_src.split("serverBreathAlpha")[1].split("fun ")[0]
      and "setColorFilter" not in view_src)
gate = re.search(r"if \(index == b\.serverIndex &&\s*\n"
                 r"\s*blipsEnabled && !appearanceFrozen\) \{", view_src)
check("Vue : respiration seulement serveur, animations vives, hors gel",
      gate is not None)

period = int(re.search(r"SERVER_BREATH_PERIOD_MS = (\d+)", view_src).group(1))
step_ms = int(re.search(r"SERVER_FLICKER_STEP_MS = (\d+)", view_src).group(1))
min_alpha = int(re.search(r"SERVER_BREATH_MIN_ALPHA = (\d+)",
                          view_src).group(1))
check("Vue : constantes de signature (1600 ms, 140 ms, alpha bas 170)",
      period == 1600 and step_ms == 140 and min_alpha == 170)

# Miroir exact de flickerValue : SplitMix64 (finisseur) porté tel quel.
# Les constantes ont leur bit de poids fort à 1 : le Kotlin les écrit
# en complément à deux NÉGATIF (littéraux hexadécimaux signés) — le
# miroir emploie les mêmes valeurs, le masque 64 bits fait le reste.
MASK = (1 << 64) - 1
SPLITMIX_GOLDEN = -0x61C8864680B583EB   # = 0x9E3779B97F4A7C15 mod 2^64
SPLITMIX_FINALIZER = -0x40A7B892E31B1A47  # = 0xBF58476D1CE4E5B9 mod 2^64

def flicker_value(seed, step):
    z = (seed ^ ((step * SPLITMIX_GOLDEN) & MASK)) & MASK
    z = ((z ^ (z >> 30)) * SPLITMIX_FINALIZER) & MASK
    z = z ^ (z >> 31)
    return (z >> 40) / float(1 << 24)

check("Vue : constantes SplitMix64 en complément à deux négatif",
      "-0x61C8864680B583EBL" in view_src
      and "-0x40A7B892E31B1A47L" in view_src
      and "0x9E3779B97F4A7C15L" not in view_src
      and "0xBF58476D1CE4E5B9L" not in view_src)

values = [flicker_value(12345, s) for s in range(1000)]
check("Miroir : SplitMix64 borné dans [0, 1[",
      all(0.0 <= v < 1.0 for v in values))
check("Miroir : SplitMix64 déterministe et non constant",
      values == [flicker_value(12345, s) for s in range(1000)]
      and len(set(values)) == 1000)

def breath_alpha(generator, seed, now):
    span = 255 - min_alpha
    if generator == "TREE":
        phase = (now % period) / period
        value = 0.5 - 0.5 * math.cos(2.0 * math.pi * phase)
    else:
        step = now // step_ms
        frac = (now % step_ms) / step_ms
        value = (flicker_value(seed, step)
                 + (flicker_value(seed, step + 1)
                    - flicker_value(seed, step)) * frac)
    return int(min_alpha + span * value)

tree_alphas = [breath_alpha("TREE", 1, t) for t in range(0, 3200, 7)]
orig_alphas = [breath_alpha("ORIGINAL", 987654321, t)
               for t in range(0, 3200, 7)]
check("Miroir : alphas TREE bornés [170, 255], périodiques",
      all(170 <= a <= 255 for a in tree_alphas)
      and tree_alphas[0] == breath_alpha("TREE", 1, period))
check("Miroir : alphas ORIGINAL bornés [170, 255], variés, pas métronome",
      all(170 <= a <= 255 for a in orig_alphas)
      and len(set(orig_alphas)) >= 30
      and orig_alphas != tree_alphas)

# ---------------------------------------------------------------------------
# 4. Structure : sources bien formées.
# ---------------------------------------------------------------------------
for name, src in (("Board", board_src), ("BlipField", blip_src),
                  ("BoardView", view_src)):
    check(f"Structure : {name} — accolades et parenthèses équilibrées",
          src.count("{") == src.count("}")
          and src.count("(") == src.count(")"))
    check(f"Structure : {name} — pas de « /* » dans un KDoc",
          "/*" not in re.sub(r"^\s*/\*\*.*?\*/", "", src, flags=re.S | re.M))

print()
if failures:
    print(f"ÉCHEC : {len(failures)} vérification(s) en échec "
          f"sur {checks} :")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"TOUT PASSE : {checks} vérifications")
