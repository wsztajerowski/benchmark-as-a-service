# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm `jobId-index` projects every attribute and that a job's index partition (`gsi1pk = <jobId>`) holds its job item without a `kind` attribute: read the table's GSI projection in `cf-template-core.yaml` and query one real job on `baas-development` with `aws dynamodb query --index-name jobId-index` without a filter; record the command and the item kinds seen in `verify.md`
- [ ] 1.2 Confirm every measurement of one job carries the same `type`, `instanceType` and other machine-observed tags, so taking them from the first measurement is sound: read where the runner applies tags (`JmhRunResults`/`CommonSharedOptions`) and note the call site in `verify.md`
- [ ] 1.3 Confirm `jcstress` measurements are stored with `kind = JCSTRESS` and every JMH type with `kind = JMH` (runner mappers, and one item of each on `baas-development`); record it in `verify.md`

## 2. Shared pieces

- [ ] 2.1 Move `JobReference` from `jobs` to the kernel's `cli` package, public, unchanged in behaviour; `jobs show`/`diff`/`download` import it from there, and `SliceBoundaryTest` plus the existing `jobs` command tests pass
- [ ] 2.2 Make `ResultsGrouping.lowerIsBetter` visible to the new diff code in the `results` package without copying it; `ResultsGroupingTest` still passes
- [ ] 2.3 Add a `ResultsQueryService` method that reads one job's index partition in one `Query` without the `kind` filter and returns empty when no job item is present, otherwise the measurements (job item dropped); `ResultsQueryServiceIT` covers an unknown id, a job with a job item and no measurements, and a job with both, against LocalStack

## 3. The comparison

- [ ] 3.1 Add the pairing: key benchmark + mode + `paramsKey()`, pairs ordered by benchmark, mode, params, then `only in A`, then `only in B`; unit tests cover a sweep's variants pairing separately, one-sided rows on each side, one side empty, and both sides empty
- [ ] 3.2 Add the library guard over stored kinds: different kinds refused naming both libraries, two JCStress jobs refused with the not-supported message and the `results query --job-id` hint, a job of mixed kinds refused as corrupt, an empty side passing the guard; unit tests for each, and one for `jmh` vs `jmh-with-async` being compared
- [ ] 3.3 Add the per-pair judgement: `Δ% = (B − A) / |A| × 100` (unknown when A is zero or non-finite), verdict `?` on a non-finite score or error, `~` when `|A − B| ≤ errA + errB`, else `improvement`/`regression` by `lowerIsBetter`; `units differ` when `scoreUnit` differs, with no delta; unit tests cover each spec scenario's numbers (`-2.0 %` `~`, `+11.5 %` regression in `avgt`, `+11.9 %` improvement in `thrpt`, touching intervals as `~`, `ss`/`sample` direction)
- [ ] 3.4 Add the environment warning: compare `imageVersion`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch` between the sides and log one stderr line naming each difference and `baas jobs diff <jobA> <jobB>`; a test asserts the line is on the logger, not the `Console`, and absent when equal

## 4. The command

- [ ] 4.1 Add `ResultsDiffSubcommand` (`baas results diff <jobA> <jobB>`, `--format table|json`, `-v`) to `ResultsCommand`'s subcommands, rewording its one-verb comment; id checks and same-id check exit 2 before any AWS call, unknown jobs exit 1 looking up A before B, refusals exit 1, a completed diff exits 0 whatever the verdicts; `ResultsDiffCommandTest` drives each with a stubbed lookup as `JobsDiffCommandTest` does
- [ ] 4.2 Render the table: the header block (both ids, instance type, benchmark type, unknown shown as `n/a`), then columns benchmark (with params), mode, A, B, Δ, verdict; regression red, improvement green, others plain; `-v` adds secondary metrics under each pair, raw on both sides; a test strips escapes and compares with the uncoloured table, and one asserts no secondary-metric line without `-v`
- [ ] 4.3 Render JSON: both jobs' id, instance type, type and tags; one entry per row with benchmark, mode, params, each side's score/error/unit/secondaryMetrics (absent side `null`), `deltaPercent`, and a verdict from `regression`, `improvement`, `indistinguishable`, `unknown`, `units-differ`, `only-in-a`, `only-in-b`; `Locale.ROOT`, non-finite as `null`, no escape sequence; a test under `pl-PL` parses the output as JSON
- [ ] 4.4 Print `Neither job has measurements.` through the `Console` when both sides are empty, exit 0; a test covers it in table and JSON form
- [ ] 4.5 Check `baas results` alone lists `query` and `diff`, `baas diff` is an unknown command, and `baas --help`'s operator line for `results` names both verbs; extend the existing command-tree and help tests

## 5. Docs

- [ ] 5.1 Add the diff sequence to `docs/diagrams/baas-results.mmd` (two index `Query`s, the guard's refusals, the rendering); render it with `mmdc -i docs/diagrams/baas-results.mmd -o <scratchpad>/baas-results.png` and look at the PNG before committing
- [ ] 5.2 README: describe `baas results diff`, the library rule, the verdicts and the `jq` gating line
- [ ] 5.3 CLAUDE.md, *Result tagging* section: one paragraph — `results diff` refuses across libraries by the stored `kind`, never the `type` tag; the JMH variants compare; JCStress pairs are refused until verdict diffs exist; exit 0 whatever the verdicts

## 6. End-to-end (manual)

- [ ] 6.1 On `baas-development` with the reactor build (`baas-dev`): diff two existing `jmh` jobs, a `jmh` and a `jmh-with-async` job, a JMH and a JCStress job (expect exit 1), and two JCStress jobs (expect exit 1); paste the outputs and exit codes into `verify.md`
- [ ] 6.2 Run the same JMH pair with `--format json | jq -e '.rows[] | select(.verdict == "regression")'` and record the result

## 7. Verify

- [ ] 7.1 Run `mvn verify` for the reactor and record the result
- [ ] 7.2 Run `/opsx:verify` and record the result in `verify.md`: a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks
