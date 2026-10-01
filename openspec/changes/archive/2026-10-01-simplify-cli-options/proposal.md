# Proposal

## Why

`baas run` and `baas results` each carry options that duplicate one another, contradict an invariant,
or quietly read the directory the command happens to be launched from. `--project`, `--branch` and
`--commit` default from the current directory's git repository, so the same command records or reads
a different partition depending on where it was typed; `--ami-id` is an escape hatch from the
one-image invariant; `--max-wall-clock` can be set below `--timeout`, letting the watchdog kill a
benchmark that was still within its own budget; and `--results-table` is a second, per-command way to
address an installation that a whole configuration file expresses better. Removing them shrinks
`baas run` from 14 options to 9 and makes every implicit input either explicit or opt-in.

## What Changes

**Global**

- New inherited option `--config-path <file>` on every command, defaulting to `~/.baas/config.yaml`.
  When the named file does not exist, a read command fails naming it; `config sync`, `config set` and
  `admin setup` create it.
- New config key `git.resolveProject`, **off by default**, set with
  `baas config set --git-resolve-project true`. Git is
  consulted only when it is on, and only for `project`.

**`baas run`**

- **BREAKING** `--project` is required unless `git.resolveProject` is on, in which case the project is
  the name of the git repository containing the `--benchmark-jar` file — not the working directory.
  A JAR outside any repository fails before provisioning, naming `--project`.
- **BREAKING** `--branch` and `--commit` are removed, and git is never consulted for either. They are
  set with `--tag branch=… --tag commit=…`, and are absent from the stored result otherwise.
- **BREAKING** `--max-wall-clock` and config `ec2.wallClockHardKillSeconds` are replaced by
  `--watchdog-margin <s>` / `ec2.watchdogMarginSeconds` (default 300, minimum 60): seconds added to
  the benchmark timeout; `baas config set --max-wall-clock` becomes `--watchdog-margin`. The watchdog delay and the CLI's poll cap are both `timeout + margin`, so the
  watchdog can no longer fire before the benchmark's own timeout.
- **BREAKING** `--ami-id` is removed.

**`baas results`**

- **BREAKING** `--results-table` and `--living-branches` are removed.
- **BREAKING** Without `--project`:
  - with `git.resolveProject` on and the working directory inside a git repository, that repository's
    name is the project;
  - otherwise, on an interactive terminal with the table format, a numbered list of projects is
    offered — only projects holding at least one non-excluded measurement — together with a tip naming
    `baas config set --git-resolve-project true`;
  - otherwise the command fails, listing the available projects and naming `--project` and
    `--all-projects`.
- New `--all-projects`: every project's measurements, with a PROJECT column. Rejected together with
  `--project`.
- **BREAKING** `--all` is renamed `--all-runs`. It disables best-per-group selection **and** includes
  measurements tagged `exclude_from_results=true`, which are rendered de-emphasised.
- Best-per-group selection groups by `(project, benchmark, group tag)`.
- The `±ERROR` column leaves the table; `scoreError` stays in JSON and CSV.
- Tags: with `-v`, each table row is followed by an indented line carrying all of its tags. JSON rows
  gain a `tags` object and CSV rows a `tags` column (`k=v;k=v`), regardless of `-v`.

**`baas download`**

- **BREAKING** `--results-table` and `--bucket` are removed; `--config-path` replaces them.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `cli-command-structure`: project derivation becomes opt-in and anchored on the benchmark JAR;
  `--branch`/`--commit` and `--ami-id` go; the results filter set changes; per-command table overrides
  give way to `--config-path`; `git.resolveProject` and `ec2.watchdogMarginSeconds` join the stored
  preferences.
- `benchmark-results-query`: the access path gains a `Scan` for the picker and `--all-projects`;
  living-branch filtering is removed; exclusion is lifted by `--all-runs`; grouping includes the
  project; output carries tags and drops the error column from the table.
- `results-store-schema`: `project` is no longer derived from the invocation directory; `branch` is
  caller-supplied only.
- `core-stack-provisioning`: the poll-loop scenario names the timeout-plus-margin cap instead of
  `wallClockHardKillSeconds`.
- `cli-console-output`: the results table de-emphasises excluded rows; the unknown-value scenario no
  longer cites the removed error column.
- `ci-benchmark-execution`: the self-test passes `--project`, `branch` and `commit` explicitly, and can
  assert `source=ci` now that JSON output carries the tag map.

## Impact

- **Code:** `baas-cli` — `BaasApp` (global option), `ConfigService` / `BaasConfig` (path parameter, new
  keys), `ConfigSetSubcommand`, `RunCommand`, `ResultsCommand`, `ResultsQueryService`,
  `ResultsGrouping`, `ResultsFilters`, `ResultsTable`, `DownloadCommand`, `TeardownCommand` (retention
  hint), `GitProject`, `UserDataScriptBuilder` (watchdog delay input). `baas-model` and
  `benchmark-runner` are untouched.
- **CI:** `e2e-cloud-test.yml` must pass `--project`, `--tag branch=…`, `--tag commit=…`; it can now
  assert `source=ci` from the JSON `tags` object.
- **Docs:** README option lists, `docs/diagrams/baas-run.mmd`, CLAUDE.md (*Read-only commands take
  `--results-table`*, the watchdog formula, the `aws.operatorProfile`/config sections).
- **Users:** scripts using any removed option fail with picocli's unknown-option error — loud, not
  silent. A leftover `wallClockHardKillSeconds` in an existing config is ignored and the 300 s margin
  applies.
- **Cost:** no AWS resource changes. The picker and `--all-projects` read the whole table with a
  `Scan`, billed on-demand by data read — negligible at today's size, growing with history, and paid
  per refresh under `--watch`. No new standing cost. No IAM change: `BaasCliOperatorRole` already holds
  `dynamodb:Scan` on the table.
- **Deliberately not changed:** the three termination layers (the watchdog stays first, `timeout`
  second, the shutdown hook third); the one-image invariant (strengthened — no override remains); the
  rejection of caller tags for machine-observed keys; `--no-database` as the only way to discard
  measurements; `--request-id` returning excluded runs; no command that writes measurements can be
  aimed at a table other than its configuration's.
- **Review findings closed:** none — no open finding in `docs/review/` covers these options.
