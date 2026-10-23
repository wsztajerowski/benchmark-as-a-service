# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm nothing outside the files named in the proposal reads Mongo, Morphia,
  `MONGO_CONNECTION_STRING` or `LocalStorageService` (`git grep -i -e mongo -e morphia -e LocalStorage`
  excluding archives, reviews and ADRs); record any surprise in design.md before going on
- [x] 1.2 Confirm every caller already passes both options: `UserDataScriptBuilder` (user-data passes
  `--s3-bucket` and `--results-table` on every job), `jmh-with-async.sh`, `jmh-with-profiler.sh`;
  verify by reading each and by `UserDataScriptBuilderTest`'s existing assertions
- [x] 1.3 Confirm picocli rejects a missing `required = true` option declared in
  `ApiCommonSharedOptions` for every subcommand (it is shared across the four types): a throwaway
  `ApiCommonSharedOptionsTest` case without `--s3-bucket` exits non-zero naming it; keep the case if it
  holds

## 2. Move the service ITs onto DynamoDB (one commit, green before any deletion)

- [x] 2.1 `JmhSubcommandServiceIT`, `JmhWithProfilerSubcommandServiceIT` and
  `JmhWithAsyncProfilerSubcommandServiceIT` extend `TestcontainersWithDynamoDbBaseIT`, store through
  `DynamoDbResultsStore`, and assert the same fields through `MeasurementItemMapper.fromItem`; verify
  with `mvn -pl benchmark-runner verify` (full reactor built first; `ASYNC_PATH` exported for the
  async IT)
- [x] 2.2 `JCStressSubcommandServiceIT` likewise, asserting the JCStress item's counts; verify the same
  way
- [x] 2.3 Commit with the Mongo code still present; the full reactor `mvn verify` is green

## 3. Close B11's upload half

- [x] 3.1 `JmhRunResults.uploadJsonAndMap` becomes `map(...)` without a `StorageService`; the three JMH
  services upload `jmh-result.json` immediately before mapping; verify `JmhStoreIntegrationIT`'s
  artifacts-survive-a-store-failure test and the service ITs still pass, and that `JmhRunResults`
  imports no `StorageService` (it keeps `ResultLoaderService`, the JSON parser, which is mapping)

## 4. Remove the MongoDB store

- [x] 4.1 Delete `MongoResultsStore`, `MongoMeasurementDocument`, `MongoResultsStoreContractIT`,
  `TestcontainersWithS3AndMongoBaseIT`, `MongoDbTestHelpers`, and the Mongo branch and
  `withConnectionString` of `ResultsStoreBuilder`; `ResultsStoreBuilderTest` keeps the blank-table
  refusal and loses the exactly-one-of cases; verify `mvn -pl benchmark-runner test` is green
- [x] 4.2 Remove `--mongo-connection-string`/`-m` from `ApiCommonSharedOptions`, make
  `--results-table` `required = true`; `ApiCommonSharedOptionsTest` covers a missing table and an
  unknown `-m`, each exiting non-zero before any benchmark
- [x] 4.3 Remove the Morphia annotations from `JmhResult`, `Metric`, `JCStressResult`, and the
  `mongodb-driver-sync`, `morphia-core`, `testcontainers-mongodb` dependencies from the POMs; verify
  `mvn dependency:tree` lists no `org.mongodb` or `dev.morphia` artifact and the reactor builds
- [x] 4.4 Remove the `mongo` service from `docker-compose.yaml` and the Mongo block from
  `.github/dependabot.yml`; verify `docker compose config` parses

## 5. Remove local storage mode

- [x] 5.1 Delete `LocalStorageService`; `StorageService.of` always builds `S3StorageService`;
  `--s3-bucket` leaves its `ArgGroup` as a plain `required = true` option; `ApiCommonSharedOptionsTest`
  covers a missing bucket exiting non-zero naming `--s3-bucket`; `JobLogs`' comment no longer cites
  local storage

## 6. CLI and model

- [x] 6.1 Drop `MONGO_CONNECTION_STRING`/`_SHORT` from `RunnerArgs` and `SET_BY_THE_CLI`; update
  `RunnerArgsTest`; verify `RunnerArgsContractTest` still passes
- [x] 6.2 `RunCommand` refuses a `-m` among the benchmark's arguments with its own message naming
  `--mode`, exit 2, before any AWS call; the existing `-m` test in `RunCommand`'s tests moves to the new
  wording, plus one asserting `--mongo-connection-string` is no longer refused by the CLI
- [x] 6.3 Update the stale comments: `StoredMeasurement`, `ConfigShowSubcommand`,
  `UserDataScriptBuilder`, `simplelogger.properties`, `e2e-cloud-test.yml`'s `--mode` note; verify by
  `git grep -i mongo` returning only history-describing lines (`ConfigShowSubcommand` and
  `UserDataScriptBuilder` already describe the removed SSM fetch as history and are unchanged)

## 7. Documentation

- [ ] 7.1 CLAUDE.md: delete the MongoDB accepted-risk row, the Morphia and connection-string gotchas,
  the `baas-cli` "no MongoDB path" and runner-keeps-Mongo lines in *What isn't there*; reword the
  `--mode` gotcha (no "can follow once retire-mongodb frees `-m`"); *Adding a benchmark type* says
  `--results-table` and `--s3-bucket` are both required and drops `LocalStorageService`; the
  Testcontainers gotcha drops `mongo:7.0.5`
- [ ] 7.2 README (integration tests, docker-compose, local runs), `infra/README.md` (the Atlas section's
  "still carries a MongoDB adapter" paragraph), `jmh-with-async.sh`/`jmh-with-profiler.sh` notes
- [ ] 7.3 `docs/diagrams/c4-3-component-runner.mmd`: remove the Mongo adapter and local storage; render
  with `mmdc` and look at the PNG before committing
- [ ] 7.4 Delete C4, A13 and B11 from `docs/review/open-findings.md` (table rows and the
  *retire-mongodb* design section); delete the `retire-mongodb` row from `openspec/changes/QUEUE.md`

## 8. End-to-end verification (manual — no automated test covers the `baas run` path)

- [ ] 8.1 On `baas-development`, with the reactor build and `--runner-jar benchmark-runner/target/benchmark-runner.jar`:
  one `jmh-with-async` job against `fake-jmh-benchmarks` and one JCStress job (`-- --mode sanity`);
  both complete, store their items, and upload `jmh-result.json`, `environment.json` and the profiler
  artifacts; record job IDs and outcomes in verify.md
- [ ] 8.2 `baas run jcstress … -- -m sanity` is refused before any upload, naming `--mode`; record the
  output in verify.md
- [ ] 8.3 Standalone runner refusals: `java -jar benchmark-runner.jar jmh …` without `--s3-bucket`,
  without `--results-table`, and with `-m mongodb://x/db` each exit non-zero naming the option and run
  nothing; record in verify.md

## 9. Verify

- [ ] 9.1 Run `/opsx:verify` and record the result in verify.md: a requirement → code → test → gap
  table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks
