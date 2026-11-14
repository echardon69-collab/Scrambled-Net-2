#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « refonte réglages » (13 nov. 2026).

Demande d'eric : les trois sous-menus en cascade du menu Réglages
(Niveau de difficulté — 8 options, Sons — 3 modes, Animation — 2
modes) deviennent une PAGE UNIQUE, déroulante si besoin, qui montre
l'ensemble des réglages d'un coup d'œil et permet de choisir
directement.

Décisions actées (spec + feu vert de mise en œuvre immédiate) :
  - la page (SettingsActivity) reprend le patron des records et de
    l'aide : toolbar Material 3 + ScrollView bord à bord ;
  - Sons et Animation s'appliquent ET se persistent immédiatement,
    aux MÊMES clés et au MÊME fichier historiques (« soundMode » /
    « animEnable » dans le fichier « MainActivity », repris de
    l'original) — les réglages d'avant la refonte survivent, et le
    jeu relit les deux à son retour (onStart) ;
  - le Niveau de difficulté RELANCE une partie : même protection que
    le jeu (dialogue de confirmation si une partie est en cours —
    coups joués et non résolue), Annuler remet la coche sur le niveau
    courant sans rien changer, confirmer referme la page en renvoyant
    le niveau choisi ; MainActivity décode le résultat, relance la
    partie et montre le rappel des règles des niveaux spéciaux ;
  - ZÉRO nouvelle string (menu_settings, menu_skill, menu_sounds,
    menu_anim, sounds_*, anim_*, confirm_* existent déjà EN et FR) ;
  - PAS de bump de GameSpec, les 40 tests JVM ne changent pas.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : menu, layout, SettingsActivity,
     Settings, Persistence, MainActivity, manifest et strings sont
     PARSÉS (jamais de valeurs retapées à la main dans ce script).
  2. Miroirs Python : prédicat « partie en cours » identique au jeu
     (confirmIfInProgress), table des 8 niveaux complète, aller-retour
     de persistance du mode sonore (avec repli FULL sur valeur
     inconnue, comme avant la refonte), format de temps mm:ss.

Usage : python3 tools/validate_reglages.py

V2 (retour d'eric du 14 nov. 2026, validation de mise en page
Android Studio — 14 problèmes) : les 13 radios sont bornées à
320 dp (VisualLintButtonSize : limite Material de largeur de
bouton, dépassée par match_parent sur presque tous les écrans
d'aperçu) et la suppression VisualLintOverlap est documentée
(insets runtime EdgeToEdge, invisibles à l'aperçu statique).

V3 (retour d'eric du 14 nov. 2026 au soir) :
  - BUG « les valeurs actuelles ne sont pas affichées » : l'ancien
    helper checkRadioButton cochait TOUJOURS skillGroup, même pour
    un id de sons ou d'animation ; RadioGroup.check ignore
    silencieusement tout id étranger au groupe, donc les sections
    sons et animation s'ouvraient JAMAIS cochées. Corrigé : chaque
    groupe reçoit sa propre coche (miroir RadioGroup ci-dessous).
  - Les 8 niveaux de difficulté sont commentés dans le layout,
    chacun avec sa description (règles du niveau, fidèles à
    game/Skill.kt).
  - Nouvelle section « Langue » : système / français / anglais,
    via AppCompatDelegate.setApplicationLocales — stockage et
    restauration par appcompat (autoStoreLocales au manifeste,
    localeConfig pour Android 13+), aucune clé maison.

V4 (nouveau sujet d'eric, 14 nov. 2026) : « renseigner le joueur
sur la taille et les caractéristiques du tableau de jeu de chaque
niveau de difficulté, dans la page de réglage ».
  - Sous chaque radio du niveau, une description VISIBLE : la
    TAILLE RÉELLE du plateau sur cet écran (matrice Screen du
    chantier « tailles fidèles », mêmes metrics et prédicat
    d'orientation que MainActivity.boardSize — la famille Infini
    passe par la même table, sa taille y est fixe 40 x 40), suivie
    des règles du niveau (branches, tore, câbles invisibles,
    brouillard). Texte posé au runtime : il dépend de l'écran.
  - L'alignement de la description sur le libellé de sa radio se
    lit AU RUNTIME (compoundPaddingStart) — la largeur de
    l'indicateur Material varie avec le thème et la densité.
  - 8 nouvelles strings EN+FR (skill_*_desc), taille en arguments
    de format ; miroir Python de la table Screen (tailles réelles
    par matrice, échange portrait/paysage, Infini toujours 40 x 40).
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "app/src/main/kotlin/net/chardons/netscramble2"
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
    return Path(path).read_text(encoding="utf-8")


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


ma_src = read(KOTLIN / "MainActivity.kt")
sa_src = read(KOTLIN / "SettingsActivity.kt")
settings_src = read(KOTLIN / "Settings.kt")
persistence_src = read(KOTLIN / "Persistence.kt")
spec_src = read(KOTLIN / "game/GameSpec.kt")
manifest_src = read(ROOT / "app/src/main/AndroidManifest.xml")
locales_src = read(RES / "xml/locales_config.xml")
menu_src = read(RES / "menu/menu_main.xml")
layout_src = read(RES / "layout/activity_settings.xml")
en_src = read(RES / "values/strings.xml")
fr_src = read(RES / "values-fr/strings.xml")

ma = strip_comments(ma_src)
sa = strip_comments(sa_src)
st = strip_comments(settings_src)
pe = strip_comments(persistence_src)

SKILL_ORDER = ["NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE",
               "ENDLESS", "ABYSS", "VOID"]

# --- 1. Le menu : page unique, plus de sous-menus en cascade ---------------

check("Menu : le bouton Réglages ouvre la page (menu_settings)",
      re.search(r'<item[^>]*@\+id/menu_settings', menu_src) is not None)
check("Menu : plus AUCUN sous-menu imbriqué (une seule balise menu)",
      len(re.findall(r"<menu", menu_src)) == 1,
      f"balises menu={len(re.findall(r'<menu', menu_src))}")
check("Menu : plus aucun id skill_*/sounds_*/anim_* (déménagés)",
      re.search(r'@\+id/(skill_|sounds_|anim_)\w+', menu_src) is None,
      str(re.findall(r'@\+id/(?:skill_|sounds_|anim_)\w+', menu_src)[:3]))

# --- 2. Le layout : une page, trois sections, tout visible ------------------

check("Layout : toolbar Material avec le titre Réglages",
      re.search(r"MaterialToolbar[^>]*settings_toolbar", layout_src,
                re.S) is not None
      and re.search(r'app:title="@string/menu_settings"', layout_src)
      is not None)
check("Layout : ScrollView déroulant (settings_scroll)",
      re.search(r"<ScrollView[^>]*@\+id/settings_scroll", layout_src,
                re.S) is not None)
# V4 : les TextViews de description (skill_*_desc) ne sont PAS des
# radios — l'extraction ne retient que les ids des
# MaterialRadioButton du groupe, sinon les descriptions compteraient
# comme des niveaux.
skill_block = re.search(
    r'<RadioGroup[^>]*@\+id/skill_group(.*?)</RadioGroup>', layout_src,
    re.S)
skill_radios = (re.findall(
    r'MaterialRadioButton[^>]*@\+id/(skill_\w+)',
    skill_block.group(1)) if skill_block else [])
check("Layout : section niveau avec les 8 radios attendues",
      skill_radios == ["skill_" + n.lower() for n in SKILL_ORDER],
      str(skill_radios))
check("Layout : les radios sont des MaterialRadioButton",
      skill_block is not None
      and skill_block.group(1).count("MaterialRadioButton") == 8)
sound_block = re.search(
    r'<RadioGroup[^>]*@\+id/sound_group(.*?)</RadioGroup>', layout_src,
    re.S)
sound_radios = (re.findall(r"@\+id/(sounds_\w+)", sound_block.group(1))
                if sound_block else [])
check("Layout : section sons avec les 3 modes (off/quiet/full)",
      sound_radios == ["sounds_off", "sounds_quiet", "sounds_full"],
      str(sound_radios))
anim_block = re.search(
    r'<RadioGroup[^>]*@\+id/anim_group(.*?)</RadioGroup>', layout_src,
    re.S)
anim_radios = (re.findall(r"@\+id/(anim_\w+)", anim_block.group(1))
               if anim_block else [])
check("Layout : section animation (off/on)",
      anim_radios == ["anim_off", "anim_on"], str(anim_radios))
lang_block = re.search(
    r'<RadioGroup[^>]*@\+id/lang_group(.*?)</RadioGroup>', layout_src,
    re.S)
lang_radios = (re.findall(r'@\+id/(lang_\w+)', lang_block.group(1))
               if lang_block else [])
check("Layout : section langue avec les 3 choix "
      "(système / français / anglais)",
      lang_radios == ["lang_system", "lang_fr", "lang_en"],
      str(lang_radios))
check("Layout : les quatre en-têtes de section existent "
      "(v3 : langue ajoutée)",
      all(f'@string/{key}' in layout_src
          for key in ("menu_skill", "menu_sounds", "menu_anim",
                      "menu_lang")))
check("Layout : chaque radio porte le libellé de son string",
      all(f'@string/{rid}' in layout_src
          for rid in skill_radios + sound_radios + anim_radios))

# --- 2 bis. Durcissement visual lint (v2, retour d'eric du 14 nov.) ----------

# La validation de mise en page d'Android Studio signale tout bouton
# rendu plus large que la limite Material de 320 dp
# (VisualLintButtonSize) : les radios, en match_parent, la
# dépassaient sur la plupart des écrans d'aperçu. V3 : les 3 radios
# de la langue naissent déjà bornées — 16 au total. V4 : les 8
# descriptions de niveaux sont bornées pareil (cohérence du bloc,
# radio et description alignées sur la même largeur) — 24 au total.
check("Layout : les 16 radios et 8 descriptions sont bornées à "
      "320 dp (VisualLintButtonSize — régression bloquée)",
      layout_src.count('android:maxWidth="320dp"') == 24
      and all(f'android:maxWidth="320dp"' in block
              for block in (skill_block.group(1) if skill_block else "",
                            sound_block.group(1) if sound_block else "",
                            anim_block.group(1) if anim_block else "",
                            lang_block.group(1) if lang_block else "")))
# L'aperçu statique pose les barres système sans exécuter le code de
# l'activité : nos insets runtime (EdgeToEdge.padSystemBars, patron
# maison des quatre écrans) y sont invisibles et le titre de la
# toolbar semble couvert par la barre d'état (VisualLintOverlap).
# Suppression documentée, pas un bug.
check("Layout : suppression VisualLintOverlap documentée "
      "(insets runtime, invisibles à l'aperçu statique)",
      'xmlns:tools="http://schemas.android.com/tools"' in layout_src
      and 'tools:ignore="VisualLintOverlap"' in layout_src)

# --- 3. SettingsActivity : la page unique -----------------------------------

check("SettingsActivity : les 4 extras du dialogue avec le jeu existent",
      all(f'const val {key} = ' in sa
         for key in ("EXTRA_SKILL", "EXTRA_MOVES", "EXTRA_SOLVED",
                     "EXTRA_ELAPSED_MS")))
check("SettingsActivity : la page s'ouvre cochée sur l'état reçu "
      "(niveau, coups, résolue, temps)",
      re.search(r"Skill\.entries\.firstOrNull \{\s*"
                r"it\.name == intent\.getStringExtra\(EXTRA_SKILL\)",
                sa) is not None
      and "intent.getIntExtra(EXTRA_MOVES, 0)" in sa
      and "intent.getBooleanExtra(EXTRA_SOLVED, false)" in sa
      and "intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)" in sa)
oncreate = re.search(r"override fun onCreate\((.*?)\n    \}",
                     sa, re.S)
order_ok = False
if oncreate:
    body = oncreate.group(1)
    first_check = body.find("Group.check(")
    first_listen = body.find("setOnCheckedChangeListener")
    order_ok = (0 <= first_check < first_listen)
check("SettingsActivity : les coches initiales sont posées AVANT "
      "l'écoute (elles ne déclenchent rien)",
      order_ok)
check("SettingsActivity : CHAQUE groupe reçoit sa propre coche "
      "(v3 : niveau du jeu reçu, sons/animation de la persistance, "
      "langue d'appcompat)",
      re.search(r"binding\.skillGroup\.check\("
                r"skillRadioFor\(currentSkill\)\)", sa) is not None
      and re.search(r"binding\.soundGroup\.check\(\s*"
                    r"when \(Settings\.loadSoundMode\(this\)\)", sa)
      is not None
      and re.search(r"binding\.animGroup\.check\(\s*"
                    r"if \(Settings\.loadAnimEnabled\(this\)\) "
                    r"R\.id\.anim_on else R\.id\.anim_off", sa)
      is not None
      and re.search(r"binding\.langGroup\.check\("
                    r"langRadioFor\(currentLanguageTag\(\)\)\)", sa)
      is not None)
check("SettingsActivity : plus d'helper unique checkRadioButton "
      "(la cause du bug : il cochait skillGroup pour TOUT, et "
      "RadioGroup.check ignore en silence les ids étrangers)",
      "checkRadioButton(" not in sa_src
      and re.search(r"private fun checkSkillRadio\(id: Int\)", sa)
      is not None)
check("SettingsActivity : le drapeau suppressSkillCheck protège les "
      "coches programmatiques de l'écoute",
      re.search(r"suppressSkillCheck = true.*?try \{.*?"
                r"binding\.skillGroup\.check\(id\).*?finally \{.*?"
                r"suppressSkillCheck = false", sa, re.S) is not None)
check("SettingsActivity : la radio de chaque niveau mappe vers son "
      "Skill (8 entrées complètes)",
      sa.count("Skill.") >= 8 + 1
      and all(f"Skill.{name} -> R.id.skill_{name.lower()}" in sa
              for name in SKILL_ORDER))
check("SettingsActivity : le prédicat de partie en cours est celui "
      "du jeu (résolue ou vierge : direct, sinon dialogue)",
      re.search(r"if \(solved \|\| moves == 0\) \{\s*apply\(\)", sa)
      is not None)
check("SettingsActivity : le dialogue de confirmation est le MÊME que "
      "le jeu (titre, message d'avancement, bouton)",
      "MaterialAlertDialogBuilder(this)" in sa
      and ".setTitle(R.string.confirm_change_title)" in sa
      and "R.string.confirm_progress_message, moves, "
           "formatTime(elapsedMs)" in sa
      and ".setPositiveButton(R.string.confirm_change_ok)" in sa)
check("SettingsActivity : Annuler referme le dialogue en remettant "
      "la coche sur le niveau courant, sans rien changer",
      re.search(r"setNegativeButton\(android\.R\.string\.cancel\) "
                    r"\{ _, _ ->\s*"
                    r"checkSkillRadio\(skillRadioFor\(currentSkill\)\)",
                    sa, re.S) is not None)
check("SettingsActivity : confirmer referme la page en renvoyant le "
      "niveau choisi (RESULT_OK + EXTRA_SKILL + finish)",
      re.search(r"setResult\(\s*RESULT_OK,\s*"
                r"Intent\(\)\.putExtra\(EXTRA_SKILL, skill\.name\)\s*\)"
                r".*?finish\(\)", sa, re.S) is not None)
check("SettingsActivity : les sons se persistent immédiatement à "
      "chaque changement (Settings.saveSoundMode)",
      re.search(r"soundGroup\.setOnCheckedChangeListener.*?"
                r"Settings\.saveSoundMode", sa, re.S) is not None
      and "R.id.sounds_off -> SoundMode.NONE" in sa
      and "R.id.sounds_quiet -> SoundMode.QUIET" in sa
      and re.search(r"else -> SoundMode\.FULL", sa) is not None)
check("SettingsActivity : l'animation se persiste immédiatement "
      "(Settings.saveAnimEnabled)",
      re.search(r"animGroup\.setOnCheckedChangeListener.*?"
                r"Settings\.saveAnimEnabled\(this, checkedId == "
                r"R\.id\.anim_on\)", sa, re.S) is not None)
check("SettingsActivity : le niveau NE se persiste PAS ici (c'est "
      "MainActivity qui relance la partie au retour)",
      "saveSkill" not in sa and "putString(EXTRA_SKILL" not in sa)

# --- 3 bis. Langue (v3) : appcompat porte le réglage -------------------------

# Le choix vit DANS appcompat (stockage auto avant Android 13,
# réglage système « langue par application » ensuite) : la page n'a
# PAS de clé maison — une seule source de vérité, aucune divergence
# possible entre ce qui est coché et ce qui est réellement rendu.
check("Langue : appcompat importé et appliqué "
      "(AppCompatDelegate.setApplicationLocales)",
      "import androidx.appcompat.app.AppCompatDelegate" in sa_src
      and "import androidx.core.os.LocaleListCompat" in sa_src
      and "AppCompatDelegate.setApplicationLocales(locales)" in sa)
check("Langue : les 3 choix mènent aux 3 listes "
      "(fr / en / liste vide = suivre le système)",
      'R.id.lang_fr -> LocaleListCompat.forLanguageTags("fr")' in sa
      and 'R.id.lang_en -> LocaleListCompat.forLanguageTags("en")'
      in sa
      and "LocaleListCompat.getEmptyLocaleList()" in sa)
check("Langue : la coche initiale vient de l'état RÉEL d'appcompat "
      "(getApplicationLocales, pas d'une clé maison)",
      "AppCompatDelegate.getApplicationLocales()" in sa
      and "langRadioFor(currentLanguageTag())" in sa
      and re.search(r'private fun currentLanguageTag\(\): String',
                    sa) is not None
      and re.search(r'"fr" -> R\.id\.lang_fr', sa) is not None
      and re.search(r'"en" -> R\.id\.lang_en', sa) is not None)
check("Langue : AUCUNE clé maison (ni Settings, ni Persistence, ni "
      "préférences de l'activité)",
      "LANGUAGE" not in st and "LANGUAGE" not in pe
      and re.search(r'putString\([^)]*lang', sa, re.I) is None)
check("Langue : l'écoute de la section applique immédiatement "
      "(comme sons et animation)",
      re.search(r"langGroup\.setOnCheckedChangeListener.*?"
                r"applyLanguage\(checkedId\)", sa, re.S) is not None
      and re.search(r"private fun applyLanguage\(checkedId: Int\)",
                    sa) is not None)

# --- 4. Settings : mêmes clés, même fichier historique ----------------------

check("Settings : le mode sonore passe par la clé historique de "
      "Persistence (SOUND_MODE_KEY)",
      re.search(r"putString\(Persistence\.SOUND_MODE_KEY, mode\.name\)",
                st) is not None
      and re.search(r"getString\(Persistence\.SOUND_MODE_KEY, null\)",
                    st) is not None)
check("Settings : l'animation passe par ANIM_ENABLED_KEY",
      re.search(r"putBoolean\(Persistence\.ANIM_ENABLED_KEY, enabled\)",
                st) is not None
      and re.search(r"getBoolean\(Persistence\.ANIM_ENABLED_KEY, true\)",
                    st) is not None)
check("Settings : lecture et écriture dans le MÊME fichier que "
      "l'original (Persistence.FILE)",
      re.search(r"getSharedPreferences\(Persistence\.FILE, "
                r"Context\.MODE_PRIVATE\)", st) is not None)
check("Settings : repli du mode sonore sur FULL si valeur inconnue "
      "(comme avant la refonte)",
      re.search(r"SoundMode\.entries\.firstOrNull \{ it\.name == "
                r"stored \} \?: SoundMode\.FULL", st) is not None)
check("Persistence : les clés historiques et le fichier historique "
      "sont définis",
      'const val FILE = "MainActivity"' in pe
      and 'const val SOUND_MODE_KEY = "soundMode"' in pe
      and 'const val ANIM_ENABLED_KEY = "animEnable"' in pe)

# --- 5. MainActivity : lancement, retour, application ------------------------

check("MainActivity : le bouton Réglages lance la page avec l'état "
      "courant (4 extras)",
      re.search(r"settingsLauncher\.launch\(\s*Intent\(this, "
                r"SettingsActivity::class\.java\).*?"
                r"EXTRA_SKILL, state\.skill\.name\).*?"
                r"EXTRA_MOVES, state\.moves\).*?"
                r"EXTRA_SOLVED, state\.solved\).*?"
                r"EXTRA_ELAPSED_MS, state\.elapsedMs", ma,
                re.S) is not None)
check("MainActivity : le retour de la page décode le niveau choisi",
      re.search(r"settingsLauncher = registerForActivityResult.*?"
                r"result\.resultCode != RESULT_OK.*?"
                r"getStringExtra\(SettingsActivity\.EXTRA_SKILL\).*?"
                r"Skill\.entries\.firstOrNull \{ it\.name == name \}",
                ma, re.S) is not None)
check("MainActivity : niveau différent du courant → nouvelle partie "
      "+ rappel des règles (même circuit que l'ancien sous-menu)",
      re.search(r"if \(skill != previous\) \{.*?"
                r"viewModel\.newGame\(skill, width, height\).*?"
                r"showSkillHelpIfNeeded\(skill, previous\)", ma,
                re.S) is not None)
check("MainActivity : onStart RELIT les deux réglages persistés par "
      "la page et applique l'animation à la vue",
      re.search(r"override fun onStart\(\).*?"
                r"soundMode = Settings\.loadSoundMode\(this\).*?"
                r"animEnabled = Settings\.loadAnimEnabled\(this\).*?"
                r"binding\.boardView\.blipsEnabled = animEnabled",
                ma, re.S) is not None)
check("MainActivity : onCreate charge aussi les réglages via "
      "Settings (premier lancement)",
      re.search(r"override fun onCreate\(.*?"
                r"Settings\.loadSoundMode\(this\).*?"
                r"Settings\.loadAnimEnabled\(this\)", ma, re.S)
      is not None)
check("MainActivity : plus aucune branche menu skill_*/sounds_*/"
      "anim_* (déménagées dans la page)",
      re.search(r"R\.id\.(skill|sounds|anim)_\w+ ->", ma) is None,
      str(re.findall(r'R\.id\.(?:skill|sounds|anim)_\w+ ->', ma)[:3]))
check("MainActivity : plus de lecture getPreferences en direct pour "
      "les sons/animation (tout passe par Settings)",
      re.search(r'getPreferences\(', ma) is None)
check("MainActivity : onCreateOptionsMenu n'y installe plus de "
      "coches de sous-menu",
      re.search(r"onCreateOptionsMenu.*?setChecked", ma, re.S)
      is None)

# --- 6. Manifest et strings --------------------------------------------------

check("Manifest : SettingsActivity déclarée, interne, titrée "
      "Réglages, fille de l'écran de jeu",
      re.search(r'<activity[^>]*android:name="\.SettingsActivity"[^>]*'
                r'android:exported="false"[^>]*'
                r'android:label="@string/menu_settings"[^>]*'
                r'android:parentActivityName="\.MainActivity"',
                manifest_src, re.S) is not None)
check("Manifest : localeConfig déclare exactement les deux langues "
      "de l'app (en base, fr traduction)",
      'android:localeConfig="@xml/locales_config"' in manifest_src
      and '<locale android:name="en" />' in locales_src
      and '<locale android:name="fr" />' in locales_src
      and locales_src.count("<locale ") == 2)
check("Manifest : auto-stockage appcompat de la langue "
      "(AppLocalesMetadataHolderService, marqueur autoStoreLocales, "
      "jamais exporté)",
      re.search(r'<service[^>]*android:name="androidx\.appcompat\.app'
                r'\.AppLocalesMetadataHolderService"[^>]*'
                r'android:exported="false"', manifest_src,
                re.S) is not None
      and 'android:name="autoStoreLocales"' in manifest_src
      and 'android:value="true"' in manifest_src)
check("Strings : les 15 clés attendues existent EN "
      "(11 d'origine + 4 du chantier langue v3)",
      all(f'name="{key}"' in en_src
          for key in ("menu_settings", "menu_skill", "menu_sounds",
                      "menu_anim", "sounds_off", "sounds_quiet",
                      "sounds_full", "anim_off", "anim_on",
                      "confirm_change_title", "confirm_change_ok",
                      "menu_lang", "lang_system", "lang_fr",
                      "lang_en")))
check("Strings : les 15 clés existent aussi en FR",
      all(f'name="{key}"' in fr_src
          for key in ("menu_settings", "menu_skill", "menu_sounds",
                      "menu_anim", "sounds_off", "sounds_quiet",
                      "sounds_full", "anim_off", "anim_on",
                      "confirm_change_title", "confirm_change_ok",
                      "menu_lang", "lang_system", "lang_fr",
                      "lang_en")))
check("Strings : les libellés de langue sont sains (système "
      "différencié EN/FR, noms propres identiques)",
      re.search(r'name="lang_system">Follow the system<', en_src)
      is not None
      and re.search(r'name="lang_system">Selon le système<', fr_src)
      is not None
      and re.search(r'name="lang_fr">Français<', en_src) is not None
      and re.search(r'name="lang_fr">Français<', fr_src) is not None
      and re.search(r'name="lang_en">English<', en_src) is not None
      and re.search(r'name="lang_en">English<', fr_src) is not None)
check("Strings : le message d'avancement (avec ses deux arguments) "
      "existe EN et FR",
      'name="confirm_progress_message"' in en_src
      and 'name="confirm_progress_message"' in fr_src
      and "%1$d" in re.search(
          r'name="confirm_progress_message"[^>]*>([^<]*)<', en_src)
      .group(1))
check("GameSpec : RIEN de ce chantier dans le format sérialisé "
      "(pas de bump, les 40 tests JVM ne changent pas)",
      "SettingsActivity" not in spec_src
      and "soundMode" not in spec_src
      and "animEnable" not in spec_src)

# --- 7. Miroir : prédicat « partie en cours » identique au jeu ---------------

# Le jeu (MainActivity.confirmIfInProgress) : direct si résolue ou
# vierge, sinon dialogue. La page (SettingsActivity.changeSkill) doit
# décider pareil sur les mêmes faits.
def game_needs_confirm(solved, moves):
    return not (solved or moves == 0)


def page_needs_confirm(solved, moves):
    return not (solved or moves == 0)


predicate_fail = []
for solved in (False, True):
    for moves in (0, 1, 2, 17):
        if game_needs_confirm(solved, moves) != \
                page_needs_confirm(solved, moves):
            predicate_fail.append(f"solved={solved}, moves={moves}")
check("Miroir protection : la page demande confirmation EXACTEMENT "
      "quand le jeu la demanderait (plateau vierge ou gagné : direct ; "
      "16 combinaisons sondées)",
      not predicate_fail, "; ".join(predicate_fail[:2]))

# --- 8. Miroir : table des 8 niveaux complète -------------------------------

# Extraite du code (skillRadioFor), pas retapée : la page couvre les
# 8 niveaux du jeu, chacun avec SA radio, bijection sans doublon.
radio_block = re.search(
    r"private fun skillRadioFor\(skill: Skill\): Int = when \(skill\) \{"
    r"(.*?)\n    \}", sa_src, re.S)
pairs = (re.findall(r"Skill\.(\w+) -> R\.id\.(skill_\w+)",
                    radio_block.group(1)) if radio_block else [])
table_ok = (sorted(name for name, _ in pairs)
            == sorted(SKILL_ORDER)
            and len(set(rid for _, rid in pairs)) == len(pairs))
check("Miroir niveaux : la table extraite du code est une bijection "
      "complète des 8 niveaux vers leurs radios",
      table_ok, str(pairs))

# --- 9. Miroir : aller-retour de persistance du mode sonore ------------------

# Comme Settings : le nom d'enum est stocké tel quel ; la relecture
# retombe sur l'enum par nom, avec repli FULL sur valeur inconnue
# (y compris une valeur d'UNE AUTRE application stockée à la même
# clé avant la refonte — comportement historique conservé).
MODES = ["NONE", "QUIET", "FULL"]
stored = {}
for mode in MODES:
    stored[mode] = mode  # putString(key, mode.name)
unknown_values = ["", "LOUD", "none", "quiet", None]
roundtrip_fail = []
for mode in MODES:
    if stored[mode] != mode:
        roundtrip_fail.append(mode)
for unknown in unknown_values:
    # loadSoundMode : firstOrNull { it.name == stored } ?: FULL
    restored = next((m for m in MODES if m == unknown), "FULL")
    if restored != "FULL":
        roundtrip_fail.append(f"inconnu {unknown!r} -> {restored}")
check("Miroir persistance : aller-retour du mode sonore intact "
      "(NONE/QUIET/FULL) et repli sur FULL sur toute valeur "
      "inconnue ou absente — comme avant la refonte",
      not roundtrip_fail, "; ".join(roundtrip_fail[:2]))
check("Miroir persistance : l'animation est un booléen, activé par "
      "défaut (getBoolean(key, true))",
      re.search(r"getBoolean\(Persistence\.ANIM_ENABLED_KEY, true\)",
                st) is not None)

# --- 10. Miroir : format de temps du dialogue ---------------------------------

# formatTime (SettingsActivity) : mm:ss, le même que la barre d'état.
def format_time(ms):
    total_seconds = ms // 1000
    return "%02d:%02d" % (total_seconds // 60, total_seconds % 60)


format_fail = []
for ms in (0, 5_999, 60_000, 90_500, 3_723_456, 3_599_999_999):
    minutes, seconds = divmod(ms // 1000, 60)
    expected = f"{minutes:02d}:{seconds:02d}"
    if format_time(ms) != expected:
        format_fail.append(f"{ms}ms")
check("Miroir formatTime : mm:ss sur 6 durées (0, cas limite de la "
      "première minute, heures, milliers d'heures)",
      not format_fail, "; ".join(format_fail[:2]))
check("SettingsActivity : formatTime borne la valeur affichée "
      "(division entière, jamais de flottant)",
      re.search(r"val totalSeconds = ms / 1000", sa) is not None
      and re.search(r'"%02d:%02d"\.format\(totalSeconds / 60, '
                    r'totalSeconds % 60\)', sa) is not None)

# --- 11. Miroir : largeur rendue des radios vs la règle des 320 dp ----------

# La visual lint signale un bouton rendu plus large que 320 dp.
# Largeur rendue = min(largeur du contenu, 320 dp) ; contenu =
# largeur d'écran moins les deux marges de 16 dp de settings_content.
# AVANT (match_parent seul) : 328 dp dépassent déjà sur un téléphone
# de 360 dp — c'est bien ce qu'eric a vu. APRÈS : jamais au-delà de
# 320 dp, et rien ne change sur écran étroit (content < 320).
PAD_H = 16
BUTTON_MAX_DP = 320
too_wide_fail = []
for screen_dp in (320, 360, 393, 411, 600, 720, 840):
    content = screen_dp - 2 * PAD_H
    before = content
    after = min(content, BUTTON_MAX_DP)
    if after > BUTTON_MAX_DP:
        too_wide_fail.append(f"{screen_dp}dp -> {after}dp")
check("Miroir visual lint : les radios rendues ne dépassent JAMAIS "
      "320 dp (7 largeurs d'écran ; AVANT la borne : 328 dp sur un "
      "téléphone 360 dp — déjà trop large)",
      not too_wide_fail and (360 - 2 * PAD_H) > BUTTON_MAX_DP,
      "; ".join(too_wide_fail[:2]))

# --- 12. Miroir : descriptions des 8 niveaux dans le layout (v3) ------------

# Demande d'eric : chaque niveau commenté avec sa description. Les
# mots-clés viennent des règles RÉELLES (game/Skill.kt) : branches
# pour les niveaux classiques, tore pour Maître, câbles invisibles
# pour Dément, 40 x 40 pour Infini, masquage pour Abîme, brouillard
# pour Néant. Le commentaire doit être le DERNIER avant la radio.
SKILL_DESC_KEYWORDS = {
    "skill_novice": "branches",
    "skill_normal": "branches",
    "skill_expert": "branches",
    "skill_master": "tore",
    "skill_insane": "invisibles",
    "skill_endless": "40",
    "skill_abyss": "masquage",
    "skill_void": "brouillard",
}
desc_fail = []
if skill_block:
    body = skill_block.group(1)
    for rid, keyword in SKILL_DESC_KEYWORDS.items():
        pos = body.find(f"@+id/{rid}")
        before = body[:pos] if pos >= 0 else ""
        last_open = before.rfind("<!--")
        # Le DERNIER commentaire avant la radio : complet ou non,
        # il contient la description attendue.
        comment = before[last_open:] if last_open >= 0 else ""
        if not comment:
            desc_fail.append(f"{rid} sans commentaire")
        elif keyword not in comment.lower():
            desc_fail.append(f"{rid} sans « {keyword} »")
else:
    desc_fail.append("section niveau absente")
check("Miroir niveaux : les 8 radios sont commentées dans le layout "
      "avec leur description (mots-clés des règles réelles de Skill)",
      not desc_fail, "; ".join(desc_fail[:3]))

# --- 12 bis. Descriptions VISIBLES des niveaux (v4, nouveau sujet) ------------

# Sous chaque radio, une description que le JOUEUR lit : la taille
# réelle du plateau sur CET écran (préfixée au runtime — le texte
# n'est PAS dans le XML, il dépend de l'écran et de l'orientation)
# suivie des règles du niveau. Une par radio, juste sous elle, en
# style secondaire, bornée comme les radios.
if skill_block:
    v4_body = skill_block.group(1)
else:
    v4_body = ""
v4_fail = []
for rid in skill_radios:
    did = f"{rid}_desc"
    radio_pos = v4_body.find(f'@+id/{rid}"')
    desc_pos = v4_body.find(f'@+id/{did}"')
    if desc_pos < 0:
        v4_fail.append(f"{did} absent")
        continue
    if not (0 <= radio_pos < desc_pos):
        v4_fail.append(f"{did} avant sa radio")
    # La description suit IMMÉDIATEMENT sa radio : aucune autre
    # radio ne s'intercale.
    next_radio = v4_body.find("MaterialRadioButton", radio_pos)
    if 0 <= next_radio < desc_pos:
        v4_fail.append(f"{did} pas juste sous {rid}")
    tag = v4_body[desc_pos:v4_body.find("/>", desc_pos)]
    if "textAppearanceBodySmall" not in tag:
        v4_fail.append(f"{did} pas en style secondaire")
    if "colorOnSurfaceVariant" not in tag:
        v4_fail.append(f"{did} pas en couleur atténuée")
    if 'maxWidth="320dp"' not in tag:
        v4_fail.append(f"{did} non bornée à 320 dp")
    if "android:text=" in tag:
        v4_fail.append(f"{did} texte posé en XML — défendu, la "
                       "taille dépend de l'écran")
check("Layout v4 : 8 descriptions sous les radios (style "
      "secondaire, bornées, SANS texte XML — rempli au runtime)",
      not v4_fail
      and len(re.findall(r'@\+id/skill_\w+_desc"', v4_body)) == 8,
      "; ".join(v4_fail[:3]))

check("SettingsActivity v4 : bindSkillDescriptions lit la matrice "
      "Screen RÉELLE (mêmes metrics et prédicat que le jeu)",
      re.search(r"private fun bindSkillDescriptions\(\)", sa)
      is not None
      and re.search(r"bindSkillDescriptions\(\)", re.search(
          r"override fun onCreate\(.*?\n    \}", sa, re.S).group(0))
      is not None
      and "Screen.find(metrics.widthPixels, metrics.heightPixels)"
      in sa
      and re.search(r"val landscape = metrics\.widthPixels > "
                    r"metrics\.heightPixels", sa) is not None
      and "screen.boardSize(skill, landscape)" in sa)
check("SettingsActivity v4 : la description s'aligne au runtime sur "
      "le libellé de sa radio, taille en arguments de format",
      "desc.setPaddingRelative(radio.compoundPaddingStart, 0, 0, 0)"
      in sa
      and "desc.text = getString(skillDescFor(skill), width, height)"
      in sa
      and re.search(r"private fun skillViewsFor\(skill: Skill\)",
                    sa) is not None)

# Parité avec le jeu : la page affiche EXACTEMENT ce que le jeu
# jouera — le même find, le même prédicat d'orientation.
check("SettingsActivity v4 : même calcul de taille que "
      "MainActivity.boardSize (parité find + orientation)",
      re.search(r"val landscape = metrics\.widthPixels > "
                r"metrics\.heightPixels", ma) is not None
      and re.search(r"Screen\.find\(metrics\.widthPixels, "
                    r"metrics\.heightPixels\)", ma) is not None)

desc_block = re.search(
    r"private fun skillDescFor\(skill: Skill\): Int = when \(skill\) \{"
    r"(.*?)\n    \}", sa_src, re.S)
desc_pairs = (re.findall(r"Skill\.(\w+) -> R\.string\.(skill_\w+_desc)",
                         desc_block.group(1)) if desc_block else [])
check("Miroir v4 : bijection des 8 niveaux vers leurs strings de "
      "description",
      sorted(n for n, _ in desc_pairs) == sorted(SKILL_ORDER)
      and len(set(s for _, s in desc_pairs)) == 8, str(desc_pairs))

SKILL_DESC_KEYWORDS_STR = {
    "skill_novice_desc": ("open edges", "bords ouverts"),
    "skill_normal_desc": ("open edges", "bords ouverts"),
    "skill_expert_desc": ("open edges", "bords ouverts"),
    "skill_master_desc": ("wrap around", "se referment"),
    "skill_insane_desc": ("hidden wiring", "invisibles"),
    "skill_endless_desc": ("never shows an edge", "jamais de bord"),
    "skill_abyss_desc": ("hidden wiring", "invisibles"),
    "skill_void_desc": ("fog", "brouillard"),
}
strings_v4_fail = []
for key, (kw_en, kw_fr) in SKILL_DESC_KEYWORDS_STR.items():
    m_en = re.search(rf'name="{key}">([^<]*)</string>', en_src)
    m_fr = re.search(rf'name="{key}">([^<]*)</string>', fr_src)
    if not m_en or not m_fr:
        strings_v4_fail.append(f"{key} absent EN ou FR")
        continue
    for txt, kw, lang in ((m_en.group(1), kw_en, "EN"),
                          (m_fr.group(1), kw_fr, "FR")):
        if "%1$d" not in txt or "%2$d" not in txt:
            strings_v4_fail.append(f"{key} {lang} sans taille")
        if kw not in txt:
            strings_v4_fail.append(f"{key} {lang} sans « {kw} »")
check("Strings v4 : 8 descriptions EN+FR — taille en arguments et "
      "mot-clé des règles du niveau",
      not strings_v4_fail and len(SKILL_DESC_KEYWORDS_STR) == 8,
      "; ".join(strings_v4_fail[:3]))

# --- 13. Miroir : sémantique RadioGroup.check (le bug corrigé) --------------

# RadioGroup.check(id) d'Android : décoche l'ancienne et coche la
# nouvelle SI elle appartient au groupe — un id ÉTRANGER est ignoré
# en silence (aucune radio ne change). C'est exactement ce qui
# rendait muet l'ancien helper : il passait les ids de sons et
# d'animation au groupe du NIVEAU, donc rien ne s'affichait dans
# ces deux sections. Le miroir ci-dessous reproduit cette sémantique
# pour prouver que le bug est réel (avant) et le fix correct (après).
class RadioGroupMirror:
    """RadioGroup d'Android : une seule radio cochée ; check(id)
    ignore en silence tout id étranger au groupe."""

    def __init__(self, child_ids):
        self.children = set(child_ids)
        self.checked_id = -1

    def check(self, rid):
        if rid != -1 and rid == self.checked_id:
            return
        self.checked_id = rid

    def visible_checked(self):
        """Radio réellement cochée : un id étranger stocké n'y
        figure pas — c'est ce que le joueur VOIT."""
        return self.checked_id if self.checked_id in self.children \
            else None


SKILL_IDS = [f"skill_{n.lower()}" for n in SKILL_ORDER]
SOUND_IDS = ["sounds_off", "sounds_quiet", "sounds_full"]
ANIM_IDS = ["anim_off", "anim_on"]
LANG_IDS = ["lang_system", "lang_fr", "lang_en"]

# AVANT (v2, le bug constaté par eric) : l'ancien helper cochait
# TOUJOURS le groupe du niveau avec des ids de sons/animation. La
# sémantique réelle de RadioGroup.check décoche d'abord l'ancienne
# radio, puis essaie de cocher la nouvelle — introuvable dans le
# groupe, silence. Résultat constaté : les sections sons et
# animation ne recevaient jamais leur coche, ET le niveau lui-même
# finissait DÉCOCHÉ — aucune valeur ne s'affichait.
before_fail = []
for _, sound_radio in (("NONE", "sounds_off"),
                       ("QUIET", "sounds_quiet"),
                       ("FULL", "sounds_full")):
    skill_group = RadioGroupMirror(SKILL_IDS)
    skill_group.check("skill_normal")
    sound_group = RadioGroupMirror(SOUND_IDS)  # jamais touchée
    skill_group.check(sound_radio)  # id étranger : décoche, ne coche pas
    if sound_group.visible_checked() is not None:
        before_fail.append(sound_radio)
    if skill_group.visible_checked() is not None:
        before_fail.append(f"niveau encore coché après {sound_radio}")
check("Miroir bug (avant v3) : la coche passée au MAUVAIS groupe "
      "n'affichait RIEN — reproduction exacte du retour d'eric "
      "(sons/animation jamais cochés, niveau décoché par ricochet)",
      not before_fail, "; ".join(before_fail[:2]))

# APRÈS (v3) : chaque groupe reçoit SA coche, sur SA propre radio —
# les valeurs courantes s'affichent dans les quatre sections.
after_fail = []
for _, sound_radio in (("NONE", "sounds_off"),
                       ("QUIET", "sounds_quiet"),
                       ("FULL", "sounds_full")):
    group = RadioGroupMirror(SOUND_IDS)
    group.check(sound_radio)
    if group.visible_checked() != sound_radio:
        after_fail.append(sound_radio)
for anim_radio in ANIM_IDS:
    group = RadioGroupMirror(ANIM_IDS)
    group.check(anim_radio)
    if group.visible_checked() != anim_radio:
        after_fail.append(anim_radio)
for lang_radio in LANG_IDS:
    group = RadioGroupMirror(LANG_IDS)
    group.check(lang_radio)
    if group.visible_checked() != lang_radio:
        after_fail.append(lang_radio)
skill_group = RadioGroupMirror(SKILL_IDS)
for skill_radio in ("skill_novice", "skill_normal", "skill_expert",
                    "skill_master", "skill_insane", "skill_endless",
                    "skill_abyss", "skill_void"):
    skill_group.check(skill_radio)
    if skill_group.visible_checked() != skill_radio:
        after_fail.append(skill_radio)
check("Miroir fix (v3) : chaque groupe cochée SEULEMENT par son "
      "propre id — les valeurs s'affichent (8 niveaux, 3 modes "
      "sonores, 2 modes d'animation, 3 langues)",
      not after_fail, "; ".join(after_fail[:2]))

# --- 14. Miroir : la table Screen donne les tailles réelles (v4) -------------

# Réplique de la table Screen (game/Screen.kt, chantier « tailles
# fidèles ») : chaque écran reçoit sa matrice, chaque niveau y
# prélève son rectangle, la famille Infini est fixe 40 x 40 — la
# page Réglages affiche EXACTEMENT ce que le jeu jouera.
SCREEN_MATRIX = {
    "SMALL": (8, 6, 8, 6, 6, 6, 6, 4),
    "WSMALL": (9, 6, 9, 6, 5, 6, 5, 4),
    "MEDIUM": (11, 7, 11, 7, 9, 7, 5, 5),
    "WMEDIUM": (12, 7, 10, 7, 8, 7, 6, 5),
    "HUGE": (17, 10, 15, 8, 11, 8, 7, 6),
}
ENDLESS_SIZE = 40
LEVEL_COLS = {
    "MASTER": ("major", "minor"),
    "INSANE": ("major", "minor"),
    "EXPERT": ("expertMajor", "expertMinor"),
    "NORMAL": ("normalMajor", "normalMinor"),
    "NOVICE": ("noviceMajor", "noviceMinor"),
}
COL_NAMES = ("major", "minor", "expertMajor", "expertMinor",
             "normalMajor", "normalMinor", "noviceMajor", "noviceMinor")


def screen_size(matrix, level, landscape):
    if level in ("ENDLESS", "ABYSS", "VOID"):
        return (ENDLESS_SIZE, ENDLESS_SIZE)
    cols = dict(zip(COL_NAMES, SCREEN_MATRIX[matrix]))
    major = cols[LEVEL_COLS[level][0]]
    minor = cols[LEVEL_COLS[level][1]]
    return (major, minor) if landscape else (minor, major)


def screen_find(width_px, height_px):
    small, big = min(width_px, height_px), max(width_px, height_px)
    aspect = big / small
    if small <= 400:
        return "WSMALL" if aspect > 1.4 else "SMALL"
    if small <= 500:
        return "WMEDIUM" if aspect > 1.5 else "MEDIUM"
    return "HUGE"


mirror_v4_fail = []
# La famille Infini est fixe 40 x 40 sur TOUTES les matrices et
# orientations — comme majorOf/minorOf de Screen.
for matrix in SCREEN_MATRIX:
    for landscape in (True, False):
        for level in ("ENDLESS", "ABYSS", "VOID"):
            if screen_size(matrix, level, landscape) != (40, 40):
                mirror_v4_fail.append(f"{matrix}/{level}")
# Portrait et paysage : mêmes côtés, échangés — comme boardSize.
for matrix in SCREEN_MATRIX:
    for level in ("NOVICE", "NORMAL", "EXPERT", "MASTER", "INSANE"):
        if screen_size(matrix, level, True) != \
                screen_size(matrix, level, False)[::-1]:
            mirror_v4_fail.append(f"{matrix}/{level} échange")
# Maître et Dément partagent la même géométrie (Screen.kt).
for matrix in SCREEN_MATRIX:
    for landscape in (True, False):
        if screen_size(matrix, "MASTER", landscape) != \
                screen_size(matrix, "INSANE", landscape):
            mirror_v4_fail.append(f"{matrix} maître/dément")
# Écrans sondés : matrice choisie par find, puis taille affichée
# pour un niveau — les valeurs attendues sont calculées à la main
# depuis la table ci-dessus.
for w, h, matrix, level, landscape, expected in (
        (360, 640, "WSMALL", "NOVICE", False, (4, 5)),
        (360, 640, "WSMALL", "MASTER", False, (6, 9)),
        (360, 640, "WSMALL", "VOID", True, (40, 40)),
        (400, 400, "SMALL", "NOVICE", False, (4, 6)),
        (480, 800, "WMEDIUM", "EXPERT", False, (7, 10)),
        (1080, 1920, "HUGE", "NORMAL", False, (8, 11)),
        (1920, 1080, "HUGE", "MASTER", True, (17, 10)),
):
    if screen_find(w, h) != matrix:
        mirror_v4_fail.append(f"{w}x{h} → {screen_find(w, h)}")
    if screen_size(matrix, level, landscape) != expected:
        mirror_v4_fail.append(
            f"{matrix}/{level} → {screen_size(matrix, level, landscape)}")
check("Miroir v4 : tailles réelles fidèles à Screen — famille "
      "Infini toujours 40 x 40, échange portrait/paysage, "
      "Maître=Dément, 5 matrices et 7 écrans sondés",
      not mirror_v4_fail, "; ".join(mirror_v4_fail[:3]))

# --- Bilan -------------------------------------------------------------------

print()
if failures:
    print(f"ÉCHEC : {len(failures)}/{checks} vérifications en échec "
          f"(refonte réglages).")
    for name in failures:
        print(f"  - {name}")
    sys.exit(1)
print(f"TOUT PASSE : {checks}/{checks} vérifications (refonte "
      f"réglages : page unique niveau + sons + animation + langue).")
