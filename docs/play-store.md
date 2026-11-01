# Google Play release — owner checklist

The single list of what is left for the account owner to do, in order, with
the commands to run. Everything that can be done from the repo is done; the
remaining steps need your Google account, your keystore password, or a public
post under your name.

Commands assume this repo is at `~/git/Embeddy` and the release keystore is at
`~/git/embeddy-release.jks` (where `scripts/generate-release-key.sh` put it).

## Already done (verified 2026-11-01)

| Item | State |
|---|---|
| Release signing key | `~/git/embeddy-release.jks` — PKCS12, alias `embeddy`, RSA 4096, valid until 2053-12-29. Created 2026-08-12. |
| Certificate SHA-256 | `61:2C:62:1B:E9:E5:CC:8E:5C:24:FB:BA:11:F5:22:AD:5A:EF:21:EA:20:D0:7C:EB:22:C2:67:1F:DD:2E:94:FE` |
| CI secrets | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` are set; v0.1.55's APKs **and** its `.aab` are signed with the key above (checked with `apksigner` / `keytool -printcert -jarfile`). F-Droid's recipe pins the same SHA-256. |
| App Bundle | Every release run uploads `embeddy-v<ver>-play-bundle` (kept 90 days). Contains arm64-v8a, armeabi-v7a, x86, x86_64. |
| versionCode | One ordering across channels: universal APK `base*10+0` < split APKs `+1..3` < Play bundle `+9` < next release. A user can move between GitHub, F-Droid and Play without a "downgrade" block. (`app/build.gradle.kts`, `PLAY_BUNDLE_VERSION_SUFFIX`) |
| Target API | `targetSdk = 36` (required for new apps since 2026-08-31) |
| 16 KB page size | All bundled `.so` files are 16 KB-aligned (`ffmpeg-kit-16kb`) |
| Permissions | `INTERNET`; `WRITE_EXTERNAL_STORAGE` on API ≤ 28 only. No `READ_MEDIA_*`, no foreground service (WorkManager's is stripped), no advertising ID. Files come from the system picker or share intents. |
| Privacy policy | https://embeddy.link/privacy/ (also linked in-app under About) |
| Listing text and art | `fastlane/metadata/android/en-US/` — title, short and full description, 512² icon, 1024×500 feature graphic, six 900×1800 phone screenshots |

## 1. Back up the keystore (do this first)

If this file or its password is lost, Play installs can still be updated (Play
holds the signing key after step 4), but GitHub and F-Droid installs never can.
The GitHub secret is not a backup — secrets cannot be read back.

```sh
# Confirm the password you have on record opens it (prompts for the password)
keytool -list -v -keystore ~/git/embeddy-release.jks -alias embeddy | rg 'SHA256:|Valid'
# Expect SHA256: 61:2C:62:1B:...:2E:94:FE
```

Copy `~/git/embeddy-release.jks` plus its password into your password manager
or another offline location. If the password does not open it, stop here: the
CI secret still works for signing, but you need the password for step 4.

## 2. Developer account

1. Sign in at https://play.google.com/console with the Google account that
   should own the app. Pay the one-time US$25 fee if the account is new.
2. Choose **Personal** account type, complete identity verification (government
   ID) and phone verification of the contact number.
3. Note the account creation date. **Personal accounts created after
   2023-11-13 must run a closed test with ≥ 12 opted-in testers for 14
   consecutive days** before production access (step 7). Older accounts skip it.

## 3. Create the app

Play Console → **Create app**:

| Field | Value |
|---|---|
| App name | `Embeddy` |
| Default language | English (United States) – en-US |
| App or game | App |
| Free or paid | Free (cannot be changed to paid later) |
| Declarations | tick Developer Program Policies and US export laws |

The package name `app.embeddy` is set by the first bundle you upload and is
permanent.

## 4. App signing — use the existing key

Recommended: give Play the **same key** that signs the GitHub and F-Droid
APKs. Then a user can switch between channels without uninstalling, and one key
covers Google's Android developer verification for all three channels (Play
apps are registered automatically; sideloaded APKs must be signed by a key
registered to you).

Play Console → your app → **Test and release → App integrity → App signing**
(this page also appears the first time you create a release):

1. Choose **Use a different key** → **Export and upload a key from Java
   keystore**.
2. Download `pepk.jar` and the encryption public key the page offers
   (e.g. to `~/storage/downloads/`).
3. Run the command the page shows. Copy it from the console — the encryption
   key path is specific to your app and the flags have changed between pepk
   versions. It will look like this:

   ```sh
   cd ~/storage/downloads
   java -jar pepk.jar \
     --keystore="$HOME/git/embeddy-release.jks" \
     --alias=embeddy \
     --output=embeddy-signing-key.zip \
     --include-cert \
     --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   # prompts for the keystore and key passwords
   ```

4. Upload `embeddy-signing-key.zip`, then **delete it** (`rm
   embeddy-signing-key.zip`) — it contains the encrypted private key.
5. Upload key: CI signs the bundle with this same key, so it is also your
   upload key. Play accepts that. After the first upload, the App signing page
   should show the same SHA-256 (`61:2C:…:94:FE`) for both the app signing key
   and the upload key.

<details>
<summary>Optional: a separate upload key</summary>

Google recommends a separate upload key so a leaked CI secret can be revoked
without losing the app identity. This repo does not do it yet because CI signs
the APKs and the bundle with one keystore; splitting them needs new secrets and
a `release.yml` change (marked with a `TODO` above the bundle step). To create one:

```sh
keytool -genkeypair -v -keystore ~/git/embeddy-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
keytool -export -rfc -keystore ~/git/embeddy-upload.jks -alias upload \
  -file ~/storage/downloads/upload_certificate.pem
```

Then upload `upload_certificate.pem` on the App signing page.
</details>

## 5. Get the bundle

The release workflow builds it on every app change. Download the newest one:

```sh
cd ~/storage/downloads
RUN=$(gh run list --repo tribixbite/Embeddy --workflow release.yml --status success \
  --limit 1 --json databaseId --jq '.[0].databaseId')
gh run download "$RUN" --repo tribixbite/Embeddy --pattern 'embeddy-v*-play-bundle'
ls embeddy-v*-play-bundle/

# Confirm it is signed with the release key (expect SHA256: 61:2C:...:94:FE)
keytool -printcert -jarfile embeddy-v*-play-bundle/*.aab | rg 'SHA256:'
```

Artifacts expire after 90 days; download the one you are about to publish.

## 6. Store listing and app content

### Main store listing (Grow users → Store presence → Main store listing)

Print paste-ready text (the repo files are hard-wrapped; this unwraps them and
checks Play's limits):

```sh
cd ~/git/Embeddy
python3 scripts/play-listing-text.py title
python3 scripts/play-listing-text.py short
python3 scripts/play-listing-text.py full
```

Graphics, from `fastlane/metadata/android/en-US/images/`:

| Field | File |
|---|---|
| App icon | `icon.png` |
| Feature graphic | `featureGraphic.png` |
| Phone screenshots | `phoneScreenshots/01-trim.png` … `06-squoosh.png` (upload in order) |

To get them onto the phone for the browser upload:

```sh
cp -r ~/git/Embeddy/fastlane/metadata/android/en-US/images ~/storage/downloads/embeddy-store-images
```

Store settings: category **Video Players & Editors**; contact email of your
choice; website `https://embeddy.link/`.

### App content (Policy and programs → App content)

| Declaration | Answer |
|---|---|
| Privacy policy | `https://embeddy.link/privacy/` |
| App access | All functionality is available without special access |
| Ads | No, the app does not contain ads |
| Content rating | Fill in the IARC questionnaire: category *All other app types*; answer **No** to violence, sexual content, profanity, drugs, gambling, and "users can interact or exchange content" (Upload makes a link the user shares elsewhere; there is no in-app exchange between users). Expected result: Everyone / PEGI 3. |
| Target audience | 18 and over (avoids the Families policy; not designed for children) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | No health features |
| Advertising ID | No (the merged manifest has no `AD_ID` permission) |
| Data safety | See below |

### Data safety form

Derived from what the code actually sends (`upload/UploadEngine.kt`,
`inspect/MetadataEngine.kt`); revisit if either changes.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
  (HTTPS only; cleartext is blocked at targetSdk ≥ 28, so an `http://` URL in
  Inspect fails rather than being sent in cleartext)
- Do you provide a way for users to request that their data is deleted? **No**
  — the developer holds no data; uploaded files are governed by the chosen host.

| Data type | Collected | Shared | Details |
|---|---|---|---|
| Photos and videos; Files and docs | Yes | Yes — with the user-chosen host (0x0.st or catbox.moe) | Only when the user taps Upload. Not processed ephemerally by you; required for that feature; purpose *App functionality*. |
| Web browsing history | No | No | Inspect fetches the URL the user typed directly from the device; nothing goes to the developer. |
| Everything else (location, personal info, app activity, device IDs, crash logs, diagnostics) | No | No | No analytics, crash reporting or ad SDKs. |

## 7. Closed test (only if step 2 says it applies)

1. **Test and release → Testing → Closed testing → Create track** (or use the
   default *Alpha* track).
2. **Testers**: add an email list or a Google Group with **at least 12** people
   (aim for 15–20; anyone who opts out during the 14 days stops counting).
3. **Create release** → upload the `.aab` from step 5. Release notes: paste
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for that
   release (`ls fastlane/metadata/android/en-US/changelogs | tail -3`).
4. **Review release → Start rollout to Closed testing.** First review usually
   takes a few days.
5. Send testers the opt-in link from the track's **Testers** tab. They must
   accept it with the Google account on their phone, then install from Play.
6. Keep ≥ 12 testers opted in for **14 consecutive days**. Upload a newer
   bundle during the test if you can — Google's production-access form asks
   what you changed based on tester feedback.
7. After day 14: **Dashboard → Apply for production** and answer the
   questionnaire (who tested, what feedback, what changed, why it is ready).
   Approval typically takes up to 7 days.

## 8. Production release

1. **Test and release → Production → Create release** → *Add from library*
   (reuse the tested bundle) or upload a newer `.aab`.
2. Countries: all, unless you want to restrict.
3. Start with a **staged rollout** (e.g. 20 %), watch *Android vitals* for
   crashes/ANRs for a few days, then increase to 100 %.

Later releases repeat step 5 and this step. Each CI release's versionCode is
higher than the last, so any new bundle uploads cleanly.

## 9. Things that need your OK before I (the agent) run them

- **Upload-cancel test on a device.** Cancelling mid-upload has not been tested
  on a phone; if cancel failed, the test file would be published on a public
  host. Reply with an OK and it will be done with a generated test image.
- **Saving on Android 8–9 (API 26–28)**: no such device is available; it uses
  the legacy `WRITE_EXTERNAL_STORAGE` path. If you have an old phone, plug it in.

## Appendix: F-Droid (separate, optional)

F-Droid publishes our own signed APKs after reproducing them, so no extra key
is needed. Submission is a public post on GitLab under your account; steps,
commands and the reviewer's likely questions are in
[`fdroid/README.md`](../fdroid/README.md#submitting).
