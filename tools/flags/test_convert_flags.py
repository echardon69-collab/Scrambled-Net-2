#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Banc d'essai du convertisseur de drapeaux du sélecteur de langue.

  1. Vérifie sur un SVG synthétique chaque règle ajoutée par
     tools/flags/convert_flags.py par rapport au pipeline tuiles :
     classes CSS (et priorité CSS > attribut de présentation),
     fill="none" (contour seul : PAS de fillColor), contours
     (strokeColor/strokeWidth/strokeLineCap), héritage par <g>
     (fill ET fill-rule), fill-rule evenodd, <line> -> M...L...,
     tailles dp rectangulaires (largeur x hauteur) ;
  2. vérifie les refus explicites : clip-path, transform, tracé sans
     fill ni stroke (le VectorDrawable ne sait pas les représenter) ;
  3. ORACLE : convertit les 4 VRAIS drapeaux (svg/flags/flag_fr,
     flag_en, flag_co, flag_gsw_fr) et compare la sortie AU BYTE
     PRES avec les drawables du dépôt ;
  4. épingle la géométrie de l'Union Jack (flag_en.svg réécrit sans
     clipPath) : chaque demi-bande de la croix de saint Patrick est
     à distance perpendiculaire EXACTE de 20 de sa diagonale,
     parallèle à elle-ci, largeur 40, côtés opposés deux à deux
     (contrechangement), chaque bande dans son quadrant ;
  5. vérifie le mode commande (python3 convert_flags.py svg_dir out).

Autonome et portable : reconstruit ses entrées dans un répertoire de
travail temporaire (à côté du script), aucune dépendance. Usage :

    python3 tools/flags/test_convert_flags.py
"""

import math
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'flags'))
import convert_flags as cf                              # noqa: E402

DRAW = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')
WORK = os.path.join(ROOT, 'tools', 'flags', 'banc-essai')
SRC = os.path.join(ROOT, 'svg', 'flags')

SVG_ESSAI = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
  <defs>
    <style>
      .t-rouge{fill:#ff0000}
      .t-trait{fill:none;stroke:#00ff00;stroke-width:3;stroke-linecap:round}
    </style>
  </defs>
  <title>essai</title>
  <rect class="t-rouge" x="0" y="0" width="100" height="50"/>
  <line class="t-trait" x1="10" y1="25" x2="90" y2="25"/>
  <g fill="#0000ff" fill-rule="evenodd">
    <rect x="10" y="10" width="20" height="10"/>
  </g>
  <path d="M0,0 L100,50" fill="#123456"/>
  <rect x="40" y="10" width="10" height="10" fill="#111111"
        class="t-rouge"/>
</svg>
"""

SVG_CLIP = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
  <g clip-path="url(#c)"><rect x="0" y="0" width="10" height="10"/></g>
</svg>
"""

SVG_TRANSFORM = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
  <rect x="0" y="0" width="10" height="10" transform="scale(2)"/>
</svg>
"""

SVG_INVISIBLE = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 50">
  <path d="M0,0 L10,10" fill="none"/>
</svg>
"""


def lit(out):
    with open(out, encoding='utf-8') as fh:
        return fh.read()


def check(ok, label, condition, detail=''):
    print('  %-52s %s' % (label, 'OK' if condition
                         else 'ÉCHEC %s' % detail))
    return ok and bool(condition)


def main():
    if os.path.isdir(WORK):
        shutil.rmtree(WORK)
    os.makedirs(os.path.join(WORK, 'essais'))
    os.makedirs(os.path.join(WORK, 'sortie'))
    ok = True

    print('== 1. SVG synthétique : CSS, fill none, contours, <g> ==')
    essai = os.path.join(WORK, 'essais', 'flag_essai.svg')
    with open(essai, 'w', encoding='utf-8') as fh:
        fh.write(SVG_ESSAI)
    cf.convert_flag(essai, os.path.join(WORK, 'sortie'))
    xml = lit(os.path.join(WORK, 'sortie', 'flag_essai.xml'))
    datas = re.findall(r'android:pathData="([^"]*)"', xml)
    blocs = re.findall(r'<path\n(.*?)\n      />', xml, re.S)

    ok = check(ok, '5 tracés convertis', len(datas) == 5,
               '(lu %d)' % len(datas))
    ok = check(ok, 'rect classe CSS -> fill #FF0000',
               'android:fillColor="#FFFF0000"' in blocs[0],
               '(bloc0=%r)' % blocs[0])
    ok = check(ok, 'line -> M...L... + stroke, SANS fillColor',
               (datas[1] == 'M10,25 L90,25'
                and 'android:strokeColor="#FF00FF00"' in blocs[1]
                and 'android:strokeWidth="3"' in blocs[1]
                and 'android:strokeLineCap="round"' in blocs[1]
                and 'android:fillColor' not in blocs[1]),
               '(bloc1=%r)' % blocs[1])
    ok = check(ok, 'héritage <g> : fill ET fill-rule evenodd',
               ('android:fillColor="#FF0000FF"' in blocs[2]
                and 'android:fillType="evenOdd"' in blocs[2]),
               '(bloc2=%r)' % blocs[2])
    ok = check(ok, 'priorité CSS > attribut de présentation',
               'android:fillColor="#FFFF0000"' in blocs[4]
               and '#FF111111' not in xml,
               '(bloc4=%r)' % blocs[4])
    ok = check(ok, 'dp rectangulaire 100x50 -> 30dp x 15dp',
               ('android:width="30dp"' in xml
                and 'android:height="15dp"' in xml), '')

    print('== 2. Refus explicites (VectorDrawable) ==')
    for label, svg, attendu in (
            ('clip-path', SVG_CLIP, 'clip-path'),
            ('transform', SVG_TRANSFORM, 'transform'),
            ('sans fill ni stroke', SVG_INVISIBLE,
             'sans fill ni stroke')):
        chemin = os.path.join(WORK, 'essais', 'flag_%s.svg'
                             % label.replace(' ', '_'))
        with open(chemin, 'w', encoding='utf-8') as fh:
            fh.write(svg)
        try:
            cf.convert_flag(chemin, os.path.join(WORK, 'sortie'))
            ok = check(ok, 'refus %s' % label, False, '(accepté !)')
        except ValueError as err:
            ok = check(ok, 'refus %s' % label, attendu in str(err),
                       '(lu %r)' % err)

    print('== 3. Oracle : les 4 drapeaux du dépôt, byte à byte ==')
    oracle = os.path.join(WORK, 'oracle')
    os.makedirs(oracle)
    for nom in ('flag_co.xml', 'flag_en.xml', 'flag_fr.xml',
                'flag_gsw_fr.xml'):
        cf.convert_flag(os.path.join(SRC, nom[:-4] + '.svg'), oracle)
        pareil = (lit(os.path.join(oracle, nom))
                  == lit(os.path.join(DRAW, nom)))
        ok = check(ok, nom, pareil, '(diffère du dépôt)')

    print('== 4. Union Jack : géométrie épinglée (sans clipPath) ==')
    en = lit(os.path.join(DRAW, 'flag_en.xml'))
    ok = check(ok, '4 demi-bandes rouges stroke 40 (+ croix rouge)',
               (en.count('android:strokeColor="#FFC8102E"') == 5
                and en.count('android:strokeWidth="40"') == 4), '')
    # Segments de la croix de saint Patrick (ordre du fichier :
    # haut-gauche, bas-droite, haut-droite, bas-gauche).
    datas_en = re.findall(r'android:pathData="([^"]*)"', en)
    bandes = datas_en[2:6]

    # Diagonales du drapeau 1200x600 : A passe par (0,0) et
    # (1200,600) ; B par (0,600) et (1200,0). Distance signée à A :
    # (y - x/2) / sqrt(1,25) ; à B : (y - 600 + x/2) / sqrt(1,25).
    def dist_a(x, y):
        return (y - x / 2.0) / math.sqrt(1.25)

    def dist_b(x, y):
        return (y - 600.0 + x / 2.0) / math.sqrt(1.25)

    familles = ('A', 'A', 'B', 'B')
    attendus_dist = ('+', '-', '+', '-')
    # Milieux attendus (x < 600 ?, y < 300 ?) : haut-gauche,
    # bas-droite, haut-droite, bas-gauche.
    milieux = ((True, True), (False, False), (False, True),
               (True, False))
    for i, (d, fam, signe, (mx, my)) in enumerate(
            zip(bandes, familles, attendus_dist, milieux)):
        nums = [float(v) for v in re.findall(r'[-+]?[\d.]+', d)]
        (x1, y1), (x2, y2) = nums[:2], nums[2:4]
        dist = dist_a(x1, y1) if fam == 'A' else dist_b(x1, y1)
        # Parallélisme : la distance des DEUX extrémités est la même.
        dist2 = dist_a(x2, y2) if fam == 'A' else dist_b(x2, y2)
        bon_cote = ((signe == '+' and dist > 0)
                    or (signe == '-' and dist < 0))
        mx_ok = (((x1 + x2) / 2 < 600) == mx
                 and ((y1 + y2) / 2 < 300) == my)
        ok = check(ok,
                   'bande %d : distance 20, parallèle, côté %s, '
                   'quadrant' % (i + 1, signe),
                   abs(dist - dist2) < 1e-6
                   and abs(abs(dist) - 20.0) < 1e-6
                   and bon_cote and mx_ok,
                   '(dist=%r, dist2=%r)' % (dist, dist2))

    print('== 5. Mode commande (svg_dir out_dir) ==')
    cmd_in = os.path.join(WORK, 'cmd-svg')
    os.makedirs(cmd_in)
    shutil.copy(essai, os.path.join(cmd_in, 'flag_essai.svg'))
    proc = subprocess.run(
        [sys.executable, os.path.join(ROOT, 'tools', 'flags',
                                      'convert_flags.py'),
         cmd_in, os.path.join(WORK, 'cmd')],
        capture_output=True, text=True)
    ok = check(ok, 'exit 0, sortie créée, drawable écrit',
               proc.returncode == 0
               and os.path.exists(os.path.join(WORK, 'cmd',
                                               'flag_essai.xml')),
               '(exit=%d)' % proc.returncode)

    print('---')
    if ok:
        print('BANC D\u2019ESSAI : TOUT PASSE')
    else:
        print('BANC D\u2019ESSAI : ÉCHECS (voir ci-dessus)')
    shutil.rmtree(WORK)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
