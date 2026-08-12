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

Sequence diagrams for the main CLI commands: [`docs/diagrams/`](docs/diagrams/) (Mermaid sources,
no checked-in SVGs — update the `.mmd` when a command changes). Design rationale and open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md). Per-change
records: `openspec/changes/*/design.md`.

**Open review findings live in [`docs/review/`](docs/review/)** — one file per module, each entry
marked Open or Fixed. An in-progress walkthrough works through them by severity; read the relevant
file before proposing security or architecture work, and update the status table when one is
fixed. Items already in *Accepted risks* below are excluded from both files on purpose.

## Invariants — breaking these costs money or silently loses data

**User-data generation (`UserDataScriptBuilder`)**

- **No `set -e`.** If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before*
  starting the watchdog and orphans the instance. Errors are handled by exit code and the
  `run-status` sentinel instead. (The Image Builder component rendered by `RunnerImageRenderer`
  *does* use `set -euxo pipefail` — opposite context: a half-installed toolchain must abort the
  bake, and there is no paid instance to orphan.)
- **The watchdog starts immediately after `INSTANCE_ID` resolves.** Every later failure has to be
  covered by it.
- **User-data installs nothing.** No `yum`, no JDK, no async-profiler download. The toolchain is
  baked into the AMI by `baas admin build-image`; a runner that installed its own would measure on
  a slightly different machine every time, which is the drift this design exists to remove.
- **The environment manifest is written and uploaded *before* the benchmark starts.** A run that
  crashes still has to say what it crashed on — same reasoning as `cloud-init-output.log`.
- **Every manifest value is captured into a shell variable first.** The heredoc body is nothing but
  `${VAR}` references. Inlining command substitutions puts quotes, parens and awk programs inside a
  JSON string inside a heredoc — three levels of quoting, and a mistake in any of them yields a
  file that only fails weeks later in `baas env diff`. Values that can contain `"` or `\` go
  through `json_escape`.
- **`imageVersion`/`instanceType` reach the database via the runner's `--tag`, not EC2 tags.**
  `ResultsQueryService` reads `benchmarkMetadata.tags`; tagging the *instance* leaves every stored
  result with a null `imageVersion` and the comparison silently never fires. The tag values are the
  ones observed on the box, so a result's tags cannot disagree with its own `environment.json`.
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

**The runner image (`infra/runner-image.yaml`, `baas admin build-image`)**

- **`baas run` has no fallback.** No AMI at `/<prefix>/runner/ami-id` → it fails before the Maven
  build and before any upload. Two provisioning paths would produce silently incomparable results.
- **Exactly one image, rebuilt in place.** No slots, no AMI history, no second pointer. The archive
  is git: `git log -p infra/runner-image.yaml`, and `git checkout <sha> -- …` to reconstruct.
- **The pointer is repointed *before* the replaced AMI is deregistered.** Retiring first aims the
  pointer at a deleted AMI for the whole ~15-minute build, failing every run launched in that window.
- **`infra/runner-image.yaml` is the only place a tool version is declared**, ships in the JAR as
  `/templates/runner-image.yaml`, and any edit needs `imageVersion` bumped — Image Builder
  components are immutable at a version.
- **`baas admin setup` renders the same image parameters `build-image` does.** Both call
  `RunnerImageRenderer`. If setup let the template's placeholder component stand, it would register
  a no-op at the declared version and Image Builder would then refuse the real one at that same
  version — immutability, hit from a direction nobody would think to look.
- **The preflight must not query components with `byName`.** That collapses every version into one
  row with no version field and an ARN ending in a literal `x.x.x`, so the version filter matches
  nothing, the preflight concludes the version is free, and a doomed build proceeds. Cost a real
  9-minute build to find.
- **`perf`'s pinned version must match the parent AMI's kernel** — the RPM is built from that kernel
  build. They are pinned together and the component smoke-tests `perf`, so a mismatch fails the
  bake rather than a benchmark.
- **Image Builder authorises reads against the collection, writes against the named resource.**
  `GetComponent`/`List*` cannot be prefix-scoped (they evaluate against `component/*`); pipeline
  writes can. Hence the split `ImageBuilderRead` (`Resource: "*"`) / `ImageBuilder` statements in
  `deployer-policy.json`.
- **Creating the first pipeline in an account needs `iam:CreateServiceLinkedRole`** for
  `imagebuilder.amazonaws.com`. Appears in no SDK call and no resource schema; only a real deploy
  finds it.
- **The Image Builder wiring uses `!GetAtt <X>.Arn`, never `!Ref`.** Each of these resources exposes
  a distinct `Arn` attribute — the shape where `Ref` is liable to return the name — and the
  properties, plus `StartImagePipelineExecution`, reject a name.
- **No `AWS::ImageBuilder::Image` in the template.** That resource builds during stack operations,
  adding ~15 minutes to every `baas admin setup`.

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
  `ResultsCommand.printJson`/`printCsv`, `ResultsQueryService.printTable`, `ImageCommand`,
  `EnvDiffSubcommand`'s table, the picocli usage renderers, and `TeardownCommand`'s confirmation
  prompt are deliberately not migrated — a timestamp prefix on every line breaks
  `--format json | jq`, `--format csv > file`, and the same-line prompt.
- **`printJson`/`printCsv` must format with `Locale.ROOT`.** Under a comma-decimal locale (pl-PL
  among many) a bare `%.6f` emits `8234574,731914`, which is not a JSON number and splits a CSV
  column in two — silently, and only on some machines. Non-finite values become JSON `null`, since
  JSON has no `NaN` literal and JMH reports one for any single-iteration run.
- **No automated test drives `baas run` end to end.** `e2e-cloud-test.yml` covers `jmh-with-async`
  against the fake benchmarks on real EC2, but through `workflow_dispatch` — the GHA path, which
  installs its own async-profiler and never boots the runner AMI. So CI cannot catch a bad bake,
  and `RunCommand.call()` is executed by no test at all. Verification of the baked image is manual
  (`openspec/changes/prebaked-runner-ami/tasks.md` §11).
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
  instance profile, `OperatorRole`, and the EC2 Image Builder resources (`Component`,
  `ImageRecipe`, `InfrastructureConfiguration`, `DistributionConfiguration`, `ImagePipeline`) plus
  the build-instance role. Deployed by `baas admin setup`, bundled into the CLI as the
  classpath resource `/templates/cf-template-core.yaml`. `UseExistingVpc` + `ExistingVpcId` /
  `ExistingSubnetId` / `ExistingSecurityGroupId` reuse existing networking. Three parameters —
  `RunnerImageVersion`, `RunnerParentAmiId`, `RunnerImageComponentData` — are rendered from
  `infra/runner-image.yaml` by `RunnerImageRenderer`; the component travels as a parameter value,
  so it must stay under CloudFormation's 4096-byte cap (guarded by a unit test).
- **`runner-image.yaml`** — the measurement environment: image version, pinned parent AMI and tool
  versions, kernel tunables. Ships in the JAR as `/templates/runner-image.yaml` because both
  `setup` and `build-image` render it at runtime.
- **`cf-template-ci.yaml`** — `GithubOidc` (conditional) + `WorkflowRole`, GHA only. **Not deployed
  by the CLI** — deploy by hand. Split out so the local CLI's identity never needs
  `iam:CreateOIDCProvider`.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin setup`/`build-image`/`teardown`; `operator-policy.json` → the stack-created
`BaasCliOperatorRole`, narrow, for `baas run`/`results`/`env diff`. `operator-policy.json` and `cf-template-ci.yaml` reach the
**test** classpath only; the core template and the two deployer policy templates ship in the JAR
because the CLI renders them at runtime.

**The deployer policy is close to IAM's size ceiling.** An inline policy on a user or group is
capped at 5120 non-whitespace characters, *shared across every inline policy on that principal*; a
customer-managed policy gets 6144 to itself. The rendered document sits around 3.9 KB, and a
`renderedPolicyLeavesRoomInAnInlinePolicyBudget` test holds it under 4096. That is why whole verb
classes are wildcarded (`ec2:Describe*`, `s3:Get*`, `imagebuilder:Get*`) rather than enumerated —
naming every action CloudFormation's bucket read handler needs is what pushed it over. `Create` is
deliberately *not* wildcarded: `imagebuilder:CreateImage` must stay excluded, and `s3:Put*` would
grant `PutObject`, which the deployer has no business holding.

**`deployer-policy.json` is a template, never a policy.** It carries `${ACCOUNT_ID}` / `${REGION}`
/ `${PREFIX}` placeholders and is rendered per caller by `DeployerPolicyRenderer` — every resource
it names is prefix-exact, so two developers cannot reach each other's stack, bucket or SSM
parameter. Attaching the file as-is grants nothing. `baas admin deployer-policy` prints the
rendered form; `--for-arn` renders it for someone else.

`baas admin setup`'s preflight (opportunistic `SimulatePrincipalPolicy`, plus translating any
`AccessDenied` into the rendered policy) is a **UX affordance, not a control** — anyone holding the
policy can call IAM directly. Don't try to make it one.

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
| `environment.json` | The environment the run measured on: `schemaVersion`, image version + AMI, instance type, CPU model/topology, memory, OS + kernel, JVM and tool versions, kernel tunables. Written **before** the benchmark, so it survives a failed run. Read by `baas env diff`. |
| `packages.txt` | `rpm -qa`, split out because several hundred lines would drown the manifest's ~20 fields |
| `logs/*.log` | Any `.log` found *below the working directory* (hence the `/app` invariant) |
| `runs/<requestId>/` | Separate top-level prefix holding uploaded inputs, not results |
| `image-builds/` | Image Builder build logs (written by the build instance, not by a run) |

`environment.json` is the **observation**; `infra/runner-image.yaml` is the **declaration**. The
observation is strictly richer — it carries what the image cannot control (instance type, CPU
model, resolved patch levels) and is the one that answers whether two results are comparable.
Never infer the environment of a past run from the declaration in the working tree.

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
| Deployer privilege | `iam:CreateRole` also writes the trust policy, so a deployer can recreate `<prefix>-operator-role` trusting itself with `Action:*` and assume it — the deployer policy is effectively account admin. Accepted: internal tool, development environments, deployer is a trusted developer. A permissions boundary was built and removed as not worth the bootstrap cost. Don't reintroduce one without a multi-principal account to justify it. |
| Atlas IP allowlist | Runners get a fresh public IP per run, so there is no stable address to pin. The access list is `0.0.0.0/0`, gated by connection-string credentials. A private subnet + NAT + PrivateLink needs a paid tier and ~$32/month standing cost. |
| Relaxed kernel isolation on the runner | The image sets `perf_event_paranoid=1` and `kptr_restrict=0` so async-profiler can walk kernel stacks *and resolve kernel symbols* — without them the profiler is crippled. This weakens kernel isolation on a box that runs arbitrary benchmark JARs. Accepted: single-tenant, throwaway, terminated within `timeout + 300 s`. Recorded because these were previously AL2023 defaults that nobody chose; now they are a decision. |
| Re-measuring a historical environment | There is no command for it. A diff showing `jdk: 25.0.4 → 25.0.3` tells you the environment moved, but isolating whether it caused a score change means `git checkout <sha> -- infra/runner-image.yaml && baas admin build-image`, which clobbers the current image. Accepted: the question actually asked is "did it change", which `environment.json` answers directly. Git is the archive; nothing in S3 duplicates it. |
| Runner AMI snapshot cost | ~$0.20/month for the single retained 30 GB snapshot. The project previously had **zero** standing cost, so this is a real change in kind, not just degree. Bounded by the one-image-at-a-time rule: a build deregisters its predecessor and deletes that snapshot, so the figure does not grow with the number of builds. |
| Runner JAR integrity | Downloaded from GitHub Releases **without checksum verification**. Known open risk. |
| Shared `RunnerRole` | One SSM path for the mongo URI, so any operator's runner can read it. Fine single-tenant. |
| MongoDB | Connect-only. `baas` never provisions a cluster. |
| `baas run` project layout | Assumes a Maven project producing one JAR; `--benchmark-jar` + `--skip-build` covers the rest. |
| Distribution | Shaded JAR only. Install script, Homebrew tap, jpackage, native image, Docker image were specified but never built — backlog, not decisions. |
