# Proposal

## Why

`jcstress` has no end-to-end coverage at all (CLAUDE.md). `e2e-cloud-test.yml` runs only
`jmh-with-async`, so a regression in the JCStress path — the runner's JCStress subcommand, its report
parsing, the `JCSTRESS#` item, `jcstress-output.txt` — passes CI and is found by a consumer. The
`detached-run` exploration (`openspec/changes/detached-run/exploration.md` §7, Q6/Q7, 2026-10-05)
decided that the self-test grows a second, **attached** leg running JCStress in sanity mode, landed
first and on its own because it needs nothing from `runs-command` or `detached-run`.

Sanity mode cannot be requested today: the runner builds JCStress's argument list explicitly and
exposes no mode option, so `-m sanity` after `--` is rejected by the runner's option parser — and the
runner's `-m` is already `--mongo-connection-string`.

## What Changes

- **Runner:** the `jcstress` subcommand gains `--mode <mode>`, forwarded to JCStress as `-m <mode>`
  and omitted when not given, so JCStress's own default applies exactly as today. Long form only:
  `-m` stays `--mongo-connection-string` until `retire-mongodb` removes it.
- **CI:** `e2e-cloud-test.yml` gains a second job that runs `baas run jcstress` attached, against
  `fake-stress-tests`, with `-- --mode sanity`. It asserts the run's shape — `runStatus` completed,
  one stored JCStress item retrievable by run id carrying the job's tags, `jcstress-output.txt` and
  `environment.json` in the prefix — and **not** pass/fail counts: the fixture's
  `TestWithForbiddenResults` fails or passes depending on whether the race fires.
- The existing `jmh-with-async` job is unchanged. Each job launches its own instance; they run in
  parallel.
- The workflow's path filter also covers `fake-stress-tests/**`.

## Capabilities

### New Capabilities
- `jcstress-execution`: what the runner passes to JCStress, starting with the run mode.

### Modified Capabilities
- `ci-benchmark-execution`: the self-test is no longer "a single job" — it has one job per covered
  benchmark type, and it additionally covers JCStress.

## Impact

- **Code:** `benchmark-runner` — `ApiJCStressOptions`, `JCStressOptions` (+ builder),
  `JCStressSubcommandService`; tests beside them. `.github/workflows/e2e-cloud-test.yml`.
  CLAUDE.md's "`jcstress` has no end-to-end coverage at all" sentence.
- **Cost:** one extra short fixture instance (default instance type, JCStress sanity mode, a few
  minutes including boot) per triggering event of the already path-filtered self-test — cents per
  push, the same order CLAUDE.md already accepts for the existing job. No AWS resource changes, no
  standing cost, no IAM change.
- **Comparability:** none affected. A run without `--mode` passes JCStress exactly the arguments it
  passes today; the self-test's runs carry `exclude_from_results=true`.
- **Deliberately not changed:** the `-m` short option (still Mongo); the CLI (`baas run` already
  forwards everything after `--` verbatim); the existing `jmh-with-async` job and its assertions;
  `cancel-in-progress: false` and the termination layers; the release pin of the runner JAR (CI uses
  `--runner-jar` from the same checkout, so the new option is testable in this PR).
- **Findings:** closes no `open-findings.md` ID; it closes the CLAUDE.md-recorded JCStress coverage
  gap and implements `detached-run` exploration Q6/Q7.
