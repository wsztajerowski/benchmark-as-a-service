# Design

## Context

After PR #67 (`refactor/cli-diagnostics-to-logger`), `baas-cli` has a clean split: diagnostics go
through SLF4J SimpleLogger to stderr, and payloads go to `System.out`. The payload side has no
structure yet:

| Writer | What it prints |
|---|---|
| `ResultsCommand.printJson` / `printCsv` | JSON / CSV |
| `ResultsQueryService.printTable` | the results table — a query class drawing output, reused by `RunCommand.showResults` |
| `RunCommand.printRunSummary` | the `--format json` summary |
| `EnvDiffSubcommand` | its own diff table and "No differences." |
| `ImageCommand` | image details |
| `DeployerPolicyCommand` | the rendered policy |
| `TeardownCommand` | the confirmation prompt, on the same line as the input |

`RunCommandSummaryTest`, `ResultsFormatTest` and `ResultsQueryServiceIT` capture output by swapping the
process-global `System.out`.

Picocli's `getOut()` is `new PrintWriter(new BufferedWriter(…), true)`: it flushes on
`println`/`printf`/`format` and **not** on `print`. So a same-line prompt and a `\r` redraw each
need an explicit flush.

### Spike findings (JDK 25, macOS and Linux, run under a real pseudo-terminal)

| Case | `System.console()` / `isTerminal()` | picocli `Ansi.AUTO` | stdout a terminal | stderr a terminal |
|---|---|---|---|---|
| all a terminal | yes | on | yes | yes |
| `> file` | no | off | no | yes |
| `2> file` | **yes** | **on** | yes | **no** |
| `\| cat` | no | off | no | yes |
| `< /dev/null` | **no** | off | yes | yes |
| `TERM=dumb` | yes | **on** | yes | yes |
| `NO_COLOR=1` | yes | off | yes | yes |
| no terminal (CI) | no | off | no | no |

Columns 3 and 4 were checked two ways, which agreed: a child `sh -c 'test -t N'` (about 12 ms) and the
`/dev/fd/N` file type (`unix:mode`, and `unix:rdev` to tell `/dev/null` apart, about 40 ms). Separately,
SimpleLogger resolves `System.err` on every write: after `System.setErr(wrapper)`, log lines went
through the wrapper.

Repeated on Linux (task 1.2: `eclipse-temurin:25-jdk`, JDK 25.0.4.1, under `script`): every row
identical to macOS.

What follows from this:
- `System.console()` answers "is stdout a terminal?" correctly, but only errs towards *no* (the stdin
  case).
- It cannot see stderr at all.
- `Ansi.AUTO` gets `TERM=dumb` wrong, and it honours `CLICOLOR_FORCE`, which forces escape sequences
  into pipes.

## Goals / Non-Goals

**Goals:**
- Every payload byte in `baas-cli` passes through one object a test can construct with a
  `StringWriter`.
- Terminal effects can never reach a file or a pipe. When detection is wrong, the output loses polish;
  it never becomes corrupt.

**Non-Goals:**
- Colouring log lines. SimpleLogger has no colour; changing the backend is its own decision.
- A live display for `baas admin build-image`'s ~15-minute poll. It can reuse the status line later,
  but it is left out here to keep the change small.
- Terminal-width awareness, Windows-specific handling, and an `--interval` or `--no-color` option.
- Any change to the runner, the image or user-data. **Comparability with existing results is
  unaffected**: this change is confined to how the laptop-side CLI renders output it already has.

## Decisions

### One `Console` per command wraps picocli's `getOut()`, and all payload goes through it

Each command obtains its `Console` from its `@Spec`'s `commandLine().getOut()`. The `Console` offers
`print`/`printf`/`println` (plain, flushed), a table renderer and a status line. Tests construct it
directly with a `StringWriter` and explicit `interactive`/`colour` flags.

Every `System.out` call site in `baas-cli` main code moves in the same change. `getOut()` buffers, so a
leftover `System.out` write would interleave out of order with it. A partial migration is worse than
none.

- *Rejected: keep `System.out` and add a colour helper beside it.* That gives no test seam and leaves
  the ordering hazard wherever the two are mixed.

### "Interactive" is `System.console()` + `isTerminal()` + `TERM != dumb`, decided once

`interactive = console != null && console.isTerminal() && !"dumb".equals(getenv("TERM"))`.
`colour = interactive && getenv("NO_COLOR") == null`. Both are computed when the `Console` is created
and never re-checked.

`isTerminal()` (JDK 22+) is added to the null check as a guard: the spike showed `console()` itself
returning null for redirects on JDK 25, but a JDK built with a different default console provider can
return a non-terminal `Console`. The guard costs nothing.

- *Rejected: a per-stream check (a child `test -t N`, or the `/dev/fd/N` file type).* Both were exact
  in the spike, but they are only needed to put the status line on stderr. With the line on stdout,
  `System.console()` is already the right question. One adds a child process and a POSIX `sh`
  dependency; the other relies on `unix:` attributes outside the public API and is unverified on
  Linux.
- *Rejected: picocli `Ansi.AUTO`.* It says on for `TERM=dumb`, and `CLICOLOR_FORCE` makes it emit
  escape sequences into pipes. That breaks the guarantee in the spec.
- *Rejected during implementation: picocli `Ansi.ON`/`OFF` markup for the styling itself.* Task 1.3
  confirmed the explicit modes ignore the environment, so they would have worked. But markup
  (`@|bold …|@`) parses the styled text, and a benchmark name or tag value containing `|@` would need
  escaping. The `Console` writes the four SGR codes it needs (bold, faint, red, green) directly, which
  is shorter than escaping the text.
- *Rejected: JLine or Jansi.* A new dependency for what four environment and console checks already
  answer.

### Tables pad first, then colour, through one small shared renderer

A cell is formatted to its width in plain text, which preserves the existing truncation with `…`, and
only then wrapped in colour. So escape sequences never count towards a column's width. The results
table and the env-diff table share this renderer, and `printTable` moves out of
`ResultsQueryService`. Colours: bold header; faint `n/a`; the env-diff sides in two distinct colours.
The exact palette can be tuned during implementation.

- *Rejected: picocli `Help.TextTable`.* It measures visible width correctly, but it is built for
  usage screens: it wraps at a column width and has its own overflow modes. It would replace working
  truncation code with an API we would have to bend.

### The status line lives on stdout, and wraps `System.err` while it is shown

Drawing it: `\r` + text + `ESC[K`, then flush. Clearing it: `\r` + `ESC[K`. The text (state, elapsed
time, instance id) stays short enough not to wrap on an 80-column terminal, so it needs no width
detection.

While the line is shown, `System.err` is replaced by a stream that, before each write, clears the line
on stdout, writes to the real stderr, flushes, and redraws the line. The spike confirmed SimpleLogger
writes through a replaced `System.err`. The status line's methods are `synchronized`, and `close()`
is idempotent and restores the real `System.err`. The shutdown hook calls `close()` before logging
"Terminating instance…", and the normal path calls it in a `finally` block.

Not interactive, or `--format json`: the status line is never created, and the existing
`logger.info("Still running…")` path runs unchanged.

- *Rejected: stderr.* It is the conventional stream for progress, but only a per-stream check can
  see stderr (see above), and a wrong guess writes `\r` and escape sequences into `2> run.log`. On
  stdout gated by `System.console()`, every wrong guess falls back to today's log lines. The cost is
  that `baas run --format json > r.json` shows log lines, not the live line.

### `--watch` clears the screen per frame, at a fixed 30-second interval

Each refresh runs the same queries and filters as one plain invocation, builds a frame (a header line
with the time of the refresh and "Ctrl+C to stop", the table, then row-derived warnings), and writes
`ESC[H ESC[2J` (cursor home, clear screen) followed by the frame. The row-derived warnings are the
environment warning, the unknown-tag warning and the `--limit` note, which are logged in non-watch
mode.

The interval is fixed at 30 s: a run lands minutes apart, and one partition `Query` per refresh is
negligible on-demand read cost. Refusal happens in validation, before any AWS client is built. A query
failure ends the command the same way it ends a plain `baas results`.

- *Rejected: cursor-up by the previous frame's height.* A frame taller than the terminal scrolls, and
  cursor-up then overwrites the wrong lines. Clearing the screen has no such failure, and it is how
  `watch(1)` behaves.
- *Rejected: logging row-derived warnings on each refresh.* They would scroll the frame every 30 s,
  and repeat the same text indefinitely.
- *Rejected: an `--interval` option.* Nobody has asked for one, and it can be added later without
  changing anything else.

## Risks / Trade-offs

- [A `System.out` call site added later bypasses the `Console` and interleaves out of order] →
  `CLAUDE.md`'s *Diagnostics go to the logger* entry is rewritten to name the `Console` as the payload
  path, and the task list ends with a check for `System.out` in `baas-cli` main code.
- [Stdin redirected (`baas results < /dev/null`) loses colour and the live line on a real terminal] →
  Accepted. It degrades to today's output, which is the chosen direction for every wrong guess.
- [A `--watch` frame taller than the terminal cuts off its top rows] → `--limit` bounds the frame.
  The screen is cleared each time, so rows can be lost from view but never overwritten wrongly.
- [The shutdown hook runs on another thread while the poll loop redraws] → the status line's
  methods are synchronized, and a draw after `close()` does nothing.
- [Wrapping `System.err` also captures stderr writes that are not log lines, such as the AWS SDK's
  own warnings] → Harmless: those get the same clear-write-redraw treatment.

## Migration Plan

None. There is no configuration and no stored state, and every non-interactive invocation is
byte-identical to before. Rollback is a revert.

## Open Questions

- The exact colours and the exact status line wording. Both are cosmetic and can be settled in
  review.

## Resolved Questions

- **Which stream the status line goes to:** stdout. The spike (table above) shows stderr can only be
  detected per stream, at the cost of a child process or unverified internal attributes, while stdout
  gated by `System.console()` makes every wrong guess safe.
- **One change or two:** one change, delivered as four independently reviewable task groups.
- **`--watch` without a terminal:** refused, not degraded to repeated snapshots.
