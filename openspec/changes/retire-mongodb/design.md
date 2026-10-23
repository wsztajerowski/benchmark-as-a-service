# Design

## Context

See proposal.md — *Why*. The state that shapes the approach:

- Five runner ITs extend `TestcontainersWithS3AndMongoBaseIT` and assert on a Mongo document:
  `JmhSubcommandServiceIT`, `JmhWithProfilerSubcommandServiceIT`,
  `JmhWithAsyncProfilerSubcommandServiceIT`, `JCStressSubcommandServiceIT`, and
  `MongoResultsStoreContractIT`. `TestcontainersWithDynamoDbBaseIT` (LocalStack, a table created per
  test, `scanTestTable()`) already exists and `JmhStoreIntegrationIT` and
  `DynamoDbResultsStoreContractIT` use it.
- The store contract suite (`ResultsStoreContractTest`) is abstract and has two bindings, one per
  adapter; with one adapter it keeps one.
- `ResultsStoreBuilder` enforces exactly-one-of `--results-table` / `--mongo-connection-string`.
  `StorageService.of` returns `LocalStorageService` when no bucket is named, and `--s3-bucket` sits
  in a non-exclusive picocli `ArgGroup` that is optional as a group.
- `JmhRunResults.uploadJsonAndMap` uploads `jmh-result.json` and maps the results; the three JMH
  services call it. JCStress already uploads in its service.
- `RunnerArgs.SET_BY_THE_CLI` lists both Mongo spellings, so `baas run` refuses them after `--` with
  `insteadOf`'s "A JCStress mode is --mode, not -m".
- Comparability: nothing here reaches a stored measurement or the instance. User-data, the options
  the CLI passes, the AMI and the item shape are unchanged, so results before and after compare as
  before.

## Goals / Non-Goals

**Goals:**
- The DynamoDB adapter is the store under every service IT before any Mongo code is deleted, so the
  deletion step cannot remove coverage.
- The runner's option surface has no way to run without a bucket and a table.

**Non-Goals:**
- Renaming `pl.wsztajerowski.entities` or restructuring the parse classes beyond removing Morphia.
- T11's repeated LocalStack pins: all five are in `baas-cli`'s ITs, which this change does not touch.
- T12 (Docker-less `mvn verify` skips every IT) — unchanged; the ITs that move stay
  `disabledWithoutDocker`.
- Migrating measurements out of any existing MongoDB.

## Decisions

### The ITs move first, in their own commit, and assert through the model's mapper

Each Mongo-backed service IT switches to `TestcontainersWithDynamoDbBaseIT`, passes
`new DynamoDbResultsStore(dynamoDbClient, TEST_TABLE_NAME)`, and asserts on
`MeasurementItemMapper.fromItem(scanTestTable().get(…))` — the same field it read from the Mongo
document (`project`, `benchmarkMethod`, the JCStress counts). Reading through the model's mapper
rather than raw attribute names keeps the test from hand-encoding the item, which CLAUDE.md forbids
outside `baas-model`. Only after that commit is green does the deletion begin.

*Rejected:* deleting the ITs with the adapter and relying on `JmhStoreIntegrationIT` — it covers JMH
without profilers only, so the profiler and JCStress paths would lose their only end-to-end test.

### `--results-table` and `--s3-bucket` become `required = true` options, and the bucket group dissolves

`--results-table` gets `required = true`. `--s3-bucket` leaves its `ArgGroup` and becomes a plain
required option beside `--s3-service-endpoint`; the group existed only to make "no bucket" a valid
state. picocli then rejects a missing option before `call()`, naming it, which satisfies both
fail-fast scenarios without code of ours. `ResultsStoreBuilder` keeps its blank-table check as the
programmatic guard (it is also built from tests), but loses the exactly-one-of branch.
`StorageService.of` always builds `S3StorageService`.

*Rejected:* keeping the builders' runtime checks as the only enforcement — picocli's message names
the option and costs nothing, and it is the same layer that already rejects `--no-database`.

### `-m` is refused by a check of its own in `RunCommand`, not by `SET_BY_THE_CLI`

Both Mongo constants leave `RunnerArgs`, and `SET_BY_THE_CLI` shrinks to options the runner really
has. `-m` is no longer a runner option, so the existing refusal's wording ("names a runner option
baas run sets itself") would be false for it. `RunCommand` gains a separate check next to the
existing one: a `-m` among the benchmark's arguments is refused with "-m is not a runner option. A
JCStress mode is --mode." and exit 2, before any AWS call. `--mongo-connection-string` is not
refused: it fails on the instance as any unknown option does, and nothing types it.

*Rejected:* keeping `-m` in `SET_BY_THE_CLI` — it would keep a model constant the runner no longer
declares, and `RunnerArgs` documents options the two sides share.

### The three JMH services upload `jmh-result.json` themselves; `JmhRunResults` only maps

`JmhRunResults.uploadJsonAndMap` becomes `JmhRunResults.map(commonOptions, machineReadableOutput,
profilerOutputPath)`. Each JMH service calls `storageService.saveFile(resultJsonKey, …)` immediately
before it, keeping upload-before-store; the key still comes from `JobLayout.RESULT_JSON_NAME`. The
mapper loses its `StorageService` parameter, which is what B11 asked for.

*Rejected:* a shared helper that uploads then maps — that is the function being split.

### `ban-mongodb` stays in `baas-model`

The requirement it backs (the shared module stays storage-neutral) is unchanged, and with no Mongo
in the reactor the rule costs nothing. Deleting it would remove the guard while the requirement it
enforces still stands.

## Risks / Trade-offs

- [A standalone runner user exists after all] → The runner fails at parse time naming the unknown
  option, before any benchmark runs; the migration note in the spec delta says what to pass instead.
  No data is lost: the option never wrote anywhere BaaS reads.
- [A local run without a bucket now fails] → `jmh-with-async.sh` and `jmh-with-profiler.sh` already
  pass `--s3-bucket` against LocalStack; the README's local-run section says the bucket is created by
  hand (`docker-compose` has no init container).
- [Moved ITs pass trivially because the table is empty] → each asserts a specific stored field, not
  just a count, as the Mongo versions did.

## Migration Plan

Nothing deploys: no template, IAM, user-data or AMI change, so no setup re-run and no image build.
The runner JAR changes at the next release and is pinned to the CLI that launches it, so a deployment
sees the new runner only through a new CLI. Rollback is a revert of the merge.
