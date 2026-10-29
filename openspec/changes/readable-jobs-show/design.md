# Design

## Context

See proposal.md for motivation. The current state that shapes the approach:

- `UserDataScriptBuilder` captures every manifest value into a shell variable, then writes a heredoc made
  only of `${VAR}` references (CLAUDE.md invariant). `CPU_CORES=$(nproc)`; memory is `awk` over
  `/proc/meminfo` in KiB; `JDK_VERSION` is already extracted from the `java -version` banner, but only
  for the `jdk` tag. Its comment says the value is `"25"`; the `sed` gives `25.0.4`.
- `EnvironmentManifest.parse` flattens any JSON object into `group.field` strings with a default Jackson
  `ObjectMapper`, which ignores trailing tokens (X7). `diff` compares those strings, except the two memory
  fields, which it converts from KiB to whole MiB at compare time (`KIB_FIELDS`, `compared`).
- `JobsShowSubcommand.printView` joins each group's raw `key value` pairs with ` · `; `printJson` splices
  the stored manifest text into its output (X7). Artifacts come from `listKeys`, keys only, though the
  `ListObjectsV2` response it reads carries each object's size.
- `console.Hints` writes `→ <purpose>: <command>` through a logger, so SimpleLogger adds a timestamp,
  level and class. Callers: `jobs show`, `jobs list`, `results query`, `run` (which passes a sink).
- Comparability: the change alters what is *recorded about* a job, never what runs on the instance or
  how. The benchmark command, the JVM, the image and the measurements are untouched, so results before
  and after are as comparable as before. Only their manifests differ in schema.

## Goals / Non-Goals

**Goals:**
- One place maps a manifest field to its display form, and an unknown field still prints.
- The schema-7 capture keeps the user-data invariants: no `set -e`, values captured first, no comment
  line relied on, output well under the 12 KB test bound.

**Non-Goals:**
- Reading v6 manifests specially, in `show` or `diff`. They parse and print generically.
- Changing `results query`'s output or the general `Console` text path (X7's remainder).
- Making `jobs diff`'s columns fit long values (E5).

## Decisions

### The display formatting lives in the CLI, and the manifest stays raw where it is merely ugly

Formatters are a map from `group.field` to a function over the stored string, applied only in `show`'s
human view; any field without one, or whose value a formatter cannot parse, prints as recorded. The
manifest changes only where a value was misleading (`cores`) or noisy for comparison (KiB), plus
`jvm.release`, which makes the `jdk` tag checkable against the file.

- *Rejected: normalise every value at capture (bare versions, `always` for THP).* The raw banners carry
  build dates and vendor strings `diff` should see change, and parsing in shell is the quoting risk the
  capture-into-variables invariant exists to contain.

### Renamed fields get new names, never a reused one, and no alias

`cores` (vCPUs) becomes `vcpus`, and the physical count gets its own `physicalCores`; `totalKb` becomes
`totalMib`. Reusing `cores` for the physical count, or `totalKb` for MiB, would make a v6↔v7 diff
report `cores 8 → 4` or a thousandfold memory drop as an environment change.

- *Rejected: alias v6 names onto v7 at parse time.* The user declined it: BaaS holds only test results,
  and only v7 manifests will be compared. Without an alias a v6↔v7 diff lists the renamed fields as
  present on one side, under the existing schema warning, which fails safe.

### Memory is rounded down to whole MiB at capture, in `awk`

`awk '/MemTotal/ {print int($2/1024)}'`, the rule `diff` already applied at compare time, moved to the
source. Two instances of one type a few KiB apart only split when they straddle a MiB boundary
(≈0.8 % for 8 KiB).

- *Rejected: a coarser unit (64 MiB, tenths of GiB).* Fewer straddles, but it discards a real
  difference between instance types; whole MiB already hides the noise nearly always.

### `physicalCores` is computed from two `lscpu` fields, guarded in shell

`Socket(s)` × `Core(s) per socket`, both through the existing `lscpu_field`; when either is empty or
not a number the variable is left empty instead of running `$(( ))` on it, which would print a bash
error and leave the value undefined under the script's no-`set -e` rule.

- *Rejected: count unique `CORE` ids from `lscpu -p`.* Its output lines begin with `#`, so the filter
  would be a `grep -v '^#'` inside the script, a pattern that sits next to the comment-stripping rule;
  the multiplication is simpler and gives the same number on every supported instance type.

### `show` sanitises at the point of printing, and JSON re-serialises the parsed manifest

Every runner-written string `show` prints (tags, environment values, error code) passes through one
`printable()` that removes C0/C1 control characters except tab. `printJson` builds the `environment`
member from the parsed map with Jackson; `parse` enables `FAIL_ON_TRAILING_TOKENS`, and `show` treats a
parse failure as an unreadable manifest (stated, `null` in JSON, exit 0), consistent with "a part that
does not exist is stated".

- *Rejected: strip in `Console.print`.* It would close X7 everywhere, but `Console` also receives text
  it has already coloured, and stripping there removes the escape codes it adds itself. That fix needs
  a separate design and stays with X7.
- *Rejected: keep failing the command on a bad manifest.* The job item and the artifact list are still
  worth showing, and `diff` keeps its fail-naming-the-job behaviour for the same parse error.

### Hints are written as a plain block to the command's standard-error writer

`Hints.show` keeps its signature shape but takes a `PrintWriter` (picocli's `getErr()`), and prints
`Next` followed by the hints, purposes padded to the longer one. The logger is no longer involved.
`run` passes the same writer instead of `logger::info`. Its status line must be closed before the
hints print, or the clear-redraw stream interleaves with them; a task confirms that order and
pins it with a test. The `Consumer<String>` overload stays for tests.

- *Rejected: put the hints on standard output.* A redirect of stdout must not keep them (CLAUDE.md's
  payload test), and `jobs show > file` would carry them.
- *Rejected: format hints only in `jobs show`.* Two hint styles across commands is worse than the old
  one; the helper is shared, so the change is in one place.

### Artifact sizes come from the listing `show` already makes

`S3UploadService` gains a listing that returns key and size from the same `ListObjectsV2` pages. Groups
are decided by name, from `JobLayout`/`BenchmarkType` where the name is known: *output* (boot log,
process output, `launch-error.txt`, `logs/`), *environment* (`environment.json`, `packages.txt`),
*results* (`jmh-result.json`), *inputs* (`input/`), and *reports* for everything else (JCStress pages,
profiler folders). A folder prints as one line with its file count and total size. The JSON
`artifacts` member stays a list of relative keys.

- *Rejected: add sizes to the JSON `artifacts` member.* A shape change for scripts nobody asked for; it
  can come with G12-style machine-readable work.

### `exclude_from_results` becomes `hidden` everywhere it is named, with no alias and no migration

The tag key, the `results query` option (`--show-hidden`) and the `jobs show` marker (`hidden`) all
change together, so no surface names a tag that no longer exists. The model constant is
`TagKeys.HIDDEN`; the filter expression's names follow it. The rule itself is untouched: the same
server-side filter, the same exemptions (`--job-id`, `jobs list`), the same faint rendering.

- *Rejected: rename the tag only, keep `--show-excluded`.* The user chose all three: an option
  describing a tag that no longer exists is the stale name this rename removes.
- *Rejected: read both keys for a while.* One more condition in each filter expression and in
  `ResultRow`, kept until someone remembers to delete it. BaaS holds no production data, so old rows
  becoming visible costs nothing; `--exclude-tag exclude_from_results=true` hides them by hand.

## Risks / Trade-offs

- [A formatter misreads a value an extension changed, e.g. another vendor's version string] → every
  formatter falls back to the raw value when its pattern does not match, and a test pins that.
- [`lscpu` on some instance type omits `Socket(s)`] → `physicalCores` is recorded empty, and `show`
  hides it; nothing else reads it.
- [v6 jobs on the test deployments now diff noisily against new jobs] → accepted; removing them is a
  manual step after release (deployer credentials), not part of this change.
- [A script reading `environment.json` by v6 key breaks] → no such consumer is known; it ships as a plain `feat` (Resolved Questions), and the proposal
  names the break for the file contract.

## Migration Plan

Ship with the next release; no AWS or stack change. Jobs launched by the new CLI write schema 7; old
jobs keep schema 6 and still show and diff generically. Rollback is an older CLI: it writes schema 6
again, and reading a v7 manifest it prints the new field names raw.

## Resolved Questions

- *Does the release carry a breaking marker for the manifest's key renames?* No: plain `feat`, decided by the
  user on 2026-10-29 (verify.md W1). The file's only readers are this CLI's `jobs show` and `jobs diff`, which
  ship in the same release, and BaaS holds no production results yet.
