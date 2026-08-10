## Why

Benchmark measurements live in a MongoDB Atlas cluster that BaaS does not provision, cannot tear down,
and reaches over the public internet on port 27017 with an `0.0.0.0/0` access list. The result is a
second system to operate outside CloudFormation, a standing external dependency in the middle of an
otherwise self-contained tool, and a network requirement that makes private-subnet runners impossible.

Moving the store into a DynamoDB table created by the BaaS stack removes the external dependency, brings
the results store under the same lifecycle as every other BaaS resource, and — because DynamoDB has a
free gateway endpoint — removes the last reason a runner needs public internet access for its data path.

## What Changes

- New DynamoDB table `baas-<prefix>-results` in `cf-template-core.yaml`: on-demand billing, `pk`/`sk`
  string keys, **no GSIs**, `DeletionPolicy: Retain` + `UpdateReplacePolicy: Retain` mirroring
  `S3MainBucket`, no TTL.
- Single-table design with **inverted index items** written by the runner alongside each result: a tag
  index (`TAG#<key>#<value>`), a benchmark index partitioned on class with the method in the sort key,
  and a month-partitioned time index. Because `branch` and `project` are already tags, index-backed tag
  search subsumes both — and arbitrary tag search becomes possible, which no GSI design can offer.
- Result items hold only the queryable summary. The **verbatim JMH result JSON is uploaded to the run's
  S3 prefix** and referenced by key; `rawData` (the only field that can approach DynamoDB's 400 KB item
  limit) and `scorePercentiles` live only there. This also closes the long-documented "measurements live
  only in MongoDB, there is no `result.json`" gap.
- New reactor module `baas-model` holding the stored item shapes, key encoding, attribute-name
  constants, and the `Map<String,AttributeValue>` mapper, depended on by both `benchmark-runner` and
  `baas-cli`. Today both read the same documents through independently duplicated raw string paths
  (finding A3), so a key-encoding change silently returns zero rows instead of failing to compile.
- `DatabaseService` becomes `ResultsStore` with a single `put(ResultItem)` backed by the low-level
  `DynamoDbClient`. **`upsert` is deleted** — dead code with zero callers. All Morphia annotations and
  the `mapPackage("pl.wsztajerowski.entities")` constraint disappear.
- `ResultsQueryService` is rewritten against the table. Open finding **D1 is implemented**:
  `exclude_from_results` is filtered server-side, and grouping by `(benchmark, branch)` with
  highest-score selection is applied client-side, since DynamoDB has no aggregation.
- **BREAKING**: `--benchmark` becomes exact-or-prefix matching. DynamoDB cannot regex a key.
- **BREAKING**: `--mongo-uri` is removed from `baas admin setup` and `baas config set`;
  `/<prefix>/mongo/connection-string` is deleted; `validateMongoUri` (all three copies, finding A5) is
  deleted. The table name is a stack output carried in `config.yaml` and user-data — it is not a secret,
  so the "never put the connection string in user-data" invariant no longer applies.
- **BREAKING**: discarding results requires an explicit `--no-database` flag. A missing table name is a
  hard failure, removing the documented footgun where an unset URI made a run report success while
  throwing the measurements away.
- Networking, in this change: a free **DynamoDB gateway endpoint** so database traffic never leaves the
  VPC, and **27017 egress removed** from `RunnerSecurityGroup`.
- `SetupCommand`'s retained-resource pre-check gains a twin for the table — the documented trap where a
  retained resource blocks the next setup with a CloudFormation error that never names the culprit.
- Ordering fix: S3 JSON is written before the DynamoDB item, and a failed store write exits non-zero so
  `run-status` reports failure and the artifacts survive for re-import. Partially addresses finding A4.
- Local development collapses to one container: `docker-compose` loses `mongo` and `mongo-express`,
  LocalStack gains `dynamodb`.
- `scripts/migrate-atlas-to-dynamodb` migrates existing history once, idempotently with a dry-run, then
  is deleted — keeping `mongodb-driver-sync` out of the shipped CLI permanently.

## Capabilities

### New Capabilities
- `results-store-schema`: the table, item shapes, key encoding, index-item derivation, and write path.
- `benchmark-results-query`: the query patterns `baas results` supports, including tag search,
  exclusion filtering, grouping, and best-score selection.

### Modified Capabilities
- `core-stack-provisioning`: adds the results table and DynamoDB gateway endpoint, removes 27017 egress
  and every Mongo SSM grant, and re-scopes runner/operator/deployer permissions onto the table.
- `cli-command-structure`: removes `--mongo-uri`, adds `--no-database` and the new `baas results`
  filters, and changes `--benchmark` matching semantics.

## Impact

- **Infra**: `infra/cf-template-core.yaml`, `infra/cf-template-ci.yaml`, `infra/deployer-policy.json`,
  `infra/operator-policy.json`, `infra/README.md`.
- **New module**: `baas-model` (added to the root `pom.xml` reactor).
- **Code, benchmark-runner**: `infra/DatabaseService.java`, `DatabaseServiceBuilder.java`,
  `DocumentDbService.java`, `NoOpDatabaseService.java` replaced; the four `*SubcommandService` classes;
  `entities/jmh/*`, `entities/jcstress/*` lose Morphia annotations; `commands/TestWrapper.java`.
- **Code, baas-cli**: `results/ResultsQueryService.java`, `results/ResultRow.java`,
  `commands/ResultsCommand.java`, `commands/RunCommand.java`, `commands/admin/SetupCommand.java`,
  `commands/admin/TeardownCommand.java`, `commands/ConfigSetSubcommand.java`,
  `commands/ConfigSyncSubcommand.java`, `config/BaasConfig.java`, `infra/UserDataScriptBuilder.java`.
- **Dependencies**: `mongodb-driver-sync` and Morphia removed from both modules; `dynamodb` SDK added.
- **Tests**: `TestcontainersWithS3AndMongoBaseIT` and `MongoDbTestHelpers` deleted; `DatabaseServiceIT`
  rewritten against LocalStack; new key-encoding, mapper round-trip, and query integration tests.
- **Scripts**: `jmh-with-profiler.sh`, `jmh-with-async.sh` swap `--mongo-connection-string`; new
  throwaway `scripts/migrate-atlas-to-dynamodb`.
- **Docs**: `CLAUDE.md` (many entries), `README.md`, `infra/README.md`, `docs/adr/`, `docs/diagrams/`,
  `docs/review/` (A3, A4, A5, D1). Three rows leave *Accepted risks*: the Atlas IP allowlist, MongoDB
  connect-only, and the shared `RunnerRole` SSM Mongo path.
- **Obsoletes**: `openspec/changes/atlas-service-account-credentials` (empty spec directories) should be
  removed.
- **No changes** to the runner AMI, the termination layers, or the public-subnet networking model.
