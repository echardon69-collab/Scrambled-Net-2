#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « sélecteur de langue dynamique » (v19 du
16 nov. 2026, feu vert d'eric le 16 nov. 2026).

Défaut d'origine : depuis la v3 de la page Réglages (14 nov. 2026),
les radios de la section Langue étaient FIGÉES dans le layout —
Système, Français, English. Une traduction neuve (corse v17,
alsacien v18) n'était PAS sélectionnable depuis l'app, seule le
permettait le réglage système « langue par application ».

Correctif validé ici :
  - seules les données vivent en source : AppLanguages (tag BCP-47
    complet + endonyme, une ligne par langue) ;
  - les radios naissent AU RUNTIME, gonflées depuis
    item_lang_radio.xml dans le groupe de la section, ids posés au
    runtime et portés par un registre tag -> id ;
  - la correspondance radio <-> locale se fait sur le tag COMPLET
    (gsw-FR, jamais « gsw » seul) ;
  - les endonymes quittent les fichiers de traduction (strings
    lang_fr/lang_en retirées partout) : identiques en toute langue,
    ils vivent dans AppLanguages et restent relus par les
    relecteurs des langues.

Trois sources doivent rester EN PHASE — le pin les exige ensemble :
  1. locales_config.xml (réglage système, déjà pinné par
     validate_reglages) ;
  2. AppLanguages.kt (le sélecteur) ;
  3. les dossiers values-xx (les traductions).

Le tout est PARSÉ (jamais de valeurs retapées à la main ici), et
doublé côté comportement par les tests JVM (AppLanguagesTest,
package racine) : le validateur sur les sources, les tests sur le
comportement.

Extension « drapeaux du sélecteur » (Lot 15, v20 du 16 nov. 2026) :
chaque radio porte le drapeau choisi par eric (svg/flags/flag_*.svg
-> drawable via tools/flags/convert_flags.py, hauteur uniforme
15 dp, largeur proportionnelle). Sont pinnés : les 5 sources et
leurs 5 drawables (viewport et tailles dp exacts), flag_en RÉÉCRIT
sans clipPath (le VectorDrawable ne sait pas recadrer — voir
l'en-tête du SVG), la correspondance EXPLICITE langFlagFor dans
SettingsActivity, l'équilibre des comptes (autant de drapeaux que
de langues), et la source unique des règles (convert_flags
réutilise convert_tiles, jamais de règles retapées). La géométrie
fine de l'Union Jack est elle, épinglee par le banc d'essai
tools/flags/test_convert_flags.py.

Extension « traduction en mandarin » (18 nov. 2026, expérimentale) :
première passe IA de chinois simplifié (zh-CN), intégrée COMPLÈTE
— les trois sources ensemble, jamais l'une sans les autres : le
dossier values-zh-rCN, la locale zh-CN dans locales_config, la
ligne AppLanguage et le drapeau de la Chine (dessiné sur la grille
officielle 30 × 20, polygones à cinq branches). Relecture native
attendue avant toute officialisation, même processus que le corse
et l'alsacien.

Extension « ombrage des drapeaux pâles » (nov. 2026, retour
d'eric : les drapeaux blancs ne se démarquent pas sur fond blanc) :
le convertisseur POSE en dernier, par-dessus le contenu, deux
bandeaux de dégradé linéaire (transparent vers l'intérieur,
#40000000 au ras du bord), sous les bordures droite et basse, de
1,5 dp d'épaisseur TOUJOURS (quelle que soit la taille du drapeau).
Pinné ici : présence et ordre (les DEUX derniers tracés, PARSÉS),
épaisseur en dp des deux axes, et l'alpha en miroir du
convertisseur (OMBRE_ALPHA). Révisé le 18 nov. 2026 sur second
retour d'eric : 1,5 dp et alpha 0x40 (~25 %), le 1 dp à ~15 %
ne se détachait pas assez.

Usage : python3 tools/validate_selecteur.py
"""

import re
import sys
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
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


sa_src = read(KOTLIN / "SettingsActivity.kt")
al_src = read(KOTLIN / "AppLanguages.kt")
layout_src = read(RES / "layout/activity_settings.xml")
item_path = RES / "layout/item_lang_radio.xml"
item_src = read(item_path)
locales_src = read(RES / "xml/locales_config.xml")
test_src = read(TESTS / "AppLanguagesTest.kt")
FLAGS_SVG = ROOT / "svg/flags"
FLAGS_TOOL = ROOT / "tools/flags"

# --- 1. AppLanguages : les données, épinglées une à une ---------------------

check("AppLanguages : data class AppLanguage(tag, endonym)",
      re.search(r"data class AppLanguage\(\s*val tag: String,"
                r"\s*val endonym: String\s*\)", al_src) is not None)
check("AppLanguages : objet AppLanguages avec ALL en liste",
      re.search(r"object AppLanguages \{\s*"
                r"/\*\* [^*]*\*/\s*"
                r"val ALL: List<AppLanguage> = listOf\(", al_src,
                re.S) is not None)
pairs = re.findall(r'AppLanguage\("([^"]+)",\s*"([^"]+)"\),?', al_src)
check("AppLanguages : les cinq langues, tags et endonymes "
      "épinglés (fr, en, co, gsw-FR, zh-CN — continuité de la page v3 "
      "puis langues ajoutées)",
      pairs == [("fr", "Français"), ("en", "English"),
                ("co", "Corsu"), ("gsw-FR", "Elsässisch"),
                ("zh-CN", "简体中文")],
      str(pairs))
check("AppLanguages : byTag en correspondance EXACTE (premier tag "
      "égal, pas de préfixe ni de langue seule)",
      re.search(r"fun byTag\(tag: String\): AppLanguage\? =\s*"
                r"ALL\.firstOrNull \{ it\.tag == tag \}", al_src)
      is not None)
check("AppLanguages : PUR Kotlin, testable JVM (aucun import "
      "android/androidx)",
      "import android" not in al_src
      and "import androidx" not in al_src)

# --- 2. locales_config : même set de langues, compte exact ------------------

locale_tags = re.findall(r'<locale android:name="([^"]+)" />',
                        locales_src)
app_tags = [tag for tag, _ in pairs]
check("Cohérence : locales_config et AppLanguages portent les "
      "MÊMES langues (set, compte exact 5)",
      len(locale_tags) == 5
      and set(locale_tags) == set(app_tags),
      f"locales_config={locale_tags} AppLanguages={app_tags}")

# --- 3. Le layout : une seule radio statique, l'item de gonflage ------------

lang_block = re.search(
    r'<RadioGroup[^>]*@\+id/lang_group(.*?)</RadioGroup>', layout_src,
    re.S)
lang_static = (re.findall(r'@\+id/(lang_\w+)', lang_block.group(1))
               if lang_block else [])
check("Layout : section langue — seule la radio système est "
      "statique, les langues naissent au runtime",
      lang_static == ["lang_system"], str(lang_static))
check("Layout : plus AUCUNE radio de langue figée "
      "(lang_fr/lang_en retirées)",
      "@+id/lang_fr" not in layout_src
      and "@+id/lang_en" not in layout_src)
check("Layout : l'item de gonflage existe, radio Material bornée "
      "à 320 dp (VisualLintButtonSize), SANS id ni texte figés",
      item_path.exists()
      and "MaterialRadioButton" in item_src
      and 'android:maxWidth="320dp"' in item_src
      and 'android:layout_width="match_parent"' in item_src
      and 'android:layout_height="wrap_content"' in item_src
      and re.search(r'android:id="', item_src) is None
      and re.search(r'android:text="', item_src) is None)

# --- 4. SettingsActivity : le mécanisme dynamique ---------------------------

check("SettingsActivity : les radios sont construites depuis "
      "AppLanguages.ALL, gonflées dans le groupe de la section",
      re.search(r"AppLanguages\.ALL\.forEach \{ lang ->\s*"
                r"val radio = layoutInflater\.inflate\(\s*"
                r"R\.layout\.item_lang_radio, binding\.langGroup, "
                r"false\s*\)", sa_src, re.S) is not None)
check("SettingsActivity : id posé au runtime, registre tag -> id "
      "et libellé = endonyme",
      "View.generateViewId()" in sa_src
      and "radio.id = id" in sa_src
      and "radio.text = lang.endonym" in sa_src
      and "langRadioIds[lang.tag] = id" in sa_src)
check("SettingsActivity : gonflage attachToRoot = FALSE + addView "
      "(avec TRUE, inflate RENVOIE LE PARENT : le cast en "
      "MaterialRadioButton plantait en ClassCastException — "
      "correction du 17 nov. 2026)",
      "R.layout.item_lang_radio, binding.langGroup, false" in sa_src
      and "binding.langGroup.addView(radio)" in sa_src
      and "langGroup, true" not in sa_src)

check("SettingsActivity : les radios sont construites AVANT la "
      "coche initiale (patron v3)",
      sa_src.find("buildLangRadios()")
      < sa_src.find("binding.langGroup.check("
                    "langRadioFor(currentLanguageTag()))")
      and "buildLangRadios()" in sa_src)
check("SettingsActivity : la coche consulte le REGISTRE, repli sur "
      "« Suivre le système »",
      re.search(r"private fun langRadioFor\(tag: String\): Int =\s*"
                r"langRadioIds\[tag\] \?: R\.id\.lang_system", sa_src)
      is not None)
check("SettingsActivity : l'application inverse le registre et "
      "force la locale par le tag (vide = suivre le système)",
      re.search(r"val tag = langRadioIds\.entries\s*"
                r"\.firstOrNull \{ it\.value == checkedId \}\?\.key",
                sa_src) is not None
      and "LocaleListCompat.forLanguageTags(tag)" in sa_src
      and "LocaleListCompat.getEmptyLocaleList()" in sa_src)
check("SettingsActivity : le tag COMPLET fait foi "
      "(toLanguageTag, gsw-FR matche, « gsw » seul jamais)",
      "locales[0]?.toLanguageTag()" in sa_src
      and 'forLanguageTags("gsw")' not in sa_src)
check("SettingsActivity : plus AUCUNE référence aux radios "
      "figées d'antan",
      "R.id.lang_fr" not in sa_src and "R.id.lang_en" not in sa_src
      and 'forLanguageTags("fr")' not in sa_src
      and 'forLanguageTags("en")' not in sa_src)
check("SettingsActivity : la coche initiale vient toujours de "
      "l'état RÉEL d'appcompat (aucune clé maison)",
      "AppCompatDelegate.getApplicationLocales()" in sa_src
      and "AppCompatDelegate.setApplicationLocales(locales)" in sa_src)

# --- 5. Traductions : endonymes partis, libellés traduits restants ----------

lang_files = {
    "values": RES / "values/strings.xml",
    "values-fr": RES / "values-fr/strings.xml",
    "values-co": RES / "values-co/strings.xml",
    "values-gsw-rFR": RES / "values-gsw-rFR/strings.xml",
    "values-zh-rCN": RES / "values-zh-rCN/strings.xml",
}
for dossier, chemin in sorted(lang_files.items()):
    src = read(chemin)
    check(f"Strings {dossier} : endonymes absents "
          "(lang_fr/lang_en retirées)",
          'name="lang_fr"' not in src and 'name="lang_en"' not in src)
    check(f"Strings {dossier} : menu_lang et lang_system toujours là "
          "(seuls libellés traduits de la section)",
          'name="menu_lang"' in src and 'name="lang_system"' in src)

# --- 6. Tests JVM : le comportement doublé ---------------------------------

check("Tests JVM : AppLanguagesTest existe et épingle le piège "
      "gsw-FR (jamais « gsw » seul)",
      (TESTS / "AppLanguagesTest.kt").exists()
      and '"gsw-FR"' in test_src
      and 'assertNull(AppLanguages.byTag("gsw"))' in test_src)
check("Tests JVM : les cinq langues et endonymes épinglés, "
      "correspondance exacte et endonymes sains",
      "listsAllFiveLanguagesInDisplayOrder" in test_src
      and "byTagIsExactMatchOnly" in test_src
      and "endonymsAreNotBlankAndDistinct" in test_src)

# --- 7. Drapeaux du sélecteur (Lot 15) --------------------------------------

flag_tags = (("fr", "flag_fr", "22.5", "900", "600"),
             ("en", "flag_en", "30", "1200", "600"),
             ("co", "flag_co", "25", "750", "450"),
             ("gsw-FR", "flag_gsw_fr", "22.5", "900", "600"),
             ("zh-CN", "flag_zh", "22.5", "900", "600"))
for tag, drawable, w, vw, vh in flag_tags:
    svg = FLAGS_SVG / f"{drawable}.svg"
    check(f"Drapeau {tag} : source svg/flags/{drawable}.svg "
          f"présente",
          svg.exists())
    vd = read(RES / f"drawable/{drawable}.xml")
    check(f"Drapeau {tag} : drawable exact ({w} dp x 15 dp, "
          f"viewport {vw} x {vh})",
          f'android:width="{w}dp"' in vd
          and 'android:height="15dp"' in vd
          and f'android:viewportWidth="{vw}"' in vd
          and f'android:viewportHeight="{vh}"' in vd)
    check(f"Drapeau {tag} : posé par langFlagFor dans "
          f"SettingsActivity (correspondance explicite)",
          re.search(r'"%s" -> R\.drawable\.%s'
                    % (re.escape(tag), drawable),
                    sa_src) is not None)

_ANDROID = "{http://schemas.android.com/apk/res/android}"
_AAPT = "{http://schemas.android.com/aapt}"


def _gradient_de(trace):
    """Le gradient linéaire du tracé (PARSÉ), sinon None."""
    for enfant in trace:
        if enfant.tag != _AAPT + "attr":
            continue
        for grad in enfant:
            if (grad.tag == "gradient"
                    and grad.get(_ANDROID + "type") == "linear"):
                return grad
    return None


for tag, drawable, w, vw, vh in flag_tags:
    arbre = ElementTree.parse(RES / f"drawable/{drawable}.xml")
    traces = [e for e in arbre.getroot() if e.tag == "path"]
    ombres = [t for t in traces if _gradient_de(t)]
    check(f"Drapeau {tag} : ombrage des drapeaux pâles — les DEUX "
          f"derniers tracés sont des bandeaux à dégradé (posés "
          f"en dernier, par-dessus le contenu, une seule fois)",
          len(ombres) == 2 and traces[-2:] == ombres)
    epaisseurs = []
    for bandau in ombres:
        gradient = _gradient_de(bandau)
        stops = [(item.get(_ANDROID + "offset"),
                  item.get(_ANDROID + "color"))
                 for item in gradient]
        nums = [float(v)
                for v in re.findall(r"[-+]?[\d.]+",
                                    bandau.get(_ANDROID + "pathData"))]
        if nums[0] == 0.0:      # bandeau du BAS : M0,y ... pleine largeur
            ep = (nums[5] - nums[1]) * 15.0 / float(vh)
        else:                   # bandeau de la DROITE : Mx,0 ... Mx,vh
            # axe HORIZONTAL : une dp vaut vw/w unités (largeur dp du
            # drapeau), PAS vh/15 comme sur l'axe vertical.
            ep = (nums[2] - nums[0]) * float(w) / float(vw)
        epaisseurs.append((ep, stops))
    check(f"Drapeau {tag} : bandeaux de 1,5 dp sur les DEUX axes, "
          f"gradient linéaire transparent -> #40000000 au bord",
          all(abs(ep - 1.5) < 1e-4 and stops == [("0", "#00000000"),
                                                 ("1", "#40000000")]
              for ep, stops in epaisseurs))

outil_drapeaux = (ROOT / "tools/flags/convert_flags.py").read_text(
    encoding="utf-8")
check("Drapeaux : miroir des constantes du convertisseur — "
      "bandau de 1,5 dp et alpha 0x40 (retouchables là, épinglés ici)",
      re.search(r"^OMBRE_LARGEUR_DP = 1\.5", outil_drapeaux, re.M)
      is not None
      and re.search(r"^OMBRE_ALPHA = 0x40", outil_drapeaux, re.M)
      is not None)

check("Drapeaux : l'Union Jack est RÉÉCRIT sans clipPath (non "
      "représentable en VectorDrawable), réécriture documentée "
      "dans l'en-tête du SVG et 4 contrebandes stroke 40 dans le "
      "drawable",
      (FLAGS_SVG / "flag_en.svg").exists()
      and "clipPath" in read(FLAGS_SVG / "flag_en.svg")
      and "clip-path" not in read(FLAGS_SVG / "flag_en.svg").split(
          "-->")[1]
      and read(RES / "drawable/flag_en.xml").count(
          'android:strokeWidth="40"') == 4)
check("Drapeaux : les radios les portent (drawable relatif + "
      "marge 8 dp), PAS de getIdentifier",
      "setCompoundDrawablesRelativeWithIntrinsicBounds(" in sa_src
      and "compoundDrawablePadding" in sa_src
      and re.search(r"\.getIdentifier\(", sa_src) is None)
flags_svg = sorted(f.name for f in FLAGS_SVG.glob("flag_*.svg"))
check("Drapeaux : autant de sources que de langues (compte exact 5 "
      "des deux côtés)",
      len(flags_svg) == 5
      and len([t for t in locale_tags if t in
               dict((tag, drawable) for tag, drawable, *_ in flag_tags)])
      == 5)
check("Drapeaux : la source unique des règles — convert_flags "
      "réutilise convert_tiles ET generate_graphics, jamais de "
      "règles retapées",
      (FLAGS_TOOL / "convert_flags.py").exists()
      and (FLAGS_TOOL / "test_convert_flags.py").exists()
      and (FLAGS_TOOL / "convert-flags.sh").exists()
      and (FLAGS_TOOL / "convert-flags.bat").exists()
      and "import convert_tiles as ct" in read(
          FLAGS_TOOL / "convert_flags.py")
      and "import generate_graphics as gg" in read(
          FLAGS_TOOL / "convert_flags.py")
      and "clip-path" in read(FLAGS_TOOL / "convert_flags.py"))

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications :")
    for echec in failures:
        print(f"  - {echec}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications "
      "(sélecteur de langue dynamique : radios nées au runtime "
      "depuis AppLanguages, en phase avec locales_config et les "
      "traductions, drapeaux générés des SVG et posés sur les "
      "radios).")
