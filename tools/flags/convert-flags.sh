#!/bin/sh
# convert-flags.sh — Regénère les drapeaux du sélecteur de langue de
# Scrambled Net 2 depuis les sources SVG de eric (svg/flags/flag_*.svg :
# France, Union Jack, tête de Maure corse, Alsace historique). Un
# drawable par drapeau, à hauteur uniforme de 15 dp.
# Outil requis : Python 3 (gratuit).
#
# Usage (à la racine du dépôt) :
#   ./tools/flags/convert-flags.sh
#
# Les drawables générés vont dans app/src/main/res/drawable/ (flag_fr,
# flag_en, flag_co, flag_gsw_fr : SettingsActivity.langFlagFor). Pour
# changer un drapeau, remplacez simplement le SVG puis relancez ce
# script. Les règles VectorDrawable (contours, fill none, tailles)
# vivent dans tools/flags/convert_flags.py, réutilisé par le banc
# d'essai tools/flags/test_convert_flags.py.
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

echo "== Génération des drapeaux du sélecteur (4 langues) =="
"$PYTHON" tools/flags/convert_flags.py svg/flags app/src/main/res/drawable

echo "== Terminé =="
