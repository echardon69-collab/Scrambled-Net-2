@echo off
rem convert-flags.bat — Regénère les drapeaux du sélecteur de langue de
rem Scrambled Net 2 depuis les sources SVG de eric
rem (svg\flags\flag_*.svg : France, Union Jack, tête de Maure corse,
rem Alsace historique). Un drawable par drapeau, à hauteur uniforme
rem de 15 dp. Outil requis : Python 3 (gratuit).
rem
rem Usage (n'importe où, le script se place à la racine du dépôt) :
rem   tools\flags\convert-flags.bat
rem
rem Les drawables générés vont dans app\src\main\res\drawable\
rem (flag_fr, flag_en, flag_co, flag_gsw_fr :
rem SettingsActivity.langFlagFor). Pour changer un drapeau,
rem remplacez simplement le SVG puis relancez ce script. Les règles
rem VectorDrawable (contours, fill none, tailles) vivent dans
rem tools\flags\convert_flags.py, réutilisé par le banc d'essai
rem tools\flags\test_convert_flags.py.
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

echo == Génération des drapeaux du sélecteur ^(4 langues^) ==
%PYTHON% tools\flags\convert_flags.py svg\flags app\src\main\res\drawable
if errorlevel 1 exit /b 1

echo == Terminé ==
endlocal
