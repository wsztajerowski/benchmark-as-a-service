# Proposal

## Why

`baas run` (the verb that starts a benchmark) and `baas runs` (the collection that lists and stops
them) differ by one letter — clig.dev's "Don't have ambiguous or similarly-named commands". The noun
also has three names for one thing: `runId` in the CLI and S3 layout, `requestId` on every stored
item, `request-id` on the GSI, the EC2 tag and `baas results`. The `jobs-command` exploration
(2026-10-06, `openspec/changes/jobs-command/exploration.md` on branch `jobs-command`) settled on
**job** as the one noun, project-wide, storage included, with "run" kept only as the verb and inside
`runner`. It goes first, as its own change, so `jobs-command` is designed on the final vocabulary.

The installation holds test data only (checked 2026-10-06: 47 items in projects
`benchmark-as-a-service`, `baas-e2e`, `baas-lifecycle-test`), so it is wiped and rebuilt — no
migration, no reading of old names.

Closes no `docs/review/open-findings.md` entry; it is the prerequisite for `jobs-command` (P11, U28).

## What Changes

- **BREAKING** `baas runs list | terminate` becomes `baas jobs list | terminate`. `baas runs` is removed,
  no alias. `baas run <type> -- …` is unchanged.
- **BREAKING** `baas results --request-id` becomes `--job-id`; `--all-runs` becomes `--all-jobs`.
- **BREAKING** `baas run`'s JSON summary fields `runId` / `runStatus` become `jobId` / `jobStatus`.
- **BREAKING** `benchmark-runner`'s `--request-id` / `-id` becomes `--job-id` / `-id`. The runner is
  pinned to the CLI's version, so the two move together.
- **BREAKING (storage)** S3 prefix `runs/<project>/<runId>/` becomes `jobs/<project>/<jobId>/`; the
  status item's partition `pk = RUN` / `gsi1sk = RUN` becomes `JOB`; the id attribute `requestId` (on
  status and measurement items) becomes `jobId`; the GSI `requestId-index` becomes `jobId-index`; the
  EC2 tag `baas-request-id` becomes `baas-job-id`; `environment.json`'s `requestId` becomes `jobId`.
  The id's *value* and format (`20260820T174432812Z-a3f9c21b`) do not change.
- IAM: `dynamodb:LeadingKeys` `RUN` → `JOB` for the runner and operator roles.
- `baas-model` and `baas-cli` types and packages: `RunId`, `RunLayout`, `RunItem`, `RunItemMapper`,
  `RunStatus`, `RunSession`, `RunTermination`, `RunRecorder`, `RunReference`, `RunListing`,
  `RunsCommand` … become `Job…`; package `…baas.runs` becomes `…baas.jobs`; the runner's `RunLogs`
  becomes `JobLogs`. `RunCommand` stays — it is the verb.
- Docs only, removed rather than renamed: the "two id shapes coexist" rule and the notes on
  `RESULT#unknown` / `unknown-migrated` rows — a wiped installation holds no such item. The
  literal-result-path argument of `download` and `env diff` stays (with `jobs/…` paths) and is
  decided in `jobs-command`, which redesigns both commands (design D6).
- Docs: CLAUDE.md, READMEs, main specs, `docs/diagrams/*.mmd`, `docs/review/open-findings.md` use
  "job", and "CI job" for a GitHub Actions job. Archived changes and ADRs stay as written. The
  capabilities `run-artifact-layout`, `run-identity` and `run-tracking` become `job-artifact-layout`,
  `job-identity` and `job-tracking`.

## Capabilities

### New Capabilities

None. The three `run-*` capabilities are renamed, not introduced: their deltas are written against
the existing paths and the directories are moved at archive time (design D5).

### Modified Capabilities

- `run-tracking` (→ `job-tracking`): `baas jobs list | terminate`, the `JOB` partition, `jobId`,
  `baas-job-id`, summary `jobId`/`jobStatus`.
- `run-identity` (→ `job-identity`): the job identifier, `jobs/` prefix, `JobLayout`/`JobId`.
- `run-artifact-layout` (→ `job-artifact-layout`): the `jobs/` tree.
- `results-store-schema`: `jobId` attribute, `jobId-index`, `JOB` status items excluded from readers.
- `benchmark-results-query`: `--job-id`, `--all-jobs`.
- `cli-command-structure`: the `jobs` group replaces `runs`; `download` and `env diff` wording.
- `core-stack-provisioning`: `jobId-index`, `LeadingKeys = JOB`, the `jobs/` lifecycle pin.
- `ci-benchmark-execution`: CI reads `jobStatus`, "CI job" versus job.
- `runner-image-provisioning`, `runner-jar-distribution`, `jcstress-execution`, `cli-console-output`,
  `cli-distribution`: requirement text that uses the noun.

## Impact

- **Code:** `baas-model` (keys, mappers, layout, id, status), `baas-cli` (commands, `jobs` package,
  `UserDataScriptBuilder`, `Ec2ProvisioningService`, `ResultsQueryService`, `TeardownCommand`),
  `benchmark-runner` (`ApiCommonSharedOptions`, `RunLogs`, the stores), all their tests,
  `infra/cf-template-core.yaml`, `infra/operator-policy.json`, `e2e-cloud-test.yml`,
  `jmh-with-*.sh`.
- **Deliberately not changed:** `runner` in every name (`benchmark-runner`, `<prefix>-role-runner`,
  `-profile-runner`, `-pipeline-runner`, `/<prefix>/runner/ami-id`), so **the deployer policy is
  unchanged**; the status values (`launching`, `running`, `completed`, …); the id format; the
  resource-name prefix; `RunnerSecurityGroup`'s `GroupDescription`. Every invariant in CLAUDE.md holds
  under the new names — three termination layers, no `set -e`, watchdog-first, comment stripping,
  `/app`, keys only in `ResultKeys`, items only in the mappers, no lifecycle rule under the job tree,
  versioning `Suspended`.
- **Operations:** the installation is torn down (`--delete-bucket`), its retained table deleted, and
  rebuilt with `setup` + `build-image` from this branch **before merge**, so the PR's CI e2e runs
  against it. Until `next-release` has the rename, every other branch's e2e fails against it. Every
  machine re-runs `baas config sync --name baas-381492019823`; GitHub variables are unchanged.
- **Users:** every CLI must be upgraded; an old CLI cannot read or write the rebuilt installation.
- **Cost:** one image bake (~15 min of a build instance), up to 5 paid verification runs on
  `c5.2xlarge`, plus the PR's CI e2e jobs — on the order of a dollar in total. Standing cost
  unchanged: one retained AMI snapshot (~$0.20/month).
