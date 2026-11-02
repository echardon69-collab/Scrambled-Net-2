#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convertisseur SVG -> VectorDrawable pour les tuiles de Scrambled Net 2.

Lit les sources de skin (svg/tiles/cable_*.svg) et genere, pour chaque
source cable_<nom>.svg, deux drawables :

  - drawable/cable_<nom>.xml       : variante normale (reliée au serveur) ;
  - drawable/cable_<nom>_grey.xml   : variante grise (déconnectée, parité
       greyOut de Cell.java de l'original : chaque canal RGB devient
       0,6 x valeur + 100, alpha inchangé).

Objectif : un nombre minimal de fichiers sources (5 formes de base) ;
les 15 orientations du jeu sont obtenues au dessin par canvas.rotate
(BoardView), et les variantes grises sont generees par cet outil plutot
que par un filtre de couleur a l'execution.

Sous-ensemble SVG garanti (le VectorDrawable n'accepte pas tout SVG) :
  - chemins : M, L, C, Q, A, Z (les arcs A sont convertis en cubiques,
    le VD n'a pas de commande d'arc ; l'approximation k = 0,5523 par
    quart de cercle est invisible a l'oeil, < 0,01 sur 226,77) ;
  - remplissages unis #RRGGBB ou a dégradés linearGradient /
    radialGradient en gradientUnits="userSpaceOnUse" ;
  - pas de contours (stroke), ni de transform, ni d'images.

Python 3 standard, aucune dependance. Usage :

    python3 tools/tiles/convert_tiles.py [svg_dir] [drawable_dir]

Valeurs par defaut : svg/tiles et app/src/main/res/drawable, relatifs a
la racine du dépôt (les scripts generate-tiles.sh / .bat appelent cet
outil depuis la racine).
"""

import os
import re
import sys
import xml.dom.minidom as minidom

SVG_NS = 'http://www.w3.org/2000/svg'

# Pas d'arc dans le pathData d'un VectorDrawable : approximation des
# arcs par cubiques, k = 4 x (racine(2) - 1) / 3 pour un quart de cercle.
KAPPA = 0.5522847498307936

NUMBER = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')
CMDS = 'MLCQAZmlcqaz'


def fmt(x):
    """Formate un nombre : 5 decimales max, sans zeros ni -0 inutiles."""
    s = '%.5f' % x
    s = s.rstrip('0').rstrip('.')
    if s in ('', '-0', '-'):
        s = '0'
    return s


# ----------------------------------------------------------------- pathData

def tokenize(d):
    """Decoupe une chaine pathData en (commande, [nombres bruts])."""
    out = []
    i, n = 0, len(d)
    while i < n:
        c = d[i]
        if c.isspace() or c == ',':
            i += 1
            continue
        if c in CMDS:
            i += 1
            nums = []
            while True:
                while i < n and (d[i].isspace() or d[i] == ','):
                    i += 1
                m = NUMBER.match(d, i)
                if not m or m.end() == i:
                    break
                nums.append(m.group(0))
                i = m.end()
            out.append((c, nums))
        else:
            raise ValueError('caractere inattendu dans pathData : %r' % c)
    return out


def arc_to_cubics(x0, y0, rx, ry, phi, large, sweep, x1, y1):
    """Convertit un arc (parametres SVG) en liste de cubiques.

    Implémente la conversion point -> centre de la spec SVG (annexe F),
    decoupe l'arc en segments d'au plus 90 degres et approxime chaque
    segment par une courbe de Bezier cubique (k = 0,5523 par quart).
    Renvoie [(c1x, c1y, c2x, c2y, px, py), ...] ; le point de depart
    (x0, y0) n'est pas inclus.
    """
    if rx == 0 or ry == 0:
        return [(x0 + (x1 - x0) / 3, y0 + (y1 - y0) / 3,
                 x0 + 2 * (x1 - x0) / 3, y0 + 2 * (y1 - y0) / 3, x1, y1)]
    rx, ry = abs(rx), abs(ry)
    # F.6.5 : placement du centre de l'ellipse.
    cos_p = _cos(phi)
    sin_p = _sin(phi)
    dx2 = (x0 - x1) / 2
    dy2 = (y0 - y1) / 2
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    if x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2 > 1:
        s = ((x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2) ** 0.5) / 2
        # arc impossible a ce rayon : mise a l'echelle du rayon (spec)
        rx *= s
        ry *= s
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    co = ((num / den) ** 0.5) if den else 0
    if large == sweep:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x0 + x1) / 2
    cy = sin_p * cxp + cos_p * cyp + (y0 + y1) / 2
    # Angles de debut et de fin sur l'ellipse.
    def angle(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = ((ux ** 2 + uy ** 2) * (vx ** 2 + vy ** 2)) ** 0.5
        a = _acos(dot / n) if n else 0
        if ux * vy - uy * vx < 0:
            a = -a
        return a
    ux, uy = (x1p - cxp) / rx, (y1p - cyp) / ry
    vx, vy = (-x1p - cxp) / rx, (-y1p - cyp) / ry
    theta1 = angle(1, 0, ux, uy)
    dtheta = angle(ux, uy, vx, vy)
    if not sweep and dtheta > 0:
        dtheta -= 2 * _pi()
    elif sweep and dtheta < 0:
        dtheta += 2 * _pi()
    # Decoupage en segments d'au plus 90 degres.
    segs = max(1, int(_ceil(abs(dtheta) / (_pi() / 2) - 1e-9)))
    delta = dtheta / segs
    t = theta1
    px, py = x0, y0
    out = []
    for _ in range(segs):
        t2 = t + delta
        ex = cx + rx * _cos(t2) * cos_p - ry * _sin(t2) * sin_p
        ey = cy + rx * _cos(t2) * sin_p + ry * _sin(t2) * cos_p
        # Bezier d'un segment d'ellipse : facteur tangent. La tangente
        # en theta est (-rx.sin, ry.cos) tournee par phi ; f porte le
        # signe de delta, donc la meme formule vaut pour les deux sens.
        f = 4 / 3 * _tan(delta / 4)
        c1x = px - f * (rx * _sin(t)) * cos_p - f * (ry * _cos(t)) * sin_p
        c1y = py - f * (rx * _sin(t)) * sin_p + f * (ry * _cos(t)) * cos_p
        c2x = ex + f * (rx * _sin(t2)) * cos_p + f * (ry * _cos(t2)) * sin_p
        c2y = ey + f * (rx * _sin(t2)) * sin_p - f * (ry * _cos(t2)) * cos_p
        out.append((c1x, c1y, c2x, c2y, ex, ey))
        px, py = ex, ey
        t = t2
    return out


def _cos(x):
    import math
    return math.cos(x)


def _sin(x):
    import math
    return math.sin(x)


def _tan(x):
    import math
    return math.tan(x)


def _acos(x):
    import math
    return math.acos(max(-1, min(1, x)))


def _pi():
    import math
    return math.pi


def _ceil(x):
    import math
    return math.ceil(x)


def convert_path(d):
    """pathData SVG -> pathData VectorDrawable (M/L/C/Q/Z uniquement).

    Conserve les nombres sources tels quels quand la commande passe
    telle quelle ; seuls les arcs produisent des valeurs recalculees.
    """
    toks = tokenize(d)
    out = []
    cur = None  # point courant, pour les arcs
    start = None
    for cmd, nums in toks:
        raw = nums
        if cmd in 'Mm':
            it = iter(raw)
            pairs = list(zip(it, it))
            out.append('M' + ','.join(pairs[0]))
            cur = (float(pairs[0][0]), float(pairs[0][1]))
            start = cur
            for p in pairs[1:]:
                out.append('L' + ','.join(p))
                cur = (float(p[0]), float(p[1]))
        elif cmd in 'Ll':
            it = iter(raw)
            for p in zip(it, it):
                out.append('L' + ','.join(p))
                cur = (float(p[0]), float(p[1]))
        elif cmd in 'Cc':
            if len(raw) % 6:
                raise ValueError('C sans 6 nombres : %s' % raw)
            for i in range(0, len(raw), 6):
                out.append('C' + ','.join(raw[i:i + 6]))
                cur = (float(raw[i + 4]), float(raw[i + 5]))
        elif cmd in 'Qq':
            if len(raw) % 4:
                raise ValueError('Q sans 4 nombres : %s' % raw)
            for i in range(0, len(raw), 4):
                out.append('Q' + ','.join(raw[i:i + 4]))
                cur = (float(raw[i + 2]), float(raw[i + 3]))
        elif cmd in 'Aa':
            if len(raw) % 7:
                raise ValueError('A sans 7 nombres : %s' % raw)
            if cur is None:
                raise ValueError('arc avant tout M')
            for i in range(0, len(raw), 7):
                rx, ry, phi, large, sweep = (float(v) for v in raw[i:i + 5])
                x1, y1 = float(raw[i + 5]), float(raw[i + 6])
                segs = arc_to_cubics(cur[0], cur[1], rx, ry, phi,
                                     int(large), int(sweep), x1, y1)
                for c in segs:
                    out.append('C' + ','.join(fmt(v) for v in c))
                cur = (x1, y1)
        elif cmd in 'Zz':
            out.append('Z')
            cur = start
        else:
            raise ValueError('commande non supportee : %s' % cmd)
    return ' '.join(out)


# ------------------------------------------------------------------- couleurs

def grey_channel(c):
    """Parité greyOut de Cell.java : 0,6 x canal + 100, borne a 255."""
    return max(0, min(255, int(round(0.6 * c + 100))))


def conv_color(rgb, alpha, grey):
    """(#RRGGBB, 0-255) -> '#AARRGGBB', grise si demande."""
    r, g, b = (int(rgb[i:i + 2], 16) for i in (1, 3, 5))
    if grey:
        r, g, b = (grey_channel(r), grey_channel(g), grey_channel(b))
    return '#%02X%02X%02X%02X' % (alpha, r, g, b)


# ------------------------------------------------------------------- SVG lu

class Gradient(object):
    kind = None  # 'linear' | 'radial'
    attrs = {}
    stops = []   # (offset, '#RRGGBB', alpha 0-255)


def parse_svg(path):
    """Lit un SVG et renvoie (viewport, [element], gradients par id).

    element = ('path', pathdata, fill, fill_type) avec fill soit
    ('solid', couleur, alpha) soit ('grad', Gradient).
    """
    doc = minidom.parse(path)
    root = doc.documentElement
    vb = root.getAttribute('viewBox').split()
    if len(vb) != 4 or float(vb[0]) != 0 or float(vb[1]) != 0:
        raise ValueError('%s : viewBox attendu "0 0 L H", lu %r'
                         % (path, root.getAttribute('viewBox')))
    vp = (vb[2], vb[3])
    grads = {}
    for g in doc.getElementsByTagName('linearGradient') \
            + doc.getElementsByTagName('radialGradient'):
        grad = Gradient()
        grad.kind = 'linear' if g.tagName == 'linearGradient' else 'radial'
        if g.getAttribute('gradientUnits') != 'userSpaceOnUse':
            raise ValueError('%s : gradient %s hors userSpaceOnUse '
                             '(le VD ne sait pas faire autrement)'
                             % (path, g.getAttribute('id')))
        grad.attrs = {
            'startX': g.getAttribute('x1'), 'startY': g.getAttribute('y1'),
            'endX': g.getAttribute('x2'), 'endY': g.getAttribute('y2'),
            'centerX': g.getAttribute('cx'), 'centerY': g.getAttribute('cy'),
            'gradientRadius': g.getAttribute('r'),
        }
        stops = []
        for s in g.getElementsByTagName('stop'):
            col = s.getAttribute('stop-color')
            if not re.match(r'^#[0-9A-Fa-f]{6}$', col):
                raise ValueError('%s : stop-color %r non supporte '
                                 '(attendu #RRGGBB)' % (path, col))
            op = s.getAttribute('stop-opacity')
            alpha = int(round(float(op) * 255)) if op else 255
            stops.append((s.getAttribute('offset'), col, alpha))
        grad.stops = stops
        grads['#' + g.getAttribute('id')] = grad
    elements = []
    for p in doc.getElementsByTagName('path'):
        d = p.getAttribute('d')
        if p.hasAttribute('stroke') and p.getAttribute('stroke') != 'none':
            raise ValueError('%s : stroke non supporte par le VD' % path)
        if p.hasAttribute('transform'):
            raise ValueError('%s : transform non supporte' % path)
        fill = p.getAttribute('fill')
        m = re.match(r'^url\(#([A-Za-z0-9_-]+)\)$', fill)
        if m:
            key = '#' + m.group(1)
            if key not in grads:
                raise ValueError('%s : gradient inconnu %s' % (path, fill))
            f = ('grad', grads[key])
        elif re.match(r'^#[0-9A-Fa-f]{6}$', fill):
            f = ('solid', fill, 255)
        elif re.match(r'^#[0-9A-Fa-f]{8}$', fill):
            f = ('solid', fill[3:], int(fill[1:3], 16))
        else:
            raise ValueError('%s : fill non supporte : %r' % (path, fill))
        rule = (p.getAttribute('fill-rule') or 'nonzero').lower()
        if rule not in ('nonzero', 'evenodd'):
            raise ValueError('%s : fill-rule non supporte : %r'
                             % (path, rule))
        elements.append(('path', d, f, rule == 'evenodd'))
    return vp, elements


# ---------------------------------------------------------------- sortie VD

def write_vd(out_path, vp, elements, grey):
    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append('<vector xmlns:android='
                 '"http://schemas.android.com/apk/res/android"')
    lines.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    lines.append('    android:width="64dp"')
    lines.append('    android:height="64dp"')
    lines.append('    android:viewportWidth="%s"' % vp[0])
    lines.append('    android:viewportHeight="%s"' % vp[1])
    lines.append('>')
    for _, d, fill, even_odd in elements:
        d_vd = convert_path(d)
        attrs = ['    android:pathData="%s"' % d_vd]
        if even_odd:
            attrs.append('    android:fillType="evenOdd"')
        if fill[0] == 'solid':
            attrs.append('    android:fillColor="%s"'
                         % conv_color(fill[1], fill[2], grey))
            lines.append('  <path')
            lines.extend(attrs)
            lines.append('      />')
        else:
            g = fill[1]
            lines.append('  <path')
            lines.extend(attrs)
            lines.append('      >')
            lines.append('    <aapt:attr name="android:fillColor">')
            if g.kind == 'linear':
                lines.append('        <gradient')
                lines.append('        android:type="linear"')
                lines.append('        android:startX="%s" android:startY="%s"'
                             % (g.attrs['startX'], g.attrs['startY']))
                lines.append('        android:endX="%s" android:endY="%s">'
                             % (g.attrs['endX'], g.attrs['endY']))
            else:
                lines.append('        <gradient')
                lines.append('        android:type="radial"')
                lines.append('        android:centerX="%s" android:centerY="%s"'
                             % (g.attrs['centerX'], g.attrs['centerY']))
                lines.append('        android:gradientRadius="%s">'
                             % g.attrs['gradientRadius'])
            for off, col, alpha in g.stops:
                lines.append('        <item android:offset="%s" '
                             'android:color="%s"/>'
                             % (off, conv_color(col, alpha, grey)))
            lines.append('        </gradient>')
            lines.append('    </aapt:attr>')
            lines.append('  </path>')
    lines.append('</vector>')
    with open(out_path, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(lines) + '\n')


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    svg_dir = argv[1] if len(argv) > 1 else os.path.join(root, 'svg', 'tiles')
    out_dir = (argv[2] if len(argv) > 2 else
               os.path.join(root, 'app', 'src', 'main', 'res', 'drawable'))
    names = sorted(f for f in os.listdir(svg_dir)
                   if f.startswith('cable_') and f.endswith('.svg'))
    if not names:
        print('Aucun cable_*.svg dans %s' % svg_dir, file=sys.stderr)
        return 1
    for name in names:
        base = name[:-4]
        vp, elements = parse_svg(os.path.join(svg_dir, name))
        write_vd(os.path.join(out_dir, base + '.xml'), vp, elements, False)
        print('  %s.xml' % base)
        write_vd(os.path.join(out_dir, base + '_grey.xml'), vp, elements,
                 True)
        print('  %s_grey.xml' % base)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
