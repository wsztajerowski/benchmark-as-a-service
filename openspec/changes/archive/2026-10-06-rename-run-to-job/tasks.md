# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm the installation holds test data only: a read-only `Scan` of
      `baas-381492019823-results` lists no partition beyond `RUN`, `RESULT#benchmark-as-a-service`,
      `RESULT#baas-e2e` and `RESULT#baas-lifecycle-test`, and the bucket has no prefix outside `runs/`
      and `image-builds/`. Verify by recording both listings here. *Checked 2026-10-06 during the
      proposal (47 items); re-run immediately before teardown.*
      *Re-run 2026-10-06 18:5x before teardown:* unchanged — 47 items (`RUN` 24,
      `RESULT#benchmark-as-a-service` 20, `RESULT#baas-e2e` 2, `RESULT#baas-lifecycle-test` 1); bucket
      prefixes `image-builds/`, `runs/`.
- [x] 1.2 Confirm the deployer policy names no partition key, attribute, index, tag or `runs/` path:
      `grep -inE 'run[^n]|request|index|LeadingKeys' infra/deployer-policy.json` matches only
      `runner` names and `aws:RequestedRegion`. Verify by the grep output.
      *Done 2026-10-06:* the grep matches only `aws:RequestedRegion` (three times); every `run`
      substring is in a `runner` name.
- [x] 1.3 Confirm nothing outside this repository reads the stored names: no other workflow, script
      or consumer in the tree queries `requestId-index`, `pk = RUN` or `runs/`. Verify with a grep
      over `.github/`, `scripts/` and the shell helpers.
      *Done 2026-10-06:* only `.github/workflows/e2e-cloud-test.yml` used the old names (`runId`,
      `runStatus`, `--request-id`, `.requestId`); it is task 7.1.

## 2. Model (`baas-model`)

- [x] 2.1 Rename `RunId`, `RunLayout`, `RunItem`, `RunItemMapper`, `RunStatus` to `Job…`, with their
      tests; `RUNS_PREFIX` value `jobs`; `ResultKeys.RUN_*` → `JOB_*` with values `JOB`; the id
      attribute `requestId` → `jobId` on both mappers. Verify with `mvn -pl baas-model verify`.

## 3. Runner (`benchmark-runner`)

- [x] 3.1 Rename the `--request-id` option to `--job-id` (keep `-id`), every `requestId` field and
      accessor, and `RunLogs` → `JobLogs`, with their tests; the scripts `jmh-with-*.sh` follow.
      Verify with the full reactor `mvn verify` (the runner needs the fixture JARs), `ASYNC_PATH`
      exported.
      *Done 2026-10-06:* full reactor `mvn clean install` green on the final tree, `ASYNC_PATH`
      exported; `JmhWithAsyncProfilerSubcommandServiceIT` ran (1 test, not skipped). Every module
      passes. `jmh-with-*.sh` held no old name.

## 4. CLI (`baas-cli`)

- [x] 4.1 Move package `…baas.runs` to `…baas.jobs` and rename its types (`JobSession`,
      `JobTermination`, `JobRecorder`, `DynamoDbJobRecorder`, `JobListing`) and tests. Verify by
      compiling and the moved tests passing.
- [x] 4.2 Replace the `runs` command group with `jobs` (`JobsCommand`, `JobsListSubcommand`,
      `JobsTerminateSubcommand`), `RunReference` → `JobReference`; `baas runs` no longer parses.
      Verify with the renamed command tests plus one asserting `baas runs` is an unknown command.
      *Done:* `JobsCommandTest.theOldRunsGroupNoLongerParses`.
- [x] 4.3 `baas results --request-id` → `--job-id`, `--all-runs` → `--all-jobs`; `baas run`'s summary
      `runId`/`runStatus` → `jobId`/`jobStatus`; every user-facing message and help text uses "job"
      and `jobs/…` paths. Verify with `RunCommandSummaryTest` and the results command tests.
- [x] 4.4 User-data and provisioning: `REQUEST_ID` → `JOB_ID`, `RUN_SORT_KEY` → `JOB_SORT_KEY`,
      `RUN_STATUS_GUARD*` → `JOB_STATUS_GUARD*`, `run_status` → `job_status`, runner flag
      `--job-id`, manifest key `jobId`, `MANIFEST_SCHEMA_VERSION` 4 → 5, EC2 tag `baas-job-id`.
      Verify with `UserDataScriptBuilderTest` (including `bash -n`, the function-before-watchdog
      test and `aLargeRunStaysWellUnderTheUserDataLimit`) and `Ec2ProvisioningServiceTest`.

## 5. Infrastructure

- [x] 5.1 `cf-template-core.yaml`: GSI `jobId-index` keyed on `gsi1pk`/`gsi1sk` as before;
      `LeadingKeys` `RUN` → `JOB` for the runner and operator statements; comments and statement ids
      naming run items. `infra/operator-policy.json` follows. `GroupDescription` of
      `RunnerSecurityGroup` untouched. Verify with `CoreTemplateTest` (the lifecycle pin now on
      `jobs/`), `OperatorPolicyDriftTest` and `DeployerPolicyTest` unchanged.

## 6. Grep gate

- [x] 6.1 No noun use of "run" survives in live code, infra, CI or scripts:
      `git grep -nE 'runId|RunId|requestId|RequestId|request-id|"RUN"|= RUN\b|\bruns/|RunItem|RunLayout|RunStatus|RunSession|RunReference|RunsCommand|baas runs|all-runs|run_status|RUN_SORT|RUN_STATUS'`
      outside `openspec/changes/archive/`, `docs/adr/`, this change and `jobs-command`'s exploration
      returns nothing; every remaining hit is listed here with why it stays. Verify by the command
      output.
      *Done 2026-10-06.* Remaining hits, each kept on purpose:
      - `RunCommand`'s `awsRequestId` — the AWS SDK's request id in `launch-error.txt`, not ours.
      - `cf-template-core.yaml`: "the deleted `runs/` expiry rule" — the name of a rule that no
        longer exists.
      - `docs/superpowers/specs/2026-08-10-…` — a historical design document, like an ADR.
      - `openspec/changes/detached-run/`, `openspec/changes/export-before-teardown/` — planning
        notes of later changes, re-read when each is picked up; renaming them now would rewrite
        their recorded analysis.
      Also kept: `RunCommand` (the verb), `RunInstances` (EC2's API), `RunLast` (picocli),
      `JmhRunResults` (a JMH run), status values such as `running`.

## 7. Docs, specs and CI

- [x] 7.1 `e2e-cloud-test.yml` reads `jobStatus`/`jobId` and uses `--job-id`. Verify with
      `actionlint` (or a YAML load) and a grep.
- [x] 7.2 CLAUDE.md: rename throughout (S3 layout table, results-table key table, *Run items* →
      *Job items*, termination layers, invariants); delete "Two id shapes coexist", the
      `RESULT#unknown` / `unknown-migrated` notes and the `run-status` object row; "CI job" for
      GitHub's. Verify with the 6.1 grep over CLAUDE.md and a read of each touched section.
- [x] 7.3 README.md, `infra/README.md`, `docs/review/open-findings.md` (the *runs-command* section
      becomes *jobs-command*), `openspec/changes/QUEUE.md` (`rename-run-to-job` at 1, `jobs-command`
      at 2). Verify with the 6.1 grep.
- [x] 7.4 `docs/diagrams/*.mmd`: rename, then render every edited file with
      `mmdc -i docs/diagrams/<file>.mmd -o <scratch>/<file>.png` and look at each PNG. Verify by
      having opened every render.
      *Done 2026-10-06:* `baas-runs.mmd` → `baas-jobs.mmd`, `baas-states-run.mmd` →
      `baas-states-job.mmd`. All 17 edited diagrams render. Looked at: `baas-jobs`,
      `baas-states-job`, `c4-3-component-cli`, `baas-run`, `baas-download-env-diff` — the files
      with structural renames (participants, component ids, file names). The other twelve
      changed label text only and were checked by rendering, not by eye — a deviation from the
      rule, recorded here.

## 8. Rebuild the installation (design D4)

- [x] 8.1 Re-run 1.1; confirm nothing is in flight (`baas runs list --in-flight` with the old CLI).
      *Done:* the 1.1 listing unchanged; no `benchmark-runner` instance pending or running (EC2
      query — release 5.0.0, the installed CLI, has no `runs` command).
- [x] 8.2 `baas admin teardown --delete-bucket --yes`, then delete the retained table with
      `aws dynamodb delete-table` under the deployer profile. Verify with `describe-stacks` and
      `describe-table` both reporting not found.
      *Done:* teardown emptied and deleted the bucket, deleted the stack and retired
      `ami-09d0ca3128f2792c0`; `delete-table` under `baas-admin`; both describes report not found.
- [x] 8.3 From the branch build: `baas admin setup` (region and federation values as before), then
      `baas admin build-image`, then `baas config sync --name baas-381492019823`. Verify: the stack
      is `CREATE_COMPLETE`, the table has `jobId-index`, `baas admin image` shows the image.
      *Done:* setup with `--github-org wsztajerowski --github-repo benchmark-as-a-service
      --oidc-provider-arn …` (the deployed values); the table's only index is `jobId-index`; the
      operator role kept its ARN; `build-image` published 1.3.0 as `ami-0fce89356196866ea` (same
      parent `ami-070cc8ab883065d64`, no extension); `config sync` adopted it.

## 9. End-to-end verification (manual — no automated test covers the `baas run` path)

At most **5 paid runs**; the image bake and the PR's CI e2e jobs do not count. Record each job id,
wall time and outcome. No score comparison: no measurement path changes (design D7), and there is no
history left to compare with.

- [x] 9.1 Run 1: `baas run --runner-jar … --benchmark-jar fake-jmh-benchmarks/… --project baas-e2e
      --tag exclude_from_results=true jmh-with-async -- …`. Verify: summary `jobId` / `jobStatus`
      `completed`; `baas jobs list` shows it; `baas results --job-id <id>` returns its rows;
      `baas download <id>` holds `environment.json` with `jobId` and `schemaVersion` 5, the async
      artifacts and `jmh-result.json` under `jobs/baas-e2e/<id>/`; the instance carried
      `baas-job-id`.
      *Done (paid 1/5):* `20261006T165241024Z-47516b65`, 80 s, `jobStatus` `completed`. `jobs list`
      shows it; `results --job-id` one row; `download` 10 artifacts under `jobs/baas-e2e/<id>/`
      (flamegraphs, JFR, `jmh-result.json`, `input/`); manifest has `jobId`, no `requestId`; the
      instance carried `baas-job-id`. **It found a miss:** the manifest still wrote
      `schemaVersion` 4 — task 4.4's bump had not been made. Fixed in `9574877`.
- [x] 9.2 Run 2: `jcstress -- --mode sanity`. Verify: one JCStress item, `jobStatus` `completed`.
      *Done (paid 2/5):* `20261006T165527798Z-9b0265b7`, 66 s, `completed`; one JCStress item;
      manifest `schemaVersion` 5 with `jobId` — confirms the fix.
- [x] 9.3 Run 3: launch a long `jmh` job and stop it with `baas jobs terminate <id> --yes`. Verify:
      status `cancelled` recorded, instance terminated, boot log uploaded.
      *Done (paid 3/5):* `20261006T165701309Z-f8b7cffb`, terminated while `running`: status
      `cancelled`, instance `shutting-down`, the attached CLI printed `jobStatus` `cancelled` and
      exited 1. The task's "boot log uploaded" was a wrong expectation: an operator's
      `jobs terminate` kills the instance outright, and only a self-terminating instance ships
      its log. Unchanged behaviour, not a regression.
- [x] 9.4 Not a paid run: `baas env diff` of runs 1 and 2 by job id. Verify it resolves both through
      `jobId-index`. (No paid run — reuses 1 and 2.)
      *Done:* `env diff` of runs 1 and 2 by job id resolved both through `jobId-index`; it warned
      on the 4 vs 5 schema difference, as designed.
- [x] 9.5 Push, open the PR into `next-release`; both CI e2e jobs green. Record the workflow run.
      *Done:* PR #80; every check green — e2e workflow run 37500704468 (`Benchmark on EC2` 1 m 50 s,
      `JCStress on EC2` 2 m 17 s), `build` 6 m 32 s, both `install.sh` jobs.
- [x] 9.6 Runs 4–5 are reserve, spent only to re-check a fix found by 9.1–9.5. Record whether they
      were used.
      *Done:* not used — 3 of the 5 paid runs spent, the reserve untouched.

## 10. Verify, merge, archive

- [x] 10.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…) and any
      deviation from the design or tasks.
      *Done 2026-10-06:* `verify.md` — no critical issue; W1–W7.
- [x] 10.2 Rebase-merge into `next-release`; archive the change and make the editorial commit of
      design D5 (`archive-renames.md`: capability directories, scenario titles, Purpose sections).
      Verify with `openspec validate --specs` and the 6.1 grep over `openspec/specs/`.
      *Done 2026-10-06:* PR #80 rebase-merged into `next-release` (head `98919f2`, all checks green
      twice); archived with every delta block landing verbatim; D5's editorial step in the archive commit.
