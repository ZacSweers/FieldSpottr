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

Store the Kernel API key in the repository Actions secret named `KERNEL_API_KEY`. Keep the secret scoped to the updater step, and never put it in command arguments, files, logs, generated artifacts, or commits.

The update workflow is manual-only during the initial burn-in. Run it manually on three separate days and verify its generated diffs, automatic merges, diagnostic artifacts, and Kernel usage. After three consecutive successful runs, restore the existing daily schedule at `17 8 * * *`. A failed strict refresh uploads diagnostics and stops before creating a pull request.

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

Brooklyn Bridge Park is the area in the app. Pier 5 is the field/group inside that area, and
`Field 1`, `Field 2`, and `Field 3` are the subfields shown as columns in the grid.

Pier 5 availability is generated from a manually read transcription of the official schedule image
on the Pier 5 page:

https://brooklynbridgepark.org/places-to-see/pier-5/

The current checked-in source snapshot is `data/bbp/pier5-summer-2026.png`, and the generator reads
`data/bbp/pier5-summer-2026.json`.

1. Open the official Pier 5 page and find the current turf schedule image.
2. If the image changed, replace `data/bbp/pier5-summer-2026.png`.
3. Read the field/date/time table from the image.
4. Update `data/bbp/pier5-summer-2026.json`, including:
   - valid date range
   - days of week
   - Pier 5 subfield numbers
   - start and end times
5. Regenerate repo data:

   ```bash
   ./gradlew :generator:run --args=--output=.
   ```

6. Confirm `availability/areas/brooklyn-bridge-park.json` changed as expected.
7. Run generator tests:

   ```bash
   ./gradlew :generator:test
   ```

The generator checks the Pier 5 page for a current turf schedule image URL and prints a warning if
it differs from the checked-in transcription metadata.

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
