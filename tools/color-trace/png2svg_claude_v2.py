import sys, numpy as np
from PIL import Image
from scipy import ndimage as ndi
from skimage import color, measure

def kmeans(X, k, iters=30, seed=0):
    rng=np.random.default_rng(seed)
    C=[X[rng.integers(len(X))]]
    for _ in range(k-1):  # farthest-point init keeps small distinct colors
        d=np.min(((X[:,None,:]-np.array(C)[None])**2).sum(-1),1); C.append(X[d.argmax()])
    C=np.array(C)
    for _ in range(iters):
        lab=((X[:,None,:]-C[None])**2).sum(-1).argmin(1)
        C=np.array([X[lab==j].mean(0) if (lab==j).any() else C[j] for j in range(len(C))])
    return C

def fold_sparse_clusters(centers,counts,sample_count,trace=None):
    # User-approved fix: fold clusters below0.5% of sampled pixels before closest-pair merging.
    centers=list(centers);counts=list(counts)
    while len(centers)>1:
        small=[i for i,n in enumerate(counts) if n<sample_count*0.005]
        if not small:break
        drop=min(small,key=lambda i:(counts[i],i))
        keep=min((i for i in range(len(centers)) if i!=drop),key=lambda i:(float(np.linalg.norm(centers[drop]-centers[i])),i))
        if trace is not None:trace.append({'phase':'sparse_prepass','drop_lab':np.asarray(centers[drop]).tolist(),'drop_count':float(counts[drop]),'keep_lab':np.asarray(centers[keep]).tolist(),'keep_count_before':float(counts[keep])})
        counts[keep]+=counts[drop];del centers[drop];del counts[drop]
    return centers,counts

def convert(src, dst, maxcolors=6, size_mm=60.0, merge_de=18.0):
    rgb=np.asarray(Image.open(src).convert('RGB'))
    h,w,_=rgb.shape
    lab=color.rgb2lab(rgb/255.0).reshape(-1,3)
    sample=lab[np.random.default_rng(1).choice(len(lab),40000,replace=False)]
    C=kmeans(sample,10)
    counts=np.bincount(((sample[:,None]-C[None])**2).sum(-1).argmin(1),minlength=len(C)).astype(float)
    C=list(C); counts=list(counts)
    C,counts=fold_sparse_clusters(C,counts,len(sample))
    def closest():
        best=None
        for i in range(len(C)):
            for j in range(i+1,len(C)):
                d=np.linalg.norm(C[i]-C[j])
                if best is None or d<best[0]: best=(d,i,j)
        return best
    # +1 slot for the background white, which is not drawn
    while True:
        d,i,j=closest()
        if len(C)<=maxcolors+1 and d>=merge_de: break
        keep,drop=(i,j) if counts[i]>=counts[j] else (j,i)   # blends fold into the bigger color
        counts[keep]+=counts[drop]; del C[drop]; del counts[drop]
    C=np.array(C)
    labels=np.empty(h*w,int)
    for s in range(0,h*w,65536): labels[s:s+65536]=((lab[s:s+65536,None]-C[None])**2).sum(-1).argmin(1)
    labels=labels.reshape(h,w)
    white=int(np.argmax(C[:,0]))
    # background = white-cluster pixels connected to the border
    comp,_=ndi.label(labels==white)
    border=set(np.unique(np.concatenate([comp[0],comp[-1],comp[:,0],comp[:,-1]])))-{0}
    bg=np.isin(comp,list(border))
    labels[bg]=-1
    fg=~bg
    ys,xs=np.nonzero(fg); x0,x1,y0,y1=xs.min(),xs.max()+1,ys.min(),ys.max()+1
    s=size_mm/max(x1-x0,y1-y0)  # mm per px
    ox=50-(x0+x1)/2*s; oy=50-(y0+y1)/2*s
    # reassign pieces under 2mm diameter to their surrounding color
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
    paths=[]
    for k,c in enumerate(order):
        region=labels==c
        later=np.isin(labels,order[k+1:])
        holes=ndi.binary_fill_holes(region)&~region
        m=region|(holes&later)
        m=ndi.binary_dilation(m,iterations=1)&(labels!=-1) | m
        sm=ndi.gaussian_filter(np.pad(m,4).astype(float),1.2)
        d=[]
        for cont in measure.find_contours(sm,0.5):
            poly=measure.approximate_polygon(cont,0.6)
            if len(poly)<4: continue
            pts=[(ox+(px-4)*s,oy+(py-4)*s) for py,px in poly[:-1]]
            d.append("M"+" L".join(f"{x:.2f},{y:.2f}" for x,y in pts)+"Z")
        r,g,b=(np.clip(color.lab2rgb(C[c][None,None])[0,0],0,1)*255).round().astype(int)
        paths.append(f'<path fill="#{r:02x}{g:02x}{b:02x}" fill-rule="evenodd" d="{" ".join(d)}"/>')
    svg=('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100mm" height="100mm">\n'
         +"\n".join(paths)+"\n</svg>\n")
    open(dst,"w").write(svg)
    return len(paths)

def _convert_file(job):
    import time
    src,dst,size,fit=job
    from png2svg_size import convert_sized
    started=time.perf_counter()
    colors,layout_info=convert_sized(sys.modules[__name__],src,dst,size,fit)
    return {'name':src.stem,'seconds':time.perf_counter()-started,'path_count':colors,'layout':layout_info}

if __name__=="__main__":
    import argparse,time,json,os
    from pathlib import Path
    from concurrent.futures import ProcessPoolExecutor
    parser=argparse.ArgumentParser(description='Claude v2: sparse clusters below0.5% folded first; remaining defaults unchanged. contact*.png excluded.')
    parser.add_argument('input_folder',type=Path)
    parser.add_argument('output_folder',type=Path)
    from png2svg_size import add_size_arguments
    add_size_arguments(parser)
    args=parser.parse_args();source=args.input_folder.resolve();dest=args.output_folder.resolve();dest.mkdir(parents=True,exist_ok=True)
    inputs=sorted(p for p in source.glob('*.png') if not p.stem.lower().startswith('contact'))
    if not inputs:parser.error('No artwork PNGs')
    workers=min(4,os.cpu_count() or 1,len(inputs));started=time.perf_counter()
    with ProcessPoolExecutor(max_workers=workers) as pool:
        records=list(pool.map(_convert_file,[(p,dest/(p.stem+'.svg'),args.size_mm,args.fit) for p in inputs]))
    for row in records:print(row,flush=True)
    (dest/('conversion-times-'+source.name+'.json')).write_text(json.dumps({'workers':workers,'wall_seconds':time.perf_counter()-started,'images':records},indent=2)+'\n')
    print('Converted',len(records),'files',flush=True)
