#!/bin/sh
# generate-tiles.sh — Regénère les tuiles câbles de Scrambled Net 2 depuis
# les sources SVG de skins (svg/tiles/cable_*.svg) : 5 formes de base,
# chacune en variante normale et grise (parité greyOut de l'original).
# Outil requis : Python 3 (gratuit).
#
# Usage (à la racine du dépôt) :
#   ./tools/tiles/generate-tiles.sh
#
# Les drawables générés vont dans app/src/main/res/drawable/ ; les 15
# orientations du jeu sont des rotations au dessin (BoardView), pas des
# fichiers distincts. Pour changer de skin, éditez simplement les SVG
# puis relancez ce script.
set -e
cd "$(dirname "$0")/../.."

PYTHON=""
for cmd in python3 python; do
    if command -v "$cmd" >/dev/null 2>&1; then
        PYTHON="$cmd"
        break
    fi
done
if [ -z "$PYTHON" ]; then
    echo "Erreur : Python 3 requis (python3 ou python dans le PATH)." >&2
    exit 1
fi

echo "== Génération des tuiles câbles (5 formes x 2 variantes) =="
"$PYTHON" tools/tiles/convert_tiles.py svg/tiles app/src/main/res/drawable

echo "== Terminé =="
