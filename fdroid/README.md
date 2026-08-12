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
commits it with `[skip ci]`, and tags *that* commit. Building a tag with no
environment overrides reproduces the published version:

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

**FFmpeg** — `com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1` (LGPL-3.0, Maven
Central, source at <https://github.com/moizhassankh/ffmpeg-kit-android-16KB>).

This needs context. Upstream `arthenica/ffmpeg-kit` was retired on 2025-01-06
and its binaries were pulled from Maven Central on 2025-04-01, so the official
artifact no longer exists. This is a community rebuild that also adds the 16 KB
page-size support API 35 requires. It is a single-maintainer republish, which is
the weakest link in the supply chain here.

Options if a reviewer objects:

1. **Build FFmpeg from source in the recipe.** Most correct, most work — F-Droid
   would need an NDK toolchain recipe for the whole FFmpeg build. Slow builds.
2. **Vendor a source-built AAR** produced by this project's own CI from the
   upstream source, so provenance traces to a build we control.
3. **Drop FFmpeg for the encode path.** The WebP work no longer strictly needs
   it: `WebPFrameSplitter` already decodes animated WebP without FFmpeg, and
   Android's `MediaCodec` can decode video. FFmpeg is still doing the filter
   chain (scale/fps/denoise/sharpen/dither) and libwebp_anim encoding, so this
   is a real rewrite, not a flag flip.

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
