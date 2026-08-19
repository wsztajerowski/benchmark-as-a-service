## Context

Measurements currently live in a MongoDB Atlas cluster that BaaS connects to but never provisions. Two
modules reach it independently: `benchmark-runner` writes through Morphia entities, and `baas-cli` reads
through raw `org.bson.Document` field paths (`"_id.requestId"`, `"benchmarkMetadata.tags.branch"`). The
two share no contract, so a schema change breaks reads silently rather than at compile time (finding
**A3**).

Atlas is also the last reason a runner needs public internet on its data path: it serves clients on
27017 over the public internet, behind an `0.0.0.0/0` access list because runners get a fresh public IP
per run. DynamoDB has a free gateway endpoint, so moving the store removes both the external dependency
and the network constraint that blocks the sibling `private-runner-network` change.

The access pattern was **derived from evidence rather than assumed** — see `brainstorm.md` for the full
analysis. Two sources were used: the retired `scripts/benchmark_overview.sh` (recovered via
`git show 889731b^`), which is the tool that was actually used day to day, and a 90-question catalogue
of what a results viewer could plausibly be asked, supplied as discussion input rather than as
requirements. The historical tool always filtered `project` + `type` — a match set that is essentially
the whole dataset — then grouped `(benchmark, branch)` client-side and kept the best score. It has no
time-based access pattern, no request-id lookup and no arbitrary-tag search. Of the 90 questions, 20 are
selection and 70 are computations over a selected set. Both sources say the same thing: the workload is
*load a coarse working set and compute over it*, not *seek a key*.

## Goals / Non-Goals

**Goals:**

- The results store is created, tagged and retained by the same CloudFormation stack as every other BaaS
  resource.
- The queries `baas results` actually serves are answered by a single `Query`, with no table scan.
- One definition of the stored shape and its tag vocabulary, shared by both modules.
- Items stay far below DynamoDB's 400 KB limit while full-fidelity results remain retrievable from S3.
- `benchmark-runner` remains usable as a standalone JAR against a user's own MongoDB.
- Existing Atlas history is carried over without loss.
- No standing cost beyond per-request billing.

**Non-Goals:**

- Preserving the Mongo document shape. The stored shape is redesigned around the access pattern.
- Analytical querying — comparison, regression detection, trend, ranking, comparability scoring. These
  are 70 of the 90 catalogued questions and none of them is made cheaper by indexing.
- Index-backed arbitrary tag search, a time index, or per-dimension GSIs.
- Cross-region replication, point-in-time recovery, or a TTL.
- MongoDB anywhere in `baas-cli`. The retained adapter is runner-local and invisible to the CLI.
- Changing the AMI, the termination layers, or the subnet model. Those belong to the sibling changes.

## Decisions

### The table is one item per measurement on a per-project partition, with no derived index items

```
pk  = RESULT#<project>
sk  = <fqcn>#<method>#<mode>#<createdAt>#<requestId>     (JMH)
sk  = JCSTRESS#<createdAt>#<requestId>                   (JCStress)
GSI = pk: requestId, sk: <fqcn>#<method>#<mode>
```

`sk` is benchmark-major then chronological, which serves three patterns from one ordering: the latest
result for a benchmark (`begins_with` + descending + `Limit 1`), a benchmark's history in order, and
grouping — rows arrive grouped by benchmark, so bucketing happens within one benchmark at a time and
never needs a global sort. The GSI covers `--request-id`, the only pattern the base key cannot reach
because `requestId` sits at the tail of `sk`.

`mode` sits between `method` and `createdAt` because the Mongo store this replaces keyed on
`(requestId, benchmarkName, benchmarkType)` where `benchmarkType` is the JMH mode: `-bm thrpt,avgt` in
one run produces two results with identical class+method, and without mode in the key they are
differentiated only by a millisecond timestamp — a same-millisecond collision silently overwrites one
via `PutItem`. A null mode (JCStress has none) renders as an empty field rather than being omitted, so
the key always carries a fixed number of `#`-separated fields. This keeps the benchmark-major-then-
chronological property and improves it: a benchmark's history within one mode is now contiguous, and
`begins_with(<fqcn>#<method>)` still returns all modes.

*Alternatives considered.* **Hand-written inverted index items** (the previous design: a
`TAG#<key>#<value>` index, a benchmark index partitioned on class, and a month-partitioned `ALL#<yyyy-mm>`
time index, six to eight items per result in one `BatchWriteItem`) lost because its sole advantage was
index-backed arbitrary tag search — a requirement the previous design admits was invented during design,
and which neither historical tool has ever had. The time index likewise served a query that has never
existed. It optimises selective lookups the workload does not perform, while the dominant query cannot
be indexed away in any case, and it costs runner-owned index consistency, a reserved non-indexed tag-key
set, a hot `TAG#branch#main` partition, and a delete path for derived items.
**One or two GSIs** lost because the dominant sweep is still not selective, so it degrades to a `Scan`
regardless; each new filterable dimension needs another GSI plus a backfill, against a list of roughly
eighteen dimensions and a hard cap of twenty; and single-dimension GSIs do not serve conjunctive filters.

### `type` is deliberately excluded from the partition key

`type` has four values (`jmh`, `jmh-with-profiler`, `jmh-with-async`, `jcstress`). Keying on it would
split the same benchmark's comparable JMH-family results across partitions, so it stays a tag.

### `project` is derived from the git repo name, overridable with `--project`

It needs no configuration and matches the existing behaviour that `baas run` builds in the current
working directory.

*Alternatives considered.* A config field is explicit but needs a defined failure when unset and does not
travel with the project. The Maven `artifactId` is stable and meaningful but couples the partition key to
the build file and breaks under `--benchmark-jar --skip-build`. A single fixed partition is simplest but
concentrates every write on one unbounded key — which this design rejects for the same reason the
previous one rejected a single `ALL` time partition.

### MongoDB survives as a runner-local write adapter, invisible to the CLI

`benchmark-runner` is a standalone artifact — one JAR, no stack, no CLI — and that deployment must keep
working against a user's own MongoDB. Inside BaaS, DynamoDB is the only store.

This is what makes the retained adapter free of consequences elsewhere: BaaS never selects Mongo, so
`RunnerSecurityGroup` still drops 27017, `private-runner-network` stays unblocked, and no NAT gateway
cost appears. It also avoids a read-side asymmetry — `baas results` never needs to read Mongo, because
anything BaaS produced is in DynamoDB, and a standalone user reads their own store however they like.

**The port speaks domain, not storage.** Each adapter owns its physical layout, so one item per
measurement maps cleanly to one document per measurement and nothing DynamoDB-specific leaks through the
interface. The previous design's six-to-eight derived items would have made a shared port impossible.

*Alternatives considered.* Deleting Mongo outright is the smallest surface but forecloses the standalone
deployment entirely. Making the adapter permanent and first-class *including in the CLI* would require
two query implementations, two integration suites, and would leave query capability varying by backend.

### Tags are the uniform query surface, governed by a known-key vocabulary

Every queryable dimension is a tag — `project`, `type`, `commit`, `jdk`, `cpuModel`, `cpuArch`,
`instanceType`, `imageVersion` — rather than a mix of typed fields and tags. Under client-side evaluation
this costs nothing: a `Map<String,String>` entry filters exactly as fast as a top-level attribute, so the
field-versus-tag boundary is a schema-discipline question, not a capability one. Only what composes the
partition key must be structurally guaranteed.

This matches the existing invariant that the runner reports the environment it *observed* via `--tag`, so
a result's tags cannot disagree with its own `environment.json`.

Free-form tags fail silently on typos — `--tag jvm=21` returning nothing is indistinguishable from "no
results". The mitigation is a known-tag-key vocabulary as constants in `baas-model`, written by the runner
and validated by the CLI, with unknown keys permitted but warned about. This is finding **A3**'s lesson
applied to tags.

`cpuModel` and `cpuArch` are kept separate because they answer different questions — the specific CPU
versus ARM64-vs-x86 — and both are already captured in `environment.json`, so this is a copy rather than
new observation work.

### `branch` is a custom user tag, not a known key

The vocabulary covers dimensions the runner can *observe* on the instance or the CLI can derive
unambiguously. `branch` is a workflow concept that only some callers have, so a user who wants
branch-aware results passes `--tag branch=<name>` like any other custom dimension.

**Consequence:** the documented `baas results` behaviour groups by `(benchmark, branch)`. With `branch`
optional, grouping cannot assume it exists, so the grouping key becomes `(benchmark, <group-tag>)`
defaulting to `branch`, and rows missing that tag group under a single untagged bucket rather than being
dropped or erroring. `--living-branches` filters on the tag when present and is a no-op when absent.

### DynamoDB holds the index; S3 holds the fidelity; the CLI can fetch a whole run

The item carries what is needed to list, filter and locate. Everything else — the verbatim JMH result
JSON, `environment.json`, logs, profiling artifacts — stays in S3, and this change adds the CLI capability
to download a run's entire prefix. Shipping a thin item without that fetch would leave a promise the CLI
could not keep. Uploading the verbatim JMH result JSON also closes the documented "measurements live only
in MongoDB, there is no `result.json`" gap.

### Analytics is deferred to an export bridge, not served by the key schema

Of the 90 catalogued questions, 70 are computations over a selected set — pairwise comparison, regression
detection, trend, ranking, comparability reasoning. None becomes cheaper by indexing, and serving them
would need roughly eighteen filterable dimensions. Three model extensions were considered and deferred on
that basis: **precomputed statistics** (`scoreError` already conveys spread for a table view, and
`rawData`/`scorePercentiles` remain in the S3 result JSON), **`release` and `PR` provenance** (`PR` is a CI
concept not sourceable from `baas run` on a laptop; `commit` is kept because `git rev-parse HEAD` is
trivially available), and **JCStress per-test items** (`JCStressResult` names only non-passing tests —
passing ones are counted, never named — so per-test granularity would cover failures only, and those
result files are already in S3).

### A low-level client with an explicit mapper, in a shared module

The Enhanced Client's model is one annotated class per table and Java records need hand-written builders
for `@DynamoDbImmutable` anyway. An explicit `Map<String, AttributeValue>` mapper gives exact control over
key encoding and doubles as the schema contract. It lives in `baas-model` alongside the item shape, key
encoding and tag vocabulary, so an incompatible change breaks compilation instead of returning zero rows.

`baas-model` sits on `baas-cli`'s classpath and must therefore stay **Mongo-free**; the runner's Mongo
adapter maps the same neutral shape independently. `JmhResult` and `Metric` stay in `benchmark-runner` as
*parsing* types for JMH's JSON output — parsing an external format and defining our storage format are
different jobs.

### Timestamps are fixed-width UTC ISO-8601 strings

`createdAt` is a `LocalDateTime` today, produced from `OffsetDateTime.now(ZoneOffset.UTC)` in one place and
left implicit in another. Sort keys must sort chronologically *as strings*, so the stored form is a
fixed-width UTC ISO-8601 instant. Variable-width or zone-ambiguous formatting would silently misorder
results, and the failure would look like missing data rather than a formatting bug.

### `upsert` is deleted

`DatabaseService.upsert` — field-path filters and `set` operators — is called by none of the four
subcommand services. Porting a Mongo-shaped update-operator surface for zero callers is pure cost, and its
field-path shape would leak Mongo semantics into a port that must serve both adapters.

### Exclusion is a server-side filter; grouping is client-side

`exclude_from_results=true` must be applied *negatively*, and no index answers "not tagged X", so it is a
`FilterExpression` on the projected attribute. Grouping and best-score selection are client-side because
DynamoDB performs no aggregation. Recorded explicitly because it is the kind of constraint that gets
discovered late and then papered over with a scan.

### Discarding results requires `--no-database`

Today an empty connection string selects a no-op store, so a run reports success while paid measurements
are thrown away. Absence of configuration becomes a hard failure and discarding requires naming the intent.

### The table is retained, and setup pre-checks for it

`DeletionPolicy: Retain` mirrors `S3MainBucket` — the results table is more the benchmark history than the
bucket is. This inherits the bucket's known trap, where a retained resource blocks the next setup with a
CloudFormation error that never names the culprit, so `SetupCommand`'s existing pre-check gains a twin.

### Migration is a throwaway script

`scripts/migrate-atlas-to-dynamodb` reads Atlas and writes result items, idempotently and with a dry-run,
then is deleted. This keeps `mongodb-driver-sync` out of the shipped CLI permanently. Acknowledged:
nothing in CI exercises `scripts/`, so the script is protected only by its dry-run and by manual
verification against row counts.

## Risks / Trade-offs

- **The working-set sweep is the load-bearing assumption** → At roughly 1 KB per item, 10k items is
  ~10 MB and sub-second; 100k is ~100 MB and noticeably slow; past ~1M it fails. Count the Atlas rows
  before implementing (tasks §1). Documented escape hatch: shard `pk` by year
  (`RESULT#<project>#<yyyy>`), costing one query per year spanned.
- **`baas run --tag` currently reaches nothing** → The entire tag-based query model depends on threading
  `extraTags` through `UserDataScriptBuilder`. Verified as a blocking assumption before implementation,
  and covered by a test asserting a user tag reaches the runner rather than only the instance — mirroring
  the existing `passesEnvironmentTagsToTheRunnerNotJustToTheInstance`.
- **Renaming a git repository splits history across partitions** → Accepted at current scale, since
  `project` is derived from the repo name. Recoverable by re-writing keys; `--project` overrides.
- **Item size is bounded by convention, not by construction** → `tags` and the reduced `secondaryMetrics`
  are unbounded in principle. Assert size in the mapper's tests and fail the write loudly rather than
  truncating.
- **Two adapters mean two write paths to keep behaviourally aligned** → Mitigated by the port speaking
  domain only, and by running the same store contract test against both adapters.
- **Timestamp format changes are silent if wrong** → Assert lexicographic ordering equals chronological
  ordering in a unit test over instants spanning month and year boundaries.
- **Migration fidelity** → The Mongo `_id` is composite for JMH and a bare `requestId` for JCStress. The
  script must derive both key forms. Verify by comparing row counts and spot-checking scores before
  decommissioning Atlas.
- **`--mongo-uri` removal breaks scripted use** → Called out in the migration notes; at current scale the
  affected surface is one operator.

## Migration Plan

1. Fix the `--tag` threading and confirm a user tag reaches a stored result. Nothing else works without it.
2. Ship the table, gateway endpoint and IAM changes; deploy with `baas admin setup`. Atlas is untouched.
3. Land `baas-model`, the `ResultsStore` port, the DynamoDB adapter and the query rewrite, verified
   against LocalStack. The Mongo adapter is refactored onto the port in the same step.
4. Cut the runner over to DynamoDB, removing `--mongo-uri` and the SSM connection-string fetch, and
   confirm a live run writes its items.
5. Verify the live path: stored tags agree with the run's `environment.json`, `baas results` serves the
   new rows through every filter, and a whole-run download recovers what the item drops.
6. Run `scripts/migrate-atlas-to-dynamodb --dry-run`, review the reported counts, then run it for real.
7. Verify the migration: row counts match, `baas results` agrees with historical output modulo the
   documented `tags.project` filter difference, and spot-checked scores are identical.
8. Decommission the Atlas cluster, delete the SSM parameter by hand, and drop the 27017 egress rule.
9. Delete the migration script.

**Sequencing rulings (2026-08-19).** `tasks.md` was resequenced to match this plan, because its
section order contradicted it. Three decisions:

- **Cutover precedes migration (revised 2026-08-19; this ruling originally said the reverse).**
  Migration is unblocked as soon as §3 and the table exist, which is what first put it early — but
  being *able* to run first is not a reason to. Migrating first writes 121 historical documents
  into a schema no live run has exercised, so a schema defect costs a re-migration; cutting over
  first costs only a re-run of an idempotent script. It also removes the sweep entirely: with §9
  after the cutover, no run is ever written to Atlas afterwards, so there is no window of
  Atlas-only runs to catch up, and task 13.5 is dropped. The cost is that `baas results` reports
  only post-cutover runs until §9 lands — Atlas stays readable throughout, so nothing is lost, and
  the two manual checks that genuinely need history are held back to §12b. 9.6's idempotency
  requirement stays, now covering a partial or interrupted run rather than a sweep.
- **No dual-write.** A composite adapter would contradict the single-adapter port above, and it
  carries an ambiguous failure rule when one store succeeds and the other does not. Assurance comes
  instead from §8's integration tests against LocalStack, §9.8's verification across all historical
  rows, §12's live checks after cutover, and Atlas being retained as rollback until §14.
- **Mongo removal is last.** 4.3 and 4.8 moved to §14 along with the SSM-parameter deletion and the
  Atlas decommission, because all four destroy the rollback path below. The cutover itself (former
  7.3-7.6) moved to §13, which the ruling above then placed *before* §9.

**Rollback:** until step 4 the Mongo path is still selected and Atlas is still being written, so reverting
is a no-op. Between steps 4 and 8, Atlas still holds every pre-cutover measurement and the code change is
revertible, so rollback means restoring the step-4 commits and accepting that runs made after the cutover
live only in DynamoDB — which is retained regardless, so no measurement is lost. After step 8 Atlas is
gone and there is nothing to roll back to; that is why it is last.

## Resolved Questions

- **Which tag keys belong in a reserved, non-indexed set?** — Moot. There are no tag index items, so
  high-cardinality keys such as `options` cost nothing beyond their own bytes. *Evidence:* the inverted
  index was dropped.
- **Should any `Scan` capability be retained for ad-hoc investigation?** — No. Every supported query is a
  `Query` on the project partition or the `requestId` GSI. *Evidence:* `benchmark_overview.sh` and
  `ResultsQueryService` between them use no pattern that a partition sweep does not cover.
- **Does `baas results` need a `--since` bound?** — No. *Evidence:* neither the historical script nor the
  current CLI has any time-based filter, ordering or limit; the month-partitioned time index in the
  previous design served a query that has never existed.
- **Field or tag for provenance?** — Tag, uniformly. *Evidence:* under client-side evaluation the boundary
  makes no performance difference, so uniformity wins on schema discipline.
- **Where does `project` come from?** — The git repo name, overridable with `--project`.
- **Is the whole-run S3 download part of this change?** — Yes. It is the justification for a thin item.

## Open Questions

- **How large is the Atlas dataset?** Blocking; see tasks §1. Determines whether the single-partition
  sweep holds or the year-shard escape hatch is needed immediately.
- **What `project` do migrated rows get?** **Resolved: `unknown-migrated`.** 41 of 121 rows carry no
  `project` tag, and 36 of those are `gha-e2e-test*` CI fixtures rather than real project
  measurements, so folding them into `lynx-journal` would pollute its history. The value is
  deliberately *not* the bare `unknown`, because `currentGitCommit()` already falls back to
  `unknown` for a missing commit — two different meanings sharing one string would read identically
  in a results table. The `source` tag on those 36 rows is **kept**, carried through as a custom
  tag (decided 2026-08-19; an earlier draft dropped it). Unknown keys are permitted by design and
  warned about only when no row carries them, 36 rows cost nothing at this scale, and discarding
  provenance is irreversible once Atlas is decommissioned — whereas an unhelpful tag can be
  ignored or removed later.
- **Does the CLI need a memory bound on the sweep?** `--limit` bounds output, not rows read. Probably
  unnecessary at current scale; revisit once the row count is known.
- **What is the S3 fetch command's surface?** Whether it is a subcommand of `baas results`, a sibling of
  `baas env diff`, or its own verb is a CLI-surface question to settle in the spec.
