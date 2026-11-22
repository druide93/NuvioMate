#!/usr/bin/env bash
# NuvioMate: builds a new release that the in-app updater offers.
#
# The app checks the GitHub releases of druide93/NuvioMate. This script:
#   1. picks the next version, e.g. "1.2.0-beta.2.nuviomate.1.3" (Nuvio's base version, untouched,
#      + NuvioMate's own version X.Y),
#   2. builds the release APKs (signed with the release key, so they update the installed NuvioMate),
#   3. copies them to ../apk-nuviomate/<version>/ with names the updater understands,
#   4. tags the commit with the version.
# Then publish the release on GitHub (the script prints the steps).
#
# Two branches, two kinds of release:
#   dev   beta releases (--beta). Work lands on dev, and betas are built from it; main is not touched.
#   main  stable ("prod") releases (no --beta). Merge dev into main once a beta has proved itself,
#         then run this on main: it builds a new version from main.
#
# For the app, stable or beta is decided on GitHub, not by the version: a release marked
# "pre-release" is a beta (only the Beta channel gets it); unticked, both channels get it.
#
# Usage (from the repository root):
#   scripts/nuviomate_release.sh            build the next version, to publish as a stable release
#   scripts/nuviomate_release.sh --beta     the same, to publish as a beta (GitHub page opens pre-ticked)
#   scripts/nuviomate_release.sh --major    NuvioMate X + 1 (e.g. 1.4 -> 2.0) instead of Y + 1
#   scripts/nuviomate_release.sh --dry-run  only show which version would be built
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT_PARENT="$(cd .. && pwd)"
DRY_RUN=false
BETA=false
MAJOR=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    --beta) BETA=true ;;
    --major) MAJOR=true ;;
    *) echo "Unknown option: $arg (use --beta, --major and/or --dry-run)" >&2; exit 1 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME_NUVIOMATE:-$ROOT_PARENT/jdk17/jdk-17.0.21+9/Contents/Home}"
export NUVIO_GO="${NUVIO_GO:-$ROOT_PARENT/go-sdk/go/bin/go}"

# Nuvio's base version, e.g. "1.2.0-beta.2". It is never changed: NuvioMate's own version goes after it.
BASE=$(grep -m1 -E '^\s*versionName = "' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
[ -n "$BASE" ] || { echo "Couldn't read versionName from app/build.gradle.kts" >&2; exit 1; }
# "1.2.0-beta.2" -> "1.2.0-beta.2.nuviomate.X.Y"; "1.2.0" -> "1.2.0-nuviomate.X.Y" (always newer than the base,
# and newer than the earlier "<base>.mate.N" releases).
case "$BASE" in
  *-*) PREFIX="$BASE.nuviomate." ;;
  *)   PREFIX="$BASE-nuviomate." ;;
esac

# Every NuvioMate tag made here or on GitHub.
ALL_TAGS=$( { git tag --list
              git ls-remote --tags origin 2>/dev/null | sed -E 's#.*refs/tags/##; s#\^\{\}##'; } | sort -u)

# NuvioMate's own version X.Y goes on across Nuvio bases: Y + 1 each release, --major for X + 1 (Y back to 0).
# The first release before this scheme ("1.2.0-beta.2.mate.1") counts as 1.0.
MAJ=1; MIN=0
for tag in $(printf '%s\n' "$ALL_TAGS" | grep -E '[.-]nuviomate\.[0-9]+\.[0-9]+$' || true); do
  xy="${tag##*nuviomate.}"; x="${xy%%.*}"; y="${xy#*.}"
  if [ "$x" -gt "$MAJ" ] || { [ "$x" -eq "$MAJ" ] && [ "$y" -gt "$MIN" ]; }; then MAJ=$x; MIN=$y; fi
done
if $MAJOR; then MAJ=$((MAJ + 1)); MIN=0; else MIN=$((MIN + 1)); fi
VERSION="$PREFIX$MAJ.$MIN"
if printf '%s\n' "$ALL_TAGS" | grep -qxF "$VERSION"; then
  echo "Tag $VERSION already exists" >&2; exit 1
fi

# Build number for Android's versionCode (Nuvio's code x 100 + N): one more per release on this Nuvio base,
# old "<base>.mate.N" tags included, so each release installs as an upgrade.
N=$(( $(printf '%s\n' "$ALL_TAGS" | grep -cE "^${BASE//./\\.}[.-](nuvio)?mate\.[0-9.]+$" || true) + 1 ))
[ "$N" -le 99 ] || { echo "Too many NuvioMate builds on Nuvio $BASE (max 99)" >&2; exit 1; }
KIND=stable; $BETA && KIND=beta
echo "Nuvio base $BASE -> NuvioMate $VERSION (NuvioMate $MAJ.$MIN, versionCode build $N), $KIND"
$DRY_RUN && exit 0

# Betas are built from dev, stable releases from main.
BRANCH=$(git symbolic-ref --quiet --short HEAD || true)
if $BETA; then WANTED=dev; else WANTED=main; fi
if [ "$BRANCH" != "$WANTED" ]; then
  if $BETA; then
    echo "Beta releases are built from dev, not '${BRANCH:-detached HEAD}': switch to dev and run this again." >&2
  else
    echo "Stable releases are built from main, not '${BRANCH:-detached HEAD}': merge dev into main and run this on main (or add --beta for a beta from dev)." >&2
  fi
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "There are uncommitted changes: commit them first, so the tag matches what is built." >&2
  exit 1
fi

./gradlew :app:assembleFullRelease \
  -x lintVitalFullRelease -x lintVitalAnalyzeFullRelease \
  -PlivetvBuildNumber="$N" -PlivetvVersionName="$VERSION"

OUT="$ROOT_PARENT/apk-nuviomate/$VERSION"
mkdir -p "$OUT"
found=false
for f in app/build/outputs/apk/full/release/*.apk; do
  [ -e "$f" ] || continue
  name=$(basename "$f" .apk)        # app-full-armeabi-v7a-release
  abi=${name#app-full-}; abi=${abi%-release}
  cp "$f" "$OUT/NuvioMate-$VERSION-$abi.apk"
  found=true
done
$found || { echo "No APK was built" >&2; exit 1; }

git tag -a "$VERSION" -m "NuvioMate $VERSION"

URL="https://github.com/druide93/NuvioMate/releases/new?tag=$VERSION&title=NuvioMate%20$VERSION"
if $BETA; then
  URL="$URL&prerelease=1"
  BOX='"Set as a pre-release" is ticked: only the Beta channel gets it. Untick it later to make it the stable release.'
else
  BOX='Leave "Set as a pre-release" unticked: both channels get it.'
fi

cat <<EOF

NuvioMate $VERSION ($KIND) is built: $OUT
$(ls -1 "$OUT")

To publish it (the installed NuvioMate apps then offer the update):
  1. In GitHub Desktop, push $WANTED (the tag goes with it).
  2. Open $URL
     write what changed, drag the APKs above into the page, and click "Publish release".
     $BOX
EOF
