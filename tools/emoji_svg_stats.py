# SPDX-License-Identifier: GPL-3.0-or-later
"""Color/feature distribution of sampled emoji SVGs: python3 -I tools/emoji_svg_stats.py <dl dir with noto/ fluent-flat/ twemoji/ openmoji/>."""
import sys,os,re,statistics
root=sys.argv[1]
def norm(c):
    c=c.lower()
    if re.fullmatch(r'#[0-9a-f]{3}',c): c='#'+''.join(ch*2 for ch in c[1:])
    return c
for k in ['noto','fluent-flat','twemoji','openmoji']:
    d=os.path.join(root,k); colors=[];grad=0;stroke=0;opac=0;mask=0;n=0;filt=0;paths=[]
    for f in sorted(os.listdir(d)):
        s=open(os.path.join(d,f),encoding='utf-8',errors='replace').read(); n+=1
        cs={norm(c) for c in re.findall(r'(?:fill|stroke|stop-color)\s*[=:]\s*"?\s*(#[0-9a-fA-F]{3,6})\b',s)}
        colors.append(len(cs))
        if re.search(r'Gradient',s): grad+=1
        if re.search(r'stroke\s*=\s*"(?!none)|stroke\s*:\s*(?!none)#',s): stroke+=1
        if re.search(r'opacity',s): opac+=1
        if re.search(r'<(mask|clipPath)',s): mask+=1
        if re.search(r'<filter',s): filt+=1
        paths.append(len(re.findall(r'<(path|circle|ellipse|rect|polygon|polyline|line)\b',s)))
    q=sorted(colors)
    print(f"{k}\tn={n}\tcolors median={statistics.median(q)} p90={q[int(n*.9)]} max={q[-1]} <=6:{sum(c<=6 for c in q)*100//n}% <=12:{sum(c<=12 for c in q)*100//n}%\tgradient={grad*100//n}%\tstroke={stroke*100//n}%\topacity={opac*100//n}%\tmask/clip={mask*100//n}%\tfilter={filt*100//n}%\tshapes median={statistics.median(paths)}")
