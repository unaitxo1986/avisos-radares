# Uso: python3 app/herramientas/generar_icono.py app/app/src/main/res  (escribe los drawables y deja vistas previas PNG aquí)
# Icono: cámara de radar con luces de policía (rojo y azul) y ondas, sobre fondo oscuro con resplandor rojo/azul.
# Genera los vector drawables de Android y una vista previa en SVG/PNG con las máscaras redonda y squircle.
import math, sys
def circ(cx,cy,r): return f"M{cx-r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0 a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0 Z"
def rrect(x1,y1,x2,y2,r):
    return (f"M{x1+r},{y1} L{x2-r},{y1} Q{x2},{y1} {x2},{y1+r} L{x2},{y2-r} Q{x2},{y2} {x2-r},{y2} "
            f"L{x1+r},{y2} Q{x1},{y2} {x1},{y2-r} L{x1},{y1+r} Q{x1},{y1} {x1+r},{y1} Z")
def arco(cx,cy,r,ang):
    a=math.radians(ang); x=cx+r*math.cos(a); y1=cy-r*math.sin(a); y2=cy+r*math.sin(a)
    return f"M{x:.2f},{y1:.2f} A{r},{r} 0 0,1 {x:.2f},{y2:.2f}"

ROJO="#FF2E3A"; AZUL="#2F7BFF"; CIAN="#00E5FF"; BLANCO="#F4F4F8"; OSCURO="#15151D"
# Fondo: base oscura + resplandor rojo (arriba a la izquierda) y azul (arriba a la derecha).
fondo=[
 dict(d="M0,0 H108 V108 H0 Z", grad=("radial",54,58,70,[("#24242F",0),("#0A0A0F",1)])),
 dict(d="M0,0 H108 V108 H0 Z", grad=("radial",26,30,46,[("#99FF2E3A",0),("#00FF2E3A",1)])),
 dict(d="M0,0 H108 V108 H0 Z", grad=("radial",82,30,46,[("#992F7BFF",0),("#002F7BFF",1)])),
]
cx=47; cy=60
frente=[
 # Poste y base
 dict(d=rrect(43.5,70,50.5,82,1.5), fill="#8B8B9C"),
 dict(d=rrect(36,80,58,84,2), fill="#8B8B9C"),
 # Luces de policía sobre la cámara
 dict(d=rrect(29,36.5,46,44,3.5), fill=ROJO),
 dict(d=rrect(48,36.5,65,44,3.5), fill=AZUL),
 dict(d=rrect(31,37.5,38,39.5,1), fill="#80FFFFFF"),
 dict(d=rrect(50,37.5,57,39.5,1), fill="#80FFFFFF"),
 # Cuerpo de la cámara
 dict(d=rrect(26,46,68,72,6), fill=BLANCO),
 dict(d=rrect(26,64,68,72,0) , fill="#D9D9E3"),
 dict(d=rrect(26,46,68,72,6), stroke="#0A0A0F", sw=0),
 # Objetivo
 dict(d=circ(cx,59,10.5), fill=OSCURO),
 dict(d=circ(cx,59,6.8), fill=ROJO),
 dict(d=circ(cx,59,3.2), fill="#7A0010"),
 dict(d=circ(cx-2.6,56.4,1.7), fill="#E6FFFFFF"),
 # Flash
 dict(d=rrect(58,50,64,54,1.5), fill=CIAN),
 # Ondas del radar
 dict(d=arco(68,59,6,42), stroke=CIAN, sw=3.4),
 dict(d=arco(68,59,12,42), stroke=CIAN, sw=3.4),
 dict(d=arco(68,59,18,42), stroke=CIAN, sw=3.4),
]
# Quitar la tira inferior con esquinas rectas: cuerpo en dos tonos con la parte baja redondeada
frente[7]=dict(d=f"M26,64 H68 V66 Q68,72 62,72 H32 Q26,72 26,66 Z", fill="#D9D9E3")
del frente[8]

def argb_to_svg(c):
    if len(c)==9: a=int(c[1:3],16)/255; return "#"+c[3:], a
    return c,1.0

TX,TY,ESC=-3,-4,0.9
def svg(capas, mascara=None, tam=432):
    defs=[]; cuerpo=[]; n=0
    for ic,capa in enumerate(capas):
        if ic==1: cuerpo.append(f'<g transform="translate({54+TX},{54+TY}) scale({ESC}) translate(-54,-54)">')
        for e in capa:
            n+=1
            if 'grad' in e:
                _,gx,gy,gr,stops=e['grad']
                st="".join(f'<stop offset="{o}" stop-color="{argb_to_svg(c)[0]}" stop-opacity="{argb_to_svg(c)[1]:.2f}"/>' for c,o in stops)
                defs.append(f'<radialGradient id="g{n}" gradientUnits="userSpaceOnUse" cx="{gx}" cy="{gy}" r="{gr}">{st}</radialGradient>')
                cuerpo.append(f'<path d="{e["d"]}" fill="url(#g{n})"/>')
            elif 'fill' in e:
                c,a=argb_to_svg(e['fill']); cuerpo.append(f'<path d="{e["d"]}" fill="{c}" fill-opacity="{a:.2f}"/>')
            elif e.get('sw',0)>0:
                cuerpo.append(f'<path d="{e["d"]}" fill="none" stroke="{e["stroke"]}" stroke-width="{e["sw"]}" stroke-linecap="round"/>')
        if ic==1: cuerpo.append('</g>')
    clip=""
    if mascara=="redonda": defs.append('<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath>'); clip=' clip-path="url(#m)"'
    if mascara=="squircle": defs.append('<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="22"/></clipPath>'); clip=' clip-path="url(#m)"'
    # Android muestra solo los 72 dp centrales (18 dp de margen por lado)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{tam}" height="{tam}" viewBox="18 18 72 72"><defs>{"".join(defs)}</defs>'
            f'<g{clip}>{"".join(cuerpo)}</g></svg>')

def vector(capa, grupo=False):
    out=['<?xml version="1.0" encoding="utf-8"?>',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"',
         '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">']
    if grupo:
        out.append(f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{ESC}" android:scaleY="{ESC}" android:translateX="{TX}" android:translateY="{TY}">')
    for e in capa:
        if 'grad' in e:
            _,gx,gy,gr,stops=e['grad']
            out.append(f'    <path android:pathData="{e["d"]}">')
            out.append('        <aapt:attr name="android:fillColor">')
            out.append(f'            <gradient android:type="radial" android:centerX="{gx}" android:centerY="{gy}" android:gradientRadius="{gr}">')
            for c,o in stops: out.append(f'                <item android:offset="{o}" android:color="{c}" />')
            out.append('            </gradient>')
            out.append('        </aapt:attr>')
            out.append('    </path>')
        elif 'fill' in e:
            par=' android:fillType="evenOdd"' if e.get('evenodd') else ''
            out.append(f'    <path android:fillColor="{e["fill"]}"{par} android:pathData="{e["d"]}" />')
        elif e.get('sw',0)>0:
            out.append(f'    <path android:strokeColor="{e["stroke"]}" android:strokeWidth="{e["sw"]}" android:strokeLineCap="round" android:pathData="{e["d"]}" />')
    if grupo: out.append('    </group>')
    out.append('</vector>')
    return "\n".join(out)+"\n"

def monocromo(capa):
    # Silueta de un solo color (iconos temáticos de Android 13 y notificación): el objetivo y el flash son huecos.
    B="#FFFFFFFF"
    m=[dict(d=rrect(43.5,70,50.5,82,1.5), fill=B), dict(d=rrect(36,80,58,84,2), fill=B),
       dict(d=rrect(29,36.5,46,44,3.5), fill=B), dict(d=rrect(48,36.5,65,44,3.5), fill=B),
       dict(d=rrect(26,46,68,72,6)+" "+circ(cx,59,10.5)+" "+rrect(58,50,64,54,1.5), fill=B, evenodd=True),
       dict(d=circ(cx,59,6.8), fill=B)]
    m+=[dict(e, stroke=B) for e in capa if e.get('sw',0)>0]
    return vector(m, grupo=True)

if __name__=="__main__":
    import cairosvg
    for masc in ["redonda","squircle"]:
        cairosvg.svg2png(bytestring=svg([fondo,frente],masc).encode(), write_to=f"vista_{masc}.png")
    cairosvg.svg2png(bytestring=svg([fondo,frente],"redonda",96).encode(), write_to="vista_pequena.png")
    if len(sys.argv)>1:
        d=sys.argv[1]
        open(f"{d}/drawable/ic_lanzador_fondo.xml","w").write(vector(fondo))
        open(f"{d}/drawable/ic_lanzador_frente.xml","w").write(vector(frente, grupo=True))
        open(f"{d}/drawable/ic_lanzador_monocromo.xml","w").write(monocromo(frente))
