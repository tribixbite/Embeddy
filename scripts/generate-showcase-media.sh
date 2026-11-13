#!/data/data/com.termux/files/usr/bin/bash
# Generate the media for embeddy.link/showcase — the page whose Discord
# component embed uses every limit (3,000 bytes, 40 components, 10 gallery
# items). Every clip is rendered from ffmpeg's own generators, so the set is
# reproducible and carries no third-party footage.
#
#   site/public/s/0.webp  640x360  Mandelbrot zoom in and back out (gallery hero, full width)
#   site/public/s/1.webp  320x320  Game of Life with decaying trails
#   site/public/s/2.webp  320x320  Rule 30 cellular automaton masking a moving rainbow
#   site/public/s/3.webp  320x320  Metaballs (three orbiting charges, looping)
#   site/public/s/4.webp  320x320  Plasma (looping sine field)
#   site/public/s/5.webp  320x320  Polar tunnel (looping)
#   site/public/s/6.webp  320x320  Lissajous figure drawn by a 3:2 chord
#   site/public/s/7.webp  320x320  Zone-plate moiré with hue cycling
#   site/public/s/8.webp  320x320  Sierpinski triangle, hue cycling
#   site/public/s/9.mp4   640x360  Constant-Q spectrum of a synthesized arpeggio, with sound
#   site/public/s/t.webp  160x160  Section thumbnail (spoilered in the embed)
#
# Gallery images are animated WebP because Discord autoplays them inline —
# that is Embeddy's own output format. Item 9 is an MP4 so the gallery also
# shows a video tile (Discord draws a play button and plays it with audio).
#
# Usage: generate-showcase-media.sh [file...]   e.g. `… 0.webp 9.mp4`; no args renders all.
# Requires: ffmpeg with libwebp_anim, libx264 and aac.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/site/public/s"
mkdir -p "$OUT"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found (pkg install ffmpeg)" >&2; exit 1; }

# Frame rate and length shared by the square loops
FPS=20
SECS=3
# Lossy animated WebP: quality, effort, loop forever
WEBP=(-c:v libwebp_anim -lossless 0 -quality 60 -compression_level 6 -loop 0 -an)

ONLY=("$@")
# want <file> — true when no files were named, or <file> was
want() {
  [[ ${#ONLY[@]} -eq 0 ]] || [[ " ${ONLY[*]} " == *" $1 "* ]]
}

# render_webp <file> <filtergraph producing [v]>
render_webp() {
  local name="$1" graph="$2"
  want "$name" || return 0
  echo "→ $name"
  ffmpeg -hide_banner -loglevel error -y -filter_complex "$graph" -map "[v]" "${WEBP[@]}" "$OUT/$name"
}

# 0 — Mandelbrot zoom into a seahorse-valley spiral and back out, the full-width hero tile.
# Each frame is rendered on its own: in one animated run the filter reuses the previous
# frame's points, and on a fast zoom that smears the detail into flat colour. Scales step
# geometrically from 1.5 to 0.003; playing the frames forward then backward makes the
# loop seamless.
if want 0.webp; then
  echo "→ 0.webp"
  FRAMES="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/embeddy-mandel.XXXXXX")"
  trap 'rm -rf "$FRAMES"' EXIT
  ZOOM_FRAMES=50
  for ((i = 0; i < ZOOM_FRAMES; i++)); do
    scale="$(awk -v i="$i" -v n="$ZOOM_FRAMES" 'BEGIN { printf "%.9f", 1.5 * (0.003 / 1.5) ^ (i / (n - 1)) }')"
    ffmpeg -hide_banner -loglevel error -y -filter_complex \
      "mandelbrot=s=640x360:rate=1:start_x=-0.77568377:start_y=0.13646737:start_scale=$scale:end_scale=$scale:end_pts=1:bailout=10:maxiter=2048:outer=normalized_iteration_count:inner=black[v]" \
      -map "[v]" -frames:v 1 "$FRAMES/$(printf %03d "$i").png"
    # Mirror: frame i also plays at position 2n-2-i (the end frames aren't doubled)
    if ((i > 0 && i < ZOOM_FRAMES - 1)); then
      cp "$FRAMES/$(printf %03d "$i").png" "$FRAMES/$(printf %03d $((2 * ZOOM_FRAMES - 2 - i))).png"
    fi
  done
  ffmpeg -hide_banner -loglevel error -y -framerate "$FPS" -i "$FRAMES/%03d.png" -vf format=yuv420p "${WEBP[@]}" "$OUT/0.webp"
fi

# 1 — Game of Life; lagfun leaves fading trails, pseudocolor maps them to a heat palette
render_webp 1.webp "life=s=80x80:rate=$FPS:ratio=0.2:seed=7:life_color=#ffffff:death_color=#000000,format=gray,lagfun=decay=0.85,format=yuv444p,pseudocolor=preset=turbo,scale=320:320:flags=neighbor,trim=duration=$SECS,format=yuv420p[v]"

# 2 — Rule 30 scrolling upward; live cells reveal a moving rainbow (multiply blend)
render_webp 2.webp "cellauto=s=80x80:rate=$FPS:rule=30:scroll=1:full=1:seed=3,scale=320:320:flags=neighbor,format=gbrp[c];gradients=s=320x320:rate=$FPS:type=linear:speed=0.05:n=4:c0=#ff3d9a:c1=#555de3:c2=#00e0b8:c3=#ffb000,format=gbrp[g];[c][g]blend=all_mode=multiply,trim=duration=$SECS,format=yuv420p[v]"

# 3 — Metaballs: M is the summed inverse distance to three charges on looping orbits
P="2*PI*T/$SECS"
M="(900/(hypot(X-160-90*cos($P),Y-160-90*sin(2*$P))+1)+900/(hypot(X-160-90*sin($P),Y-160+90*cos($P))+1)+900/(hypot(X-160+70*cos(2*$P),Y-160-70*sin($P))+1))"
render_webp 3.webp "nullsrc=s=320x320:rate=$FPS,format=gbrp,geq=r='clip(255*sin($M/12)*sin($M/12),0,255)':g='clip($M*4-120,0,255)*0.4':b='clip(400-$M*3,0,255)*0.9',trim=duration=$SECS,format=yuv420p[v]"

# 4 — Plasma: three sine fields, period = SECS so the loop is seamless
render_webp 4.webp "nullsrc=s=320x320:rate=$FPS,format=gbrp,geq=r='128+127*sin(X/23+$P)*cos(Y/31-$P)':g='128+127*sin(hypot(X-160,Y-160)/19-$P)':b='128+127*cos((X+Y)/29+2*$P)',trim=duration=$SECS,format=yuv420p[v]"

# 5 — Polar tunnel: rings travel inward, spokes rotate, one revolution per loop
render_webp 5.webp "nullsrc=s=320x320:rate=$FPS,format=gbrp,geq=r='128+127*sin(900/(hypot(X-160,Y-160)+8)+$P*2+3*atan2(Y-160,X-160))':g='128+127*sin(900/(hypot(X-160,Y-160)+8)+$P*2+3*atan2(Y-160,X-160)+2.1)':b='128+127*sin(900/(hypot(X-160,Y-160)+8)+$P*2+3*atan2(Y-160,X-160)+4.2)',trim=duration=$SECS,format=yuv420p[v]"

# 6 — Lissajous: a 3:4 pair with a slight detune, so the figure slowly turns
render_webp 6.webp "aevalsrc=exprs='0.8*sin(2*PI*60*t)|0.8*sin(2*PI*80.333*t+PI/2)':s=44100,avectorscope=s=320x320:r=$FPS:mode=lissajous_xy:draw=aaline:scale=lin:zoom=1:rc=255:gc=60:bc=200:rf=40:gf=30:bf=20,trim=duration=$SECS,format=yuv420p[v]"

# 7 — Zone plate moiré, hue rotating once per loop
render_webp 7.webp "zoneplate=s=320x320:rate=$FPS:precision=10:kt2=4:kx2=90:ky2=90:kt=0,format=gbrp,hue=h=360*t/$SECS:s=3,trim=duration=$SECS,format=yuv420p[v]"

# 8 — Sierpinski triangle panning, hue rotating
render_webp 8.webp "sierpinski=s=320x320:rate=$FPS:type=triangle:jump=1,format=gbrp,lutrgb=r='val*0.4':g='val*0.5':b='val',hue=h=360*t/$SECS:s=2,trim=duration=$SECS,format=yuv420p[v]"

# t — thumbnail for the spoilered Section accessory: a small plasma
render_webp t.webp "nullsrc=s=160x160:rate=$FPS,format=gbrp,geq=r='128+127*sin(X/9+$P)':g='128+127*sin(Y/11-$P)':b='128+127*sin((X+Y)/13+$P)',trim=duration=$SECS,format=yuv420p[v]"

# 9 — MP4 with sound: an arpeggio (A minor, 4 notes per second) seen through showcqt
if want 9.mp4; then
echo "→ 9.mp4"
NOTE='floor(mod(t*4,4))'
FREQ="220*pow(2,if(eq($NOTE,0),0,if(eq($NOTE,1),3,if(eq($NOTE,2),7,12)))/12)"
ENV='exp(-6*mod(t,0.25))'
ffmpeg -hide_banner -loglevel error -y \
  -filter_complex "aevalsrc=exprs='0.5*$ENV*(sin(2*PI*$FREQ*t)+0.4*sin(4*PI*$FREQ*t))|0.5*$ENV*(sin(2*PI*$FREQ*1.002*t)+0.4*sin(4*PI*$FREQ*t))':s=44100:d=8,asplit[a][s];[s]showcqt=s=640x360:fps=30:bar_g=2:sono_g=4:count=4:axis=0:sono_v=17:bar_v=12[v]" \
  -map "[v]" -map "[a]" -c:v libx264 -preset slow -crf 26 -pix_fmt yuv420p -movflags +faststart \
  -c:a aac -b:a 96k -shortest "$OUT/9.mp4"
fi

ls -l "$OUT"
