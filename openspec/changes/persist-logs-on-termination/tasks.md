# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm EC2's default `TerminateInstances` attempts a graceful OS shutdown, so a systemd
  `ExecStop` runs on a CLI-initiated termination (AWS EC2 User Guide, *Terminate an instance with a
  graceful OS shutdown*; the API's `--skip-os-shutdown` is the opt-out) and record the source in
  design.md
- [x] 1.2 Confirm the async-profiler 4.6 `linux-x64` tarball keeps the layout the bake moves into
  place (`async-profiler-*-linux-x64/lib/libasyncProfiler.so`, `bin/asprof`) by listing it

## 2. Ship logs from the instance

- [x] 2.1 Add `ship_logs` to `UserDataScriptBuilder` (boot log, `${WORK_DIR}/*-output.txt`,
  `${WORK_DIR}/**/*.log` under `logs/`), defined before the watchdog forks; verify with a stub-`aws`
  test that asserts the three uploads and their keys
- [x] 2.2 Call `ship_logs` from the watchdog, the refused-`running` exit and the normal end in place
  of the boot-log-only upload; verify the existing ordering tests, updated, still pin "status, then
  upload, then terminate"
- [x] 2.3 Install `baas-ship-logs.service` right after the watchdog starts, its `ExecStop` a file
  generated from `declare -f ship_logs`; verify by running the generated hook file under a stub `aws`
  with an empty environment and asserting it uploads to the job's prefix
- [x] 2.4 Verify `theRenderedScriptIsSyntacticallyValidBash`, `commentLinesAreNotRendered` and
  `aLargeJobStaysWellUnderTheUserDataLimit` still pass

## 3. Upgrade async-profiler

- [x] 3.1 `infra/runner-image.yaml`: async-profiler 4.6, `imageVersion` 1.4.0; `ci-pr-build.yml`
  `ASYNC_PROFILER_VERSION` 4.6; verify `RunnerImageRendererTest` passes and the rendered URL names 4.6
- [x] 3.2 Run `JmhWithAsyncProfilerSubcommandServiceIT` locally with `ASYNC_PATH` pointing at the 4.6
  macOS library, and verify it ran (not skipped) and passed

## 4. Documentation

- [x] 4.1 CLAUDE.md: record the shutdown hook under the user-data invariants (no shebang, why a
  `#` line cannot carry it) and the S3 layout rows for the logs of a stopped job

## 5. Full build

- [x] 5.1 `mvn -B clean verify` from the reactor root passes

## 6. End-to-end (manual, `baas run` path — no automated test covers it)

- [x] 6.1 On `baas-development`, with the reactor CLI and `--runner-jar`, start a `fake-jmh-benchmarks`
  job long enough to be mid-benchmark, stop it with `baas jobs terminate`, and verify the prefix holds
  `cloud-init-output.log` (ending in the shutdown), `jmh-output.txt` with iteration lines, and the job
  item says `cancelled`. Record it in verify.md
- [ ] 6.2 *(deferred, verify.md W1)* Second deployment for the image change (infra rule): `baas admin image build` on
  `baas-development` with this branch, then one `jmh-with-async` job, and confirm `environment.json`
  says `tools.asyncProfiler` 4.6 and a flame graph is produced. Record it in verify.md, or the reason
  it was deferred
- [ ] 6.3 *(deferred, verify.md W1)* Score comparison against a pre-change run (measurement-affecting rule): compare one
  `fake-jmh-benchmarks` score on 1.4.0 against a 1.3.x run, investigate any difference and record
  the spread, or record that it was deferred with 6.2

## 7. Verify

- [x] 7.1 Run `/opsx:verify` and record the result in verify.md: requirement → code → test → gap,
  warnings as W1, W2…, and any deviation from design or tasks
