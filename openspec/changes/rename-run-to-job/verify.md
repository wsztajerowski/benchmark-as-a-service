# Verify — rename-run-to-job

Run 2026-10-06 on `rename-run-to-job` at `9574877` + the task records, after PR #80's checks passed.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 25/27 tasks; the two open ones are this verify (10.1, closed by this file) and merge + archive (10.2) |
| Correctness | 82/82 MODIFIED requirements map to renamed code; every job-named token in the deltas exists in code, infra or CI; no test lost (763 → 764 `@Test`) |
| Coherence | D1–D4, D6–D10 followed; D5 is an archive-time step; D7 was missed and caught live (W6) |

`openspec validate rename-run-to-job`: valid.

## Requirement → code → test → gap

A rename changes names, not behaviour, so the table is per capability: what the delta renames,
where the new name lives, and what proves it end to end.

| Capability (delta) | New names | Code | Tests | Live | Gap |
|---|---|---|---|---|---|
| `run-tracking` (12 req) | `baas jobs list/terminate`, `pk = JOB`, `gsi1sk = JOB`, `jobId`, `baas-job-id`, summary `jobId`/`jobStatus` | `JobsCommand`, `JobsListSubcommand`, `JobsTerminateSubcommand`, `jobs/*`, `JobItemMapper`, `ResultKeys.JOB_*`, `Ec2ProvisioningService.JOB_ID_TAG` | `JobsCommandTest` (incl. `theOldRunsGroupNoLongerParses`), `JobSessionTest`, `JobTerminationTest`, `JobListingTest`, `DynamoDbJobRecorderIT`, `JobItemMapperTest`, `RunCommandSummaryTest` | 9.1–9.3: listed, `cancelled` recorded by `jobs terminate`, tag `baas-job-id` on the instance | Scenario titles (W1) |
| `run-identity` (5) | `jobId`, `jobs/<project>/<jobId>/`, `JobLayout`/`JobId` | `baas-model` `JobId`, `JobLayout` | `JobIdTest`, `JobLayoutTest` | prefixes `jobs/baas-e2e/<id>/` | W1 |
| `run-artifact-layout` (5) | the `jobs/` tree, `input/` inside it | `JobLayout`, `RunCommand` uploads | `JobLayoutTest`, `S3DownloadIT`, `CoreTemplateTest` (no lifecycle rule under the job tree) | 9.1 download: 10 artifacts incl. `input/` | W1 |
| `results-store-schema` (11) | attribute `jobId`, `jobId-index`, `JOB` items excluded from readers | `MeasurementItemMapper`, `JobItemMapper`, `ResultKeys`, `ResultsQueryService` | `MeasurementItemMapperTest`, `ResultKeysTest`, `ResultsQueryServiceIT`, store contract suite | `results --job-id` returned the job's rows | — |
| `benchmark-results-query` (5) | `--job-id`, `--all-jobs`, JSON/CSV `jobId`, column `JOB_ID` | `ResultsCommand`, `ResultsTable`, `ResultRow` | `ResultsFormatTest`, `ResultsProjectSelectionTest`, `ResultsFiltersTest` | CI e2e asserts `--job-id` and `--all-jobs` | — |
| `cli-command-structure` (9) | `jobs` group replaces `runs`; download/env diff take a job id | `BaasApp` subcommands, `JobReference`, `DownloadCommand`, `EnvDiffSubcommand` | `JobsCommandTest`, `JobReferenceTest`, `DownloadArgumentTest` | 9.4 `env diff` by id | W3 |
| `core-stack-provisioning` (13) | `jobId-index`, `LeadingKeys = JOB`, active-job gate | `cf-template-core.yaml`, `operator-policy.json`, `TeardownCommand` | `CoreTemplateTest`, `OperatorPolicyDriftTest`, `TeardownNoticeTest` | setup created `jobId-index`; runner and operator writes accepted | — |
| `ci-benchmark-execution` (9) | CI reads `jobId`/`jobStatus`; "CI job" | `e2e-cloud-test.yml` | — (the workflow is the test) | run 37500704468 both jobs green | — |
| `runner-image-provisioning` (7) | manifest `jobId`, `schemaVersion` 5; `env diff` by job id | `UserDataScriptBuilder` | `UserDataScriptBuilderTest` (`bash -n`, function-before-watchdog, size) | 9.2 manifest `schemaVersion` 5, `jobId` | W6 |
| `runner-jar-distribution` (4), `jcstress-execution` (1), `cli-console-output` (3), `cli-distribution` (1) | wording only | — | unchanged suites green | 9.2 JCStress | — |

## Design adherence

- **D1** — no `runner` identifier changed (diff of `runner` tokens: only test-method names lost
  their "run" part); `deployer-policy.json` untouched, and setup/build-image/teardown all ran under it.
- **D2** — `jobId-index`, `JOB`, `jobId`, `baas-job-id`, `--job-id`, `--all-jobs`, `-id` kept.
- **D3/D4** — wiped and rebuilt before merge (tasks 8.x); the PR's e2e ran against it.
- **D5** — pending: capability directories and scenario titles at archive (`archive-renames.md`).
- **D6** — `JobReference` still accepts a literal path.
- **D7** — followed only after 9.1 (W6).
- **D8** — status strings identical.
- **D9** — map followed; `RunCommand`, `RunInstances`, `RunLast`, `JmhRunResults`, `awsRequestId`
  deliberately kept.
- **D10** — "CI job" in CLAUDE.md, both templates' comments, the CI-related tests and the e2e workflow.

## Warnings

- **W1** — Main-spec scenario titles and the three capability directories still say "run" until the
  D5 editorial commit. Expected; closed by task 10.2.
- **W2** — 7.4: of 17 rendered diagrams, 5 were looked at; 12 label-only edits were checked by render
  alone. A deviation from CLAUDE.md's rule, recorded rather than hidden.
- **W3** — `download`/`env diff` keep the literal-result-path argument, now dead after the wipe
  (design D6). Decided in `jobs-command`.
- **W4** — `openspec/changes/detached-run/` and `export-before-teardown/` and the historical
  `docs/superpowers/specs/2026-08-10-…` still use the old names (6.1 allow-list). Each is re-read
  when its change is picked up.
- **W5** — Task 9.3 expected a boot log after `jobs terminate`; an operator termination never
  uploads one. The task text was wrong, the behaviour unchanged.
- **W6** — Task 4.4 was ticked with the `schemaVersion` bump unmade; paid run 1 wrote 4. Fixed in
  `9574877` and confirmed by run 2. Lesson: tick a multi-part task only after checking every part.
- **W7** — Test helpers still named `run(...)` (returning `JobItem`) in a few test classes, and test
  comments were reviewed less exhaustively than main code. Cosmetic; no reader depends on them.

## Deviations from design or tasks

- The literal-path removal moved from this change to `jobs-command` (D6), during the proposal.
- Paid runs: 3 of 5 used.
