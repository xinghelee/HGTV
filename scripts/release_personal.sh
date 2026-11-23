#!/usr/bin/env bash
# Build and publish a personal release to xinghelee/HGTV so the in-app updater can find it.
# Usage: scripts/release_personal.sh [notes]
# Override the version with HONGGUOTV_VERSION_NAME / HONGGUOTV_VERSION_CODE when releasing
# personal changes between upstream versions (versionCode must exceed the installed one).
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
REPOSITORY=xinghelee/HGTV
notes="${1:-个人构建}"

[[ -z "$(git status --porcelain)" ]] || { echo "Working tree not clean" >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"

gradle_file=kotlin-tv/app/build.gradle.kts
version="${HONGGUOTV_VERSION_NAME:-$(sed -n 's/.*versionName = .*?: "\([^"]*\)".*/\1/p' "$gradle_file")}"
code="${HONGGUOTV_VERSION_CODE:-$(sed -n 's/.*versionCode = .*?: \([0-9]*\).*/\1/p' "$gradle_file")}"
tag="kotlin-v$version"
export HONGGUOTV_VERSION_NAME="$version" HONGGUOTV_VERSION_CODE="$code"

./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:assembleRelease --console=plain

out="$repo_dir/outputs/release-$tag"
rm -rf "$out" && mkdir -p "$out"
built=kotlin-tv/app/build/platform/outputs/apk/release
apk="hongguotv-kotlin-$version-android6.apk"
cp "$built/app-universal-release.apk" "$out/$apk"
cp "$built/app-armeabi-v7a-release.apk" "$out/hongguotv-kotlin-$version-android6-arm32-v7a.apk"
cp "$built/app-arm64-v8a-release.apk" "$out/hongguotv-kotlin-$version-android6-arm64-v8a.apk"

size=$(stat -f %z "$out/$apk")
sum=$(shasum -a 256 "$out/$apk" | cut -d' ' -f1)
NOTES="$notes" VERSION="$version" CODE="$code" TAG="$tag" APK="$apk" SIZE="$size" SUM="$sum" \
  COMMIT="$(git rev-parse HEAD)" python3 - > "$out/update.json" <<'PY'
import json, os
e = os.environ
entry = dict(name=e['APK'], size=int(e['SIZE']), sha256=e['SUM'])
print(json.dumps(dict(schema=1, packageName='com.hongguotv.nativeapp', versionCode=int(e['CODE']),
    versionName=e['VERSION'], minSdk=23, tag=e['TAG'], sourceCommit=e['COMMIT'], notes=e['NOTES'],
    apk=entry, platformApk=entry), ensure_ascii=False, indent=2))
PY
(cd "$out" && shasum -a 256 *.apk update.json > SHA256SUMS)

git tag -f "$tag"
git push origin HEAD "refs/tags/$tag" --force
gh release create "$tag" --repo "$REPOSITORY" --title "红果短剧 TV $version" --notes "$notes" --latest "$out"/*
echo "Published $tag ($code) to https://github.com/$REPOSITORY/releases/tag/$tag"
