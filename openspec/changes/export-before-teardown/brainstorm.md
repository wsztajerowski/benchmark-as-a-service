# Brainstorm — export before teardown

## Design Summary

Two commands, `baas admin export` and `baas admin import`, that move the whole data plane — the
DynamoDB results table and the S3 bucket — between AWS and a local directory. `baas admin teardown`
gains `--export-to`, and only a teardown that has just exported and verified is allowed to delete
the retained table.

The unit is a **bundle**: one local directory holding the table as DynamoDB JSON Lines, a manifest
describing where it came from, and a mirror of every object in the bucket.

```
./baas-export-20260820T140000Z/
  manifest.json      source account/region/prefix/table/bucket, counts, bytes,
  table.jsonl        which parts ran, export time, CLI version
  s3/runs/<project>/<runId>/...   # unified layout; see RunLayout
```

Nothing is written back into the bucket. The bundle is the single copy, and it lives on the
operator's disk.

Export runs in one order: preflight that the table and bucket exist and the credentials can scan,
stream the table into `table.jsonl` as pages arrive, list the bucket, download each key under `s3/`,
then verify the counts against a re-list and report them. Import reverses it into whatever the
current config points at, skipping anything already present.

**Comparability:** nothing here touches the runner, the image or user-data. Both commands read
existing state and write it elsewhere; the measurement path is untouched, so no stored result
changes meaning.

## Alternatives Considered

### Alternative A: Native DynamoDB export to S3

- **Approach**: `ExportTableToPointInTime` writes the table to S3 server-side; `ImportTable` reads
  it back. No scan, no CLI-side codec.
- **Pros**: no read capacity consumed, a genuinely consistent point-in-time snapshot, output
  queryable by Athena without further work.
- **Cons**: requires point-in-time recovery, a continuous per-GB charge on a project whose entire
  standing cost is the ~$0.20/month AMI snapshot; `ImportTable` can **only create a new table**,
  never load into an existing one.
- **Why not chosen**: it does not close the import half. The CloudFormation-created table cannot be
  an import target, so a scan-and-write importer would have to be built anyway — and then the
  standing cost buys only the export side. A new standing cost has to be argued, and this one
  cannot be.

### Alternative B: Export embedded in teardown, no standalone import

- **Approach**: teardown dumps the table and mirrors the bucket as one of its steps. Restoring is a
  documented `aws` CLI procedure rather than a command.
- **Pros**: smallest possible surface; nothing to specify about targeting, conflicts or partial
  operation; the only moment data is at risk is the moment it is protected.
- **Cons**: backing up without tearing down is impossible, and restoring — the operation performed
  under pressure, having just lost something — is the one left as a manual procedure.
- **Why not chosen**: the goal is moving data in both directions, not draining it. A backup format
  nothing can read back is a hope, not a guarantee.

### Alternative C: Dump the measurement model rather than the wire shape

- **Approach**: decode items through `MeasurementItemMapper` and write the domain shape; import
  re-encodes through `ResultKeys`.
- **Pros**: readable without knowing DynamoDB's type encoding, stable against an SDK change, and it
  doubles as the analytics dataset the `dynamodb-results-store` change deferred.
- **Cons**: the round trip is bounded by the mapper — any attribute it does not model is dropped
  silently rather than failing, and every future item-shape change becomes a backup-compatibility
  question.
- **Why not chosen**: a backup that loses what it does not understand is a different guarantee from
  one that copies bytes. An analytics-shaped export is a separate job and can be added later without
  disturbing this one.

## Agreed Approach

Alternative A's shape (a file of DynamoDB JSON) without its mechanism, and neither B's nor C's
compromises: **a CLI-side scan into DynamoDB JSON Lines, plus a full bucket mirror, into one local
bundle, with a symmetric import.**

JSON Lines is what makes the rest work. It streams on both sides with no whole-table load, it
appends as the scan pages so an interrupted export leaves a valid prefix rather than a truncated
document, and because it carries the wire shape verbatim, `aws dynamodb put-item` can read it line
by line if the CLI is ever unavailable. The data is not hostage to the tool that wrote it.

Teardown is the consumer that justifies the name. `--delete-table` is refused unless `--export-to`
ran and verified in the same invocation, so the only command that can destroy benchmark history is
one that has just finished preserving it.

## Key Decisions

**Format and bundle**

- DynamoDB JSON Lines, one typed `AttributeValue` map per line. Wire shape, not domain shape.
- The bundle is `manifest.json` + `table.jsonl` + `s3/<key>`, and it is the only copy. An earlier
  sketch also archived the dump to an `exports/` prefix in the bucket; dropped, because the local
  file is written first and is what the operator keeps, and a copy inside a bucket that teardown is
  about to delete protects nothing. Consequently the mirror needs no exclusion rule, no `exports/`
  prefix exists, and export needs no S3 write grant.
- The mirror takes **every** key. Uploaded inputs and build logs come down with the results —
  `runs/<requestId>/` is the only copy of the exact JAR a measurement ran, which matters for a tool
  whose product is comparability.

**Import**

- Targets the table and bucket the current configuration resolves, never the names recorded in the
  bundle. **Superseded reasoning, same conclusion:** this used to argue that names derive from a
  hash of the caller ARN, so restoring under a different identity *always* meant different names.
  Since `account-derived-installation-naming` that is false — names derive from the account, so a
  different identity on the *same* account resolves the *same* names. The conclusion holds for a
  better reason: the bundle records where it came from, the current configuration records where it
  is going, and only the second can be right about the target. It still makes
  restore-into-a-fresh-installation and migrate-to-another-account the same operation.

  Note also that the target names are now *derived* rather than stored, so "the table and bucket in
  `~/.baas/config.yaml`" means the ones its `prefix` resolves to.
- `manifest.json` records the source account, region, prefix, table, bucket, counts and export time.
  When source and target differ, import prints both and requires the target table name to be typed —
  the same confirmation shape `TeardownCommand` already uses.
- Skip-existing by default: items are written with `attribute_not_exists(pk)`, objects already in the
  bucket are left alone. Re-running an interrupted import is therefore safe, and a bundle can be
  merged into a live table without clobbering newer rows. `--overwrite` makes writes unconditional.
- This forces per-item writes on the default path, because `BatchWriteItem` cannot carry condition
  expressions. `--overwrite` uses 25-item batches with an unprocessed-items retry loop. At this
  table's size the conditional path costs seconds.

**Both commands**

- `--table-only` and `--s3-only`; default is both. The table dump is small and the mirror is
  gigabytes, so the fast path has to be available alone.
- `--dry-run` on import, matching the idempotent-with-dry-run precedent set by the Atlas migration
  script in `dynamodb-results-store` — deleted after use, as that change planned, so the precedent
  is in its archive rather than in `scripts/`.

**Teardown**

- `teardown --export-to <dir>` exports, verifies counts, and only then honours `--delete-table` and
  `--delete-bucket`. Without a verified export in the same invocation, `--delete-table` is refused
  with a message naming the export command.
- This changes documented behaviour: CLAUDE.md currently states teardown never deletes the results
  table and there is no flag to. The export requirement is what earns the flag, and the doc has to be
  rewritten rather than quietly contradicted.

**IAM**

- `dynamodb:Scan`, `dynamodb:PutItem` and `dynamodb:BatchWriteItem` are added to
  `deployer-policy.json` only. Both commands live under `admin` and run as `aws.operatorProfile`; the
  operator policy stays read-shaped (`Query` + `GetItem`), so day-to-day credentials cannot rewrite
  the results table.
- No new S3 grant. The deployer already holds `s3:Get*` and `s3:List*` on the bucket, which is all
  the mirror needs now that nothing is uploaded.
- Re-rendering and re-attaching the deployer policy is a manual step, and `dynamodb-results-store`
  was bitten by exactly this — the attached policy is inline on an IAM group, not the
  customer-managed flow `infra/README.md` describes. It has to be sequenced before any live
  verification.

**Implementation placement**

- The `AttributeValue` <-> JSON codec lives in `baas-cli/infra`, not `baas-model`. It carries no key
  knowledge and the runner has no use for it; `baas-model` is reserved for the item shape,
  `ResultKeys` and `MeasurementItemMapper`. Jackson is already on the classpath transitively via
  `jackson-dataformat-yaml`; nothing in the SDK maps the wire shape without the Enhanced Client, so
  the codec is hand-written over the nine types (`S N B BOOL NULL M L SS NS BS`).
- The mirror reuses `S3UploadService.listKeys` (paginates the whole bucket) and `download` (creates
  parent directories). Two additions: reject any key that escapes the bundle root once resolved,
  since S3 keys may contain `..` and a mirror is the first thing that would write one to disk; and
  skip a key whose local file already exists with matching size, which makes an interrupted mirror
  resumable.

**Stated limits, not solved**

- A table scan is not a point-in-time snapshot. A standalone export racing a live run can miss items
  written after the page that covered them. Teardown's existing "no running instances" gate closes
  this for the destructive path, which is the one that matters.
- If versioning stays enabled on the bucket, a bundle carries current versions only; noncurrent
  versions are not backed up. Whether that limit exists at all depends on `unified-run-prefix`.

## Open Questions

1. **Does `import` create missing resources, or require `baas admin setup` first?** Leaning towards
   requiring setup — the stack owns resource creation, and an importer that created a table would
   create one CloudFormation does not know about. Needs deciding before the specs.
2. **Bucket versioning**, owned by `unified-run-prefix`. Its answer decides whether the
   current-versions-only limit above is real or moot.
3. **Mirror throughput.** A fixed-size download pool is assumed; the size, and whether progress
   reporting is per-object or periodic, are design-time details.
4. **`manifest.json` schema versioning policy** — whether a future CLI must read old bundles, and for
   how long.
5. **Should export eventually be runnable with the day-to-day profile?** Backup-by-developer is a
   plausible want, and `dynamodb:Scan` on the operator policy would be enough. Deferred: nobody has
   asked, and the grant is easy to add later.

## Relationship to `unified-run-prefix`

That change re-keys the bucket so one run is one prefix, re-scopes the lifecycle rule that would
otherwise expire results, and moves the runner JAR out of per-run copies. It lands first. This change
does not technically depend on it — the mirror copies whole prefixes and is indifferent to their
shape — but the bundle layout above is documented against the settled layout, and freezing a backup
format before the thing being backed up stops moving would mean writing it twice.

## Decisions recorded 2026-10-02 (supersede parts of the above; reconcile at the proposal step)

Made while scoping `teardown-retires-runner-image`. The text above is left as written; where it
disagrees with this block, this block wins.

- **Teardown removes everything BaaS created, every time:** the core stack, the runner image (already
  retired unconditionally by `teardown-retires-runner-image`), the S3 bucket and the results table.
  `--delete-bucket` goes away, and so does the planned `--delete-table`. Nothing is left for a later
  setup to trip over or inherit.
- **A second, separate confirmation guards the data loss.** After the usual stack-name confirmation,
  teardown says plainly that every result and artifact will be deleted, recommends running
  `baas admin export` first, and requires a specific typed phrase. Whether `--yes` may skip this
  second step, and how it behaves without a terminal, are still open.
- **Export is decoupled from teardown.** No `--export-to`, and no gate making table deletion depend on
  an export in the same invocation. Teardown advises; the operator decides.
- **Export's parts are selectable.** The table is exported whole by default. The bucket is optional or
  filterable, because old run artifacts may not be worth keeping. This replaces "the mirror takes every
  key" above.
- **Import targets a freshly set-up installation**, as Open Question 1 above already leaned towards.
  Reuse of retained resources through CloudFormation resource import was considered and dropped: the
  data no longer needs to survive inside AWS.
- **The template keeps `DeletionPolicy: Retain`** on the bucket and table, and teardown deletes them
  explicitly after both confirmations. `Retain` then guards only against deletion *outside* baas (the
  console, `aws cloudformation delete-stack`), which bypasses both confirmations. Check the deployer
  policy for `dynamodb:DeleteTable` in the design.
- **Sequencing:** teardown's data deletion ships only with, or after, `baas admin export`, so the
  destructive behaviour never exists without its safety net. Hence it lives in this change, not in
  `teardown-retires-runner-image`, which ships image retirement on its own first.
- CLAUDE.md's "benchmark history outlives any single stack" and the *Results table* / teardown
  invariants will need rewriting when this lands; the specs' "table deletable by no flag at all" too.

## Scenario to cover — review U12 (added 2026-10-02)

A failed first `baas admin setup` rolls back, and `DeletionPolicy: Retain` keeps the results table
through the rollback. Teardown then leaves it, and the next setup's pre-check refuses on it; today
only `aws dynamodb delete-table` clears it. Decided: no separate fix — this change closes it. Its
spec should carry the scenario *rolled-back first create → teardown → setup succeeds*, whichever
teardown shape the proposal settles on.

## Note from `run-status-in-dynamodb` (2026-10-02)

That change, first in the queue, adds **run items** to the results table: one per run at
`pk = RUN`, `sk = <createdAt>#<runId>`, `gsi1pk = <runId>`, `gsi1sk = RUN`. The table is then no
longer measurements only. The export's `table.jsonl` carries them verbatim, which is the point of
exporting the wire shape. Any counting, verification or import step that assumes every item is a
measurement must allow for them. An import into a fresh installation restores them as-is: they are
history, and their non-terminal statuses resolve as vanished.
