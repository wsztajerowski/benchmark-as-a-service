# Proposal

## Why

The product is a comparison between measurements, yet no command compares them. `baas jobs diff`
says whether two jobs measured on the same environment; whether their *numbers* moved is left to
reading two `baas results query --job-id` tables side by side and subtracting by eye — with nothing
to stop two incomparable results (a JCStress verdict and a JMH score, or `ops/ms` and `ops/s`) from
being set against each other.

## What Changes

- New verb `baas results diff <jobA> <jobB>`, under operator credentials: it pairs the two jobs'
  measurements by identity — benchmark, JMH mode and params, the identity `--best-per` already uses —
  and reports each pair's scores, errors, the delta in percent and a verdict.
- **Measurements from different libraries are never compared.** The library is the item's required
  `kind` (`JMH`, `JCSTRESS`). A JMH job and a JCStress job are refused, exit 1, before any pairing.
  The JMH variants — `jmh`, `jmh-with-async`, `jmh-with-prof` — share one kind and are compared.
- Two JCStress jobs are refused too, for now, with a message saying JCStress diffs are not supported
  and naming `baas results query --job-id`. JCStress stores verdicts, not scores, and its fixtures race.
- The delta is always printed. The verdict beside it is `regression` or `improvement` — by the mode's
  direction, as `--best-per` decides it — only when the two confidence intervals (`score ± scoreError`)
  do not overlap; `~` when they do; `?` when an error is unknown (a single-iteration run).
- A pair stored in different units shows both raw values and `units differ`, never a converted or
  subtracted number.
- A benchmark measured by one job only is listed after the pairs, as `only in A` / `only in B`.
  "Neither job has measurements." is printed only when both jobs have none.
- The payload opens with a header carrying each job's instance type and benchmark type, always —
  context, not a compared value. A difference in the other machine-observed tags (`imageVersion`,
  `jdk`, `jvmVendor`, `cpuModel`, `cpuArch`) is a stderr warning pointing at `baas jobs diff`.
- `-v` prints each pair's JMH secondary metrics under it, raw values only, without delta or verdict.
- `--format table|json`. The command exits 0 whenever the diff ran, whatever it found; exit 1 is a
  refusal, exit 2 a usage error. Gating CI on a regression is a `jq` over the JSON.
- `results` gains its second verb. `diff` belongs to two nouns, so it has no top-level alias.

## Capabilities

### New Capabilities

- `results-diff`: comparing two jobs' measurements — pairing, the library guard, deltas and verdicts,
  units, one-sided benchmarks, the header and warnings, secondary metrics, formats and exit status.

### Modified Capabilities

- `benchmark-results-query`: `baas results` is no longer a noun whose only verb is `query`.
- `cli-command-structure`: the `results` noun lists `query` and `diff`; `diff` has no top-level alias.
- `cli-console-output`: the `baas results diff` table is coloured under the same rules as the other
  tables — verdicts by direction, unknowns de-emphasised, columns aligned.

## Impact

- **Code:** `baas-cli` — a `ResultsDiffSubcommand` and the pairing logic in the `results` slice, a
  query that returns a job's job item and measurements in one `Query` on `jobId-index`, and
  `JobReference` moved from `jobs` into the kernel's `cli` package, since `results` may not import
  `jobs` (ADR 0009) and both nouns now take a job id. `ResultsGrouping.lowerIsBetter` is reused, not
  copied. `docs/diagrams/baas-results.mmd` gains the diff sequence; `baas --help` lists the verb.
- **AWS:** read-only — two `Query`s on the existing `jobId-index`, within `OperatorRole`'s existing
  grants. No new resource, permission or standing cost.
- **Not changed:** the stored item, `ResultKeys`, `MeasurementItemMapper` and every runner; `baas jobs
  diff`, which stays the environment comparison and is not called by this command; `results query`'s
  options and output. No open finding in `docs/review/open-findings.md` is closed by this change.
