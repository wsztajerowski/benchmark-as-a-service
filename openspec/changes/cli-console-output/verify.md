# Verify — cli-console-output

## Blocking assumptions (task 1)

- **1.1** PR #67 rebase-merged (`ec55c3c` on `main`); this branch rebased onto it.
- **1.2** Linux spike (`eclipse-temurin:25-jdk`, JDK 25.0.4.1, under `script`): identical to macOS in
  all eight cases — recorded in `design.md`'s spike table.
- **1.3** picocli 4.7.7: `Ansi.ON.string("@|bold x|@")` → `ESC[1mxESC[21mESC[0m`, `Ansi.OFF` → `x`,
  unchanged under `NO_COLOR=1`, `CLICOLOR_FORCE=1` and `TERM=dumb`. The explicit ON/OFF modes ignore the
  environment, so the `Console` alone decides.
- **2.4** `baas admin teardown --stack-name baas-prompt-check-nonexistent` under `script` (a real
  pty), answering `no-thanks`: prompt and answer on one line
  (`…deletion [baas-prompt-check-nonexistent]: no-thanks`), then `INFO … Aborted.`, nothing deleted.

## /opsx:verify — 2026-09-30

| Dimension | Status |
|---|---|
| Completeness | 18/24 tasks before e2e (all of groups 1–6); 7.x and 8.1 pending the paid runs. 8 requirements across 3 deltas, all implemented |
| Correctness | 8/8 requirements mapped to code and tests; scenarios needing a real terminal + AWS are covered only by 7.x (W2) |
| Coherence | Design followed, one recorded deviation (D1) |

### Requirement → code → test

| Requirement | Code | Test | Gap |
|---|---|---|---|
| Terminal effects gated on one definition of interactive | `console/Console.java` `of(PrintWriter, boolean, Map)` | `ConsoleTest` (terminal, no terminal, `TERM=dumb`, `CLICOLOR_FORCE`); `TableTest`/`ConsoleOutputTest` byte-identical golden strings | Real redirect/pipe observed only in 7.2 |
| `NO_COLOR` disables colour | `Console.of` | `ConsoleTest.noColorKeepsRedrawButDropsColour` | — |
| Machine-readable output never coloured | `ResultsCommand.printJson/printCsv`, `RunCommand.printRunSummary`, `DeployerPolicyCommand` — all via unstyled `println`/`printf` | `ConsoleOutputTest.jsonAndCsvStayPlain…`, `theRunSummaryStaysPlain…`, `printlnNeverStyles` | — |
| Tables coloured when interactive, alignment kept | `console/Table.java` (pad, then style); `results/ResultsTable.java`; `EnvDiffSubcommand.printDiff` | `TableTest.colourChangesNothingButEscapeSequences`, `theHeaderIsBoldAndAnUnknownValueIsFaint`; `ConsoleOutputTest.theColouredDiff…` | Unknown renders `NaN` until #66 merges (W1) |
| `baas run` live status line | `console/StatusLine.java`; `RunCommand.poll`/`openStatusLine`/`statusText`; shutdown hook closes it first | `StatusLineTest` (redraw, log interleave, split writes, payload interleave, close restores `System.err`); `ConsoleOutputTest` gating ×3, 80-column text | Real poll loop, Ctrl+C: 7.1, 7.3 (W2) |
| `baas run --format json` summary (MODIFIED, "Default output is unchanged") | `RunCommand.openStatusLine` returns null under json / non-interactive | `RunCommandSummaryTest` (unchanged expectations, now on a `StringWriter`); `ConsoleOutputTest.noStatusLine…` | — |
| `baas results --watch` | `ResultsCommand.watchRefusal`/`watch`/`printFrame`/`fetch` | `ConsoleOutputTest.watchIsRefused…` ×2, `aWatchFrameCarriesRowWarnings…` | In-place redraw on a terminal: 7.4 (W2) |

### Fixed during verification

- Two now-unused `import picocli.CommandLine;` (`EnvCommand`, `AdminCommand`) removed.
- The status line's `System.err` wrapper encoded with the default charset; it now uses the replaced
  stream's own `charset()`, so a non-UTF-8 stderr is not garbled while the line is shown.
- Task 4.2 promised a test of the non-interactive path; `poll` itself needs AWS, so the gate it
  depends on (`openStatusLine`, made package-private) is tested instead: null without a terminal,
  null under `--format json` on a terminal, a line otherwise, with `System.err` restored after.

### Open warnings

- **W1 — merge conflict with PR #66 (`fix/results-absent-score-error`).** #66 changes
  `ResultsQueryService.printTable` (adds `tableNumber` → `n/a`), which this change moves to
  `ResultsTable`. Whichever merges second resolves it by putting `tableNumber` in
  `ResultsTable.number`. The faint styling already keys on non-finite values, so `n/a` is
  de-emphasised as soon as the two meet. Until then, an unknown value reads `NaN`, as on `main` today.
- **W2 — real-terminal behaviour of the poll loop and `--watch`** — **closed** by 7.1–7.4 below.
- **W3 — stale status line under the results table** — **fixed**, found by paid run 1. After
  `Run status: completed` the line stayed open, so it was redrawn as "running · 0m 31s" under every
  row of the table printed next. `RunCommand.poll` now closes it as soon as a sentinel is read (both
  the normal and the late-status branch). Confirmed by run 2. Residual, deliberately not handled: a
  sentinel whose body is neither `completed` nor `failed:N` would leave the loop polling with no
  progress shown; user-data writes only those two values.

### Deviations

- **D1 — raw SGR codes instead of picocli markup.** Recorded in `design.md` under the `Ansi.AUTO`
  decision: markup parses the styled text, so a `|@` in a benchmark name would need escaping.
- **Suggestion (not acted on):** `ImageCommand`, `DeployerPolicyCommand` and `TeardownCommand` build
  their `Console` inline instead of through a test-settable field, as the commands with tests do.
  None of them has an output test that would use the seam.

## End-to-end (manual, paid) — 2026-09-30

All runs: `baas run jmh` from the branch's reactor build, `--runner-jar` local,
`fake-jmh-benchmarks` `Incrementing_Synchronized -f 1 -wi 1 -i 1` (CI's invocation),
`--tag exclude_from_results=true`, c5.2xlarge on `ami-05bbf52f9c50475b1`. "Terminal" means a real pty via
`script`. Five paid launches of the ten allowed; no instance left running afterwards
(`describe-instances` filtered on pending/running/stopping returned nothing).

| # | Task | Invocation | Observed |
|---|---|---|---|
| 1 | 7.1 | terminal | Status line `pending · 0m 00s` → `running · 0m 15s` → `0m 31s`, redrawn in place (`\r ESC[K`); log lines and the table scrolled above it; bold header; cleared before `Terminating instance…`. **Found W3.** Instance `i-0ba7c8ee7e694bfc4` terminated. |
| 2 | 7.1 | terminal, after the W3 fix | Line cleared once at `Run status: completed`; table printed with no redraws between rows. `BAAS_EXIT=0` |
| 3 | 7.2 | terminal, `> out.txt` | `out.txt`: 0 ESC, 0 CR, the plain table. Terminal: 3 "Still running" log lines, no status line. `BAAS_EXIT=0` |
| 4 | 7.2 | terminal, `--format json \| jq -c .` | One object, parsed by `jq` (`status: completed`, `exitCode: 0`). Terminal: log lines only, no status line. `BAAS_EXIT=0` |
| 5 | 7.3 | terminal, `\003` after ~35 s (`-f 3 -wi 5 -i 10` so it was still running) | `^C`, then `\r ESC[K`, then `INFO … Terminating instance i-0d33f827d24b066ad ...` on a clean line; `BAAS_EXIT=130`; instance `terminated`. |

**7.4 (`--watch`, no EC2):** on a terminal, 3 frames in ~70 s, each `ESC[H ESC[2J` + faint header
`Every 30s · refreshed HH:MM:SS · Ctrl+C to stop`; `\003` exits 130 with the last frame on screen. On
`lynx-journal` the frame shows the bold table header. `baas results --watch … | cat` → `ERROR --watch
needs an interactive terminal…`, exit 2, nothing on stdout. The same query redirected to a file: 0 ESC.

Observed and **not** introduced here (identical on a `main` build under the same pty): the table's
decimals follow the default locale (`3307897,585` under pl-PL) and the `…` truncation marker renders
the same way on both builds; `±0,000` is PR #66's subject (W1).
