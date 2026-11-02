@echo off
rem generate-tiles.bat — Regénère les tuiles câbles de Scrambled Net 2
rem depuis les sources SVG de skins (svg\tiles\cable_*.svg) : 5 formes
rem de base, chacune en variante normale et grise (parité greyOut de
rem l'original). Outil requis : Python 3 (gratuit).
rem
rem Usage (n'importe où, le script se place à la racine du dépôt) :
rem   tools\tiles\generate-tiles.bat
rem
rem Les drawables générés vont dans app\src\main\res\drawable\ ; les 15
rem orientations du jeu sont des rotations au dessin (BoardView), pas
rem des fichiers distincts. Pour changer de skin, éditez simplement les
rem SVG puis relancez ce script.
setlocal
cd /d "%~dp0\..\.."

set "PYTHON="
py -3 -c "import sys" >nul 2>&1
if not errorlevel 1 set "PYTHON=py -3"
if not defined PYTHON (
    python -c "import sys" >nul 2>&1
    if not errorlevel 1 set "PYTHON=python"
)
if not defined PYTHON (
    echo Erreur : Python 3 requis ^(py -3 ou python dans le PATH^). >&2
    exit /b 1
)

echo == Génération des tuiles câbles ^(5 formes x 2 variantes^) ==
%PYTHON% tools\tiles\convert_tiles.py svg\tiles app\src\main\res\drawable
if errorlevel 1 exit /b 1

echo == Terminé ==
endlocal
