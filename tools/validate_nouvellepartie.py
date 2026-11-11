#!/usr/bin/env python3
"""Validation du chantier « confirmation avant d'écraser une partie ».

Roadmap du 10 nov. 2026 : eric a déjà perdu un plateau en cours à cause
d'un clic par mégarde sur « Nouvelle partie ». Décisions actées :

- la confirmation (dialog Material 3) n'apparaît QUE si une partie est
  en jeu : plateau vierge (aucun coup joué) ou déjà gagné, il n'y a
  rien à perdre, l'action est directe ;
- le changement de niveau (sous-menu Novice -> Expert...) écrase aussi
  la partie en cours : même confirmation, avec un libellé adapté
  (« Changer de niveau ») ;
- le bouton « Nouvelle partie » du dialogue de victoire reste DIRECT
  (la partie est finie, rien à protéger) ;
- la coche du sous-menu de niveau ne bouge qu'à la confirmation ;
- le TODO de phase bonus ne mentionne plus l'undo (retiré de la
  roadmap sur décision d'eric).

Ce script PARSE les sources (aucun littéral retapé à la main) et
SIMULE la garde de confirmation par des scénarios miroirs.
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


main_src = read(f"{KT}/MainActivity.kt")
en_src = read("app/src/main/res/values/strings.xml")
fr_src = read("app/src/main/res/values-fr/strings.xml")

# ---------------------------------------------------------------------------
# 1. Vérification statique : MainActivity.kt.
# ---------------------------------------------------------------------------

m = re.search(
    r"private fun confirmIfInProgress\((.*?)\) \{(.*?)\n    \}",
    main_src,
    re.S,
)
check("MainActivity : confirmIfInProgress est défini",
      m is not None)
check("MainActivity : la garde laisse passer plateau vierge OU gagné",
      "state.solved || state.moves == 0" in main_src
      and "action()" in main_src)
check("MainActivity : le dialog montre l'avancement (coups + temps)",
      "R.string.confirm_progress_message" in main_src
      and "state.moves" in main_src
      and "formatTime(state.elapsedMs)" in main_src)
check("MainActivity : le dialog est un MaterialAlertDialog (parité UI)",
      "MaterialAlertDialogBuilder(this)" in main_src)
check("MainActivity : Annuler referme sans rien toucher",
      "setNegativeButton(android.R.string.cancel, null)" in main_src)

# « Nouvelle partie » passe par la confirmation, avec le libellé du
# bouton de victoire réutilisé (« Nouvelle partie »).
menu_new = re.search(
    r"item\.itemId == R\.id\.menu_new -> \{(.*?)\n            \}",
    main_src,
    re.S,
)
check("MainActivity : « Nouvelle partie » passe par la confirmation",
      menu_new is not None
      and "confirmIfInProgress(" in menu_new.group(1))
check("MainActivity : « Nouvelle partie » confirme avec le libellé « Nouvelle partie »",
      menu_new is not None
      and "R.string.confirm_new_title" in menu_new.group(1)
      and "R.string.win_new" in menu_new.group(1))

# Changement de niveau : même confirmation, libellé adapté, coche à la
# confirmation seulement.
skill_branch = re.search(
    r"skill != null -> \{(.*?)\n            \}",
    main_src,
    re.S,
)
check("MainActivity : le changement de niveau passe par la confirmation",
      skill_branch is not None
      and "confirmIfInProgress(" in skill_branch.group(1))
check("MainActivity : libellé « Changer de niveau » pour le changement de niveau",
      skill_branch is not None
      and "R.string.confirm_change_title" in skill_branch.group(1)
      and "R.string.confirm_change_ok" in skill_branch.group(1))
check("MainActivity : la coche du niveau ne bouge qu'à la confirmation",
      skill_branch is not None
      and "item.isChecked = true" in skill_branch.group(1)
      and skill_branch.group(1).index("item.isChecked = true")
      > skill_branch.group(1).index("confirmIfInProgress("))
check("MainActivity : l'aide Maître/Dément suit toujours le changement confirmé",
      skill_branch is not None
      and "showSkillHelpIfNeeded(skill, previous)" in skill_branch.group(1))

# Le dialogue de victoire reste DIRECT : rien à protéger une fois gagné.
win = re.search(r"private fun showWinDialog\(.*?\n    \}", main_src, re.S)
check("MainActivity : le bouton « Nouvelle partie » du dialogue de victoire reste direct",
      win is not None
      and "confirmIfInProgress" not in win.group(0)
      and "viewModel.newGame(current, width, height)" in win.group(0))

check("MainActivity : plus aucune mention d'undo (retiré de la roadmap)",
      "undo" not in main_src)

# ---------------------------------------------------------------------------
# 2. Vérification statique : chaînes EN + FR.
# ---------------------------------------------------------------------------

NEW_STRINGS = ["confirm_new_title", "confirm_change_title",
               "confirm_change_ok", "confirm_progress_message"]


def strings_of(src):
    return dict(re.findall(r'<string name="([^"]+)">(.*?)</string>',
                            src, re.S))


en, fr = strings_of(en_src), strings_of(fr_src)
check("Chaînes : les 4 nouvelles existent en EN",
      all(s in en for s in NEW_STRINGS))
check("Chaînes : les 4 nouvelles existent en FR",
      all(s in fr for s in NEW_STRINGS))
check("Chaînes : les versions FR sont bien traduites (différentes de l'EN)",
      all(fr.get(s, "").strip() != en.get(s, "").strip() for s in NEW_STRINGS))
check("Chaînes : le message FR porte coups et temps (%1$d, %2$s)",
      "%1$d" in fr.get("confirm_progress_message", "")
      and "%2$s" in fr.get("confirm_progress_message", ""))
check("Chaînes : le message EN porte coups et temps (%1$d, %2$s)",
      "%1$d" in en.get("confirm_progress_message", "")
      and "%2$s" in en.get("confirm_progress_message", ""))


def body(s):
    return re.sub(r"\s+", " ", s).strip()


check("Chaînes FR : titres attendus au mot près",
      body(fr.get("confirm_new_title", "")) == "Nouvelle partie ?"
      and body(fr.get("confirm_change_title", "")) == "Changer de niveau ?"
      and body(fr.get("confirm_change_ok", "")) == "Changer de niveau")
check("Chaînes : aucune apostrophe non échappée dans les nouvelles chaînes FR",
      all(re.search(r"(?<!\\)'", fr.get(s, "")) is None for s in NEW_STRINGS))

# ---------------------------------------------------------------------------
# 3. Miroir : la garde extraite de la source, évaluée sur des scénarios.
# ---------------------------------------------------------------------------

# La garde est extraite TELLE QUELLE du corps de confirmIfInProgress
# dans MainActivity.kt : le miroir exécute la vraie expression, pas une
# copie retapée.
body = re.search(
    r"private fun confirmIfInProgress\(.*?\n    \}", main_src, re.S
)
check("Miroir : le corps de confirmIfInProgress est extractible",
      body is not None)

if body:
    guard = re.search(r"if \((state\.[a-zA-Z]+ [^\n]*?)\) \{",
                      body.group(0))
    check("Miroir : la garde est extractible de la source", guard is not None)

    if guard:
        cond = guard.group(1)
        check("Miroir : la garde teste solved puis moves",
              cond == "state.solved || state.moves == 0")

        def direct(solved, moves):
            # True = action directe (pas de dialog), False = confirmation.
            # Les opérateurs Kotlin deviennent leurs équivalents Python.
            expr = (cond.replace("state.", "s_")
                        .replace("||", " or ")
                        .replace("&&", " and "))
            env = {"s_solved": solved, "s_moves": moves}
            return eval(expr, {}, env)  # source locale de confiance

        scenarios = [
            ("plateau vierge (0 coup, non résolu)", False, 0, True),
            ("1 seul coup joué = déjà une partie en jeu", False, 1, False),
            ("partie bien entamée (37 coups)", False, 37, False),
            ("victoire : rien à perdre", True, 37, True),
            ("victoire sans aucun coup (solveur immédiat)", True, 0, True),
        ]
        for label, solved, moves, expected in scenarios:
            check(f"Miroir : {label} -> "
                  f"{'direct' if expected else 'confirmation'}",
                  direct(solved, moves) == expected)

# ---------------------------------------------------------------------------
# 4. Structure Kotlin/XML minimale (pas de JVM ici : la CI d'eric compile).
# ---------------------------------------------------------------------------


def balanced(src, open_c, close_c):
    depth = 0
    for line in src.splitlines():
        code = re.sub(r"//.*", "", line)          # commentaires de ligne
        if '"""' in line:                          # KDoc à la marge
            code = code.split('"""', 1)[0]
        depth += code.count(open_c) - code.count(close_c)
        if depth < 0:
            return False
    return depth == 0


check("Structure : accolades équilibrées dans MainActivity.kt",
      balanced(main_src, "{", "}"))
check("Structure : parenthèses équilibrées dans MainActivity.kt",
      balanced(main_src, "(", ")"))
check("Structure : pas de « /* » dans un commentaire KDoc",
      re.search(r'"""\s*/\*', main_src) is None)
check("Structure : <resources> fermé dans les deux strings.xml",
      en_src.count("<resources>") == 1 and en_src.count("</resources>") == 1
      and fr_src.count("<resources>") == 1 and fr_src.count("</resources>") == 1)

# ---------------------------------------------------------------------------
# Bilan.
# ---------------------------------------------------------------------------

print()
if failures:
    print(f"{len(failures)} échec(s) sur {checks} vérifications")
    sys.exit(1)
print(f"TOUT PASSE : {checks} vérifications")
