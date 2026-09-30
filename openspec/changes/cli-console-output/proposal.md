# Proposal

## Why

Everything `baas-cli` prints for a person goes straight to `System.out`, from seven call sites that
share nothing: two hand-padded tables with their own widths, a results table drawn by a DynamoDB
query class (`ResultsQueryService.printTable`, reused by `RunCommand`), and tests that swap the
process-global `System.out` to see any of it. There is no place to add terminal polish without
touching every site, and no seam a test can hold. The polish itself is worth having: a two-hour
`baas run` prints about 480 identical "Still running…" lines, and `baas results` cannot follow runs
as they land.

Builds on `refactor/cli-diagnostics-to-logger` (PR #67), which settled what is payload and what is a
diagnostic; this change routes the payload side through one path.

## What Changes

- **One console output path.** A `Console` wraps picocli's `getOut()`; every payload `System.out`
  in `baas-cli` main code moves onto it, and the results table moves out of `ResultsQueryService`.
  Tests assert on a `StringWriter` instead of replacing `System.out`.
- **Coloured tables.** The results table and the `baas env diff` table gain colour (header, dimmed
  `n/a`, differing values), only when the output is interactive. JSON, CSV, the run summary and the
  deployer policy are never coloured.
- **Live status line in `baas run`.** When interactive, the poll loop redraws one line (state,
  elapsed time, instance id) instead of logging "Still running…" every 15 s. Log lines emitted
  meanwhile scroll above it. Not interactive — CI, redirects, `--format json` — keeps today's log
  lines exactly.
- **`baas results --watch`.** Re-runs the query on an interval and redraws the table in place.
  Refused outright when the output is not interactive.
- "Interactive" has one definition throughout: `System.console() != null` and `TERM` is not `dumb`.
  `NO_COLOR` disables colour. No new flag, no child process, no new dependency.

No breaking change: every non-interactive invocation produces byte-identical output to today.

## Capabilities

### New Capabilities
- `cli-console-output`: how `baas-cli` writes to the terminal — the single output path, when colour
  and in-place redraw are allowed, the guarantee that no escape sequence reaches a file or pipe, and
  the live status line's coexistence with the logger.

### Modified Capabilities
- `cli-command-structure`: `baas run`'s default human-readable progress is no longer "unchanged" —
  interactively it becomes a live status line.
- `benchmark-results-query`: `baas results` gains `--watch`.

## Impact

- **Code:** `baas-cli` only — a new `Console` (and table/live-line helpers) in the CLI; call sites in
  `ResultsCommand`, `ResultsQueryService`, `RunCommand`, `EnvDiffSubcommand`, `ImageCommand`,
  `DeployerPolicyCommand`, `TeardownCommand`; tests `RunCommandSummaryTest`, `ResultsFormatTest`,
  `ResultsQueryServiceIT`. `benchmark-runner` and `baas-model` untouched.
- **Dependencies:** none added. Colour uses picocli's `Help.Ansi`, already shipped.
- **Cost:** none. No AWS resource, permission or API call changes; `--watch` issues the same single
  partition `Query` per refresh that `baas results` already does.
- **Deliberately not changed:**
  - Payloads stay on standard output and diagnostics on the logger (PR #67's rule); the logger
    format and backend are untouched, so `WARN`/`ERROR` lines stay uncoloured.
  - JSON/CSV formatting, including `Locale.ROOT` and `null` for non-finite values.
  - `e2e-cloud-test.yml` runs without a terminal and so sees no change at all.
  - The three termination layers. The live line is cleared before the shutdown hook's own log
    line on Ctrl+C, but the hook's behaviour is the same.
- **Review findings:** closes none. Leaves P7 (the environment warning fires across all returned
  rows, not per comparison group) as it is.
