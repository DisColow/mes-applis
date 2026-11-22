"""Fabrique assets/micboard.ico (icône de l'exe) à partir du logo de l'interface (#i-logo).

    python tools/make_icon.py        (demande Pillow : pip install pillow)

Le dessin reprend le symbole SVG du logo, dans une grille de 32 unités : carré arrondi en dégradé
(violet -> bleu -> cyan) et cinq barres blanches arrondies en forme d'onde sonore.
"""

from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent.parent
STOPS = [(0.0, (0x8B, 0x6C, 0xFF)), (0.5, (0x5B, 0x8C, 0xFF)), (1.0, (0x22, 0xD3, 0xEE))]
BARS = [(9, 13, 19), (13, 10, 22), (17, 12, 20), (21, 9, 23), (25, 14, 18)]  # x, haut, bas
SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]


def lerp_color(t: float) -> tuple[int, int, int]:
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            k = (t - t0) / (t1 - t0)
            return tuple(round(a + (b - a) * k) for a, b in zip(c0, c1))
    return STOPS[-1][1]


def render(px: int = 1024) -> Image.Image:
    u = px / 32  # une unité de la grille SVG
    # dégradé diagonal (haut-gauche -> bas-droite)
    grad = Image.new("RGB", (px, px))
    line = [lerp_color(i / (2 * px - 2)) for i in range(2 * px - 1)]
    grad.putdata([line[x + y] for y in range(px) for x in range(px)])
    mask = Image.new("L", (px, px), 0)
    ImageDraw.Draw(mask).rounded_rectangle([2 * u, 2 * u, 30 * u, 30 * u], radius=9 * u, fill=255)
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    img.paste(grad, mask=mask)
    d = ImageDraw.Draw(img)
    w = 2.4 * u
    for x, top, bottom in BARS:
        d.rounded_rectangle([x * u - w / 2, top * u - w / 2, x * u + w / 2, bottom * u + w / 2],
                            radius=w / 2, fill=(255, 255, 255, 255))
    return img


def main() -> None:
    big = render(1024)
    out = HERE / "assets"
    out.mkdir(exist_ok=True)
    big.resize((256, 256), Image.LANCZOS).save(out / "micboard.ico", sizes=[(s, s) for s in SIZES])
    big.resize((512, 512), Image.LANCZOS).save(out / "micboard.png")
    print("écrit :", out / "micboard.ico", out / "micboard.png")


if __name__ == "__main__":
    main()
