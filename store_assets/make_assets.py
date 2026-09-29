# Generates all Dosely Play Store static assets from raw device screenshots.
# Outputs 1080x1920 captioned screenshots, 512 icon, 1024x500 feature graphic,
# and intro/outro frames for the promo video.
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(ROOT, "raw")
SHOT = os.path.join(ROOT, "screenshots")
VID = os.path.join(ROOT, "video", "frames")

# Brand palette (from app/src/main/java/com/dosely/app/ui/theme/Color.kt)
FOREST = (14, 59, 46)
FOREST_DEEP = (10, 44, 34)
MINT = (123, 228, 149)
MINT_STRONG = (62, 207, 116)
SAGE = (191, 232, 206)
CREAM = (247, 245, 239)
INK = (20, 35, 29)
CORAL = (255, 138, 122)
AMBER = (255, 196, 107)

FONT_B = "C:/Windows/Fonts/segoeuib.ttf"    # Segoe UI Bold
FONT_SB = "C:/Windows/Fonts/segoeuisl.ttf"  # Segoe UI Semilight fallback
FONT_R = "C:/Windows/Fonts/segoeui.ttf"     # Segoe UI Regular


def font(path, size):
    return ImageFont.truetype(path, size)


def rounded(draw, box, radius, fill):
    draw.rounded_rectangle(box, radius=radius, fill=fill)


# ---------------------------------------------------------------- screenshots
SHOTS = [
    ("01_home", "Your whole GLP-1 journey,\nin one calm place"),
    ("02_doses", "Log injections in seconds\n— sites, doses, notes"),
    ("03_calendar", "Calendar & insights that\nactually motivate you"),
    ("04_weight", "Weight trends in kg or lb,\nwith goal projections"),
    ("05_coach", "An on-device AI coach.\nPrivate. Offline. Free."),
    ("06_settings", "Pen-stock alerts, reminders\n& 59 languages"),
]

W, H = 1080, 1920


def compose_screenshot(src_path, caption, out_path):
    src = Image.open(src_path).convert("RGB")
    # device is 1008x2192 (Pixel 8 Pro FHD+); scale shot to fit a phone frame
    frame_w = 880
    scale = frame_w / src.width
    frame_h = int(src.height * scale)
    shot = src.resize((frame_w, frame_h), Image.LANCZOS)

    # crop vertically to the content window height we want
    content_h = 1370
    if frame_h > content_h:
        shot = shot.crop((0, 0, frame_w, content_h))

    img = Image.new("RGB", (W, H), CREAM)
    d = ImageDraw.Draw(img)

    # soft brand blobs in background
    blob = Image.new("RGB", (W, H), CREAM)
    bd = ImageDraw.Draw(blob)
    bd.ellipse((-350, -300, 550, 500), fill=(*SAGE,))
    bd.ellipse((W - 500, H - 700, W + 350, H + 250), fill=(*SAGE,))
    blob = blob.filter(ImageFilter.GaussianBlur(160))
    img = Image.blend(img, blob, 0.55)
    d = ImageDraw.Draw(img)

    # caption at top
    y = 120
    for line in caption.split("\n"):
        f = font(FONT_B, 64)
        tw = d.textlength(line, font=f)
        d.text(((W - tw) / 2, y), line, font=f, fill=INK)
        y += 84

    # phone frame with shadow
    fx = (W - frame_w) // 2
    fy = 340
    radius = 60
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle((fx - 8, fy + 14, fx + frame_w + 8, fy + shot.height + 30),
                         radius=radius + 8, fill=(20, 45, 35, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(28))
    img.paste(shadow, (0, 0), shadow)
    d = ImageDraw.Draw(img)

    rounded(d, (fx - 6, fy - 6, fx + frame_w + 6, fy + shot.height + 6), radius + 6, FOREST_DEEP)
    # paste screenshot with rounded mask
    mask = Image.new("L", shot.size, 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, frame_w, shot.height), radius=radius, fill=255)
    img.paste(shot, (fx, fy), mask)

    # brand strip at bottom: icon dot + wordmark
    strip_y = fy + shot.height + 70
    d.ellipse((W / 2 - 190, strip_y, W / 2 - 130, strip_y + 60), fill=MINT_STRONG)
    d.text((W / 2 - 118, strip_y + 2), "Dosely", font=font(FONT_B, 52), fill=FOREST)
    f_small = font(FONT_R, 34)
    sub = "Private. On-device. Not medical advice."
    tw = d.textlength(sub, font=f_small)
    d.text(((W - tw) / 2, strip_y + 78), sub, font=f_small, fill=(90, 110, 100))

    img.save(out_path, "PNG")
    print("shot ->", out_path)


# ---------------------------------------------------------------------- icon
def make_icon(out_path, size=512):
    s = 4  # supersample
    S = size * s
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # rounded-square gradient background (forest -> deep)
    grad = Image.new("RGBA", (S, S))
    gd = ImageDraw.Draw(grad)
    for i in range(S):
        t = i / S
        r = int(FOREST[0] + (FOREST_DEEP[0] - FOREST[0]) * t)
        g = int(FOREST[1] + (FOREST_DEEP[1] - FOREST[1]) * t)
        b = int(FOREST[2] + (FOREST_DEEP[2] - FOREST[2]) * t)
        gd.line([(0, i), (S, i)], fill=(r, g, b, 255))
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle((0, 0, S, S), radius=int(S * 0.22), fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # syringe, drawn diagonally: barrel + plunger + needle
    m = s
    lw = int(34 * m)
    # barrel (thick mint line)
    d.line([(170 * m, 342 * m), (300 * m, 212 * m)], fill=MINT, width=lw * 2)
    # barrel outline ticks
    for t in (0.18, 0.38, 0.58):
        x = 170 * m + (300 * m - 170 * m) * t
        y = 342 * m + (212 * m - 342 * m) * t
        d.line([(x - 18 * m, y + 26 * m), (x + 26 * m, y - 18 * m)], fill=FOREST_DEEP, width=int(10 * m))
    # plunger
    d.line([(300 * m, 212 * m), (356 * m, 156 * m)], fill=CREAM, width=lw)
    d.line([(336 * m, 136 * m), (376 * m, 176 * m)], fill=CREAM, width=lw)
    # needle
    d.line([(170 * m, 342 * m), (120 * m, 392 * m)], fill=SAGE, width=int(18 * m))
    d.ellipse((104 * m, 384 * m, 132 * m, 412 * m), fill=MINT)

    # mint drop accent
    d.ellipse((330 * m, 320 * m, 410 * m, 400 * m), fill=MINT_STRONG)
    d.ellipse((352 * m, 342 * m, 376 * m, 366 * m), fill=FOREST_DEEP)

    img = img.resize((size, size), Image.LANCZOS)
    img.save(out_path, "PNG")
    print("icon ->", out_path)


# ------------------------------------------------------------ feature graphic
def make_feature(out_path, w=1024, h=500):
    s = 2
    Wf, Hf = w * s, h * s
    grad = Image.new("RGB", (Wf, Hf))
    gd = ImageDraw.Draw(grad)
    for i in range(Hf):
        t = i / Hf
        r = int(FOREST[0] + (FOREST_DEEP[0] - FOREST[0]) * t)
        g = int(FOREST[1] + (FOREST_DEEP[1] - FOREST[1]) * t)
        b = int(FOREST[2] + (FOREST_DEEP[2] - FOREST[2]) * t)
        gd.line([(0, i), (Wf, i)], fill=(r, g, b))
    d = ImageDraw.Draw(grad)

    # decorative mint rings
    for cx, cy, rad, col in ((900 * s, 90 * s, 150 * s, (62, 207, 116, 60)),
                             (140 * s, 420 * s, 190 * s, (123, 228, 149, 40))):
        overlay = Image.new("RGBA", (Wf, Hf), (0, 0, 0, 0))
        od = ImageDraw.Draw(overlay)
        od.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=col[:3], width=8 * s)
        grad.paste(overlay, (0, 0), overlay)
    d = ImageDraw.Draw(grad)

    # icon mark on the left
    icon = make_icon_inner(200 * s)
    grad.paste(icon, (70 * s, (Hf - 200 * s) // 2), icon)

    # wordmark + tagline
    d.text((310 * s, 150 * s), "Dosely", font=font(FONT_B, 130 * s), fill=CREAM)
    d.text((313 * s, 310 * s), "GLP-1 injections · weight · AI coach",
           font=font(FONT_R, 42 * s), fill=MINT)

    grad = grad.resize((w, h), Image.LANCZOS)
    grad.save(out_path, "PNG")
    print("feature ->", out_path)


def make_icon_inner(size):
    """Transparent-background icon mark for overlaying on the feature graphic."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = size / 512
    d.rounded_rectangle((0, 0, size, size), radius=int(size * 0.22), fill=(*FOREST, 255))
    d.line([(170 * m, 342 * m), (300 * m, 212 * m)], fill=MINT, width=int(68 * m))
    d.line([(300 * m, 212 * m), (356 * m, 156 * m)], fill=CREAM, width=int(34 * m))
    d.line([(336 * m, 136 * m), (376 * m, 176 * m)], fill=CREAM, width=int(34 * m))
    d.line([(170 * m, 342 * m), (120 * m, 392 * m)], fill=SAGE, width=int(18 * m))
    d.ellipse((104 * m, 384 * m, 132 * m, 412 * m), fill=MINT)
    d.ellipse((330 * m, 320 * m, 410 * m, 400 * m), fill=MINT_STRONG)
    return img


# ------------------------------------------------------------- video frames
def make_intro(out_path, w=1080, h=1920):
    img = Image.new("RGB", (w, h), FOREST_DEEP)
    d = ImageDraw.Draw(img)
    icon = make_icon_inner(360)
    img.paste(icon, ((w - 360) // 2, 620), icon)
    f1 = font(FONT_B, 110)
    f2 = font(FONT_R, 48)
    t1 = "Dosely"
    t2 = "Your private GLP-1 companion"
    d.text(((w - d.textlength(t1, font=f1)) / 2, 1060), t1, font=f1, fill=CREAM)
    d.text(((w - d.textlength(t2, font=f2)) / 2, 1220), t2, font=f2, fill=MINT)
    img.save(out_path, "PNG")
    print("intro ->", out_path)


def make_outro(out_path, w=1080, h=1920):
    img = Image.new("RGB", (w, h), FOREST_DEEP)
    d = ImageDraw.Draw(img)
    # feature graphic burned in as the end card
    feat = Image.open(os.path.join(ROOT, "feature", "feature_1024x500.png")).convert("RGBA")
    fw = 940
    fh = int(feat.height * fw / feat.width)
    feat = feat.resize((fw, fh), Image.LANCZOS)
    # rounded corners on the burned-in feature card
    fmask = Image.new("L", feat.size, 0)
    ImageDraw.Draw(fmask).rounded_rectangle((0, 0, fw, fh), radius=36, fill=255)
    img.paste(feat, ((w - fw) // 2, 560), fmask)
    f2 = font(FONT_B, 64)
    f3 = font(FONT_R, 40)
    d.text(((w - d.textlength("Dosely — on Google Play", font=f2)) / 2, 1130),
           "Dosely — on Google Play", font=f2, fill=MINT)
    d.text(((w - d.textlength("Not medical advice. Always consult your clinician.", font=f3)) / 2, 1620),
           "Not medical advice. Always consult your clinician.", font=f3, fill=(150, 175, 160))
    img.save(out_path, "PNG")
    print("outro ->", out_path)


if __name__ == "__main__":
    os.makedirs(SHOT, exist_ok=True)
    os.makedirs(VID, exist_ok=True)
    for name, cap in SHOTS:
        src = os.path.join(RAW, name + ".png")
        if os.path.exists(src):
            compose_screenshot(src, cap, os.path.join(SHOT, f"{name}.png"))
    make_icon(os.path.join(ROOT, "icons", "icon_512.png"))
    make_icon(os.path.join(ROOT, "icons", "icon_96.png"), 96)
    make_feature(os.path.join(ROOT, "feature", "feature_1024x500.png"))
    make_intro(os.path.join(VID, "intro.png"))
    make_outro(os.path.join(VID, "outro.png"))
