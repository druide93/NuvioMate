#!/usr/bin/env python3
"""NuvioMate branding: draws the logo and writes every launcher icon, TV banner and splash wordmark.

The mark is a rounded gradient play button with a small "mate" dot at its top right.
Run from the repository root:  python3 scripts/nuviomate_brand.py [--preview out.png]
Needs Pillow and the macOS "Avenir Next" font.
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

FONT = "/System/Library/Fonts/Avenir Next.ttc"
FONT_BOLD = 0  # index of "Avenir Next Bold" in the collection
BG = (13, 16, 30)  # icon background, same navy as the previous icons
SS = 4  # supersampling factor

# (start, end) of the accent gradient for each color.
PALETTES = {
    "default": ("#914de1", "#51dcf0"),
    "arctic_blue": ("#4289f2", "#4de3ff"),
    "copper": ("#c4683a", "#f4ad6a"),
    "emerald": ("#1cc885", "#79f98f"),
    "graphite": ("#85878d", "#dcdde1"),
    "rose_gold": ("#e069c3", "#fd9481"),
    "gold": ("#e9a91a", "#ffd866"),
}

ICON_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def gradient(size, c1, c2):
    """Diagonal gradient (top-left c1 to bottom-right c2)."""
    w, h = size
    a, b = rgb(c1), rgb(c2)
    small = Image.new("RGB", (64, 64))
    px = small.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126
            px[x, y] = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return small.resize((w, h), Image.BICUBIC)


def mark(size, c1, c2):
    """The logo mark alone (rounded play button + "mate" dot), on a transparent square of `size` px."""
    s = size * SS
    grad = gradient((s, s), c1, c2).convert("RGBA")
    shape = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(shape)
    # Rounded play triangle: a smaller triangle grown by a round pen of radius r.
    r = 0.07 * s
    pts = [(0.27 * s, 0.24 * s), (0.27 * s, 0.80 * s), (0.75 * s, 0.52 * s)]
    d.polygon(pts, fill=255)
    for i in range(3):
        d.line([pts[i], pts[(i + 1) % 3]], fill=255, width=round(2 * r))
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    layer.paste(grad, (0, 0), shape)

    # "Mate" dot, top right.
    cx, cy, rd = 0.80 * s, 0.20 * s, 0.10 * s
    dot = Image.new("L", (s, s), 0)
    ImageDraw.Draw(dot).ellipse((cx - rd, cy - rd, cx + rd, cy + rd), fill=255)
    layer.paste(Image.new("RGBA", (s, s), rgb(c2) + (255,)), (0, 0), dot)
    return layer.resize((size, size), Image.LANCZOS)


def glow(size, color, strength):
    """Soft round light in the middle, tinted with the accent."""
    g = Image.new("L", (size, size), 0)
    ImageDraw.Draw(g).ellipse((size * 0.15, size * 0.12, size * 0.85, size * 0.82), fill=int(255 * strength))
    g = g.filter(ImageFilter.GaussianBlur(size * 0.16))
    layer = Image.new("RGBA", (size, size), rgb(color) + (0,))
    layer.putalpha(g)
    return layer


def icon(size, c1, c2):
    big = 512
    img = Image.new("RGBA", (big, big), BG + (255,))
    img.alpha_composite(glow(big, c1, 0.22))
    m = mark(round(big * 0.80), c1, c2)
    off = (big - m.width) // 2
    img.alpha_composite(m, (off, off + round(big * 0.02)))
    return img.convert("RGB").resize((size, size), Image.LANCZOS)


def text_layer(height_px, c1, c2):
    """'Nuvio' in white and 'Mate' in the accent gradient, tightly cropped, transparent."""
    font = ImageFont.truetype(FONT, height_px, index=FONT_BOLD)
    nuvio, mate = "Nuvio", "Mate"
    w1 = font.getlength(nuvio)
    w = round(w1 + font.getlength(mate)) + 4
    h = round(height_px * 1.4)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    d.text((0, 0), nuvio, font=font, fill=(255, 255, 255, 255))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((w1, 0), mate, font=font, fill=255)
    canvas.paste(gradient((w, h), c1, c2).convert("RGBA"), (0, 0), mask)
    return canvas.crop(canvas.getbbox())


def lockup(height, c1, c2, max_width=None):
    """Mark + text side by side on a transparent background."""
    m = mark(height, c1, c2)
    t = text_layer(round(height * 0.62), c1, c2)
    gap = round(height * 0.10)
    total = m.width + gap + t.width
    out = Image.new("RGBA", (total, height), (0, 0, 0, 0))
    out.alpha_composite(m, (0, 0))
    out.alpha_composite(t, (m.width + gap, (height - t.height) // 2 + round(height * 0.02)))
    if max_width and out.width > max_width:
        out = out.resize((max_width, round(height * max_width / out.width)), Image.LANCZOS)
    return out


def wordmark(c1, c2, height=344):
    """Mark + text, tightly cropped (no side margins) and `height` px tall, like Nuvio's wordmark."""
    lk = lockup(height * SS // 2, c1, c2)
    lk = lk.crop(lk.getbbox())
    return lk.resize((round(lk.width * height / lk.height), height), Image.LANCZOS)


def banner(c1, c2, size=(320, 180)):
    w, h = size[0] * SS, size[1] * SS
    a = rgb(c1)
    top = tuple(round(BG[i] * 0.72 + a[i] * 0.28) for i in range(3))
    bg = Image.new("RGB", (1, 2))
    bg.putpixel((0, 0), top)
    bg.putpixel((0, 1), BG)
    img = bg.resize((w, h), Image.BILINEAR).convert("RGBA")
    img.alpha_composite(glow(w, c1, 0.18).crop((0, (w - h) // 2, w, (w + h) // 2)))
    lk = lockup(round(h * 0.42), c1, c2, max_width=round(w * 0.84))
    img.alpha_composite(lk, ((w - lk.width) // 2, (h - lk.height) // 2))
    return img.convert("RGB").resize(size, Image.LANCZOS)


DEV_BAND = (245, 124, 32)  # orange "DEV" band of the debug app (NuvioMate Dev)


def dev_band(img):
    """Puts an orange "DEV" band across the bottom of an icon or banner (for NuvioMate Dev)."""
    w, h = img.size
    scale = max(1, 512 // min(w, h)) * SS
    big = img.convert("RGBA").resize((w * scale, h * scale), Image.LANCZOS)
    bw, bh = big.size
    band_h = round(min(bw, bh) * (0.26 if bw == bh else 0.2))
    d = ImageDraw.Draw(big)
    d.rectangle((0, bh - band_h, bw, bh), fill=DEV_BAND + (255,))
    font = ImageFont.truetype(FONT, round(band_h * 0.72), index=FONT_BOLD)
    l, t, r, b = d.textbbox((0, 0), "DEV", font=font)
    d.text(((bw - (r - l)) / 2 - l, bh - band_h + (band_h - (b - t)) / 2 - t), "DEV", font=font,
           fill=(255, 255, 255, 255))
    return big.convert("RGB").resize((w, h), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def main():
    res = "app/src/full/res"
    dev_res = "app/src/fullDebug/res"
    suffix = lambda name: "" if name == "default" else "_" + name
    for name in ("default", "arctic_blue", "copper", "emerald", "graphite", "rose_gold"):
        c1, c2 = PALETTES[name]
        for dpi, px in ICON_SIZES.items():
            save(icon(px, c1, c2), f"{res}/mipmap-{dpi}/iptv_ic_launcher{suffix(name)}.png")
        save(banner(c1, c2), f"{res}/mipmap-xhdpi/iptv_banner{suffix(name)}.png")
        # NuvioMate Dev (debug build): the same icons and banners with a "DEV" band.
        for dpi, px in ICON_SIZES.items():
            save(dev_band(icon(px, c1, c2)), f"{dev_res}/mipmap-{dpi}/iptv_ic_launcher{suffix(name)}.png")
        save(dev_band(banner(c1, c2)), f"{dev_res}/mipmap-xhdpi/iptv_banner{suffix(name)}.png")

    draw = "app/src/main/res/drawable"
    for file_suffix, name in (("", "default"), ("_arctic_blue", "arctic_blue"), ("_gold", "gold"),
                              ("_graphite", "graphite"), ("_jade", "emerald"), ("_rose_gold", "rose_gold")):
        save(wordmark(*PALETTES[name]), f"{draw}/livetv_wordmark_iptv{file_suffix}.png")

    if "--preview" in sys.argv:
        out = sys.argv[sys.argv.index("--preview") + 1]
        names = ("default", "arctic_blue", "copper", "emerald", "graphite", "rose_gold")
        sheet = Image.new("RGB", (6 * 340 + 20, 760), (24, 24, 28))
        for i, name in enumerate(names):
            c1, c2 = PALETTES[name]
            x = 20 + i * 340
            sheet.paste(icon(192, c1, c2), (x + 64, 20))
            sheet.paste(banner(c1, c2), (x, 230))
            sheet.paste(icon(48, c1, c2), (x + 136, 430))
        wm = wordmark(*PALETTES["default"], height=172)
        sheet.paste(wm, (20, 520), wm)
        wm = wordmark(*PALETTES["gold"], height=172)
        sheet.paste(wm, (1040, 520), wm)
        sheet.save(out)


if __name__ == "__main__":
    main()
