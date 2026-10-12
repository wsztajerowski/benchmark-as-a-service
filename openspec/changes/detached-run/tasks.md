# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm the shutdown hook fires on a normal `baas run` exit, not only on a signal: trace
  `BaasApp.main` to its `System.exit` (or JVM end) and record in `verify.md` that a detached return
  without the flag would run `stop(CANCELLED)` (DR1 as stated in design.md *Context*).
- [ ] 1.2 Confirm `baas jobs show <id> --format json` exits 0 for any job it finds and that its
  `status` field is the resolved one (`JobListing.resolve`, `VANISH_GRACE`), so the CI loop can
  read terminal statuses and `vanished` from it. Record the field names in `verify.md`.
- [ ] 1.3 Confirm ADR number 0007 is free and that `docs/diagrams/baas-run.mmd` and
  `baas-ci-e2e.mmd` are the diagrams showing the poll; record any other diagram that draws it.

## 2. `--detach` on `baas run`

- [ ] 2.1 Add `--detach` to `RunCommand` (and so to `baas jobs run`), with help text naming
  `baas jobs show` and `baas jobs terminate`. Verify: a picocli parse test for both spellings, and
  `baas run --help` lists it.
- [ ] 2.2 Add the `detached` flag and make the hook skip `stop` when it is set. Extract the hook's body
  into a package-private method if needed. Verify: a unit test that a session with a confirmed launch
  and the flag set is not stopped, and one that without the flag it is (unchanged attached behaviour).
- [ ] 2.3 After `confirmLaunched`, under `--detach`: return 1 on `CANCELLED_WHILE_LAUNCHING` as today;
  otherwise set the flag, set `summaryJobStatus` to `launched` (`CONFIRMED`) or `launching`
  (`UNCONFIRMED`) and return 0 without polling. Verify: unit tests for each `Confirmation` value.
- [ ] 2.4 Make `printJobSummary` report `"status":"launched"` for a detached exit 0, and `failed` for
  every non-zero exit as today. Verify: summary tests for detached success, detached launch failure
  and an unchanged attached success.
- [ ] 2.5 On a detached exit 0, log that the job is detached and how to follow it, and make the hint
  show `baas jobs show <jobId>` and `baas jobs terminate <jobId>`. Verify: a test on the hint lines;
  the attached hints unchanged.
- [ ] 2.6 Run `mvn verify` for the full reactor with `ASYNC_PATH` exported; record the test count in
  `verify.md`.

## 3. CI self-test

- [ ] 3.1 Switch the `benchmark` job in `e2e-cloud-test.yml` to `--detach`: assert the summary's
  `status` is `launched`, then poll `baas jobs show <id> --format json` every 15 s until `status` is
  terminal or `vanished`, bounded by timeout + margin + one poll; set `job_status` from the final
  poll so the existing *job item recorded the outcome* step and every later assertion run unchanged.
  Leave the `jcstress` job attached and `cancel-in-progress: false`. Verify: `actionlint` (if
  installed) and a read-through of every step that consumes `steps.run.outputs`.
- [ ] 3.2 **Manual:** CI on the PR — both `Benchmark on EC2 via baas run` (detached) and
  `JCStress on EC2 via baas run` (attached) pass. Record the run ids in `verify.md`.

## 4. Documentation

- [ ] 4.1 Write `docs/adr/0007-detached-run.md`: `jobs terminate` is a detached job's third layer,
  the hook disarm decision, and that it supersedes the archived `run-status-in-dynamodb` non-goal
  (DR4). Amend ADR 0001's termination-layers row to point at it.
- [ ] 4.2 Update CLAUDE.md's *Three termination layers*, layer 3: for a detached job the hook is
  disarmed once the launch is confirmed, and `baas jobs terminate` takes its place.
- [ ] 4.3 Update `docs/diagrams/baas-run.mmd` (the detached branch after the launch confirmation) and
  `baas-ci-e2e.mmd` (the detached poll). Render each with `mmdc` to the scratchpad and look at the
  PNG before committing.
- [ ] 4.4 Delete DR1's and DR4's entries from `docs/review/open-findings.md` (the index rows and the
  `detached-run` section).
- [ ] 4.5 Add a line to `exploration.md`'s status block pointing at `design.md` as superseding it
  where the two disagree.

## 5. Verification

- [ ] 5.1 **Manual, live, on the `baas run` path** (no automated test covers it), on
  `baas-381492019823`:
  - `baas run --detach --format json` on `fake-jmh-benchmarks`: exit 0, `status`/`jobStatus`
    `launched`, the CLI gone within seconds of the launch; `baas jobs show` reads `launched` or
    `running` (never `vanished`), then `completed`; the instance terminated itself.
  - Ctrl+C during a detached launch: item `cancelled`, exit non-zero, instance terminated or refusing
    the benchmark.
  - A detached job stopped with `baas jobs terminate --yes`: `cancelled`, instance shutting down.
  - A detached job with `--timeout 60` on a longer benchmark: `failed:124` with no CLI running.
  - An attached `baas run`, unchanged: exit 0, results table printed.

  Record each in `verify.md`.
- [ ] 5.2 Measurement comparison: not applicable — nothing reaches the instance, the benchmark
  process or `environment.json` (proposal, *Comparability*). Record that in `verify.md` instead of
  running one.
- [ ] 5.3 Second-deployment check: not applicable — no change to `infra/`, IAM or the runner image.
  Record that in `verify.md`.
- [ ] 5.4 Run `/opsx:verify` and record the result in `verify.md` in the change directory: the
  requirement → code → test → gap table, open warnings as W1, W2…, and any deviation from the design
  or these tasks.
