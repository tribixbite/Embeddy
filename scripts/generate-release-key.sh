#!/data/data/com.termux/files/usr/bin/bash
#
# Generate the release signing keystore for Embeddy and print the repository
# secrets that CI needs.
#
# Why this exists: without a stable key, release.yml falls back to the debug
# keystore, which is generated per machine — so every CI run signs with a
# different key and users cannot update in place (INSTALL_FAILED_UPDATE_
# INCOMPATIBLE). See fdroid/README.md > Signing.
#
# This script never takes a password on the command line or writes one to disk.
# keytool prompts for them directly, so they stay out of your shell history,
# the process table, and any transcript of this session.
#
# Usage:
#   scripts/generate-release-key.sh [output.jks]
#
# Default output is ../embeddy-release.jks — deliberately OUTSIDE the repo so it
# cannot be committed by accident.

set -euo pipefail

KEYSTORE="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/embeddy-release.jks}"
ALIAS="embeddy"
VALIDITY_DAYS=10000   # ~27 years; Play requires expiry after 2033-10-22 anyway
KEYALG="RSA"
KEYSIZE=4096

die() { printf '\n\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
info() { printf '\033[36m==>\033[0m %s\n' "$1"; }

command -v keytool >/dev/null 2>&1 || die "keytool not found. Install a JDK (pkg install openjdk-17)."

# Refuse to clobber: overwriting a keystore in use means never being able to
# update the installed app again.
if [ -e "$KEYSTORE" ]; then
  die "$KEYSTORE already exists. Refusing to overwrite — this file is irreplaceable.
       Pass a different path if you really want a second key."
fi

case "$KEYSTORE" in
  *"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"*)
    printf '\033[33mwarning:\033[0m %s\n' "that path is inside the git repo; make sure it is gitignored." ;;
esac

cat <<'EOF'

  You will be asked for two passwords (keystore, then key) and some identity
  fields. The identity fields are cosmetic for self-signed Android keys — a name
  and country are enough.

  Use a real password manager. If you lose this file or its password, you can
  never publish an update to an already-installed copy of the app: Android
  identifies an app by its signing key, not its version.

EOF

info "Generating $KEYSIZE-bit $KEYALG key, valid $VALIDITY_DAYS days"
keytool -genkeypair -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg "$KEYALG" \
  -keysize "$KEYSIZE" \
  -validity "$VALIDITY_DAYS"

chmod 600 "$KEYSTORE"
info "Keystore written to $KEYSTORE (mode 600)"

FINGERPRINT=$(keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" 2>/dev/null \
  | grep -m1 'SHA256:' || true)
[ -n "$FINGERPRINT" ] && info "Certificate ${FINGERPRINT#*SHA256: }"

cat <<EOF

  ── Next: add the repository secrets ─────────────────────────────────────────

  Run these. Each will prompt, so the values stay out of your shell history:

    gh secret set KEYSTORE_BASE64 --repo tribixbite/Embeddy < <(base64 -w0 "$KEYSTORE")
    gh secret set KEYSTORE_PASSWORD --repo tribixbite/Embeddy
    gh secret set KEY_ALIAS --repo tribixbite/Embeddy   # value: $ALIAS
    gh secret set KEY_PASSWORD --repo tribixbite/Embeddy

  Then apply the workflow change in fdroid/README.md > Signing, which decodes
  KEYSTORE_BASE64 and points KEYSTORE_PATH at it. build.gradle.kts already reads
  KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD, so no build
  script change is needed.

  ── Back up the keystore now ─────────────────────────────────────────────────

  $KEYSTORE

  The GitHub secret is not a backup — you cannot read a secret back out.

  ── One-time disruption ──────────────────────────────────────────────────────

  Everyone currently running a build from GitHub Releases has a throwaway
  debug-signed copy. Their first update to a properly signed release will fail
  with INSTALL_FAILED_UPDATE_INCOMPATIBLE and needs an uninstall first. Worth a
  line in that release's notes. F-Droid users are unaffected — F-Droid signs
  with its own key.

EOF
