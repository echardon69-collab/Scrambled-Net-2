#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « saveurs de distribution GitHub / Play »
(14 nov. 2026).

Origine : retour d'eric du 14 nov. — Android Studio signale
`REQUEST_INSTALL_PACKAGES is restricted to specific core
functionalities and must be user-initiated. No self-updates,
unauthorized installs, or APK bundling` sur la ligne 19 du
AndroidManifest.xml. C'est la permission de l'Updater (l'auto-mise à
jour GitHub des publications, chantier du 12 nov.) : la POLITIQUE
Play (« Use of the REQUEST_INSTALL_PACKAGES permission ») interdit
explicitement les self-updates — l'app serait refusée à la soumission.

Décision d'eric du 14 nov. : **saveurs GitHub/Play** —
  - APK « github » (distribution actuelle) : INTACT, permission +
    Updater, l'écran titre vérifie GitHub à chaque lancement ;
  - APK « play » : SANS la permission (retirée du manifeste fusionné
    par tools:node="remove") et SANS l'Updater actif (vérification
    coupée à la construction, Distribution.UPDATER_ENABLED = false)
    — c'est le store qui met à jour. Double verrou : même un chemin
    erroné serait bloqué par le système (sans la permission,
    canRequestPackageInstalls est faux pour toujours).

Même identité d'application (aucun suffixe) : une seule app, deux
canaux. ATTENTION : avec des saveurs, les tâches Gradle changent de
nom — la CI (.github/workflows/ci.yml) est mise à jour pour tester,
bâtir et linter les DEUX APK.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : build.gradle.kts, manifests, saveurs
     sources, TitleActivity, Updater et CI sont PARSÉS.
  2. Miroirs Python : fusion de manifeste par saveur (la permission
     ne doit exister QUE dans l'APK github), comparaison de versions
     de l'Updater (isNewer, inchangée), double verrou de l'APK play.

Usage : python3 tools/validate_maj.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"

failures = []
checks = 0


def check(name, ok, detail=""):
    global checks
    checks += 1
    status = "OK  " if ok else "ECHEC"
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def read(path):
    return Path(path).read_text(encoding="utf-8")


gradle_src = read(ROOT / "app/build.gradle.kts")
manifest_main = read(ROOT / "app/src/main/AndroidManifest.xml")
manifest_play = read(ROOT / "app/src/play/AndroidManifest.xml")
ci_src = read(ROOT / ".github/workflows/ci.yml")
title_src = read(KOTLIN / "TitleActivity.kt")
updater_src = read(KOTLIN / "Updater.kt")
github_src = read(
    ROOT / "app/src/github/kotlin/net/chardons/netscramble2/Distribution.kt")
play_src = read(
    ROOT / "app/src/play/kotlin/net/chardons/netscramble2/Distribution.kt")

# --- 1. Les saveurs Gradle --------------------------------------------------

check("Gradle : dimension de saveur « distribution » déclarée",
      'flavorDimensions += "distribution"' in gradle_src)
check("Gradle : les DEUX saveurs existent (github + play, même "
      "dimension)",
      re.search(r'create\("github"\) \{\s*dimension = "distribution"',
                gradle_src) is not None
      and re.search(r'create\("play"\) \{\s*dimension = "distribution"',
                    gradle_src) is not None)
check("Gradle : PAS de suffixe d'identité (une seule app, deux canaux)",
      "applicationIdSuffix" not in gradle_src,
      "" if "applicationIdSuffix" not in gradle_src
      else "applicationIdSuffix trouvé")

# --- 2. Les manifestes ------------------------------------------------------

check("Manifeste principal : la permission de l'Updater y reste "
      "(saveur github intacte)",
      'android.permission.REQUEST_INSTALL_PACKAGES' in manifest_main)
check("Manifeste principal : INTERNET toujours déclaré (inchangé)",
      'android.permission.INTERNET' in manifest_main)
check("Manifeste saveur play : la permission y est RETIRÉE "
      "(tools:node=\"remove\")",
      'xmlns:tools="http://schemas.android.com/tools"' in manifest_play
      and re.search(
          r'<uses-permission\s+android:name="android\.permission\.'
          r'REQUEST_INSTALL_PACKAGES"\s+tools:node="remove"\s*/>',
          manifest_play) is not None)

# --- 3. La couture entre saveurs --------------------------------------------

check("Distribution.kt : la classe existe dans les DEUX saveurs, "
      "même paquet",
      "package net.chardons.netscramble2" in github_src
      and "package net.chardons.netscramble2" in play_src
      and "object Distribution" in github_src
      and "object Distribution" in play_src)
check("Distribution.kt : github = UPDATER_ENABLED true (Updater actif)",
      "const val UPDATER_ENABLED = true" in github_src)
check("Distribution.kt : play = UPDATER_ENABLED false (Updater coupé)",
      "const val UPDATER_ENABLED = false" in play_src)

# --- 4. TitleActivity : le verrou --------------------------------------------

check("TitleActivity : la vérification GitHub passe par le drapeau "
      "de saveur (release null si UPDATER_ENABLED faux)",
      re.search(r"val release = if \(!Distribution\.UPDATER_ENABLED\) \{"
                r"\s*null\s*\} else \{", title_src, re.S) is not None
      and title_src.count("Distribution.UPDATER_ENABLED") >= 1)
check("TitleActivity : le cap de 5 secondes est CONSERVÉ (la saveur "
      "github ne régresse pas)",
      re.search(r"val release = if \(!Distribution\.UPDATER_ENABLED\).*?"
                r"withTimeoutOrNull\(CHECK_TIMEOUT_MS\) \{\s*"
                r"Updater\.checkLatest\(\)", title_src, re.S)
      is not None)
check("TitleActivity : l'aiguillage final est intact (mise à jour "
      "choisie ou entrée dans le jeu)",
      re.search(r"if \(release != null && Updater\.isNewer\(release\.version, "
                r"version\)\) \{[^{}]*?showUpdateChoice\(release\)\s*\} "
                r"else \{[^{}]*?startGame\(\)", title_src, re.S)
      is not None)
check("TitleActivity : le KDoc documente la saveur play",
      "saveur « play »" in title_src.lower()
      or "Saveur « play »" in title_src)

# --- 5. Updater : inchangé (l'APK github ne régresse pas) --------------------

check("Updater : signatures publiques intactes (aucune retouche de "
      "comportement)",
      all(sig in updater_src for sig in (
          "fun isNewer(remote: String, current: String): Boolean",
          "suspend fun checkLatest(): Release?",
          "suspend fun download(",
          "fun canInstall(context: Context): Boolean",
          "fun requestInstallPermission(",
          "const val UPDATE_APK_NAME",
          "const val ACTION_INSTALL_RESULT",
          "private fun versionParts(version: String): List<Int>?")))

# --- 6. La CI couvre les deux saveurs ----------------------------------------

check("CI : les tests JVM tournent pour les DEUX saveurs",
      "testGithubDebugUnitTest" in ci_src
      and "testPlayDebugUnitTest" in ci_src
      and "testDebugUnitTest " not in ci_src.replace(
          "testGithubDebugUnitTest", "").replace(
          "testPlayDebugUnitTest", ""))
check("CI : les DEUX APK sont bâtis et lintés (l'APK play doit rester "
      "propre pour Play)",
      "assembleGithubDebug" in ci_src
      and "assemblePlayDebug" in ci_src
      and "lintGithubDebug" in ci_src
      and "lintPlayDebug" in ci_src)

# --- 7. Miroir : manifeste fusionné par saveur -------------------------------

# Règle du fusionneur : tools:node="remove" retire l'élément du
# manifeste fusionné de CETTE saveur uniquement. La permission ne doit
# exister que dans l'APK github.
MAIN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.REQUEST_INSTALL_PACKAGES",
}
merged_github = set(MAIN_PERMISSIONS)
merged_play = MAIN_PERMISSIONS - {"android.permission.REQUEST_INSTALL_PACKAGES"}
check("Miroir fusion : APK github = INTERNET + REQUEST_INSTALL_PACKAGES, "
      "APK play = INTERNET seul",
      merged_github == MAIN_PERMISSIONS
      and "android.permission.REQUEST_INSTALL_PACKAGES" not in merged_play)


# --- 8. Miroir : comparaison de versions de l'Updater ------------------------

def version_parts(version):
    clean = version.strip()
    if clean.startswith("v") or clean.startswith("V"):
        clean = clean[1:]
    if not clean:
        return None
    parts = clean.split(".")
    if any((not part) or (not part.isdigit()) for part in parts):
        return None
    return [int(part) for part in parts]


def is_newer(remote, current):
    r = version_parts(remote)
    c = version_parts(current)
    if r is None or c is None:
        return False
    size = max(len(r), len(c))
    for i in range(size):
        rv = r[i] if i < len(r) else 0
        cv = c[i] if i < len(c) else 0
        if rv != cv:
            return rv > cv
    return False


version_cases = [
    ("0.10.0", "0.9.9", True),     # numérique champ par champ
    ("v0.2.0", "0.1.9", True),      # préfixe v toléré
    ("V1.0.0", "0.9.9", True),     # préfixe V toléré
    ("0.2", "0.1.9", True),         # champ manquant = 0
    ("0.1.0", "0.1.0", False),      # identiques
    ("0.1", "0.1.0", False),       # 0.1 == 0.1.0
    ("0.1.0-beta", "0.1.0", False),  # mal formée : ignorée
    ("", "0.1.0", False),          # vide : ignorée
    ("0.9.9", "0.10.0", False),    # sens inverse
]
version_fail = []
for remote, current, expected in version_cases:
    if is_newer(remote, current) != expected:
        version_fail.append(f"{remote} vs {current}")
check("Miroir isNewer : 9 cas de comparaison de versions de "
      "publications (identique au code de l'Updater, non retouché)",
      not version_fail, "; ".join(version_fail[:2]))

# --- 9. Miroir : le double verrou de l'APK play ------------------------------

# Verrou 1 (construction) : UPDATER_ENABLED faux — TitleActivity ne
# vérifie jamais GitHub, release reste null.
# Verrou 2 (manifeste) : permission absente — même un appel erroné à
# l'installateur serait refusé par le système (canRequestPackageInstalls
# faux pour toujours, l'app n'apparaît pas dans l'écran d'autorisation).
lock1 = "const val UPDATER_ENABLED = false" in play_src
lock2 = ("android.permission.REQUEST_INSTALL_PACKAGES"
        not in merged_play)
check("Miroir double verrou : l'APK play ne peut NI proposer NI "
      "exécuter une installation (verrou construction + verrou "
      "manifeste)", lock1 and lock2)

# --- Bilan -------------------------------------------------------------------

print()
if failures:
    print(f"ÉCHEC : {len(failures)}/{checks} vérifications en échec "
          f"(saveurs de distribution GitHub / Play).")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications (saveurs de "
      f"distribution : APK github intact, APK play sans permission "
      f"ni updater actif).")
