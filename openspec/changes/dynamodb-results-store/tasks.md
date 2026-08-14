## 1. Verify blocking assumptions

- [ ] 1.1 Count the JMH and JCStress documents in Atlas and record the totals; confirm the working set is
  small enough that a single partition sweep is viable (~10k fine, ~100k slow, ~1M fails)
- [ ] 1.2 If the count lands near or above 100k, adopt the year-sharded partition key
  (`RESULT#<project>#<yyyy>`) before implementing anything else, and revise `design.md`
- [ ] 1.3 Inventory the distinct tag keys present in the Atlas data and confirm the known-key vocabulary
  covers them; record any key the migration must map or drop
- [ ] 1.4 Confirm `environment.json` actually carries CPU model, CPU architecture and JDK version in a
  form the runner can copy into tags without new observation work
- [ ] 1.5 Confirm the retained MongoDB adapter has no consumer inside `baas-cli` — if any CLI code path
  reads Mongo, the "CLI never learns Mongo exists" decision needs revisiting
- [ ] 1.6 Decide the `project` value migrated rows receive when they carry no `project` tag

## 2. Tag pass-through (prerequisite — nothing downstream works without it)

- [ ] 2.1 Thread `RunCommand`'s `extraTags` into `UserDataScriptBuilder` and emit one `--tag` argument per
  entry in the runner invocation
- [ ] 2.2 Add `--project` to `baas run`, defaulting to the git repository name, and forward it as a tag
- [ ] 2.3 Derive `commit` from `git rev-parse HEAD` and forward it as a tag
- [ ] 2.4 Capture JDK version, CPU model and CPU architecture on the instance and forward them as tags,
  taking the values from the same shell variables the environment manifest uses
- [ ] 2.5 Extend the existing `passesEnvironmentTagsToTheRunnerNotJustToTheInstance` test to cover user
  tags, `project` and `commit`
- [ ] 2.6 Verify against a real run that a user tag reaches the stored result, not only the instance

## 3. Shared model module

- [ ] 3.1 Create the `baas-model` module and register it in the root `pom.xml` reactor ahead of
  `benchmark-runner` and `baas-cli`; assert it has no MongoDB dependency
- [ ] 3.2 Define the stored measurement shape, covering both JMH and JCStress
- [ ] 3.3 Implement key encoding for `pk`, both `sk` forms and the request-ID index, in one class with no
  duplicated literals
- [ ] 3.4 Implement fixed-width UTC ISO-8601 timestamp formatting for sort keys
- [ ] 3.5 Unit-test that lexicographic ordering of formatted timestamps equals chronological ordering
  across month and year boundaries
- [ ] 3.6 Define the known-tag-key vocabulary as constants
- [ ] 3.7 Implement the `Map<String, AttributeValue>` mapper both ways
- [ ] 3.8 Unit-test mapper round trips for a JMH measurement and a JCStress measurement
- [ ] 3.9 Unit-test that a realistic measurement serializes well under 400 KB, and that an oversized one
  fails loudly rather than being truncated

## 4. CloudFormation and IAM

- [ ] 4.1 Add the results table to `cf-template-core.yaml`: `pk`/`sk` String keys, on-demand billing, one
  `requestId` GSI, no TTL, `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`
- [ ] 4.2 Add a DynamoDB gateway endpoint associated with the runner's route table
- [ ] 4.3 Remove TCP 27017 egress from `RunnerSecurityGroup`
- [ ] 4.4 Add a table-name stack output
- [ ] 4.5 Scope `RunnerRole` to `dynamodb:PutItem` and `BatchWriteItem` on the table only
- [ ] 4.6 Grant `BaasCliOperatorRole` `dynamodb:Query` and `GetItem` on the table and its index only
- [ ] 4.7 Add the table lifecycle actions to `deployer-policy.json`, prefix-scoped with no `Resource: "*"`
- [ ] 4.8 Remove every Mongo SSM grant from `deployer-policy.json`, `operator-policy.json`,
  `cf-template-ci.yaml`, and `RunnerRole`
- [ ] 4.9 Add template tests: no interface endpoint for DynamoDB, no port 27017 rule, runner cannot `Scan`
  or `DeleteItem`, operator cannot `PutItem`
- [ ] 4.10 Extend `DeployerPolicyTest` for the new DynamoDB statements and the removed Mongo statements,
  and confirm the rendered policy still fits the inline-policy budget

## 5. Runner store port and adapters

- [ ] 5.1 Add the DynamoDB SDK dependency to `benchmark-runner`; keep `mongodb-driver-sync` and Morphia
- [ ] 5.2 Replace `DatabaseService` with a storage-neutral `ResultsStore` port exposing only a write
- [ ] 5.3 Delete the `UpsertService` interface and its implementations
- [ ] 5.4 Implement the DynamoDB adapter: one item per measurement, batched per run
- [ ] 5.5 Refactor `DocumentDbService` into a MongoDB adapter implementing the same port
- [ ] 5.6 Implement retry with backoff, and non-zero exit when the write ultimately fails
- [ ] 5.7 Rewrite the builder to select an adapter from table name, connection string, or `--no-database`,
  making ambiguous or absent configuration a hard failure
- [ ] 5.8 Map `JmhResult` into the stored shape, dropping `rawData` and `scorePercentiles` and reducing
  `secondaryMetrics` to score and unit
- [ ] 5.9 Map `JCStressResult` into the stored shape, keeping the counts and the three test maps
- [ ] 5.10 Upload the verbatim JMH result JSON to the run's S3 result path and record `resultJsonKey`,
  `resultPath` and `environmentJsonKey`
- [ ] 5.11 Order the writes S3-first, then store, in all four subcommand services
- [ ] 5.12 Update `TestWrapper` for the new options and store wiring

## 6. CLI query layer

- [ ] 6.1 Remove `mongodb-driver-sync` from `baas-cli` and add the DynamoDB SDK
- [ ] 6.2 Rewrite `ResultsQueryService` against the table using `baas-model`
- [ ] 6.3 Implement the project-partition query as the single access path
- [ ] 6.4 Implement query by request ID against the GSI
- [ ] 6.5 Apply the `exclude_from_results` filter expression server-side
- [ ] 6.6 Implement client-side tag filtering, including repeated `--tag` combining conjunctively
- [ ] 6.7 Implement client-side regex matching for `--benchmark-name`
- [ ] 6.8 Reimplement `--living-branches` as a client-side filter over one query, tolerating absent tags
- [ ] 6.9 Implement grouping by `(benchmark, <group-tag>)` keeping the highest score, defaulting the group
  tag to `branch` and bucketing untagged rows rather than dropping them
- [ ] 6.10 Warn when a `--tag` key is outside the known-key vocabulary and no row carries it
- [ ] 6.11 Add `--limit` and enforce `--request-id` mutual exclusion
- [ ] 6.12 Keep table, JSON and CSV payloads on `System.out` and diagnostics on the logger
- [ ] 6.13 Unit-test grouping and best-score selection, including the same benchmark under two group
  values and rows with no group tag

## 7. CLI configuration and commands

- [ ] 7.1 Add the results table name to `BaasConfig`, populated from the stack output
- [ ] 7.2 Populate it in `baas admin setup` and `baas config sync`
- [ ] 7.3 Remove `--mongo-uri` from `SetupCommand` and `ConfigSetSubcommand`, and delete the SecureString
  write
- [ ] 7.4 Delete `validateMongoUri` from all three classes that carry a copy
- [ ] 7.5 Pass the table name through `UserDataScriptBuilder` and remove the SSM connection-string fetch
- [ ] 7.6 Add the `--no-database` pass-through to `baas run` and fail before provisioning when the table is
  unresolvable
- [ ] 7.7 Implement the run-artifact download command over the run's S3 prefix
- [ ] 7.8 Extend `SetupCommand`'s retained-resource pre-check to cover the results table
- [ ] 7.9 Update `TeardownCommand`'s confirmation text to name both retained resources
- [ ] 7.10 Update `ConfigShowSubcommand` output for the new key and the removed one

## 8. Test infrastructure

- [ ] 8.1 Add `dynamodb` to the LocalStack test container and create the table in setup
- [ ] 8.2 Keep the Mongo Testcontainers base class and `MongoDbTestHelpers` for the retained adapter
- [ ] 8.3 Write one store contract test suite and run it against both adapters
- [ ] 8.4 Add an integration test asserting a stored run produces one item per measurement and no others
- [ ] 8.5 Add an integration test asserting a repeated write is idempotent
- [ ] 8.6 Add integration tests for the partition query and the request-ID index query
- [ ] 8.7 Add an integration test asserting a store failure exits non-zero while leaving S3 artifacts intact
- [ ] 8.8 Add an integration test for the run-artifact download
- [ ] 8.9 Update `docker-compose.yaml`: drop `mongo-express`, add `dynamodb` to LocalStack, keep `mongo`
- [ ] 8.10 Update `jmh-with-profiler.sh` and `jmh-with-async.sh` to pass a table name or `--no-database`
- [ ] 8.11 Run the full reactor `mvn clean verify` with `ASYNC_PATH` exported

## 9. Data migration

- [ ] 9.1 Write `scripts/migrate-atlas-to-dynamodb` reading Atlas and writing measurement items
- [ ] 9.2 Handle both `_id` forms: the composite JMH key and the bare JCStress `requestId`
- [ ] 9.3 Normalise existing `createdAt` values to the fixed-width UTC format used by sort keys
- [ ] 9.4 Map historical tags onto the vocabulary, applying the §1.6 default for rows lacking `project`
- [ ] 9.5 Implement `--dry-run` reporting counts per collection and per derived project partition
- [ ] 9.6 Make the script idempotent so a partial run can be repeated safely
- [ ] 9.7 Dry-run, review counts, then migrate for real
- [ ] 9.8 Verify: row counts match, spot-checked scores are identical, and `baas results` agrees with
  historical output allowing for the documented `tags.project` filter difference

## 10. Cutover and cleanup

- [ ] 10.1 Delete the `/<prefix>/mongo/connection-string` parameter by hand
- [ ] 10.2 Decommission the Atlas cluster
- [ ] 10.3 Delete `scripts/migrate-atlas-to-dynamodb`
- [ ] 10.4 Remove the obsolete `openspec/changes/atlas-service-account-credentials` change

## 11. Documentation

- [ ] 11.1 Update `CLAUDE.md` invariants: retire "the mongo URI never goes into user-data" (the table name
  is not a secret), remove "`RunnerSecurityGroup` needs egress on TCP 27017", and record that `baas run`
  now forwards user tags, `project` and `commit` to the runner
- [ ] 11.2 Update `CLAUDE.md` *What isn't there*: measurements are no longer MongoDB-only and a verbatim
  result JSON now exists in S3; note that `baas-cli` has no MongoDB path while `benchmark-runner` retains
  one for standalone use
- [ ] 11.3 Update `CLAUDE.md` *Accepted risks*: remove the Atlas IP allowlist and the shared `RunnerRole`
  SSM Mongo path rows, and reduce "MongoDB connect-only" to the standalone-runner case
- [ ] 11.4 Document the new `baas-model` module and the tag vocabulary in `CLAUDE.md`
- [ ] 11.5 Document the retained-table setup trap alongside the existing retained-bucket one
- [ ] 11.6 Update `README.md`: first-run flow without `--mongo-uri`, `--project`, the run-artifact
  download, and the results filters
- [ ] 11.7 Update `infra/README.md` for the table, the gateway endpoint and the IAM changes
- [ ] 11.8 Update `docs/diagrams/` for the affected command sequences
- [ ] 11.9 Update `docs/adr/0001-self-contained-baas-cli.md` where it assumes an external database
- [ ] 11.10 Mark findings A3, A5 and D1 fixed and A4 partially addressed in `docs/review/`, updating both
  status tables

## 12. End-to-end verification (manual — no automated test covers `baas run`)

- [ ] 12.1 **Manual**: `baas admin setup` on a clean prefix; confirm the table, the gateway endpoint and
  the absence of a 27017 egress rule
- [ ] 12.2 **Manual**: run a live JMH benchmark via `baas run` with a custom `--tag`; confirm one item per
  benchmark method, no derived items, and that `project`, `commit`, `jdk`, `cpuModel`, `cpuArch` and the
  custom tag are all present
- [ ] 12.3 **Manual**: confirm the stored tags agree with the same run's `environment.json`
- [ ] 12.4 **Manual**: run a live JCStress benchmark; confirm its single item and its test maps
- [ ] 12.5 **Manual**: exercise `baas results` unfiltered, by tag, by benchmark-name regex, by request ID
  and with `--living-branches`, against real migrated data
- [ ] 12.6 **Manual**: download a run's artifacts and confirm `rawData` is recoverable from the result JSON
- [ ] 12.7 **Manual**: confirm a run with `--no-database` succeeds and writes nothing
- [ ] 12.8 **Manual**: confirm a benchmark score is consistent with a pre-change run of the same benchmark,
  stating the observed spread — run-to-run variance is large (CI history spans 10.0M–29.6M ops/s on one
  benchmark), so investigate only a difference outside that band
- [ ] 12.9 **Manual**: `baas admin teardown` and confirm the table survives and is named in the prompt
