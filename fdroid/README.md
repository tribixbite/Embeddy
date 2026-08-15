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

Base is `MAJOR*10000 + MINOR*100 + PATCH`; each split APK gets `base*10 + N`
where N is 1 (armeabi-v7a), 2 (arm64-v8a), 3 (x86_64). `VercodeOperation` in the
recipe reproduces that arithmetic, so the two must stay in sync. The build fails
if MINOR or PATCH reaches 100, because that would collide with the next place
value and could emit a versionCode below one already published.

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

`fdroid/app.embeddy.yml` pins three `Builds` entries (one per ABI) to a specific
tag. Those are only the starting point — `UpdateCheckMode: HTTP` plus
`AutoUpdateMode: Version v%v` let F-Droid pick up later releases on its own once
the app is in, deriving each ABI's versionCode via `VercodeOperation`.

Before opening the merge request, set the three `Builds` entries,
`CurrentVersion` and `CurrentVersionCode` to the newest tag, and make sure
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists for it
(CI generates that file automatically — it uses the *base* versionCode).

`release.yml` only cuts a release when something affecting the APK changes
(`app/**`, `gradle/**`, the build scripts, `version.properties`), so tags track
real app changes rather than every documentation push.

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
   scripts/generate-release-key.sh
   ```

   It writes to `../embeddy-release.jks` (outside the repo, so it cannot be
   committed), refuses to overwrite an existing keystore, and never takes a
   password as an argument — `keytool` prompts, so nothing lands in your shell
   history or the process table. It then prints the exact `gh secret set`
   commands for step 2.

2. Add repository secrets: `KEYSTORE_BASE64` (`base64 -w0 embeddy-release.jks`),
   `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

   **Run these from a real terminal.** `gh secret set NAME` with no `--body`
   reads the value from stdin, so running it anywhere without a TTY — a script,
   a CI step, an editor's shell integration — silently stores an *empty*
   string. The secret then appears in `gh secret list` and looks fine, but
   arrives at the workflow blank. In a step's env dump a non-empty secret shows
   as `***` and an empty one shows as nothing, which is the quickest way to
   tell them apart.

   To set one non-interactively without putting the value in shell history:

   ```sh
   read -rs PW && printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD && unset PW
   ```

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
