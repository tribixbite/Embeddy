# F-Droid submission

Everything F-Droid needs from this repository is in place. Submission itself is
a manual step against F-Droid's own infrastructure and has not been done — see
[Submitting](#submitting) below.

## What is ready

| Requirement | Status |
|---|---|
| FOSS licence | GPL-3.0-only (`LICENSE`) |
| Public source, git tags per release | tags match `^v[0-9.]+$` |
| Reproducible version from a tag | Fixed — see [Versioning](#versioning) |
| Native code built from source | FFmpeg compiled by the recipe |
| Single APK per build | `./gradlew assembleRelease -PsingleApk` |
| Listing text, changelogs, screenshots | `fastlane/metadata/android/en-US/` |
| Build recipe | `fdroid/app.embeddy.yml` (draft) |
| No Google Play Services / analytics / ads / tracking | Verified — no such dependency |

## Versioning

F-Droid builds a git tag in a clean checkout with no environment variables set.
Previously the version existed only in the CI job's environment
(`BUILD_NUMBER=${{ github.run_number }}`), so `version.properties` stayed at a
stale patch number and a build at tag `v0.1.40` produced `versionName 0.1.13` —
a mismatch F-Droid rejects.

The release workflow now writes the resolved version into `version.properties`,
commits it with a skip-CI marker so the push does not re-trigger the workflow,
and tags *that* commit. Building a tag with no environment overrides reproduces
the published version:

```sh
git checkout v0.1.40
./gradlew assembleRelease -PsingleApk    # -> versionName 0.1.40, versionCode 140
```

## Dependency provenance

**FFmpeg is built from source by the recipe** — see [Decision](#decision-build-from-source)
below for how and why. AVIF encoding still uses a prebuilt,
`com.github.awxkee:avif-coder` via JitPack, which is in `scanner.py`'s
`allowed_repos`; JitPack builds from public GitHub source, so provenance is
traceable even though its builds are not reproducible.

The rest of this section records why the FFmpeg decision went the way it did, so
the reasoning survives if someone revisits it.

Ordinary (non-F-Droid) builds resolve `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1`
from Maven Central, source at <https://github.com/moizhassankh/ffmpeg-kit-android-16KB>.
Upstream `arthenica/ffmpeg-kit` was retired on 2025-01-06 and its binaries were
pulled from Maven Central on 2025-04-01, so the official artifact no longer
exists; this is a community rebuild that also adds the 16 KB page-size support
API 35 requires.

### What other F-Droid apps actually do

Surveyed from a clone of `fdroiddata` (9029 apps, 591 srclibs) rather than from
policy reading:

| Approach | Apps |
|---|---|
| Build FFmpeg (or ffmpeg-kit) from source in the recipe | ~24 |
| Consume a prebuilt FFmpeg AAR from Maven Central | 2 confirmed (Seal, YTDLnis) |

From-source is the dominant pattern by a wide margin. But the two prebuilt cases
are close precedent for us: **Seal** (`com.junkfood.seal`) and **YTDLnis**
(`com.deniscerri.ytdl`) both ship a third-party *republished* FFmpeg AAR
(`io.github.junkfood02.youtubedl-android:ffmpeg`, republished by someone other
than the upstream author) as a plain gradle dependency — no srclib, no
`scandelete`, no from-source step — and both are live in the official repo.

Two further findings that matter:

- **No reviewer objection to a prebuilt FFmpeg from a trusted Maven repo was
  found** in `fdroid/rfp` or `fdroiddata` issues and merge requests. The
  from-source recipes were driven by AARs *committed into the app's own git
  repo* (which F-Droid always requires be deleted and rebuilt) — a different
  situation from a Maven coordinate.
- The automated scanner passes us. `fdroidserver/scanner.py` lists
  `repo1.maven.org/maven2` and `jitpack.io` in `allowed_repos`, and only
  raises `unknown maven repo` for repos outside that list. It never inspects
  artifact contents.

So the current dependency is defensible, not disqualifying. The weaknesses are
about provenance quality rather than policy:

- **The declared licence is wrong.** The POM says LGPL-3.0, but the shipped
  binary is configured `--enable-gpl --enable-version3`, which makes it
  GPL-3.0. Harmless for a GPL-3.0-only app, but "simply being included in one of
  those repositories is not enough" is aimed at exactly this.
- **No traceable provenance.** No `-sources.jar` is published, and the Maven
  publish step is not in CI. The configure line baked into `libavutil.so` shows
  it was built from a personal macOS home directory, not a reproducible pipeline.
- **It tracks a retired 6.1.1** — no security updates.

### Decision: build from source

`app.embeddy.yml` compiles FFmpeg from source. That removes every weakness above
— no licence-metadata mismatch, no untraceable binary, and the FFmpeg version is
ours to move — and it matches what most FFmpeg apps in F-Droid already do.

The recipe mirrors `com.fadcam`'s accepted build: same `anonfaded-ffmpeg-kit-16KB`
srclib, **pinned to the same commit** (`d633c47`), same `ndk: r27`. Deliberately
the proven combination rather than the srclib's newer HEAD, which has never been
built on the buildserver. It differs only in enabling far fewer libraries.

Two details that are easy to get wrong and are worth preserving:

- **`--enable-libwebp` is load-bearing.** FFmpeg's `configure` probes libwebpmux
  with `check_pkg_config`, not `require_pkg_config`, so omitting it *silently*
  drops the `libwebp_anim` encoder. The build succeeds and every conversion then
  fails at runtime with `Unknown encoder 'libwebp_anim'`.
- **`--enable-gpl` is needed by exactly one filter**, `hqdn3d` (denoise).
  Verified against FFmpeg's `configure`: `hqdn3d_filter_deps="gpl"`, while
  `scale`, `fps`, `crop`, `unsharp`, `palettegen`, `paletteuse`, `trim`,
  `setpts` and `concat` have no GPL dependency. The app is GPL-3.0-only so this
  costs nothing — but if denoise is ever dropped, drop the flag with it.

### No build-script patching

The recipe never `sed`s `build.gradle.kts`. `app/build.gradle.kts` picks up
`app/libs/ffmpeg-kit.aar` when that file exists and falls back to the Maven
coordinate otherwise, and `-PsingleApk` is a supported flag. A sed patch is the
usual approach in other recipes, and it silently stops applying the moment the
patched lines are reformatted.

Because a `files()` AAR carries no POM, ffmpeg-kit's transitive
`com.arthenica:smart-exception-java` is declared explicitly on that path.

The local-AAR path is verified end to end: staging a real ffmpeg-kit AAR at
`app/libs/ffmpeg-kit.aar` builds, resolves `smart-exception-java`, drops the
Maven coordinate, installs, and converts a 30-frame animated WebP on device with
`libwebp_anim` present.

`app/libs/*.aar` is gitignored — F-Droid requires in-repo binaries be deleted and
rebuilt, and committing one is what forces most other apps into `rm`/`scandelete`
workarounds.

### Alternatives considered

**Keep the Maven prebuilt.** Zero effort, scanner-clean, with live precedent
(Seal, YTDLnis). Rejected because the from-source path turned out to be cheap —
no new srclib, a proven toolchain — and it leaves the licence-metadata and
provenance problems unanswered.

**FFmpegKitNext.** The official successor, current with FFmpeg 8.x, has libwebp.
Rejected *for now*: distributed as source only, its Android entry point is a Nix
flake (no fdroiddata recipe uses Nix), and its API is now Kotlin — a real code
migration. A reasonable 12-month target, a bad submission blocker.

**Dropping FFmpeg.** `WebPFrameSplitter` already decodes animated WebP without it
and `MediaCodec` can decode video, but FFmpeg still does the whole filter chain
and the `libwebp_anim` encoding. That is a rewrite, not a flag change.

### Size note

FFmpeg is 31.5 MB of the 44.6 MB of arm64 native code (71%), with `libavcodec`
alone at 18 MB. The build enables 24 external libraries — libx265, libaom,
libopenh264, libvpx, libass, even libtesseract (OCR) — of which Embeddy uses
only libwebp. Going the from-source route is also the opportunity to configure a
minimal build and cut most of that, which would shrink the universal APK well
below its current ~105 MB.

**AVIF** — `com.github.awxkee:avif-coder` via JitPack. JitPack builds from a
public GitHub source, so provenance is traceable, but JitPack does not offer
reproducible or verifiable builds. Moving this to a Maven Central release would
strengthen it.

## Anti-features

None expected. Worth stating in the merge request so the reviewer does not have
to work it out:

- The app has no proprietary backend. The website's Cloudflare Worker is used by
  the *browser* tools only; the Android app talks to hosts directly.
- Network access is limited to a URL the user types (Inspect) and a file the
  user chooses to upload (Upload → 0x0.st or catbox.moe). Both are optional and
  user-initiated.
- No account, no telemetry, no crash reporting.

## Keeping the recipe current

`fdroid/app.embeddy.yml` pins one `Builds` entry to a specific tag. That entry is
only the starting point — `UpdateCheckMode: Tags` and `AutoUpdateMode: Version v%v`
let F-Droid pick up later tags on its own once the app is in.

Before opening the merge request, set the `Builds` entry, `CurrentVersion` and
`CurrentVersionCode` to the newest tag, and make sure
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists for it.

Note that `release.yml` currently cuts a release on **every** push to `main`,
including documentation-only pushes, so the newest tag moves faster than the app
actually changes. Restricting the release job to pushes that touch `app/**`,
`gradle/**` or `version.properties` would make versions correspond to real
changes — worth doing before submission, but it changes release cadence, so it
is left as a decision for the maintainer.

## Submitting

F-Droid submission happens on F-Droid's GitLab, not here. It is deliberately
left as a human step — it posts publicly under your account.

1. Open a Request For Packaging issue at
   <https://gitlab.com/fdroid/rfp/-/issues> using the "App Inclusion" template.
2. Fork <https://gitlab.com/fdroid/fdroiddata>, copy `fdroid/app.embeddy.yml` to
   `metadata/app.embeddy.yml`, and open a merge request referencing the RFP.
3. Before opening the MR, run F-Droid's own checks locally:

   ```sh
   fdroid lint app.embeddy
   fdroid readmeta
   fdroid build app.embeddy:140
   ```

4. Expect the reviewer to ask about the FFmpeg artifact. The answer is in
   [Dependency provenance](#dependency-provenance--the-reviewers-likely-question).

## Signing

**This does not affect F-Droid** — F-Droid signs with its own key, so its builds
are self-consistent. It does affect the GitHub releases.

`app/build.gradle.kts` falls back to the debug signing config when
`KEYSTORE_PATH` is unset, and `release.yml` sets no keystore secrets. The debug
keystore is generated per machine, so every CI run signs with a *different* key.
Confirmed on device: installing `v0.1.44` over `v0.1.40` fails with

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package app.embeddy
signatures do not match newer version; ignoring!
```

So nobody can update in place from a GitHub release — they have to uninstall
first and lose their settings. The build now prints a loud warning when it takes
this path, but the real fix needs a stable key, which only the maintainer can
create:

1. Generate a release keystore and keep it somewhere safe and backed up. Losing
   it means never being able to update the app in place again.

   ```sh
   keytool -genkey -v -keystore embeddy-release.jks -keyalg RSA \
     -keysize 4096 -validity 10000 -alias embeddy
   ```

2. Add repository secrets: `KEYSTORE_BASE64` (`base64 -w0 embeddy-release.jks`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

3. In `release.yml`, before the build step, materialise it and point the existing
   env vars at it:

   ```yaml
   - name: Decode keystore
     env:
       KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
     run: echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"
   ```

   then add to the build step's `env:`

   ```yaml
   KEYSTORE_PATH: ${{ runner.temp }}/release.jks
   KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
   KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
   KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
   ```

`build.gradle.kts` already reads all four, so no build-script change is needed.

Note the first properly-signed release will still be uninstall-and-reinstall for
existing users, since their installed copy carries a throwaway debug key.

## Open item for the maintainer

The licence is ambiguous in one respect: `LICENSE` is the plain GPL-3.0 text and
nothing in the repository says "or any later version". `GPL-3.0-only` is
therefore the correct SPDX identifier and is what the recipe declares. If you
intended "or later", add the standard notice to the source headers and change
the recipe to `GPL-3.0-or-later`.
