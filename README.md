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

See `UPDATING.md` for the local refresh command, provider-specific notes, and manual fallback
instructions.

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
