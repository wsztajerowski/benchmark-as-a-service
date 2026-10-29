# results-diff Specification

## Purpose
How `baas results diff` compares two jobs' measurements: which pairs are comparable at all, how each
pair's change is judged, and what the comparison shows alongside the numbers.

## Requirements

### Requirement: `baas results diff` compares two jobs named by id
`baas results diff <jobA> <jobB>` SHALL take exactly two job ids, run under operator credentials and
make no AWS call other than reading the two jobs from the results table. Each argument SHALL be
checked to be a job id before any AWS call; one that is not, or the same id given twice, SHALL be a
usage error (exit 2). A job id that names no job SHALL be refused (exit 1) with a message naming it,
and the first job SHALL be looked up before the second. Each job SHALL be read with one `Query` on
the job-id index and SHALL NOT cause a `Scan`. The two jobs MAY belong to different projects.

#### Scenario: Two jobs are compared
- **WHEN** `baas results diff 20260724T120000000Z-a3f9c21b 20260811T093000000Z-b7e4d0f2` is run and
  both jobs exist
- **THEN** their measurements are compared, and the table is issued from two `Query` calls and no `Scan`

#### Scenario: A malformed id is a usage error
- **WHEN** `baas results diff main 20260811T093000000Z-b7e4d0f2` is run
- **THEN** it exits 2 naming `main` as not a job id, before any AWS call

#### Scenario: The same job twice is a usage error
- **WHEN** both arguments are the same job id
- **THEN** it exits 2, before any AWS call

#### Scenario: An unknown job is refused
- **WHEN** the first id names no job
- **THEN** it exits 1 naming that id, and the second id is not looked up

### Requirement: Measurements from different libraries are never compared
The library that produced a measurement SHALL be the measurement's stored kind (JMH or JCStress), not
its `type` tag. When both jobs have measurements and their kinds differ, the command SHALL be refused
(exit 1) before any pairing, with a message naming each job's library and saying measurements from
different libraries are not comparable. Measurements of the JMH benchmark types — `jmh`,
`jmh-with-async` and `jmh-with-prof` — SHALL be compared with each other.

#### Scenario: JMH against JCStress is refused
- **WHEN** job A ran `jmh` and job B ran `jcstress`
- **THEN** it exits 1 saying job A is JMH, job B is JCStress, and the two are not comparable, and no
  row is printed

#### Scenario: Two JMH variants are compared
- **WHEN** job A ran `jmh` and job B ran `jmh-with-async`
- **THEN** their measurements are paired and compared

### Requirement: Two JCStress jobs are refused
When both jobs' measurements are JCStress — or one job's are and the other job has none — the command
SHALL be refused (exit 1) with a message saying that comparing JCStress jobs is not supported, distinct
from the different-libraries message, and naming `baas results query --job-id <id>` as the way to read
each job's verdict.

#### Scenario: A JCStress pair is refused
- **WHEN** both jobs ran `jcstress`
- **THEN** it exits 1 saying JCStress diffs are not supported, and names `baas results query --job-id`

#### Scenario: A JCStress job against one without measurements is refused
- **WHEN** job A ran `jcstress` and job B has no measurements
- **THEN** it exits 1 with the same not-supported message

### Requirement: Measurements are paired by benchmark, mode and params
A measurement of job A and a measurement of job B SHALL form a pair when they share the benchmark's
fully qualified name, the JMH mode and the params. A measurement with no partner SHALL be reported as
`only in A` or `only in B`, after every pair. Pairs SHALL be ordered by benchmark, then mode, then
params. When neither job has a measurement, the table SHALL say `Neither job has measurements.` and
the JSON SHALL carry no rows, and the command SHALL exit 0; when only one job has measurements, each of
them SHALL be reported as one-sided.

#### Scenario: A sweep's variants pair separately
- **WHEN** both jobs measured `Foo.bar` in `thrpt` with `size=10` and `size=100`
- **THEN** two pairs are reported, one per params value

#### Scenario: A benchmark in one job only is listed
- **WHEN** job A measured `Foo.baz` and job B did not
- **THEN** a row for `Foo.baz` with job A's score and `only in A` follows the pairs

#### Scenario: One job without measurements
- **WHEN** job A has measurements and job B has none
- **THEN** each of job A's measurements is listed as `only in A`, and the command exits 0

#### Scenario: Neither job has measurements
- **WHEN** neither job has a measurement
- **THEN** the table says `Neither job has measurements.`, the JSON has an empty `rows` array, and the
  command exits 0

### Requirement: Every pair shows its delta, and a verdict only beyond the error
For each pair in the same unit, the command SHALL report both scores with their errors and the delta
of B against A in percent, always. Beside the delta it SHALL report a verdict:
- `?` when either error or either score is unknown;
- `~` when the intervals `score ± error` of the two measurements overlap;
- otherwise `improvement` or `regression`, judged by the mode's direction: lower is better for a
  time-per-operation mode (average time, sample time, single shot), higher for throughput.

When A's score is zero or unknown the delta SHALL be reported as unknown.

#### Scenario: An overlapping change is shown but not judged
- **WHEN** A is `1234.5 ± 40.1` and B is `1210.0 ± 38.7` in the same unit
- **THEN** the row shows a delta of `-2.0 %` and the verdict `~`

#### Scenario: A slower time-per-operation is a regression
- **WHEN** a pair in `avgt` has A `812.3 ± 5.2` and B `905.9 ± 6.0`
- **THEN** the row shows `+11.5 %` and `regression`

#### Scenario: A higher throughput is an improvement
- **WHEN** a pair in `thrpt` has A `501.0 ± 3.1` and B `560.4 ± 2.9`
- **THEN** the row shows `+11.9 %` and `improvement`

#### Scenario: An unknown error is not judged
- **WHEN** either measurement's error is unknown, as for a single-iteration run
- **THEN** the row shows its delta and the verdict `?`

### Requirement: A unit mismatch is shown, never computed across
When the two measurements of a pair are stored in different units, the row SHALL show both raw values
with their units and `units differ` in place of the delta and the verdict. It SHALL NOT convert either
value, and SHALL NOT fail the command.

#### Scenario: Different time units
- **WHEN** a pair has A in `ops/ms` and B in `ops/s`
- **THEN** the row shows both values with their units and `units differ`, and the other rows are
  compared as usual

### Requirement: The payload opens with each job's instance type and benchmark type
Before the rows, the payload SHALL show each job's id, instance type and benchmark type, taken from the
measurements' tags, whether or not they differ. These values SHALL NOT be given a verdict. A value
absent from a job's measurements SHALL be shown as unknown.

#### Scenario: The header is always present
- **WHEN** two jobs ran `jmh` on `c6i.large`
- **THEN** the header shows `c6i.large` and `jmh` for both jobs

#### Scenario: A differing type is visible at first glance
- **WHEN** job A ran `jmh` and job B ran `jmh-with-async`
- **THEN** the header shows `jmh` for A and `jmh-with-async` for B, above every row

### Requirement: Other environment differences are warned about
When the two jobs' measurements disagree on `imageVersion`, `jdk`, `jvmVendor`, `cpuModel` or
`cpuArch`, the command SHALL write one warning to standard error naming each differing tag with both
values and the `baas jobs diff <jobA> <jobB>` command that compares the full environments. It SHALL
NOT read either job's `environment.json`, and the warning SHALL NOT change any row.

#### Scenario: A different image is warned about
- **WHEN** job A carries `imageVersion=1.3.0` and job B `imageVersion=1.4.0`
- **THEN** standard error names `imageVersion: 1.3.0 → 1.4.0` and `baas jobs diff <jobA> <jobB>`, and
  standard output is unchanged by it

### Requirement: Different run parameters are warned about
When the two jobs' measurements disagree on `cliParameters`, a side without the tag included, the
command SHALL write one warning to standard error naming both values and saying that they change the
error interval and so the verdict. The warning SHALL NOT change any row or the exit status.

#### Scenario: Different iteration counts are warned about
- **WHEN** job A carries `cliParameters=Foo -f 1 -i 3` and job B `cliParameters=Foo -f 1 -i 5`
- **THEN** standard error names `Foo -f 1 -i 3 → Foo -f 1 -i 5`, and standard output is unchanged by it

### Requirement: Tags, params and secondary metrics are shown raw under `-v`
With `-v`, the header SHALL be followed by a tags block listing every tag key either job carries,
except the instance type and benchmark type the header already shows, with A's value and B's value, an
absent value shown as unknown, and a differing value emphasised on a terminal as the header's are. Each
row whose benchmark has params SHALL be followed by a params line giving each side's params in full. Each
row SHALL then be followed by its measurements' JMH secondary metrics, each with its value
and unit for A and for B (the store keeps no error for a secondary metric), and SHALL show no delta and
no verdict for them. A metric or params present on
one side only SHALL be shown with the other side unknown. Without `-v` the table SHALL NOT show any of
them. None of these lines SHALL be judged or change the exit status. The header and the tags block SHALL
share one label column and one pair of A and B columns, and a value too long for its column SHALL wrap
inside that column, so that no header or tag line is wider than the table.

#### Scenario: A long tag value wraps inside its column
- **WHEN** `-v` is given and job B's `cliParameters` is longer than the B column
- **THEN** its continuation lines start at the B column, every A value starts at the column where the
  header's A values start, and no line is wider than the table

#### Scenario: A GC profiler metric is listed without judgement
- **WHEN** both jobs ran with `-prof gc` and `baas results diff -v A B` is run
- **THEN** `gc.alloc.rate.norm` appears under its pair with both values and units, and no delta or verdict

#### Scenario: Tags are compared side by side
- **WHEN** job A carries `branch=main` and job B `branch=feature-x` and `commit=abc`, and
  `baas results diff -v A B` is run
- **THEN** the tags block shows `branch` as `main` against `feature-x` and `commit` as unknown against
  `abc`, and shows no `instanceType` or `type` line

#### Scenario: A sweep's params are shown in full
- **WHEN** a pair's params are too long for the benchmark column and `-v` is given
- **THEN** a params line under the row shows both sides' params untruncated

### Requirement: Output is a table or JSON, and the exit status says the diff ran
The command SHALL accept `--format table|json`, defaulting to `table`, and SHALL write its payload to
standard output. The JSON SHALL be one object carrying each job's id, instance type, benchmark type and
tags, and one entry per row carrying the benchmark, mode, params, each side's score, error, unit and
secondary metrics (an absent side as `null`), the delta in percent and the verdict as one of
`regression`, `improvement`, `indistinguishable`, `unknown`, `units-differ`, `only-in-a`, `only-in-b`.
A non-finite number SHALL be `null`, and numbers SHALL be formatted independently of the locale. The
command SHALL exit 0 whenever it compared the jobs, whatever the verdicts; exit 1 SHALL mean a refusal
and exit 2 a usage error.

#### Scenario: A regression still exits 0
- **WHEN** a pair's verdict is `regression`
- **THEN** the command exits 0, and `--format json` carries `"verdict": "regression"` for that row

#### Scenario: JSON under a comma-decimal locale
- **WHEN** `--format json` is run under the `pl-PL` locale
- **THEN** every number is a JSON number with a `.` decimal separator
