#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Convertisseur SVG -> VectorDrawable pour les drapeaux du sélecteur.

Lit les sources svg/flags/flag_*.svg (choisies par eric : France,
Union Jack, tête de Maure corse, Alsace historique) et génère un
drawable par drapeau : app/src/main/res/drawable/flag_<tag>.xml, à
hauteur uniforme de 15 dp, largeur = 15 dp x ratio du drapeau, pour
les boutons radio du sélecteur de langue (SettingsActivity,
langFlagFor : fr, en, co, gsw-FR).

RÈGLES VECTORDRAWABLE : SOURCE UNIQUE. Ce convertisseur n'en définit
aucune : il réutilise
  - tools/tiles/convert_tiles.py (ct) : fmt, convert_path, path_fill,
    write_vd (tracés sans remplissage via fill=None ; dp accepte un
    couple largeur x hauteur pour les drapeaux plus larges que hauts) ;
  - tools/graphics/generate_graphics.py (gg) : shape_to_path_d
    (rect, line, polyline, polygon, circle, ellipse), parse_stroke,
    opacity_factor, apply_opacity.

Ce que ce convertisseur ajoute par rapport à convert_tiles.parse_svg :
les drapeaux d'eric sont des SVG de dessin vectoriel génériques, avec
des CLASSES CSS (<style>), des attributs hérités par les <g>, des
contours (stroke) et des tracés sans remplissage (fill="none"). Il
RÉSOUT ces attributs (présentation d'abord, CSS par-dessus, héritage
par <g>, la dernière définition d'une classe gagne) puis injecte le
résultat sur un clone de l'élément et délègue aux fonctions ci-dessus
— gg.parse_stroke lit les attributs SUR l'élément, d'où le clone.

Sous-ensemble accepté :
  - formes : rect, line, polyline, polygon, circle, ellipse, path ;
  - remplissages unis #RRGGBB ou #AARRGGBB, ou none (contour seul) ;
  - fill-rule evenodd (tête de Maure) ;
  - <title>, <desc>, <defs>, <g> vides : ignorés ;
  - classes CSS non définies (st0, st2 des exporteurs) : ignorées.

Refus explicite (le VectorDrawable ne sait pas les représenter,
message clair) : clip-path, transform, gradients, <image>, <use>.
Pour l'Union Jack le clipPath d'eric est impossible : svg/flags/
flag_en.svg est la réécriture équivalente sans clip (voir son
en-tête). Les DÉGRADÉS du SVG restent refusés ; l'OMBRE de bordure
(voir OMBRE_LARGEUR_DP) est SYNTHÉTIQUE : le convertisseur la
fabrique en dernier, il n'en lit aucune dans le SVG.

Python 3 standard, aucune dépendance. Usage :

    python3 tools/flags/convert_flags.py [svg_dir] [drawable_dir]

Valeurs par défaut : svg/flags et app/src/main/res/drawable, relatifs
à la racine du dépôt (les scripts convert-flags.sh / .bat appellent
cet outil depuis la racine).
"""

import os
import re
import sys
import xml.dom.minidom as minidom

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, '..', 'tiles'))
sys.path.insert(0, os.path.join(_HERE, '..', 'graphics'))
import convert_tiles as ct                              # noqa: E402
import generate_graphics as gg                          # noqa: E402

SVG_NS = 'http://www.w3.org/2000/svg'

HAUTEUR_DP = 15.0

# Ombrage des drapeaux pâles : sur un fond blanc, un drapeau blanc
# ne se détache pas (retour d'eric, nov. 2026). Un bandau de dégradé
# noir translucide, qui s'éteint vers l'intérieur, se pose sous les
# bordures DROITE et BASSE : le drapeau paraît posé, sa géométrie et
# ses couleurs restent celles du SVG source, l'ombre se dessine
# PAR-DESSUS, en DERNIER. Les deux constantes se règlent ici.
OMBRE_LARGEUR_DP = 1.0   # épaisseur du bandau, en dp
OMBRE_ALPHA = 0x26       # 38/255 : ~15 % de noir au ras du bord

# Propriétés CSS/attributs hérités par les enfants, résolus par ce
# convertisseur (le reste — d, points, x, y... — reste un attribut
# de forme lu par gg.shape_to_path_d).
HERITEES = ('fill', 'fill-opacity', 'fill-rule', 'opacity', 'stroke',
            'stroke-opacity', 'stroke-width', 'stroke-linejoin',
            'stroke-linecap')

# Éléments sans tracé : ignorés en silence (métadonnées, sources).
IGNORES = ('title', 'desc', 'defs', 'style', 'metadata', 'clipPath',
           'linearGradient', 'radialGradient', 'image', 'filter',
           'mask', 'sodipodi', 'namedview')

FORMES = ('rect', 'line', 'polyline', 'polygon', 'circle', 'ellipse',
          'path')

# Sélecteur de classe CSS : .nom{prop:val;prop:val} (pas de
# sélecteurs imbriqués dans les exporteurs visés).
CSS_REGLE = re.compile(r'\.([A-Za-z0-9_-]+)\s*\{([^}]*)\}')


def _texte(el):
    """Contenu textuel d'un élément (<style> notamment)."""
    return ''.join(n.data for n in el.childNodes
                   if n.nodeType == n.TEXT_NODE)


def collecte_classes(doc):
    """Classes CSS du document -> {nom: {propriété: valeur}}.

    Les <style> sont parcourus dans l'ordre du document ; une classe
    redéfinie fusionne ses propriétés, la DERNIÈRE définition gagne
    (comme la cascade CSS pour un même poids).
    """
    classes = {}
    for style in doc.getElementsByTagName('style'):
        for m in CSS_REGLE.finditer(_texte(style)):
            nom, corps = m.group(1), m.group(2)
            props = {}
            for decl in corps.split(';'):
                if ':' not in decl:
                    continue
                cle, val = decl.split(':', 1)
                cle = cle.strip().lower()
                if cle in HERITEES:
                    props[cle] = val.strip()
            if nom not in classes:
                classes[nom] = {}
            classes[nom].update(props)
    return classes


def _localname(el):
    return el.tagName.split(':')[-1]


def _refuse(el, src):
    """Érreurs explicites : ce que le VD ne sait pas représenter."""
    for attr in ('clip-path', 'transform'):
        if el.hasAttribute(attr):
            raise ValueError(
                '%s : attribut %s non supporté par le VectorDrawable '
                '(voir l\u2019en-tête de svg/flags/flag_en.svg pour la '
                'réécriture sans clip)'
                % (src, attr))
    tag = _localname(el)
    if tag == 'use':
        raise ValueError('%s : <use> non supporté' % src)


def _resoud(el, heritees, classes):
    """Attributs résolus d'un élément : hérités du <g> parent,
    puis attributs de présentation, puis classes CSS par-dessus."""
    props = dict(heritees)
    for cle in HERITEES:
        if el.hasAttribute(cle):
            props[cle] = el.getAttribute(cle)
    for cls in el.getAttribute('class').split():
        if cls in classes:
            props.update(classes[cls])
    return props


def _element_vd(node, props, src):
    """Un nœud de forme -> tuple ('path', d, fill, even_odd[, stroke])
    pour ct.write_vd, avec les attributs RÉSOLUS (le clone porte le
    résultat : ct.path_fill et gg.parse_stroke lisent l'élément)."""
    _refuse(node, src)
    clone = node.cloneNode(False)
    for cle, val in props.items():
        clone.setAttribute(cle, val)

    tag = _localname(node)
    if tag == 'path':
        d = node.getAttribute('d')
    else:
        d = gg.shape_to_path_d(clone, src)

    facteur = gg.opacity_factor(clone)

    fill = props.get('fill', 'black')  # défaut SVG : noir
    if fill == 'none':
        f = None
        rule = (props.get('fill-rule') or 'nonzero').lower()
        if rule not in ('nonzero', 'evenodd'):
            raise ValueError('%s : fill-rule non supporté : %r'
                             % (src, rule))
        even_odd = (rule == 'evenodd')
    else:
        f, even_odd = ct.path_fill(clone, {}, src, allow_stroke=True)
        f = gg.apply_opacity(f, facteur)

    stroke = gg.parse_stroke(clone, facteur, src)
    if f is None and stroke is None:
        raise ValueError('%s : tracé sans fill ni stroke (invisible) '
                          '— retirer l\u2019élément' % src)
    if f is None:
        return "path", d, None, even_odd, stroke
    if stroke is None:
        return "path", d, f, even_odd
    return "path", d, f, even_odd, stroke


def _parcours(el, heritees, classes, elements, src):
    """Parcourt le sous-arbre dans l'ordre du document, en portant
    les attributs résolus des <g> vers leurs enfants."""
    for node in el.childNodes:
        if node.nodeType != node.ELEMENT_NODE:
            continue
        _refuse(node, src)
        tag = _localname(node)
        if tag in IGNORES:
            continue
        if tag == 'g':
            _parcours(node, _resoud(node, heritees, classes),
                      classes, elements, src)
        elif tag in FORMES:
            elements.append(_element_vd(
                node, _resoud(node, heritees, classes), src))
        else:
            raise ValueError('%s : forme <%s> non supportée' % (src, tag))


def parse_flag(path):
    """Lit un drapeau -> (viewport, [éléments pour ct.write_vd])."""
    doc = minidom.parse(path)
    root = doc.documentElement
    if _localname(root) != 'svg':
        raise ValueError('%s : racine <svg> attendue' % path)
    _refuse(root, path)
    vb = root.getAttribute('viewBox').split()
    if len(vb) != 4 or float(vb[0]) != 0 or float(vb[1]) != 0:
        raise ValueError('%s : viewBox attendu "0 0 L H", lu %r'
                         % (path, root.getAttribute('viewBox')))
    classes = collecte_classes(doc)
    elements = []
    _parcours(root, {}, classes, elements, path)
    if not elements:
        raise ValueError('%s : aucun tracé trouvé' % path)
    return (vb[2], vb[3]), elements


def dp_drapeau(vp):
    """(largeur, hauteur) en dp : hauteur uniforme 15 dp, largeur
    proportionnelle au ratio du drapeau."""
    ratio = float(vp[0]) / float(vp[1])
    return '%g' % (HAUTEUR_DP * ratio), '%g' % HAUTEUR_DP


def _gradeau(x1, y1, x2, y2, alpha):
    """Gradient linéaire maison (transparent -> noir translucide).

    Représentation de convert_tiles.Gradient, source unique des
    règles d'écriture ; les coordonnées du VD vivent dans le
    viewport, comme celles des tracés.
    """
    g = ct.Gradient()
    g.kind = 'linear'
    g.attrs = {'startX': '%g' % x1, 'startY': '%g' % y1,
               'endX': '%g' % x2, 'endY': '%g' % y2}
    g.stops = [(0, '#000000', 0), (1, '#000000', alpha)]
    return g


def ombres_drapeau(vp):
    """Les DEUX éléments d'ombre (bas, droite) à ajouter en DERNIER.

    Chaque bandau garde la MÊME épaisseur en dp d'un drapeau à
    l'autre : la hauteur rendue est uniforme (HAUTEUR_DP), le
    viewport est réduit proportionnellement, donc une dp vaut le
    même nombre d'unités sur les deux axes — hauteur du viewport /
    HAUTEUR_DP. Le
    rectangle du BAS est pleine largeur, celui de la DROITE pleine
    hauteur : au coin, les deux bandaux se superposent, l'ombre y
    est simplement un peu plus dense (naturel pour un coin posé).
    """
    largeur, hauteur = float(vp[0]), float(vp[1])
    unites = hauteur / HAUTEUR_DP       # unités viewport par dp
    bande = OMBRE_LARGEUR_DP * unites   # épaisseur en unités
    bas = ('path',
           'M0,%g L%g,%g L%g,%g L0,%g Z'
           % (hauteur - bande, largeur, hauteur - bande,
              largeur, hauteur, hauteur),
           ('grad', _gradeau(0, hauteur - bande, 0, hauteur,
                             OMBRE_ALPHA)),
           False)
    droite = ('path',
              'M%g,0 L%g,0 L%g,%g L%g,%g Z'
              % (largeur - bande, largeur, largeur, hauteur,
                 largeur - bande, hauteur),
              ('grad', _gradeau(largeur - bande, 0, largeur, 0,
                                OMBRE_ALPHA)),
              False)
    return [bas, droite]


def convert_flag(path, out_dir):
    """Convertit un svg/flags/flag_<tag>.svg en drawable du dépôt :
    drapeau d'abord, ombre SOUS ses bordures droite et basse en
    DERNIER (au-dessus du contenu, elle détache les drapeaux pâles
    du fond blanc sans retoucher leurs couleurs)."""
    vp, elements = parse_flag(path)
    dp = dp_drapeau(vp)
    elements.extend(ombres_drapeau(vp))
    out = os.path.join(out_dir,
                       os.path.splitext(os.path.basename(path))[0]
                       + '.xml')
    ct.write_vd(out, vp, elements, False, dp)
    return out


def main(argv):
    root = os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__))))
    svg_dir = argv[1] if len(argv) > 1 else os.path.join(
        root, 'svg', 'flags')
    out_dir = (argv[2] if len(argv) > 2 else
               os.path.join(root, 'app', 'src', 'main', 'res',
                            'drawable'))
    names = sorted(f for f in os.listdir(svg_dir)
                   if f.startswith('flag_') and f.endswith('.svg'))
    if not names:
        print('Aucun flag_*.svg dans %s' % svg_dir, file=sys.stderr)
        return 1
    os.makedirs(out_dir, exist_ok=True)
    for name in names:
        out = convert_flag(os.path.join(svg_dir, name), out_dir)
        print('  %s' % os.path.basename(out))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
