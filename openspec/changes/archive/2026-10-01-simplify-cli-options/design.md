# Design

## Context

See `proposal.md` for motivation. The current state that shapes the approach:

- `ConfigService` reads and writes a hard-coded `~/.baas/config.yaml` (`CONFIG_FILE` constant) and is
  constructed with `new ConfigService()` in 12 places. Unknown YAML keys are ignored
  (`FAIL_ON_UNKNOWN_PROPERTIES=false`).
- `baas config set` takes **flags** (`--timeout`, `--max-wall-clock`, `--instance-type`, …), not
  `key value` pairs.
- `RunCommand` resolves `project`, `branch` and `commit` from git in the working directory
  (`resolveProject`, `currentGitBranch`, `currentGitCommit`, all through `GitProject`).
  `ResultsCommand` resolves `project` the same way and shells out to `git branch -r` for
  `--living-branches`.
- The watchdog delay is `--max-wall-clock`, else `--timeout + 300` when `--timeout` was given, else
  config `ec2.wallClockHardKillSeconds` (7500). With a raised config timeout and the default wall
  clock, the watchdog fires before the benchmark's own timeout.
- `ResultsQueryService` has two access paths: a project-partition `Query` with a server-side
  `exclude_from_results` filter, and a `requestId-index` `Query` with none. No index spans projects.
- `ResultRow` already carries the full tag map; JSON and CSV project only `imageVersion` and
  `instanceType` from it. `printCsv` does no quoting.
- `e2e-cloud-test.yml` passes no `--project`, `--branch` or `--commit`: it relies on git derivation
  from the checkout, and asserts `.imageVersion`/`.instanceType` from `baas results --format json`.

## Goals / Non-Goals

**Goals:**

- Every input that reaches a stored measurement or a query is named on the command line or opted into
  in configuration; none comes from the directory the command happened to be typed in by default.
- One way to address another installation, applying uniformly to every command.
- The watchdog can never fire before the benchmark's own timeout.

**Non-Goals:**

- Options of `download` (beyond the removed overrides), `env diff`, `config` and `admin …` — a later,
  smaller pass.
- Explaining an empty `baas results` (the parked "why is it empty" topic). The picker removes the
  commonest cause — landing on a project with nothing visible — but a dedicated hint is not built here.
- Guarding two concurrent `build-image` runs, or anything else on the image path.
- Changing the runner, the image, or what user-data does. **Comparability:** no effect. The runner
  receives the same arguments, and the default watchdog delay renders the same 7500 s; only the
  provenance of `project`/`branch`/`commit` changes, and those are labels, not measurement inputs.

## Decisions

### `--config-path` is one inherited root option, and `ConfigService` takes its path

Declared once on `BaasApp` with picocli `scope = INHERIT`, so it parses on either side of the
subcommand. Commands obtain a `ConfigService` built from the resolved path instead of calling
`new ConfigService()`; the constant becomes the default. Missing-file handling distinguishes an
explicit path (read commands fail naming it; `config sync`, `config set`, `admin setup` create it) from
the default path (unchanged: an empty config, then the existing "no installation" error).

*Rejected:* keeping `--results-table`/`--bucket` per command — two mechanisms for one need, and only on
the commands someone remembered to add them to. A `BAAS_CONFIG` environment variable — a second way to
do the same thing, and invisible in a pasted command line.

### `git.resolveProject` is an opt-in preference, anchored on the JAR for `run` and the working directory for `results`

Off by default, set with `baas config set --git-resolve-project <true|false>` (arity 1, so it can be
turned back off). `baas run` resolves from the directory containing `--benchmark-jar`, because the JAR
is what is being measured and the shell's directory is incidental; `baas results` has no JAR, so it
resolves from the working directory — acceptable there because the operator asked for it, and because a
wrong guess only reads the wrong partition, it records nothing. `GitProject.repositoryName(Path)`
already takes a path and resolves the main repository through worktrees, so it is reused unchanged.

*Rejected:* keeping the working-directory default — the reason for the change. Removing git entirely —
loses a real convenience for an operator who always runs from one checkout.

### `branch` and `commit` are plain caller tags

`--branch`/`--commit` are deleted along with their git derivation; `--tag branch=…`/`--tag commit=…`
already reach the same tag keys and already pass the reserved-key check (both are caller-overridable).

*Rejected:* keeping the two options without derivation — two spellings of one input.

### The watchdog bound is `timeout + margin`, and the margin has a floor of 60 s

`--watchdog-margin` / `ec2.watchdogMarginSeconds` (default 300) replace the absolute bound. The value
passed to `UserDataScriptBuilder` and to `poll` is computed in one place as `timeout + margin`, so the
two cannot drift. The floor exists because the watchdog counts from launch while `timeout` counts from
JVM start: the margin must cover boot plus the final upload, or the watchdog kills the instance before
`run-status` is written and the run looks like it vanished. A leftover `wallClockHardKillSeconds` key
is ignored by the existing unknown-key setting; no migration code.

*Rejected:* keeping the absolute bound and rejecting `wallClock <= timeout` — closes the gap but keeps
two settings where one carries the meaning. A fixed, non-configurable 300 s — the operator asked to keep
the margin tunable.

### `--ami-id` is removed outright

There is no second BaaS image to name. Its spec requirement is removed rather than amended.

### Projects are listed with a `Scan`, not with a registry item or an index

The picker and `--all-projects` both need every partition. The picker's `Scan` applies the partition
query's exclusion filter server-side and projects only the item's top-level `project` attribute; the
distinct values are collected client-side. (As implemented — the first draft projected `pk` and the
exclusion tag and filtered client-side; filtering server-side reuses the one filter expression and
returns less, and the bill is the same either way since a `Scan` is charged for what it reads.) `--all-projects` is a full-item `Scan` with the same server-side
exclusion filter used by the partition query (dropped under `--all-runs`), paginated to exhaustion like
`runQuery`.

*Rejected:* a `PROJECT#` registry item written by the runner — violates *no derived index items* and
needs a runner change. A GSI on project — a new standing structure to serve a listing that costs cents.

### The picker is a numbered prompt on `System.console()`

Shown only when `Console` says interactive and the format is the table — `System.console()` non-null
already implies a terminal on stdin as well as stdout. It prints `1) a  2) c …` and the tip, reads one
line, and maps it to a project; anything else exits non-zero without querying. When no answer is
possible (redirect, pipe, `--format json|csv`) the command fails listing the projects — never guesses,
never blocks. Under `--watch` the prompt runs once, before the alternate screen is entered.

*Rejected:* an arrow-key menu — needs raw terminal mode, so JLine (~1 MB) or `stty` subprocesses, for
choosing among a handful of names.

### `--all-runs` lifts both grouping and exclusion

One flag for "show me everything for this scope". Excluded rows are de-emphasised in the interactive
table through the existing `console::faint`, so they stay distinguishable without `-v`.

*Rejected:* a separate `--include-excluded` — a third flag for a case that only matters in fixture
projects, where CI runs are the excluded ones.

### Grouping is keyed by project as well

`ResultsGrouping.bestPerGroup` keys on `(project, benchmark, group tag)`. Under a single project it is
a no-op; under `--all-projects` it prevents two projects' identically named benchmarks merging.

### Tags are printed under a row with `-v`, and always in machine formats

`ResultsCommand` reads verbosity from its `LoggingMixin`. The tag line is indented, sorted by key, and
not part of the column grid, so `console.Table` alignment is untouched. JSON gains a `tags` object and
**keeps** the flat `imageVersion`/`instanceType` fields, which CI asserts. CSV gains a `tags` column;
because tag values are free-form, that field is quoted per RFC 4180 (doubling embedded quotes) — the
first CSV field that needs it. `scoreError` stays in JSON and CSV; only the table loses `±ERROR`.

*Rejected:* a separate `--tags` flag — the operator chose to tie it to `-v`. Its cost: tags arrive with
debug logging on stderr, which `2>/dev/null` drops.

## Risks / Trade-offs

- [A full-table `Scan` grows with history, and `--watch --all-projects` pays it every 30 s] → negligible
  at today's size; on-demand billing means no standing cost. Revisit with a registry only if the table
  grows by orders of magnitude.
- [Scripts using removed options break] → they break loudly, with picocli's unknown-option error naming
  the option; no removed option is silently ignored.
- [A torn-down installation cannot be adopted by `config sync`, which verifies the stack] → reading its
  archive needs the old config file or a hand-written one; stated in the REMOVED requirement's
  migration and in CLAUDE.md.
- [`-v` couples tag display to debug logging] → accepted; stdout stays clean either way.
- [CI loses `project`/`branch`/`commit` if the workflow edit is missed] → the run fails immediately on
  the missing `--project`, before provisioning; the same PR edits the workflow.
- [Free-form tag values in CSV] → quoted, with a test for a value containing `,`, `;` and `"`.

## Migration Plan

1. Land code, specs, `e2e-cloud-test.yml`, README, `docs/diagrams/baas-run.mmd` and CLAUDE.md in one PR,
   so CI exercises the new flags on the PR that introduces them.
2. Operators who relied on derivation run `baas config set --git-resolve-project true` once.
3. Rollback: revert the PR. Configuration written in between stays readable by the old CLI — the new
   keys are ignored as unknown, and `wallClockHardKillSeconds` falls back to its default.

## Resolved Questions

- *Should dropping the git default cover `baas run` too?* — Yes, but as an opt-in config flag, with the
  project resolved from the benchmark JAR's repository; `branch`/`commit` are never resolved.
- *Flag default?* — Off; enabled with `baas config set`.
- *How to reach another installation without `--results-table`?* — A global `--config-path`, replacing
  `download`'s `--results-table` and `--bucket` too.
- *How are tags shown in the table?* — An indented line under each row, only with `-v`.
- *Drop `±ERROR` everywhere?* — Table only.
- *Name of the opt-in "every measurement" flag?* — `--all-runs`.
- *Without `--project`?* — A numbered picker on a terminal, `--all-projects` for everything with a
  PROJECT column.
- *Picker style?* — Numbered list; no JLine.
- *`exclude_from_results`?* — Picker lists only projects with a visible row; `--all-runs` includes
  excluded rows; `--all-projects --all-runs` is the whole table.
- *`--branch`/`--commit`?* — Removed in favour of `--tag`.
- *Wall clock?* — A relative `--watchdog-margin`, default 300, floor 60.
- *`--ami-id`?* — Removed.
- *Does `git.resolveProject` affect `baas results`?* — Yes: it resolves from the working directory and
  skips the picker; the picker's tip names the flag.
