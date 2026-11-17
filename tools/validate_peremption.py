#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « date limite d'utilisation » (Lot 17, v22
du 17 nov. 2026, spécification arrêtée avec eric le 17 nov. 2026).

Décisions d'eric, épinglées ici une à une :
  1. grâce de 5 jours après la date de build (pas 90) ;
  2. écran A : blocage pur + bouton Quitter, SANS proposition de
     mise à jour ;
  3. règle du numéro : versionName de majeure 0 (« 0.x.y ») =
     pré-release limitée ; « 1.x.y » et au-delà = release
     officielle, illimitée ;
  4. message avec phrase d'accompagnement (titre + détail).

Architecture validée :
  - Gradle n'estampille QUE la date de build UTC
    (BuildConfig.DATE_BUILD) — BuildConfig, coupé par défaut
    depuis AGP 8, est réactivé ;
  - TOUTE la logique vit dans Peremption.kt, pur Kotlin testable
    JVM (java.util.Calendar/TimeZone, jamais java.time : interdit
    sans desugaring à minSdk 24) ;
  - TitleActivity contrôle AVANT le déroulé (chargement de la
    partie, vérification de mise à jour), affiche l'écran de
    blocage et s'arrête là ;
  - le témoin de date vit dans un fichier DÉDIÉ (jamais
    Persistence.FILE : la sauvegarde de partie n'est pas mélangée
    à la péremption) et est persisté AVANT le test, même quand
    l'application est déjà périmée : reculer l'horloge du
    téléphone ne la réouvre pas.

Le tout est PARSÉ (jamais de valeurs retapées à la main ici) et
doublé côté comportement par les tests JVM (PeremptionTest) : le
validateur sur les sources, les tests sur le comportement —
jamais l'un sans l'autre.

Usage : python3 tools/validate_peremption.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KT = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
TESTS = ROOT / "app/src/test/kotlin/net/chardons/netscramble2"
RES = ROOT / "app/src/main/res"

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
    return path.read_text(encoding="utf-8")


gradle_src = read(ROOT / "app/build.gradle.kts")
per_src = read(KT / "Peremption.kt")
title_src = read(KT / "TitleActivity.kt")
layout_src = read(RES / "layout/activity_title.xml")
test_src = read(TESTS / "PeremptionTest.kt")
readme_src = read(ROOT / "README.md")
changes_src = read(ROOT / "CHANGES-MODIFS-DIVERSES.md")
STRINGS = {suf: read(RES / f"values{suf}/strings.xml")
           for suf in ("", "-fr", "-co", "-gsw-rFR")}

# --- 1. Gradle : la date de build, estampillée et rien d'autre ------------

check("Gradle : BuildConfig réactivé (AGP 8+ le coupe par défaut)",
      re.search(r"buildFeatures \{\s*//.*\n(?:.*\n)*?\s*buildConfig = true",
                gradle_src) is not None,
      "")
check("Gradle : DATE_BUILD = LocalDate.now(ZoneOffset.UTC), ISO "
      "yyyy-MM-dd — imports en tête de script, JAMAIS le nom "
      "pleinement qualifié dans le bloc android (le DSL y "
      "résout `java` en JavaPluginExtension, le script ne "
      "compile plus et BuildConfig n'est jamais généré)",
      '"DATE_BUILD"' in gradle_src
      and "import java.time.LocalDate" in gradle_src
      and "import java.time.ZoneOffset" in gradle_src
      and "LocalDate.now(ZoneOffset.UTC)" in gradle_src
      and "java.time.LocalDate.now" not in gradle_src)
check("Gradle : viewBinding toujours là (rien de cassé au passage)",
      "viewBinding = true" in gradle_src)

# --- 2. Peremption.kt : pur Kotlin, les 4 décisions ----------------------

check("Peremption : PUR Kotlin, testable JVM "
      "(aucun import android/androidx)",
      "import android" not in per_src
      and "import androidx" not in per_src)
check("Peremption : PAS d'import java.time (interdit sans "
      "desugaring, minSdk 24) — Calendar/TimeZone seulement "
      "(la doc peut citer java.time, le code ne l'importe jamais)",
      "import java.time" not in per_src
      and "import java.util.Calendar" in per_src
      and "import java.util.TimeZone" in per_src)
check("Décision 1 : grâce de 5 jours, constante unique",
      'const val GRACE_JOURS = 5' in per_src)
check("Décision 1 : jour de grâce INCLUS — périmée le lendemain "
      "seulement (comparaison stricte)",
      "jour > dateLimite" in per_src)
check("Décision 3 : majeure 0 = pré-release",
      'startsWith("0.")' in per_src)
check("Décision 3 : dateLimiteDe null pour une release officielle",
      re.search(r"fun dateLimiteDe\(.*?\): String\? =\s*"
                r"if \(estPreRelease\(versionName\)\) "
                r"dateLimite\(dateBuild\) else null", per_src, re.S)
      is not None)
check("Témoin : jour observé = max LEXICAL du jour UTC et du témoin",
      "maxOf(aujourdHui, temoin" in per_src)
check("Témoin : fichier DÉDIÉ « peremption », clé « temoin » "
      "(le code ne touche jamais à la sauvegarde de partie)",
      'const val FICHIER = "peremption"' in per_src
      and 'const val CLE_TEMOIN = "temoin"' in per_src
      and "getSharedPreferences" not in per_src)
check("Release : limite null ou vide = jamais périmée",
      "dateLimite.isNullOrEmpty()" in per_src)
check("Robustesse : date non ISO refusée par exception "
      "(jamais corrigée en silence)",
      "IllegalArgumentException" in per_src
      and "isLenient = false" in per_src)
check("Peremption : String.format en Locale.ROOT, jamais la "
      "locale du téléphone (les %02d en dépendent)",
      "import java.util.Locale" in per_src
      and per_src.count("String.format(Locale.ROOT,") == 2
      and 'String.format("%04d' not in per_src)

# --- 3. TitleActivity : écran A, avant tout le reste ---------------------

check("Title : contrôle de péremption AVANT le déroulé "
      "(launchFlow) et AVANT tout lancement",
      title_src.index("if (afficherEcranPerime()) return")
      < title_src.index("launchFlow(version)"))
check("Title : version et date de build lues dans BuildConfig",
      "BuildConfig.VERSION_NAME" in title_src
      and "BuildConfig.DATE_BUILD" in title_src)
check("Title : le témoin est persisté AVANT le test, même déjà "
      "périmé (KTX androidx.core.content.edit { })",
      "import androidx.core.content.edit" in title_src
      and "prefs.edit {" in title_src
      and title_src.index("putString(Peremption.CLE_TEMOIN")
      < title_src.index("Peremption.estPerimee"))
check("Décision 2 : écran A — AUCUN bouton de mise à jour dans le "
      "bloc périmé",
      re.search(r"private fun afficherEcranPerime\(\): Boolean \{.*?\n    \}",
                title_src, re.S).group(0).count("updateButton") == 0)
check("Décision 2 : blocage pur — « Quitter » ferme TOUT "
      "(finishAffinity)",
      "finishAffinity()" in title_src)
check("Title : écran de blocage — progression et statut cachés",
      "binding.titleProgress.isVisible = false" in title_src
      and "binding.titleStatus.isVisible = false" in title_src)
check("Title : les 3 vues du blocage sont montrées",
      "binding.titleObsolete.isVisible = true" in title_src
      and "binding.titleObsoleteDetail.isVisible = true" in title_src
      and "binding.titleObsoleteQuit.isVisible = true" in title_src)

# --- 4. Layout : 3 vues cachées par défaut -------------------------------

check("Layout : les 3 vues du blocage présentes, cachées par "
      "défaut (gone ×5 au total, boutons compris)",
      all(f"@+id/{v}" in layout_src for v in
          ("title_obsolete", "title_obsolete_detail",
           "title_obsolete_quit"))
      and layout_src.count('android:visibility="gone"') == 5)
check("Décision 4 : le layout référence titre + phrase "
      "d'accompagnement + bouton Quitter",
      all(f"@string/{s}" in layout_src for s in
          ("version_obsolete", "version_obsolete_detail",
           "version_obsolete_quitter")))

# --- 5. Chaînes : les 3 clés dans les 4 langues --------------------------

for suf, label in (("", "base EN"), ("-fr", "fr"),
                   ("-co", "co"), ("-gsw-rFR", "gsw-rFR")):
    keys = set(re.findall(r'<string name="([^"]+)"', STRINGS[suf]))
    check(f"Chaînes {label} : les 3 clés de péremption présentes",
          {"version_obsolete", "version_obsolete_detail",
           "version_obsolete_quitter"} <= keys)
base_texts = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>',
                             STRINGS[""], re.S))
check("Chaînes : aucune des 3 nouvelles ne porte de paramètre % "
      "(validate_langues exige la parité EN/miroirs)",
      all("%" not in base_texts.get(k, "%") for k in
          ("version_obsolete", "version_obsolete_detail",
           "version_obsolete_quitter")))

# --- 6. PeremptionTest : les épingles comportementales -------------------

check("Tests : les 4 épingles de rollover (mois, année, "
      "bissextile, non-bissextile)",
      all(d in test_src for d in ("2026-12-03", "2027-01-04",
                                  "2028-03-02", "2027-03-03")))
check("Tests : jour de grâce INCLUS épinglé dans estPerimee "
      "(limite jouable, lendemain périmé)",
      'estPerimee("2026-11-22", "2026-11-22", null)' in test_src
      and 'estPerimee("2026-11-22", "2026-11-23", null)' in test_src)
check("Tests : témoin anti-rollback épinglé (témoin postérieur "
      "suivi, témoin moindre ignoré)",
      '"2026-11-20", "2026-11-25"' in test_src
      and '"2026-11-20", "2026-11-18"' in test_src)
check("Tests : release sans limite jamais périmée",
      "estPerimee(null" in test_src)
check("Tests : date non ISO refusée par exception",
      "IllegalArgumentException" in test_src)
check("Tests : la propriété lexicale s'épingle sur des dates "
      "CALCULÉES par dateLimite, pas des constantes booléennes",
      'val novembre = Peremption.dateLimite("2026-11-02")'
      in test_src
      and "assertTrue(novembre < quinzaine)" in test_src
      and 'assertTrue("2026-11-09" < "2026-11-10")' not in test_src)

# --- 7. Documentation ----------------------------------------------------

check("README : l'entrée roadmap « Date limite » est LIVRÉE avec "
      "les 4 décisions",
      "Date limite d'utilisation (livré le 17 nov. 2026" in readme_src
      and "grâce de 5 jours" in readme_src
      and "blocage pur" in readme_src)
check("CHANGES : section Lot 17 présente, décisions résumées",
      "## Lot 17 — date limite d'utilisation" in changes_src
      and "5 jours" in changes_src.split("## Lot 17")[1])

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications "
      "(date limite d'utilisation : 5 jours de grâce, écran A de "
      "blocage pur, règle du numéro, témoin anti-rollback, date "
      "estampillée par Gradle).")
