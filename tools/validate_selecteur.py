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

Extension « traduction en allemand » (18-19 nov. 2026,
expérimentale) : première passe IA d'allemand (de), intégrée
COMPLÈTE comme le mandarin — les trois sources ensemble, jamais
l'une sans les autres : le dossier values-de, la locale de dans
locales_config, la ligne AppLanguage et le drapeau de l'Allemagne
(tricolore officiel 3:5, trois bandes horizontales égales aux
teintes officielles #000000/#DD0000/#FFCE00, sans emblème : le
drapeau n'en porte pas). Relecture native attendue avant toute
officialisation.

Extension « traduction en russe » (18-19 nov. 2026,
expérimentale) : première passe IA de russe (ru), intégrée
COMPLÈTE comme le mandarin — les trois sources ensemble, jamais
l'une sans les autres : le dossier values-ru, la locale ru dans
locales_config, la ligne AppLanguage et le drapeau de la Russie
(tricolore officiel 2:3, trois bandes horizontales égales aux
teintes officielles #FFFFFF/#0039A6/#D52B1E, sans emblème : le
tricolore n'en porte pas). Relecture native attendue avant toute
officialisation.

Extension « traduction en hébreu » (18-19 nov. 2026,
expérimentale) : première passe IA d'hébreu (he), PREMIÈRE LANGUE
RTL du jeu — intégrée COMPLÈTE comme le mandarin : le dossier
values-he, la locale he dans locales_config, la ligne AppLanguage
et le drapeau d'Israël (géométrie de la LOI de 1949 reportée dans
un viewBox 550 X 400 : bandes de 25 cm à 15 cm du bord, maguen
David de la loi — six bandes de 5,5 cm — schématisé en deux
triangles pleins, illisibles autrement à 15 dp). Le manifeste
porte supportsRtl=true : Android reflète la mise en page seul.
Relecture native attendue avant toute officialisation.

Extension « traduction en italien » (18 nov. 2026,
expérimentale) : première passe IA d'italien (it), intégrée
COMPLÈTE comme le mandarin — le dossier values-it, la locale it
dans locales_config, la ligne AppLanguage et le drapeau
d'Italie (tricolore officiel 2:3, trois bandes verticales
égales aux teintes officielles, sans emblème : il n'en a pas).
Relecture native attendue avant toute officialisation.

Extension « traduction en portugais » (18 nov. 2026,
expérimentale) : première passe IA de portugais (pt, registre
brésilien neutre documenté dans l'en-tête de values-pt), intégrée
COMPLÈTE comme le mandarin — le dossier values-pt, la locale pt
dans locales_config, la ligne AppLanguage et le drapeau du
Portugal (bandes officielles 2:3, sphère armillaire schématisée
en anneau jaune posé sur la frontière, SANS écu : illisible à
15 dp). Relecture native attendue avant toute officialisation.

Extension « traduction en espagnol » (18 nov. 2026, expérimentale) :
première passe IA d'espagnol (es), intégrée COMPLÈTE comme le
mandarin — les trois sources ensemble, jamais l'une sans les
autres : le dossier values-es, la locale es dans locales_config,
la ligne AppLanguage et le drapeau d'Espagne (bandes officielles
1:2:1, SANS emblème : illisible à 15 dp, c'est le drapeau civil).
Relecture native attendue avant toute officialisation.

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

Extension « feuille de langue » (v21 du 18 nov. 2026, demande
d'eric : l'app agrandit sa famille de langues, une radio par
langue obligeait à trop scroller la page) : la section Langue ne
garde qu'UNE entrée résumé dans la page (lang_current, libellé et
drapeau de la langue en vigueur), qui ouvre une FEUILLE Material 3
(BottomSheetDialog + sheet_langs.xml, plafonnée à 360 dp — au-delà
c'est la feuille qui déroule, jamais la page). Le REGISTRE d'ids
de la v19 disparaît : chaque item porte son tag par lambda,
« Suivre le système » reste en TÊTE. NOUVEAU champ drapeauPays dans
AppLanguage : pour une langue SECONDAIRE d'un pays (corse,
alsacien, futur breton, créoles…), le DRAPEAU NATIONAL du pays
s'affiche entre parenthèses après l'endonyme (ImageSpan dans le
libellé, hauteur uniforme 15 dp, pas d'ALIGN_CENTER : ce mode de
DynamicDrawableSpan n'existe qu'à partir de l'API 29, minSdk 24).

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

check("AppLanguages : data class AppLanguage(tag, endonym, "
      "drapeauPays — 3e champ, défaut null)",
      re.search(r"data class AppLanguage\(\s*val tag: String,"
                r"\s*val endonym: String,\s*"
                r"val drapeauPays: String\? = null\s*\)",
                al_src) is not None)
check("AppLanguages : objet AppLanguages avec ALL en liste",
      re.search(r"object AppLanguages \{\s*"
                r"/\*\* [^*]*\*/\s*"
                r"val ALL: List<AppLanguage> = listOf\(", al_src,
                re.S) is not None)
# V21 : 3e argument NOMMÉ optionnel, drapeauPays = "fr" pour les
# langues secondaires d'un pays.
pairs = [(t, e, d or None) for t, e, d in re.findall(
    r'AppLanguage\("([^"]+)",\s*"([^"]+)"'
    r'(?:,\s*drapeauPays\s*=\s*"([^"]*)")?\),?', al_src)]
check("AppLanguages : les onze langues, tags, endonymes et "
      "drapeaux pays épinglés (fr, en, co, gsw-FR, zh-CN, es, pt, "
      "it, he, ru, de — continuité de la page v3 puis langues ajoutées)",
      pairs == [("fr", "Français", None), ("en", "English", None),
                ("co", "Corsu", "fr"), ("gsw-FR", "Elsässisch", "fr"),
                ("zh-CN", "简体中文", None),
                ("es", "Español", None),
                ("pt", "Português", None),
                ("it", "Italiano", None),
                ("he", "עברית", None),
                ("ru", "Русский", None),
                ("de", "Deutsch", None)],
      str(pairs))
check("AppLanguages : drapeau NATIONAL réservé aux langues "
      "SECONDAIRES d'un pays (v21, demande d'eric : corse et "
      "alsacien portent « fr », les langues pleine page n'en "
      "portent PAS)",
      [d for _, _, d in pairs if d] == ["fr", "fr"]
      and [t for t, _, d in pairs if d] == ["co", "gsw-FR"])
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
app_tags = [tag for tag, _, _ in pairs]
check("Cohérence : locales_config et AppLanguages portent les "
      "MÊMES langues (set, compte exact 11)",
      len(locale_tags) == 11
      and set(locale_tags) == set(app_tags),
      f"locales_config={locale_tags} AppLanguages={app_tags}")

# --- 3. Le layout : une entrée résumé, la feuille, l'item de gonflage -------

# V21 : la section ne garde PLUS AUCUN groupe de radios — une seule
# entrée résumé (lang_current) ouvre la feuille Material 3 ; le
# choix déroule DANS la feuille (maxHeight 360 dp), jamais la page.
lang_block = re.search(
    r'<TextView[^>]*@\+id/lang_current(.*?)/>', layout_src, re.S)
check("Layout : section langue — UNE entrée résumé (v21) : "
      "borne 320 dp, cible tactile 48 dp, ripple et focus au "
      "toucher, PAS un groupe de radios",
      lang_block is not None
      and 'android:maxWidth="320dp"' in lang_block.group(1)
      and 'android:minHeight="48dp"' in lang_block.group(1)
      and "?attr/selectableItemBackground" in lang_block.group(1)
      and 'android:clickable="true"' in lang_block.group(1)
      and 'android:focusable="true"' in lang_block.group(1),
      lang_block.group(0)[:160] if lang_block else "absent")
check("Layout : plus AUCUNE radio de langue figée "
      "(lang_fr/lang_en retirées, lang_group et la radio système "
      "parties avec la feuille)",
      "@+id/lang_fr" not in layout_src
      and "@+id/lang_en" not in layout_src
      and "@+id/lang_group" not in layout_src
      and "@+id/lang_system" not in layout_src)
sheet_path = RES / "layout/sheet_langs.xml"
sheet_src = read(sheet_path) if sheet_path.exists() else ""
check("Layout : la feuille sheet_langs.xml (v21) — titre menu_lang, "
      "liste des items nés au runtime, ScrollView PLAFOND 360 dp "
      "(au-delà la feuille déroule EN INTERNE, jamais la page)",
      sheet_path.exists()
      and re.search(r"@\+id/lang_sheet_title", sheet_src) is not None
      and re.search(r"@\+id/lang_sheet_list", sheet_src) is not None
      and 'android:maxHeight="360dp"' in sheet_src
      and 'android:text="@string/menu_lang"' in sheet_src)
check("Layout : l'item de gonflage existe, radio Material bornée "
      "à 320 dp (VisualLintButtonSize), SANS id ni texte figés",
      item_path.exists()
      and "MaterialRadioButton" in item_src
      and 'android:maxWidth="320dp"' in item_src
      and 'android:layout_width="match_parent"' in item_src
      and 'android:layout_height="wrap_content"' in item_src
      and re.search(r'android:id="', item_src) is None
      and re.search(r'android:text="', item_src) is None)

# --- 4. SettingsActivity : la feuille Material 3 (v21) ----------------------

check("SettingsActivity : le REGISTRE d'ids de la v19 a DISPARU "
      "avec la feuille (plus d'état parallèle — chaque item porte "
      "son tag par lambda, la coche compare au tag complet)",
      "langRadioIds" not in sa_src
      and "View.generateViewId()" not in sa_src
      and "langRadioFor" not in sa_src
      and "binding.langGroup" not in sa_src)
check("SettingsActivity : l'entrée résumé ouvre la feuille, reliée "
      "à l'état RÉEL d'appcompat (aucune clé maison)",
      "binding.langCurrent.setOnClickListener { showLangSheet() }"
      in sa_src
      and "bindLangEntry(currentLanguageTag())" in sa_src
      and "AppCompatDelegate.getApplicationLocales()" in sa_src
      and "AppCompatDelegate.setApplicationLocales(locales)" in sa_src)
check("SettingsActivity : la feuille est un BottomSheetDialog qui "
      "gonfle sheet_langs (racine NON NULLE + attachToRoot FALSE, "
      "lint : la racine résout les LayoutParams) et remplit sa "
      "liste (lang_sheet_list)",
      "BottomSheetDialog(this)" in sa_src
      and re.search(r"inflate\(\s*R\.layout\.sheet_langs,\s*"
                    r"binding\.root,\s*false\s*\)", sa_src) is not None
      and re.search(r"inflate\(\s*R\.layout\.sheet_langs,\s*null",
                    sa_src) is None
      and "findViewById<LinearLayout>(R.id.lang_sheet_list)" in sa_src)
check("SettingsActivity : « Suivre le système » reste la PREMIÈRE "
      "entrée de la feuille, puis les langues dans l'ordre "
      "d'affichage d'AppLanguages.ALL",
      sa_src.find("liste, getString(R.string.lang_system)")
      < sa_src.find("AppLanguages.ALL.forEach { lang ->")
      and sa_src.count("langSheetItem(") == 3)
check("SettingsActivity : les items naissent AU RUNTIME gonflés "
      "depuis item_lang_radio.xml — attachToRoot FALSE, racine = la "
      "liste (avec TRUE, inflate RENVOIE LE PARENT : le cast en "
      "MaterialRadioButton plantait — piège du 17 nov. 2026 "
      "conservé)",
      "R.layout.item_lang_radio, racine, false" in sa_src
      and re.search(r"racine: LinearLayout, libelle: CharSequence",
                    sa_src) is not None
      and "liste.addView(" in sa_src)
check("SettingsActivity : chaque item porte libellé, drapeau "
      "(drawable relatif + marge 8 dp), coche si la langue est "
      "celle en vigueur, et le toucher applique puis referme",
      "item.text = libelle" in sa_src
      and "item.isChecked = coche" in sa_src
      and "setCompoundDrawablesRelativeWithIntrinsicBounds(" in sa_src
      and "compoundDrawablePadding" in sa_src
      and "applyLanguage(\"\")" in sa_src
      and "applyLanguage(lang.tag)" in sa_src
      and "sheet.dismiss()" in sa_src)
check("SettingsActivity : le suffixe de DRAPEAU NATIONAL entre "
      "parenthèses (v21, demande d'eric) — ImageSpan dans le "
      "libellé, hauteur uniforme 15 dp, PAS d'ALIGN_CENTER "
      "(DynamicDrawableSpan ALIGN_CENTER = API 29, minSdk 24)",
      "endonymAvecPays(lang)" in sa_src
      and "ImageSpan(drapeau)" in sa_src
      and "(15 * dens).toInt()" in sa_src
      and "ImageSpan.ALIGN_CENTER" not in sa_src)
check("SettingsActivity : applyLanguage prend le TAG complet "
      "(vide = suivre le système), l'inversion de registre est "
      "partie",
      re.search(r"private fun applyLanguage\(tag: String\)", sa_src)
      is not None
      and "LocaleListCompat.forLanguageTags(tag)" in sa_src
      and "LocaleListCompat.getEmptyLocaleList()" in sa_src
      and "langRadioIds.entries" not in sa_src)
check("SettingsActivity : le tag COMPLET fait foi "
      "(toLanguageTag, gsw-FR matche, « gsw » seul jamais)",
      "locales[0]?.toLanguageTag()" in sa_src
      and 'forLanguageTags("gsw")' not in sa_src)
check("SettingsActivity : plus AUCUNE référence aux radios "
      "figées d'antan",
      "R.id.lang_fr" not in sa_src and "R.id.lang_en" not in sa_src
      and 'forLanguageTags("fr")' not in sa_src
      and 'forLanguageTags("en")' not in sa_src)

# --- 5. Traductions : endonymes partis, libellés traduits restants ----------

lang_files = {
    "values": RES / "values/strings.xml",
    "values-fr": RES / "values-fr/strings.xml",
    "values-co": RES / "values-co/strings.xml",
    "values-gsw-rFR": RES / "values-gsw-rFR/strings.xml",
    "values-zh-rCN": RES / "values-zh-rCN/strings.xml",
    "values-es": RES / "values-es/strings.xml",
    "values-pt": RES / "values-pt/strings.xml",
    "values-it": RES / "values-it/strings.xml",
    "values-he": RES / "values-he/strings.xml",
    "values-ru": RES / "values-ru/strings.xml",
    "values-de": RES / "values-de/strings.xml",
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
check("Tests JVM : les onze langues et endonymes épinglés, "
      "correspondance exacte et endonymes sains",
      "listsAllElevenLanguagesInDisplayOrder" in test_src
      and "byTagIsExactMatchOnly" in test_src
      and "endonymsAreNotBlankAndDistinct" in test_src)
check("Tests JVM : drapeau NATIONAL pour les seules langues "
      "secondaires (v21 : co et gsw-FR portent « fr », fr, en, "
      "zh-CN, es, pt, it, he, ru et de n'en portent PAS)",
      "countryFlagsOnlyOnSecondaryLanguages" in test_src
      and "?.drapeauPays" in test_src)

# --- 7. Drapeaux du sélecteur (Lot 15) --------------------------------------

flag_tags = (("fr", "flag_fr", "22.5", "900", "600"),
             ("en", "flag_en", "30", "1200", "600"),
             ("co", "flag_co", "25", "750", "450"),
             ("gsw-FR", "flag_gsw_fr", "22.5", "900", "600"),
             ("zh-CN", "flag_zh", "22.5", "900", "600"),
             ("es", "flag_es", "22.5", "750", "500"),
             ("pt", "flag_pt", "22.5", "600", "400"),
             ("it", "flag_it", "22.5", "600", "400"),
             ("he", "flag_he", "20.625", "550", "400"),
             ("ru", "flag_ru", "22.5", "600", "400"),
             ("de", "flag_de", "25", "750", "450"))
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
check("Drapeaux : les items de la feuille et l'entrée résumé "
      "les portent (drawable relatif + marge 8 dp), PAS de "
      "getIdentifier",
      "setCompoundDrawablesRelativeWithIntrinsicBounds(" in sa_src
      and "compoundDrawablePadding" in sa_src
      and re.search(r"\.getIdentifier\(", sa_src) is None)
flags_svg = sorted(f.name for f in FLAGS_SVG.glob("flag_*.svg"))
check("Drapeaux : autant de sources que de langues (compte exact 11 "
      "des deux côtés)",
      len(flags_svg) == 11
      and len([t for t in locale_tags if t in
               dict((tag, drawable) for tag, drawable, *_ in flag_tags)])
      == 11)
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
      "(feuille de langue v21 : items nés au runtime depuis "
      "AppLanguages dans la feuille Material 3, en phase avec "
      "locales_config et les traductions, drapeaux générés des "
      "SVG, suffixe de drapeau national pour les langues "
      "secondaires d'un pays).")
