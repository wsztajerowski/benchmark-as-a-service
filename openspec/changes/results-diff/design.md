# Design

## Context

See proposal.md — Why. The parts of today's code the design leans on:

- Every measurement item carries a required `kind` (`MeasurementKind.JMH` / `JCSTRESS`;
  `StoredMeasurement` throws without it) and a free-form `type` tag (`jmh`, `jmh-with-async`,
  `jmh-with-prof`, `jcstress`). A job runs exactly one `BenchmarkType`, so all of a job's
  measurements share one kind.
- `ResultsQueryService.queryByJobId` reads one job with one `Query` on `jobId-index`, filtering
  `attribute_exists(kind)` so the job's own job item (same index partition, `gsi1sk = JOB`) drops out.
- `ResultsGrouping` already keys `--best-per` on `(project, benchmark, params, mode, tag)` and owns
  `lowerIsBetter(mode)`.
- `JobReference` (job-id syntax check, "no such job" wording) lives in `jobs` and is package-private.
  ADR 0009's slice order is deployment → jobs → {image, results}: `results` may import no slice.
- `JobsDiffSubcommand` is the model for the command's shape: id checks before any AWS call, one
  lookup at a time, payload through `Console`, warnings through the logger.

## Goals / Non-Goals

**Goals:**
- The library guard and the JCStress refusal happen before any pairing, from stored kinds alone.
- One `Query` per job, no `Scan`, no S3 read.
- Every number the table shows is one the store holds, or a delta of two numbers in the same unit.

**Non-Goals:**
- Selecting a side by tags, or "best of several jobs" per side (a later verb option; see Decisions).
- A JCStress verdict diff, unit conversion, a direction vocabulary for secondary metrics, a
  `--fail-on-regression` exit code, CSV. Each was decided out in exploration and can be added
  without changing the output shape defined here.
- Calling into `jobs diff` or reading `environment.json`.

## Decisions

### A side is one job id

Mirrors `jobs diff`, makes the library guard one check per side, and keeps the read to one `Query`
each. Rejected: tag selections (`--a branch=main --b branch=feature`). They can mix libraries inside a
side — the guard becomes a partition rather than a refusal — and must choose which of several jobs
represents a side. Real, but a second feature.

### The guard reads `kind`, never the `type` tag

`kind` is required by the model; `type` is a tag the runner writes and nothing enforces. A future
library gets its own `MeasurementKind` and is refused against the others with no new rule. Because a
job has one kind, the guard takes each side's kind from its first measurement; a job with no
measurements has no kind and can neither trip nor pass the guard — its partner's rows are all
one-sided. Should a job ever hold mixed kinds (no runner writes one), the command refuses it as
corrupt rather than picking one.

### Existence comes from the job item, read in the same `Query`

`queryByJobId` returns nothing both for an unknown id and for a job that measured nothing, and the
diff must tell them apart (refuse the first, list the partner's rows one-sided for the second).
A new `ResultsQueryService` method queries the same index partition *without* the `kind` filter and
splits the page: the item without `kind` is the job item (existence), the rest go through
`MeasurementItemMapper.fromItem`. Still one `Query`. Rejected: a second lookup through `JobPaths`
(lives in `jobs`, which `results` may not import, and doubles the calls); reading the job item by
its primary key (needs `createdAt`, which only the index knows). Every job since
`run-status-in-dynamodb` has a job item, and every deployment was rebuilt after it.

### `JobReference` moves to the kernel's `cli` package

Both nouns now take a job id and must refuse a malformed one with the same words. `cli` is the kernel
package for argument handling and may be imported by every slice; `JobReference` imports nothing, so
the move adds no kernel edge. Rejected: placing `ResultsDiffSubcommand` in `jobs` (packages are the
command tree's nouns, ADR 0009, and `SliceBoundaryTest` would then pass a command filed under the
wrong noun); duplicating the regex (two definitions of a job id drift); moving it to `baas-model`'s
`JobId` (CLAUDE.md: the id's format is a readability convention, not a contract — the model should
not start validating it on others' behalf).

### Pairing reuses `--best-per`'s identity and direction

Pair key: benchmark (fully qualified), mode, `paramsKey()`. Project is deliberately *not* in the key:
two jobs of different projects with the same benchmark class are a legitimate comparison (a fork, a
renamed repository), and each side is one job, so there is no cross-project ambiguity within a side.
Direction is `ResultsGrouping.lowerIsBetter`, made package-visible and shared — not copied, so the
two commands can never disagree on which way `sample` points.

### The verdict is interval overlap; the delta is always shown

`Δ% = (B − A) / |A| × 100`. Verdict: `?` when a score or error is non-finite; `~` when
`|A − B| ≤ errA + errB` (the closed intervals touch); otherwise direction decides. Overlap is
conservative — it calls some real changes `~` — but never colours noise, needs no tunable threshold,
and is what JMH users already read by eye. The user asked for the delta to stay visible beside `~`,
so the verdict is its own column. Rejected: a fixed percentage threshold (ignores the measured
noise), no judgement (every wobble looks like a result).

### Units are compared as stored strings

`scoreUnit` equal → compute; not equal → `units differ`, raw values. No parser: a conversion can be
added later behind the same column without changing the output, and until then no row can show a
number computed across units. Rejected: refusing the command (one stray `-tu` blocks every other row).

### Header on stdout, other environment drift on stderr

Instance type and benchmark type are the two facts that most often explain a delta and that the user
wants read first, so they are payload, always printed, in a two-column header above the table — no
verdict, since "bigger instance" is not a regression. The remaining machine-observed tags differ
rarely and are diagnosed by `jobs diff`, so they are one logger warning naming that command. Tags are
read from the measurements (every measurement of a job carries the same observed tags), so no S3
read and no new permission. Rejected: fetching both `environment.json`s (a second, overlapping
`jobs diff`, plus S3 reads for every diff).

### Exit 0 whenever the diff ran

Same reasoning as a JCStress job ending `completed` whatever its tests found: the status says the
command ran, not what it measured. Exit 1 stays "refused" only, so a script can tell a regression
from a wrong id. CI gates with `--format json | jq -e '.rows[] | select(.verdict == "regression")'`.

### Secondary metrics under `-v`, raw

Shown as indented lines under their pair, both sides' value ± error and unit, nothing computed. A
direction per metric (`gc.alloc.rate.norm` lower-better, `gc.count` neither) has no vocabulary yet;
raw values give the reader the numbers without the command claiming a judgement. JSON carries them
always, as each side's `secondaryMetrics` object, since JSON has no `-v`.

### Rendering follows `jobs diff` and `results query`

`console.Table` (pad then colour), `Console` for payload, `Locale.ROOT` and `null` for non-finite in
JSON, as `printJson` does. The `ResultsCommand` comment ("one verb: a second verb over the same rows
with overlapping filters would leave the user guessing") is reworded rather than contradicted: `diff`
takes no filters, so the worry it records does not apply.

## Risks / Trade-offs

- [Overlap misses small real regressions] → The delta is always shown; the verdict is a floor, not a
  gate. A proper test (Welch on `rawData` from `jmh-result.json`) is possible later from S3.
- [Mismatched `-tu` hides deltas] → Visible as `units differ` per row; conversion can be added later.
- [Pairing across projects matches unrelated same-named classes] → Only when the operator chose two
  such jobs; the header shows both ids, and project is in the JSON tags.
- [Moving `JobReference` touches three `jobs` commands] → Mechanical import change; their tests stay.
- [Profiler overhead read as a regression] → The header shows `jmh-with-async` beside `jmh` above
  every row, which is where the user asked for it.

## Migration Plan

None. A new read-only verb; no stored shape, permission or resource changes. Rollback is reverting
the commit.
