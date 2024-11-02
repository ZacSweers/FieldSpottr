#!/usr/bin/env bash

set -exo pipefail

# Gets a property out of a .properties file
# usage: getProperty $key $filename
function getProperty() {
  grep "${1}" "$2" | cut -d'=' -f2
}


# Increments an input version string given a version type
# usage: increment_version $current_version $version_type
increment_version() {
    local current_version=$1
    local version_type=$2
    IFS='.' read -r major minor patch <<< "$current_version"
    case "$version_type" in
        major) ((major++)); minor=0; patch=0 ;;
        minor) ((minor++)); patch=0 ;;
        patch) ((patch++)) ;;
    esac
    echo "$major.$minor.$patch"
}

update_property() {
    local file=$1
    local key=$2
    local value=$3

    sed "s/^${key}=.*$/${key}=${value}/" "$file" > "${file}.tmp" && mv "${file}.tmp" "$file"
}

# Increments the fs_versioncode prop in gradle.properties to a new value
# usage: increment_version_code $gradlePropertiesFile
increment_version_code() {
  local properties_file=$1
  if grep -q "fs_versioncode=" "$properties_file"; then
    local prev_version
    prev_version=$(getProperty 'fs_versioncode' "${properties_file}")
    local new_version
    new_version=$((prev_version + 1))
    update_property "${properties_file}" "fs_versioncode" "${new_version}"
    echo "${new_version}"
  fi
}

# TODO eventually generate release notes to release_notes.txt

# Remove stale iOS binaries
rm FieldSpottr.ipa || true
rm FieldSpottr.app.dSYM.zip || true

# Default values
specific_version=""
increment_type=""

# Parse arguments
while [[ "$#" -gt 0 ]]; do
    case $1 in
        --major|--minor|--patch)
            increment_type="${1/--/}"  # Remove -- prefix
            ;;
        *)
            specific_version="$1"  # Assume it's a specific version
            ;;
    esac
    shift
done

# Update libraries
echo "Updating library definitions"
./gradlew exportLibraryDefinitions -PaboutLibraries.exportPath=src/commonMain/composeResources/files --quiet

# Increment version code
echo "Incrementing version code"
NEW_VERSION_CODE=$(increment_version_code gradle.properties)

export RELEASING=true
export FS_BUILD_NUMBER=$NEW_VERSION_CODE

# Fetch the latest version from gradle.properties if no specific version provided
if [[ -z "$specific_version" ]]; then
    latest_version=$(getProperty 'fs_versionname' gradle.properties)
    VERSION_NAME=$(increment_version "$latest_version" "$increment_type")
else
    VERSION_NAME="$specific_version"
fi

update_property gradle.properties "fs_versionname" "${VERSION_NAME}"

echo "New version code: ${NEW_VERSION_CODE}"
echo "New version name: ${VERSION_NAME}"

cd FieldSpottr
xcrun agvtool new-version -all "${NEW_VERSION_CODE}"
xcrun agvtool new-marketing-version "${VERSION_NAME}"
cd ..

# Build Android release
echo "Building Android"
./gradlew :bundleRelease --quiet

# Build iOS release
echo "Building iOS"
bundle install
bundle exec fastlane ios build_prod

# Commit and tag. Don't do it until we know builds were successful
echo "Tagging"
git commit -am "Prepare for release ${NEW_VERSION_CODE}."
git tag -a "v${NEW_VERSION_CODE}" -m "Version ${NEW_VERSION_CODE}"

# Publish binaries
bundle exec fastlane ios publish_prod
bundle exec fastlane android publish_prod
# TODO publish to GitHub?

# TODO Upload mapping/dSYM files
# TODO need API key in a variable
#bugsnag-cli upload dsym FieldSpottr/FieldSpottr.xcodeproj
#bugsnag-cli upload android-aab build/outputs/bundle/release/field-spottr-root-release.aab
#bugsnag-cli upload android-proguard
#bugsnag-cli upload android-ndk --variant=release

# Finally push
echo "Pushing"
git push && git push --tags

echo "Done"
