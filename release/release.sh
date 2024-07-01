#!/usr/bin/env bash

set -exo pipefail

# Gets a property out of a .properties file
# usage: getProperty $key $filename
function getProperty() {
  grep "${1}" "$2" | cut -d'=' -f2
}

# Increments the fs_versioncode prop in gradle.properties to a new value
# usage: increment_version $gradlePropertiesFile
increment_version() {
  local properties_file=$1
  if grep -q "fs_versioncode=" "$properties_file"; then
    local prev_version
    prev_version=$(getProperty 'fs_versioncode' "${properties_file}")
    local new_version
    new_version=$((prev_version + 1))
    sed -i '' "s/${prev_version}/${new_version}/g" "${properties_file}"
    echo $new_version
  fi
}

# Update libraries
echo "Updating library definitions"
./gradlew exportLibraryDefinitions -PaboutLibraries.exportPath=src/commonMain/composeResources/files --quiet

# Increment version
echo "Incrementing version"
NEW_VERSION=$(increment_version gradle.properties)

export RELEASING=true
export FS_BUILD_NUMBER=$NEW_VERSION

# Build Android release
echo "Building Android"
./gradlew :bundleRelease --quiet

# Build iOS release
echo "Building iOS"
bundle exec fastlane ios build_prod

# Commit and tag. Don't do it until we know builds were successful
echo "Tagging"
git commit -am "Prepare for release ${NEW_VERSION}."
git tag -a "v${NEW_VERSION}" -m "Version ${NEW_VERSION}"

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
