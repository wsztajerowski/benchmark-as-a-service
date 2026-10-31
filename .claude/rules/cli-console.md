---
paths:
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/console/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/cli/**"
  - "baas-cli/src/main/java/pl/wsztajerowski/baas/BaasApp.java"
  - "baas-cli/src/main/resources/simplelogger.properties"
  - "baas-cli/src/main/java/**/*Subcommand.java"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/jobs/RunCommand.java"
---

# CLI output: stdout, stderr and the terminal

What a redirect of stdout must keep, and when escape sequences are allowed.

- **`baas -v` needs the argv pre-scan, not just the execution-strategy hook.**
  `LoggingMixin.applyEarlyVerbosity` in `BaasApp.main` looks redundant next to the
  `TestWrapper`-style hook, but SimpleLogger pins a logger's level when the logger is constructed,
  and every `baas` command's `static final Logger` is built while picocli instantiates the
  subcommand tree — before `execute()`. Delete the pre-scan and `-v` **silently stops** raising
  command-level logging. `benchmark-runner` is unaffected only because its loggers live in
  services, constructed later.
- **Diagnostics go to the logger (stderr); command payloads go through `Console`, never
  `System.out`.** `Console` wraps picocli's `getOut()`, which *buffers*, so a stray `System.out`
  write elsewhere lands out of order with everything else — hence all-or-nothing, and no
  `System.out` anywhere in `baas-cli` main code. Payload that must never be styled: JSON, CSV,
  `RunCommand`'s JSON summary, the deployer policy setup prints unattended. A timestamp prefix on any of it breaks
  `--format json | jq` and `--format csv > file`, which is why none of it is logged. The test for
  which side a line belongs on is whether a redirect of stdout should keep it: an empty answer
  ("No results found.", "No differences.") is payload; the unknown-`--tag` warning, both
  `ImageCommand` warnings and `BaasApp.reportFailure` are not, and are logged. The one stderr text
  that bypasses the logger is the `Next` hint block (`console.Hints`), written to picocli's
  `getErr()`: it is a command to copy, and a timestamp, level and class name in front of it are
  what buried it.
- **Terminal effects happen only when `Console` says interactive: `System.console()` +
  `isTerminal()` + `TERM != dumb`, decided once.** Every misdetection is a *no*, so the worst case
  is today's plain output and never an escape sequence in a file. `NO_COLOR` drops colour.
  picocli's `Ansi.AUTO` is deliberately not used — it colours `TERM=dumb`, and `CLICOLOR_FORCE`
  makes it colour a pipe. Tables pad *then* colour (`console.Table`), so columns never move.
- **`baas run`'s status line is on stdout, not stderr, on purpose.** Java cannot tell whether
  stderr alone is a terminal (the `cli-console-output` spike: a child `test -t 2` or `/dev/fd/2`
  can, at a cost), and a wrong guess writes `\r` into `2> run.log`. Gated by `System.console()`,
  every wrong guess falls back to the log line instead. While shown, it swaps `System.err` for a
  clear-write-redraw stream — SimpleLogger resolves `System.err` per write, which is what makes
  that reach it — and the shutdown hook closes it *before* logging the termination.
- **Log lines are short and level-coloured only when stdout is a terminal *and* `/dev/fd/2` is the
  same file as `/dev/fd/1`** (`console.LogStyle`, applied in `BaasApp.main` before any logger
  exists, like `-v`): `WARN <message>`, the level in Maven's colours (`ERROR` red, `WARN` yellow,
  `INFO` blue, `DEBUG` cyan), no timestamp or logger name. The `/dev/fd` test is what the status line
  above lacked — cheap, and made only once stdout is known to be a terminal; anything it cannot answer
  counts as *no*. Everywhere else — `2> run.log`, a pipe, CI — lines keep
  `simplelogger.properties`' full format with no escape sequence. Colour is a filter installed as
  `System.err` that touches only a line whose first word is a level, so the `Next` block and stack
  traces pass through; `run`'s status line wraps on top of it. There is no log file: SimpleLogger has
  one target and one format, and a file appender would mean another backend.
- **`printJson`/`printCsv` must format with `Locale.ROOT`.** Under a comma-decimal locale (pl-PL
  among many) a bare `%.6f` emits `8234574,731914`, which is not a JSON number and splits a CSV
  column in two — silently, and only on some machines. Non-finite values become JSON `null`, since
  JSON has no `NaN` literal and JMH reports one for any single-iteration run.
