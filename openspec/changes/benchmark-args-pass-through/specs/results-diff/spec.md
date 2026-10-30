# Spec Delta

## MODIFIED Requirements

### Requirement: Measurements from different libraries are never compared
The library that produced a measurement SHALL be the measurement's stored kind (JMH or JCStress), not
its `type` tag. When both jobs have measurements and their kinds differ, the command SHALL be refused
(exit 1) before any pairing, with a message naming each job's library and saying measurements from
different libraries are not comparable. Measurements of JMH jobs SHALL be compared with each other
whatever their `type` tag, including jobs stored under the former types `jmh-with-async` and
`jmh-with-prof`.

#### Scenario: JMH against JCStress is refused
- **WHEN** job A ran `jmh` and job B ran `jcstress`
- **THEN** it exits 1 saying job A is JMH, job B is JCStress, and the two are not comparable, and no
  row is printed

#### Scenario: Two JMH variants are compared
- **WHEN** job A ran `jmh` and job B ran `jmh` with `-prof async`
- **THEN** their measurements are paired and compared

#### Scenario: A job stored under a former type is compared
- **WHEN** job A is stored with `type=jmh-with-async` and job B with `type=jmh`
- **THEN** their measurements are paired and compared

### Requirement: The payload opens with each job's instance type and benchmark type
Before the rows, the payload SHALL show each job's id, instance type and benchmark type, taken from the
measurements' tags, whether or not they differ. These values SHALL NOT be given a verdict. A value
absent from a job's measurements SHALL be shown as unknown.

#### Scenario: The header is always present
- **WHEN** two jobs ran `jmh` on `c6i.large`
- **THEN** the header shows `c6i.large` and `jmh` for both jobs

#### Scenario: A differing type is visible at first glance
- **WHEN** job A is stored with `type=jmh-with-async` and job B with `type=jmh`
- **THEN** the header shows `jmh-with-async` for A and `jmh` for B, above every row

## ADDED Requirements

### Requirement: Different profilers are warned about
When the two jobs' measurements disagree on `profilers`, a side without the tag included, the command
SHALL write one warning to standard error naming both values, with an absent tag shown as `none`, and
saying that a profiler perturbs the measured score. The warning SHALL NOT change any row or the exit
status, and SHALL be written in addition to any warning about differing `cliParameters`.

#### Scenario: A profiled job against an unprofiled one
- **WHEN** job A carries no `profilers` tag and job B carries `profilers=async`
- **THEN** standard error names `profilers: none → async` and says a profiler perturbs the score, and
  standard output is unchanged by it

#### Scenario: The same profilers raise no warning
- **WHEN** both jobs carry `profilers=gc`
- **THEN** no profilers warning is written
