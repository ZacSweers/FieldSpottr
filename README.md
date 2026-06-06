⚽️ FieldSpottr
==============

A toy app for checking field permit status from nycgovparks.org.

- App Store: https://apps.apple.com/us/app/field-spottr/id6505042655
- Play Store: https://play.google.com/store/apps/details?id=dev.zacsweers.fieldspottr

Data refresh
------------

The app reads repo-hosted catalog and availability JSON from raw GitHub. Source-specific fetching
and scraping belongs in the generator, not in app runtime code.

- `:models` contains the shared serializable catalog and availability feed models.
- `:generator` is a JVM application that writes `areas.json`, `availability/manifest.json`, and
  `availability/areas/<area-id>.json`.
- `:shared` downloads those repo-hosted files and imports each parsed area feed into SQLDelight.

Run the generator with:

    ./gradlew :generator:run --args=--output=.

Use `--live-days=<days>` to adjust the NYC Parks live availability window. GitHub Actions runs the
same Gradle task daily and commits changed generated JSON.

Hudson River Park / West Side Highway schedules can be supplied from a browser-dumped page source:

    ./gradlew :generator:run --args="--output=. --hrp-source-file=build/hrp/fields.html"

If that source is blocked or cannot be parsed, the generator preserves the previous West Side
Highway feed instead of replacing it with empty data.

Brooklyn Bridge Park Pier 5 is generated from the checked-in schedule transcription in
`data/bbp/pier5-summer-2026.json`, read from the checked-in source image in `data/bbp/`. The
generator scans the official Pier 5 page and prints a warning if it appears to link a newer turf
schedule image.

The generated manifest lists one hash per area feed. App refreshes download only stale/missing area
feeds, and each feed replaces that area's DB rows transactionally after it parses successfully.
Failed manifest or feed downloads keep the existing cached DB data in place.

`Area.csvUrl` is optional catalog metadata for generator/debug use. The app should not fetch NYC
Parks CSVs or live provider APIs directly; generated feeds are the runtime availability contract.

License
--------

    Copyright 2024 Zac Sweers

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
