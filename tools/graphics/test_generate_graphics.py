#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Banc d'essai du générateur d'éléments graphiques (SVG à calques).

Construit un SVG de test à calques à partir des vraies sources du jeu
(svg/tiles, svg/tile_background*.svg, et les paths des VectorDrawables
computer/server), un fichier de configuration de test, puis :

  1. génère via tools/graphics/generate_graphics.py ;
  2. compare AU BYTE PRES la sortie avec :
     - les drawables existants du dépôt (câbles : déjà couverts par le
       pipeline tools/tiles — oracle absolu), et
     - la sortie de référence de convert_tiles (parse_svg + write_vd)
       sur un SVG plat équivalent, pour chaque élément ;
  3. vérifie l'aplatissement des transformations (translate, scale,
     rotation) sur des calques dédiés ;
  4. vérifie la conversion des formes Inkscape (rect arrondi -> arcs,
     circle, polygon) ;
  5. vérifie les éléments NOMMÉS addressables un à un (rect par id,
     polygon par data-name), l'attribut opacity (alpha),
     les contours stroke (android:stroke*), la variante grise du
     contour, et l'ignorance des <image> avec note ;
  6. vérifie les messages d'erreur (calque inconnu, sous-élément
     inconnu -> enfants du parent listés, syntaxe de configuration,
     --check).

Autonome et portable : reconstruit ses entrées dans un répertoire de
travail temporaire (a côté du script), aucune dépendance. Usage :

    python3 tools/graphics/test_generate_graphics.py
"""

import os
import re
import shutil
import subprocess
import sys

import xml.dom.minidom as minidom

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'tiles'))
import convert_tiles as ct  # noqa: E402

SRC = os.path.join(ROOT, 'svg')
DRAW = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')
WORK = os.path.join(ROOT, 'tools', 'graphics', 'banc-essai')
TOOL = os.path.join(ROOT, 'tools', 'graphics', 'generate_graphics.py')

VD_NSS = 'http://schemas.android.com/apk/res/android'


def serialize_gradient(g, gid):
    if g.kind == 'linear':
        tag = 'linearGradient'
        geo = 'x1="%s" y1="%s" x2="%s" y2="%s"' % (
            g.attrs['startX'], g.attrs['startY'],
            g.attrs['endX'], g.attrs['endY'])
    else:
        tag = 'radialGradient'
        geo = 'cx="%s" cy="%s" r="%s"' % (
            g.attrs['centerX'], g.attrs['centerY'], g.attrs['gradientRadius'])
    stops = []
    for off, col, alpha in g.stops:
        op = (' stop-opacity="%s"' % repr(alpha / 255.0)
              if alpha != 255 else '')
        stops.append('      <stop offset="%s" stop-color="%s"%s/>'
                     % (off, col, op))
    return ('    <%s id="%s" gradientUnits="userSpaceOnUse" %s>\n%s\n'
            '    </%s>' % (tag, gid, geo, '\n'.join(stops), tag))


def layer_paths_from_svg(svg_file, grad_prefix):
    """(viewBox, defs, paths) sérialisés depuis un SVG plat du dépôt."""
    vp, elements = ct.parse_svg(svg_file)
    defs = []
    paths = []
    grad_ids = {}
    for _, d, f, eo in elements:
        if f[0] == 'grad':
            key = id(f[1])
            if key not in grad_ids:
                grad_ids[key] = '%s-gt%d' % (grad_prefix,
                                             len(grad_ids))
                defs.append(serialize_gradient(f[1], grad_ids[key]))
            fill = 'url(#%s)' % grad_ids[key]
        else:
            # ct rend la couleur sans '#' pour les fills 8 chiffres.
            fill = '#%02X%s' % (f[2], f[1].lstrip('#'))
        rule = ' fill-rule="evenodd"' if eo else ''
        paths.append('      <path d="%s" fill="%s"%s/>' % (d, fill, rule))
    return vp, defs, paths


def layer_paths_from_vd(vd_file, defs, gid_prefix):
    """Paths sérialisés depuis un VectorDrawable existant.

    Gère les fills plats (#AARRGGBB acceptés tels quels par
    parse_svg) et les dégradés inline (<aapt:attr><gradient>),
    resérialisés en dégradés SVG userSpaceOnUse avec ids uniques.
    """
    doc = minidom.parse(vd_file)
    paths = []
    n_grad = 0
    for p in doc.getElementsByTagName('path'):
        d = p.getAttribute('android:pathData')
        rule = (' fill-rule="evenodd"'
                if p.getAttribute('android:fillType') == 'evenOdd' else '')
        grad = None
        for attr in p.childNodes:
            if (attr.nodeType == attr.ELEMENT_NODE
                    and attr.tagName == 'aapt:attr'
                    and attr.getAttribute('name')
                    == 'android:fillColor'):
                gnodes = attr.getElementsByTagName('gradient')
                if gnodes:
                    grad = gnodes[0]
        if grad is None:
            color = p.getAttribute('android:fillColor')
            if not color:
                raise ValueError('%s : path sans fill' % vd_file)
            paths.append('      <path d="%s" fill="%s"%s/>'
                         % (d, color, rule))
            continue
        n_grad += 1
        gid = '%s-g%d' % (gid_prefix, n_grad)
        if grad.getAttribute('android:type') == 'linear':
            geo = 'x1="%s" y1="%s" x2="%s" y2="%s"' % (
                grad.getAttribute('android:startX'),
                grad.getAttribute('android:startY'),
                grad.getAttribute('android:endX'),
                grad.getAttribute('android:endY'))
            tag = 'linearGradient'
        else:
            geo = 'cx="%s" cy="%s" r="%s"' % (
                grad.getAttribute('android:centerX'),
                grad.getAttribute('android:centerY'),
                grad.getAttribute('android:gradientRadius'))
            tag = 'radialGradient'
        stops = []
        for item in grad.getElementsByTagName('item'):
            col = item.getAttribute('android:color')
            alpha = int(col[1:3], 16)
            stops.append('<stop offset="%s" stop-color="#%s"%s/>'
                         % (item.getAttribute('android:offset'),
                            col[3:],
                            '' if alpha == 255 else
                            ' stop-opacity="%s"'
                            % repr(alpha / 255.0)))
        defs.append('      <%s id="%s" gradientUnits="userSpaceOnUse" '
                    '%s>%s</%s>' % (tag, gid, geo, ''.join(stops), tag))
        paths.append('      <path d="%s" fill="url(#%s)"%s/>'
                     % (d, gid, rule))
    return paths


def write_layer(fh, name, defs, paths, extra_attrs='', gid=None):
    fh.write('  <g inkscape:groupmode="layer" id="%s" '
             'inkscape:label="%s"%s>\n'
             % (gid or ('lyr-%s' % name), name, extra_attrs))
    for d in defs:
        fh.write(d + '\n')
    for p in paths:
        fh.write(p + '\n')
    fh.write('  </g>\n')


def main():
    if os.path.exists(WORK):
        shutil.rmtree(WORK)
    os.makedirs(WORK, exist_ok=True)
    ok = True

    # ---- 1. SVG de test à calques (grande viewBox tuile 226.77) ----
    svg_path = os.path.join(WORK, 'test-sources.svg')
    with open(svg_path, 'w', encoding='utf-8') as fh:
        fh.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        fh.write('<svg xmlns="http://www.w3.org/2000/svg" '
                 'xmlns:xlink="http://www.w3.org/1999/xlink" '
                 'xmlns:inkscape="http://www.inkscape.org/namespaces'
                 '/inkscape" viewBox="0 0 226.77 226.77">\n')
        fh.write('  <title>Sources unifiées de test</title>\n')
        cables = {
            'cable-droit': 'cable_straight.svg',
            'cable-te': 'cable_ttee.svg',
            'cable-croix': 'cable_cross.svg',
            'cable-bout': 'cable_stub.svg',
        }
        for name, f in cables.items():
            _, defs, paths = layer_paths_from_svg(
                os.path.join(SRC, 'tiles', f), name)
            write_layer(fh, name, defs, paths)
        # Regroupement « coudes » (cas réel demandé) : calque racine
        # contenant de vrais sous-calques Inkscape. Le coude DROIT
        # seul est addressable par « coudes/Coude droit » ; le calque
        # père « coudes » prend tout le sous-arbre.
        fh.write('  <g inkscape:groupmode="layer" id="lyr-coudes" '
                 'inkscape:label="coudes">\n')
        for sub, gid, f, prefix in (
            ('Coude droit', 'lyr-coude-droit',
             'cable_elbow.svg', 'coude-droit'),
            ('Coude gauche', 'lyr-coude-gauche',
             'cable_ttee.svg', 'coude-gauche'),
        ):
            _, defs, paths = layer_paths_from_svg(
                os.path.join(SRC, 'tiles', f), prefix)
            write_layer(fh, sub, defs, paths, gid=gid)
        fh.write('  </g>\n')
        # Regroupements « Ecran » et « Connecteurs » (2e cas réel) :
        # « Ecran » prend tous ses calques, « Connecteurs/Haut » un
        # seul sous-calque.
        fh.write('  <g inkscape:groupmode="layer" id="lyr-ecran" '
                 'inkscape:label="Ecran">\n')
        for sub, gid, f, prefix in (
            ('Haut', 'lyr-ecran-haut', 'cable_straight.svg', 'ecran-haut'),
            ('Bas', 'lyr-ecran-bas', 'cable_stub.svg', 'ecran-bas'),
        ):
            _, defs, paths = layer_paths_from_svg(
                os.path.join(SRC, 'tiles', f), prefix)
            write_layer(fh, sub, defs, paths, gid=gid)
        fh.write('  </g>\n')
        fh.write('  <g inkscape:groupmode="layer" id="lyr-connecteurs" '
                 'inkscape:label="Connecteurs">\n')
        for sub, gid, f, prefix in (
            ('Haut', 'lyr-conn-haut', 'cable_cross.svg', 'conn-haut'),
            ('Bas', 'lyr-conn-bas', 'cable_ttee.svg', 'conn-bas'),
        ):
            _, defs, paths = layer_paths_from_svg(
                os.path.join(SRC, 'tiles', f), prefix)
            write_layer(fh, sub, defs, paths, gid=gid)
        fh.write('  </g>\n')
        # computer/server/fonds : couches monoblocs depuis les
        # VectorDrawables (les svg/tile_background*.svg du dépôt
        # contiennent des <rect> dégénérés, sans valeur d'oracle ; la
        # décomposition réelle en calques sera faite par eric — ici on
        # teste le mécanisme calques + dégradés inline des VD vers SVG).
        for layer, f in {
            'ordinateur-1': 'computer1.xml',
            'ordinateur-2': 'computer2.xml',
            'serveur-partiel': 'server.xml',
            'serveur-complet': 'server1.xml',
            'fond-tuile': 'tile_background.xml',
            'fond-tuile-verrouille': 'tile_background_locked.xml',
            'fond-tuile-vide': 'tile_background_empty.xml',
        }.items():
            defs = []
            paths = layer_paths_from_vd(os.path.join(DRAW, f), defs, layer)
            write_layer(fh, layer, defs, paths)
        # Calques de test : transformation imbriquée + formes Inkscape.
        fh.write('  <g inkscape:groupmode="layer" id="lyr-tf" '
                 'inkscape:label="test-transform">\n')
        fh.write('    <g transform="translate(10,20)">\n')
        fh.write('      <g transform="scale(2)">\n')
        fh.write('        <path d="M1,2 L3,4Z" fill="#FF0000"/>\n')
        fh.write('      </g>\n')
        fh.write('    </g>\n')
        fh.write('  </g>\n')
        fh.write('  <g inkscape:groupmode="layer" id="lyr-shapes" '
                 'inkscape:label="test-formes">\n')
        fh.write('    <rect x="10" y="10" width="40" height="30" '
                 'rx="5" fill="#00FF00"/>\n')
        fh.write('    <circle cx="50" cy="50" r="20" fill="#0000FF"/>\n')
        fh.write('    <polygon points="0,0 10,0 5,8" fill="#FFFF00"/>\n')
        fh.write('  </g>\n')
        # Calque de test : éléments NOMMÉS adressables un à un (cas
        # réel du SVG d'eric : un rect id="Vertical" dans le calque
        # Câbles), data-name (VectorCraft et similaires), opacité,
        # stroke, image ignorée.
        fh.write('  <g inkscape:groupmode="layer" id="lyr-objets" '
                 'inkscape:label="test-objets">\n')
        fh.write('    <rect id="objet-rect" x="0" y="0" width="10" '
                 'height="10" fill="#112233"/>\n')
        fh.write('    <polygon id="objet-poly" data-name="Polygone" '
                 'points="0,0 10,0 5,8" fill="#445566" '
                 'opacity="0.5"/>\n')
        fh.write('    <path id="objet-trait" d="M0,0 L10,10" '
                 'fill="#778899" stroke="#ABCDEF" stroke-width="2.5" '
                 'stroke-linejoin="round"/>\n')
        fh.write('    <image id="objet-photo" width="10" height="10" '
                 'xlink:href="data:image/png;base64,iVBORw0KGgo="/>\n')
        fh.write('  </g>\n')
        fh.write('</svg>\n')

    # ---- 2. Configuration de test ----
    conf_path = os.path.join(WORK, 'test.conf')
    with open(conf_path, 'w', encoding='utf-8') as fh:
        fh.write('[svg]\nfile = %s\noutput = %s\n\n' % (svg_path,
                                                        os.path.join(WORK,
                                                                     'out')))
        for name, layer in {
            'cable_straight': 'cable-droit',
            # coude via le sous-calque du regroupement « coudes » :
            # c'est le chemin d'adresse hiérarchique qui est testé.
            'cable_elbow': 'coudes/Coude droit',
            'cable_ttee': 'cable-te',
            'cable_cross': 'cable-croix',
            'cable_stub': 'cable-bout',
        }.items():
            fh.write('[%s]\nlayers = %s\ngrey = true\n\n'
                     % (name, layer))
        # Le calque PÈRE prend tout son sous-arbre (Coude droit puis
        # Coude gauche, dans l'ordre du document) ; « Ecran » prend
        # Haut+Bas, puis « Connecteurs/Haut » le seul sous-calque Haut.
        fh.write('[essai_groupe]\nlayers = coudes\n\n')
        fh.write('[essai_multi]\nlayers = Ecran, Connecteurs/Haut\n\n')
        fh.write('[computer1]\nlayers = ordinateur-1\n\n')
        fh.write('[computer2]\nlayers = ordinateur-2\n\n')
        fh.write('[server]\nlayers = serveur-partiel\n\n')
        fh.write('[server1]\nlayers = serveur-complet\n\n')
        for name, layer in {
            'tile_background': 'fond-tuile',
            'tile_background_locked': 'fond-tuile-verrouille',
            'tile_background_empty': 'fond-tuile-vide',
        }.items():
            fh.write('[%s]\nlayers = %s\nviewport = 64x64\n\n'
                     % (name, layer))
        fh.write('[essai_transform]\nlayers = test-transform\n\n')
        fh.write('[essai_formes]\nlayers = test-formes\n\n')
        # Éléments nommés : rect par id, polygon par data-name,
        # path par id (stroke). L'image du calque doit être ignorée.
        fh.write('[essai_objets]\nlayers = test-objets/objet-rect, '
                 'test-objets/Polygone, test-objets/objet-trait\n'
                 'grey = true\n\n')

    # ---- 3. Références plates via convert_tiles (parse_svg+write_vd) ----
    oracle_dir = os.path.join(WORK, 'oracle')
    os.makedirs(oracle_dir, exist_ok=True)
    for name, f in {
        'cable_straight': 'cable_straight.svg',
        'cable_elbow': 'cable_elbow.svg',
        'cable_ttee': 'cable_ttee.svg',
        'cable_cross': 'cable_cross.svg',
        'cable_stub': 'cable_stub.svg',
    }.items():
        vp, elements = ct.parse_svg(os.path.join(SRC, 'tiles', f))
        ct.write_vd(os.path.join(oracle_dir, name + '.xml'), vp,
                    elements, False)
        ct.write_vd(os.path.join(oracle_dir, name + '_grey.xml'), vp,
                    elements, True)
    for name, f in {
        'computer1': 'computer1.xml',
        'computer2': 'computer2.xml',
        'server': 'server.xml',
        'server1': 'server1.xml',
        'tile_background': 'tile_background.xml',
        'tile_background_locked': 'tile_background_locked.xml',
        'tile_background_empty': 'tile_background_empty.xml',
    }.items():
        defs = []
        paths = layer_paths_from_vd(os.path.join(DRAW, f), defs, name)
        vb = ('0 0 64 64' if name.startswith('tile_')
              else '0 0 226.77 226.77')
        flat = os.path.join(WORK, 'flat-%s.svg' % name)
        with open(flat, 'w', encoding='utf-8') as fh:
            fh.write('<?xml version="1.0"?>\n<svg '
                     'xmlns="http://www.w3.org/2000/svg" '
                     'viewBox="%s">\n' % vb)
            fh.write('  <defs>\n')
            for d in defs:
                fh.write(d + '\n')
            fh.write('  </defs>\n')
            for p in paths:
                fh.write(p + '\n')
            fh.write('</svg>\n')
        vp, elements = ct.parse_svg(flat)
        ct.write_vd(os.path.join(oracle_dir, name + '.xml'), vp,
                    elements, False)

    # Oracles des éléments multi-calques : SVG plats équivalents,
    # tracés dans l'ordre du document du SVG à calques.
    for name, sources in {
        # « coudes » entier : Coude droit (elbow) puis Coude
        # gauche (ttee), dans cet ordre du document.
        'essai_groupe': [('cable_elbow.svg', 'groupe-e'),
                         ('cable_ttee.svg', 'groupe-t')],
        # « Ecran » (Haut=droit, Bas=bout) puis « Connecteurs/Haut »
        # (croix) : Connecteurs/Bas (té) NE DOIT PAS apparaître.
        'essai_multi': [('cable_straight.svg', 'multi-d'),
                        ('cable_stub.svg', 'multi-b'),
                        ('cable_cross.svg', 'multi-c')],
    }.items():
        flat = os.path.join(WORK, 'flat-%s.svg' % name)
        all_defs = []
        all_paths = []
        for f, prefix in sources:
            _, defs, paths = layer_paths_from_svg(
                os.path.join(SRC, 'tiles', f), prefix)
            all_defs.extend(defs)
            all_paths.extend(paths)
        with open(flat, 'w', encoding='utf-8') as fh:
            fh.write('<?xml version="1.0"?>\n<svg '
                     'xmlns="http://www.w3.org/2000/svg" '
                     'viewBox="0 0 226.77 226.77">\n  <defs>\n')
            for d in all_defs:
                fh.write(d + '\n')
            fh.write('  </defs>\n')
            for p in all_paths:
                fh.write(p + '\n')
            fh.write('</svg>\n')
        vp, elements = ct.parse_svg(flat)
        ct.write_vd(os.path.join(oracle_dir, name + '.xml'), vp,
                    elements, False)

    # ---- 4. Génération + comparaison byte à byte ----
    r = subprocess.run([sys.executable, TOOL, conf_path],
                       capture_output=True, text=True)
    print(r.stdout[-1500:])
    if r.returncode != 0:
        print('ÉCHEC génération :', r.stderr)
        return 1
    out_dir = os.path.join(WORK, 'out')
    diffs = []
    for name in sorted(os.listdir(oracle_dir)):
        gen = os.path.join(out_dir, name)
        ref = os.path.join(oracle_dir, name)
        with open(gen, encoding='utf-8') as a, \
                open(ref, encoding='utf-8') as b:
            if a.read() != b.read():
                diffs.append(name)
    print('---')
    print('Éléments identiques à la référence : %d/%d'
          % (len(os.listdir(oracle_dir)) - len(diffs),
             len(os.listdir(oracle_dir))))
    if diffs:
        ok = False
        print('DIFFÉRENCES :', ', '.join(diffs))

    # Comparaison avec les drawables du dépôt (doivent être identiques
    # aux références : ils en proviennent). Les éléments d'essai
    # (essai_*) n'existent pas dans le dépôt : hors comparaison.
    repo_names = [n for n in sorted(os.listdir(oracle_dir))
                  if os.path.exists(os.path.join(DRAW, n))]
    repo_diffs = []
    for name in repo_names:
        with open(os.path.join(oracle_dir, name), encoding='utf-8') as a, \
                open(os.path.join(DRAW, name), encoding='utf-8') as b:
            if a.read() != b.read():
                repo_diffs.append(name)
    print('Références identiques aux drawables du dépôt : %d/%d'
          % (len(repo_names) - len(repo_diffs), len(repo_names)))
    if repo_diffs:
        print('(écart dépôt :', ', '.join(repo_diffs), ')')

    # ---- 5. Transformations : contrôle attendu ----
    gen = os.path.join(out_dir, 'essai_transform.xml')
    with open(gen, encoding='utf-8') as fh:
        content = fh.read()
    # translate(10,20) puis scale(2) : M1,2 -> M(1*2+10, 2*2+20) = M12,24
    # L3,4 -> L16,28 ; Z inchangé.
    expect = 'M12,24 L16,28 Z'
    got = re.search(r'android:pathData="([^"]*)"', content).group(1)
    print('---')
    print('Transformation aplatie : %s' % ('OK' if got == expect
                                           else 'ÉCHEC (%s)' % got))
    ok = ok and got == expect

    # ---- 6. Formes Inkscape : rect arrondi (arcs), circle, polygon ----
    gen = os.path.join(out_dir, 'essai_formes.xml')
    with open(gen, encoding='utf-8') as fh:
        content = fh.read()
    datas = re.findall(r'android:pathData="([^"]*)"', content)
    # rect arrondi x=10 y=10 w=40 h=30 rx=5 -> arcs convertis en
    # cubiques (pas de A dans la sortie, 1 cubique par coin de 90
    # degrés), nombres formatés sans zeros inutiles.
    rect_ok = ('A' not in datas[0] and datas[0].startswith('M15,10 ')
               and datas[0].count('C') == 4 and datas[0].endswith('Z'))
    # circle -> 2 demi-arcs ; en cubiques : au moins 4 C.
    circ_ok = 'A' not in datas[1] and datas[1].count('C') >= 4
    poly_ok = datas[2] == 'M0,0 L10,0 L5,8 Z'
    print('Formes : rect arrondi %s, circle %s, polygon %s'
          % ('OK' if rect_ok else 'ÉCHEC', 'OK' if circ_ok else 'ÉCHEC',
             'OK' if poly_ok else 'ÉCHEC (%s)' % datas[2]))
    ok = ok and rect_ok and circ_ok and poly_ok

    # ---- 6bis. Éléments nommés, image, opacité, stroke ----
    gen = os.path.join(out_dir, 'essai_objets.xml')
    with open(gen, encoding='utf-8') as fh:
        content = fh.read()
    blocks = re.findall(r'<path(.*?)/>', content, re.S)

    def attr(block, name):
        found = re.search(r'android:%s="([^"]*)"' % name, block)
        return found.group(1) if found else None

    # Ordre du document : rect, puis polygon (opacity 0.5 ->
    # alpha = int(255 x 0.5 + 0.5) = 128 = 0x80), puis path à contour.
    obj_ok = len(blocks) == 3
    obj_ok = obj_ok and blocks.index(
        next(b for b in blocks if attr(b, 'pathData')
             == 'M0,0 L10,0 L10,10 L0,10 Z')) == 0
    obj_ok = obj_ok and attr(blocks[0], 'fillColor') == '#FF112233'
    obj_ok = obj_ok and blocks.index(
        next(b for b in blocks if attr(b, 'pathData')
             == 'M0,0 L10,0 L5,8 Z')) == 1
    obj_ok = obj_ok and attr(blocks[1], 'fillColor') == '#80445566'
    obj_ok = obj_ok and blocks.index(
        next(b for b in blocks if attr(b, 'pathData')
             == 'M0,0 L10,10')) == 2
    obj_ok = obj_ok and attr(blocks[2], 'fillColor') == '#FF778899'
    obj_ok = obj_ok and attr(blocks[2], 'strokeColor') == '#FFABCDEF'
    obj_ok = obj_ok and attr(blocks[2], 'strokeWidth') == '2.5'
    obj_ok = obj_ok and attr(blocks[2], 'strokeLineJoin') == 'round'
    print('Éléments nommés + opacité + stroke : %s'
          % ('OK' if obj_ok else 'ÉCHEC'))
    ok = ok and obj_ok

    # Variante grise : le contour suit la même règle greyOut que le
    # remplissage (0,6 x canal + 100, alpha inchangé).
    with open(os.path.join(out_dir, 'essai_objets_grey.xml'),
              encoding='utf-8') as fh:
        grey_blocks = re.findall(r'<path(.*?)/>', fh.read(), re.S)
    grey_ok = (attr(grey_blocks[2], 'fillColor')
               == ct.conv_color('#778899', 255, True)
               and attr(grey_blocks[2], 'strokeColor')
               == ct.conv_color('#ABCDEF', 255, True)
               and attr(grey_blocks[1], 'fillColor')
               == ct.conv_color('#445566', 128, True))
    print('Variante grise (remplissage et contour) : %s'
          % ('OK' if grey_ok else 'ÉCHEC'))
    ok = ok and grey_ok

    # L'image du calque doit être ignorée avec une note, sans faire
    # échouer la génération.
    img_ok = ('<image>' in r.stdout and 'ignorée' in r.stdout)
    print('<image> ignorée avec note : %s'
          % ('OK' if img_ok else 'ÉCHEC'))
    ok = ok and img_ok

    # ---- 7. Erreurs : calque inconnu, --check ----
    bad_conf = os.path.join(WORK, 'bad.conf')
    with open(bad_conf, 'w', encoding='utf-8') as fh:
        fh.write('[svg]\nfile = %s\noutput = %s\n\n'
                 '[cable_straight]\nlayers = calque-qui-nexiste-pas\n'
                 % (svg_path, os.path.join(WORK, 'out')))
    r = subprocess.run([sys.executable, TOOL, bad_conf],
                       capture_output=True, text=True)
    err_ok = (r.returncode != 0
              and 'calque(s) inconnu(s)' in r.stderr
              and 'cable-droit' in r.stderr)
    print('Calque inconnu -> message clair : %s'
          % ('OK' if err_ok else 'ÉCHEC'))
    ok = ok and err_ok

    # Sous-élément inconnu d'un parent CONNU : le message liste les
    # enfants directs de ce parent (cas réel : une variante mal
    # orthographiée dans un regroupement).
    bad_child = os.path.join(WORK, 'child.conf')
    with open(bad_child, 'w', encoding='utf-8') as fh:
        fh.write('[svg]\nfile = %s\noutput = %s\n\n'
                 '[cable_straight]\nlayers = coudes/Coude tres loin\n'
                 % (svg_path, os.path.join(WORK, 'out')))
    r = subprocess.run([sys.executable, TOOL, bad_child],
                       capture_output=True, text=True)
    child_ok = (r.returncode != 0
                and 'calque(s) inconnu(s)' in r.stderr
                and 'Coude droit' in r.stderr
                and 'Coude gauche' in r.stderr)
    print('Sous-élément inconnu -> enfants du parent listés : %s'
          % ('OK' if child_ok else 'ÉCHEC'))
    ok = ok and child_ok

    # Erreur de syntaxe de la configuration (cas réel : une ligne
    # laissée sans ';' dans un commentaire édité) : message clair en
    # français, SANS traceback Python.
    bad_syntax = os.path.join(WORK, 'syntax.conf')
    with open(bad_syntax, 'w', encoding='utf-8') as fh:
        fh.write('[svg]\nfile = %s\noutput = %s\n\n'
                 '[cable_straight]\nlayers = cable-droit\n\n'
                 'Générateur\n'
                 % (svg_path, os.path.join(WORK, 'out')))
    r = subprocess.run([sys.executable, TOOL, bad_syntax],
                       capture_output=True, text=True)
    syntax_ok = (r.returncode != 0
                 and 'Traceback' not in r.stderr
                 and 'Configuration invalide' in r.stderr
                 and 'Générateur' in r.stderr
                 and 'commentaire' in r.stderr)
    print('Syntaxe de conf invalide -> message clair : %s'
          % ('OK' if syntax_ok else 'ÉCHEC'))
    ok = ok and syntax_ok
    r = subprocess.run([sys.executable, TOOL, '--check', conf_path],
                       capture_output=True, text=True)
    check_ok = (r.returncode == 0
                and 'Vérification OK : 17 éléments' in r.stdout
                and not os.path.exists(os.path.join(WORK, 'out',
                                                    'essai_check.xml')))
    print('--check valide sans écrire : %s' % ('OK' if check_ok
                                              else 'ÉCHEC'))
    ok = ok and check_ok

    print('===')
    print('BANC D\'ESSAI : %s' % ('TOUT PASSE' if ok else 'ÉCHECS'))
    return 0 if ok else 1

if __name__ == '__main__':
    sys.exit(main())
