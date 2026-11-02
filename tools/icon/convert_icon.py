#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""convert_icon.py — Génère l'icône de lancement de Scrambled Net 2 depuis
le SVG source (Inkscape), en 100 % vectoriel (aucun outil externe requis,
Python 3 standard suffit — outil gratuit).

Usage :
    python3 convert_icon.py <source.svg> <repertoire_res>
    (typiquement : python3 tools/icon/convert_icon.py svg/icon.svg
     app/src/main/res, à lancer depuis la racine du dépôt)

Produit, à partir du SVG (drawing transparent par conception) :
  - drawable/ic_launcher_foreground.xml   : premier plan adaptatif (dessin
    entier dans la zone sûre 66 dp du canevas 108 dp) ;
  - drawable/ic_launcher_monochrome.xml   : silhouette blanche (icônes
    thématiques Android 13+) ;
  - mipmap-anydpi-v26/ic_launcher.xml     : icône adaptative API 26+
    (fond @color/ic_launcher_background, blanc — les lanceurs ne laissent
    pas passer la transparence : OnePlus/Samsung compositent en noir,
    Pixel laisse voir le fond d'écran) ;
  - mipmap/ic_launcher.xml                : repli API 24-25 (VectorDrawable
    carré arrondi blanc + dessin à 82 %).

Le SVG source n'a volontairement aucun fond : le blanc est ajouté par les
couches ci-dessus. Modifier la couleur du fond = valeurs/colors.xml
(ic_launcher_background), la couche de repli étant régénérée par ce script.
"""
import math, re, sys
import xml.etree.ElementTree as ET

SVG = "http://www.w3.org/2000/svg"
XLINK = "http://www.w3.org/1999/xlink"
NS = {"s": SVG}

IDENT = (1, 0, 0, 1, 0, 0)

def apply(m, p):
    x, y = p
    return (m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5])

def compose(outer, inner):
    """p -> outer(inner(p))"""
    a1, b1, c1, d1, e1, f1 = outer
    a2, b2, c2, d2, e2, f2 = inner
    return (a1 * a2 + c1 * b2, b1 * a2 + d1 * b2,
            a1 * c2 + c1 * d2, b1 * c2 + d1 * d2,
            a1 * e2 + c1 * f2 + e1, b1 * e2 + d1 * f2 + f1)

def det(m):
    return m[0] * m[3] - m[1] * m[2]

_NUM = r"[-+]?(?:[0-9]*\.[0-9]+|[0-9]+\.?)(?:[eE][-+]?[0-9]+)?"

def parse_transform(s):
    m = IDENT
    if not s:
        return m
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", s):
        v = [float(x) for x in re.findall(_NUM, args)]
        if name == "matrix":
            t = (v[0], v[1], v[2], v[3], v[4], v[5])
        elif name == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "rotate":
            a = math.radians(v[0])
            ca, sa = math.cos(a), math.sin(a)
            t = (ca, sa, -sa, ca, 0, 0)
            if len(v) > 2:  # rotate(a,cx,cy) = T(c) . R(a) . T(-c)
                tc = (1, 0, 0, 1, v[1], v[2])
                t = compose(tc, t)
                t = compose(t, (1, 0, 0, 1, -v[1], -v[2]))
        elif name == "skewX":
            t = (1, 0, math.tan(math.radians(v[0])), 1, 0, 0)
        elif name == "skewY":
            t = (1, math.tan(math.radians(v[0])), 0, 1, 0, 0)
        else:
            raise ValueError("transform inconnu: " + name)
        m = compose(m, t)
    return m

STYLE_KEYS = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
              "stroke-opacity", "stroke-miterlimit", "fill-opacity", "fill-rule",
              "opacity", "display", "stroke-dasharray")

def parse_style(el):
    st = {}
    for k in STYLE_KEYS:
        v = el.get(k)
        if v is not None:
            st[k] = v
    for part in (el.get("style") or "").split(";"):
        part = part.strip()
        if ":" in part:
            k, v = part.split(":", 1)
            k, v = k.strip(), v.strip()
            if k in STYLE_KEYS:
                st[k] = v
    return st

def _tokens(d):
    i, n = 0, len(d)
    while i < n:
        c = d[i]
        if c in "MmLlHhVvCcSsQqTtAaZz":
            yield ("cmd", c); i += 1
        elif c in ", \t\r\n":
            i += 1
        else:
            j = i
            if d[j] in "+-":
                j += 1
            k = j
            while k < n and (d[k].isdigit() or d[k] == "."):
                k += 1
            if k < n and d[k] in "eE":
                k += 1
                if k < n and d[k] in "+-":
                    k += 1
                while k < n and d[k].isdigit():
                    k += 1
            yield ("num", float(d[i:k])); i = k

def arc_to_cubics(p0, rx, ry, phi, large, sweep, p1):
    """Arc elliptique -> cubics absolus (le VectorDrawable n'a pas d'arcs)."""
    if rx == 0 or ry == 0:
        return [(p0, p1, p1)]
    x0, y0 = p0; x1, y1 = p1
    cosp, sinp = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2, (y0 - y1) / 2
    x1p = cosp * dx2 + sinp * dy2
    y1p = -sinp * dx2 + cosp * dy2
    rx, ry = abs(rx), abs(ry)
    lam = x1p**2 / (rx**2) + y1p**2 / (ry**2)
    if lam > 1:
        sq = math.sqrt(lam); rx *= sq; ry *= sq
    num = rx**2 * ry**2 - rx**2 * y1p**2 - ry**2 * x1p**2
    den = rx**2 * y1p**2 + ry**2 * x1p**2
    f = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        f = -f
    cxp, cyp = f * rx * y1p / ry, -f * ry * x1p / rx
    cx = cosp * cxp - sinp * cyp + (x0 + x1) / 2
    cy = sinp * cxp + cosp * cyp + (y0 + y1) / 2
    def ang(ux, uy, vx, vy):
        dd = math.hypot(ux, uy) * math.hypot(vx, vy)
        c = max(-1, min(1, (ux * vx + uy * vy) / dd)) if dd else 1
        a = math.acos(c)
        return a if ux * vy - uy * vx >= 0 else -a
    th1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dth > 0:
        dth -= 2 * math.pi
    elif sweep and dth < 0:
        dth += 2 * math.pi
    segs = max(1, int(math.ceil(abs(dth) / (math.pi / 2))))
    delta = dth / segs
    t = 4 / 3 * math.tan(delta / 4)
    th = th1
    px, py = (cosp * rx * math.cos(th) - sinp * ry * math.sin(th) + cx,
              sinp * rx * math.cos(th) + cosp * ry * math.sin(th) + cy)
    out = []
    for _ in range(segs):
        th2 = th + delta
        d1x, d1y = (-rx * cosp * math.sin(th) - ry * sinp * math.cos(th),
                    -rx * sinp * math.sin(th) + ry * cosp * math.cos(th))
        d2x, d2y = (-rx * cosp * math.sin(th2) - ry * sinp * math.cos(th2),
                    -rx * sinp * math.sin(th2) + ry * cosp * math.cos(th2))
        qx, qy = (cosp * rx * math.cos(th2) - sinp * ry * math.sin(th2) + cx,
                  sinp * rx * math.cos(th2) + cosp * ry * math.sin(th2) + cy)
        out.append(((px + t * d1x, py + t * d1y), (qx - t * d2x, qy - t * d2y), (qx, qy)))
        th, px, py = th2, qx, qy
    return out

def parse_path(d):
    """-> commandes absolues ('M'|'L'|'C'|'Q'|'Z', points...)"""
    toks = list(_tokens(d))
    i, out = 0, []
    cur = start = (0.0, 0.0)
    ctrl = None
    cmd = None
    mv = False
    def nums(k):
        nonlocal i
        vals = []
        while len(vals) < k:
            if i < len(toks) and toks[i][0] == "num":
                vals.append(toks[i][1]); i += 1
            else:
                raise ValueError("nombre attendu")
        return vals
    while i < len(toks):
        if toks[i][0] == "cmd":
            cmd = toks[i][1]; i += 1
        if cmd is None:
            raise ValueError("path sans commande")
        C = cmd.upper()
        rel = cmd.islower()
        if C == "Z":
            out.append(("Z",)); cur = start; ctrl = None; mv = False
            cmd = None
        elif C == "M":
            x, y = nums(2)
            if rel: x += cur[0]; y += cur[1]
            cur = (x, y)
            if not mv:
                start = cur
            out.append(("M", cur)); ctrl = None; mv = True
        elif C == "H":
            (x,) = nums(1)
            cur = (x + (cur[0] if rel else 0), cur[1])
            out.append(("L", cur)); ctrl = None; mv = False
        elif C == "V":
            (y,) = nums(1)
            cur = (cur[0], y + (cur[1] if rel else 0))
            out.append(("L", cur)); ctrl = None; mv = False
        elif C == "L":
            x, y = nums(2)
            if rel: x += cur[0]; y += cur[1]
            cur = (x, y); out.append(("L", cur)); ctrl = None; mv = False
        elif C in "CQ":
            pts = nums(4 if C == "Q" else 6)
            if rel:
                pts = [pts[k] + (cur[0] if k % 2 == 0 else cur[1]) for k in range(len(pts))]
            if C == "Q":
                out.append(("Q", (pts[0], pts[1]), (pts[2], pts[3])))
                ctrl = (pts[0], pts[1])
            else:
                out.append(("C", (pts[0], pts[1]), (pts[2], pts[3]), (pts[4], pts[5])))
                ctrl = (pts[2], pts[3])
            cur = (pts[-2], pts[-1]); mv = False
        elif C in "ST":
            c1 = cur if ctrl is None else (2 * cur[0] - ctrl[0], 2 * cur[1] - ctrl[1])
            if C == "S":
                pts = nums(4)
                if rel:
                    pts = [pts[k] + (cur[0] if k % 2 == 0 else cur[1]) for k in range(4)]
                out.append(("C", c1, (pts[0], pts[1]), (pts[2], pts[3])))
                ctrl = (pts[0], pts[1])
            else:
                pts = nums(2)
                if rel:
                    pts = [pts[k] + (cur[0] if k % 2 == 0 else cur[1]) for k in range(2)]
                out.append(("Q", c1, (pts[0], pts[1])))
                ctrl = c1
            cur = (pts[-2], pts[-1]); mv = False
        elif C == "A":
            rx, ry, phi, laf, swf = nums(5)
            x, y = nums(2)
            if rel: x += cur[0]; y += cur[1]
            if laf not in (0.0, 1.0) or swf not in (0.0, 1.0):
                raise ValueError("flags d'arc invalides")
            for c in arc_to_cubics(cur, rx, ry, math.radians(phi), int(laf), int(swf), (x, y)):
                out.append(("C", c[0], c[1], c[2]))
            cur = (x, y); ctrl = None; mv = False
        else:
            raise ValueError("commande non geree: " + C)
    return out

K = 0.5522847498

def rect_to_cmds(x, y, w, h, rx, ry):
    if rx <= 0 and ry <= 0:
        return [("M", (x, y)), ("L", (x + w, y)), ("L", (x + w, y + h)),
                ("L", (x, y + h)), ("Z",)]
    if rx <= 0: rx = ry
    if ry <= 0: ry = rx
    rx = min(rx, w / 2); ry = min(ry, h / 2)
    return [
        ("M", (x + rx, y)),
        ("L", (x + w - rx, y)),
        ("C", (x + w - rx + K * rx, y), (x + w, y + ry - K * ry), (x + w, y + ry)),
        ("L", (x + w, y + h - ry)),
        ("C", (x + w, y + h - ry + K * ry), (x + w - rx + K * rx, y + h), (x + w - rx, y + h)),
        ("L", (x + rx, y + h)),
        ("C", (x + rx - K * rx, y + h), (x, y + h - ry + K * ry), (x, y + h - ry)),
        ("L", (x, y + ry)),
        ("C", (x, y + ry - K * ry), (x + rx - K * rx, y), (x + rx, y)),
        ("Z",),
    ]

class Grad:
    def __init__(self, el, elmap):
        self.type = el.tag.split("}")[1]
        self.gtf = parse_transform(el.get("gradientTransform") or "")
        g, self.stops = el, None
        while g is not None:
            stop_els = g.findall("s:stop", NS)
            if stop_els:
                self.stops = []
                for st in stop_els:
                    off = float(st.get("offset", "0"))
                    kv = {}
                    for part in (st.get("style") or "").split(";"):
                        if ":" in part:
                            kk, vv = part.split(":", 1)
                            kv[kk.strip()] = vv.strip()
                    col = kv.get("stop-color") or st.get("stop-color") or "#000000"
                    op = float(kv.get("stop-opacity") or st.get("stop-opacity") or 1)
                    self.stops.append((off, col, op))
                break
            href = g.get("{%s}href" % XLINK) or g.get("href")
            if href:
                href = href.split("#")[-1]
            g = elmap.get(href) if href else None
        if self.stops is None:
            raise ValueError("gradient sans stops")
        if self.type == "linearGradient":
            self.x1, self.y1 = float(el.get("x1", 0)), float(el.get("y1", 0))
            self.x2, self.y2 = float(el.get("x2", 0)), float(el.get("y2", 0))
        elif self.type == "radialGradient":
            self.cx, self.cy = float(el.get("cx", 0)), float(el.get("cy", 0))
            self.fx, self.fy = float(el.get("fx", el.get("cx", 0))), float(el.get("fy", el.get("cy", 0)))
            self.r = float(el.get("r", 0))
        else:
            raise ValueError("type de gradient non gere: " + self.type)

def hexa(color, opacity=1.0):
    c = color.strip()
    if not c.startswith("#"):
        raise ValueError("couleur non hex: " + color)
    c = c[1:]
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    if len(c) == 8:
        r, g, b, a = int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), int(c[6:8], 16)
    elif len(c) == 6:
        r, g, b = int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16); a = 255
    else:
        raise ValueError("couleur hex invalide: " + color)
    return "#%02X%02X%02X%02X" % (int(round(a * opacity)), r, g, b)

DRAW_TAGS = ("rect", "path", "circle", "ellipse", "polygon", "polyline", "line")

def collect(el, cum, out):
    tag = el.tag.split("}")[1]
    if tag in ("g", "svg"):
        m = compose(cum, parse_transform(el.get("transform") or ""))
        for ch in el:
            collect(ch, m, out)
        return
    if tag not in DRAW_TAGS:
        return
    st = parse_style(el)
    if st.get("display") == "none":
        return
    m = compose(cum, parse_transform(el.get("transform") or ""))
    if tag == "rect":
        x, y = float(el.get("x", 0)), float(el.get("y", 0))
        w, h = float(el.get("width")), float(el.get("height"))
        rx = float(el.get("rx", 0) or 0); ry = float(el.get("ry", 0) or 0)
        if el.get("rx") is None and el.get("ry") is not None: rx = ry
        if el.get("ry") is None and el.get("rx") is not None: ry = rx
        cmds = rect_to_cmds(x, y, w, h, rx, ry)
    elif tag == "path":
        cmds = parse_path(el.get("d"))
    elif tag in ("circle", "ellipse"):
        cx, cy = float(el.get("cx", 0)), float(el.get("cy", 0))
        rx = float(el.get("r", 0) or el.get("rx", 0)); ry = float(el.get("ry", rx) or rx)
        cmds = rect_to_cmds(cx - rx, cy - ry, 2 * rx, 2 * ry, rx, ry)
    elif tag == "line":
        cmds = [("M", (float(el.get("x1", 0)), float(el.get("y1", 0)))),
                ("L", (float(el.get("x2", 0)), float(el.get("y2", 0))))]
    else:
        pts = [(float(a), float(b)) for a, b in
               re.findall(r"(%s)\s*,\s*(%s)" % (_NUM, _NUM), el.get("points", ""))]
        if not pts:
            return
        cmds = [("M", pts[0])] + [("L", p) for p in pts[1:]]
        if tag == "polygon":
            cmds.append(("Z",))
    out.append({"cmds": cmds, "m": m, "style": st, "id": el.get("id", "?")})

def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def emit_cmds(cmds, m):
    parts = []
    for c in cmds:
        if c[0] == "Z":
            parts.append("Z")
        else:
            pts = [apply(m, p) for p in c[1:]]
            parts.append(c[0] + " " + " ".join(fmt(px) + "," + fmt(py) for px, py in pts))
    return " ".join(parts)

def gradient_block(grad, m_final, indent):
    M = compose(m_final, grad.gtf)
    ind = indent + "  "
    L = []
    if grad.type == "linearGradient":
        p1 = apply(M, (grad.x1, grad.y1)); p2 = apply(M, (grad.x2, grad.y2))
        L.append('%s<aapt:attr name="android:fillColor">' % ind)
        L.append('%s  <gradient' % ind)
        L.append('%s    android:type="linear"' % ind)
        L.append('%s    android:startX="%s" android:startY="%s"' % (ind, fmt(p1[0]), fmt(p1[1])))
        L.append('%s    android:endX="%s" android:endY="%s">' % (ind, fmt(p2[0]), fmt(p2[1])))
    else:
        c = apply(M, (grad.cx, grad.cy))
        sx = math.hypot(M[0], M[1]); sy = math.hypot(M[2], M[3])
        if abs(sx - sy) / max(sx, sy) > 0.02:
            print("ATTENTION radial non-uniforme (sx=%.4f sy=%.4f) -> r moyen" % (sx, sy),
                  file=sys.stderr)
        r = grad.r * math.sqrt(abs(det(M)))
        L.append('%s<aapt:attr name="android:fillColor">' % ind)
        L.append('%s  <gradient' % ind)
        L.append('%s    android:type="radial"' % ind)
        L.append('%s    android:centerX="%s" android:centerY="%s"' % (ind, fmt(c[0]), fmt(c[1])))
        L.append('%s    android:gradientRadius="%s" android:startX="%s" android:startY="%s">'
                 % (ind, fmt(r), fmt(c[0]), fmt(c[1])))
    for off, col, op in grad.stops:
        L.append('%s    <item android:offset="%s" android:color="%s"/>'
                  % (ind, ("%.6g" % off), hexa(col, op)))
    L.append('%s  </gradient>' % ind)
    L.append('%s</aapt:attr>' % ind)
    return L

def scale_of(m):
    return math.sqrt(abs(det(m)))

def emit_vector(items, grads, transform_hook, flat=False, header_comment=None,
                width="108dp", viewport=108.0):
    out = ["<?xml version=\"1.0\" encoding=\"utf-8\"?>"]
    if header_comment:
        out.extend(header_comment)
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    out.append('    xmlns:aapt="http://schemas.android.com/aapt"')
    out.append('    android:width="%s"' % width)
    out.append('    android:height="%s"' % width)
    out.append('    android:viewportWidth="%s"' % fmt(viewport))
    out.append('    android:viewportHeight="%s">' % fmt(viewport))
    n_grad = 0
    for it in items:
        st = it["style"]
        m = transform_hook(it["m"])
        fill = st.get("fill", "black")
        path_attrs = []
        if st.get("fill-rule") == "evenodd":
            path_attrs.append('android:fillType="evenOdd"')
        if flat:
            if fill not in ("none", "None"):
                path_attrs.append('android:fillColor="#FFFFFFFF"')
        elif fill.startswith("url"):
            pass
        elif fill != "none":
            path_attrs.append('android:fillColor="%s"'
                              % hexa(fill, float(st.get("fill-opacity", "1") or 1)))
        stroke = st.get("stroke")
        has_stroke = stroke and stroke not in ("none", "None")
        if has_stroke:
            sw = float(st.get("stroke-width", "1") or 1) * scale_of(m)
            if flat:
                path_attrs.append('android:strokeColor="#FFFFFFFF"')
            else:
                path_attrs.append('android:strokeColor="%s"'
                                  % hexa(stroke, float(st.get("stroke-opacity", "1") or 1)))
            path_attrs.append('android:strokeWidth="%s"' % fmt(sw))
            if st.get("stroke-linecap"):
                path_attrs.append('android:strokeLineCap="%s"' % st["stroke-linecap"])
            if st.get("stroke-linejoin"):
                path_attrs.append('android:strokeLineJoin="%s"' % st["stroke-linejoin"])
        out.append("  <path")
        for a in path_attrs:
            out.append("      " + a)
        out.append('      android:pathData="%s">' % emit_cmds(it["cmds"], m))
        if (not flat) and fill.startswith("url"):
            gid = re.match(r"url\(#([^)]+)\)", fill).group(1)
            out.extend(gradient_block(grads[gid], m, "      "))
            n_grad += 1
        out.append("  </path>")
    out.append("</vector>")
    print("  paths: %d, gradients: %d" % (len(items), n_grad))
    return "\n".join(out) + "\n"

ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Icone adaptative (API 26+). Le fond doit etre OPAQUE : les lanceurs ne
     laissent pas passer la transparence (OnePlus/Samsung compositent en
     noir, Pixel laisse voir le fond d'ecran). Fond blanc par decision du
     projet (l'icon SVG source est dessinee sur transparent). La couche
     monochrome sert aux icones thematiques Android 13+ ; les versions
     anterieures l'ignorent. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src, res = sys.argv[1], sys.argv[2]
    root = ET.parse(src).getroot()
    defs_el = root.find("s:defs", NS)
    elmap = {g.get("id"): g for g in defs_el if g.get("id")}
    items = []
    for ch in root:
        if ch.tag.split("}")[1] == "g":
            collect(ch, IDENT, items)
    print("elements dessines:", len(items))
    used = set()
    for it in items:
        m = re.match(r"url\(#([^)]+)\)", it["style"].get("fill", ""))
        if m:
            used.add(m.group(1))
    grads = {}
    for gid in used:
        g = elmap.get(gid)
        if g is None:
            raise ValueError("gradient absent du defs: " + gid)
        grads[gid] = Grad(g, elmap)
    print("gradients utilises:", len(grads))

    xs, ys = [], []
    for it in items:
        for c in it["cmds"]:
            for p in c[1:]:
                q = apply(it["m"], p)
                xs.append(q[0]); ys.append(q[1])
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    w, h = x1 - x0, y1 - y0
    ccx, ccy = (x0 + x1) / 2, (y0 + y1) / 2
    print("bbox dessin: %.2fx%.2f (diag %.2f)" % (w, h, math.hypot(w, h)))

    import os
    drawable = os.path.join(res, "drawable")
    mipmap = os.path.join(res, "mipmap")
    mipmap26 = os.path.join(res, "mipmap-anydpi-v26")
    for d in (drawable, mipmap, mipmap26):
        os.makedirs(d, exist_ok=True)

    # 1. premier plan adaptatif : dessin entier dans la zone sure 66 dp
    s = 64.0 / math.hypot(w, h)
    def fit_fg(m):
        return compose((s, 0, 0, s, 54 - s * ccx, 54 - s * ccy), m)
    print("foreground:")
    open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w").write(emit_vector(
        items, grads, fit_fg, flat=False,
        header_comment=["<!-- Premier plan de l'icone adaptative. Genere depuis le SVG",
                        "     source par tools/icon/convert_icon.py (dessin entier dans",
                        "     la zone sure de 66 dp). -->"]))
    print("monochrome:")
    open(os.path.join(drawable, "ic_launcher_monochrome.xml"), "w").write(emit_vector(
        items, grads, fit_fg, flat=True,
        header_comment=["<!-- Couche monochrome des icones thematiques (Android 13+) :",
                        "     silhouette blanche du premier plan. Genere par",
                        "     tools/icon/convert_icon.py. -->"]))

    # 2. repli API 24-25 : carre arrondi blanc + dessin a 82 %
    ls = (226.77165 * 0.82) / max(w, h)
    def fit_legacy(m):
        return compose((ls, 0, 0, ls, 113.386 - ls * ccx, 113.386 - ls * ccy), m)
    legacy_items = [{"cmds": rect_to_cmds(0, 0, 226.77165, 226.77165, 18.9, 18.9),
                     "m": IDENT, "id": "bg",
                     "style": {"fill": "#FFFFFF", "fill-opacity": "1"}}] + items
    print("repli (fond blanc):")
    open(os.path.join(mipmap, "ic_launcher.xml"), "w").write(emit_vector(
        legacy_items, grads, fit_legacy, flat=False,
        width="48dp", viewport=226.77165,
        header_comment=["<!-- Repli API 24-25 : le VectorDrawable sert directement",
                        "     d'icone de lancement (fond blanc opaque, comme",
                        "     l'icone adaptative). Genere par tools/icon/convert_icon.py.",
                        "     -->"]))

    # 3. icone adaptative (statique, reference la couleur du fond)
    open(os.path.join(mipmap26, "ic_launcher.xml"), "w").write(ADAPTIVE)
    print("adaptive: mipmap-anydpi-v26/ic_launcher.xml")

    # 4. verifier la couleur du fond
    colors_path = os.path.join(res, "values", "colors.xml")
    if os.path.exists(colors_path):
        c = open(colors_path).read()
        if 'name="ic_launcher_background"' not in c:
            print("ATTENTION : ajouter dans values/colors.xml :", file=sys.stderr)
            print('    <color name="ic_launcher_background">#FFFFFFFF</color>',
                  file=sys.stderr)
    print("OK")

if __name__ == "__main__":
    main()
