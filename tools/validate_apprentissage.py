#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Validation du chantier « Apprentissage » (17 nov. 2026).

Mode Apprentissage acté avec eric : sept leçons interactives
accessibles par le popup « Outils » SEULEMENT (pas d'entrée écran
titre), rejouables à volonté, coche persistée à côté des parties
(aucun record, aucune incidence sur la partie en cours). Une leçon
= un plateau reproductible (graine figée, invariants épinglés par
LessonTest) + des bulles de guidance dans le bandeau bas (patron
de la démo) + UNE action attendue à la fois.

Les sept leçons dans l'ordre validé :
  1. Tourner et connecter — 3 x 3 Novice, UN terminal ;
  2. La méthode et le verrou — 5 x 5 Novice, TROIS terminaux,
     proche en proche puis verrouillage ;
  3. Les aides — 5 x 5 Novice, indice informatif puis correctif
     scellé, puis Résoudre RÉELLEMENT EXÉCUTÉ (jamais scoré) ;
  4. Le tableau torique — 5 x 5 Maître, une connexion de la
     solution TRAVERSE le bord (graine à invariant) ;
  5. Les tuiles invisibles — 5 x 5 Dément, au moins deux masquées ;
  6. Le tableau infini — 40 x 40 PRÉ-RÉSOLU cameraOnly : tour
     HORIZONTAL puis VERTICAL jusqu'à RETROUVER le serveur, puis
     pincements zoom/dézoom (scénario d'eric, version enrichie ;
     PAS de mini-puzzle ni victoire) ;
  7. Le brouillard — 6 x 6 Néant, générateur ORIGINAL.

Deux parties, comme les validateurs précédents :
  1. Vérification statique : les sources sont PARSÉES (jamais de
     valeurs retapées à la main dans ce script).
  2. Cohérence transverse : identifiants de bulles utilisés dans
     Lessons.kt, présents dans les strings EN+FR ET dans le when
     de LessonActivity ; graines présentes et placeholders partis.

Usage : python3 tools/validate_apprentissage.py
"""

import re
import sys
from pathlib import Path

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
    return Path(path).read_text(encoding="utf-8")


def count(st, needle):
    return st.count(needle)


lessons = read(KOTLIN / "Lessons.kt")
lvm = read(KOTLIN / "LessonViewModel.kt")
lact = read(KOTLIN / "LessonActivity.kt")
llist = read(KOTLIN / "LessonsListActivity.kt")
ma = read(KOTLIN / "MainActivity.kt")
bv = read(KOTLIN / "ui/BoardView.kt")
st = read(KOTLIN / "Settings.kt")
pe = read(KOTLIN / "Persistence.kt")
manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
menu_tools = read(RES / "menu/menu_tools.xml")
layout_lesson = read(RES / "layout/activity_lesson.xml")
layout_list = read(RES / "layout/activity_lessons_list.xml")
strings_en = read(RES / "values/strings.xml")
strings_fr = read(RES / "values-fr/strings.xml")
dimens = read(RES / "values/dimens.xml")
lesson_test = read(TESTS / "LessonTest.kt")

# ---------------------------------------------------------------------------
# 1. Lessons.kt — les sept leçons, leurs étapes, leurs graines.
# ---------------------------------------------------------------------------

STEP_RE = re.compile(r'LessonStep\("(lesson_l\w+)", ExpectKind\.(\w+)\)')


def lesson_body(num):
    """Corps de lessonN() : du nom à la fonction suivante ou à ALL."""
    start = lessons.find(f"private fun lesson{num}()")
    if start < 0:
        return ""
    nexts = [lessons.find(f"private fun lesson{n}()") for n in range(num + 1, 8)]
    nexts = [n for n in nexts if n > 0]
    end = min(nexts) if nexts else lessons.find("val ALL: List<Lesson>")
    return lessons[start:end]


def lesson_steps(num):
    return STEP_RE.findall(lesson_body(num))


check("Lessons : les sept fonctions leçon existent",
      all(f"private fun lesson{n}()" in lessons for n in range(1, 8)))

check("Lessons : ALL référence l1 à l7 dans l'ordre",
      count(lessons, "lesson1(), lesson2(), lesson3(), lesson4(),") == 1
      and count(lessons, "lesson5(), lesson6(), lesson7()") == 1)

EXPECTED_STEPS = {
    1: [("lesson_l1_b1", "ROTATE"), ("lesson_l1_b2", "VICTORY"), ("lesson_l1_b3", "CONTINUE")],
    2: [("lesson_l2_b1", "ROTATE"), ("lesson_l2_b2", "LOCK"), ("lesson_l2_b3", "VICTORY"), ("lesson_l2_b4", "CONTINUE")],
    3: [("lesson_l3_b1", "HINT_SIGNAL"), ("lesson_l3_b2", "HINT_FIX"), ("lesson_l3_b3", "SOLVE"), ("lesson_l3_b3", "VICTORY"), ("lesson_l3_b4", "CONTINUE")],
    4: [("lesson_l4_b1", "CONTINUE"), ("lesson_l4_b2", "VICTORY"), ("lesson_l4_b3", "CONTINUE")],
    5: [("lesson_l5_b1", "ROTATE"), ("lesson_l5_b2", "VICTORY"), ("lesson_l5_b3", "CONTINUE")],
    6: [("lesson_l6_b1", "PAN_X"), ("lesson_l6_b2", "PAN_Y"), ("lesson_l6_b3", "ZOOM"), ("lesson_l6_b4", "CONTINUE")],
    7: [("lesson_l7_b1", "ROTATE"), ("lesson_l7_b2", "VICTORY"), ("lesson_l7_b3", "CONTINUE")],
}

for num, expected in EXPECTED_STEPS.items():
    got = lesson_steps(num)
    check(f"Leçon {num} : scénario conforme ({len(expected)} étapes)",
          got == expected, f"lu={got}")

check("Leçon 3 : la bulle de Résoudre porte DEUX étapes (SOLVE puis VICTORY)",
      lesson_steps(3)[2][0] == lesson_steps(3)[3][0] == "lesson_l3_b3")

SEED_RE = re.compile(r"seed = (\d+)L")


def lesson_seed(num):
    m = SEED_RE.search(lesson_body(num))
    return m.group(1) if m else None


PLACEHOLDERS = {"1001", "2002", "3003", "4004", "5005", "7007"}
check("Lessons : chaque leçon porte une graine",
      all(lesson_seed(n) is not None for n in range(1, 8)),
      f"graines={[lesson_seed(n) for n in range(1, 8)]}")

check("Lessons : les graines PLACEHOLDERS sont parties (6006 conservé pour l6)",
      all(lesson_seed(n) not in PLACEHOLDERS for n in range(1, 8) if n != 6))

l6_body = lesson_body(6)
check("Leçon 6 : plateau Infini PRÉ-RÉSOLU et cameraOnly, 40 x 40, ORIGINAL",
      "preSolved = true" in l6_body and "cameraOnly = true" in l6_body
      and "Skill.ENDLESS_SIZE" in l6_body and "Generator.ORIGINAL" in l6_body)

check("Lessons : dimensions et niveaux des leçons (1 = 3x3, 2-5 = 5x5, 7 = 6x6)",
      "width = 3, height = 3" in lesson_body(1)
      and all("width = 5, height = 5" in lesson_body(n) for n in (2, 3, 4, 5))
      and "width = 6, height = 6" in lesson_body(7))

check("Lessons : niveaux (Novice x3, Maître, Dément, Infini, Néant)",
      all("Skill.NOVICE" in lesson_body(n) for n in (1, 2, 3))
      and "Skill.MASTER" in lesson_body(4) and "Skill.INSANE" in lesson_body(5)
      and "Skill.ENDLESS" in lesson_body(6) and "Skill.VOID" in lesson_body(7))

# ---------------------------------------------------------------------------
# 2. LessonRunner — invariants de la machine d'étapes.
# ---------------------------------------------------------------------------

check("Runner : Continuer n'avance QUE les étapes CONTINUE",
      'if (step?.kind == ExpectKind.CONTINUE) advance()' in lessons)

check("Runner : PAN_X cumule jusqu'à width - 0,5 tuile près",
      "abs(accX) >= lesson.board.width - 0.5f" in lessons
      and "accX += event.dxCells" in lessons)

check("Runner : PAN_Y cumule jusqu'à height - 0,5 tuile près",
      "abs(accY) >= lesson.board.height - 0.5f" in lessons
      and "accY += event.dyCells" in lessons)

check("Runner : seuils de zoom épinglés (1,3 avant / 0,8 arrière)",
      "const val ZOOM_IN_DONE = 1.3f" in lessons
      and "const val ZOOM_OUT_DONE = 0.8f" in lessons
      and "zoomMax >= ZOOM_IN_DONE || zoomMin <= ZOOM_OUT_DONE" in lessons)

check("Runner : les accumulateurs se REMETTENT À ZÉRO à chaque étape",
      count(lessons, "accX = 0f") == 2 and count(lessons, "accY = 0f") == 2
      and lessons.find("private fun advance()") < lessons.find("accX = 0f\n        accY = 0f\n        zoom = 1f"))

check("Runner : VICTORY ne se consomme que par l'événement victoire",
      "event is LessonEvent.Victory" in lessons)

# ---------------------------------------------------------------------------
# 3. LessonViewModel — gestes, indices, solveur, caméra.
# ---------------------------------------------------------------------------

check("VM : cameraOnly ignore taps ET appuis longs (leçon Infini) — joueur (v1) et démo (v2)",
      lvm.count("if (lesson.board.cameraOnly) return") == 4)

check("VM : rotation JOUÉE (byPlayer) et victoire vérifiée",
      "b.rotateCell(index, 1, byPlayer = true)" in lvm
      and "r.onEvent(LessonEvent.Rotated(index))" in lvm
      and "checkVictory()" in lvm)

check("VM : tuile verrouillée, scellée ou libre jamais tournée",
      "b.locked[index] || b.isSealed(index) || b.isEmpty(index)" in lvm)

check("VM : indice SIGNAL = hintTarget + pulsation (génération incrémentée)",
      "val index = b.hintTarget()" in lvm and "++hintPulseId" in lvm)

check("VM : indice FIX = remise à l'aplomb + révélation + scellement (miroir du jeu)",
      "val steps = (4 - b.rotations[index]) % 4" in lvm
      and "if (b.isHidden(index)) b.reveal(index)" in lvm
      and "b.seal(index)" in lvm)

check("VM : Résoudre n'agit QUE sur l'étape SOLVE (leçon 3)",
      "if (r.kind != ExpectKind.SOLVE || b.isSolved()) return" in lvm)

check("VM : solveur un coup par tick, file de solveMoves, arrêt en fin de file",
      "solveQueue = ArrayDeque(b.solveMoves())" in lvm
      and "SOLVE_TICK_MS = 250L" in lvm
      and "while (isActive && solveQueue != null)" in lvm)

check("VM : branche ELSE du solveur consomme explicitement la tête",
      "else -> queue.removeFirst()" in lvm)

check("VM : gel en arrière-plan (setPaused, patron de la démo)",
      "fun setPaused(p: Boolean)" in lvm)

check("VM : hashCode nullables sans double sécurité ?: 0 (correctif v2, lint)",
      "31 * result + bubbleId.hashCode()" in lvm
      and "31 * result + kind.hashCode()" in lvm)

check("VM : hintRequestId publié, bulle et kind courants à l'état",
      "hintRequestId = hintPulseId" in lvm
      and "bubbleId = r.step?.bubbleId" in lvm
      and "kind = r.kind" in lvm
      and "lessonDone = r.finished" in lvm)

# ---------------------------------------------------------------------------
# 4. LessonActivity — bulles, boutons, pulsation, coche.
# ---------------------------------------------------------------------------

used_ids = [b for b, _ in STEP_RE.findall(lessons)]
missing_map = [
    bid for bid in used_ids
    if f'"{bid}" -> getString(R.string.{bid})' not in lact
]
check("Activity : when complet — chaque bulle utilisée a sa branche R.string",
      not missing_map, f"manquantes={missing_map}")

check("Activity : Indice visible pour HINT_SIGNAL et HINT_FIX uniquement",
      "kind == ExpectKind.HINT_SIGNAL || kind == ExpectKind.HINT_FIX" in lact)

check("Activity : Résoudre visible pour SOLVE uniquement, Continuer pour CONTINUE",
      "if (kind == ExpectKind.SOLVE)" in lact
      and "if (kind == ExpectKind.CONTINUE)" in lact)

check("Activity : pulsation (re)armée par la génération, jamais par les ticks",
      "state.hintRequestId != lastHintRequestId" in lact
      and "binding.lessonBoard.showHint(state.hintIndex)" in lact)

check("Activity : préréglage APRÈS update, cadrage une fois par plateau",
      lact.find("binding.lessonBoard.update(board") < lact.find("binding.lessonBoard.applyZoomPreset(1f)")
      and "if (newBoard)" in lact)

check("Activity : leçon Infini cadrée sur le SERVEUR (post-layout, CENTER)",
      "board.serverIndex, BoardView.FollowMode.CENTER" in lact
      and "binding.lessonBoard.post {" in lact)

check("Activity : la coche se persiste UNE fois puis referme",
      "if (state.lessonDone && !lastLessonDone)" in lact
      and "Settings.saveLessonDone(this, ordinal)" in lact
      and "finish()" in lact)

check("Activity : listeners caméra branchés au modèle",
      "viewModel.onCameraScrolled(dx, dy)" in lact
      and "viewModel.onCameraZoomed(ratio)" in lact)

check("Activity : EXTRA_ORDINAL exposé et lu avec borne",
      'const val EXTRA_ORDINAL = "ordinal"' in lact
      and "coerceIn(0, Lessons.ALL.size - 1)" in lact)

# ---------------------------------------------------------------------------
# 5. LessonsListActivity — liste, coches, navigation.
# ---------------------------------------------------------------------------

check("Liste : repeuplée à chaque onStart (rattrape la coche du retour)",
      "override fun onStart()" in llist
      and "fillRows()" in llist[llist.find("override fun onStart()"):
                                llist.find("override fun onStart()") + 300])

check("Liste : coche lue du masque, « Terminée » à côté du titre",
      "Settings.loadLessonsDoneMask(this)" in llist
      and "R.string.lesson_completed" in llist)

check("Liste : apparence en code (textSize, patron records), PAS de ressource attr (correctif v2)",
      "android.R.attr" not in llist
      and "title.textSize = 16f" in llist
      and "done.textSize = 14f" in llist)

check("Liste : titres résolus identifiant vers R.string (titleId est un STRING, correctif v3)",
      "getString(lesson.titleId)" not in llist
      and "titleText(lesson)" in llist
      and all(f'"lesson_{n}_title" -> R.string.lesson_{n}_title' in llist
              for n in range(1, 8)))

check("Liste : clic vers la leçon avec l'extra ordinal",
      "LessonActivity.EXTRA_ORDINAL, index" in llist)

check("Liste : la coche ne verrouille rien — toutes les leçons cliquables",
      "for ((index, lesson) in Lessons.ALL.withIndex())" in llist)

# ---------------------------------------------------------------------------
# 6. Accès : popup Outils SEULEMENT, manifest, persistence.
# ---------------------------------------------------------------------------

check("Accès : entrée dans le popup Outils après la démonstration",
      'android:id="@+id/menu_lessons"' in menu_tools
      and menu_tools.find("@+id/menu_demo") < menu_tools.find("@+id/menu_lessons"))

check("Accès : MainActivity case menu_lessons vers la liste",
      "R.id.menu_lessons ->" in ma
      and "startActivity(Intent(this, LessonsListActivity::class.java))" in ma)

check("Accès : PAS d'entrée écran titre (décision d'eric)",
      "LessonsListActivity" not in read(KOTLIN / "TitleActivity.kt"))

def activity_block(name):
    start = manifest.find(f'android:name="{name}"')
    if start < 0:
        return ""
    end = manifest.find("</activity>", start)
    return manifest[start:end if end > 0 else len(manifest)]


check("Manifest : deux activités, labels menu_lessons, parents corrects",
      'android:exported="false"' in activity_block(".LessonsListActivity")
      and 'android:parentActivityName=".MainActivity"' in activity_block(".LessonsListActivity")
      and 'android:exported="false"' in activity_block(".LessonActivity")
      and 'android:parentActivityName=".LessonsListActivity"' in activity_block(".LessonActivity")
      and count(manifest, 'android:label="@string/menu_lessons"') == 2)

check("Persistence : clé du masque lessonsDone",
      'const val LESSONS_DONE_KEY = "lessonsDone"' in pe)

check("Settings : masque lu, bit posé (patron mask Int)",
      "fun loadLessonsDoneMask(context: Context): Int" in st
      and "fun saveLessonDone(context: Context, ordinal: Int)" in st
      and "Persistence.LESSONS_DONE_KEY" in st)

check("Settings : PAS de test de bit unitaire inutilisé (correctif v2, lint)",
      "fun isLessonDone" not in st)

# ---------------------------------------------------------------------------
# 7. BoardView — listeners caméra, invoqués dans les gestes.
# ---------------------------------------------------------------------------

check("BoardView : listeners caméra déclarés (scroll en cellules, zoom en facteur)",
      "var onCameraScrolled: ((Float, Float) -> Unit)? = null" in bv
      and "var onCameraZoomed: ((Float) -> Unit)? = null" in bv)

check("BoardView : onScroll invoque le listener, garde cellPx > 0",
      "if (cellPx > 0f) {" in bv
      and "onCameraScrolled?.invoke(-dx / cellPx, -dy / cellPx)" in bv)

check("BoardView : onScale invoque le listener avec le FACTEUR de croissance",
      "onCameraZoomed?.invoke(growth)" in bv
      and "val growth = if (cellPx > 0f) newCellPx / cellPx else 1f" in bv)

# ---------------------------------------------------------------------------
# 8. Layouts — écran leçon et liste.
# ---------------------------------------------------------------------------

check("Layout leçon : BoardView + bandeau voile + bulle + trois boutons",
      '@+id/lesson_board' in layout_lesson
      and '@+id/lesson_bubble' in layout_lesson
      and '@+id/lesson_continue_btn' in layout_lesson
      and '@+id/lesson_hint_btn' in layout_lesson
      and '@+id/lesson_solve_btn' in layout_lesson
      and '@color/demo_hint_scrim' in layout_lesson)

check("Layout leçon : boutons cachés par défaut, l'étape pose le sien",
      count(layout_lesson, 'android:visibility="gone"') == 3)

check("Layout liste : toolbar + scroll + conteneur des lignes",
      '@+id/lessons_toolbar' in layout_list
      and '@+id/lessons_scroll' in layout_list
      and '@+id/lesson_rows' in layout_list
      and '@string/menu_lessons' in layout_list)

check("Dimens : paddings des lignes de la liste",
      'name="lesson_row_padding"' in dimens
      and 'name="lesson_row_padding_vertical"' in dimens)

# ---------------------------------------------------------------------------
# 9. Strings EN + FR — parité, échappement, complétude.
# ---------------------------------------------------------------------------

def string_names(st):
    return set(re.findall(r'<string name="(lesson_\w+|menu_lessons)">', st))


en_names = string_names(strings_en)
fr_names = string_names(strings_fr)
check("Strings : les 34 identifiants lesson_*/menu_lessons existent en EN et FR",
      len(en_names) == 34 and en_names == fr_names,
      f"en={len(en_names)} fr={len(fr_names)}")

used_all = set(used_ids) | {f"lesson_{n}_title" for n in range(1, 8)}
used_all |= {"lesson_completed", "lesson_continue", "menu_lessons"}
check("Strings : chaque identifiant UTILISÉ a sa chaîne (EN et FR)",
      used_all <= en_names, f"manquantes={sorted(used_all - en_names)}")

check("Strings : menu_lessons FR = Apprentissage, EN = Learning",
      '<string name="menu_lessons">Apprentissage</string>' in strings_fr
      and '<string name="menu_lessons">Learning</string>' in strings_en)

# Apostrophes : dans les valeurs FR, toute apostrophe est échappée \'.
fr_block = strings_fr[strings_fr.find('name="menu_lessons"'):]
fr_block = fr_block[:fr_block.find("</resources>")]
bare = re.findall(r"[A-Za-zéèà]'[A-Za-zéèà]", fr_block.replace("\\'", ""))
check("Strings FR : apostrophes des chaînées échappées (\\')",
      not bare, f"brutes={bare[:5]}")

check("Strings FR : guillemets français des chaînes absents des valeurs EN",
      "TORE" in strings_fr and "TORUS" in strings_en)

# ---------------------------------------------------------------------------
# 10. LessonTest — invariants épinglés côté JVM.
# ---------------------------------------------------------------------------

check("Test : LessonTest épingle graines, scénarios et machine",
      "lesson 1 seed gives one terminal on 3 by 3 novice" in lesson_test
      and "lesson 4 seed has a solution crossing the torus edge" in lesson_test
      and "lesson 6 board is pre solved endless with camera only" in lesson_test
      and "runner pan x accumulates a full turn before advancing" in lesson_test
      and "runner zoom constants pin the amplitude thresholds" in lesson_test)

test_names = re.findall(r"fun `([^`]*)`\(", lesson_test)
bad_names = [n for n in test_names if "'" in n or ":" in n]
check("Test : 22 tests, aucun deux-points ni apostrophe dans les noms",
      len(test_names) == 22 and not bad_names,
      f"n={len(test_names)} bannis={bad_names}")

# ---------------------------------------------------------------------------
# 11. Démo du doigt (refonte « doigt animé », validée par eric le
#     17 nov. 2026) — gestes AVD, cibles pures, pilotes de caméra,
#     orchestration de l'activité, correctif CI MissingTranslation.
# ---------------------------------------------------------------------------

overlay = read(KOTLIN / "ui/FingerOverlay.kt")
doigt = read(RES / "drawable/doigt.xml")
doigt_glisse = read(RES / "drawable/doigt_glisse.xml")
doigts_pincement = read(RES / "drawable/doigts_pincement.xml")
avd_appui = read(RES / "drawable/lesson_doigt_appui.xml")
avd_prolonge = read(RES / "drawable/lesson_doigt_prolonge.xml")
avd_pincement = read(RES / "drawable/lesson_doigt_pincement.xml")
avd_glissement = read(RES / "drawable/lesson_doigt_glissement.xml")
anims = {
    name: read(RES / f"animator/lesson_{name}.xml")
    for name in [
        "appui_corps", "appui_onde", "prolonge_corps", "prolonge_anneau",
        "pince_gauche", "pince_droite", "glisse_trajet",
    ]
}
gradle_app = read(ROOT / "app/build.gradle.kts")


def kotlin_fun(src, signature):
    """Corps d'une fonction Kotlin : de l'accolade ouvrante qui suit
    la signature à sa fermante appariée (compte les accolades)."""
    i = src.find(signature)
    if i < 0:
        return ""
    start = src.find("{", i)
    if start < 0:
        return ""
    depth = 0
    for j in range(start, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[start:j + 1]
    return ""


def xml_attr(st, name):
    m = re.search(rf'{name}="([^"]+)"', st)
    return m.group(1) if m else ""


def avd_targets(st):
    return re.findall(r'target\s+android:name="(\w+)"', st)


check("Démo : les sept gestes de DemoKind dans l'ordre de la refonte",
      "enum class DemoKind { NONE, TAP_TILE, HOLD_TILE, TAP_BUTTON, "
      "PINCH, DRAG_X, DRAG_Y }" in lessons)

demo_for_body = kotlin_fun(lessons, "fun demoFor(kind: ExpectKind)")
check("Démo : demoFor prononce les ONZE attentes (when exhaustif)",
      len(re.findall(r"ExpectKind\.\w+ ->", demo_for_body)) == 11
      and "ExpectKind.PAN_Y -> DemoKind.DRAG_Y" in demo_for_body
      and "ExpectKind.ZOOM -> DemoKind.PINCH" in demo_for_body)

demo_target_body = kotlin_fun(lessons, "fun demoTarget(board: Board, kind: ExpectKind)")
check("Démo : la rotation désigne la première tuile à remettre, VISIBLE de préférence",
      "board.solveMoves()" in demo_target_body
      and "firstOrNull { !board.isHidden(it) }" in demo_target_body)
check("Démo : le verrou exige l'aplomb, jamais un piège (ni serveur ni terminal)",
      "(4 - board.rotations[i]) % 4 == 0" in demo_target_body
      and "i == board.serverIndex || board.isTerminal(i)" in demo_target_body)

check("VM : la démo s'arme à CHAQUE changement d'étape (la génération avance aussi vers NONE)",
      "if (r.index != lastStepIndex)" in lvm and "++demoArmId" in lvm)

demo_bodies = [
    kotlin_fun(lvm, "fun demoTapAt(index: Int)"),
    kotlin_fun(lvm, "fun demoLongPressAt(index: Int)"),
    kotlin_fun(lvm, "fun demoHintButton()"),
    kotlin_fun(lvm, "fun demoAct(kind: DemoKind, index: Int)"),
]
check("VM : la démo ne nourrit JAMAIS la machine (aucun onEvent dans les quatre corps)",
      all(b for b in demo_bodies)
      and all("onEvent" not in b for b in demo_bodies),
      f"corps vides={sum(1 for b in demo_bodies if not b)}")
check("VM : demoAct route le contact vers l'action réelle du geste",
      "DemoKind.TAP_TILE -> demoTapAt(index)" in demo_bodies[3]
      and "DemoKind.HOLD_TILE -> demoLongPressAt(index)" in demo_bodies[3]
      and "DemoKind.TAP_BUTTON -> demoHintButton()" in demo_bodies[3])
check("VM : la démo d'indice se contente de la pulsation (le correctif reste au joueur)",
      "if (r.kind != ExpectKind.HINT_SIGNAL && r.kind != ExpectKind.HINT_FIX) return"
      in demo_bodies[2]
      and "b.hintTarget()" in demo_bodies[2]
      and "seal" not in demo_bodies[2])
check("VM : l'état porte la démo (geste, cible, génération) — equals et hashCode complets",
      "demoKind = armedDemoKind" in lvm
      and "demoIndex = armedDemoIndex" in lvm
      and "demoRequestId = demoArmId" in lvm
      and "demoRequestId == other.demoRequestId" in lvm
      and "31 * result + demoRequestId" in lvm)

check("BoardView : les trois pilotes de démo exposés (cellCenter, demoPanBy, demoZoomBy)",
      "fun cellCenter(index: Int): FloatArray?" in bv
      and "fun demoPanBy(dxPx: Float, dyPx: Float)" in bv
      and "fun demoZoomBy(ratio: Float)" in bv)
demo_pan_body = kotlin_fun(bv, "fun demoPanBy(dxPx: Float, dyPx: Float)")
demo_zoom_body = kotlin_fun(bv, "fun demoZoomBy(ratio: Float)")
check("BoardView : les pilotes de démo n'invoquent JAMAIS les listeners du joueur",
      "onCameraScrolled" not in demo_pan_body and "onCameraZoomed" not in demo_pan_body
      and "onCameraScrolled" not in demo_zoom_body
      and "onCameraZoomed" not in demo_zoom_body)
check("BoardView : le zoom de démo s'ancre au CENTRE de la vue (miroir de onScale)",
      "val focusX = width / 2f" in demo_zoom_body
      and "panX = focusX - (focusX - panX) * growth" in demo_zoom_body)

check("Overlay : FingerOverlay pose l'AVD au bout de l'index (ancrage, alpha, rotation)",
      "class FingerOverlay" in overlay
      and "canvas.withTranslation(fx - ax, fy - ay) {" in overlay
      and "canvas.rotate(rotDeg, ax, ay)" in overlay
      and "ANCHOR_V = 11f / 48f" in overlay)
check("Overlay : le doigt n'intercepte RIEN (la leçon reste jouable sous la main)",
      "onTouchEvent" not in overlay and "isClickable" not in overlay)
check("Overlay : le voyage d'approche freine à l'arrivée, un ValueAnimator maître linéaire",
      "LinearInterpolator()" in overlay
      and "DecelerateInterpolator()" in overlay
      and "anim.animatedFraction * totalMs" in overlay)

check("AVD : les quatre genres de geste existent (appui, prolongé, pincement, glissement)",
      'android:drawable="@drawable/doigt"' in avd_appui
      and 'android:drawable="@drawable/doigt"' in avd_prolonge
      and 'android:drawable="@drawable/doigts_pincement"' in avd_pincement
      and 'android:drawable="@drawable/doigt_glisse"' in avd_glissement)
check("AVD : chaque cible nommée existe dans son drawable (tracés nommés)",
      set(avd_targets(avd_appui)) == {"corps", "onde"}
      and 'android:name="corps"' in doigt
      and 'android:name="onde"' in doigt
      # trimPathEnd anime un TRACÉ : la cible est le path, jamais le groupe.
      and set(avd_targets(avd_prolonge)) == {"corps", "arc_anneau"}
      and 'android:name="arc_anneau"' in doigt
      and set(avd_targets(avd_pincement)) == {"doigt_gauche", "doigt_droite"}
      and 'android:name="doigt_gauche"' in doigts_pincement
      and 'android:name="doigt_droite"' in doigts_pincement
      and set(avd_targets(avd_glissement)) == {"chevron_trajet"}
      and 'android:name="chevron_trajet"' in doigt_glisse)
check("AVD : les durées XML sont le MIROIR des constantes Kotlin (épinglées ensemble)",
      xml_attr(anims["appui_corps"], "android:duration") == "350"
      and "PRESS_MS = 350f" in overlay
      and xml_attr(anims["prolonge_anneau"], "android:duration") == "1300"
      and "RING_MS = 1300f" in overlay
      and xml_attr(anims["pince_gauche"], "android:duration") == "550"
      and "PINCH_MS = 550f" in overlay
      and xml_attr(anims["glisse_trajet"], "android:duration") == "550"
      and "SWEEP_MS = 550f" in overlay)
check("AVD : l'appui descend puis se relève, l'onde s'éteint sur le cycle complet",
      xml_attr(anims["appui_corps"], "android:propertyName") == "translateY"
      and xml_attr(anims["appui_corps"], "android:repeatMode") == "reverse"
      and xml_attr(anims["appui_onde"], "android:propertyName") == "alpha"
      and xml_attr(anims["appui_corps"], "android:duration") == "350")
check("AVD : l'appui prolongé RESTE enfoncé, l'anneau se remplit (accéléré)",
      xml_attr(anims["prolonge_corps"], "android:repeatCount") == "0"
      and xml_attr(anims["prolonge_anneau"], "android:propertyName") == "trimPathEnd"
      and xml_attr(anims["prolonge_anneau"], "android:interpolator")
      == "@android:interpolator/accelerate_quad")
check("Pincement : écart épinglé des DEUX côtés (36 vers 48 dp, deux mains dessinées)",
      xml_attr(anims["pince_gauche"], "android:valueTo") == "-6"
      and xml_attr(anims["pince_droite"], "android:valueTo") == "6"
      and "PINCH_CLOSED_DP = 36f" in overlay
      and "PINCH_OPEN_DP = 48f" in overlay
      and "lesson_doigt_pincement" in overlay)

check("Layout : la surimpression du doigt au-dessus de tout (dernier enfant, pleine vue)",
      "net.chardons.netscramble2.ui.FingerOverlay" in layout_lesson
      and layout_lesson.find("lesson_finger") > layout_lesson.find("lesson_solve_btn")
      and 'android:id="@+id/lesson_finger"' in layout_lesson)

check("Activity : la démo se pose UNE fois par étape, annulée au changement et au départ",
      "state.demoRequestId != lastDemoRequestId" in lact
      and lact.count("lessonFinger.cancel()") == 2
      and "armDemoWhenReady" in lact)
check("Activity : le contact exécute l'action RÉELLE du modèle, la caméra suit les images",
      "viewModel.demoAct(state.demoKind, state.demoIndex)" in lact
      and "demoPanBy(dx, dy)" in lact
      and "demoZoomBy(ratio)" in lact)
check("Activity : la cible se résout par genre (tuile, bouton de l'étape, centre de la vue)",
      "binding.lessonBoard.cellCenter(state.demoIndex)" in lact
      and "buttonCenter(binding.lessonHintBtn)" in lact
      and "buttonCenter(binding.lessonSolveBtn)" in lact
      and "root.height / 2.2f" in lact)
check("Activity : le doigt attend la MESURE (cellCenter null avant le premier layout)",
      "armDemoWhenReady(state)" in lact and "return@post" in lact)

check("CI : MissingTranslation en AVERTISSEMENT (option A d'eric, langues partielles admises)",
      'warning += "MissingTranslation"' in gradle_app)

# ---------------------------------------------------------------------------

print()
if failures:
    print(f"ECHEC : {len(failures)} contrôle(s) sur {checks} — {failures}")
    sys.exit(1)
print(f"OK : {checks} contrôles")
