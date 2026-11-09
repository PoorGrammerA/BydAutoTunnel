#!/usr/bin/env python3
"""Generate Android mipmap launcher icons and store preview from a source image."""

from __future__ import annotations

import argparse
from pathlib import Path
from PIL import Image, ImageDraw


SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def make_round(im: Image.Image) -> Image.Image:
    """Create a circular masked version of the image for round launcher icons."""
    mask = Image.new("L", im.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, im.size[0], im.size[1]), fill=255)
    result = im.copy()
    result.putalpha(mask)
    return result


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    default_src = repo_root / "app" / "icon_512.png"
    default_res = repo_root / "app" / "src" / "main" / "res"

    parser = argparse.ArgumentParser(description="Generate Android launcher icons.")
    parser.add_argument("--src", type=Path, default=default_src,
                        help="Path to source high-resolution icon image (default: app/icon_512.png)")
    parser.add_argument("--res-dir", type=Path, default=default_res,
                        help="Path to Android res/ directory (default: app/src/main/res)")
    args = parser.parse_args()

    if not args.src.exists():
        raise FileNotFoundError(f"Source icon file not found: {args.src}")

    img = Image.open(args.src).convert("RGBA")
    round_img = make_round(img)

    for folder, size in SIZES.items():
        target_dir = args.res_dir / folder
        target_dir.mkdir(parents=True, exist_ok=True)

        # 1. Standard icon
        resized_std = img.resize((size, size), Image.Resampling.LANCZOS)
        webp_std = target_dir / "ic_launcher.webp"
        resized_std.save(webp_std, "WEBP", quality=95)

        # 2. Round icon
        resized_round = round_img.resize((size, size), Image.Resampling.LANCZOS)
        webp_round = target_dir / "ic_launcher_round.webp"
        resized_round.save(webp_round, "WEBP", quality=95)

        print(f"Generated {size}x{size} icons in {folder}")

    # 3. Save 512x512 store/preview icon in app root
    store_icon = args.res_dir.parent.parent.parent / "icon_512.png"
    img.resize((512, 512), Image.Resampling.LANCZOS).save(store_icon, "PNG")
    print(f"Saved 512x512 preview icon: {store_icon}")

    # Remove mipmap-anydpi-v26 so AOSP/Android launchers use the custom webp icons directly
    anydpi_dir = args.res_dir / "mipmap-anydpi-v26"
    if anydpi_dir.exists():
        for f in anydpi_dir.iterdir():
            if f.is_file():
                f.unlink()
        anydpi_dir.rmdir()
        print("Removed mipmap-anydpi-v26 directory for full launcher compatibility.")

    print("App icon generation completed successfully!")


if __name__ == "__main__":
    main()
