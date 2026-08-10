## 1. Shared model module

- [ ] 1.1 Create the `baas-model` module and register it in the root `pom.xml` reactor ahead of
  `benchmark-runner` and `baas-cli`
- [ ] 1.2 Define the stored `ResultItem` and `IndexItem` shapes, covering both JMH and JCStress results
- [ ] 1.3 Implement key encoding for all five item forms (`RUN#`, `TAG#`, `BENCH#`, `ALL#`, and the
  `JCSTRESS` sort key) in one class with no duplicated literals
- [ ] 1.4 Implement fixed-width UTC ISO-8601 timestamp formatting for sort keys
- [ ] 1.5 Unit-test that lexicographic ordering of formatted timestamps equals chronological ordering
  across month and year boundaries
- [ ] 1.6 Implement the `Map<String, AttributeValue>` mapper both ways
- [ ] 1.7 Unit-test mapper round trips for a JMH result, a JCStress result, and each index item form
- [ ] 1.8 Unit-test that a realistic result item serializes well under 400 KB, and that an oversized item
  fails loudly rather than being truncated
- [ ] 1.9 Implement index-item derivation from a result, honouring the reserved non-indexed tag-key set
- [ ] 1.10 Unit-test derivation: a result with four indexable tags yields four tag items plus one benchmark
  and one time item, and reserved keys yield none

## 2. CloudFormation and IAM

- [ ] 2.1 Add the results table to `cf-template-core.yaml`: `pk`/`sk` String keys, on-demand billing, no
  GSIs, no TTL, `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`
- [ ] 2.2 Add a DynamoDB gateway endpoint associated with the runner's route table
- [ ] 2.3 Remove TCP 27017 egress from `RunnerSecurityGroup`
- [ ] 2.4 Add a table-name stack output
- [ ] 2.5 Scope `RunnerRole` to `dynamodb:PutItem` and `BatchWriteItem` on the table only
- [ ] 2.6 Grant `BaasCliOperatorRole` `dynamodb:Query` and `GetItem` on the table only
- [ ] 2.7 Add the table lifecycle actions to `deployer-policy.json`, prefix-scoped with no `Resource: "*"`
- [ ] 2.8 Remove every Mongo SSM grant from `deployer-policy.json`, `operator-policy.json`,
  `cf-template-ci.yaml`, and `RunnerRole`
- [ ] 2.9 Add template tests: no interface endpoint for DynamoDB, no port 27017 rule, runner cannot
  `Scan` or `DeleteItem`, operator cannot `PutItem`
- [ ] 2.10 Extend `DeployerPolicyTest` for the new DynamoDB statements and the removed Mongo statements

## 3. Runner store

- [ ] 3.1 Add the DynamoDB SDK dependency; remove `mongodb-driver-sync` and Morphia from
  `benchmark-runner`
- [ ] 3.2 Strip `dev.morphia` annotations from `entities/jmh/*` and `entities/jcstress/*`
- [ ] 3.3 Replace `DatabaseService` with a `ResultsStore` interface exposing only a write operation
- [ ] 3.4 Delete `DocumentDbService`, the `UpsertService` interface, and its no-op implementation
- [ ] 3.5 Implement the DynamoDB-backed store: derive index items and write each result as one
  `BatchWriteItem`
- [ ] 3.6 Implement retry with backoff, and non-zero exit when the write ultimately fails
- [ ] 3.7 Rewrite the builder to take table name, region, and an optional endpoint override for LocalStack
- [ ] 3.8 Make a missing table name a hard failure and gate the no-op store behind `--no-database`
- [ ] 3.9 Map `JmhResult` into the stored shape, dropping `rawData` and `scorePercentiles` and reducing
  `secondaryMetrics` to score and unit
- [ ] 3.10 Upload the verbatim JMH result JSON to the run's S3 result path and record `resultJsonKey`
- [ ] 3.11 Order the writes S3-first, then DynamoDB, in all four subcommand services
- [ ] 3.12 Update `TestWrapper` for the new options and store wiring

## 4. CLI query layer

- [ ] 4.1 Remove `mongodb-driver-sync` from `baas-cli` and add the DynamoDB SDK
- [ ] 4.2 Rewrite `ResultsQueryService` against the table using `baas-model`
- [ ] 4.3 Implement query by request ID
- [ ] 4.4 Implement query by branch, descending
- [ ] 4.5 Implement query by benchmark class, with optional method prefix on the sort key
- [ ] 4.6 Implement query by arbitrary tag, descending
- [ ] 4.7 Implement the unfiltered query over the month-partitioned time index, walking back months until
  the limit fills
- [ ] 4.8 Apply the `excludeFromResults` filter expression server-side
- [ ] 4.9 Implement client-side grouping by `(benchmark, branch)` keeping the highest score
- [ ] 4.10 Reject regular-expression `--benchmark` input with a message naming the supported forms
- [ ] 4.11 Add `--tag <key>=<value>` and `--limit`; enforce filter mutual exclusion
- [ ] 4.12 Keep table, JSON, and CSV payloads on `System.out` and diagnostics on the logger
- [ ] 4.13 Unit-test grouping and best-score selection, including the same benchmark on two branches

## 5. CLI configuration and commands

- [ ] 5.1 Add the results table name to `BaasConfig`, populated from the stack output
- [ ] 5.2 Populate it in `baas admin setup` and `baas config sync`
- [ ] 5.3 Remove `--mongo-uri` from `SetupCommand` and `ConfigSetSubcommand`, and delete the SecureString
  write
- [ ] 5.4 Delete `validateMongoUri` from all three classes that carry a copy
- [ ] 5.5 Pass the table name through `UserDataScriptBuilder` and remove the SSM connection-string fetch
- [ ] 5.6 Add the `--no-database` pass-through to `baas run` and fail before provisioning when the table is
  unresolvable
- [ ] 5.7 Extend `SetupCommand`'s retained-resource pre-check to cover the results table
- [ ] 5.8 Update `TeardownCommand`'s confirmation text to name both retained resources
- [ ] 5.9 Update `ConfigShowSubcommand` output for the new key and the removed one

## 6. Test infrastructure

- [ ] 6.1 Replace the LocalStack-plus-Mongo base class with a single LocalStack container running `s3` and
  `dynamodb`, creating the table in setup
- [ ] 6.2 Delete `MongoDbTestHelpers` and the Mongo Testcontainers base class
- [ ] 6.3 Rewrite `DatabaseServiceIT` against the new store
- [ ] 6.4 Add an integration test asserting a stored result produces its result item and all derived index
  items
- [ ] 6.5 Add an integration test asserting a repeated write is idempotent
- [ ] 6.6 Add integration tests for each of the five query patterns
- [ ] 6.7 Add an integration test asserting a store failure exits non-zero while leaving S3 artifacts intact
- [ ] 6.8 Update `docker-compose.yaml`: drop `mongo` and `mongo-express`, add `dynamodb` to LocalStack
  services
- [ ] 6.9 Update `jmh-with-profiler.sh` and `jmh-with-async.sh` to pass a table name or `--no-database`
- [ ] 6.10 Run the full reactor `mvn clean verify` with `ASYNC_PATH` exported

## 7. Data migration

- [ ] 7.1 Write `scripts/migrate-atlas-to-dynamodb` reading Atlas and writing result plus index items
- [ ] 7.2 Handle both `_id` forms: the composite JMH key and the bare JCStress `requestId`
- [ ] 7.3 Normalise existing `createdAt` values to the fixed-width UTC format used by sort keys
- [ ] 7.4 Implement `--dry-run` reporting counts per collection and per derived item type
- [ ] 7.5 Make the script idempotent so a partial run can be repeated safely
- [ ] 7.6 Inventory the tag keys present in the Atlas data and finalise the reserved non-indexed set
- [ ] 7.7 Dry-run, review counts, then migrate for real
- [ ] 7.8 Verify: row counts match, spot-checked scores are identical, and `baas results --branch main`
  agrees with historical output allowing for the documented `tags.project` filter difference

## 8. Cutover and cleanup

- [ ] 8.1 Run a live benchmark end to end and confirm result and index items appear
- [ ] 8.2 Confirm `baas results` answers all five patterns against real migrated data
- [ ] 8.3 Delete the `/<prefix>/mongo/connection-string` parameter by hand
- [ ] 8.4 Decommission the Atlas cluster
- [ ] 8.5 Delete `scripts/migrate-atlas-to-dynamodb`
- [ ] 8.6 Remove the obsolete `openspec/changes/atlas-service-account-credentials` change

## 9. Documentation

- [ ] 9.1 Update `CLAUDE.md`: DynamoDB replaces MongoDB throughout, the table-name-in-user-data change to
  the SSM invariant, the removed 27017 egress rule, the `--no-database` requirement, the new `baas-model`
  module, the retained-table setup trap, and the single-container local setup
- [ ] 9.2 Remove the three obsolete *Accepted risks* rows: Atlas IP allowlist, MongoDB connect-only, and
  the shared `RunnerRole` SSM Mongo path
- [ ] 9.3 Update `README.md`: first-run flow without `--mongo-uri`, the S3 result-JSON addition, and the
  `--benchmark` matching change
- [ ] 9.4 Update `infra/README.md` for the table, the gateway endpoint, and the IAM changes
- [ ] 9.5 Update `docs/diagrams/` for all three command sequences
- [ ] 9.6 Update `docs/adr/0001-self-contained-baas-cli.md` where it assumes an external database
- [ ] 9.7 Mark findings A3, A5, and D1 fixed and A4 partially addressed in `docs/review/`, updating both
  status tables
