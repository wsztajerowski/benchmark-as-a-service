## Context

Measurements currently live in a MongoDB Atlas cluster that BaaS connects to but never provisions. Two
modules reach it independently: `benchmark-runner` writes through Morphia entities, and `baas-cli` reads
through raw `org.bson.Document` field paths (`"_id.requestId"`, `"benchmarkMetadata.tags.branch"`). The
two have no shared contract, so a schema change breaks reads silently rather than at compile time
(finding A3).

The store is also the last reason a runner needs public internet on its data path: Atlas serves clients on
27017 over the public internet, behind an `0.0.0.0/0` access list because runners get a fresh public IP
per run. DynamoDB has a free gateway endpoint, so moving the store removes both the external dependency
and the network constraint.

Existing history matters and will be migrated once. Query patterns required: one run's results, a branch's
results newest-first, a benchmark's results over time, and — newly — search by arbitrary tag.

## Goals / Non-Goals

**Goals:**

- The results store is created, tagged, and retained by the same CloudFormation stack as every other BaaS
  resource.
- All four query patterns are index-backed, with no table scan on the normal path.
- One definition of the stored shape, shared by both modules.
- Items stay far below DynamoDB's 400 KB limit while full-fidelity results remain retrievable.
- Existing Atlas history is carried over without loss.
- No standing cost beyond per-request billing.

**Non-Goals:**

- Preserving the Mongo document shape. The stored shape is redesigned around the access patterns.
- Regular-expression benchmark matching. It cannot be index-backed.
- Cross-region replication, point-in-time recovery, or a TTL. The dataset is small and its value is
  historical; none of these earn their complexity yet.
- Changing the AMI, the termination layers, or the subnet model. Those belong to the sibling changes.
- A general-purpose query API. `baas results` is the only consumer.

## Decisions

### Single table with inverted index items, no GSIs

One table holds result items plus derived index items:

| Item | pk | sk |
|---|---|---|
| JMH result | `RUN#<requestId>` | `BENCH#<fqcn>#<method>#<type>` |
| JCStress result | `RUN#<requestId>` | `JCSTRESS` |
| tag index | `TAG#<key>#<value>` | `<createdAt>#<requestId>#<name>` |
| benchmark index | `BENCH#<fqcn>` | `<method>#<createdAt>#<requestId>` |
| time index | `ALL#<yyyy-mm>` | `<createdAt>#<requestId>#<name>` |

`branch` and `project` are already tags, so an index-backed tag search subsumes both — and arbitrary tag
search comes free, which is the deciding factor.

*Alternatives considered.* A single table with `BRANCH` and `BENCH` GSIs is less runner code and AWS
maintains the indexes, but arbitrary tag search is then permanently unreachable — it degrades to
`Scan` + filter — and each new indexed dimension needs a GSI plus a backfill. Two tables mirroring the
existing collections is the smallest conceptual leap but doubles the infrastructure and index work while
still leaving tag search unsolved.

The cost objection to writing index items by hand does not hold: a GSI replicates writes automatically, so
manual index items cost the same order of write units with a projection under our control. At six to eight
tiny items per result on on-demand billing, this is a fraction of a cent per run.

The accepted cost is that the runner owns index consistency, and deleting a run means deleting its derived
items. Results are write-once and every key is deterministic, so this stays bounded.

### The benchmark index is partitioned on the class, not the full method name

`pk = BENCH#<fqcn>` with the method in the sort key answers two questions from one partition: all methods
of a class is a plain `Query`, and one method over time is `begins_with(sk, "<method>#")`. A flat
`BENCH#<fqcn>.<method>` partition would answer only the second, and "how did this whole benchmark class
move" would need one query per method.

### The time index is month-partitioned

`ALL#<yyyy-mm>` bounds partition size while still serving "recent N" without a scan. A single `ALL`
partition would grow unboundedly and concentrate every write on one key; a day-partitioned index would
force many queries to satisfy a modest limit.

### Summary in DynamoDB, verbatim JMH JSON in S3

`Metric.rawData` is `List<List<Double>>` — per-fork, per-iteration samples — and is the one field that can
approach the 400 KB item limit. Nothing in the codebase reads it back. So the item holds the queryable
summary and references `resultJsonKey`; `rawData` and `scorePercentiles` live only in the verbatim JMH JSON
now uploaded to the run's S3 prefix, and `secondaryMetrics` is reduced to score and unit per metric.

This also closes the documented "measurements live only in MongoDB, there is no `result.json`" gap as a
side effect.

*Alternatives considered.* Keeping the full structure minus `rawData` stays closest to today's shape but
still risks large items on wide benchmarks. A gzipped blob attribute is compact but opaque to PartiQL, the
console, and any future query. Spilling to S3 only above a size threshold preserves full fidelity but
creates two code paths where the rarely-exercised one is the untested one.

### Low-level client with an explicit mapper

The Enhanced Client's model is one annotated class per table, which fights a single-table design holding
heterogeneous item types — each type needs its own `TableSchema` and manual key composition regardless —
and Java records need hand-written builders for `@DynamoDbImmutable` anyway. A ~150-line explicit
`Map<String, AttributeValue>` mapper gives exact control over key encoding and doubles as the schema
contract.

*Alternatives considered.* Enhanced Client with `@DynamoDbImmutable` was rejected because most of its
convenience is lost in a single-table layout. Enhanced Client with mutable beans would additionally abandon
the immutable-record convention used consistently across the entities package.

### A new shared module owns the stored shape

`baas-model` holds the item shapes, key encoding, attribute-name constants, and the mapper, and both
`benchmark-runner` and `baas-cli` depend on it. This is the structural fix for finding A3: an incompatible
key-encoding change now breaks compilation instead of returning zero rows.

`JmhResult` and `Metric` stay in `benchmark-runner` as *parsing* types for JMH's JSON output and map into
the stored shape — parsing an external format and defining our storage format are different jobs and
should not share a class.

*Alternatives considered.* Having `baas-cli` depend on `benchmark-runner` avoids a new module but couples
the laptop-side tool to the EC2-side tool's full dependency tree and blurs a module boundary the project
draws deliberately. Keeping duplicated constants leaves A3 open.

### Timestamps become fixed-width UTC ISO-8601 strings

`createdAt` is a `LocalDateTime` today, produced from `OffsetDateTime.now(ZoneOffset.UTC)` in one place and
left implicit in another. Sort keys must sort chronologically as *strings*, so the stored form is a
fixed-width UTC ISO-8601 instant. Variable-width or zone-ambiguous formatting would silently misorder
results — the failure would look like missing data rather than a formatting bug.

### `upsert` is deleted

The `DatabaseService.upsert` API — field-path filters and `set` operators — is called by none of the four
subcommand services. Porting a Mongo-shaped update-operator surface to DynamoDB for zero callers is pure
cost, and its field-path shape would leak Mongo semantics into a store that does not share them.

### Negation is not indexable, so exclusion is a filter expression

`exclude_from_results=true` must be applied *negatively*, and no index answers "not tagged X". So
`excludeFromResults` is projected onto index items and applied via `FilterExpression` server-side. Worth
recording explicitly because it is the kind of constraint that gets discovered late and then papered over
with a scan.

### D1's grouping is client-side

The exclusion filter is server-side; grouping by `(benchmark, branch)` and keeping the highest score are
applied client-side over the returned rows, because DynamoDB performs no aggregation. At the row counts
`baas results` deals with, pulling a bounded query result and reducing it locally is the correct trade
rather than a limitation worked around.

### Discarding results requires `--no-database`

Today an empty connection string selects a no-op store, so a run reports success while the paid
measurements are thrown away. Absence of configuration becomes a hard failure and discarding requires
naming the intent.

### The table is retained, and setup pre-checks for it

`DeletionPolicy: Retain` mirrors `S3MainBucket` — the results table is more the benchmark history than the
bucket is. This inherits the bucket's known trap, where a retained resource blocks the next setup with a
CloudFormation error that never names the culprit, so `SetupCommand`'s existing pre-check gains a twin.

### Migration is a throwaway script

`scripts/migrate-atlas-to-dynamodb` reads Atlas and writes result plus index items, idempotently and with
a dry-run, then is deleted. This keeps `mongodb-driver-sync` out of the shipped CLI permanently.
Acknowledged: nothing in CI exercises `scripts/`, so the script is protected only by its dry-run and by
manual verification against row counts.

## Risks / Trade-offs

- **Item size is bounded by convention, not by construction** → `tags` and the reduced `secondaryMetrics`
  are still unbounded in principle. Add an explicit size assertion in the mapper's tests and fail the
  write loudly rather than truncating.
- **High-cardinality tags cause useless write amplification** → A tag such as `options=<params>`, unique
  per run, produces one index item per run in its own partition and can never be usefully queried. The
  reserved tag-key set must exclude such keys from indexing; getting this list wrong costs writes, not
  correctness.
- **A very active branch concentrates writes on one partition key** → `TAG#branch#main` is a single
  partition. Benchmark writes are small and infrequent relative to DynamoDB's per-partition limits, and
  on-demand adaptive capacity absorbs the rest, but this is the first place to look if throttling ever
  appears.
- **The runner owns index consistency** → A partial batch failure could leave a result without its index
  items. Retry with backoff, and treat ultimate failure as run failure so the discrepancy is visible
  rather than silent. Deterministic keys make a re-import idempotent.
- **Deleting a run requires deleting derived items** → No delete path exists today and none is added.
  Documented as a constraint rather than solved speculatively.
- **Regular-expression benchmark matching is lost** → Real capability reduction. Mitigated by exposing
  `--tag`, which covers the "several related benchmarks" case better than a name pattern did, and by
  failing with a message that names the supported forms rather than silently matching nothing.
- **Timestamp format changes are silent if wrong** → Assert lexicographic ordering equals chronological
  ordering in a unit test over a set of instants spanning month and year boundaries.
- **Migration fidelity** → The Mongo `_id` is composite for JMH and a bare `requestId` for JCStress. The
  script must derive both key forms and reproduce the index items a live write would have produced.
  Verify by comparing row counts and spot-checking scores before decommissioning Atlas.
- **Two breaking CLI changes land together** → `--mongo-uri` removal and the `--benchmark` semantics change
  both break scripted use. Both are called out in the proposal's migration notes; at current scale the
  affected surface is one operator.

## Migration Plan

1. Ship the table, gateway endpoint, and IAM changes; deploy with `baas admin setup`. Atlas is untouched.
2. Land `baas-model`, the store rewrite, and the query rewrite behind the new table, verified against
   LocalStack.
3. Run `scripts/migrate-atlas-to-dynamodb --dry-run`, review the reported counts, then run it for real.
4. Verify: row counts match, `baas results --branch main` agrees with historical output modulo the
   documented `tags.project` filter difference, and spot-checked scores are identical.
5. Cut the runner over to DynamoDB and confirm a live run writes result and index items.
6. Remove the Mongo dependency, the SSM parameter, `--mongo-uri`, and the Mongo test infrastructure.
7. Decommission the Atlas cluster and delete the SSM parameter by hand.
8. Delete the migration script.

**Rollback:** until step 6, the Mongo code path is still present and the Atlas cluster still holds the
data, so reverting the runner and CLI restores the previous behaviour. After step 6, rollback means
restoring those commits; the DynamoDB table is retained regardless, so no measurement is lost either way.

## Open Questions

- Which tag keys belong in the reserved, non-indexed set? `options` is the clear first entry given its
  per-run cardinality. Decide from the tag keys actually present in the Atlas data during migration.
- Should any `Scan` capability be retained for ad-hoc investigation, or should the absence of an index be a
  hard "not supported"? Leaning toward no scan on the normal path, with unfiltered queries served by the
  time index.
- Does `baas results` need a `--since` bound? The time index makes it cheap, but no requirement asks for it
  yet.
