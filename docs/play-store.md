# Google Play submission guide

What is already done in the repo, and what has to be done by the account owner
in Play Console. Keep this in sync with the code: the Data safety answers below
are derived from what the app actually sends over the network
(`upload/UploadEngine.kt`, `inspect/MetadataEngine.kt`) and must change if
either does.

## Technical requirements — status

| Requirement | Status |
|---|---|
| Target API 36 (required for new apps/updates since 2026-08-31) | `targetSdk = 36`, `compileSdk = 36` in `gradle/libs.versions.toml` |
| Android App Bundle | `./gradlew bundleRelease`; CI uploads `embeddy-v<ver>-play-bundle` artifact (`.github/workflows/release.yml`) |
| 16 KB page size (API 35+ targets) | All 18 bundled `.so` files have 0x4000 LOAD alignment (checked with `llvm-readelf -lW`) |
| Photo & video permissions policy | No `READ_MEDIA_*` / `READ_EXTERNAL_STORAGE`; all files come from the system picker (SAF) or share intents |
| Edge-to-edge (enforced at API 35+) | `enableEdgeToEdge()` + inset-aware Scaffold |
| Predictive back | `android:enableOnBackInvokedCallback="true"` |
| Backup | `dataExtractionRules` / `fullBackupContent` back up preferences only |
| Privacy policy URL | https://embeddy.link/privacy/ (`site/src/pages/privacy.astro`), also linked in-app |
| versionCode monotonic | `MAJOR*1_000_000 + MINOR*10_000 + PATCH`; see `fdroid/README.md` |

## Store listing assets (`fastlane/metadata/android/en-US/`)

| Asset | Spec | File |
|---|---|---|
| App name | ≤30 chars | `title.txt` |
| Short description | ≤80 chars | `short_description.txt` |
| Full description | ≤4000 chars | `full_description.txt` |
| Hi-res icon | 512×512 PNG | `images/icon.png` (`scripts/generate-store-assets.sh`) |
| Feature graphic | 1024×500 PNG/JPEG | `images/featureGraphic.png` (same script) |
| Phone screenshots | 2–8, 320–3840 px, aspect ≤ 2:1 | `images/phoneScreenshots/` |

## Signing

Use **Play App Signing**. Two options:

1. *Recommended if F-Droid/GitHub users should be able to move to the Play
   build without uninstalling:* in Play Console choose "Use the same key as
   other distribution channels" and upload the existing release key (the one
   whose SHA-256 is pinned in `fdroid/app.embeddy.yml`) via the PEPK tool.
   Then generate a separate **upload key** for CI.
2. Let Google generate the app signing key. Simpler, but Play installs and
   GitHub/F-Droid installs will then be signed differently and cannot update
   each other.

Either way, the AAB uploaded to Play is signed with the upload key; set
`KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` accordingly.

## Testing requirement for new personal accounts

Personal developer accounts created after 2023-11-13 must run a **closed test
with at least 12 opted-in testers for 14 consecutive days** before applying for
production access. Plan for this before the intended launch date.

## Data safety form

Answers reflect the code as of this commit.

- **Does your app collect or share any of the required user data types?** Yes.
- **Is all user data encrypted in transit?** Yes (HTTPS only; cleartext is
  disabled by the default network security config at targetSdk ≥ 28). Note: Inspect accepts
  `http://` URLs typed by the user; those requests fail by design rather than
  being sent in cleartext.
- **Do you provide a way for users to request that their data be deleted?**
  No data is held by the developer. Files uploaded to third-party hosts are
  governed by those hosts.

Data types:

| Type | Collected? | Shared? | Notes |
|---|---|---|---|
| Photos / Videos / Files and docs | Yes — *only when the user taps Upload* | Yes, with the user-selected host (0x0.st or catbox.moe) | User-initiated, not optional for that feature, not processed by the developer. Purpose: App functionality. |
| Web browsing (URL inspected) | No | No | The device fetches the page directly; nothing is sent to the developer. "Collected" in Play's sense means transmitted off-device to the developer or a third party *for the developer's purposes*; a direct request to the site the user typed is the user's own browsing. |
| Device or other IDs, location, personal info, app activity, crash logs, diagnostics | No | No | No analytics, crash reporting or ad SDKs are included. |

Security practices: data encrypted in transit — yes.

## Content rating (IARC questionnaire)

Category: *Utility, productivity, communication or other*. No violence, sexual
content, profanity, controlled substances, gambling or user-to-user
communication features. Note that Upload produces a public link the user can
share — this is not in-app sharing between users, so the "users can interact or
exchange content" question is **No**.

## Other declarations

- **Ads:** No ads.
- **Target audience:** 18+ (avoids the Families policy; the app is a general
  tool and is not designed for children).
- **News app:** No. **Government app:** No. **Financial features:** None.
- **Health:** No.
- **App access:** All functionality is available without login.
