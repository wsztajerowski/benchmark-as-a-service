# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm PR #67 (`refactor/cli-diagnostics-to-logger`) is merged and branch this change from
      `main`. Verify: `git log main --oneline` shows `refactor(cli): log diagnostics…`.
- [x] 1.2 Re-run the design's console spike on Linux, JDK 25 (e.g. under `script` in an
      `eclipse-temurin:25` container): `System.console()` null or `!isTerminal()` for `> file` and
      `| cat`, a terminal for the all-terminal case. Verify: results recorded in `design.md`'s spike table
      as a Linux column. If Linux differs, stop and revisit the detection decision before task 2.
- [x] 1.3 Confirm picocli 4.7.7 `Ansi.ON.string("@|bold x|@")` yields an escape sequence and
      `Ansi.OFF.string(…)` yields plain `x`, whatever the `NO_COLOR`/`CLICOLOR_FORCE` environment. Verify:
      a throwaway JShell or scratch run, noted in `verify.md`.

## 2. One console output path

- [x] 2.1 Add `Console` (over a `PrintWriter`, with `interactive` and `colour` flags and a factory that
      derives them from `System.console()`, `isTerminal()`, `TERM` and `NO_COLOR`) with `print`/`printf`/
      `println`, each flushing. Verify: `ConsoleTest` covers the flag derivation for `TERM=dumb`,
      `NO_COLOR`, and absent console (factory takes the environment as an argument so the test needs no
      real terminal).
- [x] 2.2 Move `printJson`, `printCsv` and the `--format json` run summary onto `Console`, keeping
      `Locale.ROOT` and `null` for non-finite values. Verify: `ResultsFormatTest` and
      `RunCommandSummaryTest` assert on a `StringWriter` and no longer call `System.setOut`; their
      expected output is unchanged.
- [x] 2.3 Move the results table out of `ResultsQueryService` into a CLI-side renderer on `Console`, and
      point `RunCommand.showResults` at it. Verify: `ResultsQueryServiceIT`'s "No results found." case
      asserts on a `StringWriter`; the plain table output is byte-identical to before for the same rows
      (a golden-string test).
- [x] 2.4 Move `EnvDiffSubcommand`, `ImageCommand`, `DeployerPolicyCommand` and the `TeardownCommand`
      prompt onto `Console`; the prompt still ends without a newline and is flushed before reading
      input. Verify: `mvn -pl baas-cli -am test` passes, and a manual `baas admin teardown` shows the
      prompt on the input line.
- [x] 2.5 Verify no payload bypasses the path: `grep -rn 'System\.out' baas-cli/src/main` returns only
      Javadoc/comments.

## 3. Coloured tables

- [x] 3.1 Add the shared pad-then-colour table renderer (plain padding and `…` truncation first, colour
      wrap after) and use it for the results table. Verify: a test renders the same rows with colour on
      and off and asserts they are identical after stripping escape sequences; with colour on, the header
      and an `n/a` cell carry escapes.
- [x] 3.2 Render the `baas env diff` table through the same renderer, colouring the two sides distinctly.
      Verify: same strip-and-compare test for the diff table.
- [x] 3.3 Verify machine formats stay plain: a test with colour forced on asserts JSON, CSV, the run
      summary and the deployer policy contain no escape sequence.

## 4. Live status line in `baas run`

- [x] 4.1 Add the status line (`\r` + text + `ESC[K`, synchronized, idempotent `close()`), which while
      open replaces `System.err` with a clear-write-redraw stream and restores it on close. Verify: a unit
      test with a `StringWriter` stdout and a captured real stderr shows a log write arriving as clear →
      log line → redraw, and `System.err` is the original after `close()`.
- [x] 4.2 Use it in `RunCommand.poll` when interactive and not `--format json`; keep the existing
      "Still running…" log line otherwise. Close it in a `finally` and first thing in the shutdown hook.
      Verify: a test of the non-interactive path shows the log line unchanged; `mvn -pl baas-cli -am test`
      passes.

## 5. `baas results --watch`

- [x] 5.1 Add `--watch` with refusal before any AWS client is built: not interactive, or `--format
      json|csv`. Verify: tests for both refusals assert a non-zero exit and that no query was attempted.
- [x] 5.2 Implement the frame loop (clear screen, header with refresh time and "Ctrl+C to stop", table,
      row-derived warnings in the frame instead of the log) at a fixed 30 s interval. Verify: a test renders
      one frame from fixed rows spanning two image versions and finds the environment warning below the
      table and nothing logged.

## 6. Documentation

- [x] 6.1 Rewrite `CLAUDE.md`'s *Diagnostics go to the logger* entry: payload goes through `Console`
      (never `System.out`, because `getOut()` buffers and a stray write interleaves out of order);
      terminal effects only when interactive, defined once; the status line lives on stdout and why.
      Verify: the entry names `Console` and no longer lists `System.out` call sites.
- [x] 6.2 Update the `baas run` and `baas results` sequence diagrams in `docs/diagrams/` if they show the
      poll loop's log line or the results output. Verify: `.mmd` files reflect the status line and
      `--watch`, or are confirmed not to depict them.

## 7. End-to-end verification (manual — no automated test covers the `baas run` path)

- [x] 7.1 On a real terminal: `baas run jmh -- <fixture>` shows one updating status line; a warning
      during the poll appears above it; the results table is coloured. Record the observation in
      `verify.md`.
- [x] 7.2 Same run with `> out.txt`, and with `--format json | jq .`: no escape sequence in either
      (`grep -c $'\e' out.txt` is 0), progress via log lines. Record in `verify.md`.
- [x] 7.3 Ctrl+C during the status line: the line is cleared before "Terminating instance…" and the
      instance terminates. Record in `verify.md`.
- [x] 7.4 `baas results --watch` on a terminal redraws in place; `baas results --watch | cat` is refused.
      Record in `verify.md`.
- [ ] 7.5 Confirm `e2e-cloud-test.yml` passes on the PR with its log unchanged apart from timing.

## 8. Verify

- [ ] 8.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
      from design or tasks.
