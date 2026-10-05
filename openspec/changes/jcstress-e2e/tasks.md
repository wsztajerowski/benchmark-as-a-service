# Tasks

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm the pinned JCStress version accepts `-m sanity`: run
      `java -jar fake-stress-tests/target/fake-stress-tests.jar -m sanity` locally and record the
      wall time and that it writes a report. Also record that an unknown mode (`-m bogus`) exits
      non-zero.
- [ ] 1.2 Confirm the JCStress row's JSON from `baas results --request-id … --format json`:
      `benchmarkName` is `(jcstress) <runId>`, and `tags` carries project/branch/commit/source.
      Check the code path (`ResultRow`, `ResultsCommand.printJson`) and record the fields the job
      will assert.

## 2. Runner: `--mode`

- [ ] 2.1 Add `--mode` (long form only) to `ApiJCStressOptions`, `mode` to `JCStressOptions` and its
      builder, and `addArgumentIfValueIsNotNull("-m", mode)` in `JCStressSubcommandService`. Verify
      with a unit test that `--mode sanity` yields `-m sanity` on the JCStress command line and that
      an absent option yields no `-m`.
- [ ] 2.2 Verify `-m` still parses as `--mongo-connection-string` on the `jcstress` subcommand, with
      a picocli parsing test.
- [ ] 2.3 Run the full reactor `mvn verify` and confirm it is green (the runner ITs need the
      fixture JARs from the reactor).

## 3. CI: the JCStress job

- [ ] 3.1 Add a `jcstress` job to `e2e-cloud-test.yml`. It repeats the setup steps; runs
      `baas run jcstress --format json --project … --tag branch/commit … --runner-jar …
      --benchmark-jar fake-stress-tests/target/fake-stress-tests.jar --tag exclude_from_results=true
      -- --mode sanity`, attached; asserts `runStatus == completed`, one `(jcstress)` row with the
      job's tags, and non-empty `jcstress-output.txt` and `environment.json`; uploads artifacts and
      surfaces the boot log on failure, as the existing job does. Verify the YAML parses
      (`actionlint` if available, else a YAML load).
- [ ] 3.2 Add `fake-stress-tests/**` to the workflow's `pull_request` path filter. Verify by reading
      the filter.

## 4. Documentation

- [ ] 4.1 Update CLAUDE.md: the *e2e-cloud-test.yml* paragraph (two jobs, the JCStress one in sanity
      mode) and remove "`jcstress` has no end-to-end coverage at all". Verify with a grep.
- [ ] 4.2 Update `docs/diagrams/baas-ci-e2e.mmd` for the second job, and render it with `mmdc` per
      the repository rule. Verify that it renders.

## 5. End-to-end verification (manual — no automated test covers the `baas run` path)

- [ ] 5.1 From this machine, run `baas run jcstress … --runner-jar <built runner>
      --benchmark-jar fake-stress-tests/target/fake-stress-tests.jar --tag exclude_from_results=true
      -- --mode sanity` against the real installation. Record the run id, the wall time, `runStatus`,
      and the JCStress row.
- [ ] 5.2 Open the PR into `next-release` and confirm both E2E jobs pass on it. Record the run ids.
      No score comparison is needed: the change does not affect measurements (design.md
      *Comparability*). A run without `--mode` passes JCStress the same arguments as before, which
      task 2.1's test pins.

## 6. Verify

- [ ] 6.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory: a
      requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any
      deviation from the design or tasks.
