# F-Droid submission

Everything F-Droid needs from this repository is in place. Submission itself is
a manual step against F-Droid's own infrastructure and has not been done — see
[Submitting](#submitting) below.

## What is ready

| Requirement | Status |
|---|---|
| FOSS licence | GPL-3.0-only (`LICENSE`) |
| Public source, git tags per release | `v0.1.40`, tags match `^v[0-9.]+$` |
| Reproducible version from a tag | Fixed — see [Versioning](#versioning) |
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

## Dependency provenance — the reviewer's likely question

Two dependencies ship prebuilt native code. Both are free-licensed and come from
repositories F-Droid lists as trusted for prebuilt FLOSS binaries (Maven Central,
Google Maven, OSS Sonatype, OSS JFrog, JitPack.io, Clojars), so neither is
disqualifying on its face. Neither is built from source by F-Droid, which a
reviewer may still raise.

**FFmpeg** — `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1`, Maven Central, source
at <https://github.com/moizhassankh/ffmpeg-kit-android-16KB>.

Context: upstream `arthenica/ffmpeg-kit` was retired on 2025-01-06 and its
binaries were pulled from Maven Central on 2025-04-01, so the official artifact
no longer exists. This is a community rebuild that also adds the 16 KB page-size
support API 35 requires.

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

### Options, ranked

**1. Build ffmpeg-kit from source in the recipe (recommended).** This is the
dominant pattern, needs no app-code change (the forks keep arthenica's 6.x Java
API), and fixes all three weaknesses above. It does not require writing a new
srclib: `anonfaded-ffmpeg-kit-16KB` already exists in fdroiddata and is used by
FadCam, whose accepted recipe already passes `--enable-libwebp` — direct
evidence that the flag we depend on builds on the F-Droid buildserver. Cost is
build time (comparable recipes set `timeout: 10800`). A sketch is in
[`app.embeddy.yml`](app.embeddy.yml) under `MaintainerNotes`.

**2. Keep the current Maven dependency.** Zero effort, scanner-clean, with live
precedent. Accept the licence-metadata and provenance weaknesses above, and be
ready to answer for them in the merge request.

**3. Migrate to FFmpegKitNext.** The official successor, current with FFmpeg 8.x,
and it has libwebp. Rank it last for *this* submission regardless: it is
distributed as source only, its Android entry point is a Nix flake (no
fdroiddata recipe uses Nix), and its API is now Kotlin — a real code migration.
A reasonable 12-month target, a bad submission blocker.

**Not recommended: dropping FFmpeg.** `WebPFrameSplitter` already decodes
animated WebP without it and `MediaCodec` can decode video, but FFmpeg still
does the whole filter chain and the `libwebp_anim` encoding. That is a rewrite,
not a flag change.

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

## Open item for the maintainer

The licence is ambiguous in one respect: `LICENSE` is the plain GPL-3.0 text and
nothing in the repository says "or any later version". `GPL-3.0-only` is
therefore the correct SPDX identifier and is what the recipe declares. If you
intended "or later", add the standard notice to the source headers and change
the recipe to `GPL-3.0-or-later`.
