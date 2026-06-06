#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Add a NYC Parks field/court catalog entry to Area.kt.

Usage:
  scripts/add_nyc_park.py https://www.nycgovparks.org/permits/field-and-court/issued/B529

The script fetches the issued-permits page and CSV with a browser-like user agent, discovers CSV
field names, tries to match NYC live API ids from the Parks vector tiles, inserts a generated area
block into the Kotlin catalog, and bumps Areas.VERSION.
"""

from __future__ import annotations

import argparse
import csv
import html
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from io import StringIO
from pathlib import Path

import update_live_field_ids as live_ids

REPO_ROOT = Path(__file__).resolve().parents[1]
AREA_KT = REPO_ROOT / "models/src/commonMain/kotlin/dev/zacsweers/fieldspottr/data/Area.kt"
NYC_ISSUED_RE = re.compile(
  r"^https://www\.nycgovparks\.org/permits/field-and-court/issued/([^/?#]+)(?:/csv)?/?$"
)
INSERT_BEFORE = '    area(name = "Brooklyn Bridge Park"'


@dataclass(frozen=True)
class ParkInfo:
  issued_url: str
  csv_url: str
  prop_id: str
  display_name: str
  center: tuple[float, float] | None


@dataclass(frozen=True)
class FieldInfo:
  csv_name: str
  display_name: str
  api_location_id: str | None
  shared_fields: tuple[str, ...]


def fetch_text(url: str, accept: str) -> str:
  request = urllib.request.Request(
    url,
    headers={
      "User-Agent": live_ids.USER_AGENT,
      "Accept": accept,
      "Accept-Language": "en-US,en;q=0.9",
    },
  )
  try:
    with urllib.request.urlopen(request, timeout=30) as response:
      return response.read().decode("utf-8", errors="replace")
  except urllib.error.HTTPError as e:
    raise RuntimeError(f"Failed to fetch {url}: HTTP {e.code}") from e
  except urllib.error.URLError as e:
    raise RuntimeError(f"Failed to fetch {url}: {e}") from e


def normalize_issued_url(url: str) -> tuple[str, str]:
  match = NYC_ISSUED_RE.match(url.rstrip("/"))
  if not match:
    raise ValueError(
      "Expected a NYC Parks issued-permits URL like "
      "https://www.nycgovparks.org/permits/field-and-court/issued/B529"
    )
  prop_id = match.group(1)
  issued_url = f"https://www.nycgovparks.org/permits/field-and-court/issued/{prop_id}"
  return prop_id, issued_url


def strip_tags(value: str) -> str:
  return re.sub(r"<[^>]+>", " ", value).strip()


def parse_park_info(url: str) -> ParkInfo:
  prop_id, issued_url = normalize_issued_url(url)
  page = fetch_text(issued_url, "text/html,application/xhtml+xml")

  display_name = None
  h1 = re.search(r'<h1[^>]*class="avail-h-one"[^>]*>(.*?)<br\b', page, flags=re.S)
  if h1:
    display_name = html.unescape(strip_tags(h1.group(1))).strip()
  if not display_name:
    title = re.search(r"<title>Field and Court Usage Report for (.*?) : NYC Parks</title>", page)
    if title:
      display_name = html.unescape(title.group(1)).strip()
  if not display_name:
    display_name = prop_id

  center = None
  center_match = re.search(
    r"center:\s*\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]",
    page,
  )
  if center_match:
    lon = float(center_match.group(1))
    lat = float(center_match.group(2))
    center = (lat, lon)

  return ParkInfo(
    issued_url=issued_url,
    csv_url=f"{issued_url}/csv",
    prop_id=prop_id,
    display_name=display_name,
    center=center,
  )


def parse_csv_fields(csv_body: str) -> list[str]:
  reader = csv.DictReader(StringIO(csv_body))
  fields = {row.get("Field", "").strip() for row in reader}
  return natural_sort(field for field in fields if field)


def natural_sort(values) -> list[str]:
  def key(value: str):
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", value)]

  return sorted(values, key=key)


def display_name_for(csv_name: str, all_names: list[str]) -> str:
  sport = csv_name.split("-", 1)[0]
  same_sport = [name for name in all_names if name.startswith(f"{sport}-")]
  if len(same_sport) == 1:
    return sport.title()
  return csv_name.replace("-", " ")


def shared_fields_for(csv_name: str, all_names: list[str]) -> tuple[str, ...]:
  half_match = re.match(r"^(.+-\d+)([A-Za-z])$", csv_name)
  if half_match and half_match.group(1) in all_names:
    return (shared_key(half_match.group(1), half_match.group(2)),)

  half_names = [
    match.group(1)
    for name in all_names
    if (match := re.match(rf"^{re.escape(csv_name)}([A-Za-z])$", name))
  ]
  if half_names:
    return tuple(shared_key(csv_name, suffix) for suffix in sorted(half_names))

  return ()


def shared_key(base: str, suffix: str) -> str:
  return re.sub(r"[^a-z0-9]+", "-", f"{base}-{suffix}".lower()).strip("-")


def candidate_bbox(center: tuple[float, float] | None) -> tuple[float, float, float, float]:
  if center is None:
    return live_ids.DEFAULT_BBOX
  lat, lon = center
  return (lon - 0.04, lat - 0.04, lon + 0.04, lat + 0.04)


def live_candidates(
  prop_id: str,
  center: tuple[float, float] | None,
  verbose: bool,
) -> list[live_ids.Candidate]:
  candidates = live_ids.collect_candidates(candidate_bbox(center), live_ids.DEFAULT_ZOOM, verbose)
  return natural_sort_candidates(candidate for candidate in candidates if candidate.propnum == prop_id)


def natural_sort_candidates(candidates) -> list[live_ids.Candidate]:
  return sorted(candidates, key=lambda candidate: natural_sort([candidate.system])[0])


def api_location_id_for(
  csv_name: str,
  candidates: list[live_ids.Candidate],
) -> str | None:
  field = {"name": csv_name, "displayName": display_name_for(csv_name, [csv_name])}
  scored = live_ids.best_candidates(field, candidates)
  if not scored:
    return None
  best_score = scored[0][0]
  best = [candidate for score, candidate in scored if score == best_score]
  if len(best) == 1 and best_score >= 80:
    return best[0].system
  return None


def build_fields(csv_fields: list[str], candidates: list[live_ids.Candidate]) -> list[FieldInfo]:
  candidate_names = [
    candidate.name for candidate in candidates if candidate.name and looks_like_field_name(candidate.name)
  ]
  all_names = natural_sort({*csv_fields, *candidate_names})
  return [
    FieldInfo(
      csv_name=name,
      display_name=display_name_for(name, all_names),
      api_location_id=api_location_id_for(name, candidates),
      shared_fields=shared_fields_for(name, all_names),
    )
    for name in all_names
  ]


def looks_like_field_name(value: str) -> bool:
  return re.match(r"^[A-Za-z][A-Za-z ]*-\d", value) != None


def maps_urls(display_name: str, center: tuple[float, float] | None) -> tuple[str, str]:
  encoded = urllib.parse.quote(display_name)
  if center is not None:
    lat, lon = center
    return (
      f"https://www.google.com/maps/search/?api=1&query={lat:.6f},{lon:.6f}",
      f"https://maps.apple.com/?ll={lat:.6f},{lon:.6f}&q={encoded}",
    )
  return (
    f"https://www.google.com/maps/search/?api=1&query={encoded}",
    f"https://maps.apple.com/?q={encoded}",
  )


def kt_string(value: str) -> str:
  return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def kt_set(values: tuple[str, ...]) -> str:
  return "setOf(" + ", ".join(kt_string(value) for value in values) + ")"


def generated_area_block(area_name: str, group_name: str, park: ParkInfo, fields: list[FieldInfo]) -> str:
  gmaps, amaps = maps_urls(park.display_name, park.center)
  lines = [
    "    area(",
    f"      name = {kt_string(area_name)},",
    f"      displayName = {kt_string(park.display_name)},",
    f"      csvUrl = {kt_string(park.csv_url)},",
    "    ) {",
    "      group(",
    f"        name = {kt_string(group_name)},",
    "        location =",
    "          Location(",
    f"            {kt_string(gmaps)},",
    f"            {kt_string(amaps)},",
    "          ),",
    "      ) {",
  ]
  for field in fields:
    args = [
      f"csvName = {kt_string(field.csv_name)}",
      f"displayName = {kt_string(field.display_name)}",
    ]
    if field.shared_fields:
      args.append(f"sharedFields = {kt_set(field.shared_fields)}")
    if field.api_location_id:
      args.append(f"apiLocationId = {kt_string(field.api_location_id)}")

    if len(args) <= 2:
      lines.append(f"        field({', '.join(args)})")
    else:
      lines.append("        field(")
      for arg in args:
        lines.append(f"          {arg},")
      lines.append("        )")
  lines += [
    "      }",
    "    }",
  ]
  return "\n".join(lines)


def bump_areas_version(source: str) -> str:
  match = re.search(r"const val VERSION = (\d+)", source)
  if not match:
    raise RuntimeError("Could not find Areas.VERSION")
  version = int(match.group(1))
  return source[: match.start(1)] + str(version + 1) + source[match.end(1) :]


def insert_area_block(area_kt: Path, block: str, csv_url: str, bump_version: bool) -> None:
  source = area_kt.read_text()
  if csv_url in source:
    raise RuntimeError(f"{csv_url} already exists in {area_kt}")
  marker = source.find(INSERT_BEFORE)
  if marker == -1:
    raise RuntimeError(f"Could not find insertion marker in {area_kt}: {INSERT_BEFORE}")
  next_source = source[:marker] + block + "\n" + source[marker:]
  if bump_version:
    next_source = bump_areas_version(next_source)
  area_kt.write_text(next_source)


def main() -> None:
  parser = argparse.ArgumentParser()
  parser.add_argument("url", help="NYC Parks issued-permits URL")
  parser.add_argument("--area-kt", type=Path, default=AREA_KT, help="Area.kt path")
  parser.add_argument("--name", help="Area.areaName value. Defaults to the park display name.")
  parser.add_argument("--group", help="Field group name. Defaults to the park display name.")
  parser.add_argument("--display-name", help="Override the parsed park display name.")
  parser.add_argument("--dry-run", action="store_true", help="Print the generated block only")
  parser.add_argument("--no-live-ids", action="store_true", help="Skip vector-tile live id lookup")
  parser.add_argument("--no-version-bump", action="store_true", help="Do not bump Areas.VERSION")
  parser.add_argument("--verbose", action="store_true", help="Print vector tile fetch progress")
  args = parser.parse_args()

  park = parse_park_info(args.url)
  if args.display_name:
    park = ParkInfo(
      issued_url=park.issued_url,
      csv_url=park.csv_url,
      prop_id=park.prop_id,
      display_name=args.display_name,
      center=park.center,
    )
  csv_fields = parse_csv_fields(fetch_text(park.csv_url, "text/csv"))
  if not csv_fields:
    raise RuntimeError(f"No fields found in {park.csv_url}")

  candidates: list[live_ids.Candidate] = []
  if not args.no_live_ids:
    try:
      candidates = live_candidates(park.prop_id, park.center, args.verbose)
    except Exception as e:
      print(f"Warning: failed to fetch live field ids: {e}", file=sys.stderr)

  fields = build_fields(csv_fields, candidates)
  area_name = args.name or park.display_name
  group_name = args.group or park.display_name
  block = generated_area_block(area_name, group_name, park, fields)

  if args.dry_run:
    print(block)
    return

  insert_area_block(args.area_kt, block, park.csv_url, bump_version=not args.no_version_bump)
  matched = sum(1 for field in fields if field.api_location_id)
  print(f"Added {park.display_name} ({park.prop_id}) to {args.area_kt}")
  print(f"Fields: {len(fields)} total, {matched} with live apiLocationId")
  if any(field.shared_fields for field in fields):
    print("Inferred simple whole/half-field sharedFields; review them before committing.")


if __name__ == "__main__":
  main()
