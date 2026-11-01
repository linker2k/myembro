"""Dump png2svg_claude_v2.convert() color labels (unchanged steps, stopped before SVG smoothing).

Usage: python reference_labels.py input.png out_prefix [size_mm=60] [maxcolors=6]
Writes out_prefix.json (sample-independent facts) and out_prefix-*.bin (int32 little endian):
sample indices, first k-means index, final labels (-1 background) and palette RGB.
"""
import sys, json, numpy as np
from PIL import Image
from scipy import ndimage as ndi
from skimage import color
import png2svg_claude_v2 as v2

def labels(src, maxcolors=6, size_mm=60.0, merge_de=18.0):
    rgb=np.asarray(Image.open(src).convert('RGB'))
    h,w,_=rgb.shape
    lab=color.rgb2lab(rgb/255.0).reshape(-1,3)
    sample_index=np.random.default_rng(1).choice(len(lab),40000,replace=False)
    sample=lab[sample_index]
    first=int(np.random.default_rng(0).integers(len(sample)))
    C=v2.kmeans(sample,10)
    counts=np.bincount(((sample[:,None]-C[None])**2).sum(-1).argmin(1),minlength=len(C)).astype(float)
    C=list(C); counts=list(counts)
    C,counts=v2.fold_sparse_clusters(C,counts,len(sample))
    def closest():
        best=None
        for i in range(len(C)):
            for j in range(i+1,len(C)):
                d=np.linalg.norm(C[i]-C[j])
                if best is None or d<best[0]: best=(d,i,j)
        return best
    while True:
        d,i,j=closest()
        if len(C)<=maxcolors+1 and d>=merge_de: break
        keep,drop=(i,j) if counts[i]>=counts[j] else (j,i)
        counts[keep]+=counts[drop]; del C[drop]; del counts[drop]
    C=np.array(C)
    labels=np.empty(h*w,int)
    for s in range(0,h*w,65536): labels[s:s+65536]=((lab[s:s+65536,None]-C[None])**2).sum(-1).argmin(1)
    labels=labels.reshape(h,w)
    white=int(np.argmax(C[:,0]))
    comp,_=ndi.label(labels==white)
    border=set(np.unique(np.concatenate([comp[0],comp[-1],comp[:,0],comp[:,-1]])))-{0}
    bg=np.isin(comp,list(border))
    labels[bg]=-1
    fg=~bg
    ys,xs=np.nonzero(fg); x0,x1,y0,y1=xs.min(),xs.max()+1,ys.min(),ys.max()+1
    s=size_mm/max(x1-x0,y1-y0)
    min_area=np.pi*(1.0/s)**2
    for _ in range(3):
        changed=False
        for c in range(len(C)):
            cl,n=ndi.label(labels==c)
            if n==0: continue
            areas=ndi.sum(np.ones_like(cl),cl,range(1,n+1))
            for idx in np.nonzero(areas<min_area)[0]:
                m=cl==idx+1
                ring=ndi.binary_dilation(m,iterations=2)&~m
                nb=labels[ring]; nb=nb[nb!=c]
                labels[m]=np.bincount(nb+1).argmax()-1 if len(nb) else -1
                changed=True
        if not changed: break
    present=[c for c in range(len(C)) if (labels==c).any()]
    order=sorted(present,key=lambda c:-(labels==c).sum())
    remap={c:k for k,c in enumerate(order)}
    out=np.vectorize(lambda v: remap.get(v,-1))(labels) if len(order) else labels
    palette=[]
    for c in order:
        r,g,b=(np.clip(color.lab2rgb(C[c][None,None])[0,0],0,1)*255).round().astype(int)
        palette.append(int(r)<<16|int(g)<<8|int(b))
    return w,h,s,sample_index,first,out,palette

if __name__=='__main__':
    src,prefix=sys.argv[1],sys.argv[2]
    size=float(sys.argv[3]) if len(sys.argv)>3 else 60.0
    maxc=int(sys.argv[4]) if len(sys.argv)>4 else 6
    w,h,s,si,first,lab,pal=labels(src,maxc,size)
    si.astype('<i4').tofile(prefix+'-sample.bin'); lab.astype('<i4').ravel().tofile(prefix+'-labels.bin')
    json.dump({'width':w,'height':h,'mm_per_px':s,'first':first,'palette':pal,'maxcolors':maxc},open(prefix+'.json','w'))
