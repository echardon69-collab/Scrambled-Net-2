#!/bin/sh
# generate-graphics.sh — Génère les drawables du jeu depuis le SVG
# unique à calques (svg/netscramble2-sources.svg) selon la composition
# déclarée dans tools/graphics/graphics.conf.
# Outil requis : Python 3 (gratuit).
#
# Usage (à la racine du dépôt) :
#   ./tools/graphics/generate-graphics.sh          (génère)
#   ./tools/graphics/generate-graphics.sh --check  (valide sans écrire)
#   ./tools/graphics/generate-graphics.sh --check graphics-eric.conf
#     (la config passée en argument remplace graphics.conf par défaut)
#
# Les drawables générés vont dans app/src/main/res/drawable/ (ou le
# répertoire output de la section [svg] de graphics.conf). Éditez le
# SVG (dans Inkscape par exemple) et/ou la config, puis relancez ce
# script. Le banc d'essai tools/graphics/test_generate_graphics.py
# vérifie l'outil indépendamment du SVG.
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

echo "== Génération des éléments graphiques (SVG unique à calques) =="
"$PYTHON" tools/graphics/generate_graphics.py "$@"

echo "== Terminé =="
