#!/usr/bin/env python3
# Copyright (C) 2026 Zac Sweers
# SPDX-License-Identifier: Apache-2.0

"""Suggest NYC Parks live API ids for FieldSpottr areas.

This script reads `areas.json`, fetches NYC Parks' public Mapbox vector tiles, and matches vector
tile `system` ids (for example `M165-FOOTBALL-1`) to fields that do not yet have `apiLocationId`.

By default it only prints suggestions. Pass `--apply-json` to write high-confidence matches to
`areas.json`. `Area.kt` is still the durable built-in catalog, so copy the printed Kotlin hint into
the DSL when adding new fields there.
"""

from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import struct
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[1]
USER_AGENT_SOURCE = (
  REPO_ROOT / "shared/src/commonMain/kotlin/dev/zacsweers/fieldspottr/data/NycParksApi.kt"
)
TILE_URL = "https://www.nycgovparks.org/maps/athletic_facility/{z}/{x}/{y}"
DEFAULT_BBOX = (-74.35, 40.45, -73.65, 40.95)
DEFAULT_ZOOM = 13


def load_user_agent() -> str:
  source = USER_AGENT_SOURCE.read_text()
  match = re.search(r'NYC_PARKS_USER_AGENT\s*=\s*"([^"]+)"', source)
  if not match:
    raise RuntimeError(f"Could not find NYC_PARKS_USER_AGENT in {USER_AGENT_SOURCE}")
  return match.group(1)


USER_AGENT = load_user_agent()


@dataclass(frozen=True)
class Candidate:
  system: str
  name: str
  propnum: str
  properties: dict[str, Any]


class ProtoReader:
  def __init__(self, data: bytes) -> None:
    self.data = data
    self.index = 0

  def eof(self) -> bool:
    return self.index >= len(self.data)

  def read_varint(self) -> int:
    shift = 0
    result = 0
    while True:
      if self.eof():
        raise EOFError("Unexpected EOF while reading varint")
      byte = self.data[self.index]
      self.index += 1
      result |= (byte & 0x7F) << shift
      if byte & 0x80 == 0:
        return result
      shift += 7

  def read_length_delimited(self) -> bytes:
    length = self.read_varint()
    value = self.data[self.index : self.index + length]
    self.index += length
    return value

  def read_field(self) -> tuple[int, int, Any]:
    key = self.read_varint()
    field_number = key >> 3
    wire_type = key & 0x7
    if wire_type == 0:
      value = self.read_varint()
    elif wire_type == 1:
      value = self.data[self.index : self.index + 8]
      self.index += 8
    elif wire_type == 2:
      value = self.read_length_delimited()
    elif wire_type == 5:
      value = self.data[self.index : self.index + 4]
      self.index += 4
    else:
      raise ValueError(f"Unsupported protobuf wire type {wire_type}")
    return field_number, wire_type, value


def decode_sint(value: int) -> int:
  return (value >> 1) ^ -(value & 1)


def decode_value(data: bytes) -> Any:
  reader = ProtoReader(data)
  decoded: Any = None
  while not reader.eof():
    field_number, _wire_type, value = reader.read_field()
    if field_number == 1:
      decoded = value.decode("utf-8", errors="replace")
    elif field_number == 2:
      decoded = struct.unpack("<f", value)[0]
    elif field_number == 3:
      decoded = struct.unpack("<d", value)[0]
    elif field_number in (4, 5):
      decoded = value
    elif field_number == 6:
      decoded = decode_sint(value)
    elif field_number == 7:
      decoded = bool(value)
  return decoded


def decode_feature(data: bytes, keys: list[str], values: list[Any]) -> dict[str, Any]:
  tags: list[int] = []
  reader = ProtoReader(data)
  while not reader.eof():
    field_number, wire_type, value = reader.read_field()
    if field_number == 2 and wire_type == 2:
      tag_reader = ProtoReader(value)
      while not tag_reader.eof():
        tags.append(tag_reader.read_varint())

  properties: dict[str, Any] = {}
  for i in range(0, len(tags), 2):
    if i + 1 >= len(tags):
      break
    key_index = tags[i]
    value_index = tags[i + 1]
    if key_index < len(keys) and value_index < len(values):
      properties[keys[key_index]] = values[value_index]
  return properties


def decode_layer(data: bytes) -> tuple[str | None, list[dict[str, Any]]]:
  name: str | None = None
  keys: list[str] = []
  values: list[Any] = []
  feature_bytes: list[bytes] = []

  reader = ProtoReader(data)
  while not reader.eof():
    field_number, _wire_type, value = reader.read_field()
    if field_number == 1:
      name = value.decode("utf-8", errors="replace")
    elif field_number == 2:
      feature_bytes.append(value)
    elif field_number == 3:
      keys.append(value.decode("utf-8", errors="replace"))
    elif field_number == 4:
      values.append(decode_value(value))

  return name, [decode_feature(feature, keys, values) for feature in feature_bytes]


def decode_tile(data: bytes) -> dict[str, list[dict[str, Any]]]:
  layers: dict[str, list[dict[str, Any]]] = {}
  reader = ProtoReader(data)
  while not reader.eof():
    field_number, wire_type, value = reader.read_field()
    if field_number == 3 and wire_type == 2:
      name, features = decode_layer(value)
      if name:
        layers[name] = features
  return layers


def fetch(url: str) -> bytes | None:
  request = urllib.request.Request(
    url,
    headers={
      "User-Agent": USER_AGENT,
      "Accept": "application/vnd.mapbox-vector-tile,application/octet-stream,*/*",
      "Accept-Language": "en-US,en;q=0.9",
      "Referer": "https://www.nycgovparks.org/permits/field-and-court/map",
    },
  )
  try:
    with urllib.request.urlopen(request, timeout=30) as response:
      body = response.read()
  except urllib.error.HTTPError as e:
    print(f"Skipping {url}: HTTP {e.code}", file=sys.stderr)
    return None
  except urllib.error.URLError as e:
    print(f"Skipping {url}: {e}", file=sys.stderr)
    return None

  if body.startswith(b"\x1f\x8b"):
    return gzip.decompress(body)
  return body


def lon_to_tile_x(lon: float, zoom: int) -> int:
  return math.floor(((lon + 180.0) / 360.0) * (2**zoom))


def lat_to_tile_y(lat: float, zoom: int) -> int:
  lat_rad = math.radians(lat)
  return math.floor(
    ((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0)
    * (2**zoom)
  )


def prop_id(area: dict[str, Any]) -> str | None:
  match = re.search(r"/issued/([^/]+)/csv", area["csvUrl"])
  return match.group(1) if match else None


def normalize(value: str) -> str:
  return re.sub(r"[^a-z0-9]", "", value.lower())


def field_sport_and_number(field_name: str) -> tuple[str, str | None]:
  sport = re.split(r"[-\s]", field_name)[0].upper()
  sport = {
    "SOFTBALL": "BASEBALL",
    "FUTSAL": "SOCCER",
  }.get(sport, sport)
  numbers = re.findall(r"\d+[A-Za-z]?", field_name)
  number = numbers[-1].lstrip("0") if numbers else None
  return sport, number


def score_candidate(field: dict[str, Any], candidate: Candidate) -> int:
  field_name = field["name"]
  display_name = field.get("displayName", "")
  sport, number = field_sport_and_number(field_name)

  if normalize(candidate.name) == normalize(field_name):
    return 100
  if normalize(candidate.name) == normalize(display_name):
    return 90
  if number and candidate.system.endswith(f"-{sport}-{number}"):
    return 80
  if number and f"-{sport}-{number}" in candidate.system:
    return 70
  if f"-{sport}-" in candidate.system:
    return 40
  return 0


def best_candidates(
  field: dict[str, Any],
  candidates: list[Candidate],
) -> list[tuple[int, Candidate]]:
  return sorted(
    (
      (score_candidate(field, candidate), candidate)
      for candidate in candidates
      if score_candidate(field, candidate) > 0
    ),
    key=lambda item: (-item[0], item[1].system),
  )


def collect_candidates(bbox: tuple[float, float, float, float], zoom: int, verbose: bool) -> list[Candidate]:
  min_lon, min_lat, max_lon, max_lat = bbox
  x_min = lon_to_tile_x(min_lon, zoom)
  x_max = lon_to_tile_x(max_lon, zoom)
  y_min = lat_to_tile_y(max_lat, zoom)
  y_max = lat_to_tile_y(min_lat, zoom)

  by_system: dict[str, Candidate] = {}
  for x in range(x_min, x_max + 1):
    for y in range(y_min, y_max + 1):
      url = TILE_URL.format(z=zoom, x=x, y=y)
      if verbose:
        print(f"Fetching {url}", file=sys.stderr)
      body = fetch(url)
      if not body:
        continue
      for features in decode_tile(body).values():
        for properties in features:
          system = properties.get("system")
          propnum = properties.get("gispropnum") or properties.get("propnum")
          if not system or not propnum or system in by_system:
            continue
          by_system[system] = Candidate(
            system=str(system),
            name=str(properties.get("name", "")),
            propnum=str(propnum),
            properties=properties,
          )
  return list(by_system.values())


def print_debug_tile(tile: str, zoom: int) -> None:
  x_text, y_text = tile.split(",", 1)
  url = TILE_URL.format(z=zoom, x=int(x_text), y=int(y_text))
  body = fetch(url)
  if not body:
    return
  layers = decode_tile(body)
  for name, features in layers.items():
    print(f"{name}: {len(features)} feature(s)")
    for feature in features[:8]:
      print(f"  {feature}")


def main() -> None:
  parser = argparse.ArgumentParser()
  parser.add_argument("--areas", default="areas.json", help="areas.json path")
  parser.add_argument("--area", help="Only process one areaName")
  parser.add_argument(
    "--bbox",
    default=",".join(str(value) for value in DEFAULT_BBOX),
    help="minLon,minLat,maxLon,maxLat",
  )
  parser.add_argument("--zoom", type=int, default=DEFAULT_ZOOM, help=f"Vector tile zoom")
  parser.add_argument("--apply-json", action="store_true", help="Write matches to areas.json")
  parser.add_argument(
    "--include-existing",
    action="store_true",
    help="Also check fields that already have apiLocationId values",
  )
  parser.add_argument("--verbose", action="store_true", help="Print tile fetch progress")
  parser.add_argument("--debug-tile", help="Print decoded layer/property samples for x,y")
  args = parser.parse_args()

  if args.debug_tile:
    print_debug_tile(args.debug_tile, args.zoom)
    return

  bbox_values = tuple(float(value) for value in args.bbox.split(","))
  if len(bbox_values) != 4:
    parser.error("--bbox must contain four comma-separated numbers")

  areas_path = Path(args.areas)
  areas = json.loads(areas_path.read_text())
  candidates = collect_candidates(bbox_values, args.zoom, args.verbose)
  candidates_by_prop: dict[str, list[Candidate]] = {}
  for candidate in candidates:
    candidates_by_prop.setdefault(candidate.propnum, []).append(candidate)

  updates = 0
  for area in areas["entries"]:
    if args.area and area["areaName"] != args.area:
      continue
    prop = prop_id(area)
    if not prop:
      continue
    park_candidates = candidates_by_prop.get(prop, [])
    print(f"\n{area['areaName']} ({prop})")
    if not park_candidates:
      print("  No vector tile features found for this park.")
      continue

    for group in area["fieldGroups"]:
      for field in group["fields"]:
        existing_api_id = field.get("apiLocationId")
        if existing_api_id and not args.include_existing:
          continue
        scored = best_candidates(field, park_candidates)
        if not scored:
          suffix = f" (currently {existing_api_id})" if existing_api_id else ""
          print(f"  {group['name']} / {field['name']}: no obvious match{suffix}")
          systems = ", ".join(sorted(candidate.system for candidate in park_candidates))
          print(f"    Park candidates: {systems}")
          continue

        best_score = scored[0][0]
        best = [item for item in scored if item[0] == best_score]
        if len(best) == 1 and best_score >= 80:
          candidate = best[0][1]
          if existing_api_id:
            status = "ok" if existing_api_id == candidate.system else "mismatch"
            print(
              f"  {group['name']} / {field['name']}: {existing_api_id} ({status}, best {candidate.system})"
            )
            if status == "mismatch":
              print(f"    Kotlin: apiLocationId = \"{candidate.system}\",")
            continue

          if args.apply_json:
            field["apiLocationId"] = candidate.system
          updates += 1
          print(f"  {group['name']} / {field['name']}: {candidate.system}")
          print(f"    Kotlin: apiLocationId = \"{candidate.system}\",")
        else:
          print(f"  {group['name']} / {field['name']}: ambiguous")
          for score, candidate in best[:8]:
            print(f"    [{score}] {candidate.system} {candidate.name}")

  if args.apply_json:
    areas_path.write_text(json.dumps(areas, indent=2) + "\n")
    print(f"\nUpdated {areas_path} with {updates} apiLocationId value(s).")
    print("Remember to update shared/src/commonMain/kotlin/.../data/Area.kt too.")
  else:
    print(f"\nFound {updates} high-confidence apiLocationId suggestion(s).")
    print(f"Run with --apply-json to write them to {areas_path}.")


if __name__ == "__main__":
  main()
