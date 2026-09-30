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
- **W2 — real-terminal behaviour of the poll loop and `--watch`** is covered only by the manual
  tasks 7.1–7.4 (no in-process test can own a pty and an EC2 instance). Closes when 7.x is recorded.

### Deviations

- **D1 — raw SGR codes instead of picocli markup.** Recorded in `design.md` under the `Ansi.AUTO`
  decision: markup parses the styled text, so a `|@` in a benchmark name would need escaping.
- **Suggestion (not acted on):** `ImageCommand`, `DeployerPolicyCommand` and `TeardownCommand` build
  their `Console` inline instead of through a test-settable field, as the commands with tests do.
  None of them has an output test that would use the seam.
