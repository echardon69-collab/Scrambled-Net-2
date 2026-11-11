@echo off
rem generate-graphics.bat — Génère les drawables du jeu depuis le SVG
rem unique à calques (svg\netscramble2-sources.svg) selon la composition
rem déclarée dans tools\graphics\graphics.conf.
rem Outil requis : Python 3 (gratuit).
rem
rem Usage (n'importe où, le script se place à la racine du dépôt) :
rem   tools\graphics\generate-graphics.bat           (génère)
rem   tools\graphics\generate-graphics.bat --check    (valide sans écrire)
rem   tools\graphics\generate-graphics.bat --check graphics-eric.conf
rem     (la config passée en argument remplace graphics.conf par défaut)
rem
rem Les drawables générés vont dans app\src\main\res\drawable\ (ou le
rem répertoire output de la section [svg] de graphics.conf). Le banc
rem d'essai tools\graphics\test_generate_graphics.py vérifie l'outil
rem indépendamment du SVG.
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

echo == Génération des éléments graphiques ^(SVG unique à calques^) ==
%PYTHON% tools\graphics\generate_graphics.py %*
if errorlevel 1 exit /b 1

echo == Terminé ==
endlocal
