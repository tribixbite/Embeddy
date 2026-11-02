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
| Reproducible per-ABI builds | CI and recipe both use `-PabiFilter=<abi>` |
| Published-binary mode | `binary:` + `AllowedAPKSigningKeys` in the recipe |
| Listing text, changelogs, screenshots | `fastlane/metadata/android/en-US/` |
| Build recipe | `fdroid/tribixbite.embeddy.yml` (draft) |
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
./gradlew assembleRelease -PsingleApk    # -> versionName 0.1.40, versionCode 140 (pre-0.1.53 scheme)
```

## Reproducible builds — how this app is published

Embeddy uses F-Droid's **published-binary** mode rather than letting F-Droid sign
its own APK. F-Droid builds from source, compares the result to the APK on our
GitHub Releases page and, if they match, publishes *our* signed APK. The file on
f-droid.org and the file on GitHub Releases are then the same bytes, so users can
switch between the two without uninstalling.

Two recipe fields drive this:

- `binary:` — the GitHub Releases URL to compare against, per ABI.
- `AllowedAPKSigningKeys:` — SHA-256 of our release signing certificate.

### Build each ABI separately — on both sides

This is the part that is easy to get wrong. **Building all ABIs in one Gradle
invocation does not produce the same per-ABI APK as building that ABI alone.**
Measured locally on arm64-v8a: both APKs had the same 452 entries and matching
CRCs for 451 of them, with one dex differing. Running the *same* command twice
was fully deterministic, so the split configuration itself is the variable.

So CI builds each ABI in its own `clean` invocation with `-PabiFilter=<abi>`,
which is exactly what the recipe does. If you change one side, change the other.

### versionCodes

Base is `MAJOR*1000000 + MINOR*10000 + PATCH`; every artifact gets `base*10 + N`
where N is 0 (universal APK), 1 (armeabi-v7a), 2 (arm64-v8a), 3 (x86_64) or
9 (Play bundle), so a user can move between GitHub, F-Droid and Play without
hitting a lower versionCode. Releases up to 0.1.55 shipped the universal APK and
Play bundle with the bare base code. `VercodeOperation` in the
recipe reproduces that arithmetic, so the two must stay in sync. The build fails
if MINOR reaches 100 or PATCH reaches 10000, because that would collide with the
next place value and could emit a versionCode below one already published.

Releases up to 0.1.52 used `MAJOR*10000 + MINOR*100 + PATCH`. PATCH is the CI
run number, so that scheme would have failed every build from run 100 on; the
widened scheme was chosen because every code it emits (0.1.53 = 10053, per-ABI
100531..100533) sorts above every code published under the old one.

### Update detection

`UpdateCheckMode: HTTP` scrapes the releases page for two literal lines that the
release workflow appends to every set of notes:

```
Version: 0.1.46
VersionCode: 146
```

`UpdateCheckData` matches those exact shapes. Rewording them stops F-Droid
seeing new releases — silently.

## Dependency provenance

FFmpeg is a prebuilt AAR from Maven Central
(`com.moizhassan.ffmpeg:ffmpeg-kit-16kb`), a community rebuild of
`arthenica/ffmpeg-kit` — retired 2025-01-06, binaries pulled from Maven Central
2025-04-01 — that adds the 16 KB page-size support API 35 requires. AVIF uses
`com.github.awxkee:avif-coder` via JitPack. Both repositories are in
`fdroidserver/scanner.py`'s `allowed_repos`, and the scanner never inspects
artifact contents.

Precedent for a prebuilt FFmpeg from Maven Central: **Seal**
(`com.junkfood.seal`) and **YTDLnis** (`com.deniscerri.ytdl`) both consume a
third-party republished FFmpeg AAR as a plain gradle dependency, and both are
live in the official repo. A survey of `fdroiddata` (9029 apps) found no reviewer
objection to a prebuilt FFmpeg from a trusted Maven repo — the from-source
recipes were driven by AARs *committed into app repos*, which is a different
situation.

### Why not build FFmpeg from source

It was implemented, and then deliberately reverted, because **it is incompatible
with reproducible builds**. Verification requires F-Droid's APK to match ours
byte-for-byte. A cross-compiled FFmpeg is not bit-reproducible across machines —
the shipped `.so` files even embed the builder's absolute paths (the current
artifact's configure line names a personal macOS home directory). For the APKs to
match, both sides must consume the identical prebuilt artifact.

That trade is worth naming plainly: reproducible builds buy a verifiable link
between source and published binary, at the cost of trusting one prebuilt
dependency. Building from source would invert it.

`app/build.gradle.kts` still supports dropping a locally built AAR at
`app/libs/ffmpeg-kit.aar` (gitignored) if you ever want the other trade — it is
tested and works, it is just not what the recipe uses.

Known defect in the artifact worth disclosing in the merge request: its POM
declares LGPL-3.0 while the binary is built `--enable-gpl --enable-version3`,
i.e. actually GPL-3.0. Compatible with this app's GPL-3.0-only licence, but the
metadata is wrong.

### Size note

FFmpeg is 31.5 MB of the 44.6 MB of arm64 native code (71%), with `libavcodec`
alone at 18 MB. The build enables 24 external libraries — libx265, libaom,
libopenh264, libvpx, libass, even libtesseract (OCR) — of which Embeddy uses
only libwebp.

Trimming that needs a custom FFmpeg build, which is exactly what reproducible
builds rule out unless the artifact is published somewhere both CI and F-Droid
can fetch the *same* bytes from. The realistic route is publishing a minimal
ffmpeg-kit build to Maven Central under our own account and depending on that —
which would fix the provenance and licence-metadata problems too. Worth doing;
out of scope for the first submission.

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

`fdroid/tribixbite.embeddy.yml` pins three `Builds` entries (one per ABI) to a specific
tag. Those are only the starting point — `UpdateCheckMode: HTTP` plus
`AutoUpdateMode: Version v%v` let F-Droid pick up later releases on its own once
the app is in, deriving each ABI's versionCode via `VercodeOperation`.

Before opening the merge request, set the three `Builds` entries,
`CurrentVersion` and `CurrentVersionCode` to the newest tag, and make sure
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists for each
of its three split versionCodes (CI writes all three, identical, in the version-pin
commit; fdroidserver matches the build's exact versionCode). Releases up to
0.1.55 have only a base-code file, so point the recipe at a later tag.

`release.yml` only cuts a release when something affecting the APK changes
(`app/**`, `gradle/**`, the build scripts, `version.properties`), so tags track
real app changes rather than every documentation push.

## Submitting

F-Droid submission happens on F-Droid's GitLab, not here. It is deliberately
left as a human step — it posts publicly under your account.

1. Open a Request For Packaging issue at
   <https://gitlab.com/fdroid/rfp/-/issues> using the "App Inclusion" template.
2. Fork <https://gitlab.com/fdroid/fdroiddata>, copy `fdroid/tribixbite.embeddy.yml` to
   `metadata/tribixbite.embeddy.yml`, and open a merge request referencing the RFP.
3. Before opening the MR, run F-Droid's own checks locally:

   ```sh
   fdroid lint tribixbite.embeddy
   fdroid readmeta
   fdroid build tribixbite.embeddy:100582   # arm64-v8a of v0.1.58; also 100581, 100583
   ```

4. Expect the reviewer to ask about the FFmpeg artifact. The answer is in
   [Dependency provenance](#dependency-provenance--the-reviewers-likely-question).

## Signing

Done. The release key was created with `scripts/generate-release-key.sh`
(2026-08-12, kept outside the repo at `../embeddy-release.jks`), the four
`KEYSTORE_*`/`KEY_*` secrets are set, and `release.yml` decodes and verifies the
keystore before building. Releases are signed with the certificate pinned
in `AllowedAPKSigningKeys` (checked on v0.1.55's APKs and bundle, 2026-11-01). The Play bundle is signed with
the same key; see [`docs/play-store.md`](../docs/play-store.md) for backing it
up and handing it to Play App Signing.

`app/build.gradle.kts` still falls back to the per-machine debug key when
`KEYSTORE_PATH` is unset (local builds) and prints a warning when it does.

## Open item for the maintainer

The licence is ambiguous in one respect: `LICENSE` is the plain GPL-3.0 text and
nothing in the repository says "or any later version". `GPL-3.0-only` is
therefore the correct SPDX identifier and is what the recipe declares. If you
intended "or later", add the standard notice to the source headers and change
the recipe to `GPL-3.0-or-later`.
