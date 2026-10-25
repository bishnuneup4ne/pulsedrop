#!/usr/bin/env bash
# Fails when the documents claim a version or a toolchain this tree does not have.
#
# The drift this exists for: README listed VirusTotal rows for releases three minors old,
# Installation called 2.0.1 current after 2.0.2 had been published, CONTRIBUTING named a Gradle
# and compileSdk the build no longer used, and Home called an unpublished draft a release. Each
# was internally consistent and none was checked by anything.
#
# Offline checks compare the docs against this checkout. `--online` also asks GitHub for the
# newest published release and requires Installation and README to name it.
set -euo pipefail
cd "$(dirname "$0")/.."

online=0
[ "${1:-}" = "--online" ] && online=1

fail() {
  echo "version drift: $*" >&2
  exit 1
}

version_name=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)
version_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
compile_sdk=$(sed -n 's/.*release(\([0-9]*\)).*/\1/p' app/build.gradle.kts | head -1)
gradle_version=$(sed -n 's/.*gradle-\([0-9][0-9.]*\)-bin\.zip.*/\1/p' gradle/wrapper/gradle-wrapper.properties | head -1)

[ -n "$version_name" ] || fail "cannot read versionName from app/build.gradle.kts"
[ -n "$version_code" ] || fail "cannot read versionCode from app/build.gradle.kts"
[ -n "$compile_sdk" ] || fail "cannot read compileSdk from app/build.gradle.kts"
[ -n "$gradle_version" ] || fail "cannot read the Gradle version from the wrapper properties"

# What a checkout builds is stated separately from what is published, so a draft version is allowed
# here as long as the docs say it is a draft.
grep -q "versionName \`$version_name\`, versionCode \`$version_code\`" docs/Home.md \
  || fail "docs/Home.md must say 'versionName \`$version_name\`, versionCode \`$version_code\`'"
grep -q "\`$version_name\` in this tree" docs/Protocol-V2.md \
  || fail "docs/Protocol-V2.md must name '$version_name in this tree' as the /api/info version"

grep -q "Gradle $gradle_version" CONTRIBUTING.md \
  || fail "CONTRIBUTING.md must name the wrapper's Gradle $gradle_version"
grep -q "\`compileSdk\` $compile_sdk\b" CONTRIBUTING.md \
  || fail "CONTRIBUTING.md must name this build's compileSdk $compile_sdk"

# The worked example and the README references must be the same artifact.
install_hash=$(grep -o 'SHA-256 `[0-9a-f]\{64\}`' docs/Installation.md | head -1 | grep -o '[0-9a-f]\{64\}' || true)
[ -n "$install_hash" ] || fail "docs/Installation.md has no reference SHA-256"
grep -q "$install_hash" README.md \
  || fail "the SHA-256 in docs/Installation.md does not appear in README.md"

if [ "$online" = "1" ]; then
  repo=${GITHUB_REPOSITORY:-bishnuneup4ne/pulsedrop}
  headers=()
  [ -n "${GITHUB_TOKEN:-}" ] && headers=(-H "Authorization: Bearer $GITHUB_TOKEN")
  latest=$(curl -fsS "${headers[@]}" "https://api.github.com/repos/$repo/releases/latest" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["tag_name"].lstrip("v"))') \
    || fail "could not ask GitHub for the newest published release of $repo"

  grep -q "newest published release at the time of writing, \*\*$latest\*\*" docs/Installation.md \
    || fail "docs/Installation.md must name the newest published release, $latest"
  readme_first=$(sed -n 's/^- \*\*v\([0-9][0-9.]*\)\*\* .*/\1/p' README.md | head -1)
  [ "$readme_first" = "$latest" ] \
    || fail "README.md's newest report row is v${readme_first:-none}, but the newest release is $latest"
fi

if [ "$online" = "1" ]; then
  echo "version references agree with this tree and the newest published release"
else
  echo "version references agree with this tree"
fi
