# Proposal

## Why

The command surface grew one command at a time and mixes three grammars — verb first (`run`,
`download`), noun first (`jobs`, `config`, `env`) and audience first (`admin setup`,
`admin build-image`) — so a user cannot find an action by thinking of the thing it acts on. One job's
information is spread across `jobs`, `env diff`, `download` and `results --request-id`. Two findings
sit inside that: **P11**, `env diff` compares identity fields, so "No differences" can never print,
and **U28**, there is no lookup of one job by id. The 2026-10-06/07 exploration
(`exploration.md`, this directory) settled the whole construction top-down; this change implements
it. While exploring, a defect surfaced: best-per-group ignores the JMH mode and always keeps the
*highest* score, which for time-per-op modes is the slowest result.

## What Changes

- **Construction.** `baas [--deployment <name>] [admin] <noun> <verb>`. Canonical commands are
  noun–verb. A top-level alias is allowed only for a verb that belongs to exactly one noun:
  `baas run` = `baas jobs run`, `baas query` = `baas results query`. A noun alone prints its usage.
- **BREAKING — operator commands.**
  - `baas run` becomes the alias of `baas jobs run` (unchanged arguments).
  - New `baas jobs show <job>`: the job item (stored and resolved status), the environment by group,
    and the job's artifacts from one S3 listing (U28).
  - `baas env diff` becomes `baas jobs diff <jobA> <jobB>`: environment only, by group, with a
    packages section when the AMIs differ (P11). `baas env` is removed.
  - `baas download` becomes `baas jobs download <job>`, job id only — the literal-result-path form is
    removed (it served only jobs predating job items or the unified layout; none exist).
  - `baas results` becomes `baas results query` (alias `baas query`); bare `baas results` prints
    usage. It lists every measurement by default; `--best-per <tag>` replaces the old default view
    and `--group-by`; `--show-excluded` replaces `--all-jobs`; new `--exclude-tag k=v`; `--job-id`
    becomes an ordinary filter.
  - `jobs list` and `results query` share one pipeline — filter → [`--best-per`] → sort → `--offset`
    → `--limit` — with newest first by default, `--sort-by`, `--asc`, `--limit` (default 20 newest,
    `0` = none) and `--offset`; `--watch` and `--exclude-tag` on both.
- **BREAKING — deployer commands.** `admin setup|teardown` become `admin deployment setup|teardown`;
  `admin build-image` / `admin image` become `admin image build|show`; `admin deployer-policy` is
  removed — setup renders the policy first, checks the caller's rights, and when they fall short prints
  the policy and stops without creating anything (`--for-account` is gone).
- **BREAKING — naming a deployment.** A global `--deployment <name>` replaces `teardown --stack-name`
  and `config sync --name`. Until `multiple-deployments` lands it can only name the one configured
  deployment.
- **BREAKING — teardown removes everything.** The bucket and the results table lose
  `DeletionPolicy: Retain`; setup's retained-resource pre-checks, teardown's retention report and
  `--delete-bucket` go. Data leaves before a teardown through the later `export-before-teardown`.
- **`environment.json` (schemaVersion 6)** nests its 22 environment fields in seven groups — machine,
  cpu, memory, os, jvm, tools, tunables — and drops `jobId`, `createdAt`, `project`, `branch`,
  `benchmarkType`, `region`, `awsCliVersion`.
- **Best-per-group fix.** Grouping includes the JMH mode; best is the highest score for throughput
  and the lowest for time-per-op modes.
- **Help and hints.** `baas --help` is grouped: shortcuts, operator commands (operator AWS
  credentials), deployer commands (deployer AWS credentials) with full `admin …` paths. Next-step
  hints on interactive terminals only, on stderr, as `→ <purpose>: <command>`.

## Capabilities

### New Capabilities

None. Every behaviour belongs to an existing capability.

### Modified Capabilities

- `cli-command-structure`: the construction, aliases, bare-noun usage, grouped help, hints,
  `--deployment`, the `jobs`/`results`/`admin deployment`/`admin image` commands, `jobs download`,
  teardown removing everything; `env diff`, `download`, `deployer-policy` and the retention report go.
- `job-tracking`: `jobs run`, `jobs list` (pipeline, `--watch`, `--exclude-tag`), `jobs show`.
- `benchmark-results-query`: `results query`, the default, `--best-per` and its direction,
  `--show-excluded`, `--exclude-tag`, the shared pipeline, `--watch`, `jobs download`.
- `runner-image-provisioning`: the grouped manifest and `jobs diff` with packages.
- `core-stack-provisioning`: nothing retained by teardown, setup's policy step, the removed
  deployer-policy command, renamed admin commands.
- `results-store-schema`: the table's deletion policy.
- `cli-console-output`, `ci-benchmark-execution`, `job-identity`: command names in requirement text.

## Impact

- **Code:** `baas-cli` command tree (`BaasApp`, `JobsCommand` and its subcommands, `ResultsCommand` →
  `results query`, `admin` → `deployment`/`image` nouns, removal of `EnvCommand`, `DownloadCommand`'s
  move, `DeployerPolicyCommand` folded into `SetupCommand`), `JobReference`, `ResultsGrouping`,
  `EnvironmentManifest`, `UserDataScriptBuilder` (manifest heredoc), `TeardownCommand`,
  `infra/cf-template-core.yaml`, `e2e-cloud-test.yml`, CLAUDE.md, READMEs, diagrams.
- **Deliberately not changed:** `jobs run`'s options and arguments (`--detach` stays with
  `detached-run`); the job item, keys and IAM; the runner JAR's options; the termination layers,
  user-data invariants (no `set -e`, watchdog first, manifest before the benchmark, values captured
  into shell variables); `RunnerSecurityGroup`'s description; the deployer policy's *content*;
  `--config-path` and everything about several deployments (owned by `multiple-deployments`).
- **Comparability:** measurements are unaffected; only the manifest's shape changes (schemaVersion 6),
  and `jobs diff` refuses to pretend two schema versions are comparable.
- **Operations:** the template change (no Retain) applies on the next `admin deployment setup`; the
  deployment holds test data only. Teardown now destroys history unrecoverably until export exists.
- **Cost:** no standing change. Verification uses paid runs (budget set in tasks).
- **Closes** P11 and U28 in `docs/review/open-findings.md`; removes U12 (no retained table) and
  reduces U2 to exporting before teardown. Files the best-per-group finding as fixed.
