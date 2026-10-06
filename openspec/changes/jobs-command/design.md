# Design

## Context

See proposal.md — *Why*. Every decision below was taken one question at a time in the exploration
(`exploration.md`), which keeps the rejected alternatives in full; this document records what the
implementation needs. The command tree today is built in `BaasApp` (root subcommands `run`, `jobs`,
`results`, `download`, `env`, `config`, `admin`) and `AdminCommand` (`setup`, `teardown`,
`build-image`, `image`, `deployer-policy`). `JobReference` resolves an id *or* a path. `ResultsCommand`
groups by default (`ResultsGrouping.bestPerGroup`, key without the JMH mode, highest score wins) and
cuts `--limit` after sorting by project and benchmark. The manifest is a flat heredoc in
`UserDataScriptBuilder` (schemaVersion 5) read by `EnvironmentManifest`. The bucket and the table are
`DeletionPolicy: Retain`.

A sibling change, `multiple-deployments`, is built on this one and owns everything about several
deployments, including removing `--config-path` (scope split accepted 2026-10-07).

## Goals / Non-Goals

**Goals:**
- One construction for the whole CLI that a new command can be placed into without a new debate.
- Every behaviour the exploration decided for the commands this change owns.

**Non-Goals:**
- Several deployments, per-deployment files, the selection rule, `config list`, the profile-key
  rename, removing `--config-path` (`multiple-deployments`).
- Export/import before teardown (`export-before-teardown`).
- `jobs run`'s options, `--detach` (`detached-run`).
- Indexes on the job partition (deferred; trigger below).

## Decisions

### D1 — Commands are `baas [admin] <noun> <verb>`, and the only top-level words are nouns and two aliases

Nouns: `jobs`, `results`, `config`; under `admin`: `deployment`, `image`. A top-level alias exists only
for a verb owned by exactly one noun — `run` (jobs), `query` (results) — so `list`/`show` can never be
aliased. A bare noun prints usage. In picocli the alias is the same command class registered twice
(under its noun and at the root), hidden from the generated command list because D9's help renders
the map itself. Rejected: kubectl's verb–noun grid (five nouns, verbs mostly owned by one of them);
implied default verbs per noun (unguessable); `baas list` (ambiguous between jobs and results).

### D2 — `results` has one verb, `query`, whose default is every measurement

No `results list`: two verbs over the same rows with overlapping filters is the update/upgrade problem.
`--best-per <tag>` replaces the default view and `--group-by`; `--show-excluded` replaces `--all-jobs`;
`--job-id` becomes a filter that combines with everything but `--project`/`--all-projects`.
Rejected: `list` + `query` with a border by filter richness (no stable border); `--best` plus
`--group-by` (two flags, one meaningless alone); `--best[=tag]` (optional values misparse).

### D3 — "Best" groups within one JMH mode and follows the mode's direction

The grouping key gains the mode; throughput keeps the highest score, `avgt`/`sample`/`ss` the lowest; a
non-finite score never wins. This fixes the finding raised in the exploration
(`ResultsGrouping.java:31-46`). The mode-to-direction map lives next to the grouping, keyed by the
mode strings the runner stores.

### D4 — One pipeline orders and pages every listing

`jobs list` and `results query` apply filter → [`--best-per`] → sort → `--offset` → `--limit`; newest
first by default; `--sort-by`, `--asc`; `--limit` default 20, `0` none; a cut is announced on stderr.
`--limit` used to mean "first N in display order" on results — the oldest rows of the alphabetically
first benchmarks — and "newest N" on jobs; it now means the latter on both. `jobs list` keeps reading
pages lazily only under the default order. Rejected: per-command display orders (paging would not
follow what is shown); a cursor instead of `--offset` (noted as the stable alternative if rows
arriving between pages ever matter).

### D5 — `jobs show` is the execution view: item, environment, artifacts — no measurements

Two domains: `jobs` is the execution, `results` the numbers. `show` reads the job item (through
`jobId-index`), the manifest and one `ListObjectsV2` of the prefix. Rejected: measurements inside
`show` (merges the domains; `results query --job-id` stays the measurement view).

### D6 — `jobs diff` compares the environment only, by group, with packages when the AMIs differ

The manifest drops identity, so every remaining field is meant to be compared and "No differences" can
print (P11). Packages are read only when `machine.amiId` differs: user-data installs nothing, so equal
AMIs mean equal packages. `packages.txt` lines are parsed as `name-version-release.arch` and compared by
name. Rejected: P11's `--run|--system|--all` (it put AMI and instance type in the optional section);
always diffing packages (always empty for one AMI); never (the tool would not answer "what moved
inside the image?").

### D7 — `environment.json` becomes seven groups and the environment only (schemaVersion 6)

Groups `machine`, `cpu`, `memory`, `os`, `jvm`, `tools`, `tunables`; dropped: `jobId`, `createdAt`,
`project`, `branch`, `benchmarkType`, `region`, `awsCliVersion`. The heredoc stays pure `${VAR}`
references, now nested; every value is still captured first; the machine-observed tags read the same
variables. **Comparability:** no measured value and no image content changes; only the file's shape
does. `jobs diff` warns on a schema mismatch, so a 5-vs-6 comparison is never presented as like for
like. The deployment holds test data only, so no reader of schema 5 needs to survive beyond the
mismatch warning.

### D8 — `download` is `jobs download`, and it takes a job id only

The path branch of `JobReference` served jobs without a job item or from before the unified layout;
the rebuild left none. `JobReference` keeps one shape: an id, resolved through the job item.

### D9 — Help is rendered by a custom section; hints go through the logger

`baas --help` replaces picocli's flat command list with three sections — shortcuts (`run = jobs run`),
operator commands, deployer commands with full `admin …` paths — headed by role ("operator AWS
credentials", "deployer AWS credentials"), never by config key. Hints are `→ <purpose>: <command>`, at
most two, logged to stderr only when `Console` is interactive (the colour gate). No opt-out flag.

### D10 — `--deployment` is a global option that can name only the configured deployment for now

It replaces `teardown --stack-name` and `config sync --name`. Until `multiple-deployments` lands, a
name other than the configured one fails naming both. `--config-path` stays (its removal belongs to the
sibling change). `config sync --deployment` keeps sync's rule that the name is required.

### D11 — The deployer policy is a step of setup, not a command

Setup renders the policy (account from `sts:GetCallerIdentity`, which needs no permission), simulates
the caller's rights where `SimulatePrincipalPolicy` is allowed, and on a shortfall prints the policy to
stdout, the missing actions to stderr, and exits non-zero having created nothing. `DeployerPolicyCommand`
and `--for-account` go; `DeployerPolicyRenderer` and `DeployerPreflight` stay. The deployer policy's
*content* does not change. Rejected: an `admin policy` noun (a noun for a once-per-deployment step);
`--dry-run` (its main use was rendering for someone else, dropped); a CDK-style bootstrap role (a third
credential tier and a bootstrap stack, which the project removed); publishing the template only (seven
region substitutions by hand).

### D12 — Nothing survives a teardown

Bucket and table: `DeletionPolicy: Delete`. Teardown empties the bucket (CloudFormation will not delete
a non-empty one), deletes the stack — which now deletes the table — then retires the image. Removed:
`--delete-bucket`, setup's retained-bucket and retained-table pre-checks, teardown's retention report.
A stack created before this change still holds `Retain` on both until its next `admin deployment setup`
applies the new template; until then a teardown would leave them, so the verification rebuilds the
deployment from this branch before testing teardown.

### D13 — Specs follow the mechanical rename method of `rename-run-to-job`

Command renames in requirement text are generated as MODIFIED blocks; behaviour changes are written by
hand, as REMOVED + ADDED where a scenario stops being true. Scenario titles that OpenSpec will not let a
MODIFIED block rename are listed in `archive-renames.md` and changed in the archive's editorial commit.

### D14 — Indexes on the job partition are deferred

They would only speed up what D4 already defines. Trigger: the job partition outgrows a few pages
(≈ more than 5,000 jobs) or `jobs list` latency is noticeable; first a sparse by-project index.

## Risks / Trade-offs

- [Teardown now destroys history unrecoverably] → the prompt says so before confirmation; the
  deployment holds test data; export-before-teardown is queued.
- [Every script and CI step calling `baas results`, `download`, `env diff`, `admin setup` breaks] → one
  major release with the rename; CI is updated in this change; no aliases for removed spellings, as
  with `runs` (clig.dev: an alias keeps the confusable name alive).
- [`results query`'s default output grows from "best per branch" to every row] → `--limit 20` default
  and the stderr notice bound it.
- [A non-default `--sort-by` on `jobs list` reads the whole job partition] → one or two pages at this
  scale; D14's trigger covers growth.
- [The packages diff is only as good as `rpm -qa` parsing] → names compared, versions shown verbatim;
  unparseable lines reported as added/removed rather than dropped.
- [Two changes rewrite overlapping requirements] → this change archives first; `multiple-deployments`
  rebases its deltas (its task 1.2), with the renamed headers sent to it.

## Migration Plan

1. Implement; full reactor `mvn verify` with `ASYNC_PATH`.
2. `baas admin deployment setup` from the branch build on the existing deployment: applies
   `DeletionPolicy: Delete` and keeps everything else.
3. Paid verification (tasks), then teardown and setup again to prove nothing survives, then
   `admin image build` so the deployment is usable.
4. PR into `next-release`, CI e2e green, verify, rebase-merge, archive with the editorial commit.

Rollback: revert the merge and run `admin deployment setup` from the previous build; the retention
policy returns with it.

## Resolved Questions

- *Where does the deployer policy live?* — In setup (D11); decided 2026-10-07.
- *Who removes `--config-path`?* — `multiple-deployments` (scope split, 2026-10-07).
- *`config list` and the profile-key rename?* — Adopted in `multiple-deployments` (relayed 2026-10-07).
