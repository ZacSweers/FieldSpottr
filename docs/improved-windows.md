# Find a Field Time Windows

## Goal

Make Find a Field time windows more configurable without turning the app into a calendar or
availability search product. The main screen should stay simple: pick a date, tap a time window,
see open fields.

The preferred direction is saved custom time windows. Keep the built-in windows, then let users add
one or two personal windows such as "After work" or "Practice slot".

## Current UI

Find a Field currently has a date selector and fixed time chips:

```text
Field Spottr                         refresh

[ Jun 2 ]  [ This morning ] [ This afternoon ] [ This evening ]

14 fields open
```

Those chips map to hard-coded hour ranges:

- Morning: 6am-12pm
- Afternoon: 12pm-6pm
- Evening: 6pm-11pm

The data layer already supports arbitrary start and end hours through the permit window query. The
main missing pieces are UI, retained state, and persistence for custom windows.

## Proposed UI

Keep the same basic chip row, but add saved custom windows next to the built-ins.

```text
Field Spottr                         refresh

[ Jun 2 ]  [ Morning ] [ Afternoon ] [ Evening ] [ After work ] [ + ]

18 fields open
```

The `+` button opens a management sheet.

```text
Time windows

Built in
  Morning                  6am-12pm
  Afternoon                12-6pm
  Evening                  6-11pm

Saved
  After work               5-8pm        [edit] [trash]
  Practice slot            3:30-5:30pm  [edit] [trash]

[ New window ]
```

Creating or editing a window should be direct:

```text
New time window

Name
[ After work              ]

Time
[ 5:00 PM v ] to [ 8:00 PM v ]

[ Save ]
```

Deletion should be explicit in the management sheet. Swipe-to-delete can be a nice extra later, but
it should not be the only way to remove a saved window.

## Behavior

- Built-in windows remain available by default.
- A custom window is selected the same way as a built-in chip.
- Tapping an already selected chip clears the selection and returns to the full-day window, matching
  the current behavior.
- New custom windows appear after the built-ins.
- The `+` chip opens the management sheet instead of selecting a window.
- Custom windows should be editable and deletable from the sheet.
- If the currently selected custom window is deleted, fall back to the default window.
- The default selected window can remain Afternoon for now.

## Constraints

- Avoid a timeline slider for the first version. It is more configurable, but too much UI for the
  app's current simplicity.
- Avoid intent-heavy presets like "Next 2h" or "After school" unless they are later shown to be
  common user needs.
- Avoid duration search, such as "find me any 90 minute opening between 4 and 9". That is interval
  math rather than AI, but it creates a more complex search product than this feature needs.
- Cap visible custom chips if needed. If users can create many windows, the main chip row can become
  cluttered.

## Data Model

Replace the selected `TimeWindow?` concept with a small window model that can represent built-ins and
custom windows:

```kotlin
data class FieldTimeWindow(
  val id: String,
  val name: String,
  val startMinutes: Int,
  val endMinutes: Int,
  val builtIn: Boolean,
)
```

Minutes since midnight keeps half-hour windows possible without committing to a richer time type in
UI state. Built-ins can use stable IDs such as `morning`, `afternoon`, and `evening`. Custom IDs can
be generated UUIDs or another stable persisted identifier.

The current availability calculation is hour-based. Supporting `3:30-5:30pm` correctly would require
moving the availability computation from whole-hour buckets to minute ranges. If that is too much for
the first pass, constrain custom windows to whole hours in the picker.

## Persistence

Persist only custom windows. Built-ins should remain code-defined.

Possible storage options:

- A metadata JSON value in the existing `dbMetadata` table.
- A small preferences abstraction if one already grows in the app.
- A dedicated SQLDelight table if saved windows later need ordering or migration-friendly edits.

For a first pass, `dbMetadata` is probably enough:

```text
key: custom_time_windows
value: [{"id":"...","name":"After work","startMinutes":1020,"endMinutes":1200}]
```

## Implementation Checklist

1. Introduce a common time-window model that covers built-ins and custom windows.
2. Add repository or preferences APIs to load, save, update, and delete custom windows.
3. Update `FindFieldScreen.State` to expose the full list of available windows and the selected
   window ID.
4. Update the presenter to combine built-ins with persisted custom windows.
5. Replace the current `TimeWindow.entries` chip row with state-provided windows plus a `+` action.
6. Add a management overlay/sheet for creating, editing, and deleting custom windows.
7. Keep custom times whole-hour unless the availability calculation is updated to minute precision.
8. Add tests for selection fallback after deletion, persisted windows loading, and availability for
   custom ranges.

## Open Questions

- Should custom windows support half-hour increments in the first version?
- Should there be a hard cap, such as three custom windows?
- Should custom windows be ordered manually, by creation time, or by start time?
- Should deleting a selected custom window fall back to Afternoon or full day?
