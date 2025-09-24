# RELEASING

1. Run `./release/release.sh --major/--minor/--patch` to bump the version. To cut a specific version, run `./release/release.sh some.version.here`.
2. Go publish the release on the App and Play stores.
   - App Store
     - Create a new release
     - Type in the semver version
     - Add the build 
     - Add "What's new in this version" from release_notes.txt.
     - Click "Save"
     - Click "Add for Review"
     - Click "Submit for Review" in the draft panel
   - Play Store
     - Click "View Change" in the "publishing overview" banner that appears near the top
     - Go to the uploaded version
