"""生成 PIME 启动器图标：品牌蓝底 + 白色气泡。

源是同目录 res/drawable-nodpi/pime_logo.webp —— 它的 alpha 就是气泡轮廓，
所以同一份资源既能给界面里的品牌标记上色，也能给启动器图标上色。

产物：
- mipmap-<density>/ic_launcher.webp      蓝底圆角方块 + 白气泡（API 24/25 与部分启动器）
- mipmap-<density>/ic_launcher_round.webp 蓝底圆形 + 白气泡
- mipmap-<density>/ic_launcher_foreground.webp 透明白气泡，收在自适应图标安全区内
"""
from pathlib import Path
import math

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
SOURCE = RES / "drawable-nodpi" / "pime_logo.webp"

BRAND_BLUE = (34, 103, 250, 255)
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
FOREGROUND_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

# 自适应图标画布 108dp，中央 66dp 圆是各家启动器遮罩一定覆盖不到的安全区。
SAFE_RADIUS_DP = 31.0
CANVAS_DP = 108.0

# 老式位图图标不做遮罩，气泡可以画得比安全区大一些，视觉重量才够。
LEGACY_RADIUS_FRACTION = 0.40


def bubble_mask() -> Image.Image:
    """气泡的 alpha 轮廓，已裁到内容边界。"""
    with Image.open(SOURCE) as image:
        alpha = image.convert("RGBA").getchannel("A")
    box = alpha.getbbox()
    if box is None:
        raise ValueError(f"图标为空：{SOURCE}")
    return alpha.crop(box)


def radius(mask: Image.Image) -> float:
    """轮廓上任意一点离图像中心的最远距离，用来把图形缩进安全区。"""
    cx, cy = mask.width / 2, mask.height / 2
    farthest = 0.0
    for y in range(mask.height):
        for x in range(mask.width):
            if mask.getpixel((x, y)) <= 0:
                continue
            farthest = max(farthest, math.hypot(x + 0.5 - cx, y + 0.5 - cy))
    if farthest <= 0:
        raise ValueError(f"图标为空：{SOURCE}")
    return farthest


def white_bubble(mask: Image.Image, size: int, target_radius: float) -> Image.Image:
    """把气泡缩放到包围半径 target_radius（像素），白色，居中。"""
    scale = target_radius / radius(mask)
    scaled = mask.resize(
        (max(1, round(mask.width * scale)), max(1, round(mask.height * scale))),
        Image.Resampling.LANCZOS,
    )
    layered = Image.new("RGBA", scaled.size, (255, 255, 255, 0))
    layered.putalpha(scaled)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(
        layered, ((size - scaled.width) // 2, (size - scaled.height) // 2)
    )
    return canvas


def supersample(shape: Image.Image, size: int) -> Image.Image:
    """形状按 4 倍绘制再缩回来，边缘不带锯齿。"""
    return shape.resize((size, size), Image.Resampling.LANCZOS)


def square_background(size: int) -> Image.Image:
    layer = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    ImageDraw.Draw(layer).rounded_rectangle(
        (0, 0, size * 4 - 1, size * 4 - 1), radius=size * 4 * 0.22, fill=BRAND_BLUE
    )
    return supersample(layer, size)


def round_background(size: int) -> Image.Image:
    layer = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=BRAND_BLUE)
    return supersample(layer, size)


def save(image: Image.Image, path: Path, lossless: bool) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if lossless:
        image.save(path, "WEBP", lossless=True, method=6)
    else:
        image.save(path, "WEBP", quality=95, method=6)


def main() -> None:
    mask = bubble_mask()

    for directory, size in LEGACY_SIZES.items():
        square = square_background(size)
        square.alpha_composite(white_bubble(mask, size, size * LEGACY_RADIUS_FRACTION))
        save(square.convert("RGB"), RES / directory / "ic_launcher.webp", lossless=False)

        circle = round_background(size)
        circle.alpha_composite(white_bubble(mask, size, size * LEGACY_RADIUS_FRACTION))
        save(circle, RES / directory / "ic_launcher_round.webp", lossless=False)

    for directory, size in FOREGROUND_SIZES.items():
        target = size * SAFE_RADIUS_DP / CANVAS_DP
        save(
            white_bubble(mask, size, target),
            RES / directory / "ic_launcher_foreground.webp",
            lossless=True,
        )

    print(f"生成启动器图标：{RES}")


if __name__ == "__main__":
    main()
