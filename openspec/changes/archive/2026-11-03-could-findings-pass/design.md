# Design

## Context

See proposal.md for why. Four findings, one module (`baas-cli`), no infrastructure change. The parts
that constrain the approach:

- `JobLaunch.run` orders the launch as: image → JDK → networking → name → upload (benchmark JAR, then
  runner JAR or the pinned release) → user-data → reserve → arm the hook → launch. Its class comment
  and `.claude/rules/user-data.md` hold the invariants: every precondition before any upload, the
  reservation before the launch, and the hook armed before `RunInstances`.
- `RunCommand.execute` checks everything local first: format, type, managed and no-run arguments.
  Then it reads the deployment (`context.operator()`), the project, the table, the timings, the tags
  and that the JAR exists. A no-run argument such as `-l` is already refused with the local
  `java -jar` command as its hint.
- JMH and JCStress list without measuring under `-l`, after parsing every option (checked on the
  fixture JARs, 2026-11-03):

  | Arguments + `-l` | JMH | JCStress |
  |---|---|---|
  | valid | `Benchmarks:` then one name per line, exit 0 | banner, CPU probe, one name per line, exit 0 |
  | mistyped option | `Error parsing command line: …`, exit 1 | a jopt-simple stack trace, exit 1 |
  | pattern that matches nothing | `Benchmarks:` and no name, **exit 0** | banner and no name, **exit 0** (`-t NoSuchTest`) |

  An empty match is therefore found only by reading the output.
- `GitHubStep.deployed(parameters)` already reads the GitHub trust back from the stack's parameters,
  and `ImageBuilderService.currentImage` already resolves the pointer to an image. Both run under
  the deployer credentials setup and `admin image show` use.
- `ConfigService.defaultRoot()` reads `user.home`. Surefire points `user.home` at
  `target/test-home`, but an IDE run doesn't. `testsupport.NoRealAwsProfile`, a
  `LauncherSessionListener` registered in `META-INF/services`, solved the same problem for
  `~/.aws`.

## Goals / Non-Goals

**Goals:**
- A failure between the reservation and the launch leaves a job item, never an anonymous `input/`.
- A user can find an argument mistake for free, with the same reading of the arguments the job
  would get.
- One read-only command answers "what is deployed" without the AWS CLI.
- No test run can read or write the developer's `~/.baas`.

**Non-Goals:**
- Running `--dry-run` implicitly before every `baas run`. It costs a JVM start and, for JCStress, a
  CPU probe, and it can fail where the job would succeed (see Risks).
- Validating the deployment in a dry run: the image, `--jdk` against the image, the table, the
  project. Those need AWS, and a real run already checks them before it uploads anything.
- `--add-github-repo` and `--remove-github-repo` for unattended setup, and drift against the
  bundled image base. `admin image show` already reports the drift.
- Checking that the OIDC provider exists. `admin deployment show` reports what the stack declares,
  which is the trust setup manages.

## Decisions

### The reservation moves to just after naming the job, and the pinned runner JAR stays a precondition

The new order is: image → JDK → networking → **pinned runner JAR** → name → **reserve → arm the
hook** → upload the benchmark JAR (and a `--runner-jar` override) → user-data → launch.

`aws.pinnedRunnerJar` seeds and verifies `releases/<version>/benchmark-runner.jar`. It writes
nothing under the job's prefix, and a checksum mismatch is a refusal ("uploads nothing and launches
nothing"). So it stays before the reservation, and "a refused precondition creates no job item"
still holds. Only the job's own inputs move after the reservation.

*Rejected: reserve last but delete `input/` on a failed reservation.* Operators hold no
`s3:DeleteObject` under `jobs/*` (`narrow-bucket-grants`). The cleanup would also be one more write
that can fail the same way the reservation did.

### A failed upload is recorded as `launch-failed`, through the path a failed instance request uses

`recordLaunchFailure` takes the step that failed (`upload` or `launch`) and writes it into
`launch-error.txt` and the log line. The job item's status and error code are written as for a
failed `RunInstances`. Reusing `launch-failed` keeps the status vocabulary as it is: no new
terminal status, and so no change to `JobStatus.EXACT_TERMINAL`, to CI's copied poll loop
(ADR 0005, G5) or to `jobs list`.

*Rejected: leave the item `launching`, so it lists as `vanished` after the grace period.* That says
"the instance disappeared" about a job that never had one.

### The shutdown hook is armed with the reservation, so Ctrl+C during the upload cancels the job

`Listener.reserved` now fires before the upload. A Ctrl+C there goes through `JobStop`: it records
`cancelled`, and the instance lookup finds nothing to terminate. Without it, an interrupted upload
would leave a `launching` item that reads `vanished`. The listener's Javadoc changes from "the
instance is about to be requested" to "from here an interrupt must find and stop the job".

### `--dry-run` runs the benchmark JAR's own `-l` on the CLI's JVM, and counts the names it prints

The command is `<java.home>/bin/java -jar <benchmark-jar> <args…> -l`, with `java.home` taken from
the JVM running the CLI. It is started with `ProcessBuilder`, its output merged and captured, and it
is killed after 120 s.

A listed name is a line that matches a dotted Java identifier
(`[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+`). Every header line both tools print contains a space or a
colon (`Benchmarks: `, `Java Concurrency Stress Tests`, `Rev: …`, `Burning up …`), and so do the
JVM's warnings. An IT pins that reading against both fixture JARs, as `BenchmarkArgsContractIT`
pins the option lists, so a JMH or JCStress bump that changes the format fails there.

*Rejected: parse the arguments with JMH's and JCStress's classes inside the CLI.* That needs both
libraries on the CLI's classpath, at versions that may differ from the ones in the user's JAR. The
user's JAR is the one place the right version is already present.

*Rejected: accept any exit 0 as success.* A pattern that matches nothing exits 0 in both tools, and
it is the most likely typo.

### A dry run stops before anything reads the deployment

`--dry-run` branches in `RunCommand.execute` right after `refuseManagedOrNoRun()` and checks the
JAR's path itself. A real run keeps that check where it was, after the tag and timeout refusals that
`OptionValidationTest` pins. It returns before `context.operator()`, so it needs no
configured deployment and no credentials. It skips the unreleased-build runner-JAR refusal, since
nothing will be launched. Task 1.2 confirms that nothing earlier on the path (the version check,
deployment selection) touches AWS.

The run's other options are accepted and ignored, except `--format json`. That one is refused: the
JSON summary describes a job, and a dry run has none. A fourth `status` value would change a
contract CI already reads.

`-lprof` alone is a listing job (`list-runner-profilers`). JMH given `-lprof -l` lists benchmarks,
which says nothing about profilers, so a dry run of it runs nothing and says so.

*Rejected: a separate `baas jobs check` command.* The check belongs to the request it checks.
`--dry-run` on the same command line means the user deletes one flag to launch.

### `admin deployment show` is a thin read command with no port

It joins `setup`, `teardown`, `export` and `import` under `admin deployment` and uses
`DeployerSession.factory(config)`, as `admin image show` does. ADR 0009 adds a port when a sequence
needs a test. This command has no sequence: three reads, then a rendering. So the rendering is a
static function of a small record (`Report`), tested without AWS, and the reads stay inline.

What it reads:
- `DescribeStacks` for status, last update (`LastUpdatedTime`, else `CreationTime`), parameters
  and outputs;
- the `BaasCliVersion` parameter for the CLI version;
- `GitHubStep.deployed` for the trust, made reachable from `deployment` (it is package-private in
  `deployment.setup`);
- `ImageBuilderService.currentImage` for the image.

All are already in the deployer policy (task 1.1 confirms), so no IAM change. A missing stack is a
refusal (exit 1), worded as `admin image show` words it.

Text output is aligned `label  value` lines, like `config show`. JSON is one object built with
`console.Json`, like every other `--format json`.

*Rejected: an operator-credential `baas deployment show`.* The operator role can describe its own
stack, but the GitHub trust and the image are what an admin changes, through `setup` and `admin image
build`. The command tree keeps the deployment noun under `admin`, and a second, operator-level noun
would be a new top-level shape.

### `~/.baas` is redirected by a `baas.home` system property, set by a test-session listener

`ConfigService.defaultRoot()` returns `Path.of(System.getProperty("baas.home"))` when that property
is set, and `~/.baas` otherwise. A new `testsupport.NoRealBaasHome` listener, registered beside
`NoRealAwsProfile`, sets it to a fresh temporary directory for every test session. Surefire's
`user.home` setting stays, since other code may read `user.home`; its comment names the listener as
what covers IDE runs.

*Rejected: let the listener set `user.home` itself.* The listener runs in the failsafe JVM too, and
ITs need the real `user.home` for `~/.testcontainers.properties`.

The property is undocumented. It is a test seam, not a user setting.

## Risks / Trade-offs

- **[Risk] The CLI's JVM is older than the benchmark JAR's bytecode**, so the dry run fails with
  `UnsupportedClassVersionError` where the job would run. → The failure prints the tool's output
  verbatim, and the message names the Java version it ran on. No special case.
- **[Risk] JCStress ignores a positional argument**, so `jcstress -- NoSuchTest` lists every test.
  → Accepted. The dry run reports what JCStress itself would do, which is what the job would do too.
- **[Risk] A future JMH or JCStress changes the listing format.** → The fixture IT fails on the
  bump, as `BenchmarkArgsContractIT` does for option lists.
- **[Trade-off] A failed upload now leaves a `launch-failed` job item** where it used to leave no
  item. → That is the point. The item names the prefix, so `jobs list` shows it and `jobs download`
  reaches what was uploaded.
- **[Trade-off] The reservation now precedes a possibly long upload**, so a `launching` item can
  sit there for the upload's duration. → `JobListing` treats a `launching` item younger than the
  grace period as in flight. Task 1.3 checks the grace period against a large JAR's upload time.

## Migration Plan

None. No stored data, IAM or infrastructure changes. A CLI from before this change behaves as
before against the same deployment.
