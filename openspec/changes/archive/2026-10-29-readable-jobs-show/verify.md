# Verify — readable-jobs-show

## Blocking assumptions (section 1)

**1.1 `lscpu` fields.** No stored job's boot log carries `lscpu` output, and a `jobs connect` session would
need a paid instance, so the check was split:

- *Field names and the capture function* — the script's own `lscpu_field` run against `lscpu` from
  `util-linux-2.37.4-1.amzn2023.0.6` (the AL2023 package) in an `amazonlinux:2023` container on 2026-10-29:
  ```
  Thread(s) per core:                      1
  Core(s) per socket:                      12
  Socket(s):                               1
  T=[1] C=[12] S=[1]
  ```
  All three fields are unindented, so `grep -m1 "^<name>"` finds them; the values are the container
  host's, not a c5's.
- *Values on c5.2xlarge* — AWS's published CPU options for the type: 4 default cores, 2 threads per core,
  8 vCPUs, which gives `physicalCores` 4 with one socket. Stored job `20261026T111802133Z-3a07e193`
  recorded `cores` (nproc) 8 and `threadsPerCore` 2, consistent with it. Confirmed on the instance by 7.1.

**1.2 Status line before hints.** Holds without a change: `JobLaunch.poll` closes the status line in its
own `finally`, before `report`, and `RunCommand.call()` prints hints in its outer `finally`, after
`execute()` has returned. A detached launch never opens one; the shutdown hook closes it first.

**1.3 Sizes in the listing.** `S3UploadService.listKeys` reads `ListObjectsV2` pages whose `S3Object`s
carry `size()`. Its callers are `jobs show`, `jobs download` and `S3DownloadIT`; a sibling listing of
key and size leaves `listKeys` and its callers untouched.

## Manifest schema 7 (section 2)

**2.3 User-data size.** `aLargeJobStaysWellUnderTheUserDataLimit`'s outsized run renders to 9458 raw bytes
before the change and 9851 after (+393), against the test's bound of 12 KB (¾ of EC2's 16 KB). Measured by a
temporary print in that test, removed afterwards. `UserDataScriptBuilderTest`: 77 tests, 0 failures,
`theRenderedScriptIsSyntacticallyValidBash` included.

## `jobs diff` (section 3)

`EnvironmentManifestTest` 13/13, `JobsDiffCommandTest` 5/5, `PackagesDiffTest` 5/5. The KiB tests
(`aMemoryValueThatIsNotANumberIsComparedAsIs`, `theManifestItselfKeepsKib`) are deleted with the conversion
they tested. **3.2:** the parse failure is covered in `EnvironmentManifestTest`
(`trailingContentFailsTheParseNamingTheJob`); `jobs diff` reaches it through `fetch`, which calls
`parse(jobId, …)` and lets the exception carry the job id to `BaasApp.reportFailure`, as before. No command-level
test drives a bad manifest through `diff`, because its S3 read has no seam; that is unchanged by this change.

## `jobs show` (section 4)

`JobsShowCommandTest` 16/16 (7 new, one per new scenario plus the JSON environment's shape),
`EnvironmentViewTest` 8/8 (each form's fallback), `PrintableTest` 2/2, `S3DownloadIT` 5/5 (new:
`listsEachObjectWithItsSize`, on LocalStack). Rendered from a fixture built on job 20261026T111802133Z-3a07e193's
manifest and tags (schema 7 fields substituted), through a temporary print in
the test, removed afterwards:

```
Job 20261006T165241024Z-47516b65   completed · excluded from results
  Project      baas-e2e
  Type         jcstress
  Created      2026-10-06 16:52:41 UTC
  Instance     i-0a5605864eb16d146 · c5.2xlarge
  Result path  jobs/baas-e2e/20261006T165241024Z-47516b65/
  Tags         branch=dependabot/maven/software.amazon.awssdk-bom-2.57.3
               commit=20e8b2390918c0c1edda9796848009b6c9108c7d
               source=ci

Environment (schema 7)
  machine      imageVersion 1.3.0 · amiId ami-006bf9067587a5c80
  cpu          model Intel(R) Xeon(R) Platinum 8275CL CPU @ 3.00GHz · arch x86_64 · vcpus 8 · physicalCores 4 · threadsPerCore 2
  memory       15.3 GiB · no swap
  os           version Amazon Linux 2023.12.20260803 · kernelRelease 6.1.177-224.371.amzn2023.x86_64
  jvm          Corretto 25.0.4.7.1 (Amazon.com Inc.) · name OpenJDK 64-Bit Server VM
  tools        perf 6.1.177-224.371.amzn2023.x86_64 · async-profiler 4.0
  tunables     perfEventParanoid 1 · kptrRestrict 0 · thp always

Artifacts (8)
  output       cloud-init-output.log    12.3 KiB
               jcstress-output.txt       4.0 KiB
  environment  environment.json          1.1 KiB
               packages.txt             18.5 KiB
  reports      pl.Other.html             2.0 KiB
               pl.Test.html                812 B
  inputs       input/ (2 files)          5.2 MiB
```

Deviations, all in the direction of the spec rather than against it:
- The stored status and the instance id are filtered through `Printable` too. The design named tags,
  environment values and the error code; the instance writes the other two (X3), so the same reasoning
  covers them.
- An unreadable manifest also logs the parse error as a warning on standard error, so the operator sees
  why, not only that.
- `EnvironmentManifest` gained a `tree` component (the parsed document) for `toJson()`, instead of a second
  parse in `show`.

## Hints (section 5)

`Hints` prints `Next` and the aligned lines to a `PrintWriter` (picocli's `getErr()`) or a line sink; the
`Logger` overloads are gone, so no caller can reach the logger by accident. `jobs show`, `jobs list` and
`results query` pass `spec.commandLine().getErr()`; `run` passes a sink that writes to it and does nothing
when the command was built outside picocli (unit tests). Tests: `HelpAndHintsTest` (two hints, one hint,
the error writer receives the block as is), `RunCommandDetachTest` (both hint pairs), `JobsShowCommandTest`
(`aFinishedJobPointsToItsMeasurementsOnStandardError`, `aRedirectedJobShowPrintsNoHint`). The full `baas-cli`
unit suite: 1125 tests, 0 failures, 0 errors.

Not a hint and left as is: `ConfigService`'s refusal text `→ choose one: baas config list`, which is part of
an error message, not a `Hints` call.

## Docs and findings (section 6)

- CLAUDE.md: *S3 result layout* names schema 7, its renames and the no-alias rule; *Diagnostics go to the
  logger* names the `Next` block as the one stderr text that bypasses the logger.
- `docs/review/open-findings.md`: X7 narrowed to `results query`'s table and the general `Console` text path;
  G19's user-data-comment bullet marked fixed. The review report (`api-gaps.md`, `security.md`) is left as the
  record of what was filed.

**6.3 Full reactor.** `mvn verify` on 2026-10-29: BUILD SUCCESS in 5:48, every module green. Suite totals as
Maven printed them: 100, 60, 15, 1125 and 52 tests, 0 failures, 0 errors, 0 skipped. `ASYNC_PATH` was not
exported, so `JmhWithAsyncProfilerSubcommandServiceIT` did not run; this change touches nothing on the runner.

## End to end (section 7, manual, paid) — 2026-10-29, `baas-development`

`baas-development` had been torn down and its deployer policy detached, so it was rebuilt first: the user
attached `BaasCliDeployerPolicy-baas-development`, then the reactor CLI ran
`admin deployment setup --non-interactive --build-image --region eu-central-1`, which created the stack and
published `ami-0ac966b256742be9d` (base 1.4.0, contract tests passed) in about 21 minutes. The deployment is left
in place; its snapshot costs about $0.20/month until a teardown.

**7.1** Reactor CLI, `run jcstress … -- --mode sanity`, job `20261029T114929239Z-5f1e5ffa`, `completed`, exit 0,
one c5.2xlarge for about a minute. Its `environment.json`:

```json
{
  "schemaVersion": 7,
  "machine": {
    "imageVersion": "1.4.0",
    "amiId": "ami-0ac966b256742be9d",
    "instanceType": "c5.2xlarge"
  },
  "cpu": {
    "model": "Intel(R) Xeon(R) Platinum 8124M CPU @ 3.00GHz",
    "arch": "x86_64",
    "vcpus": "8",
    "physicalCores": "4",
    "threadsPerCore": "2",
    "maxMhz": ""
  },
  "memory": {
    "totalMib": "15510",
    "swapTotalMib": "0"
  },
  "os": {
    "version": "Amazon Linux 2023.12.20260803",
    "kernelRelease": "6.1.177-224.371.amzn2023.x86_64"
  },
  "jvm": {
    "version": "openjdk version \"25.0.4\" 2026-07-21 LTS",
    "release": "25.0.4",
    "vendor": "Amazon.com Inc.",
    "vendorVersion": "Corretto-25.0.4.7.1",
    "name": "OpenJDK 64-Bit Server VM"
  },
  "tools": {
    "perf": "perf version 6.1.177-224.371.amzn2023.x86_64",
    "asyncProfiler": "Async-profiler 4.6 built on Oct 12 2026"
  },
  "tunables": {
    "perfEventParanoid": "1",
    "kptrRestrict": "0",
    "transparentHugepages": "[always] madvise never"
  }
}
```

`schemaVersion` 7; `vcpus` 8, `physicalCores` 4, `threadsPerCore` 2 (closes 1.1 on the instance); `totalMib`
15510 (the kernel's MemTotal of 15882372 KiB, divided by 1024 and rounded down, as the v6 job below recorded
it); `jvm.release` 25.0.4. The stored result's tags: `jdk=25.0.4`, equal to `jvm.release`; `cpuModel`,
`instanceType` and `imageVersion` equal their manifest fields.

**7.2** `jobs show 20261029T114929239Z-5f1e5ffa`. On a terminal (a pseudo-terminal from `script`, since the agent
has none), standard output and standard error together:

```
2026-10-29 12:51:51:044 +0100 WARN CliVersionCheck - Unreleased build (baas 0.0.0-semantically-released): not checked against the release deployment baas-development was set up with.
Job 20261029T114929239Z-5f1e5ffa   completed · excluded from results
  Project      benchmark-as-a-service
  Type         jcstress
  Created      2026-10-29 11:49:29 UTC
  Instance     i-0dea4a1102c4228d6 · c5.2xlarge
  Result path  jobs/benchmark-as-a-service/20261029T114929239Z-5f1e5ffa/
  Tags         branch=readable-jobs-show
               source=local

Environment (schema 7)
  machine      imageVersion 1.4.0 · amiId ami-0ac966b256742be9d
  cpu          model Intel(R) Xeon(R) Platinum 8124M CPU @ 3.00GHz · arch x86_64 · vcpus 8 · physicalCores 4 · threadsPerCore 2
  memory       15.1 GiB · no swap
  os           version Amazon Linux 2023.12.20260803 · kernelRelease 6.1.177-224.371.amzn2023.x86_64
  jvm          Corretto 25.0.4.7.1 (Amazon.com Inc.) · name OpenJDK 64-Bit Server VM
  tools        perf 6.1.177-224.371.amzn2023.x86_64 · async-profiler 4.6
  tunables     perfEventParanoid 1 · kptrRestrict 0 · thp always

Artifacts (8)
  output       cloud-init-output.log                                                   12.0 KiB
               jcstress-output.txt                                                     43.5 KiB
  environment  environment.json                                                          1006 B
               packages.txt                                                            20.8 KiB
  reports      pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults.html      34.4 KiB
               pl.wsztajerowski.IntegerIncrementing.TestWithInterestingResults.html    34.4 KiB
  inputs       input/ (2 files)                                                        23.1 MiB
Next
  measurements  baas --deployment baas-development query --job-id 20261029T114929239Z-5f1e5ffa
  files         baas --deployment baas-development jobs download 20261029T114929239Z-5f1e5ffa
```

With standard output redirected to a file: the same view in the file, and on standard error only the
unreleased-build warning — no `Next` block on either stream.

**7.3** A schema-6 job was needed on this deployment, and the rebuilt one held none, so the installed release
(`baas 8.0.0`, which writes schema 6) ran the same JCStress sanity job: `20261029T115214052Z-75c9e78d`,
`completed`, one more c5.2xlarge for under a minute. Then `jobs diff <v6> <v7>`:

```
WARN JobsDiffSubcommand - Manifests use different schema versions (6 vs 7) — added or removed fields below may reflect that rather than an environment change
Differs in: cpu, memory, jvm

GROUP      FIELD              20261029T115214052Z-75c9e78d   20261029T114929239Z-5f1e5ffa
-------------------------------------------------------------------------------------------
cpu        cores              8                              (absent)
cpu        vcpus              (absent)                       8
cpu        physicalCores      (absent)                       4
memory     totalKb            15882372                       (absent)
memory     swapTotalKb        0                              (absent)
memory     totalMib           (absent)                       15510
memory     swapTotalMib       (absent)                       0
jvm        release            (absent)                       25.0.4
```

The warning, then every renamed or added field present on one side only, and nothing else: same AMI, CPU
model, kernel and JVM. Two paid jobs in all, about two instance-minutes.

## Verification (8.1, `/opsx:verify`, 2026-10-29)

Code paths are under `baas-cli/src/main/java/pl/wsztajerowski/baas/`; tests under the matching `src/test` path.

### Requirement → code → test → gap

| Requirement / scenario | Code | Test | Gap |
|---|---|---|---|
| **Every job records its environment, grouped** (schema 7) | `jobs/UserDataScriptBuilder.java:25,203-223` and the heredoc | `UserDataScriptBuilderTest.manifestIsValidJsonForARepresentativeCapture`, `manifestSchemaVersionIsBumpedForTheNewField`; 7.1 | — |
| · Manifest accompanies a successful job / survives a failed one / environment only / what the image does not control | unchanged upload order | existing `UserDataScriptBuilderTest` cases; 7.1 | — |
| · Logical CPUs and physical cores are told apart | `UserDataScriptBuilder.java:203-210` | `theManifestRecordsLogicalCpusAsVcpusNeverAsCores`, `physicalCoresAreSocketsTimesCoresPerSocket`, `physicalCoresStayEmptyWhenLscpuLacksAFactor`; 7.1 (8 / 4 / 2) | — |
| · Memory is recorded in whole MiB | `UserDataScriptBuilder.java:214-215` | `memoryIsRecordedInWholeMibRoundedDown` (15882372 KiB → 15510); 7.1 vs 7.3's v6 job | — |
| · Tags agree with the manifest | `UserDataScriptBuilder.java:223`, `--tag jdk=${JDK_VERSION}` | `jvmReleaseIsTheJdkTagsValue`; 7.1 (`jdk=25.0.4`) | — |
| **Two jobs' environments are compared group by group** | `jobs/EnvironmentManifest.java:113` (as stored), `KIB_FIELDS`/`compared` removed | `EnvironmentManifestTest` (14) | — |
| · Memory a few KiB apart / a real memory difference | same | `memoryAFewKibApartIsNoDifference`, `aRealMemoryDifferenceIsPrintedInMib` | — |
| · A JVM change is named by group (version and release) | same | `aJvmChangeListsVersionAndReleaseUnderJvm` (added during verify) | — |
| · Different schema versions without translation | same; warning in `JobsDiffSubcommand` | `manifestsOfDifferentSchemaVersionsAreComparedWithoutTranslation`; 7.3 | — |
| · Unknown id, missing manifest, operator credentials, packages | unchanged | existing `JobsDiffCommandTest`, `PackagesDiffTest` | W2 |
| **`baas jobs show` reports one job's execution** | `jobs/JobsShowSubcommand.java:169-230`, `jobs/EnvironmentView.java`, `jobs/ArtifactView.java`, `console/Printable.java`, `aws/S3UploadService.java:71` | `JobsShowCommandTest` (18), `EnvironmentViewTest` (9), `PrintableTest` (2), `S3DownloadIT.listsEachObjectWithItsSize`; 7.2 | — |
| · Tags already shown are not repeated | `JobsShowSubcommand.java:176,256` | `tagsAlreadyShownAreNotRepeated`; 7.2 | — |
| · Environment values shown for reading / unknown field as recorded | `EnvironmentView.java:30-75` | `environmentValuesAreShownForReading`, `anUnknownFieldIsShownAsRecorded`, `EnvironmentViewTest`; 7.2 | — |
| · Artifacts one per line with sizes | `ArtifactView.java:44`, `S3UploadService.java:71` | `artifactsAreListedOnePerLineWithSizes`, `S3DownloadIT.listsEachObjectWithItsSize`; 7.2 | — |
| · Control characters not printed | `Printable.java:13`, applied in `JobsShowSubcommand`, `EnvironmentView` (field names too, fixed during verify) | `controlCharactersWrittenByARunnerAreNotPrinted`, `aFieldNameIsFilteredLikeItsValue`, `PrintableTest` | — |
| · Trailing content does not reach JSON | `EnvironmentManifest.java:29,63`, `JobsShowSubcommand.java:157,194` | `aManifestWithTrailingContentDoesNotReachTheJsonOutput`, `jsonEnvironmentIsTheParsedManifest`, `trailingContentFailsTheParseNamingTheJob` | — |
| · Completed, never booted, vanished, unknown id, JSON, deadlines | unchanged paths | existing `JobsShowCommandTest` cases | — |
| **Next-step hints are shown to people, never to scripts** | `console/Hints.java:38`; callers `JobsShowSubcommand.java:164`, `JobsListSubcommand.java:169`, `ResultsQuerySubcommand.java:211`, `RunCommand.java:213` | `HelpAndHintsTest` (block, one hint, error writer as is, scripts see none), `RunCommandDetachTest`, `aFinishedJobPointsToItsMeasurementsOnStandardError`, `aRedirectedJobShowPrintsNoHint`; 7.2 | — |

### Design adherence

Every decision is implemented as written: formatters in the CLI with raw fallback (a `switch` over
`group.field` rather than a map, which is the same thing); new names and no alias; MiB rounded down in `awk`;
`physicalCores` guarded; sanitising at print and a re-serialised JSON environment; hints to `getErr()`; sizes from
the existing listing. The deviations are those listed under section 4, plus the field-name filtering added
during verify. All of them widen the filtering the design asked for; none narrows it.

### Fixed during verify

- `EnvironmentView` printed a manifest field's **name** unfiltered in the raw fallback. Field names are as
  runner-writable as values, so they now go through `Printable` (`aFieldNameIsFilteredLikeItsValue`).
- The diff scenario *A JVM change is named by group* names `release`, and nothing tested it:
  `aJvmChangeListsVersionAndReleaseUnderJvm` added.

### Open warnings

- **W1** — *Closed 2026-10-29.* No breaking marker: the change ships as a plain `feat` (user's decision; design.md,
  *Resolved Questions*).
- **W2** — `jobs diff`'s bad-manifest path has no command-level test: its S3 read has no seam. The parse error and
  its job id are unit-tested, and `diff` calls that parse directly. Unchanged by this change.
- **W3** — `baas-development` was rebuilt for section 7 and left running: an AMI snapshot at about $0.20/month,
  plus two stored test jobs (both `exclude_from_results=true`), until someone tears it down.
- **W4** — The rendered `jobs show` artifact column is as wide as the longest name. JCStress report pages make it
  about 70 characters, so a line is about 100 columns wide and wraps in an 80-column terminal (seen in 7.2).
  Cosmetic. Clipping the names would hide the part that tells two reports apart.

### Result

23 of 23 tasks done, 4 of 4 requirements implemented, every scenario covered by a test, and the paid
end-to-end check confirmed the schema-7 capture on a real instance. No critical issues; W1 closed, three warnings open.

## Tag rename (section 9, added 2026-10-29)

`exclude_from_results` → `hidden`, `--show-excluded` → `--show-hidden`, marker `· excluded from results` →
`· hidden`, as the user decided: all three, no alias, no data migration. `baas-cli`: 1127 unit tests and 53
integration tests, 0 failures. That includes `ResultsQueryServiceIT` (21) with the new
`theFormerKeyHidesNothing`, which round-trips the renamed filter expression against LocalStack's DynamoDB.
One test, `tagsAlreadyShownAreNotRepeated`, failed after the mechanical rename because it asserted that the
output never contains `hidden`, which the new marker contains. It now asserts the absence of `hidden=` (the tag
line). The diagrams `baas-ci-e2e`, `baas-results`, `baas-run` and `baas-jobs` were rendered with `mmdc` and
inspected: each renamed label sits where the old one was. Spec deltas: *Excluded results are filtered out*
REMOVED (heading copied verbatim from the main spec) and replaced by *Hidden results are filtered out*. Five
more requirements are MODIFIED with the names substituted, their scenario names kept.

- **W5** — The `baas-development` test jobs of 2026-10-29 and every CI fixture row on `baas-381492019823` carry
  `exclude_from_results=true`, so once this ships they appear in `results query` sweeps and in the picker.
  Accepted (no production data). Clear them with `--exclude-tag exclude_from_results=true`, or remove them
  with the parked v6 cleanup.
- The CI e2e has not run with the new tag yet; it first does on the PR.


### End to end after the rename (2026-10-29, `baas-development`, manual, paid)

JARs rebuilt from the tree after the rename. A script mirroring `e2e-cloud-test.yml`'s two jobs and every one of
their assertions, run with `CI=true` and `--deployment baas-development`, plus three checks of this change: the
stored `hidden=true` tag, `--show-excluded` refused as an unknown option (exit 2), and `jobs show`'s
`completed · hidden` marker with no `hidden=` tag line.

- `jmh-with-async`, detached, `Incrementing_Synchronized -f 1 -wi 1 -i 1`, wall-clock flame graph and JFR:
  `20261029T124513275Z-6edd6eae`, `completed`. 22 checks, all PASS, including absent from the project sweep,
  present under `--show-hidden`, flame graph and JFR produced, manifest schema 7.
- `jcstress`, attached, `--mode sanity`: `20261029T124643146Z-9aa35168`, `completed`. 11 checks, all PASS.

34 of 34 PASS. Two more c5.2xlarge instances, a few minutes each.

## Short, coloured log lines (section 10, added 2026-10-29)

`console.LogStyle` decides once in `BaasApp.main`; `console.LevelColouring` colours a line's level word.
`LogStyleTest` 8/8 (each decision, each level, a non-log line, a flush before the newline). `baas-cli`: 1135 unit
and 53 integration tests, 0 failures, `SliceBoundaryTest` included. The stderr test was probed on macOS first
(same terminal → `/dev/fd/1` and `/dev/fd/2` the same file; `2> file` → different; stdout redirected → not
interactive).

**10.3** Reactor CLI against `baas-development`, under `script` (a pseudo-terminal), raw bytes via `cat -v`
(`^D^H^H` is `script`'s own noise):

```
a) terminal                 ^[[1;33mWARN^[[0m Unreleased build (baas 0.0.0-semantically-released): …
b) terminal, NO_COLOR       WARN Unreleased build (baas 0.0.0-semantically-released): …
c) terminal, 2> file        2026-10-29 14:19:06:682 +0100 WARN CliVersionCheck - Unreleased build … (in the file)
d) terminal, stdout piped   2026-10-29 14:19:10:347 +0100 WARN CliVersionCheck - Unreleased build …
   ERROR on a terminal      ^[[1;31mERROR^[[0m No deployment 'no-such' is configured on this machine …
   INFO on a terminal (-v)  ^[[1;34mINFO^[[0m Showing 1 of 6 jobs (--limit 1).
```

- **W6** — `DEBUG`'s cyan is covered by `LogStyleTest` only: no command tried logs at debug on its success path.
- **W7** — On Windows there is no `/dev/fd`, so the check fails and lines keep the full format. That is safe,
  but nobody there gets the short format.
