#!/usr/bin/env python3
"""Validation du chantier « refonte UI À propos » (page autonome).

Demande d'eric du 14 nov. 2026 : l'À propos quitte sa fenêtre de
dialogue pour une PAGE AUTONOME, qui ajoute les propositions de
participation (signaler un bug, contacter l'auteur, proposer une
traduction, proposer un skin), puis les QUATRE LISTES des
fonctionnalités par rapport au jeu d'origine : nouvelles,
modifiées, conservées, retirées (et pourquoi).

Les adresses réelles ne sont pas encore disponibles : chaque
proposition affiche « lien à venir » / « link to come », et
l'évolution « Renseigner les liens de À propos » rejoint la
feuille de route (README). La page porte AUSSI la licence
complète (sujet « Amélioration de la licence » du 13 nov.,
volet à propos) : KNetWalk (Andi Peredri, Thomas Nagy et Fela
Winkelmolen), Scrambled Net (Ian Cameron Smith, Michael Mueller),
Scrambled Net 2 (Éric Chardon) — le NOTICE du dépôt est corrigé
de la même occasion (« Kainhofer » repris du README de Mueller
était une erreur : le copyright officiel KNetWalk cite
Winkelmolen, vérifié le 13 nov. 2026 dans les crédits KDE).

V2 (retour d'eric du 14 nov.) : la rubrique « L'auteur » s'insère
entre « Le jeu » et « Origine et licence » — les motivations
(passion des jeux de logique, découverte de l'application de
Smith début 2010 avec son source, espoirs placés dans la
reprise de Mueller en 2014) ; et la liste des fonctionnalités
retirées CONSOLIDE la suppression de toutes les dépendances de
l'origine, précisées entre parenthèses (HermitAndroid,
HermitLibrary, dont le générateur aléatoire MTRandom, remplacé
par le générateur standard de Kotlin).

Ce script PARSE les sources (aucun littéral retapé à la main) :
patron de la page (toolbar + ScrollView bord à bord, ViewBinding,
contenu runtime depuis les arrays EN + FR comme l'aide), retrait
propre du dialogue (MainActivity, strings mortes), manifeste,
et MIROIRS : les 7 sections existent dans les deux langues, même
nombre de puces EN et FR par liste, mots-clés obligatoires par
section (auteurs, niveaux, « lien à venir »).
"""

import re
import sys
import xml.dom.minidom

BASE = "app/src/main/kotlin/net/chardons/netscramble2"
RES = "app/src/main/res"
checks = 0
failures = []


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def check(label, ok, info=""):
    global checks
    checks += 1
    print(("  ok  " if ok else "ÉCHEC ") + label + (f" — {info}" if info and not ok else ""))
    if not ok:
        failures.append(label)


# ---------------------------------------------------------------------------
# 1. MainActivity : le dialogue est remplacé par la page, proprement.
# ---------------------------------------------------------------------------
ma = read(f"{BASE}/MainActivity.kt")
check("MainActivity : menu_about lance AboutActivity",
      re.search(r"menu_about ->\s*\{[^}]*startActivity\(Intent\(this, AboutActivity::class\.java\)\)",
                ma, re.S) is not None)
check("MainActivity : plus de showAbout", "showAbout" not in ma)
check("MainActivity : plus de about_title ni about_message",
      "about_title" not in ma and "about_message" not in ma)
check("MainActivity : les autres dialogues restent (victoire, règles)",
      "showWinDialog" in ma and "showSkillHelpIfNeeded" in ma)

# ---------------------------------------------------------------------------
# 2. Layout : patron maison des pages (toolbar + ScrollView bord à bord).
# ---------------------------------------------------------------------------
layout = read(f"{RES}/layout/activity_about.xml")
check("Layout : minidom accepte activity_about.xml",
      xml.dom.minidom.parseString(layout).toxml() is not None)
check("Layout : MaterialToolbar titrée menu_about",
      'about_toolbar' in layout and '@string/menu_about' in layout)
check("Layout : toolbar wrap_content + minHeight actionBarSize (insets)",
      re.search(r'about_toolbar.*?wrap_content', layout, re.S) is not None
      and '?attr/actionBarSize' in layout)
check("Layout : ScrollView + about_content vertical",
      'about_scroll' in layout and 'about_content' in layout
      and re.search(r'about_content.*?vertical', layout, re.S) is not None)
check("Layout : padding horizontal partagé (content_padding_h)",
      "@dimen/content_padding_h" in layout)
check("Layout : pas de findViewById dans la page (ViewBinding)",
      "findViewById" not in layout)

# ---------------------------------------------------------------------------
# 3. AboutActivity : patron de l'aide, contenu runtime, version.
# ---------------------------------------------------------------------------
sa = read(f"{BASE}/AboutActivity.kt")
check("AboutActivity : EdgeToEdge appliqué + insets toolbar/scroll",
      "EdgeToEdge.apply(window)" in sa
      and "padSystemBars(binding.aboutToolbar, top = true)" in sa
      and "padSystemBars(binding.aboutScroll, bottom = true)" in sa)
check("AboutActivity : ViewBinding gonflé (ActivityAboutBinding)",
      "ActivityAboutBinding.inflate(layoutInflater)" in sa)
check("AboutActivity : setSupportActionBar + retour (up)",
      "setSupportActionBar(binding.aboutToolbar)" in sa
      and "setDisplayHomeAsUpEnabled(true)" in sa)
check("AboutActivity : sections lues depuis about_titles/about_texts",
      "R.array.about_titles" in sa and "R.array.about_texts" in sa)
check("AboutActivity : version posée au runtime (title_version + Updater)",
      "Updater.currentVersion(this)" in sa and "R.string.title_version" in sa
      and "R.string.app_name" in sa)
check("AboutActivity : jamais de version codée en dur dans les strings",
      not re.search(r'[Vv]ersion\s+\d', read(f"{RES}/values/arrays.xml")))
check("AboutActivity : sections « titre + texte » (patron de l'aide)",
      "sectionTitle" in sa and "sectionText" in sa
      and "textAppearanceTitleMedium" in sa
      and "textAppearanceBodyMedium" in sa)
check("AboutActivity : structure équilibrée, pas de « /* » dans un KDoc",
      sa.count("{") == sa.count("}") and sa.count("(") == sa.count(")")
      and "/*" not in re.sub(r"^\s*/\*\*.*?\*/", "", sa, flags=re.S | re.M))

# ---------------------------------------------------------------------------
# 4. Arrays EN + FR : les 7 sections, mêmes index, mots-clés obligatoires.
# ---------------------------------------------------------------------------
def items_of(path, name):
    src = read(path)
    m = re.search(rf'<string-array name="{name}">(.*?)</string-array>', src, re.S)
    return re.findall(r"<item>(.*?)</item>", m.group(1), re.S) if m else []


def flat(items):
    # Android rend l'apostrophe échappée « \' » par une apostrophe
    # droite : le miroir compare la forme RENDERED, pas le fichier.
    return [re.sub(r"\\'", "'", re.sub(r"\s+", " ", it)).strip()
            for it in items]


en_titles = flat(items_of(f"{RES}/values/arrays.xml", "about_titles"))
fr_titles = flat(items_of(f"{RES}/values-fr/arrays.xml", "about_titles"))
en_texts = flat(items_of(f"{RES}/values/arrays.xml", "about_texts"))
fr_texts = flat(items_of(f"{RES}/values-fr/arrays.xml", "about_texts"))

check("Arrays : 8 titres EN et FR",
      len(en_titles) == 8 and len(fr_titles) == 8,
      f"EN={len(en_titles)} FR={len(fr_titles)}")
check("Arrays : 8 textes EN et FR",
      len(en_texts) == 8 and len(fr_texts) == 8,
      f"EN={len(en_texts)} FR={len(fr_texts)}")
check("Arrays : la rubrique « L'auteur » en 2e position (EN et FR)",
      "The author" in en_titles[1] and "auteur" in fr_titles[1],
      f"EN={en_titles[1]!r} FR={fr_titles[1]!r}")
check("Arrays : aucun item vide",
      all(t for t in en_titles + fr_titles + en_texts + fr_texts))

# Mots-clés par section (index dans about_texts) — EN puis FR.
required = [
    (0, ["Scrambled Net 2", "KNetWalk", "terminal"], 0,
     ["Scrambled Net 2", "KNetWalk", "terminal"]),
    (1, ["Éric Chardon", "logic and puzzle games",
         "Ian Cameron Smith", "Michael Mueller", "2010", "2014",
         "Vibe", "Mistral AI", "GPL-2.0"], 1,
     ["Éric Chardon", "jeux de logique", "Ian Cameron Smith",
      "Michael Mueller", "2010", "2014",
      "Vibe", "Mistral AI", "GPL-2.0"]),
    (2, ["Peredri", "Nagy", "Winkelmolen", "Ian Cameron Smith",
         "Michael Mueller", "Éric Chardon", "GPL-2.0",
         "github.com/echardon69-collab/Scrambled-Net-2"], 2,
     ["Peredri", "Nagy", "Winkelmolen", "Ian Cameron Smith",
      "Michael Mueller", "Éric Chardon", "GPL-2.0",
      "github.com/echardon69-collab/Scrambled-Net-2"]),
    (3, ["Report a bug", "Contact the author",
         "translation into a new language", "tile skin",
         "link to come"], 3,
     ["Signaler un bug", "Contacter l'auteur",
      "traduction dans une nouvelle langue", "nouveau skin",
      "lien à venir"]),
    (4, ["Endless", "Abyss", "Void", "40 × 40", "title screen",
         "GitHub", "Play Store", "rhythm", "Settings",
         "language"], 4,
     ["Infini", "Abîme", "Néant", "40 × 40", "page titre",
      "GitHub", "Play Store", "rythmes", "Réglages",
      "langue"]),
    (5, ["VectorDrawables", "synthesized", "battery",
         "zoom", "spanning-tree", "submenus"], 5,
     ["VectorDrawables", "synthétisés", "batterie",
      "zoom", "arbre couvrant", "sous-menus"]),
    (6, ["Novice", "Insane", "auto-solver", "Personal bests",
         "Screen matrix", "help"], 6,
     ["Novice", "Dément", "auto-solveur", "records",
      "matrice Screen", "aide"]),
    (7, ["D-pad", "focus cursor", "MTRandom", "HermitAndroid",
         "Kotlin", "no code from the original"], 7,
     ["D-pad", "curseur de focus", "MTRandom", "HermitAndroid",
      "Kotlin", "aucun code de l'original"]),
]
for en_ix, en_kw, fr_ix, fr_kw in required:
    missing_en = [kw for kw in en_kw if kw not in en_texts[en_ix]]
    missing_fr = [kw for kw in fr_kw if kw not in fr_texts[fr_ix]]
    check(f"Arrays : mots-clés de la section {en_ix + 1} (EN)",
          not missing_en, f"manque {missing_en}")
    check(f"Arrays : mots-clés de la section {en_ix + 1} (FR)",
          not missing_fr, f"manque {missing_fr}")

# Miroir : même nombre de puces « • » EN / FR dans les 4 listes
# (sections 3 à 6 — la participation en compte 4, les listes
# fonctionnalités les leurs ; l'égalité EN/FR garantit qu'aucune
# puce n'a été oubliée en traduisant).
for ix in (3, 4, 5, 6, 7):
    check(f"Miroir : section {ix + 1} — autant de puces EN que FR",
          en_texts[ix].count("•") == fr_texts[ix].count("•"),
          f"EN={en_texts[ix].count('•')} FR={fr_texts[ix].count('•')}")
# Miroir : les 4 propositions de participation, dans les 2 langues.
check("Miroir : 4 propositions de participation (bug, contact, "
      "traduction, skin) EN",
      en_texts[3].count("•") == 4)
check("Miroir : 4 propositions de participation FR",
      fr_texts[3].count("•") == 4)
check("Miroir : chaque proposition EN annonce « link to come »",
      en_texts[3].count("link to come") == 4)
check("Miroir : chaque proposition FR annonce « lien à venir »",
      fr_texts[3].count("lien à venir") == 4)

# Miroir : la section licence cite les trois étages de la chaîne,
# dans le même ordre EN et FR (jeu KDE, portage, refonte).
chain = ["KNetWalk", "Scrambled Net", "Scrambled Net 2"]
for lang, text in (("EN", en_texts[2]), ("FR", fr_texts[2])):
    pos = [text.find(word) for word in chain]
    check(f"Miroir : chaîne de licence {lang} dans l'ordre",
          all(p >= 0 for p in pos) and pos == sorted(pos))

# Mention Mistral AI (Vibe) — chantier du 17 nov. 2026 : la rubrique
# « L'auteur » précise que la refonte a été écrite avec l'aide de
# Vibe, l'assistant de développement de Mistral AI, sous la
# conduite, la relecture et la validation systématique de l'auteur,
# et que le code produit rejoint le dépôt sous la même GPL-2.0.
check("Mistral : mention Vibe + Mistral AI dans « L'auteur » EN",
      "Vibe" in en_texts[1] and "Mistral AI" in en_texts[1])
check("Mistral : mention Vibe + Mistral AI dans « L'auteur » FR",
      "Vibe" in fr_texts[1] and "Mistral AI" in fr_texts[1])
check("Mistral : l'auteur garde conduite, relecture et validation EN",
      "direction, review and systematic validation" in en_texts[1])
check("Mistral : l'auteur garde conduite, relecture et validation FR",
      "sa conduite, sa relecture et sa validation" in fr_texts[1])
check("Mistral : le code rejoint le dépôt sous GPL-2.0 (EN et FR)",
      "same GPL-2.0 license" in en_texts[1]
      and "même licence GPL-2.0" in fr_texts[1])
for tag, path in (("corse", "values-co"), ("alsacien", "values-gsw-rFR")):
    items = flat(items_of(f"{RES}/{path}/arrays.xml", "about_texts"))
    check(f"Mistral : mention présente dans « L'auteur » ({tag})",
          len(items) == 8 and "Vibe" in items[1]
          and "Mistral AI" in items[1] and "GPL-2.0" in items[1])
check("FR : coquille « phylosophie » corrigée en « philosophie »",
      "phylosophie" not in read(f"{RES}/values-fr/arrays.xml")
      and "philosophie" in read(f"{RES}/values-fr/arrays.xml"))

# ---------------------------------------------------------------------------
# 5. Strings : les mortes du dialogue sont retirées, les vives intactes.
# ---------------------------------------------------------------------------
en_strings = read(f"{RES}/values/strings.xml")
fr_strings = read(f"{RES}/values-fr/strings.xml")
check("Strings : about_title et about_message retirées (EN et FR)",
      not re.search(r'<string name="about_', en_strings)
      and not re.search(r'<string name="about_', fr_strings))
check("Strings : menu_about toujours là (EN et FR)",
      'name="menu_about"' in en_strings and 'name="menu_about"' in fr_strings)
all_res = "\n".join(read(f"{RES}/values/arrays.xml")
                    + read(f"{RES}/values-fr/arrays.xml"))
check("Strings : aucune référence morte à about_message",
      not re.search(r"about_message", read(f"{BASE}/MainActivity.kt")
                    + read(f"{BASE}/AboutActivity.kt") + all_res))
check("Strings EN : apostrophes échappées dans les arrays",
      not re.search(r"(?<!\\)'", re.sub(r"<item>|</item>", "",
                                        read(f"{RES}/values/arrays.xml").split("about_titles")[1])))
check("Strings FR : apostrophes échappées dans les arrays",
      not re.search(r"(?<!\\)'", re.sub(r"<item>|</item>", "",
                                        read(f"{RES}/values-fr/arrays.xml").split("about_titles")[1])))

# ---------------------------------------------------------------------------
# 6. Manifeste + menu : page déclarée, item du menu inchangé.
# ---------------------------------------------------------------------------
manifest = read("app/src/main/AndroidManifest.xml")
check("Manifeste : AboutActivity déclarée, non exportée, parent jeu",
      re.search(r'<activity\s+android:name="\.AboutActivity"\s+'
                r'android:exported="false"\s+'
                r'android:label="@string/menu_about"\s+'
                r'android:parentActivityName="\.MainActivity"',
                manifest) is not None)
menu = read(f"{RES}/menu/menu_main.xml")
check("Menu : menu_about toujours dans le débordement, id inchangé",
      'menu_about' in menu and 'showAsAction="never"' in menu)

# ---------------------------------------------------------------------------
# 7. Licence : NOTICE corrigé (Winkelmolen, pas Kainhofer).
# ---------------------------------------------------------------------------
notice = read("NOTICE")
check("NOTICE : les trois auteurs KNetWalk cités (Peredri, Nagy, "
      "Winkelmolen)",
      all(n in notice for n in ("Andi Peredri", "Thomas Nagy",
                                "Fela Winkelmolen")))
check("NOTICE : « Kainhofer » corrigé (erreur héritée du README "
      "de Mueller)", "Kainhofer" not in notice)

# ---------------------------------------------------------------------------
# 8. Roadmap : l'évolution « Renseigner les liens de À propos ».
# ---------------------------------------------------------------------------
readme = read("README.md")
check("README : évolution « Renseigner les liens de À propos » "
      "inscrite", "Renseigner les liens de À propos" in readme)
check("README : le chantier À propos y est marqué livré",
      re.search(r"« À propos » : page autonome.*\(livré", readme, re.S)
      is not None)

print()
if failures:
    print(f"ÉCHEC : {len(failures)} vérification(s) en échec "
          f"sur {checks} :")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"TOUT PASSE : {checks} vérifications (À propos : page autonome "
      f"— participation, licence complète et listes des fonctionnalités).")
