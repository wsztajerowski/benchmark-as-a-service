# Verify — jobs-command

Run 2026-10-07 on branch `jobs-command` after the live verification (tasks §14), PR #81 open.
`openspec validate jobs-command`: valid. Full reactor `mvn clean install` with `ASYNC_PATH` exported:
green — model 73, runner 44 + 18, CLI 684 unit + 40 IT.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 35/36 tasks; the open one is this verify (15.1). 17 ADDED, 61 MODIFIED, 9 REMOVED requirements across 9 capabilities |
| Correctness | Every ADDED requirement maps to code and a test; MODIFIED requirements are command renames in text plus the behaviour rows below; 3 of 20 paid runs exercised the `baas run` path live |
| Coherence | D1–D14 followed, with three recorded deviations (W2, W3, W4) |

## Requirement → code → test → gap

| Requirement (ADDED unless noted) | Code | Tests | Live | Gap |
|---|---|---|---|---|
| Commands are nouns with verbs, one-noun aliases only | `BaasApp` subcommands, `JobsCommand`, `ResultsCommand`, `admin/DeploymentCommand`, `admin/AdminImageCommand` | `CommandTreeTest` (17) | `baas run` and `baas jobs run` both launched jobs | — |
| Top-level help is grouped by workload | `BaasApp.commandLine`, `commandMap` | `HelpAndHintsTest` | rendered from the packaged JAR | — |
| Next-step hints shown to people, never to scripts | `console.Hints`; `RunCommand.hints`, `JobsShowSubcommand`, `JobsListSubcommand`, `ResultsQuerySubcommand` | `HelpAndHintsTest` (gate) | non-interactive runs printed none | W5 |
| A deployment is named only by `--deployment` | `BaasApp.deploymentRefusal`, `ConfigSyncSubcommand`, `SetupCommand` | `DeploymentOptionTest`, `ConfigSyncSubcommandTest` | `config sync --deployment` after the rebuild | — |
| Filter options mean the same on every command | `--project` help text on both listings; `--exclude-tag`, `--limit`, `--offset`, `--sort-by`, `--asc` on both | `OptionValidationTest`, `JobsCommandTest` | — | — |
| Listings share one ordering and paging pipeline | `ResultsGrouping.sorted`, `JobListing.sorted`, `fetch`/`page` | `ResultsQueryPipelineTest`, `ResultsGroupingTest`, `JobsCommandTest` | `jobs list --sort-by status` | W3 |
| `results query` lists every measurement by default | `ResultsQuerySubcommand.fetch` | `ResultsQueryPipelineTest` | `query --project baas-e2e --show-excluded` | — |
| The best score per group only on request (mode + direction) | `ResultsGrouping.bestPerGroup`, `LOWER_IS_BETTER` | `ResultsGroupingTest` (avgt/sample/ss, two modes) | `--best-per branch` | — |
| Tags can exclude rows | `ResultsFilters.byExcludedTags`, `JobListing.filter` | `ResultsFiltersTest`, `JobListingTest` | — | — |
| `jobs run` launches a job, `run` is its alias | `RunCommand` registered twice | `CommandTreeTest` | runs 1–3 | — |
| `jobs show` reports one job's execution | `JobsShowSubcommand` | `JobsShowCommandTest` (5) | run 1: three sections, JSON shape | W6 |
| `jobs download` fetches one job's artifacts | `JobsDownloadSubcommand`, `JobReference` | `DownloadArgumentTest`, `JobReferenceTest`, `S3DownloadIT` | run 1: 10 artifacts; a path exits 2 | — |
| Every job records its environment, grouped | `UserDataScriptBuilder` heredoc, schema 6 | `UserDataScriptBuilderTest` | runs 1–3 wrote schema 6 | — |
| Two jobs' environments compared group by group | `JobsDiffSubcommand`, `EnvironmentManifest.diff`, `PackagesDiff` | `EnvironmentManifestTest`, `PackagesDiffTest`, `ConsoleOutputTest`, `JobsDiffCommandTest` | runs 1 vs 2 | W1 |
| Teardown removes every deployment resource | template `DeletionPolicy: Delete`, `TeardownCommand` | `CoreTemplateTest`, `TeardownNoticeTest` | 14.5: stack, bucket, table, AMI, pointer gone | — |
| The working bucket never expires job artifacts | template lifecycle rules | `CoreTemplateTest` | — | — |
| Setup renders the deployer policy and stops when lacking it | `SetupCommand.refusalForMissingRights`, `printPolicy` | `SetupCommandTest` (2) | 14.4 (AccessDenied path) | W2 |
| MODIFIED: teardown safety gates, results table configuration, policy out-of-band / resource-scoped, `jobs list`, `jobs` group, excluded results, results filters, `jobs diff` command, admin grouping, watch | as above | as above | as above | W7 |
| REMOVED (9) | deleted: `EnvCommand`, `DeployerPolicyCommand`, `ResultsTableService`, `--delete-bucket`, retained-resource notices and pre-checks, literal-path branch | `CommandTreeTest`, `OptionValidationTest`, `TeardownNoticeTest` pin their absence | — | — |

## Design adherence

D1–D14 followed. Deviations, each recorded in `tasks.md` and here:

- **D4** — `jobs list` reads every matching job instead of paging lazily under the default order, so
  "N of M" is exact for any order (design D4 amended). W3.
- **D11 / spec** — the bucket-region lookup stays before the policy step (U23's reason); the
  `core-stack-provisioning` delta was amended to say so. W2.
- **Hints** — setup keeps its existing next-steps prose instead of a `→` hint (task 11.2's list
  named it; the requirement does not). W5.

## Found and fixed by this change

**Best-per-group compared across JMH modes and always kept the highest score** (`ResultsGrouping`
before `jobs-command`): for `avgt`, `sample` and `ss` that is the slowest result, and a benchmark run
in two modes compared ops/s with ns/op. Never filed as a finding — raised in the exploration, fixed
here (D3), pinned by `ResultsGroupingTest`.

## Warnings

- **W1** — `memory.totalKb` differs by a few KB between two instances of one type (15997060 vs
  15997068 on `c5.2xlarge`, runs 1 and 2), so `jobs diff` of two different instances will almost
  never say "No differences". Correct, but noise. Candidates: compare memory in MiB, or exclude
  `totalKb` from the verdict while still printing it. Left for the user to decide.
- **W2** — Setup's simulator-denied path is tested through `refusalForMissingRights`, not through
  `call()` (which builds its AWS clients inline); live, only the AccessDenied fallback ran, because the
  operator role may not simulate. Both end in the same method.
- **W3** — `jobs list` reads the whole job partition every time (design D14's trigger covers growth).
- **W4** — `infra/runner-image.yaml` comments still say `baas admin build-image` and `baas env diff`;
  editing the file changes the base component and needs an `imageVersion` bump, so they wait for the
  next base change.
- **W5** — No `→` hint after `admin deployment setup`; its next-steps prose already names
  `admin image build`.
- **W6** — `jobs show` has no LocalStack IT of its own; the listing it uses is `S3UploadService.listKeys`,
  covered by `S3DownloadIT`, and the command ran live in 14.2.
- **W7** — Four scenario titles keep pre-change wording until the archive's editorial commit
  (`archive-renames.md`, design D13).
- **W8** — Of the 13 edited diagrams, the six with structural changes were looked at; the rest were
  label edits checked by render only.
- **W9** — Until `multiple-deployments` lands, a by-hand second deployment fills the deployer policy
  template with `sed` (infra/README); setup cannot render for another name yet.

## Deviations from tasks

- 1.1's help half was proven in 11.1; 1.2's change set was replaced by the stack events (the deployer
  may not call `DescribeStackResources`).
- 6.1's IT and 9.1's stubbed-simulator command test were replaced as W6 and W2 describe.
- Paid runs: 3 of 20.
