# Live Permit API Integration

NYC Parks exposes an undocumented per-field availability endpoint that includes pending permit data
that is not present in the CSV downloads:

```text
https://www.nycgovparks.org/api/athletic-fields?location=<apiLocationId>&date=<yyyy-mm-dd>
```

Example:

```text
https://www.nycgovparks.org/api/athletic-fields?location=M165-FOOTBALL-1&date=2026-06-02
```

Important: requests need a browser-like user agent or Parks may block/reject them. The app and
`scripts/update_live_field_ids.py` both need to impersonate a browser.

## Architecture

- `LivePermitRepository` is separate from `PermitRepository`.
- Live data is not written to SQLDelight and remains an ad hoc/on-demand overlay.
- Live checks happen only for the currently selected `FieldGroup` and date. Find a Field is too
  broad because checking every candidate would require many individual field-detail requests.
- Results are retained in screen state, not persisted in the repository.
- The live check button is a FAB. It is hidden when the selected group has no live API IDs.
- Once fetched, the FAB becomes a checked/disabled "Live activity" state so we do not send repeat
  requests for the same retained screen state.
- The repository caches responses in memory by field API ID and requested date.

## UI Semantics

Live API results are overlaid on top of the normal CSV permit grid:

- Issued live permits should look like normal green permits.
- Pending final approval (`is_issued == false`) should use the pending theme color.
- Overlapping live permits should use the existing gray overlap style.
- Construction, special event, out-of-season, and similar hard blocks should stay error-colored.
- Advisory-only pending permit counts (`num_pending_permits > 0` without holder/type/permit data)
  are not hard blocks.
- Live blocks animate in with a short fade/slide instead of popping into existence.

## Parsing

The response is keyed by epoch seconds. Slots are 30-minute increments. One API response can include
multiple days starting from the requested date, so parsing filters down to the requested local NYC
date.

Current hard-block interpretation:

- `permit_is_for_overlapping_field == true`: overlapping permit block.
- `in_season == false`: out of season.
- `permit_type == "Construction"`: construction block.
- `permit_type == "Special Event"`: special event block.
- `is_issued == false`: pending final approval.
- Any holder/type/permit number otherwise: issued live permit.

Treat `num_pending_permits > 0` with no holder/type/overlap as advisory, not a hard block.

## Overlap Handling

There are two overlap sources:

- Existing CSV-derived overlap logic in `PermitState`.
- Live API overlap data plus inferred sibling-field overlays in
  `LiveGroupAvailability.withOverlapsFrom`.

Live availability is normalized again at the grid boundary via `liveAvailabilityForGrid(...)`. This
keeps retained or pre-normalized live data safe and makes the operation idempotent.

The grid must not simply drop a live block if any part overlaps an existing CSV reservation. Parks
can return adjacent live overlap blocks that merge into a larger span, where only part of that span
is already covered by CSV data. `permitGridColumnItems(...)` clips live items around CSV reserved
slots and renders the remaining visible pieces.

## Known Parks Data Quirks

### Baruch

Baruch's live API overlap graph is broader than the manual field labels might suggest.

Observed on `2026-06-06`:

- `Softball 1` at `7-8 PM` is returned by Parks as `permit_is_for_overlapping_field=true`, permit
  `901604`, GoodRec. The primary/non-overlap version of that permit is on `Soccer 2` from `6-8 PM`.
- `Soccer 2` at `8 AM-2 PM` is returned as `permit_is_for_overlapping_field=true`, permit `905910`,
  Downtown Little League. The primary/non-overlap version is on `Softball 1` from `8 AM-2 PM`.

So gray "overlap" blocks that do not visually match our old `field1`/`field2` split are coming from
Parks, not from our inferred propagation.

### Grand Street Field 2

For `Grand Street (Field 2)`, the whole field and both softball halves are currently modeled as one
shared overlap surface:

```text
field2
```

This is intentionally broader than separate `field2 south` / `field2 north` tokens because live
activity needs to participate across the whole physical field. Keep `Area.kt` and `areas.json` in
sync when changing this.

## Updating Field IDs

Live API IDs live in both:

- `shared/src/commonMain/kotlin/dev/zacsweers/fieldspottr/data/Area.kt`
- `areas.json`

Use the Python helper to find or verify `apiLocationId` values from NYC Parks' vector tiles:

```shell
scripts/update_live_field_ids.py --area Baruch --include-existing --bbox=-73.99,40.71,-73.97,40.73
```

For new fields, run without `--include-existing` to print suggestions for unmapped fields. The
script can write high-confidence matches to `areas.json` with `--apply-json`, but the same IDs still
need to be added to `Area.kt`.

The script uses NYC Parks vector tiles and must use a browser-like user agent. Explicit review is
still useful for ambiguous field labels.

## Regression Coverage

Current regression coverage lives mostly in:

- `shared/src/jvmTest/kotlin/dev/zacsweers/fieldspottr/data/LivePermitRepositoryTest.kt`
- `shared/src/jvmTest/kotlin/dev/zacsweers/fieldspottr/PermitGridTest.kt`

Important cases:

- Pending final approval becomes a pending block.
- Advisory-only pending counts do not become hard blocks.
- Live blocks propagate to overlapping fields.
- Live normalization is idempotent.
- Live blocks are clipped around CSV reservations rather than dropped wholesale.
- Grand Street Field 2 fields all share the `field2` overlap token.
