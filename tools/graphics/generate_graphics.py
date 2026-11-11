#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Générateur des éléments graphiques du jeu depuis un SVG unique à calques.

Lit :
  - un SVG source UNIQUE (déclaré dans le fichier de configuration) qui
    contient tous les éléments graphiques de base du jeu, chacun dans
    son propre calque (comme les calques Inkscape) ;
  - le fichier de configuration (tools/graphics/graphics.conf) qui
    dit, pour chaque ÉLÉMENT DU JEU ( drawable Android ), la liste des
    calques du SVG à activer pour le produire.

Pour chaque élément, l'outil compose les calques demandés dans l'ordre
du document SVG (comme si on affichait/masquait des calques dans
Inkscape), aplatit les transformations imbriquées (translate, scale,
rotate, matrix, skewX, skewY), convertit les arcs et les formes
Inkscape (rect, circle, ellipse, line, polygon, polyline) en chemins,
et écrit le VectorDrawable Android (res/drawable/<element>.xml).
L'option grey = true génère AUSSI la variante grise
<element>_grey.xml (parité greyOut de Cell.java de l'original :
chaque canal RGB devient 0,6 x valeur + 100, alpha inchangé) — la
même règle que tools/tiles/convert_tiles.py, dont cet outil
réutilise le code (source unique des règles de conversion).

Sous-ensemble SVG garanti :
  - chemins : M, L, C, Q, A, Z ; formes de base (rect y compris à
    coins arrondis, circle, ellipse, line, polygon, polyline) ;
    remplissages unis #RRGGBB/#AARRGGBB ou dégradés linearGradient /
    radialGradient en gradientUnits="userSpaceOnUse" ; fill-rule
    nonzero/evenodd ; attributs opacity / fill-opacity appliqués à
    l'alpha des couleurs (unis comme dégradés) ;
  - contours (stroke, stroke-width, stroke-linejoin, stroke-linecap,
    stroke-opacity) : convertis en android:strokeColor / strokeWidth /
    strokeLineJoin / strokeLineCap — le VectorDrawable sait les faire ;
  - les transformations sont acceptées et aplaties (convert_tiles,
    lui, les refuse : il travaille sur des SVG déjà posés) ;
  - les <image> (bitmaps, ex. références PNG de l'original) sont
    IGNORÉES avec une note : un VectorDrawable ne peut pas contenir
    d'image ;
  - mix-blend-mode et gradientTransform ne sont pas représentables
    dans un VectorDrawable : ignorés avec une note (rendu approché).

Adressage : chaque <g> enfant direct de la racine <svg> est un
CALQUE. Est aussi addressable, dans un calque, tout élément NOMMÉ —
sous-calque Inkscape, groupe, ou simple forme : le nom lu dans
l'attribut inkscape:label, data-name (VectorCraft, Illustrator et
similaires) ou id.
L'adresse d'un sous-élément est son chemin, niveaux séparés par des
« / » : « coudes/Coude droit », ou « Câbles/Vertical » pour le rect
nommé Vertical du calque Câbles. Choisir le calque PÈRE (« coudes »)
prend tout son sous-arbre, sous-éléments compris ; on peut mixer
calques entiers et sous-éléments précis : « Ecran, Connecteurs/Haut ».
Un groupe ou une forme SANS aucun nom reste anonyme : ses tracés
appartiennent au calque qui l'entoure. Tous les calques partagent le
même viewBox : les éléments se composent par superposition.

Python 3 standard, aucune dépendance. Usage, à la racine du dépôt :

    python3 tools/graphics/generate_graphics.py [--check]

    --check : valide le SVG et la configuration sans rien écrire.

À la fin, l'outil compare les fichiers générés aux éléments que le
jeu charge réellement (BoardView, R.drawable.*) et signale ceux qui
manquent, ainsi que les calques du SVG jamais utilisés.
"""

import configparser
import math
import os
import re
import sys

import xml.dom.minidom as minidom

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, 'tools', 'tiles'))
import convert_tiles as ct  # noqa: E402  (règles VD : source unique)

# Éléments que le jeu charge (BoardView.kt, R.drawable.*) : connus du
# générateur pour signaler ceux qui manquent à la fin d'une exécution.
GAME_ELEMENTS = {
    # câbles : 5 formes, chacune en normal + gris (les 15 orientations
    # du jeu sont des rotations au dessin, BoardView)
    'cable_straight', 'cable_straight_grey',
    'cable_elbow', 'cable_elbow_grey',
    'cable_ttee', 'cable_ttee_grey',
    'cable_cross', 'cable_cross_grey',
    'cable_stub', 'cable_stub_grey',
    # terminaux : écran rouge (éteint) / écran vert (allumé)
    'computer1', 'computer2',
    # serveur : DEL rouges (partiel) / DEL vertes (entièrement câblé)
    'server', 'server1',
    # fonds de tuile : normal / verrouillé / vide (hors plateau)
    'tile_background', 'tile_background_locked', 'tile_background_empty',
}

DEFAULT_CONFIG = os.path.join(ROOT, 'tools', 'graphics', 'graphics.conf')
DEFAULT_OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')

TRANSFORM_OPS = re.compile(
    r'(translate|scale|rotate|matrix|skewX|skewY)\s*\(([^)]*)\)')
CONVERTED_CMD = re.compile(r'([MLCQZ])')
IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

# Enfants de la racine sans intérêt graphique (calques elsewhere).
ROOT_SKIP = {'defs', 'metadata', 'namedview', 'title', 'desc'}

# Éléments jamais dessinés, rencontrables dans un calque (les dégradés
# sont collectés globalement par collect_gradients, pas ici).
SKIP_TAGS = ROOT_SKIP | {'linearGradient', 'radialGradient'}
# Éléments directement convertissables en chemin.
SHAPE_TAGS = {'rect', 'circle', 'ellipse', 'line', 'polygon', 'polyline'}


# ------------------------------------------------------------------ matrices

def mat_mul(m, n):
    """Composition de deux matrices affines : n appliquée APRÈS m."""
    a1, b1, c1, d1, e1, f1 = m
    a2, b2, c2, d2, e2, f2 = n
    return (a2 * a1 + b2 * c1, a2 * b1 + b2 * d1,
            c2 * a1 + d2 * c1, c2 * b1 + d2 * d1,
            e2 * a1 + f2 * c1 + e1, e2 * b1 + f2 * d1 + f1)


def parse_transform(text):
    """Chaîne transform SVG -> matrice affine (a, b, c, d, e, f)."""
    m = IDENTITY
    for name, args_txt in TRANSFORM_OPS.findall(text or ''):
        args = [float(v) for v in re.split(r'[\s,]+', args_txt.strip()) if v]
        if name == 'translate':
            tx = args[0]
            ty = args[1] if len(args) > 1 else 0.0
            op = (1.0, 0.0, 0.0, 1.0, tx, ty)
        elif name == 'scale':
            sx = args[0]
            sy = args[1] if len(args) > 1 else sx
            op = (sx, 0.0, 0.0, sy, 0.0, 0.0)
        elif name == 'rotate':
            a = math.radians(args[0])
            cx = args[1] if len(args) > 1 else 0.0
            cy = args[2] if len(args) > 2 else 0.0
            cos, sin = math.cos(a), math.sin(a)
            rot = (cos, sin, -sin, cos, 0.0, 0.0)
            op = mat_mul(mat_mul((1.0, 0.0, 0.0, 1.0, cx, cy), rot),
                         (1.0, 0.0, 0.0, 1.0, -cx, -cy))
        elif name == 'matrix':
            if len(args) != 6:
                raise ValueError('matrix sans 6 nombres : %r' % args_txt)
            op = tuple(args)
        elif name == 'skewX':
            op = (1.0, 0.0, math.tan(math.radians(args[0])), 1.0, 0.0, 0.0)
        else:  # skewY
            op = (1.0, math.tan(math.radians(args[0])), 0.0, 1.0, 0.0, 0.0)
        m = mat_mul(m, op)
    return m


def apply_matrix(m, x, y):
    a, b, c, d, e, f = m
    return (a * x + c * y + e, b * x + d * y + f)


def transform_converted_path(d, m):
    """Applique une matrice affine à un pathData DÉJÀ converti.

    Entrée : sortie de ct.convert_path (M, L, C, Q, Z absolus
    uniquement, arcs déjà changés en cubiques). Chaque point —
    extrémités et points de contrôle — traverse la matrice : une
    transformation affine respecte les Bézier.
    """
    if m == IDENTITY:
        return d
    chunks = CONVERTED_CMD.split(d)
    # ['', 'M', 'nombres', 'L', 'nombres', ..., 'Z', '']
    out = []
    for i in range(1, len(chunks), 2):
        cmd = chunks[i]
        nums_txt = chunks[i + 1] if i + 1 < len(chunks) else ''
        nums = [float(v) for v in re.split(r'[\s,]+', nums_txt) if v]
        if cmd == 'Z':
            out.append('Z')
        else:
            pts = [apply_matrix(m, nums[j], nums[j + 1])
                   for j in range(0, len(nums), 2)]
            out.append(cmd + ','.join(
                ct.fmt(px) + ',' + ct.fmt(py) for px, py in pts))
    return ''.join(out)


# --------------------------------------------------- formes Inkscape -> path

def _num(el, name, default=0.0):
    v = el.getAttribute(name)
    return float(v) if v else default


def shape_to_path_d(el, src):
    """<rect>/<circle>/<ellipse>/<line>/<polygon>/<polyline> -> pathData.

    Les coins arrondis des rect passent par des arcs A (convertis en
    cubiques juste après, comme n'importe quel arc).
    """
    tag = el.tagName.split(':')[-1]
    if tag == 'rect':
        x, y = _num(el, 'x'), _num(el, 'y')
        w, h = _num(el, 'width'), _num(el, 'height')
        rx = el.getAttribute('rx') or el.getAttribute('ry')
        ry = el.getAttribute('ry') or rx
        if rx and float(rx) > 0:
            rx, ry = float(rx), float(ry or rx)
            x2, y2 = x + w, y + h
            return ('M%s,%s L%s,%s A%s,%s 0 0 1 %s,%s L%s,%s '
                    'A%s,%s 0 0 1 %s,%s L%s,%s A%s,%s 0 0 1 %s,%s '
                    'L%s,%s A%s,%s 0 0 1 %s,%s Z'
                    % (ct.fmt(x + rx), ct.fmt(y), ct.fmt(x2 - rx),
                       ct.fmt(y), ct.fmt(rx), ct.fmt(ry), ct.fmt(x2),
                       ct.fmt(y + ry),
                       ct.fmt(x2), ct.fmt(y2 - ry), ct.fmt(rx),
                       ct.fmt(ry), ct.fmt(x2 - rx), ct.fmt(y2),
                       ct.fmt(x + rx), ct.fmt(y2), ct.fmt(rx),
                       ct.fmt(ry), ct.fmt(x), ct.fmt(y2 - ry),
                       ct.fmt(x), ct.fmt(y + ry), ct.fmt(rx),
                       ct.fmt(ry), ct.fmt(x + rx), ct.fmt(y)))
        return ('M%s,%s L%s,%s L%s,%s L%s,%s Z'
                % (ct.fmt(x), ct.fmt(y), ct.fmt(x + w), ct.fmt(y),
                   ct.fmt(x + w), ct.fmt(y + h), ct.fmt(x),
                   ct.fmt(y + h)))
    if tag == 'circle':
        cx, cy, r = _num(el, 'cx'), _num(el, 'cy'), _num(el, 'r')
        return ('M%s,%s A%s,%s 0 0 1 %s,%s A%s,%s 0 0 1 %s,%s Z'
                % (ct.fmt(cx - r), ct.fmt(cy), ct.fmt(r), ct.fmt(r),
                   ct.fmt(cx + r), ct.fmt(cy), ct.fmt(r), ct.fmt(r),
                   ct.fmt(cx - r), ct.fmt(cy)))
    if tag == 'ellipse':
        cx, cy = _num(el, 'cx'), _num(el, 'cy')
        rx, ry = _num(el, 'rx'), _num(el, 'ry')
        return ('M%s,%s A%s,%s 0 0 1 %s,%s A%s,%s 0 0 1 %s,%s Z'
                % (ct.fmt(cx - rx), ct.fmt(cy), ct.fmt(rx), ct.fmt(ry),
                   ct.fmt(cx + rx), ct.fmt(cy), ct.fmt(rx), ct.fmt(ry),
                   ct.fmt(cx - rx), ct.fmt(cy)))
    if tag == 'line':
        return ('M%s,%s L%s,%s'
                % (ct.fmt(_num(el, 'x1')), ct.fmt(_num(el, 'y1')),
                   ct.fmt(_num(el, 'x2')), ct.fmt(_num(el, 'y2'))))
    if tag in ('polygon', 'polyline'):
        pts = [float(v) for v in re.split(r'[\s,]+',
                                          el.getAttribute('points').strip())
               if v]
        if len(pts) < 4 or len(pts) % 2:
            raise ValueError('%s : attribut points invalide' % src)
        pairs = ['%s,%s' % (ct.fmt(pts[i]), ct.fmt(pts[i + 1]))
                 for i in range(0, len(pts), 2)]
        d = 'M' + pairs[0] + ' L' + ' L'.join(pairs[1:])
        return d + ' Z' if tag == 'polygon' else d
    raise ValueError('%s : forme <%s> non supportée' % (src, tag))


# ------------------------------------------------------------- lecture calques

def element_names(el):
    """(nom affiché, aliases) d'un élément quelconque (groupe ou forme).

    Sont lus, dans cet ordre de priorité : inkscape:label (le nom
    visible dans Inkscape), data-name (le nom lisible défini par
    l'outil de dessin : VectorCraft, Illustrator et similaires),
    id. Chaque nom présent est un ALIAS addressable :
    un élément peut donc être désigné par n'importe lequel de ses
    noms. Un élément sans aucun nom est anonyme : ses tracés
    appartiennent au calque qui l'entoure.
    """
    label = el.getAttribute('inkscape:label').strip()
    data = el.getAttribute('data-name').strip()
    eid = el.getAttribute('id').strip()
    aliases = []
    for key in (label, data, eid):
        if key and key not in aliases:
            aliases.append(key)
    return (label or data or eid, aliases)


def _opacity_factor(el):
    """Facteur d'alpha d'un élément : opacity puis fill-opacity."""
    factor = 1.0
    for attr in ('opacity', 'fill-opacity'):
        value = el.getAttribute(attr)
        if value:
            factor *= float(value)
    return factor


def apply_opacity(fill, factor):
    """Applique le facteur d'alpha à un remplissage (uni ou dégradé).

    Les dégradés sont partagés entre éléments : ne jamais les
    modifier en place — on en construit une copie aux alphas réduits.
    """
    if factor >= 1.0:
        return fill
    if fill[0] == 'solid':
        return ('solid', fill[1], int(fill[2] * factor + 0.5))
    grad = fill[1]
    copy = ct.Gradient()
    copy.kind = grad.kind
    copy.attrs = dict(grad.attrs)
    copy.stops = [(off, col, int(alpha * factor + 0.5))
                  for off, col, alpha in grad.stops]
    return ('grad', copy)


def parse_stroke(el, factor, src):
    """Attributs de contour d'une forme -> tuple pour ct.write_vd.

    Retourne (couleur_#RRGGBB, alpha, largeur, linejoin, linecap),
    ou None si la forme n'a pas de contour. L'alpha du contour
    intègre le facteur d'opacité de l'élément (opacity et
    stroke-opacity) : un contour semi-transparent traverse la même
    règle que le remplissage.
    """
    value = el.getAttribute('stroke')
    if not value or value == 'none':
        return None
    if re.match(r'^#[0-9A-Fa-f]{6}$', value):
        rgb, alpha = value, 255
    elif re.match(r'^#[0-9A-Fa-f]{8}$', value):
        rgb, alpha = '#' + value[3:], int(value[1:3], 16)
    else:
        raise ValueError('%s : couleur de stroke non supportée : %r '
                         '(attendu #RRGGBB ou #AARRGGBB)' % (src, value))
    stroke_opacity = el.getAttribute('stroke-opacity')
    if stroke_opacity:
        alpha = int(alpha * float(stroke_opacity) + 0.5)
    alpha = int(alpha * factor + 0.5)
    width = el.getAttribute('stroke-width') or '1'
    try:
        float(width)
    except ValueError:
        raise ValueError('%s : stroke-width non numérique : %r'
                         % (src, width))
    join = (el.getAttribute('stroke-linejoin') or 'miter').strip()
    if join not in ('miter', 'round', 'bevel'):
        raise ValueError('%s : stroke-linejoin non supporté : %r'
                         % (src, join))
    cap = (el.getAttribute('stroke-linecap') or 'butt').strip()
    if cap not in ('butt', 'round', 'square'):
        raise ValueError('%s : stroke-linecap non supporté : %r'
                         % (src, cap))
    return (rgb, alpha, width, join, cap)


def walk_layer(group, matrix, grads, src, all_paths, notices):
    """Parcourt le sous-arbre d'un calque, dans l'ordre du document.

    Chaque tracé reçoit un numéro global (son index dans all_paths) ;
    les transformations imbriquées se composent de la racine vers la
    feuille et sont aplaties une fois par chemin.

    Retourne un NŒUD : {'display', 'aliases', 'seqs', 'subs'}.
    seqs = numéros des tracés du sous-arbre ; subs = nœuds des
    sous-éléments NOMMÉS rencontrés directement (groupe ou forme :
    calque imbriqué, regroupement, rect nommé, etc.). Les noms vides
    remontent : un conteneur anonyme fond ses tracés et ses
    sous-éléments dans le calque qui l'entoure.
    """
    m = mat_mul(matrix, parse_transform(group.getAttribute('transform')))
    display, aliases = element_names(group)
    seqs = []
    subs = []
    for child in group.childNodes:
        if child.nodeType != child.ELEMENT_NODE:
            continue
        tag = child.tagName.split(':')[-1]
        if tag in SKIP_TAGS:
            # defs, dégradés, title/desc, metadata : jamais dessinés ;
            # les dégradés sont collectés globalement par collect_gradients.
            continue
        if tag == 'image':
            # Bitmap (souvent une référence de l'original) : un
            # VectorDrawable ne peut pas contenir d'image. On ne
            # plante pas : on saute en le signalant.
            notices.append('<image> ignorée dans « %s » : un '
                           'VectorDrawable ne peut pas contenir '
                           "d'image — vectoriser ou garder la référence "
                           'hors du pipeline' % display)
            continue
        if tag == 'g':
            sub = walk_layer(child, m, grads, src, all_paths, notices)
            if sub['aliases']:
                seqs.extend(sub['seqs'])
                subs.append(sub)
            else:
                seqs.extend(sub['seqs'])
                subs.extend(sub['subs'])
            continue
        if tag == 'path':
            d = child.getAttribute('d')
        elif tag in SHAPE_TAGS:
            d = shape_to_path_d(child, src)
        else:
            raise ValueError('%s : élément <%s> non supporté dans un '
                             'calque (attendus : path, rect, circle, '
                             'ellipse, line, polygon, polyline, g)'
                             % (src, tag))
        if 'mix-blend-mode' in (child.getAttribute('style') or ''):
            notices.append('mix-blend-mode ignoré sur « %s » : le '
                           'VectorDrawable ne connaît pas les modes de '
                           'fusion — rendu approché en alpha simple'
                           % (element_names(child)[0] or display))
        factor = _opacity_factor(child)
        f, even_odd = ct.path_fill(child, grads, src, allow_stroke=True)
        f = apply_opacity(f, factor)
        stroke = parse_stroke(child, factor, src)
        d = transform_converted_path(ct.convert_path(d), m)
        all_paths.append((d, f, even_odd, stroke))
        seqs.append(len(all_paths) - 1)
        child_display, child_aliases = element_names(child)
        if child_aliases:
            subs.append({'display': child_display, 'aliases': child_aliases,
                         'seqs': [len(all_paths) - 1], 'subs': []})
    return {'display': display, 'aliases': aliases,
            'seqs': seqs, 'subs': subs}


def parse_layered_svg(src):
    """SVG unique -> (viewport, units, registry, all_paths, notices).

    units   : {chemin d'adresse: unité} — un chemin par alias
              (inkscape:label, data-name et id de chaque niveau,
              joints par '/') ;
    registry: [unité, ...] dans l'ordre du document, chaque unité
              (calque racine, sous-calque, groupe ou forme nommée)
              étant addressable sous tous ses chemins d'alias ;
    all_paths: liste des tracés, index = ordre du document ;
    notices : limitations rencontrées (images ignorées, blend modes,
              gradientTransform) à afficher à l'utilisateur.

    Sélectionner une unité PÈRE prend tout son sous-arbre (ses
    sous-éléments compris) ; sélectionner 'parent/enfant' ne prend
    que ce sous-élément.
    """
    doc = minidom.parse(src)
    root = doc.documentElement
    vb = root.getAttribute('viewBox').split()
    if len(vb) != 4 or float(vb[0]) != 0 or float(vb[1]) != 0:
        raise ValueError('%s : viewBox attendu "0 0 L H", lu %r'
                         % (src, root.getAttribute('viewBox')))
    vp = (vb[2], vb[3])
    grads = ct.collect_gradients(doc, src)
    notices = []
    seen = set()
    for g in (doc.getElementsByTagName('linearGradient')
              + doc.getElementsByTagName('radialGradient')):
        gid = g.getAttribute('id')
        if gid in seen:
            raise ValueError('%s : id de dégradé dupliqué %r — dans un '
                             'SVG unique, chaque dégradé doit avoir un '
                             'id distinct (Inkscape le garantit)'
                             % (src, gid))
        seen.add(gid)
        if g.getAttribute('gradientTransform'):
            notices.append('gradientTransform ignoré sur le dégradé '
                           '#%s : le VectorDrawable ne sait pas '
                           'transformer un dégradé — géométrie prise '
                           'telle quelle (rendu approché si ce dégradé '
                           'est utilisé)' % gid)

    all_paths = []
    units = {}
    registry = []

    def register(prefixes, disp, node):
        """Enregistre une unité puis ses sous-éléments, en profondeur.

        prefixes : chemins d'alias déjà construits pour le parent ;
        la racine démarre avec [''] (pas de préfixe).
        """
        display, aliases = node['display'], node['aliases']
        if not aliases:
            raise ValueError('%s : calque sans nom (ni inkscape:label, '
                             'ni data-name, ni id) — donner un nom à '
                             'chaque calque' % src)
        paths = [(p + '/' + a) if p else a
                 for p in prefixes for a in aliases]
        ndisp = (disp + '/' + display) if disp else display
        layer = {'paths': paths, 'display': ndisp,
                 'seqs': sorted(node['seqs'])}
        for p in paths:
            if p in units:
                raise ValueError('%s : nom de calque dupliqué %r'
                                 % (src, p))
        registry.append(layer)
        for p in paths:
            units[p] = layer
        for sub in node['subs']:
            register(paths, ndisp, sub)

    for child in root.childNodes:
        if child.nodeType != child.ELEMENT_NODE:
            continue
        tag = child.tagName.split(':')[-1]
        if tag in ROOT_SKIP:
            continue
        if tag != 'g':
            raise ValueError('%s : <%s> hors de tout calque — ranger '
                             'chaque élément dans un <g> (calque)'
                             % (src, tag))
        node = walk_layer(child, IDENTITY, grads, src, all_paths, notices)
        register([''], '', node)

    if not registry:
        raise ValueError('%s : aucun calque (<g>) trouvé' % src)
    return vp, units, registry, all_paths, notices


# ------------------------------------------------------------------ programme

def load_config(conf_path):
    """Lit graphics.conf -> (svg déclaré, sortie, [options d'élément])."""
    parser = configparser.ConfigParser()
    try:
        found = parser.read(conf_path, encoding='utf-8')
    except configparser.Error as exc:
        raise ValueError(
            'Configuration invalide dans %s : %s\n'
            'Chaque ligne non vide doit être une section [nom] '
            '(unique), une option "cle = valeur" (unique dans sa '
            'section) ou un commentaire commençant par ; ou # en '
            'début de ligne.'
            % (conf_path, ' '.join(str(exc).split())))
    if not found:
        raise ValueError('configuration introuvable : %s' % conf_path)
    if not parser.has_section('svg'):
        raise ValueError('[svg] manquant dans %s' % conf_path)
    svg = parser.get('svg', 'file')
    out = parser.get('svg', 'output', fallback=None) or DEFAULT_OUT
    elements = []
    for section in parser.sections():
        if section == 'svg':
            continue
        layers = [v.strip() for v in
                  parser.get(section, 'layers').split(',') if v.strip()]
        if not layers:
            raise ValueError('[%s] : layers vide' % section)
        elements.append({
            'name': section,
            'layers': layers,
            'grey': parser.getboolean(section, 'grey', fallback=False),
            'dp': parser.getint(section, 'dp', fallback=64),
            'viewport': parser.get(section, 'viewport', fallback=''),
        })
    return svg, out, elements


def viewport_of(spec, vp):
    """Viewport d'un élément : viewBox du SVG, ou override 'LxH'/'L H'."""
    spec = spec.strip()
    if not spec:
        return vp
    parts = re.split(r'[xX\s,]+', spec)
    if len(parts) != 2 or not all(
            re.match(r'^\d*\.?\d+$', p) for p in parts):
        raise ValueError('viewport attendu "LxH", lu %r' % spec)
    return (parts[0], parts[1])


def main(argv):
    check_only = '--check' in argv[1:]
    conf_path = next((a for a in argv[1:] if not a.startswith('--')),
                     DEFAULT_CONFIG)
    svg, out_dir, elements = load_config(conf_path)
    svg_path = svg if os.path.isabs(svg) else os.path.join(ROOT, svg)
    print('SVG source    : %s' % svg_path)
    print('Configuration : %s' % conf_path)
    print('Sortie        : %s%s'
          % (out_dir, ' (vérification seule)' if check_only else ''))
    vp, units, registry, all_paths, notices = parse_layered_svg(svg_path)
    roots = [l for l in registry if '/' not in l['display']]
    print('Calques       : %d — %s'
          % (len(roots), ', '.join(l['display'] for l in roots)))
    if len(registry) > len(roots):
        print('Sous-éléments nommés addressables (parent/nom) : %d'
              % (len(registry) - len(roots)))
    for note in notices:
        print('NOTE : %s' % note)

    generated = set()
    used = set()
    if not check_only and out_dir:
        os.makedirs(out_dir, exist_ok=True)
    for el in elements:
        missing = [l for l in el['layers'] if l not in units]
        if missing:
            roots = [l['display'] for l in registry
                     if '/' not in l['display']]
            hints = []
            for miss in missing:
                parent = miss.rsplit('/', 1)[0] if '/' in miss else None
                if parent is not None and parent in units:
                    base = units[parent]['display']
                    depth = base.count('/') + 1
                    kids = sorted(
                        c['display'].rsplit('/', 1)[1] for c in registry
                        if c['display'].startswith(base + '/')
                        and c['display'].count('/') == depth)
                    hints.append('sous-éléments de « %s » : %s'
                                 % (parent, ', '.join(kids)))
            msg = ('[%s] : calque(s) inconnu(s) %s — calques racine '
                   'disponibles : %s ; les éléments nommés se '
                   'désignent par « parent/nom »'
                   % (el['name'], missing, ', '.join(roots)))
            if hints:
                msg += ' ; ' + ' ; '.join(hints)
            raise ValueError(msg)
        used.update(el['layers'])
        # Composition dans l'ordre du document : la superposition du
        # SVG fait foi, comme des calques qu'on affiche ou masque.
        # Chaque tracé porte son numéro d'ordre global, ce qui garde
        # l'ordre du document même entre calques et sous-calques.
        seqs = sorted({s for l in el['layers']
                       for s in units[l]['seqs']})
        combined = [all_paths[s] for s in seqs]
        if not combined:
            raise ValueError('[%s] : aucun tracé dans les calques %s'
                             % (el['name'], el['layers']))
        el_vp = viewport_of(el['viewport'], vp)
        vd_elements = [('path', d, f, eo, st) for d, f, eo, st in combined]
        print('  %-24s %2d tracés -> %s.xml%s'
              % (el['name'], len(combined), el['name'],
                 ' + %s_grey.xml' % el['name'] if el['grey'] else ''))
        generated.add(el['name'])
        if el['grey']:
            generated.add(el['name'] + '_grey')
        if not check_only:
            ct.write_vd(os.path.join(out_dir, el['name'] + '.xml'),
                        el_vp, vd_elements, grey=False, dp=el['dp'])
            if el['grey']:
                ct.write_vd(os.path.join(out_dir,
                                         el['name'] + '_grey.xml'),
                            el_vp, vd_elements, grey=True, dp=el['dp'])

    # Rapport de fin : éléments du jeu non générés, calques inutilisés.
    print()
    missing = sorted(GAME_ELEMENTS - generated)
    if missing:
        print('ATTENTION : éléments chargés par le jeu, non générés :')
        for name in missing:
            print('  - %s' % name)
    else:
        print('Tous les éléments chargés par le jeu sont générés (%d).'
              % len(GAME_ELEMENTS))
    # Une unité est utilisée si la configuration la cite (par
    # n'importe lequel de ses alias), ou cite un de ses ANCESTRES
    # (choisir le père traverse tout son sous-arbre), ou cite un de
    # ses descendants.
    def layer_used(layer):
        for p in layer['paths']:
            if p in used:
                return True
            for u in used:
                if p.startswith(u + '/') or u.startswith(p + '/'):
                    return True
        return False

    unused_roots = [l['display'] for l in registry
                    if '/' not in l['display'] and not layer_used(l)]
    unused_subs = [l for l in registry
                   if '/' in l['display'] and not layer_used(l)]
    if unused_roots:
        print('Calques du SVG jamais utilisés par la configuration :')
        for name in sorted(unused_roots):
            print('  - %s' % name)
    if unused_subs:
        print('(+ %d sous-éléments nommés jamais utilisés)'
              % len(unused_subs))
    print('%s : %d éléments, %d unités addressables.'
          % ('Vérification OK' if check_only else 'Terminé',
             len(elements), len(registry)))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except ValueError as exc:
        # Toutes les erreurs attendues de l'outil sont des ValueError
        # au message complet : les afficher sans traceback Python.
        print('ÉCHEC : %s' % exc, file=sys.stderr)
        sys.exit(1)
