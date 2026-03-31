#!/usr/bin/env bash
#
# Creates a SQLDelight schema snapshot (.db file) for migration testing.
#
# Usage:
#   ./scripts/create-db-snapshot.sh
#
# This generates the current schema as a .db file named after the current
# schema version. Place it BEFORE adding new tables/columns, then add your
# migration .sqm file with the same version number.
#
# Example workflow for adding a new table:
#   1. Run this script to snapshot the current schema
#   2. Add the new table to fsdb.sq
#   3. Create N.sqm with the ALTER/CREATE statements
#   4. Run: ./gradlew :shared:verifySqlDelightMigration
#

set -euo pipefail

SQLDELIGHT_DIR="shared/src/commonMain/sqldelight/dev/zacsweers/fieldspottr"

# Count existing .db files to determine current version
CURRENT_VERSION=$(find "$SQLDELIGHT_DIR" -name "*.db" | wc -l | tr -d ' ')
NEXT_VERSION=$((CURRENT_VERSION + 1))
OUTPUT_FILE="$SQLDELIGHT_DIR/$NEXT_VERSION.db"

if [[ -f "$OUTPUT_FILE" ]]; then
  echo "ERROR: $OUTPUT_FILE already exists."
  exit 1
fi

# Generate the current schema into a temp db, then copy it
TEMP_DB=$(mktemp /tmp/fsdb_snapshot.XXXXXX.db)
trap 'rm -f "$TEMP_DB"' EXIT

# Use gradle to generate the schema
./gradlew :shared:generateCommonMainFSDatabaseSchema \
  -Dsqldelight.schema.output="$TEMP_DB" \
  --quiet 2>/dev/null || true

# If gradle task doesn't support that flag, fall back to creating manually
# from the .sq file
if [[ ! -s "$TEMP_DB" ]]; then
  echo "Generating schema from fsdb.sq..."
  # Extract CREATE TABLE statements from the .sq file
  grep -A 20 "^CREATE TABLE" "$SQLDELIGHT_DIR/fsdb.sq" | \
    sed '/^$/d' | \
    sqlite3 "$TEMP_DB"
fi

cp "$TEMP_DB" "$OUTPUT_FILE"
echo "Created schema snapshot: $OUTPUT_FILE (version $NEXT_VERSION)"
echo ""
echo "Next steps:"
echo "  1. Add your schema changes to fsdb.sq"
echo "  2. Create $SQLDELIGHT_DIR/$NEXT_VERSION.sqm with migration SQL"
echo "  3. Run: ./gradlew :shared:verifySqlDelightMigration"
