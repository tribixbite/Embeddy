#!/data/data/com.termux/files/usr/bin/bash
# Generate the Google Play / F-Droid listing graphics from the app's own
# adaptive-icon geometry, so the store art cannot drift from the launcher icon.
#
#   fastlane/metadata/android/en-US/images/icon.png            512x512  (Play hi-res icon)
#   fastlane/metadata/android/en-US/images/featureGraphic.png  1024x500 (Play feature graphic)
#
# The SVG paths below are transcribed from
#   app/src/main/res/drawable/ic_launcher_foreground.xml (108x108 viewport)
#   app/src/main/res/drawable/ic_launcher_background.xml
# If those change, update FOREGROUND/BACKGROUND here and re-run.
#
# Requires: rsvg-convert (librsvg), a "RobotoStatic" (Android system) or "Roboto" font visible to fontconfig.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/fastlane/metadata/android/en-US/images"
WORK="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/embeddy-assets.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

command -v rsvg-convert >/dev/null || { echo "rsvg-convert not found (pkg install librsvg)" >&2; exit 1; }

BACKGROUND="#1A1A2E"
# Film frame with sprocket holes (even-odd) + conversion arrow.
FOREGROUND='
  <path fill="#B388FF" fill-rule="evenodd" d="M30,36 L78,36 Q80,36 80,38 L80,70 Q80,72 78,72 L30,72 Q28,72 28,70 L28,38 Q28,36 30,36 Z
    M32,40 L32,44 L28,44 L28,40 Z M32,48 L32,52 L28,52 L28,48 Z
    M32,56 L32,60 L28,60 L28,56 Z M32,64 L32,68 L28,68 L28,64 Z
    M76,40 L76,44 L80,44 L80,40 Z M76,48 L76,52 L80,52 L80,48 Z
    M76,56 L76,60 L80,60 L80,56 Z M76,64 L76,68 L80,68 L80,64 Z"/>
  <path fill="#FFFFFF" d="M44,50 L56,50 L56,46 L64,54 L56,62 L56,58 L44,58 Z"/>'

# --- Hi-res icon -------------------------------------------------------------
# Play applies its own corner mask, so this is a full-bleed square. The 108-unit
# adaptive canvas is cropped to its central 72 units (the visible safe zone a
# launcher shows), which is how the icon appears on a home screen.
cat > "$WORK/icon.svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">
  <rect x="0" y="0" width="108" height="108" fill="$BACKGROUND"/>
  $FOREGROUND
</svg>
SVG
rsvg-convert -w 512 -h 512 "$WORK/icon.svg" -o "$OUT/icon.png"

# --- Feature graphic ---------------------------------------------------------
# 1024x500. Play may overlay a play button in the centre and crops edges on some
# surfaces, so the text stays left of centre-right and well inside the margins.
cat > "$WORK/feature.svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#140f24"/>
      <stop offset="1" stop-color="#1A1A2E"/>
    </linearGradient>
    <radialGradient id="glow" cx="0.22" cy="0.5" r="0.45">
      <stop offset="0" stop-color="#7c3aed" stop-opacity="0.45"/>
      <stop offset="1" stop-color="#7c3aed" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="1024" height="500" fill="url(#bg)"/>
  <rect width="1024" height="500" fill="url(#glow)"/>
  <!-- Icon art, 108-unit canvas scaled ~5.2x and centred at (225, 250) -->
  <g transform="translate(-55 -30) scale(5.2)">
    $FOREGROUND
  </g>
  <text x="440" y="225" font-family="RobotoStatic, Roboto" font-weight="700" font-size="84" fill="#FFFFFF">Embeddy</text>
  <text x="444" y="285" font-family="RobotoStatic, Roboto" font-weight="400" font-size="32" fill="#D7CCFF">Trim, join &amp; convert video</text>
  <text x="444" y="330" font-family="RobotoStatic, Roboto" font-weight="400" font-size="32" fill="#9E95C7">GIF &#183; WebP &#183; MP4 that fits the limit</text>
</svg>
SVG
rsvg-convert -w 1024 -h 500 "$WORK/feature.svg" -o "$OUT/featureGraphic.png"

echo "Wrote:"
ls -l "$OUT/icon.png" "$OUT/featureGraphic.png"
