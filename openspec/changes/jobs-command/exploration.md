# `jobs-command` — exploration

Not an OpenSpec artifact: the outcome of `/opsx:explore` on 2026-10-06, kept so the proposal starts
from it. This change was `runs-command` (P11, U28; design in `docs/review/open-findings.md`,
*runs-command*). The exploration broadened it from "`runs show`/`runs diff` replace `env diff`" to the
shape of the whole command surface, and split a vocabulary rename out into its own change,
`rename-run-to-job`, which goes first.

## Sequence

**Status 2026-10-06:** steps 1–3 done. `rename-run-to-job` merged into `next-release` as PR #80
(archived as `openspec/changes/archive/2026-10-06-rename-run-to-job/`); the deployment was rebuilt on
the job names before the merge; this branch is rebased. Continue at *Open*, question 1.

1. **`rename-run-to-job`** — its own OpenSpec change, branch from `next-release`, PR into
   `next-release`, `feat(cli)!`. Scope below.
2. After it merges: wipe and rebuild the deployment (below).
3. Rebase the `jobs-command` branch onto `next-release` and continue exploring this change from
   *Open*, starting with `download`.
4. The deployment is rebuilt once more if `jobs-command` changes anything stored.

## Decisions

- **Noun–verb grammar, as `gh` and `docker container …`, not kubectl's verb–noun.** kubectl's
  fixed verb set pays off across ~50 resource kinds sharing CRUD; BaaS has ~5 nouns and most verbs fit
  one (`download`, `terminate`, `build-image`, `sync`), so the grid would be mostly exceptions. Noun
  first answers the discoverability rule this started from: the user finds the thing, then `--help`
  lists what can be done to it. clig.dev: "`noun verb` seems to be more common."
- **`baas run <type> -- …` stays, and is the only creating verb.** Its simplicity is the point.
- **The collection is `baas jobs`.** `run` and `runs` one letter apart is clig.dev's
  "Don't have ambiguous or similarly-named commands" (`update`/`upgrade`) — so renaming is required,
  not a preference. "Run" survives only as a verb (`baas run` runs a job) and inside `runner`.
- **`baas runs` is removed outright**, no deprecated alias. The major is breaking anyway, and an alias
  keeps the confusable name alive while it exists.
- **The rename is project-wide, storage included** — not only the CLI noun. One thing, one name: a
  CLI saying `job` over storage saying `run` (and, today, `requestId`) is the split being removed.
- **The deployment holds only test data, so it is wiped and rebuilt** — no dual reading of old
  names, no migration. Confirm before teardown that the migrated `lynx-journal` / `unknown-migrated`
  rows are disposable: `export-before-teardown` is not built and the bucket is unversioned.
- **`runner` is not renamed** (`benchmark-runner`, `<prefix>-role-runner`, `-profile-runner`,
  `-pipeline-runner`, `/<prefix>/runner/ami-id`). It names the machine and program that executes a job
  — runners run jobs, as in GitHub. Consequence: **the deployer policy does not change**; it names no
  run, request, index or partition key.
- **Docs say "CI job" for a GitHub Actions job** wherever a doc touched by the rename means one, so "a
  red CI job with a completed BaaS job" stays readable. Every current use of "job" in this repository
  means a CI job.

## The name: candidates considered

The noun had to carry `list`, `show`, `diff`, `download`, `terminate`.

| Noun | Verdict | Why |
|---|---|---|
| `jobs` | **Chosen** | Fits every verb, short, a whole unit of work run to completion (Kubernetes `Job`, AWS Batch job). Costs, accepted: Jenkins/Glue/Databricks pair *job* = definition with *run* = execution — the reverse of ours; in GitHub Actions a job is part of a run; and this repository's docs use "job" for the CI job that calls `baas run` (hence "CI job") |
| `executions` | Rejected | No clash at full length (Step Functions uses it our way), but 10 characters; the natural short form `exec`/`execs` reads as `docker exec`/`kubectl exec` — an action inside a live container, the opposite of a recorded, unattended job |
| `tasks` | Rejected | ECS tasks sit in the same AWS console; in Gradle, Airflow, Spark and Step Functions a task is a step *inside* a job — a BaaS run is the top-level unit |
| `trials` | Rejected | JMH's `Level.Trial` is one benchmark × params × fork — far smaller than a BaaS run, in the API our users write |
| `launches` | Rejected | Echoes `launching`/`launched`, but "download a launch" reads wrong |
| `benchmarks` | Rejected | In JMH and `baas results` a benchmark is the measured method, not an execution |
| `history`, `instances`, `sessions`, `flights`, `submissions`, `invocations`, `workloads`, `experiments`, `ps` | Rejected | Cannot terminate history; an instance is the EC2 machine a job outlives; the rest misdescribe or are one verb |

Rejected naming shapes: singular `baas run list` as in `gh run` (collides with `baas run <type>`, whose
type is a positional parameter, `RunCommand.java:103`); renaming the verb to `baas launch` and keeping
`run` as the noun (gives up the simple `baas run`).

## clig.dev, applied

- **Consistency** ("Use the same flag names for the same things"): make it a written requirement that
  list commands share `--project`, `--tag`, `--limit`, `--format`, and that every job argument accepts
  a `JobReference`. True today by coincidence only.
- **Suggest next commands**: next-step hints (`→ baas jobs download <id>` after `jobs show`; a failed
  `baas run` pointing at `jobs show`). Diagnostics, so stderr via the logger, never payload.
  picocli already prints "Did you mean" for unknown subcommands.
- **No arbitrary abbreviations**: picocli's abbreviation matching stays off (nothing enables it).
- **Confirmation**: already matches — `admin teardown` types the stack name back (severe tier),
  `jobs terminate --yes` (moderate tier), both refuse without a terminal.
- **Output**: `--format json|csv` covers `--json`/`--plain`; `-o` means an output path (as
  `download -o`), one more reason not to take kubectl's `-o json`.
- **"Prefer flags to args" / two args for different things**: `baas run <type> -- <args>` passes two
  kinds of thing positionally. Defensible (`--` passthrough), but `--type` or picocli subcommands per
  type are the clig-shaped forms. Separate thread, not decided.
- **Future-proofing**: clig suggests deprecation warnings before a break; declined here (above).

## `rename-run-to-job` — scope

"Run" as a noun becomes "job" everywhere; the id merges `runId`, `requestId` and `request-id` into
`jobId`. The id *values* do not change (`20260820T174432812Z-a3f9c21b` contains no "run"), so no sort
key changes shape.

**In scope**

| Layer | From | To |
|---|---|---|
| CLI noun | `baas runs list \| terminate` | `baas jobs list \| terminate`; `runs` removed |
| CLI flags/output | `results --request-id`; summary `runId`/`runStatus` | `--job-id`; `jobId`/`jobStatus` |
| Model (`baas-model`) | `RunLayout`, `RunId`, `RunItem`, `RunItemMapper`, `RunStatus`, `ResultKeys.RUN_*` | `Job…` |
| CLI code | `RunSession`, `RunTermination`, `RunReference`, `RunsCommand`, `Runs*Subcommand`, package `…baas.runs` | `Job…`, `…baas.jobs`. `RunCommand` stays — it is the verb |
| Storage | `runs/<project>/<runId>/`; `pk = RUN`, `gsi1sk = RUN`; attributes `requestId` (run items and measurement items); index `requestId-index` | `jobs/…/<jobId>/`; `JOB`; `jobId`; `jobId-index` |
| Infrastructure | IAM `dynamodb:LeadingKeys = RUN` for runner and operator (`cf-template-core.yaml`, `operator-policy.json`); EC2 tag `baas-request-id` | `JOB`; `baas-job-id`. Deployer policy unchanged |
| Instance | user-data variables (`RUN_SORT_KEY`, …) | `JOB_…` |
| Deleted, not renamed | `download`'s literal-result-path fallback for pre-unified-layout runs; CLAUDE.md's "two id shapes coexist and always will"; the `RESULT#unknown` / `unknown-migrated` notes | — |
| Docs | CLAUDE.md, README, `infra/README.md`, main specs (REMOVED headings verbatim), `docs/diagrams/*.mmd` — each rendered with `mmdc` and looked at | "job"; "CI job" for GitHub's. Archived changes and ADRs stay as they are |
| CI | `e2e-cloud-test.yml` reads `runStatus` | `jobStatus` |
| Queue | `QUEUE.md` | `rename-run-to-job` at 1, `jobs-command` (was `runs-command`) at 2 |

**Out of scope — stays in `jobs-command`**

- `jobs show` (the lookup by id, U28) and `jobs diff` (P11), replacing `env diff`.
- Where `download`, `env diff` and `results --request-id`/`--job-id` end up.
- The P11 manifest changes (drop `requestId`, `createdAt`, `region`, `awsCliVersion`; run/system
  sections; `schemaVersion` bump and loud mismatch).
- Flag consistency as a requirement, `JobReference` on every job argument, next-step hints, grouped
  top-level help, `<type>` as flag or subcommand, whether `admin` stays the deployer-credentials
  namespace.

**Rebuild, after the merge** (the new CLI must deploy the renamed template):

1. Confirm the `lynx-journal` / `unknown-migrated` rows are disposable.
2. `baas admin teardown --delete-bucket`.
3. Delete the retained table — the deployer already holds `dynamodb:DeleteTable`.
4. `baas admin setup`, passing `GitHubOidcProviderArn` again so CI can still federate.
5. `baas admin build-image` (~15 min; teardown retired the image).
6. `baas config sync --name <prefix>` on each machine. The prefix is account-derived, so GitHub's
   `CORE_STACK_NAME` and `OPERATOR_ROLE_ARN` stay the same.

A run launched by an old CLI and still in flight when setup switches `LeadingKeys` to `JOB` cannot
record its outcome — rebuild with nothing in flight.

**Verification budget: up to 5 paid runs** after the rebuild, to check the renamed path end to end
(e.g. `jmh-with-async` and `jcstress` sanity on the rebuilt deployment; `jobs list`, `jobs terminate`,
`results --job-id`, `download <jobId>` against them; the CI e2e run on the PR counts separately).
Record each in the change's `verify.md`.

## Whole-API construction (2026-10-06, after the rename)

Decided top-down; each action is settled only after the construction.

```
baas  [--deployment X]  [admin]  <noun>  <verb>  [args] [options]
       scope             workload  domain   shared vocabulary
```

- **Three layers below the scope: workload → domain noun → verb.** Canonical commands are always
  noun–verb; a short form is an *alias* onto a canonical one, never an exception to the grammar.
- **`baas run` is an alias of `baas jobs run`.** `baas results` is an alias of `baas results list`
  (tentative — "perhaps").
- **Workloads are credential boundaries:** operator (no prefix) and deployer (`admin`). `admin` stays.
- **Domains:** `jobs` = the execution (status, instance, boot log, environment, artifacts);
  `results` = the measurements, the numbers that matter historically. Two domains, so
  `results --job-id` stays and `jobs show` shows no measurements (a stderr hint may point across).
  `config` = this machine's binding. Deployer: `deployment` (setup, teardown) and `image`.
- **The deployer noun is `deployment`** (`baas admin deployment setup | teardown`), chosen over
  `deployment` for pairing with the deployer workload; `instance` rejected (the EC2 runner).
- **Scope:** global, inherited `--deployment <name>` plus `BAAS_DEPLOYMENT`; never positional; absent
  means the account's deployment. Same word as the noun, as kubectl's `--namespace` / `namespaces`.
  Coordinates with the second-deployment exploration (session `0d0d679c`), which was leaning to
  `--deployment`. Renaming "deployment" in messages and docs to "deployment" is owed by
  whichever change introduces the selector.
- **`download` becomes `baas jobs download <job>`**, job id only; the literal-result-path form goes.
- **`build-image` / `image` become `baas admin image build | show`.**

- **The word "installation" is already renamed to "deployment"** in messages, identifiers, docs,
  diagrams and main specs (`fec4adc`, no OpenSpec change, at the user's request); the installed-CLI
  sense and `infra/runner-image.yaml` keep "installation". The second-installation session was told.
- **Parked:** where the deployer policy lives (`admin policy show`, a `deployment` verb, or a separate
  command) — the user expects a deeper discussion of whether it stays a command at all.

- **A bare noun prints its usage**; short forms exist only as declared aliases.
- **Alias rule: a top-level alias is a verb that belongs to exactly one noun.** `baas run` →
  `baas jobs run`, `baas query` → `baas results query`. Shared verbs (`list`, `show`) are never
  aliased, so `baas list` cannot exist; `baas results` alone prints usage.
- **`results` has one verb, `query`** — no `results list`, so there is no list/query border to defend.
  A later action (comparing two branches) gets its own verb, e.g. `results compare`.
- **`query` defaults to every measurement**; `--all-jobs` becomes **`--show-excluded`** (excluded
  rows hidden by default; `--job-id` shows them anyway).
- **Parked for the `baas query` features discussion:** `--exclude-tag k=v`, a negative tag filter.
- **Finding raised (not yet filed):** today's best-per-group ignores the JMH mode in its key and
  always keeps the *highest* score (`ResultsGrouping.java:31-46`) — for `avgt`/`sample`/`ss`
  (time per op) that is the worst, and `thrpt` and `avgt` rows of one benchmark are compared.

- **Summary flag: `--best-per <tag>`** (replaces today's default view and `--group-by`; results only —
  a job has no score). "Best" = highest for throughput, lowest for time-per-op, grouped within one
  JMH mode — the fix of the finding above.
- **Filter flags: same name, same meaning** on every command, shared where meaningful.
- **`--project p` = "only project p" everywhere; the default when absent is per command**, stated in
  its help: `jobs list` = every project (one partition, cheap); `results query` = the resolved
  project (git, picker, else error) — scanning every project is opt-in via `--all-projects`, which
  exists on results only.
- **`--limit N` = the N newest rows**, default 20 on both, `--limit 0` = no limit; a cut is announced
  on stderr. **`--offset M`** pages; newest-first paging shifts if a row arrives between pages
  (harmless here; a `--before <jobId>` cursor is the stable alternative if it ever matters).
- **One pipeline on both: filter → [`--best-per`] → sort → `--offset` → `--limit`**, default sort
  newest first, `--sort-by <field>` (results: `created`, `benchmark`, `score`, `project`; jobs:
  `created`, `status`, `project`) and `--asc`. A non-default sort on `jobs list` reads every job.
- **Indexes deferred.** They optimise behind the CLI semantics and change no flag. Trigger: the job
  partition outgrows a few pages (≈ >5,000 jobs) or `jobs list` latency becomes noticeable. First a
  sparse by-project index (`pk = <project>`, `sk = createdAt`, job items only); a by-status index last
  or never (status moves 4–5 times per job, `vanished` is never stored).

- **Next-step hints:** interactive only (the `Console` gate colour uses), stderr via the logger,
  shape `→ <purpose>: <exact command>` with real ids, at most two per command, no opt-out flag.
- **Grouped `baas --help`:** shortcuts (`run = jobs run`, `query = results query`) → operator
  commands → deployer commands; each noun line lists its verbs; deployer lines show the full path
  (`admin deployment …`, `admin image …`) so every line is copyable; headers name the role —
  "Operator commands (operator AWS credentials)", "Deployer commands (deployer AWS credentials)" —
  never a config key.
- **Parked for the `config` discussion:** renaming `aws.profile` / `aws.operatorProfile` to
  `aws.deployerProfile` / `aws.operatorProfile` (a `config.yaml` migration, and the invariant that the
  operator profile never falls back to the deployer's).

Open, in order: the full command tree, then each action; the parked policy question; `--exclude-tag`
with the `baas query` features; the config key rename with `config`.

## Open — continue here after the rebase

Asked one at a time, in this order:

1. Does top-level `baas download <job>` become `baas jobs download <job>`? Coupled with the item the
   rename deferred here (its design D6): `download` and `env diff` still accept a literal result path,
   which only served jobs without a status item or from before the unified layout — none exist since
   the rebuild. Removing it is a behaviour change with a spec delta (REMOVED + ADDED, since OpenSpec
   cannot drop a scenario from a MODIFIED block), and it reverses the P11 design's "id or path".
2. `results --job-id` versus `jobs show` — does a job's measurements view belong under `jobs`?
3. Does `admin` stay as the deployer-credentials namespace, or become `baas image build` /
   `baas install setup`?
4. `<type>` on `baas run`: positional (today), `--type`, or a subcommand per type?
5. Next-step hints and grouped `baas --help`: in this change or later?
