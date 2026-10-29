# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm `lscpu` on the runner AMI reports `Socket(s)` and `Core(s) per socket` and that their product is 4 on c5.2xlarge, from a stored job's `cloud-init-output.log` or a `baas jobs connect` session; record the output in verify.md
- [x] 1.2 Confirm `run` closes its status line before `hints(...)` runs (read `RunCommand`'s finally path); if it does not, move the hint call after the close and note the change in verify.md
- [x] 1.3 Confirm the `ListObjectsV2` pages `listKeys` reads expose each object's size, and that no caller other than `show` and `download` depends on `listKeys`' shape; verify by reading `S3UploadService` and grepping its callers

## 2. Manifest schema 7 (user-data)

- [x] 2.1 In `UserDataScriptBuilder`, capture `CPU_VCPUS=$(nproc)`, `CPU_PHYSICAL_CORES` from `Socket(s)` × `Core(s) per socket` (left empty unless both are numbers), and `MEMORY_TOTAL_MIB`/`SWAP_TOTAL_MIB` with `int($2/1024)`; write `vcpus`, `physicalCores`, `totalMib`, `swapTotalMib` and `jvm.release` (`${JDK_VERSION}`) in the heredoc; drop `cores`, `totalKb`, `swapTotalKb`; set `MANIFEST_SCHEMA_VERSION` to 7. Verify with `UserDataScriptBuilderTest` asserting the new keys, the absent old ones and `bash -n` on the rendered script
- [x] 2.2 Fix the `JDK_VERSION` comment (it yields `25.0.4`, not `"25"`) and the stale comment on the manifest's identity before the heredoc (G19's user-data bullet); verify both by reading the rendered source
- [x] 2.3 Verify `aLargeJobStaysWellUnderTheUserDataLimit` still passes and record the rendered size before and after in verify.md

## 3. `jobs diff`

- [x] 3.1 Remove `KIB_FIELDS` and `compared` from `EnvironmentManifest.diff`, comparing every field as stored; verify with `EnvironmentManifestTest` cases for "Memory a few KiB apart is not a difference", "A real memory difference is printed in MiB" and "Manifests of different schema versions are compared without translation"
- [x] 3.2 Enable `FAIL_ON_TRAILING_TOKENS` in `EnvironmentManifest.parse`; verify a test that trailing text fails the parse naming the job, and that `jobs diff` still fails naming the job

## 4. `jobs show`

- [x] 4.1 Add the field formatters (memory GiB/no swap, JVM vendor version + vendor, `perf`/async-profiler version numbers, THP selected mode) with raw fallback when a pattern does not match; verify with unit tests per formatter, including a non-matching value printed raw
- [x] 4.2 Render the Environment section through the formatters, omitting empty values and `machine.instanceType`, printing unknown fields as recorded; verify with `JobsShowCommandTest` for "Environment values are shown for reading" and "An unknown field is shown as recorded"
- [x] 4.3 Render tags one per line without `project`/`type`, and `exclude_from_results=true` as a status marker; verify with a test for "Tags already shown are not repeated"
- [x] 4.4 Add a key-and-size listing to `S3UploadService`, and render artifacts grouped (output, environment, results, reports, inputs) one per line with sizes, a folder as one line with count and total; keep the JSON `artifacts` member as relative keys; verify with a test for "Artifacts are listed one per line with sizes" and an S3 IT for the sizes
- [x] 4.5 Pass every runner-written string `show` prints through one `printable()` that strips C0/C1 controls except tab; verify with a test for "Control characters written by a runner are not printed"
- [x] 4.6 Build the JSON `environment` member from the parsed manifest, `null` when unreadable, and state an unreadable manifest in the text view with exit 0; verify with a test for "A manifest with trailing content does not reach the JSON output"

## 5. Hints

- [x] 5.1 Change `console.Hints` to print a `Next` block (`  <purpose>  <command>`, purposes padded) to a given writer, keeping the `Consumer<String>` overload for tests; verify with `Hints` unit tests for one and two hints and for a non-interactive console printing nothing
- [x] 5.2 Switch `jobs show`, `jobs list`, `results query` and `run` to the command's error writer; verify each command's existing hint test against the new block, and that no hint line carries a timestamp, level or logger name

## 6. Docs and findings

- [x] 6.1 Update CLAUDE.md: *S3 result layout* names schema 7 and the renamed fields; *Diagnostics go to the logger* names the hint block as plain stderr; verify by reading the diff
- [x] 6.2 In docs/review/open-findings.md, narrow X7 to `ResultsTable` and the general `Console` text path, and delete G19's user-data-comment bullet; verify by reading the diff
- [x] 6.3 Run the full reactor `mvn verify` and record the result in verify.md

## 7. End-to-end (manual, on the `baas run` path, paid)

- [x] 7.1 Manual: with the reactor build (`baas-dev`), run one JCStress job in sanity mode on `baas-development`; download its `environment.json` and confirm `schemaVersion` 7, `vcpus`, `physicalCores`, `totalMib`, `swapTotalMib` and `jvm.release`, and that the result's `jdk` tag equals `jvm.release`; record in verify.md
- [x] 7.2 Manual: run `baas jobs show` on that job in an interactive terminal and with stdout redirected; paste both outputs into verify.md, confirming the `Next` block appears only in the first
- [x] 7.3 Manual: run `baas jobs diff` between that job and a v6 job; confirm the schema warning and the one-sided renamed fields; record in verify.md

## 8. Verify

- [x] 8.1 Run /opsx:verify and record the result in verify.md: requirement → code → test → gap table, open warnings as W1, W2…, and any deviation from design or tasks

## 9. Rename `exclude_from_results` to `hidden` (added 2026-10-29)

- [x] 9.1 Rename the tag in `baas-model` (`TagKeys.HIDDEN = "hidden"`) and every reader: `ResultsQueryService`'s filter, `ResultRow.hidden()`, `ResultsTable`, `JobsShowSubcommand`'s marker; verify with the `baas-cli` unit suite and `ResultsQueryServiceIT`
- [x] 9.2 Rename `--show-excluded` to `--show-hidden`, help text and refusal message included; verify by the existing option tests and `ResultsQueryServiceIT`
- [x] 9.3 Pin that the former key hides nothing (no alias, no migration); verify with `ResultsQueryServiceIT.theFormerKeyHidesNothing`
- [x] 9.4 Rename in `e2e-cloud-test.yml`, `README.md`, `CLAUDE.md` and the diagrams `baas-ci-e2e`, `baas-results`, `baas-run`, `baas-jobs`; verify each `.mmd` by rendering it with `mmdc` and looking at the PNG

## 10. Short, coloured log lines on a terminal (added 2026-10-29)

- [x] 10.1 Decide the format once in `BaasApp.main`: short only when `Console`'s interactive test passes and `/dev/fd/2` is the same file as `/dev/fd/1`; set SimpleLogger's `showDateTime`/`showShortLogName` before any logger exists; verify with a unit test of the decision over injected inputs
- [x] 10.2 Add the level-colouring stderr filter (first word only, SimpleLogger levels only, `NO_COLOR` respected) and install it in `main`; verify with unit tests for each level, a non-log line, a partial write and a stack trace
- [x] 10.3 Manual: run the reactor CLI under a pseudo-terminal, with stderr redirected, with stdout piped, and with `NO_COLOR`; record the raw bytes in verify.md
- [x] 10.4 Update CLAUDE.md (*Diagnostics go to the logger*, *Terminal effects*) and rerun the `baas-cli` unit and integration suites

