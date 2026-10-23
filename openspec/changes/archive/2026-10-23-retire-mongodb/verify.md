# Verify — retire-mongodb

Verified 2026-10-23 on branch `retire-mongodb` (code through `bec5b5e`). Tasks 25/26 before this file;
9.1 is this record.

## Summary

| Dimension | Status |
|---|---|
| Completeness | 26/26 tasks; 5 requirements (3 modified, 1 added, 1 removed) |
| Correctness | 5/5 requirements implemented; 16/17 scenarios tested, 1 by absence (W3) |
| Coherence | Design followed; 4 recorded deviations, none contradicting it |

No critical issues. Three warnings. Ready for archive.

## Requirement → code → test → gap

| Requirement / scenario | Code | Test / evidence | Gap |
|---|---|---|---|
| **results-store-schema: The store interface exposes writes only** | `infra/ResultsStore.java:22` (`write(List<StoredMeasurement>)`) | `ResultsStoreContractTest` (bound by `DynamoDbResultsStoreContractIT`) | — |
| · No upsert surface exists | same | interface has one method | — |
| · The interface is storage-neutral | same | signature names `StoredMeasurement` only | — |
| **results-store-schema: Every job names a results store** | `ApiCommonSharedOptions.java:40` (`required = true`); `ResultsStoreBuilder.java:54` (blank-table guard) | `ApiCommonSharedOptionsTest`, `ResultsStoreBuilderTest` | — |
| · Missing configuration fails fast | picocli `MissingParameterException` | `aJobWithoutAResultsTableIsRefusedBeforeItRuns`; live 8.3 (exit 2, "Missing required option: '--results-table…'", nothing ran) | — |
| · The discard option is gone | — | `theDiscardOptionIsUnknown` | — |
| · The MongoDB option is gone | option removed from `ApiCommonSharedOptions` | `theMongoOptionsAreUnknown`, `JCStressSubcommandTest.theShortMIsNoRunnerOption`; live 8.3 (`-m` → "Unknown option", exit 2) | `MONGO_CONNECTION_STRING` having no effect is shown by absence, not a test (W3) |
| · A local job names a local table | `ResultsStoreBuilder` endpoint override | service ITs and `DynamoDbResultsStoreContractIT` on LocalStack | — |
| **results-store-schema: Every job names an artifact bucket** (added) | `ApiCommonSharedOptions.java:48` (`required = true`, no `ArgGroup`); `StorageService.of` refuses a blank bucket; `LocalStorageService` deleted | `aJobWithoutABucketIsRefusedBeforeItRuns` | — |
| · Missing bucket fails fast | picocli | test above; live 8.3 (exit 2, "Missing required option: '--s3-bucket…'") | — |
| · A local job names a local bucket | `S3StorageService` with endpoint | every service IT uploads to LocalStack S3 | — |
| · Binary artifacts upload like any other | `S3StorageService` (no text read; A13's `readString` left with the class) | `JmhWithProfilerSubcommandServiceIT` asserts `profile.jfr` uploaded | — |
| **results-store-schema: MongoDB remains available…** (removed) | `MongoResultsStore`, `MongoMeasurementDocument`, Mongo builder branch, option, deps deleted | `mvn dependency:tree`: no `org.mongodb`/`dev.morphia`; full reactor green | — |
| **jcstress-execution: A JCStress job can select its mode** | `ApiJCStressOptions` `--mode` (unchanged); `RunCommand.java:257`, `:453` (`-m` refusal) | see scenarios | — |
| · Sanity mode is requested | unchanged | live 8.1 job `20261023T090642860Z-5165200d` completed, 1 item, both HTML reports | — |
| · No mode is requested | unchanged | `JCStressSubcommandServiceIT` (no mode) | — |
| · An unknown mode fails the job visibly | unchanged | existing behaviour, not re-run | not exercised by this change (unchanged code) |
| · The short option is refused before launch | `RunCommand.java:257` | `OptionValidationTest.aShortModeFlagIsRefusedNamingTheLongOne`, `RunCommandLaunchTest.aShortModeFlagIsRefusedAfterTheDoubleDashBeforeAnyAwsCall`; live 8.2 (exit 2, "-m after -- is not a runner option. A JCStress mode is --mode…", nothing launched) | — |
| · The short option keeps its meaning | `-m` declared nowhere on the runner | `theShortMIsNoRunnerOption`; live 8.3 | — |

Findings closed: C4 (service ITs on DynamoDB, Mongo gone), A13 (local storage gone), B11 (`JmhRunResults.map`
takes no `StorageService`; Morphia annotations gone). All three deleted from `docs/review/open-findings.md`;
G19 lost its `StoredMeasurement` item.

## Live evidence (`baas-development`, 2026-10-23, reactor CLI and `--runner-jar` from this branch)

| Check | Job / command | Result |
|---|---|---|
| 8.1 jmh-with-async | `20261023T090642890Z-bf67ec64`, `Incrementing_Synchronized -f 1 -wi 1 -i 2 -r 2s` | `completed`, 1 measurement; `jmh-result.json`, `environment.json`, `packages.txt`, both flame graphs, process output and boot log in the prefix; boot log shows the service's own "Saving verbatim JMH result JSON" before "Storing 1 measurement(s)" |
| 8.1 JCStress | `20261023T090642860Z-5165200d`, `-- --mode sanity` | `completed`, 1 item; two test HTML reports, `jcstress-output.txt` |
| 8.2 | `baas run jcstress … -- -m sanity` | exit 2 before any upload |
| 8.3 | runner JAR without `--s3-bucket`, without `--results-table`, with `-m mongodb://x/db` | each exit 2 naming the option; no output file written |

## Warnings

- **W1 — the async job spent 13m30s inside JMH.** JMH's own summary says `Total time: 00:13:30` for a run it
  estimated at 14 s, scoring 254 ops/s, with async-profiler's default CPU event (10 µs interval) on two threads
  contending for one lock. The time is between the runner's "Running JMH" and "Saving benchmark process
  output" lines, inside the child process; this change touches no `process/` code and nothing the child runs.
  CI runs the same benchmark with `--async-event=wall` in ~2 min. *Recommendation:* not this change's to fix;
  decide whether it deserves an `open-findings.md` entry (default async options on a contended benchmark).
- **W2 — one LocalStack `createTable` 400 in the first full `mvn verify`.** `JmhSubcommandServiceIT` failed
  in 6.7 s at the base class's `createTable`, with no message, before its test body ran. Not reproduced in
  two single-class runs, two full runner suites and the full reactor afterwards. Four more ITs now create a
  table per test, so a readiness flake has more chances to show. *Recommendation:* if it recurs, have
  `TestcontainersWithDynamoDbBaseIT` wait for the table (`waiter().waitUntilTableExists`) or retry the
  create once; nothing to do on one occurrence.
- **W3 — `MONGO_CONNECTION_STRING` having no effect is shown by absence.** No runner option reads it any
  more (`git grep MONGO_CONNECTION_STRING` finds only the user-data absence test and history), so there is
  nothing to test against. *Recommendation:* none.

## Deviations from design and tasks

- **T11 dropped from scope** (proposal and design updated before apply): its five LocalStack pins are in
  `baas-cli`'s ITs, not in the runner base class this change edits.
- **`ban-mongodb` kept** (design decision added): OpenSpec refuses a MODIFIED block that drops the
  storage-neutral scenario, and the requirement still stands, so the rule that enforces it stays.
- **The `jcstress-execution` scenario name "The short option keeps its meaning" was kept** with new
  content, because a MODIFIED block may not drop a scenario.
- **Task 3.1's check was narrowed**: `JmhRunResults` still imports `ResultLoaderService` from `infra` — the
  JSON parser, which is mapping — and no `StorageService`.
- **Task 1.1 found more Mongo references than the proposal listed**; recorded in design.md *Context*.
  The `baas-cli`/`baas-model` ones are absence tests and history and were kept.
