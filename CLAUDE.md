# CLAUDE.md — Benchmark as a Service (BaaS)

`AGENTS.md` is a symlink to this file — edit this one, both names stay in sync.

This file deliberately carries only what you **cannot** get by reading the code: invariants that
look arbitrary but aren't, facts about what *isn't* there, and decisions whose rationale lives
nowhere else. Standard Maven/AWS/picocli behaviour, directory-name-restates-purpose descriptions,
and anything `--help` or a template file will tell you are omitted on purpose. Don't add them back.

## What this is

Runs JMH and JCStress benchmarks on throwaway EC2 instances. Measurements go to MongoDB; process
output and profiling artifacts go to S3.

| Module | Runs where |
|---|---|
| `baas-cli` | **Your laptop.** Provisions infrastructure, launches runners, polls for results. `pl.wsztajerowski.baas.BaasApp` |
| `benchmark-runner` | **The EC2 instance.** Executes benchmarks, uploads to S3, writes to MongoDB. `pl.wsztajerowski.commands.TestWrapper` |
| `fake-jmh-benchmarks`, `fake-stress-tests` | Test fixtures |

Two trigger paths: `baas run` (supported, no GitHub Actions anywhere in it) and
`benchmark-runner.yml` via `workflow_dispatch` (CI only — `e2e-cloud-test.yml` uses it). `baas`
neither dispatches nor depends on the workflows.

Sequence diagrams for the three CLI commands: [`docs/diagrams/`](docs/diagrams/) (Mermaid sources,
no checked-in SVGs — update the `.mmd` when a command changes). Design rationale and open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md). Per-change
records: `openspec/changes/*/design.md`.

## Invariants — breaking these costs money or silently loses data

**User-data generation (`UserDataScriptBuilder`)**

- **No `set -e`.** If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before*
  starting the watchdog and orphans the instance. Errors are handled by exit code and the
  `run-status` sentinel instead.
- **The watchdog starts immediately after `INSTANCE_ID` resolves.** Every later failure has to be
  covered by it.
- **The benchmark runs from `/app`, never `/`.** The runner scans below its working directory for
  `.log` files to upload; cloud-init starts user-data in `/`, so that walk covers the whole root
  filesystem and aborts on `/proc` entries that vanish mid-walk.
- **The mongo URI never goes into user-data.** Fetched from SSM at runtime so it stays out of
  instance metadata and CI logs.

**Three termination layers, all required.** Any one alone leaves a way to orphan a paid instance.
The watchdog is the only one that survives a deadlocked JVM.

1. Shell watchdog (`UserDataScriptBuilder`) — `sleep N && ec2:TerminateInstances`, fires
   `timeout + 300 s` after launch
2. Process `timeout` around `java -jar benchmark-runner.jar`
3. CLI JVM shutdown hook (`RunCommand`) for Ctrl+C

**Other rules that exist because something broke**

- **S3 upload paths are request-ID-scoped** (`runs/<requestId>/…`). Without it, two developers on
  the same branch overwrite each other's JARs mid-run.
- **`RunnerSecurityGroup` needs egress on TCP 27017.** Atlas is *not* reachable over 443; omitting
  27017 makes every run fail at the database write.
- **EC2 tags use the key `baas-role`, not `baas:role`.** `RunnerRole`'s `ec2:TerminateInstances`
  condition is scoped to it, so changing the key breaks self-termination.
- **Root volume is 30 GB gp3, not the AL2023 default.** 8 GB is exhausted by profiling artifacts.
- **`aws.operatorProfile` must not fall back to `aws.profile`.** That field holds deployer
  credentials; the fallback would silently hand day-to-day commands elevated rights. Don't
  "helpfully" add it.
- **Stack and bucket names are derived from caller identity.** `sts:GetCallerIdentity` →
  `prefix = lowercase(base32(sha256(arn)))[0:8]` → both are `baas-<prefix>`. Not user-selectable.
  The bucket is `DeletionPolicy: Retain`, so a teardown that keeps it blocks the next setup with a
  CloudFormation error that never mentions S3 — `SetupCommand` pre-checks for that case explicitly.

## What isn't there, and what fails silently

- **`baas run` has no local mode.** It always provisions EC2. The only no-cost way to exercise the
  runner is `./jmh-with-profiler.sh` / `./jmh-with-async.sh` against LocalStack.
- **Measurements live only in MongoDB.** There is no `result.json`. An empty or unset mongo URI
  selects `NoOpDatabaseService`, so the run reports success and the numbers are discarded.
- **`ASYNC_PATH` gates async coverage.** `JmhWithAsyncProfilerSubcommandServiceIT` is annotated
  `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)`, so a plain `mvn verify` **silently
  skips** the only test exercising async-profiler end to end. Export it before trusting a green
  build on profiler changes. Same variable `jmh-with-async.sh` needs, since `--async-path`
  otherwise defaults to the on-instance path.
- **`MONGO_CONNECTION_STRING` is not a GHA secret.** `exec-single-benchmark.yml` reads it from SSM
  at `/<RESOURCE_NAME_PREFIX>/mongo/connection-string` and exits 1 if absent or empty.
- **`baas -v` needs the argv pre-scan, not just the execution-strategy hook.**
  `LoggingMixin.applyEarlyVerbosity` in `BaasApp.main` looks redundant next to the
  `TestWrapper`-style hook, but SimpleLogger pins a logger's level when the logger is constructed,
  and every `baas` command's `static final Logger` is built while picocli instantiates the
  subcommand tree — before `execute()`. Delete the pre-scan and `-v` **silently stops** raising
  command-level logging. `benchmark-runner` is unaffected only because its loggers live in
  services, constructed later.
- **Diagnostics go to the logger (stderr); command payloads stay on `System.out`.**
  `ResultsCommand.printJson`/`printCsv` and `ResultsQueryService.printTable`, the picocli usage
  renderers, and `TeardownCommand`'s confirmation prompt are deliberately not migrated — a
  timestamp prefix on every line breaks `--format json | jq`, `--format csv > file`, and the
  same-line prompt.
- **`docker-compose` has no init container.** Create the bucket and any SSM params by hand:
  `aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas`. The local act
  E2E additionally needs `/baas/mongo/connection-string` as a SecureString.
- **Nothing in CI invokes `scripts/`.** `release.yml` builds its semantic-release config inline and
  shells out only to `mvn`, so CI does not protect those three utilities.
- **`s3-hook-lambda` is gone** — module, CloudFormation resources, `<prefix>-lambda` bucket, and the
  S3-object-create trigger path. Any reference you find is stale.
- **The zsh orchestration helpers are gone** (`run-remote-benchmark.zsh`, `wait-for-gha-run.sh`,
  `benchmark_overview.sh`, `logger.sh`, `git_helpers.sh`, `aws_helpers.sh`). Use `baas run` /
  `baas results`, and don't reintroduce shell helpers for orchestration.
  `.github/test/testing-scripts/logger.sh` is a **separate, still-live copy**.

## Gotchas that will waste your time

- **`--` is required before benchmark parameters**, and `baas` options must come before it.
  Without it picocli parses JMH flags as `baas` options: `Unknown options: '-f', '-wi', '-i'`.
  `baas run --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1 -wi 1 -i 3`
- **`baas run` builds in the current working directory** — the user's benchmark project, not this
  repo.
- **`mvn -pl benchmark-runner verify` alone fails.** It needs the `fake-jmh-benchmarks` and
  `fake-stress-tests` shaded JARs already in the local repo (`classifier=shaded`). Run the full
  reactor first.
- **JUnit 6** (`6.0.2`) and **Testcontainers 2.x** — both differ from the versions you'd assume.
  Integration tests pin `mongo:7.0.5`.
- **JCStress writes `jcstress-results-*.bin.gz` to the module root**, not `target/`. `mvn clean`
  removes them via an extra fileset.
- **The mongo connection string must include a database name** (`mongodb://host:port/dbname`),
  enforced in `DatabaseServiceBuilder`.
- **Morphia auto-maps everything under `pl.wsztajerowski.entities`** — new entity classes must live
  there.
- **`pom.xml` version stays `0.0.0-semantically-released`.** Never bump it by hand; `release.yml`
  sets the real version at release time. Shaded artifacts are named `${project.artifactId}` with no
  version suffix, so `target/baas-cli.jar` and `target/benchmark-runner.jar` are stable paths.

## Infrastructure

Two independently-deployed CloudFormation stacks. `infra/README.md` is current — follow it. There
is no `cf-template-main.yaml` and no bootstrap stack.

- **`cf-template-core.yaml`** — networking, the `baas-<prefix>` bucket, `RunnerRole` +
  instance profile, `OperatorRole`. Deployed by `baas admin setup`, bundled into the CLI as the
  classpath resource `/templates/cf-template-core.yaml`. `UseExistingVpc` + `ExistingVpcId` /
  `ExistingSubnetId` / `ExistingSecurityGroupId` reuse existing networking.
- **`cf-template-ci.yaml`** — `GithubOidc` (conditional) + `WorkflowRole`, GHA only. **Not deployed
  by the CLI** — deploy by hand. Split out so the local CLI's identity never needs
  `iam:CreateOIDCProvider`.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin setup`/`teardown`; `operator-policy.json` → the stack-created `BaasCliOperatorRole`,
narrow, for `baas run`/`results`. Both JSONs reach the **test** classpath only
(`baas-cli/pom.xml` `<testResources>`); only the core template ships in the JAR.

GHA values whose origin isn't obvious from the workflow files:

| Name | Source |
|---|---|
| `WORKFLOW_ROLE_ARN` | CI stack output `WorkflowRoleArn` |
| `RUNNER_ROLE_NAME` | Core stack output — role *name*, not ARN |
| `GHA_EC2_PAT` | GitHub classic token, `repo` scope, for `machulav/ec2-github-runner` |
| `RESOURCE_NAME_PREFIX` | SSM/S3 prefix; defaults to `baas` if unset |

## S3 result layout

Under `<result-path>` = `<branch>/<type>/<YYYYMMDD_HHMMSS>`. Per-type stdout lands in
`jmh-output.txt`, `jmh-profiler-output.txt`, `jmh-with-async-output.txt`, or `jcstress-output.txt`;
profiling artifacts go under `<fully.qualified.BenchmarkName-Mode>/`. The non-obvious entries:

| Key | Meaning |
|---|---|
| `run-status` | Sentinel written by user-data: `completed` or `failed:<exitCode>`. This is what the CLI polls. |
| `cloud-init-output.log` | Runner boot log, uploaded before self-termination — start here when a run fails before producing output |
| `logs/*.log` | Any `.log` found *below the working directory* (hence the `/app` invariant) |
| `runs/<requestId>/` | Separate top-level prefix holding uploaded inputs, not results |

## Result tagging

Runs are tagged `branch`, `type`, `project`, `options=<params>`; non-standard hardware gets
`exclude_from_results=true`. `baas results` filters that out, groups by `(benchmark, branch)`, and
keeps the highest-scoring run per group. Tags are a free-form `Map<String,String>` on
`BenchmarkMetadata` — `exclude_from_results` is a convention, not a field.

The retired `benchmark_overview.sh` also hard-coded `tags.project: 'lynx-journal'`. `baas results`
has no such filter, which explains row-count differences against historical output.

## Adding a benchmark type

A subcommand class in `commands/`, a service + builder in `services/`, an options record in
`services/options/`, and registration in `TestWrapper`'s `subcommands` list.

Storage and database are both optional at runtime: no `--s3-bucket` → `LocalStorageService`; no
mongo URI → `NoOpDatabaseService`; `AWS_ENDPOINT_URL_S3` or `--s3-service-endpoint` redirects S3 to
LocalStack.

## Accepted risks

Decisions already made and deliberately not revisited — don't file these as bugs.

| Area | Position |
|---|---|
| Atlas IP allowlist | Runners get a fresh public IP per run, so there is no stable address to pin. The access list is `0.0.0.0/0`, gated by connection-string credentials. A private subnet + NAT + PrivateLink needs a paid tier and ~$32/month standing cost. |
| Runner JAR integrity | Downloaded from GitHub Releases **without checksum verification**. Known open risk. |
| Shared `RunnerRole` | One SSM path for the mongo URI, so any operator's runner can read it. Fine single-tenant. |
| MongoDB | Connect-only. `baas` never provisions a cluster. |
| `baas run` project layout | Assumes a Maven project producing one JAR; `--benchmark-jar` + `--skip-build` covers the rest. |
| Distribution | Shaded JAR only. Install script, Homebrew tap, jpackage, native image, Docker image were specified but never built — backlog, not decisions. |
