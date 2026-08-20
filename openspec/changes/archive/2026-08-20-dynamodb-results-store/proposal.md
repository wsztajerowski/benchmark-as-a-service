## Why

Benchmark measurements live in a MongoDB Atlas cluster that BaaS does not provision, cannot tear down,
and reaches over the public internet on port 27017 behind an `0.0.0.0/0` access list. That is a second
system to operate outside CloudFormation, a standing external dependency inside an otherwise
self-contained tool, and the last reason a runner needs a public data path — which blocks the sibling
private-subnet change.

A DynamoDB table created by the core stack removes all three: the store shares the lifecycle of every
other BaaS resource, and DynamoDB's gateway endpoint carries no hourly charge.

## What Changes

- New DynamoDB table `baas-<prefix>-results` in `cf-template-core.yaml`: on-demand billing, `pk`/`sk`
  string keys, one GSI on `requestId`, no TTL, `DeletionPolicy: Retain` +
  `UpdateReplacePolicy: Retain` mirroring `S3MainBucket`.
- **One item per measurement**, at `pk = RESULT#<project>` and a benchmark-major, chronological sort
  key. No derived index items, no inverted indexes, no month-partitioned time index. `project` is
  derived from the git repo name, overridable with `--project`.
- **Tags become the uniform query surface.** `commit`, `jdk`, `cpuModel` and `cpuArch` join the
  existing `project`, `type`, `instanceType`, `imageVersion`, `options` and `exclude_from_results`.
  A known-tag-key vocabulary in `baas-model` gives both modules one definition, so a typo warns instead
  of silently returning nothing. `branch` is a **custom user tag**, not a known key.
- **Prerequisite fix: `baas run --tag` never reaches the store.** `UserDataScriptBuilder` hardcodes
  exactly two runner tags, and `RunCommand`'s tag map is applied to the EC2 *instance*, which its own
  comment warns is not the same thing. No result from the `baas run` path currently carries `project`,
  `type` or any user tag. `extraTags` must be threaded through user-data before any of the query model
  works.
- New reactor module `baas-model` holding the item shape, key encoding, tag vocabulary and the
  `Map<String,AttributeValue>` mapper, depended on by `benchmark-runner` and `baas-cli`. Today both
  address the same documents through independently duplicated raw string paths (finding **A3**), so a
  key-encoding change returns zero rows instead of failing to compile.
- `DatabaseService` becomes a `ResultsStore` port with a single write operation. **`upsert` is
  deleted** — dead code with zero callers, and its field-path shape would leak Mongo semantics.
- **MongoDB survives as a runner-local adapter.** `benchmark-runner` is usable as a standalone JAR with
  no stack and no CLI, and that deployment keeps working against a user's own MongoDB. BaaS itself is
  DynamoDB-only: the CLI never learns Mongo exists.
- `ResultsQueryService` is rewritten against the table. Open finding **D1 is implemented**:
  `exclude_from_results` filtered server-side, grouping and best-score selection client-side. The
  grouping key is `(benchmark, <group-tag>)` defaulting to `branch`, since `branch` is now optional.
- `--benchmark-name` **keeps** regex matching and `--tag <key>=<value>` works for any key, both as
  client-side predicates over the loaded partition. `--living-branches` drops from N queries to one.
- New CLI capability: **download a run's entire S3 prefix** — result JSON, `environment.json`, logs and
  profiling artifacts. This is what makes a thin DynamoDB item honest.
- **BREAKING**: `--mongo-uri` is removed from `baas admin setup` and `baas config set`;
  `/<prefix>/mongo/connection-string` is deleted; `validateMongoUri` (all three copies, finding **A5**)
  is deleted. The table name is a stack output carried in `config.yaml` and user-data — it is not a
  secret, so the "mongo URI never goes into user-data" invariant is retired rather than violated.
- **BREAKING**: discarding results requires an explicit `--no-database`. A missing table name is a hard
  failure, removing the footgun where an unset URI made a paid run report success while throwing the
  measurements away.
- Networking: a free **DynamoDB gateway endpoint**, and **27017 egress removed** from
  `RunnerSecurityGroup`.
- `SetupCommand`'s retained-resource pre-check gains a twin for the table.
- Ordering fix: S3 artifacts are written before the DynamoDB item, and a failed store write exits
  non-zero so `run-status` reports failure and the artifacts survive for re-import. Partially addresses
  finding **A4**.
- `docker-compose` loses `mongo-express`; LocalStack gains `dynamodb`. The Mongo container stays, since
  the runner's Mongo adapter still needs an integration test.
- `scripts/migrate-atlas-to-dynamodb` migrates existing history once, idempotently with a dry-run, then
  is deleted — keeping `mongodb-driver-sync` out of the shipped **CLI** permanently.

**Explicitly deferred**, with rationale in `brainstorm.md`: precomputed statistics, `release`/`PR`
provenance, JCStress per-test items, and all analytical querying (comparison, regression detection,
trend, ranking, comparability scoring). When those are wanted the answer is an export bridge to an
analytics engine, not a richer key schema.

## Capabilities

### New Capabilities
- `results-store-schema`: the table, the item shape, key encoding, the tag vocabulary, and the write
  path including adapter selection.
- `benchmark-results-query`: the query patterns `baas results` supports, plus exclusion filtering,
  grouping, best-score selection, and whole-run artifact retrieval.

### Modified Capabilities
- `core-stack-provisioning`: adds the results table and DynamoDB gateway endpoint, removes 27017 egress
  and every Mongo SSM grant, and re-scopes runner/operator/deployer permissions onto the table.
- `cli-command-structure`: removes `--mongo-uri`, adds `--no-database`, `--project`, the run-artifact
  download, and the `baas results` filters; fixes the `--tag` pass-through so user tags reach the store.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`,
  `infra/operator-policy.json`, `infra/README.md`.
- **New module**: `baas-model` (added to the root `pom.xml` reactor, ahead of both consumers).
- **Code, benchmark-runner**: `infra/DatabaseService.java`, `DatabaseServiceBuilder.java`,
  `DocumentDbService.java`, `NoOpDatabaseService.java`; the four `*SubcommandService` classes;
  `entities/jmh/*`, `entities/jcstress/*`; `commands/TestWrapper.java`.
- **Code, baas-cli**: `results/ResultsQueryService.java`, `results/ResultRow.java`,
  `commands/ResultsCommand.java`, `commands/RunCommand.java`, `commands/admin/SetupCommand.java`,
  `commands/admin/TeardownCommand.java`, `commands/ConfigSetSubcommand.java`,
  `commands/ConfigSyncSubcommand.java`, `config/BaasConfig.java`, `infra/UserDataScriptBuilder.java`.
- **Dependencies**: `dynamodb` SDK added to both modules; `mongodb-driver-sync` and Morphia removed from
  `baas-cli` and **retained in `benchmark-runner`** for the standalone adapter.
- **Tests**: LocalStack base class gains `dynamodb`; the Mongo Testcontainers base class and
  `MongoDbTestHelpers` **survive** to cover the retained adapter; new key-encoding, mapper round-trip,
  tag-vocabulary and query integration tests.
- **Scripts**: `jmh-with-profiler.sh`, `jmh-with-async.sh` gain a table name or `--no-database`; new
  throwaway `scripts/migrate-atlas-to-dynamodb`.
- **Docs**: `CLAUDE.md`, `README.md`, `infra/README.md`, `docs/adr/`, `docs/diagrams/`, `docs/review/`.
- **Closes review findings**: **A3** (no shared stored-shape contract), **A5** (`validateMongoUri`
  duplicated three times), **D1** (`baas results` documents filtering and grouping it does not have).
  Partially addresses **A4** (write ordering).
- **Accepted risks retired**: three rows leave CLAUDE.md — the Atlas IP allowlist and the shared
  `RunnerRole` SSM Mongo path disappear entirely; "MongoDB connect-only" survives in reduced form, since
  BaaS no longer uses Mongo at all but the standalone runner may.
- **Obsoletes**: `openspec/changes/atlas-service-account-credentials` (empty spec directories) should be
  removed.

**Cost**: DynamoDB on-demand billing with no provisioned capacity, and a **gateway** endpoint rather
than an interface endpoint — both are zero standing cost. Storage for the migrated history is a few MB,
well inside the free tier. Per-run writes are a handful of items, a fraction of a cent. Reads are one
partition sweep per `baas results` invocation. **This change adds no standing cost**, and removes the
Atlas cluster as an external system. The project's only standing cost remains the ~$0.20/month runner
AMI snapshot.

**Deliberately NOT changed**: the runner AMI, the toolchain, kernel tunables, the three termination
layers, the public-subnet networking model, and the S3 result layout. User-data changes only in *which
tags it passes to the runner* — not in what is measured or how — so **comparability with existing
results is unaffected**. The `/app` working-directory invariant, the request-ID-scoped upload paths, the
`baas-role` tag key and the `Locale.ROOT` formatting rules all stand.
