"""Original geometric brand artwork; no downloaded imagery or font files are distributed."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'store-assets'
OUT.mkdir(exist_ok=True)
FOREST = '#173F35'
CREAM = '#F5F2E9'
SUN = '#F8B95E'
CLAY = '#DF794A'

def font(size, bold=False):
    candidates = [Path('C:/Windows/Fonts') / ('segoeuib.ttf' if bold else 'segoeui.ttf'), Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default(size=size)

def icon(size=512):
    image = Image.new('RGBA', (size, size), FOREST)
    draw = ImageDraw.Draw(image)
    scale = size / 108
    def point(x, y): return (round(x * scale), round(y * scale))
    draw.ellipse([point(66, 21), point(86, 41)], fill=SUN)
    draw.polygon([point(18,78),point(46,31),point(61,57),point(70,43),point(91,78)], fill=CREAM)
    draw.line([point(42,78),point(49,64),point(45,57),point(50,47)], fill=CLAY, width=round(4*scale), joint='curve')
    return image

icon().save(OUT / 'icon-512.png')
feature = Image.new('RGB', (1024,500), CREAM)
d = ImageDraw.Draw(feature)
d.rectangle([620,0,1024,500], fill=FOREST)
for shift in range(-120,340,48):
    d.arc([610+shift,20,1150+shift,620], 165,340,fill='#315A4F',width=2)
d.ellipse([833,67,927,161],fill=SUN)
d.polygon([(661,390),(785,158),(861,288),(911,220),(1017,390)],fill=CREAM)
d.line([(777,390),(802,331),(785,290),(805,249)],fill=CLAY,width=12,joint='curve')
d.text((54,56),'CragLight',font=font(52,True),fill=FOREST)
d.text((54,151),'Good days',font=font(58,True),fill=FOREST)
d.text((54,222),'start outside.',font=font(58,True),fill=FOREST)
d.text((56,330),'UK climbing. Open knowledge.',font=font(25),fill=FOREST)
d.text((56,379),'Offline maps  /  Sun & weather  /  Your logbook',font=font(20),fill=FOREST)
feature.save(OUT / 'feature-graphic-1024x500.png')
print('Created 512px icon and 1024x500 feature graphic.')
