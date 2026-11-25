"""Write the pages used to check Discord's undocumented link-preview precedence.

Each page isolates one question (two twitter:card tags, og:type rich vs a card,
JSON-LD vs Open Graph, inline vs linked component embeds, a 3,001-byte payload…).
Discord must be able to fetch them, so serve the directory publicly, e.g.:

    python3 scripts/discord-precedence-fixtures.py OUT https://<tunnel-host>
    cp site/public/s/{t,0,1}.webp site/public/s/9.mp4 OUT/
    python3 -m http.server 8791 --bind 127.0.0.1 --directory OUT &
    cloudflared tunnel --url http://127.0.0.1:8791   # prints the https://…trycloudflare.com host

then paste each https://<tunnel-host>/<name>.html into Discord's Embed Debugger
(https://discord.com/developers/embeds, signed in) and compare its embed JSON with
/tools/inspect. The 2026-11-15 and 2026-11-25 results are summarised in TASKS.md (12.8) and
encoded as tests in discord-unfurl.test.ts / DiscordUnfurlTest.kt.

Usage: discord-precedence-fixtures.py OUT_DIR BASE_URL
"""
import json, sys, pathlib
out, base = pathlib.Path(sys.argv[1]), sys.argv[2].rstrip("/")
out.mkdir(parents=True, exist_ok=True)
def page(name, head, body=""):
    (out / f"{name}.html").write_text(f"<!doctype html><html><head><meta charset=utf-8><title>{name}</title>{head}</head><body>{body}</body></html>")
def og(t, d="desc", img=True):
    return f'<meta property="og:title" content="{t}"><meta property="og:description" content="{d}">' + (f'<meta property="og:image" content="{base}/0.webp">' if img else "")
def ce(text):
    return json.dumps({"component": {"type": 17, "components": [{"type": 10, "content": text}]}})
# 1. two twitter:card tags: large first, summary last
page("cards-large-then-summary", og("cards L,S") + '<meta name="twitter:card" content="summary_large_image"><meta name="twitter:card" content="summary">')
page("cards-summary-then-large", og("cards S,L") + '<meta name="twitter:card" content="summary"><meta name="twitter:card" content="summary_large_image">')
# 2. duplicate og:title / twitter:title before og:title
page("titles-dup", '<meta property="og:title" content="FIRST og:title"><meta property="og:title" content="SECOND og:title"><meta property="og:description" content="d">')
page("titles-twitter-first", '<meta name="twitter:title" content="TWITTER title"><meta property="og:title" content="OG title"><meta property="og:description" content="d">')
# 3. og:type rich vs twitter:card summary_large_image
page("rich-vs-large", og("rich vs large") + '<meta property="og:type" content="rich"><meta name="twitter:card" content="summary_large_image">')
# 4. twitter:card photo vs summary_large_image order
page("photo-vs-large", og("photo vs large") + '<meta name="twitter:card" content="photo"><meta name="twitter:card" content="summary_large_image">')
# 5. JSON-LD VideoObject alongside og:image (no og:video)
ld = json.dumps({"@context": "https://schema.org", "@type": "VideoObject", "name": "LD name", "description": "LD description",
                 "thumbnailUrl": f"{base}/1.webp", "embedUrl": f"{base}/9.mp4", "contentUrl": f"{base}/9.mp4", "uploadDate": "2026-01-02T03:04:05Z"})
page("jsonld-vs-og", og("OG title vs LD") + f'<script type="application/ld+json">{ld}</script>')
page("jsonld-only", f'<script type="application/ld+json">{ld}</script>')
# 6. og:video vs og:image + summary_large_image
page("video-vs-large", og("video vs large") + f'<meta property="og:video" content="{base}/9.mp4"><meta property="og:video:type" content="video/mp4"><meta property="og:video:width" content="640"><meta property="og:video:height" content="360"><meta name="twitter:card" content="summary_large_image">')
# 7. several og:image: gallery?
page("gallery-3", og("gallery 3") + f'<meta property="og:image" content="{base}/1.webp"><meta property="og:image" content="{base}/t.webp">')
# 8. inline + linked component embeds, both orders
(out / "linked.json").write_text(ce("LINKED payload"))
inline = f'<script id="discord:component-embed" type="application/json">{ce("INLINE payload")}</script>'
linked = f'<link rel="discord:component-embed" type="application/json" href="{base}/linked.json">'
page("ce-inline-then-linked", og("ce I,L") + inline + linked)
page("ce-linked-then-inline", og("ce L,I") + linked + inline)
# 9. two inline embeds
page("ce-two-inline", og("ce two") + f'<script id="discord:component-embed" type="application/json">{ce("FIRST inline")}</script><script id="discord:component-embed" type="application/json">{ce("SECOND inline")}</script>')
# 10. inline embed in <body>
page("ce-in-body", og("ce body"), inline)
# 11. invalid first inline, valid second
page("ce-invalid-then-valid", og("ce bad,good") + '<script id="discord:component-embed" type="application/json">{"component":{"type":17,"components":[{"type":99}]}}</script>' + f'<script id="discord:component-embed" type="application/json">{ce("VALID second")}</script>')
# 12. 3001-byte payload (pads text so the whole JSON is 3001 bytes)
shell = ce("")
page("ce-3001", og("ce 3001") + f'<script id="discord:component-embed" type="application/json">{ce("x" * (3001 - len(shell.encode())))}</script>')
page("ce-3000", og("ce 3000") + f'<script id="discord:component-embed" type="application/json">{ce("x" * (3000 - len(shell.encode())))}</script>')
# 13. linked embed on another host
page("ce-linked-cross", og("ce cross") + '<link rel="discord:component-embed" type="application/json" href="https://embeddy.link/linked-does-not-exist.json">')
# ── Baselines: one layout signal each ──
page("b-none", og("b none"))
page("b-summary", og("b summary") + '<meta name="twitter:card" content="summary">')
page("b-large", og("b large") + '<meta name="twitter:card" content="summary_large_image">')
page("b-photo", og("b photo") + '<meta name="twitter:card" content="photo">')
page("b-rich", og("b rich") + '<meta property="og:type" content="rich">')
page("b-instapp", og("b instapp") + '<meta property="og:type" content="instapp:photo">')
vid = f'<meta property="og:video" content="{base}/9.mp4"><meta property="og:video:type" content="video/mp4"><meta property="og:video:width" content="640"><meta property="og:video:height" content="360">'
page("b-video", og("b video") + vid)
page("b-video-type", og("b video type") + vid + '<meta property="og:type" content="video.other">')
page("large-then-photo", og("large then photo") + '<meta name="twitter:card" content="summary_large_image"><meta name="twitter:card" content="photo">')
page("rich-then-photo", og("rich then photo") + '<meta property="og:type" content="rich"><meta name="twitter:card" content="photo">')
page("b-large-twimage", '<meta property="og:title" content="b large twimage"><meta property="og:description" content="d"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:image" content="' + base + '/1.webp">')
page("ce-head-and-body", og("ce head+body") + f'<script id="discord:component-embed" type="application/json">{ce("HEAD inline")}</script>', f'<script id="discord:component-embed" type="application/json">{ce("BODY inline")}</script>')
page("ce-two-linked", og("ce two linked") + f'<link rel="discord:component-embed" type="application/json" href="{base}/linked.json"><link rel="discord:component-embed" type="application/json" href="{base}/linked2.json">')
(out / "linked2.json").write_text(ce("LINKED2 payload"))
# ── Round 3: og:type vs twitter:card order, video combinations ──
page("large-then-rich", og("large then rich") + '<meta name="twitter:card" content="summary_large_image"><meta property="og:type" content="rich">')
page("photo-then-rich", og("photo then rich") + '<meta name="twitter:card" content="photo"><meta property="og:type" content="rich">')
page("videotype-then-large", og("vtype then large") + vid + '<meta property="og:type" content="video.other"><meta name="twitter:card" content="summary_large_image">')
page("large-then-videotype", og("large then vtype") + vid + '<meta name="twitter:card" content="summary_large_image"><meta property="og:type" content="video.other">')
page("video-card-summary", og("video summary") + vid + '<meta name="twitter:card" content="summary">')
page("video-card-player", og("video player") + vid + '<meta name="twitter:card" content="player">')
page("video-card-photo", og("video photo") + vid + '<meta name="twitter:card" content="photo">')
page("videotype-then-photo", og("vtype then photo") + vid + '<meta property="og:type" content="video.other"><meta name="twitter:card" content="photo">')
page("video-ogtype-rich", og("video rich") + vid + '<meta property="og:type" content="rich">')
page("rich-then-ogtype-website", og("rich then website") + '<meta property="og:type" content="rich"><meta property="og:type" content="website">')
page("jsonld-twimage", '<meta name="twitter:card" content="summary_large_image">' + f'<script type="application/ld+json">{ld}</script>')
page("gallery-large", og("gallery large") + f'<meta property="og:image" content="{base}/1.webp"><meta name="twitter:card" content="summary_large_image">')
# ── Round 4: JSON-LD vs <title> pair; wrong-type inline before linked; photo vs gallery ──
(out / "jsonld-vs-pair.html").write_text(f'<!doctype html><html><head><title>PAIR title</title><meta name="description" content="PAIR description"><script type="application/ld+json">{ld}</script></head><body></body></html>')
(out / "pair-only.html").write_text('<!doctype html><html><head><title>PAIR title</title><meta name="description" content="PAIR description"></head><body></body></html>')
page("ce-badtype-then-linked", og("ce badtype,linked") + f'<script id="discord:component-embed" type="text/plain">{ce("BADTYPE inline")}</script>' + linked)
page("photo-gallery", og("photo gallery") + f'<meta property="og:image" content="{base}/1.webp"><meta name="twitter:card" content="photo">')
page("video-gallery", og("video gallery") + f'<meta property="og:image" content="{base}/1.webp">' + vid + '<meta property="og:type" content="video.other">')
# ── Round 5: orderings left open after round 4 ──
# oEmbed title vs JSON-LD name when neither Open Graph nor Twitter gives a title
(out / "oembed.json").write_text(json.dumps({"version": "1.0", "type": "link", "title": "OEMBED title"}))
oembed_link = f'<link rel="alternate" type="application/json+oembed" href="{base}/oembed.json">'
page("jsonld-vs-oembed", oembed_link + f'<script type="application/ld+json">{ld}</script>')
# og:pubdate vs JSON-LD uploadDate (ld carries 2026-01-02T03:04:05Z)
page("pubdate-vs-upload", og("pubdate vs upload") + '<meta property="og:pubdate" content="2025-05-06T07:08:09Z">' + f'<script type="application/ld+json">{ld}</script>')
# JSON-LD with only a name, or only a description, next to the <title> pair
ld_name = json.dumps({"@type": "VideoObject", "name": "LD name", "thumbnailUrl": f"{base}/1.webp"})
ld_desc = json.dumps({"@type": "VideoObject", "description": "LD description", "thumbnailUrl": f"{base}/1.webp"})
for name, block in (("jsonld-name-only-pair", ld_name), ("jsonld-desc-only-pair", ld_desc)):
    (out / f"{name}.html").write_text(f'<!doctype html><html><head><title>PAIR title</title><meta name="description" content="PAIR description"><script type="application/ld+json">{block}</script></head><body></body></html>')
# ── Round 6: which VideoObject properties Discord needs before it reads one at all ──
page("pubdate-only", og("pubdate only") + '<meta property="og:pubdate" content="2025-05-06T07:08:09Z">')
full = {"@context": "https://schema.org", "@type": "VideoObject", "name": "LD name", "description": "LD description",
        "thumbnailUrl": f"{base}/1.webp", "uploadDate": "2026-01-02T03:04:05Z"}
for missing in ("@context", "name", "description", "thumbnailUrl", "uploadDate", "nothing"):
    block = json.dumps({k: v for k, v in full.items() if k != missing})
    (out / f"ld-no-{missing.lower().lstrip('@')}-pair.html").write_text(f'<!doctype html><html><head><title>PAIR title</title><meta name="description" content="PAIR description"><script type="application/ld+json">{block}</script></head><body></body></html>')
# ── Round 7: @context forms, and whether a video URL can stand in for it ──
variants = {
    "http-context": {"@context": "http://schema.org"},
    "slash-context": {"@context": "https://schema.org/"},
    "embedurl": {"embedUrl": f"{base}/9.mp4"},
}
for suffix, extra in variants.items():
    base_obj = {k: v for k, v in full.items() if k != "@context"}
    block = json.dumps({**base_obj, **extra})
    (out / f"ld-four-{suffix}-pair.html").write_text(f'<!doctype html><html><head><title>PAIR title</title><meta name="description" content="PAIR description"><script type="application/ld+json">{block}</script></head><body></body></html>')
print("\n".join(sorted(p.name for p in out.glob("*.html"))))
