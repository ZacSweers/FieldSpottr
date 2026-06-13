# Updating Generated Data

FieldSpottr reads repo-hosted generated data from `areas.json` and `availability/`. The generator is
a Gradle JVM app:

```bash
./gradlew :generator:run --args=--output=.
```

## Normal Availability Refresh

For normal local updates, run:

```bash
scripts/update-availability.sh
```

This dumps the Hudson River Park page and NYC Parks live API responses through local headless
Chrome, then runs the generator with those dumped sources. The scheduled GitHub Actions refresh is
disabled for now because NYC Parks/Cloudflare frequently blocks GitHub-hosted runner traffic, which
can otherwise produce misleading generated diffs.

Useful environment overrides:

```bash
LIVE_DAYS=14 scripts/update-availability.sh
CHROME=/path/to/chrome scripts/update-availability.sh
OUTPUT_ROOT=/tmp/fieldspottr-output scripts/update-availability.sh
```

`LIVE_DAYS` controls how many NYC Parks live dates are dumped, `CHROME` selects a specific
Chrome/Chromium binary, and `OUTPUT_ROOT` writes generated files somewhere other than the repo root.

The generated manifest lists one hash per area feed. App refreshes download only stale/missing area
feeds, and each feed replaces that area's DB rows transactionally after it parses successfully.
Failed manifest or feed downloads keep the existing cached DB data in place.

`Area.csvUrl` is optional catalog metadata for generator/debug use. The app should not fetch NYC
Parks CSVs or live provider APIs directly; generated feeds are the runtime availability contract.

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

The script first tries a local Chrome dump of the official HRP page. If that is blocked by
Cloudflare, it falls back to a reader-format copy of the same page and feeds that source to the
generator.

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

5. Confirm `availability/areas/west-side-highway.json` has rows and `availability/manifest.json`
   has an updated West Side Highway hash.
6. Run generator tests:

   ```bash
   ./gradlew :generator:test
   ```

If the source file is blocked or cannot be parsed, the generator preserves the previous West Side
Highway feed rows instead of replacing them with empty data.
