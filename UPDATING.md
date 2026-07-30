# Updating Generated Data

FieldSpottr reads repo-hosted generated data from `areas.json` and `availability/`. The generator is a Gradle JVM app:

```bash
./gradlew :generator:run --args=--output=.
```

## Normal Availability Refresh

Local Chrome is the default fetch backend:

```bash
scripts/update-availability.sh
```

This dumps the Hudson River Park page and NYC Parks live API responses through local headless Chrome, then runs the generator with those dumped sources.

Kernel is an optional backend for local testing and the required backend in CI. Install Kernel CLI 0.26.0 or newer, export `KERNEL_API_KEY` from a secure shell environment, and select the backend:

```bash
brew install kernel/tap/kernel
FETCH_BACKEND=kernel scripts/update-availability.sh
```

Kernel mode creates one headful stealth browser session for the refresh. HRP and NYC requests run sequentially through Browser Curl without custom browser headers. If Kernel encounters a Cloudflare challenge or an HTTP failure that may hide one, the script navigates to the URL with Playwright, waits for challenge handling, and retries Browser Curl with the same session and cookies. The script deletes the session when it exits, and the five-minute server timeout is a fallback if local cleanup cannot run.

Useful environment overrides:

```bash
FETCH_BACKEND=chrome scripts/update-availability.sh
FETCH_BACKEND=kernel scripts/update-availability.sh
REQUIRE_FRESH_LIVE_SOURCES=true FETCH_BACKEND=kernel scripts/update-availability.sh
LIVE_DAYS=14 scripts/update-availability.sh
CHROME=/path/to/chrome scripts/update-availability.sh
OUTPUT_ROOT=/tmp/fieldspottr-output scripts/update-availability.sh
HRP_SOURCE_FILE=/path/to/hrp.html scripts/update-availability.sh
NYC_CSV_SOURCE_DIR=/path/to/csv-dir scripts/update-availability.sh
NYC_CLOSURES_SOURCE_FILE=/path/to/closures.json scripts/update-availability.sh
```

`FETCH_BACKEND` accepts `chrome` or `kernel` and defaults to `chrome`. `KERNEL_API_KEY` is required only for Kernel mode, and `CHROME` applies only to the Chrome backend. `LIVE_DAYS` controls how many NYC Parks live dates are dumped, and `OUTPUT_ROOT` writes generated files somewhere other than the repo root.

`REQUIRE_FRESH_LIVE_SOURCES=true` enables the strict mode used by CI. Strict mode requires HRP to parse after its fallback and requires every expected NYC live response to exist and parse. A strict failure exits nonzero before CI can create a pull request. Without strict mode, failed live sources retain their existing preservation behavior.

`HRP_SOURCE_FILE`, `NYC_CSV_SOURCE_DIR`, and `NYC_CLOSURES_SOURCE_FILE` let you rerun with manually saved upstream dumps. NYC CSV and closure sources remain best-effort even in strict mode. There is no known current automatic NYC Parks closures feed; without `NYC_CLOSURES_SOURCE_FILE`, existing closure rows are preserved.

When changing the updater or generator, run its focused tests before the full build:

```bash
scripts/update-availability-test.sh
./gradlew :generator:test
./gradlew build
```

The generated manifest lists one hash per area feed. App refreshes download only stale or missing area feeds, and each feed replaces that area's DB rows transactionally after it parses successfully. Failed manifest or feed downloads keep the existing cached DB data in place.

`Area.csvUrl` is optional catalog metadata for generator and debugging use. The app should not fetch NYC Parks CSVs or live provider APIs directly; generated feeds are the runtime availability contract.

## GitHub Actions

Store the Kernel API key in the repository Actions secret named `KERNEL_API_KEY`. The daily availability workflow runs at `17 8 * * *`, can be dispatched manually, and also runs after a change under `data/bbp/` reaches `main`. It uses Kernel with strict live-source validation, uploads diagnostics on failure, and opens an auto-merge pull request only when generated availability changed.

The Brooklyn Bridge Park schedule workflow runs every Monday at `43 10 * * 1` and can also be dispatched manually. Manual runs execute only when dispatched against the repository's default branch. Store its OpenAI API key in the repository Actions secret named `OPENAI_API_KEY`. The inspection job has read-only repository access. `KERNEL_API_KEY` is exposed only while fetching the official page and schedule image, and `OPENAI_API_KEY` is exposed only while transcribing changed image bytes or running the explicit `verify_current` check. The existing `FIELDSPOTTR_BOT_TOKEN` is exposed only to the separate publication step.

Never put an API key in command arguments, files, logs, generated artifacts, or commits. Both workflows fail before creating a pull request when a required secret, fetch, transcription, or validation fails.

The BBP workflow opens a review-required pull request containing only `data/bbp/` source changes. It does not enable auto-merge. Merging that source pull request triggers the strict availability workflow. That workflow opens a separate generated-data pull request only when runtime availability changes; a URL-only metadata update or a new image with the same canonical schedule can produce no generated diff. A generated-data pull request changes `areas.json` or `availability/`, not `data/bbp/`, so it does not trigger another refresh.

## NYC Parks

NYC Parks areas can be added from their issued-permits page. The helper script fetches the page and
CSV with a browser-like user agent, discovers CSV field names, tries to match live `apiLocationId`
values from NYC Parks map tiles, inserts a Kotlin catalog block into `Area.kt`, and bumps
`Areas.VERSION`.

Start with a dry run:

```bash
scripts/add_nyc_park.py https://www.nycgovparks.org/permits/field-and-court/issued/<PARK_ID> --dry-run
```

Then add it:

```bash
scripts/add_nyc_park.py https://www.nycgovparks.org/permits/field-and-court/issued/<PARK_ID>
```

Review the generated `Area.kt` block before committing. The script can infer simple whole/half-field
overlaps, but group names, display names, map links, and unusual shared-field relationships may need
manual cleanup. Useful overrides:

```bash
scripts/add_nyc_park.py <url> --name "Short Area Name" --group "Field Group Name"
scripts/add_nyc_park.py <url> --display-name "Display Name"
scripts/add_nyc_park.py <url> --no-live-ids
```

After adding or adjusting a park, regenerate repo data and run generator tests:

```bash
scripts/update-availability.sh
./gradlew :generator:test
```

## Brooklyn Bridge Park Pier 5

Brooklyn Bridge Park is the area in the app. Pier 5 is the field/group inside that area, and `Field 1`, `Field 2`, and `Field 3` are the subfields shown as columns in the grid.

Pier 5 availability is generated from a checked-in transcription of the official schedule image on the Pier 5 page:

https://brooklynbridgepark.org/places-to-see/pier-5/

`data/bbp/pier5-current.json` is the current source pointer. It records the official page and image URLs, image hash and path, schedule year and valid date range, transcription provenance, and the field, day, and time blocks that the generator expands into availability rows. Images use immutable names in the form `data/bbp/pier5-<full-sha256>.<ext>`, and every prior hash-named image remains checked in for audit.

For a local check, load `KERNEL_API_KEY` and `OPENAI_API_KEY` from a secure shell environment and run:

```bash
scripts/update-bbp-schedule.sh
```

The no-argument command runs `fetch`, conditionally runs `transcribe` when the image bytes changed, and then runs `prepare`. Those phase names can also be passed separately when investigating a failure.

The transcription defaults to `gpt-5.6-sol`. Local callers can set `OPENAI_MODEL` to test another model, while CI pins the default explicitly.

The updater creates one Kernel stealth browser session, fetches the official page and exact schedule image, and compares the image bytes with the checked-in snapshot. Unchanged bytes require no transcription. A URL-only change reuses the existing transcription. Changed bytes are transcribed twice through the Responses API, and both canonical results must agree before deterministic validation accepts the candidate. Candidate source files and a network-free generated preview are written under `build/bbp-refresh/`. After validation succeeds, the updater installs only the changed source files under `data/bbp/` for review; it does not commit or open a pull request.

To exercise the transcription and comparison against the checked-in image without fetching or preparing an update, run:

```bash
scripts/update-bbp-schedule.sh verify-current
```

The manual workflow exposes the same check through its `verify_current` input. That mode is exclusive: it does not fetch a new image, prepare source changes, or run the publication job.

The updater fails closed when Kernel cannot fetch the page or image, the asset is unsupported, the API refuses or cannot complete the transcription, the two transcriptions disagree, or deterministic source and generator validation fails. Failed workflow runs open no pull request and retain sanitized diagnostics for 14 days.

If automation cannot produce a valid candidate, update the source manually:

1. Open the official Pier 5 page and download the current turf schedule image.
2. Compute the image's full lowercase SHA-256 with `shasum -a 256 <image>` or `sha256sum <image>`, then copy it to `data/bbp/pier5-<full-sha256>.<ext>` using its `.png`, `.jpg`, or `.webp` extension without replacing or removing any prior hash-named image.
3. Update `data/bbp/pier5-current.json` with its schema version and ID; exact `sourcePageUrl`, `imageUrl`, `imagePath`, and `imageSha256`; schedule year and valid dates; the manual `provenance` fields `method`, `model`, `promptVersion`, `responseIds`, and `extractedAt`; and the days, Pier 5 field IDs, start times, and end times exactly as shown.
4. Validate the source and image:

   ```bash
   ./gradlew :generator:run --args="--validate-bbp-source=data/bbp/pier5-current.json --bbp-image-root=."
   ```

5. Regenerate repo data:

   ```bash
   ./gradlew :generator:run --args=--output=.
   ```

6. Confirm `availability/areas/brooklyn-bridge-park.json` changed as expected.
7. Run the focused updater and generator tests:

   ```bash
   scripts/update-bbp-schedule-test.sh
   ./gradlew :generator:test
   ```

## Hudson River Park / West Side Highway

Hudson River Park schedules are parsed from the official fields page:

https://hudsonriverpark.org/visit/events/permits/fields/

For normal updates, use the local update script described above:

```bash
scripts/update-availability.sh
```

The script first fetches the official HRP page through the selected Chrome or Kernel backend. Kernel retries Cloudflare challenges in the same stealth browser session. If the primary fetch is blocked, fails, or produces no schedule rows, the script falls back to a reader-format copy of the same page and feeds that source to the generator.

If both automated sources fail, update the checked-in source snapshot manually.

1. Open the HRP fields page in a normal browser.
2. Open DevTools Console and copy the full page HTML:

   ```js
   copy(document.documentElement.outerHTML)
   ```

3. Replace `data/hrp/source.html` with the copied HTML.
4. Regenerate repo data from that source:

   ```bash
   ./gradlew :generator:run --args="--output=. --hrp-source-file=data/hrp/source.html"
   ```

5. Confirm `availability/areas/west-side-highway.json` has rows and `availability/manifest.json` has an updated West Side Highway hash.
6. Run generator tests:

   ```bash
   ./gradlew :generator:test
   ```

If the source file is blocked or cannot be parsed, the generator preserves the previous West Side Highway feed rows instead of replacing them with empty data. Strict mode fails instead of accepting that preserved HRP result.
