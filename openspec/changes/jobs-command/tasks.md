# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 picocli can register one command class at two places (`jobs run` and root `run`; `results
      query` and root `query`) and render a custom help section in place of the command list. Verify
      with a throwaway test that parses both spellings to the same options and renders the section.
      *Done:* `CommandTreeTest` (one class registered under its noun and at the root, both parse
      alike) and `HelpAndHintsTest` (the custom command-list section replaces picocli's).
- [ ] 1.2 Changing `DeletionPolicy`/`UpdateReplacePolicy` from `Retain` to `Delete` on the existing
      bucket and table is an in-place stack update, not a replacement. Verify from the CloudFormation
      documentation for both resource types, and by the change set of task 14.1 showing no
      `Replacement`.
- [x] 1.3 `packages.txt` lines parse as `name-version-release.arch`. Verify against the
      `packages.txt` of a job downloaded from the current deployment: every line parses, or the
      exceptions are listed here with how they are reported.
      *Done 2026-10-07:* the 521-line `packages.txt` of job `20261006T165241024Z-47516b65`: every
      line parses as `name-version-release.arch` except `gpg-pubkey-d832c631-6515c85e` (no arch);
      the parser falls back to `name-version-release`, then to the whole line as the name. No name
      repeats there, but installonly packages (kernel) can, so a name maps to a set of versions.
- [x] 1.4 `sts:GetCallerIdentity` succeeds for an identity holding no policy at all, so setup can
      always render. Verify from the STS documentation.
      *Done:* AWS STS documents `GetCallerIdentity` as requiring no permissions — it succeeds even
      when an explicit deny is attached.

## 2. Command tree (D1)

- [x] 2.1 Root: `jobs`, `results`, `config`, `admin`, plus the aliases `run` and `query`; `download`
      and `env` removed. Verify with `JobsCommandTest`-style parse tests: `baas download`, `baas env`,
      `baas list`, `baas show` are unknown commands.
      *Done:* `CommandTreeTest` (17 cases incl. the removed spellings).
- [x] 2.2 `jobs` gains `run` (the existing `RunCommand`), `show`, `diff`, `download`; `results` becomes
      a noun with the single verb `query`; a bare noun prints usage. Verify with tests that
      `baas jobs run …` and `baas run …` parse identically, and that `baas results` and `baas jobs`
      print usage and execute nothing.
      *Done:* `CommandTreeTest` (both spellings of `run` and `query`, bare nouns print usage, `jobs`
      lists run, list, show, diff, download, terminate).
- [x] 2.3 `admin` gains the nouns `deployment` (`setup`, `teardown`) and `image` (`build`, `show`);
      `admin setup|teardown|build-image|deployer-policy` and verb-less `admin image` are gone. Verify
      with parse tests for both new and removed spellings.
      *Done:* `CommandTreeTest`, `ImageCommandsTest`, `AdminProfileOptionTest`, `BaasAppTest`.

## 3. Naming a deployment (D10)

- [x] 3.1 A global, inherited `--deployment <name>` that must equal the configured deployment;
      `teardown --stack-name` and `config sync --name` removed, `config sync --deployment` required as
      `--name` was. Verify with tests: a matching name passes, another fails naming both before any AWS
      call, the removed options are unknown.
      *Done:* `BaasApp.deploymentRefusal`, checked in the execution strategy before any command;
      `config sync` and `admin deployment setup` check the name themselves. `DeploymentOptionTest`,
      `ConfigSyncSubcommandTest`, `TeardownNoticeTest`.

## 4. `results query` (D2, D3, D4)

- [x] 4.1 Default lists every measurement; `--show-excluded` replaces `--all-jobs`; `--group-by` and
      `--all-jobs` removed; `--job-id` combines with every filter except `--project`/`--all-projects`.
      Verify with `ResultsQueryServiceIT` and the results command tests.
      *Done:* `OptionValidationTest` (removed options unknown, `--job-id` refuses only the partition
      options), `ResultsQueryPipelineTest` (every measurement by default).
- [x] 4.2 `--best-per <tag>` with the JMH mode in the group key and the direction per mode (highest for
      `thrpt`, lowest for `avgt`/`sample`/`ss`, non-finite never wins). Verify with `ResultsGroupingTest`
      cases for each mode and for one benchmark measured in two modes.
      *Done:* `ResultsGroupingTest` — lower wins for `avgt`/`sample`/`ss`, two modes never compared,
      NaN never wins; `ResultsQueryPipelineTest.bestPerKeepsOneRowPerBranch`.
- [x] 4.3 `--exclude-tag k=v` (repeatable, any match drops). Verify with `ResultsFiltersTest`.
      *Done:* `ResultsFiltersTest` (any pair drops a row; a list, so one key may repeat);
      `ResultsQueryPipelineTest.anExcludedTagIsDroppedBeforePaging`; a malformed pair exits 2.
- [x] 4.4 The pipeline: sort (default newest first; `--sort-by created|benchmark|score|project`,
      `--asc`), then `--offset`, then `--limit` (default 20, `0` none) with the stderr notice. Verify
      with tests for the default 20-of-30 cut, an offset page, and a non-default sort.
      *Done:* `ResultsQueryPipelineTest` (20 newest of 30, offset 20 → the 10 oldest, `--limit 0`,
      `--sort-by score --asc`); `ResultsGroupingTest` for the comparators.
- [x] 4.5 `--watch` keeps its behaviour under `results query`. Verify with the existing watch tests,
      renamed.
      *Done:* the watch tests (`ConsoleOutputTest`, `ResultsProjectSelectionTest`) pass under `query`.

## 5. `jobs list` (D4)

- [x] 5.1 The pipeline (`--sort-by created|status|project`, `--asc`, `--offset`, `--limit` default 20 /
      `0`), `--exclude-tag`, and `--watch` (interactive only, refused with a machine format). Lazy
      paging stays for the default order only. Verify with `JobListingTest` and `JobsCommandTest`.
      *Done:* `JobListingTest` (exclusion, ordering), `JobsCommandTest` (20 of 30 with the note, offset,
      `--limit 0`, `--watch` refused without a terminal). Deviation: every matching job is read, not
      lazily under the default order (design D4 updated). `--watch` shares `console.Watch` with
      `results query`.

## 6. `jobs show` (D5)

- [x] 6.1 Job, Environment (by group) and Artifacts (one `ListObjectsV2`, summarised by folder)
      sections; resolved status; stated absences; an unknown id fails before any S3 read; `--format
      json` → `{job, environment, artifacts}`. Verify with a command test over stubbed AWS for a
      completed, a `launch-failed` and an unknown job, and an IT listing a LocalStack prefix.
      *Done:* `JobsShowCommandTest` over a `Sources` seam — completed, `launch-failed`, vanished,
      unknown id (no S3 read), JSON shape. The S3 listing it uses (`S3UploadService.listKeys`) is
      the one `S3DownloadIT` already exercises against LocalStack; no separate IT was added.

## 7. `jobs download` (D8)

- [x] 7.1 Move `DownloadCommand` under `jobs`; `JobReference` accepts a job id only; a path fails
      stating that a job id is required. Verify with `DownloadArgumentTest`, `JobReferenceTest` and
      `S3DownloadIT`.
      *Done:* `JobReference` is id-only (`notAJobId` before any AWS call); `DownloadArgumentTest`
      (a path exits 2 before the config is read), `JobReferenceTest`. `S3DownloadIT` runs at the full build.

## 8. Manifest and `jobs diff` (D6, D7)

- [x] 8.1 `UserDataScriptBuilder`: nested heredoc in seven groups, the seven fields dropped,
      `MANIFEST_SCHEMA_VERSION` 6; values still captured into variables first; tags from the same
      variables. Verify with `UserDataScriptBuilderTest` — `bash -n`, the rendered JSON parses with
      exactly the eight members, `aLargeRunStaysWellUnderTheUserDataLimit` — and the
      tags-agree-with-manifest test.
      *Done:* `UserDataScriptBuilderTest` — the rendered manifest parses with exactly the eight
      members and each group's fields; `theManifestCarriesNoJobIdentity`; schema 6; `cpuArch` tag and
      `cpu.arch` from one variable. `PROJECT_NAME`, `BRANCH_NAME`, `AWS_CLI_VERSION` removed.
- [x] 8.2 `EnvironmentManifest` reads the nested form; `jobs diff` prints `Differs in:` then fields by
      group, the same-environment message, the schema-mismatch warning, and the AMI-gated packages
      section (changed/added/removed). Verify with `EnvironmentManifestTest`, a packages-diff unit test
      on two fixture lists, and `ConsoleOutputTest` for the plain table.
      *Done:* `EnvironmentManifestTest` (nested parse, groups in order, flat manifests still diff,
      schemaVersion never a difference), `PackagesDiffTest` (arch-less key, installonly versions),
      `ConsoleOutputTest` (Differs in, grouped table, packages section, identical message),
      `JobsDiffCommandTest` (fail fast on the first unknown id).

## 9. Setup renders the policy (D11)

- [x] 9.1 `admin deployment setup` renders the policy first and, on missing rights, prints it to
      stdout, names the missing actions on stderr, creates nothing, exits non-zero; `DeployerPolicyCommand`
      and `--for-account` removed. Verify with `SetupCommandTest` (stubbed simulator: denied → policy
      on stdout and no CloudFormation call; allowed → proceeds; not checkable → proceeds) and
      `DeployerPolicyTest` unchanged.
      *Done:* `SetupCommand.refusalForMissingRights` (policy on stdout as the only payload, missing
      actions on stderr, exit 1; `null` when nothing was denied or the simulator was unavailable) with
      two `SetupCommandTest` cases; `DeployerPolicyCommand` and its test deleted; `DeployerPolicyTest` and
      `DeployerPolicyRendererTest` unchanged and green. Deviation: the bucket-region lookup stays before
      the policy step (U23's reason); the spec delta now says so. The stubbed-simulator command test
      was replaced by the method test — `call()` builds its AWS clients inline.

## 10. Teardown removes everything (D12)

- [x] 10.1 `cf-template-core.yaml`: `DeletionPolicy`/`UpdateReplacePolicy: Delete` on the bucket and the
      table; the lifecycle rules unchanged. Verify with `CoreTemplateTest` (new pins; the no-expiry pin
      kept; `theRunnerSecurityGroupDescriptionIsNeverEdited` still green).
      *Done:* `CoreTemplateTest` pins `Delete` on both (`theWorkingBucketIsDeletedWithTheStack`,
      `theResultsTableIsDeletedWithTheStack`); the no-expiry and `GroupDescription` pins unchanged, green.
- [x] 10.2 Teardown always empties the bucket, deletes the stack, retires the image, reports nothing
      retained, and warns before the prompt that everything will be deleted; `--delete-bucket` and
      setup's retained-resource pre-checks removed. Verify with `TeardownNoticeTest`, `SetupCommandTest`.
      *Done:* teardown warns before the prompt (`everythingGoesNotice`), always empties the bucket and
      stops before the stack if it cannot, then deletes the stack and retires the image; `--delete-bucket`,
      the retention report and setup's retained-table check and same-region bucket message removed
      (`ResultsTableService` deleted as unused). The other-region bucket check (U23) stays.
      `TeardownNoticeTest`, `SetupCommandTest`.

## 11. Help and hints (D9)

- [x] 11.1 Grouped `baas --help` (shortcuts, operator, deployer with full paths, role headings, no
      config keys; first-run guidance `admin deployment setup` → `admin image build`) and `admin --help`
      listing its two nouns. Verify with a help-rendering test pinning the three sections.
      *Done:* `BaasApp.commandLine` installs `commandMap` in place of picocli's command list, read from
      the live tree; `HelpAndHintsTest` pins the order, the role headings, no config keys, and the
      first-run guidance. Rendered and checked by eye from the packaged JAR.
- [x] 11.2 Hints after `jobs run` (completed / failed), `jobs show`, `jobs list --in-flight`, an empty
      `results query`, and `admin deployment setup`, on stderr, interactive only. Verify with tests for
      an interactive and a non-interactive console.
      *Done:* `console.Hints` (interactive gate, `→ purpose: command`, at most two). Shown after
      `jobs run` (completed / failed), `jobs show`, `jobs list` with a job in flight, and an empty
      `results query`; setup keeps its existing next-steps prose. `HelpAndHintsTest` covers the gate.

## 12. Grep gate

- [x] 12.1 No live code, script, workflow or doc calls a removed spelling:
      `git grep -nP 'baas (download|env|results( |$)(?!query))|admin (setup|teardown|build-image|deployer-policy)|--stack-name|sync --name|--all-jobs|--group-by|--for-account|--delete-bucket'`
      outside archived changes and ADRs returns nothing, or each remaining hit is listed here with why.
      *Done 2026-10-07* (ERE, since this git lacks PCRE; `baas results query` filtered out after).
      Remaining hits, each kept on purpose: `CommandTreeTest`, `DeploymentOptionTest`,
      `OptionValidationTest`, `TeardownNoticeTest` pin the removed spellings as unknown;
      `infra/README.md`'s two `--stack-name` are the AWS CLI's own flag; `infra/runner-image.yaml`'s
      comments name `build-image`/`env diff` but any edit there changes the base component and needs an
      `imageVersion` bump, so they wait for the next base bump; `openspec/specs/` changes at archive.
      The mechanical pass once rewrote an `aws cloudformation … --stack-name` too — caught and restored.

## 13. CI, docs, findings

- [x] 13.1 `e2e-cloud-test.yml`: `results query`, `--show-excluded`, `jobs download`. Verify with
      `actionlint` or a YAML load and the 12.1 grep.
      *Done:* `results query` (`--limit 0` for the sweep, `--show-excluded`), `jobs download` by id
      (also on the failure path, which used the result path), `config sync --deployment`. Validated
      with a YAML load (Ruby; no actionlint or PyYAML on this machine).
- [x] 13.2 CLAUDE.md: the command names everywhere; the retention invariants rewritten ("nothing
      survives a teardown"); the setup/teardown pre-check paragraphs; the S3 layout table's
      `environment.json` row (groups, schema 6); the policy paragraphs (setup prints it). Verify with
      the 12.1 grep over CLAUDE.md and a read of each touched section.
      *Done:* mechanical command renames plus the rewritten retention, policy, `environment.json`,
      `--best-per` and command-tree paragraphs; a new paragraph records the alias rule.
- [x] 13.3 README.md and infra/README.md (attach steps around setup's output; command names). Verify
      with the 12.1 grep.
      *Done:* README (setup prints the policy, `query` table, `jobs diff` example, teardown) and
      infra/README (no Retain, setup renders the policy, the by-hand dev deployment fills the template
      with `sed` until `multiple-deployments`).
- [x] 13.4 `docs/diagrams/*.mmd`: command names; `baas-jobs-show-diff.mmd` becomes
      `baas-jobs-show-diff.mmd`; teardown and setup sequences. Render every edited file with `mmdc` and
      look at each PNG.
      *Done:* 13 diagrams edited, `baas-download-env-diff.mmd` → `baas-jobs-show-diff.mmd` (now with
      `jobs show`). All render; four first failed on `;` in sequence text and a stray `end`, fixed.
      Looked at: jobs-show-diff, teardown, setup, lifecycle, states-deployment, states-workflow; the
      rest were label edits checked by render.
- [x] 13.5 `docs/review/open-findings.md`: delete P11 and U28 (closed); delete U12 (no retained table);
      reduce U2 to exporting before teardown; record the best-per-group defect as fixed by this change
      in the closing note. `QUEUE.md`: `jobs-command` done, `multiple-deployments` next. Verify by
      reading both files.
      *Done:* P11, U28 and U12 deleted; U2 reduced to exporting before a teardown; QUEUE has
      `jobs-command` then `multiple-deployments`. The best-per-group defect was never filed — it is
      recorded in `verify.md` as found and fixed here.

## 14. Deployment and verification (manual — no automated test covers the `baas run` path)

At most **5 paid runs**, the same budget as `rename-run-to-job`; the image bake and the PR's CI e2e do
not count. No score comparison: no measurement path changes (D7).

- [ ] 14.1 `baas admin deployment setup` from the branch build on the existing deployment; the change set
      shows the deletion-policy change with no replacement. Record it.
- [ ] 14.2 Paid run 1: `baas run jmh-with-async …` (alias). Then `baas jobs show <id>` (three sections,
      grouped environment, schema 6), `baas query --job-id <id>` and `--best-per branch`,
      `baas jobs download <id>`, `baas jobs list --sort-by status`. Record each.
- [ ] 14.3 Paid run 2: `baas jobs run jcstress … -- --mode sanity`. Then `baas jobs diff` of runs 1 and 2
      ("Differs in" without `packages`, same AMI).
- [ ] 14.4 Setup's policy step live: run `admin deployment setup` under the operator profile (lacking the
      deployer policy) and confirm stdout holds the policy, nothing is created, exit non-zero. No paid run.
- [ ] 14.5 Teardown removes everything: `baas admin deployment teardown --yes`, then confirm the stack,
      bucket, table, AMI and pointer are gone; `admin deployment setup` and `admin image build` to
      restore. No paid run (the bake is not counted).
- [ ] 14.6 Paid run 3 on the restored deployment, then push, open the PR into `next-release`, both CI
      e2e jobs green. Runs 4–5 are reserve for re-checking fixes.

## 15. Verify

- [ ] 15.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…) and any deviation
      from the design or tasks.
