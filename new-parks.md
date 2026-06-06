# External Park Availability Plan

## Summary

Add a provider-neutral "third mechanism" for non-NYC-Parks field availability, starting with
Brooklyn Bridge Park Pier 5 Turf. This must stay separate from NYC Parks CSV/live APIs, and must
not use `scripts/update_live_field_ids.py`.

The v1 source of truth for Pier 5 is BBP's official schedule image:

https://brooklynbridgepark.org/wp-content/uploads/2023/07/PIer-5-Turf-Summer-2026-e1779906422445.png

BBP also links Metro Soccer/LeagueApps schedules, but those are supplemental only. The public
LeagueApps JSON had sparse current rows and TBD end times during research on 2026-06-04, so it
should not drive v1 availability.

## Key Changes

- Add BBP Pier 5 to the catalog as an external-source area/group:
  - Area: `Brooklyn Bridge Park`
  - Group: `Pier 5 Turf`
  - Fields: `pier5-field-1`, `pier5-field-2`, `pier5-field-3`
  - Display names: `Field 1`, `Field 2`, `Field 3`
  - Google Maps: `https://www.google.com/maps/search/?api=1&query=Brooklyn%20Bridge%20Park%20Pier%205`
  - Apple Maps: `https://maps.apple.com/?q=Brooklyn%20Bridge%20Park%20Pier%205`
- Make area CSV support optional:
  - Change `Area.csvUrl` to nullable.
  - Keep existing NYC Parks areas unchanged.
  - `PermitRepository.populateDb` should skip CSV population for areas without `csvUrl`.
  - Keep the browser-like user agent for NYC Parks only.
- Add hosted external availability JSON at repo root:
  - `external-availability.json`
  - Downloaded by the app from raw GitHub and cached like `areas.json`.
  - Parsed by a new external availability repository and merged with DB permits before
    `PermitState.fromPermits`.
- Add a source definition file, for example `external-sources/bbp-pier5-summer-2026.json`, with
  the curated schedule and expected SHA-256 for the BBP image. Do not OCR the image in v1.
- Add `scripts/update_external_availability.py` to:
  - Fetch the official BBP image.
  - Verify its SHA-256 against the checked-in source definition.
  - Generate `external-availability.json`.
  - Fail if the image hash changes, so a human refreshes the curated schedule intentionally.
- Add a GitHub Actions workflow on a daily cadence and manual dispatch to run the script and commit
  changed JSON. If the source image hash changes, upload the image as an artifact and fail the
  workflow.

## JSON Shape

Use this minimum structure:

```json
{
  "version": 1,
  "generatedAt": "2026-06-04T00:00:00Z",
  "sources": [
    {
      "id": "bbp-pier5-turf-summer-2026",
      "provider": "Brooklyn Bridge Park",
      "sourceUrl": "https://brooklynbridgepark.org/wp-content/uploads/2023/07/PIer-5-Turf-Summer-2026-e1779906422445.png",
      "kind": "official_recurring_schedule",
      "coverage": "seasonal_recurring_blocks",
      "validFrom": "2026-06-01",
      "validTo": "2026-08-31"
    }
  ],
  "blocks": []
}
```

Each block should include `areaName`, `groupName`, `fieldIds`, `daysOfWeek`, `start`, `end`,
`title`, `org`, `status`, `timezone`, and `sourceId`.

## Pier 5 Data

Encode these recurring blocks for `2026-06-01` through `2026-08-31`, timezone
`America/New_York`, title `Busy (Active permits)`, org `Brooklyn Bridge Park`, status
`Active permits`.

```text
Sun: Field 1, Field 2, Field 3  08:00-23:00
Mon: Field 1 09:00-10:00, 17:00-23:00; Field 2 15:00-23:00; Field 3 19:00-23:00
Tue: Field 1, Field 2, Field 3  16:00-23:00
Wed: Field 1 16:00-23:00; Field 2 17:00-23:00; Field 3 17:00-23:00
Thu: Field 1 09:00-10:00, 18:00-23:00; Field 2 16:00-23:00; Field 3 18:00-23:00
Fri: Field 1 19:00-23:00; Field 2 17:00-23:00; Field 3 19:00-23:00
Sat: Field 1, Field 2, Field 3  08:00-23:00
```

## App Behavior

- On refresh/startup, download both `areas.json` and `external-availability.json`.
- For the selected `group` and `date`, convert matching external blocks into synthetic `DbPermit`
  values:
  - `area = areaName`
  - `groupName = groupName`
  - `fieldId = fieldId`
  - `type = "External schedule"`
  - `name = title`
  - `org = org`
  - `status = status`
  - `start/end` from date plus block local time in `America/New_York`
  - `recordId = hash(sourceId, date, groupName, fieldId, start, end)`
- Merge synthetic external permits with SQLDelight permits before calling `PermitState.fromPermits`.
- Existing `PermitGrid`, detail screen, overlap handling, default group, and date picker should work
  without special BBP UI.
- Keep LeagueApps out of v1 app behavior, but document it as a future supplemental source:
  - Programs:
    `https://api.leagueapps.io/api/member-portal/metrosoccerny/siteLevelCalendar/programs`
  - Locations:
    `https://api.leagueapps.io/api/member-portal/metrosoccerny/siteLevelCalendar/locations`
  - Activities:
    `https://api.leagueapps.io/api/member-portal/metrosoccerny/siteLevelCalendar/scheduleBFF/activities?startDateFrom=YYYY-MM-DD&startDateUpTo=YYYY-MM-DD&programIds=...`

## Test Plan

- Unit test external JSON parsing and date expansion:
  - Sunday all fields become 08:00-23:00 synthetic permits.
  - Monday Field 1 creates two blocks.
  - Date outside `2026-06-01..2026-08-31` creates no permits.
  - Unknown area/group/field references are ignored or logged without crashing.
- Unit test repository merge:
  - External-only Pier 5 group produces a non-empty `PermitState`.
  - Existing NYC Parks CSV groups still populate from CSV.
  - Areas with `csvUrl = null` are skipped by CSV population.
- Script tests:
  - Given a fixture source definition, generated JSON is stable.
  - Hash mismatch fails with a clear message.
  - Generated blocks match the Pier 5 schedule above.
- Verification:
  - Run `./gradlew build`.
  - Run existing JVM tests plus new tests.
  - Run the update script locally against the live BBP image before committing generated JSON.

## Assumptions

- v1 supports Brooklyn Bridge Park Pier 5 Turf only for this external source work.
- The official BBP schedule image is authoritative for recurring field unavailability.
- LeagueApps is useful future detail, but not reliable enough for v1 availability because current
  rows were sparse and event end times were marked TBD.
- West Side Highway / Hudson River Park will use the same external feed mechanism later, but no
  HRP-specific source is included in this implementation plan.
