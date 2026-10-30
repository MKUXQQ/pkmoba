"""Preview server for the expansion textures (read-only, writes nothing).

Serves dynamically generated PNG contact sheets on 127.0.0.1 so the artwork can be reviewed
before/without writing into the resource tree (useful in sandboxes where child processes can
only read the workspace).

    python tools/preview_expansion_textures.py --port 8765
    -> http://127.0.0.1:8765/icons.png      (32 skill icons, 2x)
    -> http://127.0.0.1:8765/portraits.png  (8 official portraits, fetched in memory)
"""

from __future__ import annotations

import io
import sys
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_expansion_textures as gen  # noqa: E402

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PKMOBA-preview/1.0"
DEX = {"lucario": 448, "abomasnow": 460, "blaziken": 257, "gengar": 94,
       "tyranitar": 248, "garchomp": 445, "scizor": 212, "gardevoir": 282}


def label(draw: ImageDraw.ImageDraw, x: int, y: int, text: str, fill=(235, 235, 235, 255)) -> None:
    draw.text((x, y), text, fill=fill)


def icons_sheet() -> Image.Image:
    cell, pad = 64, 46
    cols, rows = 4, len(gen.MOVES)
    img = Image.new("RGBA", (cols * (cell + pad) + 16, rows * (cell + 18) + 40), (18, 20, 26, 255))
    d = ImageDraw.Draw(img)
    label(d, 10, 8, "PKMOBA expansion skill icons 32x32 (drawn 2x) - Q W E R")
    for r, species in enumerate(gen.MOVES):
        for c in range(4):
            icon = gen.render_icon(species, c).resize((cell, cell), Image.Resampling.NEAREST)
            x = 10 + c * (cell + pad)
            y = 30 + r * (cell + 18)
            img.alpha_composite(icon, (x, y))
            zh, glyph = gen.MOVES[species][c]
            label(d, x + cell + 4, y + 20, f"{species}")
            label(d, x + cell + 4, y + 34, f"{'qwer'[c]} {glyph}")
    return img


def portraits_sheet() -> Image.Image:
    cell, pad = 96, 74
    img = Image.new("RGBA", (4 * (cell + pad) + 16, 2 * (cell + 18) + 40), (18, 20, 26, 255))
    d = ImageDraw.Draw(img)
    label(d, 10, 8, "PKMOBA expansion portraits 64x64 (shown 1.5x) - official art, framed")
    for i, (species, dex) in enumerate(DEX.items()):
        data = None
        for url in (f"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/{dex}.png",
                    f"https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/{dex}.png",
                    f"https://assets.pokemon.com/assets/cms2/img/pokedex/full/{dex:03d}.png"):
            for attempt in range(2):
                try:
                    req = urllib.request.Request(url, headers={"User-Agent": UA})
                    data = urllib.request.urlopen(req, timeout=30).read()
                    break
                except Exception:  # noqa: BLE001
                    time.sleep(1.5)
            if data:
                break
        x = 10 + (i % 4) * (cell + pad)
        y = 30 + (i // 4) * (cell + 18)
        if not data:
            d.rectangle((x, y, x + cell, y + cell), fill=(120, 40, 40, 255))
            label(d, x + 4, y + 40, "FETCH FAILED", (255, 200, 200, 255))
            continue
        raw = Image.open(io.BytesIO(data)).convert("RGBA")
        bbox = raw.getbbox()
        if bbox:
            raw = raw.crop(bbox)
        side = max(raw.size)
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.alpha_composite(raw, ((side - raw.width) // 2, (side - raw.height) // 2))
        from fetch_pokemon_portraits import frame  # 64x64 framed style
        portrait = frame(square.resize((64, 64), Image.Resampling.LANCZOS))
        portrait = portrait.resize((cell, cell), Image.Resampling.LANCZOS)
        img.alpha_composite(portrait, (x, y))
        label(d, x + 4, y + cell + 2, species)
    return img


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802
        try:
            if self.path.startswith("/portraits"):
                payload = _png(cached("portraits", portraits_sheet))
            elif self.path.startswith("/icons"):
                payload = _png(cached("icons", icons_sheet))
            else:
                payload = b"use /icons.png or /portraits.png"
                self.send_response(404)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception as exc:  # noqa: BLE001
            print("server error:", exc)
            self.send_error(500, str(exc))

    def log_message(self, *args) -> None:  # quiet
        pass


def _png(image: Image.Image) -> bytes:
    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return buf.getvalue()


_CACHE: dict[str, bytes] = {}


def cached(key: str, builder) -> Image.Image:
    """Render once per server lifetime so slow mirror retries don't run on every request."""
    if key not in _CACHE:
        _CACHE[key] = _png(builder())
    return Image.open(io.BytesIO(_CACHE[key]))


def main(argv: list[str]) -> int:
    port = 8765
    if "--port" in argv:
        port = int(argv[argv.index("--port") + 1])
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"serving http://127.0.0.1:{port}/icons.png and /portraits.png")
    server.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
