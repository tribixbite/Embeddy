#!/data/data/com.termux/files/usr/bin/bash
# Regenerate the PWA PNG icons from the SVG sources (L5).
# Requires rsvg-convert (librsvg). Run from site/: bash scripts/gen-icons.sh
set -euo pipefail
cd "$(dirname "$0")/../public"
for size in 192 512; do
  rsvg-convert -w "$size" -h "$size" favicon.svg -o "icon-$size.png"
  rsvg-convert -w "$size" -h "$size" icon-maskable.svg -o "icon-maskable-$size.png"
done
