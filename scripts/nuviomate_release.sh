#!/usr/bin/env bash
# NuvioMate: builds a new release that the in-app updater offers.
#
# The app checks the GitHub releases of druide93/NuvioMate. This script:
#   1. picks the next version, e.g. "1.2.0-beta.2.mate.3" (Nuvio's base version + our build number),
#   2. builds the release APKs (signed with the release key, so they update the installed NuvioMate),
#   3. copies them to ../apk-nuviomate/<version>/ with names the updater understands,
#   4. tags the commit with the version.
# Then publish the release on GitHub (the script prints the steps).
#
# Usage (from the repository root):
#   scripts/nuviomate_release.sh            build the next version
#   scripts/nuviomate_release.sh --dry-run  only show which version would be built
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT_PARENT="$(cd .. && pwd)"
DRY_RUN=false
[ "${1:-}" = "--dry-run" ] && DRY_RUN=true

export JAVA_HOME="${JAVA_HOME_NUVIOMATE:-$ROOT_PARENT/jdk17/jdk-17.0.21+9/Contents/Home}"
export NUVIO_GO="${NUVIO_GO:-$ROOT_PARENT/go-sdk/go/bin/go}"

# Nuvio's base version, e.g. "1.2.0-beta.2".
BASE=$(grep -m1 -E '^\s*versionName = "' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
[ -n "$BASE" ] || { echo "Couldn't read versionName from app/build.gradle.kts" >&2; exit 1; }
# "1.2.0-beta.2" -> "1.2.0-beta.2.mate.N"; "1.2.0" -> "1.2.0-mate.N" (always newer than the base).
case "$BASE" in
  *-*) PREFIX="$BASE.mate." ;;
  *)   PREFIX="$BASE-mate." ;;
esac

# Next build number: one more than the highest tag already made here or on GitHub.
LAST=0
for tag in $(git tag --list "${PREFIX}*") \
           $(git ls-remote --tags origin "refs/tags/${PREFIX}*" 2>/dev/null | sed -E 's#.*refs/tags/##; s#\^\{\}##'); do
  n="${tag#"$PREFIX"}"
  case "$n" in ''|*[!0-9]*) continue ;; esac
  [ "$n" -gt "$LAST" ] && LAST=$n
done
N=$((LAST + 1))
[ "$N" -le 99 ] || { echo "Too many NuvioMate builds on Nuvio $BASE (max 99)" >&2; exit 1; }
VERSION="$PREFIX$N"
echo "Nuvio base $BASE -> NuvioMate $VERSION (build $N)"
$DRY_RUN && exit 0

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

cat <<EOF

NuvioMate $VERSION is built: $OUT
$(ls -1 "$OUT")

To publish it (the installed NuvioMate apps then offer the update):
  1. In GitHub Desktop, push main (the tag goes with it).
  2. Open https://github.com/druide93/NuvioMate/releases/new?tag=$VERSION&title=NuvioMate%20$VERSION
     write what changed, drag the APKs above into the page, and click "Publish release".
EOF
