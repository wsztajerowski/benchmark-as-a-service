# Tasks

**Section numbers are labels, not execution order.** The real order is below; §13 and §14 are
numbered last because they are the last things that should happen, and §7's cutover items were
moved out of it for the same reason.

| Order | Sections | Why here |
|---|---|---|
| 1 | §1, §2, §3, §4 | ✅ done and deployed. Purely additive; nothing writes to the table yet |
| 2 | §5, §8 | ✅ done. The write path and its tests. Config still selects Mongo |
| 3 | §6, 7.1, 7.2, 7.7-7.10 | ✅ done. Read path and config plumbing. Table still empty |
| 4 | **§13** | ✅ code done, not yet deployed. The cutover: new runs go to DynamoDB; Atlas keeps history and stays readable |
| 5 | §12 | Live verification of the new write and read paths |
| 6 | **§9** | Migrate history, into a schema live runs have now exercised |
| 7 | §12b | The two manual checks that need migrated data |
| 8 | **§14**, then §10, §11 | Remove Mongo, clean up, document |

**Cutover precedes migration (revised 2026-08-19; originally the reverse).** Migrating first would
write 121 historical documents into a schema no live run had yet exercised, so a schema defect
would cost a re-migration. Cutting over first costs only a re-run of an idempotent script. It also
removes the sweep step: with §9 after the cutover, no run is ever written to Atlas after §13, so
there is no window of Atlas-only runs to catch up (former task 13.5). The cost is that
`baas results` reports only post-cutover runs until §9 lands — Atlas stays readable throughout, so
nothing is lost, and the two manual checks that genuinely need history are held back to §12b.

There is deliberately **no dual-write**: assurance comes from §8's integration tests, §12's live
checks after cutover, §9.8's verification over all historical rows, and Atlas being retained as
rollback until §14.

## 1. Verify blocking assumptions

- [x] 1.1 Count the JMH and JCStress documents in Atlas and record the totals; confirm the working set is
  small enough that a single partition sweep is viable (~10k fine, ~100k slow, ~1M fails)

  **Finding:** `jmh_benchmarks: 121`, `jcstress_tests: 0`. Total = 121 documents. Far below the ~10k
  "fine" threshold — a single partition sweep is trivially viable at current scale.

- [x] 1.2 If the count lands near or above 100k, adopt the year-sharded partition key
  (`RESULT#<project>#<yyyy>`) before implementing anything else, and revise `design.md`

  **Finding:** 121 << 100k. Per the task-1 brief's ruling, `design.md` and
  `specs/results-store-schema/spec.md` are left unmodified — `pk = RESULT#<project>` stands with no
  year-sharding. Numbers recorded above in 1.1.

- [x] 1.3 Inventory the distinct tag keys present in the Atlas data and confirm the known-key vocabulary
  covers them; record any key the migration must map or drop

  **Finding:** distinct tag keys observed across `jmh_benchmarks` (`jcstress_tests` is empty, so it
  contributes nothing): `branch`, `exclude_from_results`, `imageVersion`, `instanceType`, `options`,
  `project`, `source`, `type`.
  - Covered by the known vocabulary already documented in `proposal.md`/`design.md`: `exclude_from_results`,
    `imageVersion`, `instanceType`, `options`, `project`, `type`. `branch` is present too, but `design.md`
    already treats it as a deliberate custom tag, not a known key — no action needed.
  - Known-vocabulary keys **absent** from the historical data: `commit`, `jdk`, `cpuModel`, `cpuArch`.
    Expected — these are the tags `baas run --tag`/environment-observation threading was never wired to
    emit (the exact gap `design.md`'s Risks section calls "`baas run --tag` currently reaches nothing").
  - **`source` is not in the known vocabulary and must be mapped or dropped.** Present on 36/121 docs,
    values `gha-e2e-test`, `gha-e2e-test-async`, `gha-e2e-test-profilers` — all CI e2e-workflow markers
    (`e2e-cloud-test.yml`'s `workflow_dispatch` path), not something `baas run` itself ever wrote. Not
    decided here (out of this task's scope per the brief); flagged for task 9.4 to either map it into a
    known/custom tag as-is (unknown tags pass through with a CLI warning per `design.md`) or drop it
    during migration.

- [x] 1.4 Confirm `environment.json` actually carries CPU model, CPU architecture and JDK version in a
  form the runner can copy into tags without new observation work

  **Finding — variable inventory in `UserDataScriptBuilder.SCRIPT_BODY`** (baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java):
  - `CPU_MODEL` — line 58: `CPU_MODEL=$(json_escape "$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')")`. Already written into `environment.json` as `"cpuModel"`. **Exists.**
  - `JVM_VERSION` — line 66: `JVM_VERSION=$(json_escape "$(java -version 2>&1 | head -1)")`. Already
    written into `environment.json` as `"jvmVersion"`. **Exists, and already captures the JDK version** —
    the full first line of `java -version` output (e.g. `openjdk version "21.0.5" 2024-10-15 LTS`).
  - `cpuArch` — **does not exist.** No `uname -m` capture, no `CPU_ARCH`/`cpuArch` variable anywhere in
    `SCRIPT_BODY`, no `"cpuArch"` field in the `environment.json` heredoc. Confirms the brief's expected
    finding exactly: `CPU_MODEL` and `JVM_VERSION` exist, `cpuArch` does not and must be added in Task 4.

  **Most important finding (per ruling a):** `openspec/changes/dynamodb-results-store/plan.md`'s Task 4
  (lines 465-560) currently plans to add a **new, independent** `JDK_VERSION` variable:
  `JDK_VERSION=$(java -version 2>&1 | head -1 | sed -n 's/.*"\(.*\)".*/\1/p')` — a **second** invocation of
  `java -version`, re-parsed with `sed` to extract just the version number, then forwarded as `--tag
  "jdk=${JDK_VERSION}"`. This duplicates `JVM_VERSION`, which already captures the same underlying
  observation (the same `java -version` first line) and is already the value written into
  `environment.json`'s `"jvmVersion"` field. Two consequences:
  1. It re-runs `java -version` a second time for information the script already captured once.
  2. It computes the `jdk` tag from a **separate** subprocess invocation rather than from `JVM_VERSION`
     itself, which is exactly the shape `tasks.md` 2.4 says to avoid: "taking the values from the same
     shell variables the environment manifest uses." Deriving `JDK_VERSION` via `sed` on `$JVM_VERSION`
     (not a fresh `java -version` call) would guarantee the `jdk` tag can never disagree with
     `environment.json`'s `jvmVersion`, which is the CLAUDE.md invariant this whole pattern exists to
     protect ("a result's tags cannot disagree with its own `environment.json`").
  **Recommendation for Task 4 (not applied here — no code changes in this task):** compute `JDK_VERSION`
  as `JDK_VERSION=$(printf '%s' "$JVM_VERSION" | sed -n 's/.*"\(.*\)".*/\1/p')` (or similar, deriving from
  the already-captured `$JVM_VERSION`), not a second `java -version` invocation.

- [x] 1.5 Confirm the retained MongoDB adapter has no consumer inside `baas-cli` — if any CLI code path
  reads Mongo, the "CLI never learns Mongo exists" decision needs revisiting

  **Finding:** `grep -rn "mongo\|Mongo" --include="*.java" baas-cli/src/main` hits more files than the
  brief's illustrative list. Confirmed present, all matching the brief's expectation:
  `ResultsQueryService.java`, `ResultsCommand.java`, `SetupCommand.java`, `ConfigSetSubcommand.java`,
  `ConfigSyncSubcommand.java` (comment only).
  Additional hits, all of which this change's own `tasks.md`/`proposal.md` already schedules for removal:
  `UserDataScriptBuilder.java` (SSM connection-string fetch — removed by task 7.5),
  `ConfigShowSubcommand.java` (masked Mongo URI display — removed by task 7.10),
  `RunCommand.java` (SSM lookup + `ResultsQueryService` call in `showResults()`, plus two comment lines —
  listed in `proposal.md`'s Impact section; reworked as a natural consequence of task 6.2's
  `ResultsQueryService` rewrite, though no task item names `showResults()` explicitly).
  **Two genuine gaps, not currently covered by any numbered task:**
  1. **`DeployerPreflight.java`** (line 68) builds an `ssm:PutParameter` `SimulatePrincipalPolicy` probe
     against `arn:aws:ssm:...:parameter/<prefix>/mongo/connection-string` as part of `baas admin setup`'s
     preflight. Not named in `tasks.md` §4 or §7, nor in `proposal.md`'s Impact list. Needs removing (or
     replacing with an equivalent DynamoDB-table probe) alongside task 4.7/4.8.
  2. **`TeardownCommand.java`** (lines 99-104, 115) unconditionally deletes the
     `/<prefix>/mongo/connection-string` SSM parameter on every teardown and logs "MongoDB cluster NOT
     touched...". Task 7.9 only covers the confirmation *prompt* text; this delete-block and log line are
     not scoped to any task and will reference a parameter that (per task 10.1) is deleted by hand during
     cutover — leaving dead/misleading code post-migration.
  **Conclusion:** the "CLI never learns Mongo exists" decision itself is not invalidated — every hit found
  is either already scheduled for removal or is small cleanup naturally within task 7's scope — but the
  task list is missing explicit coverage for `DeployerPreflight.java` and `TeardownCommand.java`'s Mongo
  SSM delete block. Recommend folding both into task 7 (e.g. new items 7.11, 7.12) before implementation.

- [x] 1.6 Decide the `project` value migrated rows receive when they carry no `project` tag

  **Data:** of 121 total `jmh_benchmarks` docs, 80 carry `project=lynx-journal`; 41 carry no `project` tag
  at all. Of those 41: 36 also carry `source` = `gha-e2e-test`/`gha-e2e-test-async`/`gha-e2e-test-profilers`
  (CI e2e-workflow fixture runs against `fake-jmh-benchmarks`, not real `lynx-journal` measurements); the
  remaining 5 carry neither `project` nor `source` (minimal tags — e.g. only `imageVersion`/`instanceType`
  — consistent with very early/manual runs before tagging existed).
  **Recommendation (not decided unilaterally — for ratification):** default untagged rows to a distinct
  sentinel value, e.g. `unknown`, rather than folding them into `lynx-journal` — 36 of the 41 are
  demonstrably CI test-fixture runs, not `lynx-journal` measurements, and collapsing them into that
  project's partition would misrepresent its benchmark history in `baas results` grouping/best-score
  selection.

## 2. Tag pass-through (prerequisite — nothing downstream works without it)

- [x] 2.1 Thread `RunCommand`'s `extraTags` into `UserDataScriptBuilder` and emit one `--tag` argument per
  entry in the runner invocation
- [x] 2.2 Add `--project` to `baas run`, defaulting to the git repository name, and forward it as a tag
- [x] 2.3 Derive `commit` from `git rev-parse HEAD` and forward it as a tag
- [x] 2.4 Capture JDK version, CPU model and CPU architecture on the instance and forward them as tags,
  taking the values from the same shell variables the environment manifest uses
- [x] 2.5 Extend the existing `passesEnvironmentTagsToTheRunnerNotJustToTheInstance` test to cover user
  tags, `project` and `commit`
- [x] 2.6 Verify against a real run that a user tag reaches the stored result, not only the instance
  Verified 2026-08-17 on request `jmh-20260817_220706` (instance `i-06d1766071d96fcbb`, c5.2xlarge,
  ami-0aa25ec7fbf1c80f5, self-terminated). `baas run --tag experiment=gc-tuning` stored all nine
  tags on the measurement: `experiment=gc-tuning` (the user tag this task exists to prove),
  `project=dynamodb-results-store`, `commit=4e43c62`, `type=jmh`, plus the five observed ones.
  Every observed tag matches that run's own `environment.json` exactly — `cpuArch=x86_64` (the new
  field), `schemaVersion` 2, and `jdk=25.0.4` is precisely the quoted substring of
  `jvmVersion="openjdk version \"25.0.4\" 2026-07-21 LTS"`, confirming the one-observation split.
  `cpuModel`'s spaces and parentheses survived as a single token.
  Also confirmed live, outside the task's strict scope:
  - `baas run` outside a git repository exits 1 naming `--project` and makes NO AWS call at all.
  - `--tag jdk=8` is rejected by name, but only AFTER the S3 upload — the parked non-fail-fast
    finding, now observed rather than argued.
  NOT verified: `--project` override on a live run (plan.md Task 5 Step 4) — needs a second paid
  run; unit-tested only.

## 3. Shared model module

- [x] 3.1 Create the `baas-model` module and register it in the root `pom.xml` reactor ahead of
  `benchmark-runner` and `baas-cli`; assert it has no MongoDB dependency
- [x] 3.2 Define the stored measurement shape, covering both JMH and JCStress
- [x] 3.3 Implement key encoding for `pk`, both `sk` forms and the request-ID index, in one class with no
  duplicated literals
- [x] 3.4 Implement fixed-width UTC ISO-8601 timestamp formatting for sort keys
- [x] 3.5 Unit-test that lexicographic ordering of formatted timestamps equals chronological ordering
  across month and year boundaries
- [x] 3.6 Define the known-tag-key vocabulary as constants
- [x] 3.7 Implement the `Map<String, AttributeValue>` mapper both ways
- [x] 3.8 Unit-test mapper round trips for a JMH measurement and a JCStress measurement
- [x] 3.9 Unit-test that a realistic measurement serializes well under 400 KB, and that an oversized one
  fails loudly rather than being truncated

## 4. CloudFormation and IAM

- [x] 4.1 Add the results table to `cf-template-core.yaml`: `pk`/`sk` String keys, on-demand billing, one
  `requestId` GSI, no TTL, `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`
- [x] 4.2 Add a DynamoDB gateway endpoint associated with the runner's route table
- Mongo removal (formerly 4.3 and 4.8) moved to §13, at the end: those steps must not land
  until §12 confirms DynamoDB works. §4 therefore runs 4.1, 4.2, 4.4-4.7, 4.9, 4.10.
- [x] 4.4 Add a table-name stack output
- [x] 4.5 Scope `RunnerRole` to `dynamodb:PutItem` and `BatchWriteItem` on the table only
- [x] 4.6 Grant `BaasCliOperatorRole` `dynamodb:Query` and `GetItem` on the table and its index only
- [x] 4.7 Add the table lifecycle actions to `deployer-policy.json`, prefix-scoped with no `Resource: "*"`
- [x] 4.9 Add template tests: no interface endpoint for DynamoDB, runner cannot `Scan` or single-item
  `DeleteItem`, operator cannot `PutItem`. The "no port 27017 rule" assertion moved to 13.3 — while
  the runner still writes to Atlas the test must pin 27017's PRESENCE, which it does.
- [x] 4.10 Extend `DeployerPolicyTest` for the new DynamoDB statements and confirm the rendered policy
  still fits the inline-policy budget (raised 4096 -> 4608; inline on an IAM group, 5120 shared,
  nothing else attached). Asserting the REMOVED Mongo statements moved to 13.3.

## 5. Runner store port and adapters

- [x] 5.1 Add the DynamoDB SDK dependency to `benchmark-runner`; keep `mongodb-driver-sync` and Morphia
- [x] 5.2 Replace `DatabaseService` with a storage-neutral `ResultsStore` port exposing only a write
- [x] 5.3 Delete the `UpsertService` interface and its implementations
- [x] 5.4 Implement the DynamoDB adapter: one item per measurement, batched per run
- [x] 5.5 Refactor `DocumentDbService` into a MongoDB adapter implementing the same port
- [x] 5.6 Implement retry with backoff, and non-zero exit when the write ultimately fails
- [x] 5.7 Rewrite the builder to select an adapter from table name, connection string, or `--no-database`,
  making ambiguous or absent configuration a hard failure
- [x] 5.8 Map `JmhResult` into the stored shape, dropping `rawData` and `scorePercentiles` and reducing
  `secondaryMetrics` to score and unit
- [x] 5.9 Map `JCStressResult` into the stored shape, keeping the counts and the three test maps
- [x] 5.10 Upload the verbatim JMH result JSON to the run's S3 result path and record `resultJsonKey`,
  `resultPath` and `environmentJsonKey`
- [x] 5.11 Order the writes S3-first, then store, in all four subcommand services
- [x] 5.12 Update `TestWrapper` for the new options and store wiring

## 6. CLI query layer

- [x] 6.1 Remove `mongodb-driver-sync` from `baas-cli` and add the DynamoDB SDK
- [x] 6.2 Rewrite `ResultsQueryService` against the table using `baas-model`
- [x] 6.3 Implement the project-partition query as the single access path
- [x] 6.4 Implement query by request ID against the GSI
- [x] 6.5 Apply the `exclude_from_results` filter expression server-side
- [x] 6.6 Implement client-side tag filtering, including repeated `--tag` combining conjunctively
- [x] 6.7 Implement client-side regex matching for `--benchmark-name`
- [x] 6.8 Reimplement `--living-branches` as a client-side filter over one query, tolerating absent tags
- [x] 6.9 Implement grouping by `(benchmark, <group-tag>)` keeping the highest score, defaulting the group
  tag to `branch` and bucketing untagged rows rather than dropping them
- [x] 6.10 Warn when a `--tag` key is outside the known-key vocabulary and no row carries it
- [x] 6.11 Add `--limit` and enforce `--request-id` mutual exclusion
- [x] 6.12 Keep table, JSON and CSV payloads on `System.out` and diagnostics on the logger
- [x] 6.13 Unit-test grouping and best-score selection, including the same benchmark under two group
  values and rows with no group tag

## 7. CLI configuration and commands

- [x] 7.1 Add the results table name to `BaasConfig`, populated from the stack output
- [x] 7.2 Populate it in `baas admin setup` and `baas config sync`
- 7.3-7.6 moved to §13 (Runner cutover). They switch the runner off Atlas, so they must land
  once the read path is in place — not alongside the config plumbing above. §9 then migrates
  history into the schema those live runs have exercised.
- [x] 7.7 Implement the run-artifact download command over the run's S3 prefix
- [x] 7.8 Extend `SetupCommand`'s retained-resource pre-check to cover the results table
- [x] 7.9 Update `TeardownCommand`'s confirmation text to name both retained resources
- [x] 7.10 Update `ConfigShowSubcommand` output for the new results-table key. Removing the
  `mongo.connectionString` line waits for §13, since the key still exists until then

## 8. Test infrastructure

- [x] 8.1 Add `dynamodb` to the LocalStack test container and create the table in setup
- [x] 8.2 Keep the Mongo Testcontainers base class and `MongoDbTestHelpers` for the retained adapter
- [x] 8.3 Write one store contract test suite and run it against both adapters
- [x] 8.4 Add an integration test asserting a stored run produces one item per measurement and no others
- [x] 8.5 Add an integration test asserting a repeated write is idempotent
- [x] 8.6 Add integration tests for the partition query and the request-ID index query — deferred to
  §6 and landed with it: `ResultsQueryServiceIT` covers both query paths against LocalStack
- [x] 8.7 Add an integration test asserting a store failure exits non-zero while leaving S3 artifacts intact
- [x] 8.8 Add an integration test for the run-artifact download — deferred to 7.7 and landed with
  it: `S3DownloadIT`, including that `rawData` is recoverable from the downloaded result JSON
- [x] 8.9 Update `docker-compose.yaml`: drop `mongo-express`, add `dynamodb` to LocalStack, keep `mongo`
- [x] 8.10 Update `jmh-with-profiler.sh` and `jmh-with-async.sh` to pass a table name or `--no-database`
- [x] 8.11 Run the full reactor `mvn clean verify` with `ASYNC_PATH` exported

## 9. Data migration (runs after §13's cutover and §12's live verification)

- [ ] 9.1 Write `scripts/migrate-atlas-to-dynamodb` reading Atlas and writing measurement items
- [ ] 9.2 Handle both `_id` forms: the composite JMH key and the bare JCStress `requestId`
- [ ] 9.3 Normalise existing `createdAt` values to the fixed-width UTC format used by sort keys,
  truncating to milliseconds so they match what `StoredMeasurement` produces
- [ ] 9.3b Populate `mode` from Mongo's `_id.benchmarkType` — the sort key now carries it, and a
  defaulted or empty mode would collide multi-mode historical rows onto one key
- [ ] 9.4 Map historical tags onto the vocabulary. Rows lacking `project` get `unknown-migrated`
  (NOT bare `unknown` — `currentGitCommit()` already uses that for a missing commit, and two
  meanings sharing one string read identically in a results table). **Keep** the `source` tag on
  the 36 `gha-e2e-test*` rows, carried through as a custom tag: unknown keys are permitted by
  design and warned about only when no row carries them, 36 rows cost nothing at this scale, and
  discarding provenance is irreversible once Atlas is decommissioned (decided 2026-08-19,
  resolving the question 1.3 deferred here)
- [ ] 9.5 Implement `--dry-run` reporting counts per collection and per derived project partition
- [ ] 9.6 Make the script idempotent so a partial run can be repeated safely
- [ ] 9.7 Dry-run, review counts, then migrate for real
- [ ] 9.8 Verify: row counts match, spot-checked scores are identical, and `baas results` agrees with
  historical output allowing for the documented `tags.project` filter difference

## 10. Cleanup (the cutover itself moved to §13)

- 10.1 and 10.2 moved to §14. Deleting the SSM parameter and decommissioning Atlas destroy the
  rollback path, so they must follow both §12's verification and §9's migration. §10 and §11 now
  run last, alongside §14.
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
  and with `--living-branches`, against the post-cutover runs above. The same sweep over migrated
  history is 12b.1 — at this point the table holds only what §13 has written
- [ ] 12.6 **Manual**: download a run's artifacts and confirm `rawData` is recoverable from the result JSON
- [ ] 12.7 **Manual**: confirm a run with `--no-database` succeeds and writes nothing
- [ ] 12.9 **Manual**: `baas admin teardown` and confirm the table survives and is named in the prompt

## 12b. Verification that needs migrated history (after §9)

Split out of §12 when migration moved after the cutover: these two are the only manual checks that
read data §9 puts there, and running them before it would verify against an empty partition.

- [ ] 12b.1 **Manual**: repeat 12.5's `baas results` sweep against migrated history, confirming
  pre-cutover rows appear and that `--tag source=gha-e2e-test` finds the 36 carried-through CI rows
- [ ] 12b.2 **Manual**: confirm a benchmark score is consistent with a pre-change run of the same
  benchmark, stating the observed spread — run-to-run variance is large (CI history spans
  10.0M–29.6M ops/s on one benchmark), so investigate only a difference outside that band
  (was 12.8, which could not run before the history existed in the table)

## 13. Runner cutover (first step after the read path; §9 migrates history afterwards)

The point of no easy return for the runner: after this, new measurements go to DynamoDB and stop
going to Atlas. Atlas is still readable, so rollback is reverting these commits — until §14.

Landing this before §9 is deliberate: it puts live runs through the schema before 121 historical
documents are written into it, and it guarantees no run is ever written to Atlas afterwards, so
there is no window for migration to miss.

- [x] 13.1 Remove `--mongo-uri` from `SetupCommand` and `ConfigSetSubcommand`, and delete the
  SecureString write (was 7.3). The "no MongoDB connection string provided" warning goes with it —
  it named a fallback that no longer exists
- [x] 13.2 Delete `validateMongoUri` from all three classes that carry a copy (was 7.4). Only two
  copies were left — `ConfigSetSubcommand` had already been reduced to a delegating one-liner —
  and `mongoDatabaseName`, its only caller, went with it
- [x] 13.3 Pass the table name through `UserDataScriptBuilder` and remove the SSM connection-string
  fetch (was 7.5) — this is the cutover itself. The table name travels in user-data rather than
  being fetched at boot: unlike the connection string it carries no credentials, and access is
  granted by `RunnerRole`, not by knowing the name. `SSM_PREFIX` went with the fetch — that was
  its only use
- [x] 13.4 Add the `--no-database` pass-through to `baas run` and fail before provisioning when the
  table is unresolvable (was 7.6). Note this makes absent configuration a hard failure, replacing
  today's silent `NoOpDatabaseService` — a breaking change for standalone `benchmark-runner` users.
  Resolution sits beside `resolveProject()`, before the Maven build and the S3 upload, so a
  misconfigured CLI costs an error message rather than a paid instance
- 13.5 removed (2026-08-19). It re-ran the migration to sweep runs made between §9 and the
  cutover; with §9 now *after* the cutover there is no such window. 9.6's idempotency requirement
  stays — it still covers a partial or interrupted migration run
- [x] 13.6 Remove the `mongo.connectionString` line from `ConfigShowSubcommand` output (rest of 7.10).
  That line was the only value `config show` read from AWS, so the command now makes no AWS call
  at all and its operator-credentials warning went too

- [x] 13.7 Repoint `DeployerPreflight`'s `ssm:PutParameter` probe from the Mongo connection string
  to `/<prefix>/runner/ami-id` (gap found by task 1.5, which proposed it as 7.11). Setup writes no
  Mongo parameter after 13.1, so the old probe proved nothing; the AMI pointer is the SSM write the
  deployer still performs. Not deleted, because §14.2 removes the Mongo grant from the deployer
  policy and the old probe would then have failed preflight for a permission nothing uses.
  `TeardownCommand`'s Mongo SSM delete (proposed 7.12) is deliberately left for §14 — it is what
  performs 14.4

## 14. MongoDB removal (only after §12 confirms DynamoDB works and §9 has migrated history)

Held to the very end deliberately. Everything here destroys the rollback path described in
`design.md`: *"between steps 4 and 8, Atlas still holds every pre-cutover measurement and the code
change is revertible."* Landing 14.1 early breaks every
benchmark run outright, since `CLAUDE.md` records that omitting TCP 27017 makes every run fail at
the database write.

- [ ] 14.1 Remove TCP 27017 egress from `RunnerSecurityGroup` (was 4.3)
- [ ] 14.2 Remove every Mongo SSM grant from `deployer-policy.json`, `operator-policy.json`,
  `cf-template-ci.yaml`, and `RunnerRole` (was 4.8)
- [ ] 14.3 Update the §4 template tests that currently pin the presence of 27017 egress and the Mongo
  SSM grants, so they assert absence instead — `runnerCanReachMongoAtlasOnItsStandardPort` is the
  guard that must be inverted, not deleted
- [ ] 14.4 Delete the `/<prefix>/mongo/connection-string` parameter by hand (was 10.1)
- [ ] 14.5 Decommission the Atlas cluster (was 10.2)
