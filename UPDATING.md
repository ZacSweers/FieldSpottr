# Updating Generated Data

FieldSpottr reads repo-hosted generated data from `areas.json` and `availability/`. The generator is
a Gradle JVM app:

```bash
./gradlew :generator:run --args=--output=.
```

## Brooklyn Bridge Park

Brooklyn Bridge Park Pier 5 availability is generated from a manually read transcription of the
official schedule image on the Pier 5 page:

https://brooklynbridgepark.org/places-to-see/pier-5/

The current checked-in source snapshot is `data/bbp/pier5-summer-2026.png`, and the generator reads
`data/bbp/pier5-summer-2026.json`.

1. Open the official Pier 5 page and find the current turf schedule image.
2. If the image changed, replace `data/bbp/pier5-summer-2026.png`.
3. Read the field/date/time table from the image.
4. Update `data/bbp/pier5-summer-2026.json`, including:
   - valid date range
   - days of week
   - field numbers
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

Automated fetches may be blocked by the site, so update the checked-in source snapshot manually when
the weekly schedule changes.

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
