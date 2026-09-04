#!/usr/bin/env bash
#
# Throwaway. Relocates pre-unified-layout runs into runs/<project>/<requestId>/ and rewrites the
# four stored path attributes to match. Delete once it has run; git log is the archive.
# See openspec/changes/unified-run-prefix/design.md, "History is migrated by a throwaway script".
#
#   ./scripts/migrate-run-layout.sh <bucket> <table> [0|1]      # 1 = dry run (default)
#
# `set -e` here, unlike in user-data: a half-finished migration is worse than an aborted one, and
# there is no paid instance to orphan. Copy before rewrite, so the worst case is a stale path
# attribute rather than a lost object — and keys are never touched, so no row can be lost at all.
set -euo pipefail

BUCKET="${1:?usage: migrate-run-layout.sh <bucket> <table> [0|1]}"
TABLE="${2:?usage: migrate-run-layout.sh <bucket> <table> [0|1]}"
DRY="${3:-1}"

# The AWS CLI paginates a scan client-side and merges the pages, so this is the whole table.
aws dynamodb scan --table-name "$TABLE" \
  --projection-expression "pk,sk,requestId,resultPath,resultJsonKey,environmentJsonKey,profilerOutputPath" \
  --output json | python3 -c '
import json, subprocess, sys

bucket, table, dry = sys.argv[1], sys.argv[2], sys.argv[3] == "1"

# resultPath is authoritative; the other three are derived from it and are rewritten only where
# they are actually present. Writing an absent attribute as "" would invent data.
DERIVED = ["resultJsonKey", "environmentJsonKey", "profilerOutputPath"]

relocated = projects = skipped = 0
for item in json.load(sys.stdin)["Items"]:
    old = item.get("resultPath", {}).get("S")
    if not old:
        skipped += 1
        continue
    if old.startswith("runs/"):
        # Idempotent: a second pass completes and changes nothing.
        skipped += 1
        continue

    project = item["pk"]["S"].removeprefix("RESULT#")
    # RESULT#unknown relocates to runs/unknown/ by the same rule as every other project.
    # Re-attributing it would mean DeleteItem + PutItem, because pk is part of the key — which
    # would break the rule this migration keeps throughout: keys are never touched.
    new = "runs/{}/{}".format(project, item["requestId"]["S"])

    names, values = [], {":p": {"S": new}}
    for attribute in DERIVED:
        current = item.get(attribute, {}).get("S")
        if current:
            placeholder = ":" + attribute
            names.append("{} = {}".format(attribute, placeholder))
            values[placeholder] = {"S": current.replace(old, new, 1)}

    print("{} {} -> {}  [{}]".format(
        "DRY" if dry else "RUN", old, new, ", ".join(a.split(" = ")[0] for a in names) or "path only"))
    relocated += 1
    if dry:
        continue

    # Copy first, rewrite second. A crash between the two leaves both trees present and the item
    # still pointing at the old one, which is simply the pre-migration state plus dead bytes.
    subprocess.run(["aws", "s3", "cp", "s3://{}/{}/".format(bucket, old),
                    "s3://{}/{}/".format(bucket, new),
                    "--recursive", "--only-show-errors"], check=True)
    subprocess.run(["aws", "dynamodb", "update-item", "--table-name", table,
                    "--key", json.dumps({"pk": item["pk"], "sk": item["sk"]}),
                    "--update-expression", "SET " + ", ".join(["resultPath = :p"] + names),
                    "--expression-attribute-values", json.dumps(values)], check=True)

print("\n{} item(s) {}, {} skipped (already relocated or no stored path)".format(
    relocated, "would be relocated" if dry else "relocated", skipped), file=sys.stderr)
' "$BUCKET" "$TABLE" "$DRY"
