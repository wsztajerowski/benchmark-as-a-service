# Proposal

## Why

`benchmark-runner` still carries a complete MongoDB results store, and a local-disk storage mode, so
that its JAR could be used standalone. Neither has a live user: the one standalone consumer the
accepted-risk row named sits on a branch frozen in 2024 that cannot run today's runner. Meanwhile
every service integration test stores through the Mongo adapter, so the DynamoDB path — the only one
BaaS uses — is exercised by the store contract suite and by no end-to-end runner test (C4). Retiring
both makes the production path the tested path and deletes a driver, an object mapper and their
upkeep.

## What Changes

- **Service ITs move onto LocalStack DynamoDB first**, before anything is deleted, so each of them
  stores through `DynamoDbResultsStore` and asserts on the stored item.
- **The MongoDB store is removed from the runner**: `MongoResultsStore`, `MongoMeasurementDocument`,
  the Mongo branch of `ResultsStoreBuilder`, and `--mongo-connection-string`/`-m` with its
  `MONGO_CONNECTION_STRING` default. `--results-table` becomes the runner's only store option and is
  required.
- **Local storage mode is removed**: `LocalStorageService` and the no-bucket branch of
  `StorageService.of`. `--s3-bucket` becomes required, so a runner started without one fails before
  running anything instead of writing artifacts to disk. That also removes A13 (the trace-log
  `readString` that fails on a binary artifact) rather than fixing it.
- **Dependencies leave the build**: `mongodb-driver-sync`, `morphia-core`, `testcontainers-mongodb`,
  the `mongo` service in `docker-compose.yaml`, and Dependabot's ignore block for both. `baas-model`'s
  `ban-mongodb` enforcer rule stays: it is what holds the shared module's storage-neutrality
  requirement, which this change does not touch.
- **B11 is closed**: the parse classes (`JmhResult`, `Metric`, `JCStressResult`) lose their Morphia
  annotations, and the three JMH services upload `jmh-result.json` themselves before mapping, so
  `JmhRunResults` maps and does nothing else. Upload-before-store order is unchanged.
- **`-m` stays unassigned.** It no longer means anything to the runner, and it does not become a
  short form of JCStress `--mode`. `baas run` keeps refusing `-m` after `--`, before any AWS call,
  pointing at `--mode`; `--mongo-connection-string` is no longer a runner option and is no longer
  named by the CLI.
- **Docs**: CLAUDE.md (the MongoDB accepted-risk row, the Morphia and connection-string gotchas, the
  `-m` gotcha, *What isn't there*, *Adding a benchmark type*), README, `infra/README.md`, the two
  `jmh-with-*.sh` scripts, `c4-3-component-runner.mmd`. C4, A13 and B11 are deleted from
  `docs/review/open-findings.md`.

Not marked as an incompatible release: `baas` users change nothing, since the CLI never selected
Mongo, always passes `--s3-bucket` and `--results-table`, and pins its own runner version. Only a
direct invocation of `benchmark-runner.jar` with the removed options is affected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `results-store-schema`: *MongoDB remains available as a runner-local adapter* is removed; *Every job
  names a results store* requires `--results-table` alone; *The store interface exposes writes only*
  drops its MongoDB wording; and a new requirement, *Every job names an artifact bucket*, makes
  `--s3-bucket` required.
- `jcstress-execution`: *A JCStress job can select its mode* no longer reserves `-m` for MongoDB; `-m`
  is not a runner option, and `baas run` refuses it pointing at `--mode`.

## Impact

- **Code**: `benchmark-runner` (`infra`, `entities`, `results`, `services`, `commands`, ITs and their
  base classes), `baas-model` (`RunnerArgs`, the `StoredMeasurement` comment), `baas-cli` (`RunCommand.insteadOf`,
  two comments, `simplelogger.properties`).
- **Runner JAR's options**: `--mongo-connection-string`/`-m` removed; `--s3-bucket` and
  `--results-table` required. The `MONGO_CONNECTION_STRING` environment variable is no longer read.
- **Tests**: the Mongo testcontainer is gone, so `mvn verify` starts LocalStack only; the store
  contract suite keeps DynamoDB as its one real binding.
- **Local runs**: `./jmh-with-async.sh` and `./jmh-with-profiler.sh` already name a LocalStack table
  and bucket; their Mongo notes go. `docker-compose up` starts LocalStack alone.
- **Cost**: none. No AWS resource changes: no template, IAM, user-data or image edit.
- **Deliberately not changed**: the store port and `DynamoDbResultsStore`; upload-before-store order
  and store-failure-fails-the-job; user-data and the options the CLI passes; `RunnerArgs.SET_BY_THE_CLI`
  guarding every option the CLI sets; the `pl.wsztajerowski.entities` package name; the absence of any
  fallback store (no option discards measurements).
- **Closes**: C4, A13, B11.
