#!/bin/sh
# generate-icon.sh — Regénère l'icône de lancement de Scrambled Net 2 depuis
# le SVG source (svg/icon.svg). Outil requis : Python 3 (gratuit). Optionnel :
# Inkscape 1.x pour l'export PNG (mode --png).
#
# Usage (à la racine du dépôt) :
#   ./tools/icon/generate-icon.sh          # icône vectorielle (foreground,
#                                          # monochrome, repli, adaptative)
#   ./tools/icon/generate-icon.sh --png    # + PNGs 48→512 px dans
#                                          # build/icon-png/ (Inkscape requis)
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

echo "== Génération de l'icône vectorielle =="
"$PYTHON" tools/icon/convert_icon.py svg/icon.svg app/src/main/res

if [ "$1" = "--png" ]; then
    if command -v inkscape >/dev/null 2>&1; then
        echo "== Export PNG (Inkscape) =="
        mkdir -p build/icon-png
        for size in 48 72 96 144 192 512; do
            inkscape svg/icon.svg --export-type=png \
                --export-filename="build/icon-png/icon-${size}.png" \
                --export-width="$size"
            echo "  build/icon-png/icon-${size}.png"
        done
    else
        echo "Inkscape absent : export PNG ignoré (optionnel)." >&2
    fi
fi

echo "== Terminé =="
