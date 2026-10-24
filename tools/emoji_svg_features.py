# SPDX-License-Identifier: GPL-3.0-or-later
"""SVG feature usage (transform kinds, elements, fill-rule, opacity) over a folder: python3 -I tools/emoji_svg_features.py <dir>."""
import sys,os,re,collections
d=sys.argv[1]; n=0; files=collections.Counter(); tags=collections.Counter(); kinds=collections.Counter()
for f in sorted(os.listdir(d)):
    s=open(os.path.join(d,f),encoding='utf-8',errors='replace').read(); n+=1
    for t in set(re.findall(r'<([a-zA-Z]+)\b',s)): tags[t]+=1
    if 'transform=' in s: files['transform']+=1
    for k in re.findall(r'transform="\s*([a-z]+)\(',s): kinds[k]+=1
    if re.search(r'fill-rule="evenodd"',s): files['fill-rule evenodd']+=1
    if re.search(r'(fill-)?opacity=',s): files['opacity']+=1
    if re.search(r'<(circle|ellipse|rect)\b',s): files['basic shapes']+=1
    if 'xlink:href' in s or '<use' in s: files['use/href']+=1
print('files',n)
for k,v in files.most_common(): print(f'{k}: {v} ({v*100/n:.1f}%)')
print('transform kinds:',dict(kinds))
print('elements (files containing):',dict(tags.most_common()))
