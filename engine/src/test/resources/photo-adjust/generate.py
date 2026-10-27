"""Synthetic-only oracle; run with Pillow==11.3.0. No personal images."""
from pathlib import Path
from PIL import Image, ImageFilter, ImageEnhance
import PIL, hashlib
assert PIL.__version__ == '11.3.0'
root=Path(__file__).parent
files=[]
for w,h in [(1,1),(2,3),(17,13),(128,128)]:
    raw=bytes(((x*37+y*61+c*83+(x*y*17))%256) for y in range(h) for x in range(w) for c in range(3))
    image=Image.frombytes('RGB',(w,h),raw)
    stem=f'{w}x{h}'
    outputs={'input':raw}
    for size in (3,5,7): outputs[f'median{size}']=image.filter(ImageFilter.MedianFilter(size)).tobytes()
    for factor in (0.5,0.73,1.0,1.3,2.0): outputs[f'contrast{factor}']=ImageEnhance.Contrast(image).enhance(factor).tobytes()
    outputs['combined']=ImageEnhance.Contrast(image.filter(ImageFilter.MedianFilter(5))).enhance(1.3).tobytes()
    for name,data in outputs.items():
        filename=f'{stem}-{name}.rgb';(root/filename).write_bytes(data)
        files.append(f'{hashlib.sha256(data).hexdigest()}  {filename}')
(root/'SHA256SUMS').write_text('\n'.join(files)+'\n')
print(f'PILLOW {PIL.__version__}: {len(files)} synthetic RGB fixtures generated')
