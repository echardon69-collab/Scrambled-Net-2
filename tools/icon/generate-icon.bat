@echo off
rem generate-icon.bat — Regénère l'icône de lancement de Scrambled Net 2
rem depuis le SVG source (svg/icon.svg). Outil requis : Python 3 (gratuit).
rem Optionnel : Inkscape 1.x pour l'export PNG (mode --png).
rem
rem Usage (n'importe où, le script se place à la racine du dépôt) :
rem   tools\icon\generate-icon.bat          icône vectorielle
rem   tools\icon\generate-icon.bat --png    + PNGs 48→512 px dans
rem                                        build\icon-png\ (Inkscape requis)
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

echo == Génération de l'icône vectorielle ==
%PYTHON% tools\icon\convert_icon.py svg\icon.svg app\src\main\res
if errorlevel 1 exit /b 1

if "%~1"=="--png" (
    where inkscape >nul 2>&1
    if not errorlevel 1 (
        echo == Export PNG ^(Inkscape^) ==
        if not exist build\icon-png mkdir build\icon-png
        for %%S in (48 72 96 144 192 512) do (
            inkscape svg\icon.svg --export-type=png --export-filename="build\icon-png\icon-%%S.png" --export-width=%%S
            echo   build\icon-png\icon-%%S.png
        )
    ) else (
        echo Inkscape absent : export PNG ignoré ^(optionnel^). >&2
    )
)

echo == Terminé ==
endlocal
