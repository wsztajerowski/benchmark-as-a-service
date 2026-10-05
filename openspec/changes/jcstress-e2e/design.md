# Design

## Context

See `proposal.md` for the motivation and `specs/` for the requirements.

- The runner's `jcstress` subcommand takes its JCStress options through `ApiJCStressOptions`, a
  picocli mixin. `JCStressSubcommandService` then builds JCStress's command line from the
  `JCStressOptions` record, one `addArgumentIfValueIsNotNull` per option (`-r -c -f -fsm -hs
  -jvmArgs -jvmArgsPrepend -pth -sc -spinStyle -strideCount -strideSize -t`). Nothing passes through
  unknown arguments, so an option the runner does not declare is a picocli error.
- `-m` is already `--mongo-connection-string` (`ApiCommonSharedOptions`, mixed into every
  subcommand).
- `baas run` forwards everything after `--` verbatim into the runner invocation in user-data. No
  CLI change is needed to pass `--mode sanity`.
- `e2e-cloud-test.yml` is one job, `jmh-with-async` against `fake-jmh-benchmarks`. It builds the
  whole reactor (`mvn package`), so `fake-stress-tests/target/fake-stress-tests.jar` already exists
  in the job. It uses `--runner-jar` from the same checkout, so a runner change is exercised by the
  PR that makes it.
- A JCStress run stores one item (`sk = JCSTRESS#…`). `baas results --request-id … --format json`
  returns it as one row whose `benchmarkName` is `(jcstress) <runId>`, carrying the run's `tags`.
- The fixture `IntegerIncrementing` holds two tests. `TestWithInterestingResults` is always
  acceptable. `TestWithForbiddenResults` fails whenever the non-atomic increment races. The runner
  treats a non-zero JCStress exit as non-fatal when a report exists, so the run still records
  `completed`.

## Goals / Non-Goals

**Goals:**
- A JCStress run's mode can be chosen, and leaving it unchosen changes nothing.
- One end-to-end JCStress run per triggering event, attached, asserting only deterministic facts.

**Non-Goals:**
- Exposing the rest of JCStress's options that the runner lacks (`-time`, `-iters`, …). Add them
  when someone needs them.
- Detaching anything. The existing job stays attached here; `detached-run` switches it later.
- Asserting JCStress pass/fail counts, or making the fixture deterministic.

## Decisions

### The mode is a long-only `--mode`, forwarded verbatim as JCStress's `-m`

It is added to `ApiJCStressOptions` and `JCStressOptions` like every other JCStress option, and emitted
with `addArgumentIfValueIsNotNull("-m", mode)`, so an absent option adds no argument.

*Rejected:*
- **A `-m` short form.** It is taken by `--mongo-connection-string`. It can become an alias once
  `retire-mongodb` frees it.
- **Validating against an enum** (`sanity|quick|default|tough|stress`). JCStress owns that
  vocabulary and has changed it between versions, and an unknown value already fails visibly in
  JCStress (spec: *An unknown mode fails the run visibly*).
- **A generic passthrough for unknown JCStress arguments.** It would also accept typos and
  arguments the runner itself sets (`-r`), and nobody needs it yet.

### The JCStress leg is a second job, not a matrix leg

`e2e-cloud-test.yml` gets a `jcstress` job beside `benchmark`. The setup steps (checkout, Java, build,
AWS credentials, config sync) are repeated in it.

*Rejected:*
- **A matrix over types.** Almost every assertion step is type-specific (`jmh-result.json`, the
  flamegraph and the `.jfr` exist only for `jmh-with-async`), so a matrix would need `if:` on most
  steps.
- **A composite action for the shared setup.** That is a new abstraction to save five short steps
  in two places.
- **A separate workflow file.** It would duplicate the triggers, the path filter and the
  concurrency group, which belong together.

### The JCStress job asserts shape, and runs the whole fixture

The job asserts:
- the summary's `runStatus` is `completed`;
- `results --request-id` returns exactly one row, whose `benchmarkName` starts with `(jcstress)`
  and whose `tags` carry the job's project, branch, commit and `source=ci`;
- `jcstress-output.txt` and `environment.json` are non-empty in the run's prefix.

*Rejected:* narrowing the run to `TestWithInterestingResults` with `-t`. That would make the counts
deterministic, but the counts are still not asserted, and running the forbidden test keeps the
"failed tests are still stored" path exercised end to end.

### Both jobs share the workflow's concurrency group

The existing group is per ref with `cancel-in-progress: false`, so two jobs of one workflow run
proceed in parallel, and a newer push still queues behind the running workflow. No change.

## Comparability

**No effect on stored results.**
- A JCStress run without `--mode` builds the same JCStress command line as before, so historical
  JCStress results stay comparable.
- A run with `--mode sanity` runs a much shorter JCStress run. Its stored summary is not comparable
  with default-mode runs. The mode is not recorded in any tag. The self-test's runs are
  `exclude_from_results=true`.
- Recording the mode as a tag is deferred until a consumer compares JCStress runs across modes.

## Risks / Trade-offs

- **[Two paid instances per triggering event instead of one]** → Both are short fixtures on the
  default type, cents each. The workflow stays path-filtered and manually dispatchable (CLAUDE.md
  accepts this order of cost).
- **[A sanity-mode run finds nothing interesting]** → That is intended. The job tests BaaS's
  JCStress plumbing, not the fixture.
- **[JCStress needs at least two CPUs for two actors]** → The default instance type has them.
  Nothing pins the type in the job.
- **[Copied setup steps drift between the two jobs]** → Accepted. They are five short steps, and a
  drift shows up as one job failing.

## Migration Plan

Nothing to migrate. Rollback means deleting the job and the option. No stored data depends on
either.
