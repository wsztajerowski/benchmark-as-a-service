## Design Summary

Move the BaaS results store from MongoDB Atlas to a DynamoDB table created by the core stack, keeping
MongoDB alive **only** as an optional adapter inside `benchmark-runner` for standalone use of that JAR.

The store's job is deliberately narrow: **identify, filter and locate runs, plus carry the headline
number for the table view.** Full fidelity stays in S3, which the CLI gains the ability to download
wholesale for a run. Analytical questions — comparison, regression detection, trend, ranking,
comparability scoring — are explicitly *not* served by DynamoDB; when they are wanted, a bridge will
export to an analytics engine.

This scope was set by evidence rather than assumption. Two sources were consulted:

1. **The retired `benchmark_overview.sh`** (recovered from `git show 889731b^:scripts/benchmark_overview.sh`),
   the tool that was actually used day to day.
2. **A 90-question catalogue** of what a benchmark-results viewer could plausibly be asked, supplied as
   discussion input rather than as requirements.

The historical tool always filtered `project` + `type` — a match set that is essentially the whole
dataset — then grouped `(benchmark, branch)` client-side and kept the best score. It has **no**
time-based access pattern, **no** request-id lookup, and **no** arbitrary-tag search. Classifying the
90 questions showed 70 of them are computations over a selected set, not lookups; no index makes
"show the biggest regressions" a query. Both sources therefore point the same way: the access pattern
is *load a coarse working set and compute over it*, not *seek a key*.

That collapses the design to one item per measurement, one partition per project, one GSI, and no
derived index items at all.

## Alternatives Considered

### Alternative A: Single table with hand-written inverted index items (the previous design)
- **Approach**: Result items plus 6–8 derived items per result — a tag index (`TAG#<key>#<value>`), a
  benchmark index partitioned on class, and a month-partitioned time index (`ALL#<yyyy-mm>`) — written
  by the runner in one `BatchWriteItem`.
- **Pros**: Every access pattern is index-backed with no scan; arbitrary tag search is a `Query`;
  projections are under our control.
- **Cons**: The runner owns index consistency and must retry to avoid orphaning a result from its
  index items. Needs a reserved non-indexed tag-key set to avoid write amplification on high-cardinality
  keys such as `options`. `TAG#branch#main` is a single hot partition. Deleting a run means deleting
  derived items. Six to eight writes per result.
- **Why not chosen**: Its sole justification over a GSI design was index-backed arbitrary tag search —
  a requirement the previous `design.md` admits was invented during design ("and — newly — search by
  arbitrary tag"). Neither `benchmark_overview.sh` nor `baas results` has ever had it; both use exactly
  two tag dimensions, `branch` and `project`, hardcoded or enumerated. The month-partitioned time index
  likewise serves a query that has never existed in either tool. The machinery optimises *selective*
  lookups that the real workload does not perform, while the dominant query — read everything, then
  group — cannot be indexed away in any case.

### Alternative B: Base table plus one or two GSIs
- **Approach**: `pk = RUN#<requestId>`, `sk = <benchmark>`, with AWS-maintained GSIs on branch+time and
  benchmark+time. One item per result.
- **Pros**: One write per result; AWS maintains the indexes; no runner-owned consistency; no reserved
  tag-key list.
- **Cons**: The dominant sweep is still not selective, so it degrades to a `Scan` regardless. Each new
  filterable dimension needs another GSI plus a backfill, and the dimension list from the 90 questions
  runs to roughly eighteen — against a hard cap of twenty, each one paying write amplification.
  Conjunctive filters ("Java 21 **and** G1 **and** ARM64") are not served by single-dimension GSIs.
- **Why not chosen**: Pays for index infrastructure that the actual queries bypass. Strictly more moving
  parts than the chosen approach for no query it answers better.

### Alternative C: Working-set partition, client-side evaluation (chosen)
- **Approach**: `pk = RESULT#<project>`, `sk` benchmark-major then chronological. One item per
  measurement, one GSI on `requestId`, no derived items. `baas results` issues one `Query` on the
  project partition and filters, groups and ranks in memory.
- **Pros**: One `PutItem` per measurement. No index consistency to own, no reserved tag-key set, no
  write amplification, no hot tag partition. `sk` ordering makes `(benchmark, branch)` grouping a linear
  pass with no sort. `--benchmark` regex and arbitrary `--tag` filtering both survive as client-side
  predicates, so **both BREAKING changes in the previous proposal disappear**. Adding a filterable
  dimension is a new tag, not a new index.
- **Cons**: Reads the whole project partition on every unfiltered query. Bounded by partition size and
  by how much the CLI is willing to hold in memory.
- **Why not chosen**: It was chosen. Its one real weakness — the working-set assumption — is recorded as
  a blocking assumption and given a documented escape hatch (shard `pk` by year).

### Alternative D: Replace MongoDB outright, including in the runner
- **Approach**: The previous proposal's position — delete `mongodb-driver-sync`, Morphia,
  `DocumentDbService` and the Mongo test infrastructure entirely.
- **Pros**: One store, one code path, smallest surface.
- **Cons**: `benchmark-runner` is usable as a standalone JAR with no CloudFormation stack and no `baas`
  CLI. Removing Mongo forecloses that deployment entirely.
- **Why not chosen**: Superseded by the decision below to keep Mongo as a runner-local write adapter.

## Agreed Approach

**Alternative C**, with MongoDB retained as a runner-local adapter.

Within BaaS, DynamoDB is the only store: the CLI never learns Mongo exists — no `--mongo-uri`, no SSM
parameter, no Mongo in `ResultsQueryService`. Outside BaaS, `benchmark-runner` remains a single JAR that
can be pointed at a user's own MongoDB.

**Table**

```
pk  = RESULT#<project>
sk  = <fqcn>#<method>#<createdAt>#<requestId>     (JMH)
sk  = JCSTRESS#<createdAt>#<requestId>            (JCStress)
GSI = pk: requestId, sk: <fqcn>#<method>
```

`sk` is benchmark-major then chronological, which serves three patterns from one ordering: the latest
result for a benchmark (`begins_with` + descending + `Limit 1`), a benchmark's history in order, and
grouping — rows arrive grouped by benchmark already, so the group-tag bucketing happens within one
benchmark at a time and never needs a global sort.

**Item attributes**

| Group | Contents |
|---|---|
| Identity | fqcn, method, type, mode, createdAt, requestId |
| Measurement | score, scoreError, scoreUnit; `secondaryMetrics` reduced to score + unit per metric. JCStress: totalTests, passedTests, and the three failure maps |
| `tags` (Map) | project, type, commit, jdk, cpuModel, cpuArch, instanceType, imageVersion, options, `exclude_from_results`, plus user-supplied keys — `branch` among them |
| Pointers | resultPath, resultJsonKey, environmentJsonKey |

**Query model.** One `Query` on the project partition, a `FilterExpression` for `exclude_from_results`,
then client-side filtering on tags, grouping and best-score selection. This implements open finding
**D1**, which documents behaviour `baas results` does not currently have. Because `branch` is an
optional user tag (below), the grouping key is `(benchmark, <group-tag>)` with `branch` as the default
rather than a hardcoded field.

## Key Decisions

### MongoDB survives as a runner-local write adapter, invisible to the CLI

`benchmark-runner` is a standalone artifact — one JAR, no stack, no CLI — and that deployment must keep
working against a user's own MongoDB. Inside BaaS, DynamoDB is the only store.

This dissolves the objection that a retained Mongo adapter blocks the sibling `private-runner-network`
change: BaaS never selects Mongo, so `RunnerSecurityGroup` still drops 27017 and no NAT gateway cost
appears. It also avoids a read-side asymmetry — `baas results` never needs to read Mongo, because
anything BaaS produced is in DynamoDB, and a standalone user reads their own store however they like.

**The port speaks domain, not storage.** Each adapter owns its physical layout. One item per measurement
maps cleanly to one document per measurement, so nothing DynamoDB-specific leaks through the interface —
which the previous design's 6–8 derived items would have made impossible.

Consequence: `baas-model` sits on `baas-cli`'s classpath and must therefore stay Mongo-free, and the
Mongo Testcontainers infrastructure survives (the previous `tasks.md` §6.2 deletes it, and is now wrong).

### Tags are the uniform query surface, governed by a known-key vocabulary

Every queryable dimension is a tag — project, type, commit, jdk, cpuModel, cpuArch, instanceType,
imageVersion — rather than a mix of typed fields and tags. Under a client-side evaluation
model this costs nothing: a `Map<String,String>` entry filters exactly as fast as a top-level attribute,
so the field-versus-tag boundary is a schema-discipline question, not a capability one. Only what
composes the partition key must be structurally guaranteed.

This matches the existing invariant that the runner reports the environment it *observed* via `--tag`,
so a result's tags cannot disagree with its own `environment.json`.

Free-form tags fail silently on typos — `--tag jvm=21` returning nothing is indistinguishable from "no
results". The mitigation is a **known-tag-key vocabulary as constants in `baas-model`**, written by the
runner and validated by the CLI, with unknown keys permitted but warned about. This is finding **A3**'s
lesson applied to tags: one definition, shared by both modules.

`cpuModel` and `cpuArch` are kept separate because they answer different questions (specific CPU versus
ARM64-vs-x86), and both are already captured in `environment.json` — this is a copy, not new observation
work.

### `branch` is a custom user tag, not a known key

`branch` is not part of the known-key vocabulary and `baas run` does not populate it. A user who wants
branch-aware results passes `--tag branch=<name>` like any other custom dimension. This keeps the
vocabulary to dimensions the runner can *observe* on the instance (environment) or the CLI can derive
unambiguously (project, commit), rather than mixing in a workflow concept that only some callers have.

**Consequence, recorded because it is easy to miss:** the documented `baas results` behaviour — and
`benchmark_overview.sh` before it — groups by `(benchmark, branch)`. With `branch` optional, grouping
cannot assume it exists. The grouping key therefore becomes `(benchmark, <group-tag>)` defaulting to
`branch`, and rows missing that tag group under a single "untagged" bucket rather than being dropped or
erroring. `--living-branches` likewise filters on the tag when present and is a no-op when absent.

### `project` is derived from the git repo name, overridable with `--project`

It needs no configuration and matches the existing behaviour that `baas run` builds in the current
working directory. **Accepted trade-off:** renaming a repository silently splits history across two
partitions. Judged acceptable at current scale, and recoverable by re-writing keys.

`type` is deliberately **not** part of the partition key. It has four values (`jmh`,
`jmh-with-profiler`, `jmh-with-async`, `jcstress`), and keying on it would split the same benchmark's
comparable JMH-family results across partitions.

### DynamoDB holds the index; S3 holds the fidelity; the CLI can fetch a whole run

The item carries what is needed to list, filter and locate. Everything else — the verbatim JMH result
JSON, `environment.json`, logs, profiling artifacts — stays in S3, and this change adds the CLI
capability to download a run's entire S3 prefix. Shipping a thin item without that fetch would leave a
promise the CLI could not keep.

The verbatim JMH result JSON is newly uploaded to the run's S3 prefix, which also closes the documented
"measurements live only in MongoDB, there is no `result.json`" gap.

### Analytics is out of scope and will be a bridge, not a query

Of the 90 catalogued questions, 20 are selection and 70 are computation over a selected set — pairwise
comparison (15), regression detection (24), trend (7), ranking (10) and comparability reasoning (14).
None of them become cheaper by indexing, and serving them would require roughly eighteen filterable
dimensions. When they are wanted, the answer is an export from DynamoDB plus S3 into an analytics
engine, not a richer key schema.

Three model extensions were considered and **deferred** on that basis:

- **Precomputed statistics** (stddev, CV, min/max, percentiles) — justified only by the analytical
  classes. `scoreError` already conveys spread for a table view, and `rawData` / `scorePercentiles`
  remain in the S3 result JSON.
- **`release` and `PR` provenance** — `PR` is a CI concept and is not sourceable from `baas run` on a
  laptop at all; `release` has no obvious source. **`commit` is kept**, since `git rev-parse HEAD` is
  trivially available.
- **JCStress per-test items** — `JCStressResult` names only *non-passing* tests (`testsWithFailedResults`,
  `testsWithErrorResults`, `testsWithInterestingResults`); passing tests are counted, never named. Per-test
  granularity would therefore cover failures only, and the per-test result files are already in S3.

### Both BREAKING changes from the previous proposal are withdrawn

`--benchmark` keeps regex matching, applied client-side over the loaded partition exactly as MongoDB
applied it server-side. Arbitrary `--tag <key>=<value>` filtering needs no index. `--living-branches`
improves from N queries to one query plus a client-side filter.

The one remaining behaviour change is that discarding results requires an explicit `--no-database`,
replacing today's footgun where an empty connection string silently selects a no-op store and a paid
run reports success with its measurements thrown away.

### Effect on comparability with existing results

Per the project rule that anything touching the runner, image or user-data must state this explicitly:
**this change does not affect comparability.** The runner AMI, kernel tunables, JVM, toolchain and
termination layers are untouched. User-data changes only in *what tags it passes to the runner*, not in
what is measured or how. Existing Atlas history migrates once and keeps its scores verbatim.

### Accepted risks this change retires

Three rows leave CLAUDE.md's *Accepted risks* table: the Atlas `0.0.0.0/0` IP allowlist, MongoDB
connect-only, and the shared `RunnerRole` SSM Mongo path. None is reopened as a problem — each simply
ceases to exist within BaaS. MongoDB connect-only survives in reduced form for the standalone runner:
BaaS still never provisions a cluster.

## Open Questions

### Blocking — must be resolved before implementation (tasks §1)

- **`baas run --tag` never reaches the store.** `UserDataScriptBuilder:133–139` invokes the runner with
  exactly two hardcoded tags, `imageVersion` and `instanceType`. `RunCommand:231` builds a tag map, but
  per its own comment at lines 221–229 those are EC2 *instance* tags and never reach
  `benchmarkMetadata.tags`. Today, on the `baas run` path, there is no `branch`, no `project`, no `type`
  and no user tag on any stored result — `benchmark_overview.sh` could query `project` and `branch` only
  because those came from the GHA path. Threading `extraTags` through `UserDataScriptBuilder`, and
  populating `project`, `type` and `commit`, is a prerequisite for the entire tag-based query model.
  `branch` rides the same `--tag` path as any user tag, so it needs no special handling — but it does
  need the threading fix, without which `--tag branch=…` still goes nowhere.
- **How large is the Atlas dataset?** The working-set sweep is the load-bearing assumption. At roughly
  1 KB per item: 10k items ≈ 10 MB, sub-second, a fraction of a cent; 100k ≈ 100 MB and noticeably slow;
  past ~1M it fails. Count the rows before committing. Documented escape hatch: shard `pk` by year
  (`RESULT#<project>#<yyyy>`), costing one query per year spanned.

### Non-blocking

- **What `project` do migrated rows get?** Historical Atlas rows carry `project=lynx-journal` from the
  GHA path and can key off it directly, but any row lacking the tag needs a default decided during
  migration.
- **Does the CLI need a memory bound on the sweep?** A `--limit` bounds *output*, not the rows read.
  Probably unnecessary at current scale; revisit if the row count comes back large.
- **What does the S3 fetch command look like?** Whether it is a subcommand of `baas results`, a sibling
  of `baas env diff`, or its own verb is a CLI-surface question for the proposal, not a model question.
