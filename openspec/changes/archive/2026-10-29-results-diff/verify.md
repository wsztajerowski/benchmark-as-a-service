# Verify — results-diff

Applied unattended on 2026-10-28, in the worktree `benchmark-as-a-service-results-diff`, branch
`results-diff` from `next-release` (32b8ea6).

## 1. Blocking assumptions

**1.1 — the job item shares the index partition and lacks `kind`.** `infra/cf-template-core.yaml:336`
declares `jobId-index` with `ProjectionType: ALL`. A raw, unfiltered query of three real jobs:

```
aws dynamodb query --profile baas-operator-381492019823 --region eu-central-1 \
  --table-name baas-381492019823-results --index-name jobId-index \
  --key-condition-expression "gsi1pk = :j" --expression-attribute-values '{":j":{"S":"<jobId>"}}' \
  --query 'Items[].[pk.S, gsi1sk.S, kind.S, tags.M.type.S, tags.M.instanceType.S, scoreUnit.S]' --output text

== 20261028T204501136Z-0b86547a
JOB	JOB	None	jmh-with-async	None	None
RESULT#benchmark-as-a-service	pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized#thrpt	JMH	jmh-with-async	c5.2xlarge	ops/s
== 20261028T204435272Z-a35ce5e2
RESULT#benchmark-as-a-service	JCSTRESS#20261028T204435272Z-a35ce5e2	JCSTRESS	jcstress	c5.2xlarge	None
JOB	JOB	None	jcstress	None	None
== 20261023T170136685Z-709cd6a3
JOB	JOB	None	jmh	None	None
RESULT#version-floor-check	pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized#thrpt	JMH	jmh	c5.2xlarge	ops/s
```

The job item (`gsi1sk = JOB`) carries no `kind`; every measurement does. Confirmed.

**1.2 — one job's measurements share their tags.** `JmhRunResults.map` passes the same
`commonOptions.tags()` to every `JmhMeasurementMapper.toMeasurement` call
(`benchmark-runner/src/main/java/pl/wsztajerowski/results/JmhRunResults.java:63`), and JCStress writes
one item per job (`JCStressSubcommandService.java:115`). Reading the header tags from the first
measurement is sound.

**1.3 — kinds as stored.** `JmhMeasurementMapper:38` writes `MeasurementKind.JMH` for every JMH type,
`JCStressMeasurementMapper:28` writes `JCSTRESS`; the live items above agree (`jmh`, `jmh-with-async` →
`JMH`; `jcstress` → `JCSTRESS`).

## 6. End-to-end (manual)

**Deviation — the deployment.** Task 6.1 names `baas-development`, which no longer exists: no stack in
`eu-central-1` (`DescribeStacks` is denied for that name because its deployer policy is gone, and
`us-east-1` answers "does not exist"), and no `~/.baas/deployments/baas-development.yaml`. Recreating
it needs an IAM administrator. The checks therefore ran the reactor JAR against `baas-381492019823`,
**read-only**: `results diff` issues two `Query`s and nothing else, so it cannot change that deployment.
This departs from the standing rule that work in progress is exercised on the dev deployment; flagged
for the user (W1).

All runs: `java -jar baas-cli/target/baas-cli.jar --deployment baas-381492019823 results diff …`
(each also printed the expected "Unreleased build … not checked" warning, omitted below).

| Pair | Expected | Got |
|---|---|---|
| `709cd6a3` (jmh, c5.2xlarge) vs `bf67ec64` (jmh-with-async, c5.large) | compared, header shows both types | exit 0; header `jmh` / `jmh-with-async`, `c5.2xlarge` / `c5.large`; stderr warns `imageVersion: 1.4.0+ext.de4980bc → 1.4.0` and `cpuModel`, naming `baas jobs diff …`; one row, `?` (CI's single-iteration runs store no error) |
| `bf67ec64` vs `0b86547a` (both jmh-with-async) | compared | exit 0; `+4497991,8 %` `?` — this run found the Δ column too narrow (9), widened to 12 |
| `0b86547a` (JMH) vs `a35ce5e2` (JCStress) | refused, different libraries | exit 1: "Job …0b86547a is JMH and job …a35ce5e2 is JCStress: measurements from different libraries are not comparable." |
| `bf67ec64` (JMH) vs `5165200d` (JCStress) | refused | exit 1, same message |
| `a35ce5e2` vs `806fadeb` (both JCStress) | refused, not supported | exit 1: "Comparing JCStress jobs is not supported yet. Read each job's verdict with `baas results query --job-id …`…" |
| `2d1780c9` (cancelled, no measurements) vs `709cd6a3` | one-sided rows | exit 0; header `n/a` for A; the row reads `only in B` |
| `0b86547a` vs `20261099T000000000Z-00000000` | unknown job | exit 1: "No job found with id '20261099T000000000Z-00000000'." |

**6.2 — JSON and the gate.**

```
$ … results diff 20261023T090642890Z-bf67ec64 20261028T204501136Z-0b86547a --format json \
    | jq -c '{a: .a.type, b: .b.instanceType, rows: [.rows[] | {benchmark, verdict, deltaPercent}]}'
{"a":"jmh-with-async","b":"c5.2xlarge","rows":[{"benchmark":"pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized","verdict":"unknown","deltaPercent":4497991.826194}]}
$ jq -e '[.rows[] | select(.verdict == "regression")] | length == 0' diff.json
true          (exit 0)
```

Under a pseudo-terminal (`script -q /dev/null …`), the header's differing INSTANCE value was bold. The
first live run also bolded the JOB line, since two ids always differ. That was fixed to emphasise only
INSTANCE and TYPE, and a test pins it.

No live job carries secondary metrics (CI runs async-profiler, not `-prof gc`), so `-v`'s metric lines
are covered by `ResultsDiffCommandTest.secondaryMetricsAppearRawOnlyUnderVerbose` alone.

## 7. Build and verification

**7.1 — `mvn -o verify` for the reactor: BUILD SUCCESS**, exit 0, 2026-10-28. 1371 tests, 0 failures, 0 errors,
0 skipped (model 100, runner 58 + 14, CLI 1145 + 54). LocalStack ITs ran, `ResultsQueryServiceIT` 23
(three new `queryJob` cases among them) and `JmhWithAsyncProfilerSubcommandServiceIT` 1 (`ASYNC_PATH`
was set). After it, two command tests were added (below): `ResultsDiffCommandTest` 22/22,
`CommandTreeTest` 20/20, `HelpAndHintsTest` 7/7, `SliceBoundaryTest` 6/6 re-run green.

**7.2 — `/opsx:verify`.**

| Dimension | Status |
|---|---|
| Completeness | 22/22 tasks; 10 ADDED + 3 MODIFIED requirements, all implemented |
| Correctness | 13/13 requirements traced to code and a test; every scenario covered |
| Coherence | Design followed; three deviations recorded in design.md, each folded into the spec |

### Requirement → code → test

| Requirement | Code | Test |
|---|---|---|
| Two jobs named by id | `ResultsDiffSubcommand.call` (id, same-id and format checks before AWS; A looked up before B); `ResultsQueryService.queryJob` (one `Query`, no `Scan`) | `ResultsDiffCommandTest`: `aMalformedIdIsAUsageErrorBeforeAnyLookup`, `theSameJobTwiceIsAUsageError`, `anUnknownFormatIsAUsageError`, `anUnknownFirstJobIsRefusedWithoutLookingUpTheSecond`; `ResultsQueryServiceIT`: `aJobReadForADiff*` ×3; live: unknown id |
| Different libraries never compared | `ResultsDiff.refusal` on `ResultRow.kind` (stored `kind`) | `ResultsDiffTest`: `jmhAgainstJcstressIsRefusedNamingBothLibraries`, `theGuardReadsTheStoredKindNotTheTypeTag`, `jmhVariantsAreCompared`, `aJobOfMixedKindsIsRefusedAsCorrupt`; `ResultsDiffCommandTest.jmhAgainstJcstressIsRefusedAndPrintsNoRow`; live ×2 |
| Two JCStress jobs refused | `ResultsDiff.refusal` | `twoJcstressJobsAreRefusedAsNotSupported`, `aJcstressJobAgainstAnEmptyOneIsRefusedAsNotSupported`, command `twoJcstressJobsAreRefused`; live |
| Paired by benchmark, mode, params | `ResultsDiff.compare` | `aSweepsVariantsPairSeparately`, `modesPairSeparately`, `oneSidedRowsFollowThePairsAThenB`, `oneSideEmptyMakesEveryRowOneSided`, `bothSidesEmptyIsNoRows`; command `neitherJobHavingMeasurementsSaysSoAndExitsZero`, `oneJobWithoutMeasurementsListsTheOthersRowsOneSided`; live (cancelled job) |
| Delta always, verdict beyond the error | `ResultsDiff.pair`/`deltaPercent`/`judge`, reusing `ResultsGrouping.lowerIsBetter` | `anOverlappingChangeShowsItsDeltaButNoVerdict` (−2.0 % ~), `aSlowerTimePerOperationIsARegression` (+11.5 %), `aHigherThroughputIsAnImprovement` (+11.9 %), `touchingIntervalsAreIndistinguishable`, `sampleAndSingleShotAreTimePerOperation`, `anUnknownErrorIsNotJudgedButStillHasADelta`, `anUnknownScoreHasNoDelta`, `aZeroBaselineHasNoDelta` |
| Unit mismatch shown, never computed | `ResultsDiff.pair`; `ResultsDiffSubcommand.unit` | `differentUnitsAreShownNotComputed`, command `differentUnitsReadAsSuch` |
| Header: instance type and benchmark type | `ResultsDiffSubcommand.printTable`/`headerLine`, `jobJson` | command `theTableOpensWithTheHeaderThenPairsThenOneSidedRows`, `aJobWithoutMeasurementsHeadsItsColumnWithUnknowns`, `jsonOfTwoEmptyJobsHasNoRows`; live |
| Other environment differences warned | `ResultsDiff.environmentDifferences`; logger in `call` | `aDifferentImageIsReportedAndTheHeaderTagsAreNot`, `equalEnvironmentsAndAnEmptySideReportNothing`, command `otherEnvironmentDifferencesGoToStderrNotStdout`, `equalEnvironmentsWarnNothing`; live |
| Tags, params and secondary metrics raw under `-v` | `printTable` (verbose), `printTags`, `params`, `metric`; `ResultsDiff.tagKeys` | `tagKeysAreBothJobsKeysWithoutTheHeadersTwo`; command `tagsAreComparedOnlyUnderVerbose`, `aDifferingTagIsEmphasisedAndAnEqualOneIsNot`, `paramsAppearInFullUnderEachRowOnlyUnderVerbose`, `secondaryMetricsAppearRawOnlyUnderVerbose`, `aMetricOnOneSideOnlyShowsTheOtherAsUnknown`; live tags block (2026-10-29) |
| Table or JSON, exit status | `printJson`, `call` | command `aRegressionStillExitsZero`, `jsonCarriesBothJobsAndEveryRowsVerdict`, `jsonUnderACommaDecimalLocaleIsStillJson`; live `jq -e` gate |
| (MODIFIED) `results` noun lists `query` and `diff` | `ResultsCommand` | `CommandTreeTest.eachNounOffersItsVerbs`, `theBareResultsNounNamesBothVerbs` |
| (MODIFIED) nouns/verbs, no alias for `diff` | `ResultsCommand` (no root alias) | `CommandTreeTest.removedAndNeverAliasedSpellingsAreUnknown` (`diff a b`), `HelpAndHintsTest.theRootHelpIsGroupedByWorkload`, command `diffIsAVerbOfResultsWithNoTopLevelAlias` |
| (MODIFIED) coloured tables | `verdict`, `score` (faint unknowns), `headerLine` | command `colouredVerdictsKeepTheColumnsWhereThePlainTableHasThem` (alignment, red regression, uncoloured `~`, bold INSTANCE, plain JOB); JSON never coloured: `jsonUnderACommaDecimalLocaleIsStillJson` |

### Deviations from design and tasks

- Header bolds a differing INSTANCE/TYPE value, never the ids (design.md, *Deviations*).
- "Neither job has measurements." is table-only. JSON has an empty `rows` (spec updated).
- A JCStress job against one without measurements is refused like a JCStress pair (spec updated).
- Secondary metrics show value and unit, because `SecondaryMetric` stores no error (spec updated).
- Task 6.1 ran on `baas-381492019823`, not `baas-development`, which no longer exists (W1).
- The Δ column was widened from 9 to 12 after a live delta of `+4497991,8 %` overflowed it.
- 2026-10-29: `baas run` records the text after `--` as the reserved tag `cliParameters` (task 4.7,
  `results-store-schema` delta). Tests: `RunCommandTest.recordsEverythingAfterTheSeparatorAsCliParameters`,
  `anArgumentHoldingSpacesStaysOneArgumentInCliParameters`, `nothingAfterTheSeparatorLeavesCliParametersAbsent`,
  `aCallerCannotSetCliParameters`; `TagKeysTest.reservedKeysAreTheObservedOnesAndTheRecordedCliParameters`.
  Checked live 2026-10-29 on the rebuilt `baas-development` with the reactor build: two `jmh-with-async`
  jobs, `20261029T115415139Z-7418c53b` (`-- Incrementing_Synchronized -f 1 -wi 1 -i 3 --async-event=cpu`)
  and `20261029T115415139Z-a28ee13a` (`… -i 5 --async-event=wall -jvmArgs '-Xmx1g -Xms1g'`), both
  `completed`. `results diff -v` shows both values in the tags block, the quoted argument intact, and JSON
  carries them under `tags.cliParameters`. The same pair gave the first live verdict other than `?`:
  `+2.5 %` `~` (`10518341.270 ± 12757093.473` against `10785029.592 ± 984867.728`), see W2.
- 2026-10-29, after the live `-v` above: the header and tags now share one set of columns, a long value
  wraps inside its column, and differing `cliParameters` are warned on stderr (task 4.8). Re-run live on
  the same pair: the warning names both values, B's `-jvmArgs '-Xmx1g -Xms1g'` continues under B.
- The A and B columns were widened from 22 to 28 (rule 140 → 152) after that live pair: a score with a
  real error, `10518341,270 ± 12757093,473`, is 27 characters and pushed the row out of line.
- 2026-10-29, after review of the live output: `-v` also compares tags (a block after the header) and
  shows params in full under each row (task 4.6, spec and design updated). No live job has params.

### Warnings

- **W1** — the live checks ran a reactor build against the default deployment `baas-381492019823`,
  where the standing rule puts work in progress on `baas-development`. That deployment is gone, and
  restoring it needs an IAM administrator. Every call was a read (two `Query`s per run), so nothing on
  the default deployment changed. The user decides whether this is acceptable or a dev deployment
  should be rebuilt first. **Closed 2026-10-29:** `baas-development` was rebuilt, and the `cliParameters`
  and diff checks ran there with two new jobs.
- **W2** — no live job has a `score ± error` interval: CI's runs are single-iteration, so every live
  pair read `?`. `regression`, `improvement` and `~` are covered by unit and command tests only. A real
  multi-iteration pair would show them on AWS data. **Partly closed 2026-10-29:** a multi-iteration pair on
  `baas-development` read `~` live. `regression` and `improvement` are still checked by tests only: a
  this pair's error (±12.8M on A's 10.5M ops/s) is far wider than its delta, and the fixture
  is multimodal on its own.

### Assessment

No critical issues. Two warnings for the user. Ready to merge into `next-release` and archive, once W1
is accepted.
