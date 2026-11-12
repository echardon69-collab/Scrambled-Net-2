#!/usr/bin/env python3
"""Validation du chantier « page titre » (TitleActivity + mises à jour).

Spéc arrêtée avec eric le 12 nov. 2026 :

- page titre PLEIN ÉCRAN au lancement : icône, nom, version, licence,
  remerciements ; le dialogue « À propos » reste distinct ;
- barre de progression couvrant le chargement de la partie
  sauvegardée ET la vérification de mise à jour ;
- vérification GitHub à CHAQUE lancement, CAPÉE à 5 secondes : le
  jeu n'est jamais retenu au-delà, une réponse tardive n'est PAS
  exploitée ;
- mise à jour trouvée à temps : le joueur choisit (« Mettre à
  jour » / « Jouer ») ;
- « Mettre à jour » : téléchargement de l'APK puis installation SANS
  confirmation quand Android l'accepte (USER_ACTION_NOT_REQUIRED,
  Android 12+), écran système sinon ; permission
  REQUEST_INSTALL_PACKAGES + écran d'autorisation la 1re fois.

Ce script PARSE les sources (aucun littéral retapé à la main) et
SIMULE la logique de versions et le déroulé du lancement.
"""

import re
import sys

KT = "app/src/main/kotlin/net/chardons/netscramble2"
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


title_src = read(f"{KT}/TitleActivity.kt")
updater_src = read(f"{KT}/Updater.kt")
persist_src = read(f"{KT}/Persistence.kt")
main_src = read(f"{KT}/MainActivity.kt")
manifest = read("app/src/main/AndroidManifest.xml")
layout = read("app/src/main/res/layout/activity_title.xml")
en_src = read("app/src/main/res/values/strings.xml")
fr_src = read("app/src/main/res/values-fr/strings.xml")

# ---------------------------------------------------------------------------
# 1. Manifeste : permissions et point d'entrée.
# ---------------------------------------------------------------------------

check("Manifest : permission INTERNET",
      '<uses-permission android:name="android.permission.INTERNET" />'
      in manifest)
check("Manifest : permission REQUEST_INSTALL_PACKAGES",
      '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
      in manifest)
check("Manifest : TitleActivity est le point d'entrée (MAIN/LAUNCHER, exported)",
      'android:name=".TitleActivity"' in manifest
      and re.search(
          r'\.TitleActivity".*?exported="true">.*?<action android:name="android.intent.action.MAIN" />\s*'
          r'<category android:name="android.intent.category.LAUNCHER" />',
          manifest, re.S) is not None)
check("Manifest : MainActivity n'est plus lanceur (exported=false, sans filtre)",
      re.search(r'\.MainActivity"\s+android:exported="false"\s*/>', manifest)
      is not None
      and "LAUNCHER" not in manifest.split(".MainActivity")[1].split(".ScoresActivity")[0])
check("Manifest : le commentaire ne dit plus « Aucune permission »",
      "Aucune permission" not in manifest)

# ---------------------------------------------------------------------------
# 2. TitleActivity : le déroulé du lancement.
# ---------------------------------------------------------------------------

check("Title : affichage minimum et cap des 5 secondes déclarés",
      "MIN_DISPLAY_MS = 1200.milliseconds" in title_src
      and "CHECK_TIMEOUT_MS = 5000.milliseconds" in title_src)
check("Title : la vérification est capée par withTimeoutOrNull",
      "withTimeoutOrNull(CHECK_TIMEOUT_MS)" in title_src
      and "Updater.checkLatest()" in title_src)
check("Title : l'affichage minimum est attendu (delay + join)",
      "launch { delay(MIN_DISPLAY_MS) }" in title_src
      and "branding.join()" in title_src)
check("Title : la partie sauvegardée est chargée puis attendue",
      "async(Dispatchers.Default) { loadSavedGame() }" in title_src
      and "gameLoad.await()" in title_src)
check("Title : le chargement décode GameSpec et reconstruit le plateau",
      "GameSpec.decode(saved)?.buildBoard()" in title_src
      and "Persistence.GAME_SPEC_KEY" in title_src)
check("Title : même fichier de sauvegarde que MainActivity",
      "getSharedPreferences(Persistence.FILE, MODE_PRIVATE)" in title_src)

flow = re.search(r"private fun launchFlow\(.*?\n    \}", title_src, re.S)
check("Title : le déroulé est extractible", flow is not None)
if flow:
    body = flow.group(0)
    check("Title : mise à jour à temps = choix, PAS d'entrée automatique",
          "showUpdateChoice(release)" in body
          and "Updater.isNewer(release.version, version)" in body)
    check("Title : sans mise à jour = entrée automatique dans le jeu",
          "startGame()" in body
          and body.count("startGame()") == 1)

choice = re.search(r"private fun showUpdateChoice\(.*?\n    \}", title_src, re.S)
check("Title : le choix montre les DEUX boutons",
      choice is not None
      and "binding.updateButton.isVisible = true" in choice.group(0)
      and "binding.playButton.isVisible = true" in choice.group(0))

start = re.search(r"private fun startGame\(\).*?\n    \}", title_src, re.S)
check("Title : « Jouer » entre dans le jeu et ferme le titre (pas de Retour)",
      start is not None
      and "MainActivity::class.java" in start.group(0)
      and "finish()" in start.group(0))

check("Title : récepteur d'installation NON EXPORTÉ, désenregistré à la destruction",
      "ContextCompat.RECEIVER_NOT_EXPORTED" in title_src
      and "unregisterReceiver(installReceiver)" in title_src)
check("Title : repli — la confirmation système est lancée quand Android l'exige",
      "PackageInstaller.STATUS_PENDING_USER_ACTION" in title_src
      and "IntentCompat.getParcelableExtra" in title_src
      and "startActivity(it)" in title_src)
check("Title : UnsafeIntentLaunch désarmé uniquement parce que le récepteur est NON EXPORTÉ",
      "@SuppressLint(\"UnsafeIntentLaunch\")" in title_src
      and "ContextCompat.RECEIVER_NOT_EXPORTED" in title_src)
check("Title : le succès n'ouvre PAS le jeu (le système relance l'app)",
      "PackageInstaller.STATUS_SUCCESS" in title_src)
check("Title : l'échec réactive les boutons et laisse jouer",
      "title_install_failed" in title_src
      and "binding.playButton.isEnabled = true" in title_src)
check("Title : la progression du téléchargement repasse par le fil UI",
      "runOnUiThread" in title_src)
check("Title : permission d'installation demandée avant tout téléchargement",
      "Updater.canInstall(this)" in title_src
      and "Updater.requestInstallPermission(this)" in title_src)
check("Title : version lue dans le PackageManager (jamais codée en dur)",
      "Updater.currentVersion(this)" in title_src
      and "0.1.0" not in title_src)

# ---------------------------------------------------------------------------
# 3. Updater : GitHub, installation, versions.
# ---------------------------------------------------------------------------

check("Updater : la cible est la dernière publication du dépôt d'eric",
      "https://api.github.com/repos/echardon69-collab/Scrambled-Net-2/releases/latest"
      in updater_src)
check("Updater : User-Agent envoyé (exigé par l'API GitHub)",
      updater_src.count('setRequestProperty("User-Agent", USER_AGENT)') >= 2)
check("Updater : l'asset APK de la publication est ciblé",
      '.endsWith(".apk")' in updater_src
      and 'optString("browser_download_url"' in updater_src
      and 'optString("tag_name"' in updater_src)
check("Updater : installation sans confirmation (USER_ACTION_NOT_REQUIRED, Android 12+)",
      "USER_ACTION_NOT_REQUIRED" in updater_src
      and "Build.VERSION_CODES.S" in updater_src
      and "Build.VERSION.SDK_INT >= Build.VERSION_CODES.S" in updater_src)
check("Updater : canInstall consulte le droit par application (Android 8+)",
      "Build.VERSION_CODES.O" in updater_src
      and "canRequestPackageInstalls()" in updater_src)
check("Updater : écran système d'autorisation d'installation pointé",
      "Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES" in updater_src
      and '"package:${context.packageName}".toUri()' in updater_src
      and "import androidx.core.net.toUri" in updater_src)
check("Updater : PendingIntent mutable seulement là où le système l'exige",
      "PendingIntent.FLAG_MUTABLE" in updater_src
      and "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)" in updater_src)
check("Updater : session fsync + commit + fermeture garantie",
      "session.fsync(out)" in updater_src
      and "session.commit(pending.intentSender)" in updater_src
      and "finally {" in updater_src
      and "session.close()" in updater_src)
check("Updater : progression déterminée, -1 si taille inconnue",
      "contentLengthLong" in updater_src
      and "onProgress(-1)" in updater_src)
check("Updater : la version installée vient du PackageManager",
      "PackageManager.PackageInfoFlags.of(0)" in updater_src
      and "Build.VERSION_CODES.TIRAMISU" in updater_src
      and "@Suppress(\"DEPRECATION\")" in updater_src)
check("Updater : l'APK téléchargé vit dans le cache privé de l'app",
      "File(cacheDir, Updater.UPDATE_APK_NAME)" in title_src)
check("Updater : tolérance au préfixe « v » des étiquettes git",
      'removePrefix("v")' in updater_src
      and 'removePrefix("V")' in updater_src)

# ---------------------------------------------------------------------------
# 4. Persistence : compatibilité avec les sauvegardes existantes.
# ---------------------------------------------------------------------------

check("Persistence : fichier et clé historiques (sauvegardes préservées)",
      'const val FILE = "MainActivity"' in persist_src
      and 'const val GAME_SPEC_KEY = "gameSpec"' in persist_src)
check("Persistence : clé définie UNE seule fois dans tout le code",
      sum('GAME_SPEC_KEY' in read(f"{KT}/{f}")
          for f in ("Persistence.kt", "MainActivity.kt",
                    "TitleActivity.kt")) >= 2
      and 'private const val GAME_SPEC_KEY' not in main_src)
check("MainActivity : la partie vit toujours au même endroit",
      main_src.count("Persistence.GAME_SPEC_KEY") == 2
      and "GAME_SPEC_KEY" not in
      main_src.replace("Persistence.GAME_SPEC_KEY", ""))

# ---------------------------------------------------------------------------
# 5. Miroir de versions : isNewer extraite de la source.
# ---------------------------------------------------------------------------


def python_port(remote, current):
    """Port fidèle de Updater.isNewer — sa structure est vérifiée
    ci-dessous, marqueur par marqueur, dans le source Kotlin."""
    def parts(v):
        clean = v.strip()
        if clean.startswith("v") or clean.startswith("V"):
            clean = clean[1:]
        if not clean:
            return None
        p = clean.split(".")
        if any(not q.isdigit() or q == "" for q in p):
            return None
        return [int(q) for q in p]

    r = parts(remote)
    c = parts(current)
    if r is None or c is None:
        return False
    for i in range(max(len(r), len(c))):
        rv = r[i] if i < len(r) else 0
        cv = c[i] if i < len(c) else 0
        if rv != cv:
            return rv > cv
    return False


newer_src = (
    re.search(r"fun isNewer\(.*?\n    \}", updater_src, re.S).group(0)
    + "\n"
    + re.search(r"fun versionParts\(.*?\n    \}", updater_src, re.S).group(0)
)
check("Miroir : structure de isNewer conforme au port (préfixe, split, padding)",
      'removePrefix("v")' in newer_src
      and 'clean.split(".")' in newer_src
      and "getOrNull(i) ?: 0" in newer_src
      and "!it.isDigit()" in newer_src)

version_cases = [
    ("0.2.0", "0.1.0", True),
    ("0.1.0", "0.1.0", False),
    ("v0.2.0", "0.1.0", True),
    ("V0.2.0", "0.1.0", True),
    ("0.9.9", "0.10.0", False),      # numérique, pas lexicographique
    ("1.0", "0.9.9", True),          # champs manquants = 0
    ("0.1.0-beta", "0.1.0", False),  # mal formée = ignorée
    ("", "0.1.0", False),
    ("0.1.0", "x", False),
    ("0.1.0.1", "0.1.0", True),
]
for remote, current, expected in version_cases:
    check(f"Miroir : isNewer({remote!r}, {current!r}) = {expected}",
          python_port(remote, current) == expected)

# ---------------------------------------------------------------------------
# 6. Miroir du déroulé : les constantes extraites de TitleActivity.
# ---------------------------------------------------------------------------

min_ms = int(re.search(r"MIN_DISPLAY_MS = (\d+)", title_src).group(1))
cap_ms = int(re.search(r"CHECK_TIMEOUT_MS = (\d+)", title_src).group(1))
check("Miroir : constantes du déroulé (1,2 s minimum, 5 s de cap)",
      min_ms == 1200 and cap_ms == 5000)


def timeline(check_ms, newer, load_ms=50):
    """Déroulé en miroir : instant d'entrée et exploitation de la réponse.
    Une réponse qui n'arrive pas AVANT le cap est abandonnée
    (withTimeoutOrNull annule la vérification) — décision d'eric."""
    answered = min(check_ms, cap_ms)
    used = check_ms < cap_ms and newer
    enter = max(min_ms, load_ms, answered)
    return enter, used and newer


scenarios = [
    ("réseau rapide, pas de mise à jour", 300, False, 1200, False),
    ("réponse à temps, mise à jour dispo", 4000, True, 4000, True),
    ("réseau bloqué : réponse trop tardive ignorée", 9000, True, 5000, False),
    ("réponse à temps mais version égale", 1500, False, 1500, False),
]
for label, check_ms, newer, expect_enter, expect_used in scenarios:
    enter, used = timeline(check_ms, newer)
    check(f"Miroir : {label} -> entrée à {expect_enter} ms, "
          f"mise à jour {'exploitée' if expect_used else 'ignorée'}",
          enter == expect_enter and used == expect_used)

# ---------------------------------------------------------------------------
# 7. Layout et chaînes.
# ---------------------------------------------------------------------------

check("Layout : barre de progression linéaire Material",
      "LinearProgressIndicator" in layout
      and '@+id/title_progress' in layout)
check("Layout : boutons cachés par défaut (entrée automatique)",
      layout.count('android:visibility="gone"') == 2
      and '@+id/update_button' in layout
      and '@+id/play_button' in layout)
check("Layout : icône grand format + licence + statut initial",
      "@drawable/ic_launcher_foreground" in layout
      and "@string/title_license" in layout
      and "@string/title_loading" in layout)
check("Layout : les vues référencées par le code existent (binding)",
      all(f'@+id/{snake}' in layout for snake in
          ("title_version", "title_status", "title_progress",
           "update_button", "play_button")))

NEW_STRINGS = ["title_version", "title_license", "title_loading",
               "title_checking", "title_update_available",
               "title_update_button", "title_play_button",
               "title_download_status", "title_install_status",
               "title_install_permission", "title_install_failed"]


def strings_of(src):
    return dict(re.findall(r'<string name="([^"]+)">(.*?)</string>',
                           src, re.S))


en, fr = strings_of(en_src), strings_of(fr_src)
check("Chaînes : les 11 nouvelles existent en EN",
      all(s in en for s in NEW_STRINGS))
check("Chaînes : les 11 nouvelles existent en FR",
      all(s in fr for s in NEW_STRINGS))
check("Chaînes : les versions FR sont bien traduites",
      all(fr.get(s, "").strip() != en.get(s, "").strip()
          for s in NEW_STRINGS
          # identiques de fait dans les deux langues :
          if s not in ("title_version", "title_install_status")))
check("Chaînes : apostrophes FR échappées",
      all(re.search(r"(?<!\\)'", fr.get(s, "")) is None for s in NEW_STRINGS))
check("Chaînes : mêmes paramètres %1$s en EN et FR",
      all(re.findall(r"%\d+\$[ds]", en.get(s, ""))
          == re.findall(r"%\d+\$[ds]", fr.get(s, ""))
          for s in NEW_STRINGS))

# ---------------------------------------------------------------------------
# 8. Structure minimale (la CI d'eric compile pour de vrai).
# ---------------------------------------------------------------------------


def balanced(src, open_c, close_c):
    depth = 0
    for line in src.splitlines():
        code = re.sub(r"//.*", "", line)
        if '"""' in line:
            code = code.split('"""', 1)[0]
        depth += code.count(open_c) - code.count(close_c)
        if depth < 0:
            return False
    return depth == 0


for name, src in (("TitleActivity", title_src), ("Updater", updater_src),
                  ("Persistence", persist_src), ("MainActivity", main_src)):
    check(f"Structure : {name} — accolades et parenthèses équilibrées",
          balanced(src, "{", "}") and balanced(src, "(", ")"))
check("Structure : pas de « /* » dans un KDoc",
      all(re.search(r'"""\s*/\*', s) is None
          for s in (title_src, updater_src, persist_src, main_src)))
check("Structure : XML bien fermés",
      layout.count("<LinearLayout") == layout.count("</LinearLayout>")
      and layout.rstrip().endswith("</LinearLayout>")
      and manifest.rstrip().endswith("</manifest>")
      and en_src.count("<resources>") == 1
      and fr_src.count("</resources>") == 1)

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications")
    sys.exit(1)
print(f"TOUT PASSE : {checks} vérifications")
