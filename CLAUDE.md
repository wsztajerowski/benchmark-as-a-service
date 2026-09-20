# CLAUDE.md — Benchmark as a Service (BaaS)

`AGENTS.md` is a symlink to this file — edit this one, both names stay in sync.

This file deliberately carries only what you **cannot** get by reading the code: invariants that
look arbitrary but aren't, facts about what *isn't* there, and decisions whose rationale lives
nowhere else. Standard Maven/AWS/picocli behaviour, directory-name-restates-purpose descriptions,
and anything `--help` or a template file will tell you are omitted on purpose. Don't add them back.

## How to read the prompts here

**Most prompts in this repository are dictated, not typed.** So the transcript is one lossy step
away from what was meant, and the errors cluster in exactly the words that matter most here:
identifiers, flags, file names, AWS service names, and anything CamelCase.

Treat a word that doesn't fit — a wrong homophone, a mangled class or option name, a sentence that
parses strangely, a stray "the" splitting a term — as a speech-to-text artefact first and a
deliberate instruction second. Reconstruct the term the code actually uses, and say in one line
which reading you took ("reading X as Y") rather than either asking about it or silently guessing.
Ask only when two readings would lead to materially different work.

The same applies to names you are asked to create: a dictated branch, change or file name arrives
without spelling or casing, so slugify it to match what is already in the tree and state what you
chose.

**Open questions get asked one at a time, the way brainstorming does.** When you have several
decisions to put to the user — an artifact's Open Questions, a set of unresolved options, a list of
things to confirm — do not present them as a batch to be answered in one reply. Ask the first,
wait for the answer, then ask the next. Dictating a reply that addresses four numbered questions
at once is exactly where answers get merged, misattributed or silently dropped. Analysis and
recommendations for all of them may be written out together; the *questions* are serialised.

## What this is

Runs JMH and JCStress benchmarks on throwaway EC2 instances. Measurements go to a DynamoDB table,
one item per measurement; process output, the verbatim result JSON and profiling artifacts go to S3.

| Module | Runs where |
|---|---|
| `baas-cli` | **Your laptop.** Provisions infrastructure, launches runners, polls for results. `pl.wsztajerowski.baas.BaasApp` |
| `benchmark-runner` | **The EC2 instance.** Executes benchmarks, uploads to S3, writes measurements to the results table. `pl.wsztajerowski.commands.TestWrapper` |
| `baas-model` | The stored measurement shape, the key encoding and the tag vocabulary — shared by the CLI and the runner so the two cannot drift. No MongoDB dependency, enforced by the build |
| `fake-jmh-benchmarks`, `fake-stress-tests` | Test fixtures |

One trigger path: `baas run`. CI does not have a second one — `e2e-cloud-test.yml` is a single
`ubuntu-latest` job that federates into `OperatorRole` and calls `baas run`, so a regression in the
CLI cannot pass CI. `benchmark-runner.yml`, `exec-single-benchmark.yml`, `start-ec2-runner.yml`,
`stop-ec2-runner.yml` and the `act` harness under `.github/test/` are deleted; the consumer
contract is *install the CLI*, not *call our reusable workflow*.

Sequence diagrams for the main CLI commands: [`docs/diagrams/`](docs/diagrams/) (Mermaid sources,
no checked-in SVGs — update the `.mmd` when a command changes). Design rationale and open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md). Per-change
records: `openspec/changes/*/design.md`, and `openspec/changes/archive/*/design.md` once archived.

**OpenSpec changes use the stock `spec-driven` schema**, driven by `/opsx:explore` → `/opsx:propose`
→ `/opsx:apply` → `/opsx:verify` → `/opsx:archive`. For those, explore replaces
`superpowers:brainstorming` and `tasks.md` replaces `writing-plans`: both skills write a second copy
under `docs/superpowers/`, the duplication the retired `superspec` schema existed to redirect
(`git log -- openspec/schemas/superspec`). `verify.md` is a convention, not an artifact — it holds
the requirement → code → test → gap table and the `W<n>` warning IDs later notes cite. Project rules
live in `openspec/config.yaml`, keyed by artifact ID only; any other key is dropped with a stderr
warning the agent never sees. Archived changes still pinned to `superspec` are never re-read, so
the deleted schema breaks nothing.

**Open review findings live in [`docs/review/`](docs/review/)** — one file per module, plus one per
reviewed change (`prebaked-runner-ami-review.md`), each entry marked Open or Fixed. An in-progress walkthrough works through them by severity; read the relevant
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
  `ResultsQueryService` reads the item's top-level `tags` map; tagging the *instance* leaves every stored
  result with a null `imageVersion` and the comparison silently never fires. The tag values are the
  ones observed on the box, so a result's tags cannot disagree with its own `environment.json`.
- **The benchmark runs from `/app`, never `/`.** The runner scans below its working directory for
  `.log` files to upload; cloud-init starts user-data in `/`, so that walk covers the whole root
  filesystem and aborts on `/proc` entries that vanish mid-walk.
- **The results table name *does* go into user-data, and that is deliberate.** It replaced an SSM
  fetch of the mongo connection string, which had to stay out of instance metadata because it
  carried credentials. A table name carries none — access comes from `RunnerRole`, not from knowing
  the name — so fetching it at boot would buy nothing and cost a round trip on every run. Don't
  "restore" the SSM indirection.
- **`baas run` forwards `project`, `branch`, `commit` and every `--tag` to the *runner*, not just to
  the instance.** They reach the item's top-level `tags` map, which is the only query surface `baas results`
  has. A caller `--tag` for a machine-observed key (`imageVersion`, `instanceType`, `jdk`,
  `cpuModel`, `cpuArch`, `type`) is rejected outright rather than dropped or allowed to win — the
  same rule that keeps a result's tags from disagreeing with its own `environment.json`.

**Three termination layers, all required.** Any one alone leaves a way to orphan a paid instance.
The watchdog is the only one that survives a deadlocked JVM.

1. Shell watchdog (`UserDataScriptBuilder`) — `sleep N && ec2:TerminateInstances`, fires
   `timeout + 300 s` after launch
2. Process `timeout` around `java -jar benchmark-runner.jar`
3. CLI JVM shutdown hook (`RunCommand`) for Ctrl+C

**The runner image (`infra/runner-image.yaml`, `baas admin build-image`)**

- **`baas run` has no fallback.** No AMI at `/<prefix>/runner/ami-id` → the runner-image lookup
  fails there, before any upload. Two provisioning paths would produce silently incomparable
  results.
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
- **The four Image Builder *cross-resource* references use `!GetAtt <X>.Arn`, never `!Ref`** —
  `ImageRecipe.Components[].ComponentArn`, and the pipeline's `ImageRecipeArn`,
  `InfrastructureConfigurationArn` and `DistributionConfigurationArn`. Each of those resources
  exposes a distinct `Arn` attribute — the shape where `Ref` is liable to return the name — and the
  properties, plus `StartImagePipelineExecution`, reject a name. This does **not** generalise to the
  block as a whole, where every other reference is correctly a `!Ref`: `InstanceProfileName` wants a
  *name* (`!Ref` on an `AWS::IAM::InstanceProfile` returns exactly that, and `!GetAtt …Arn` breaks
  it), `SubnetId`/`SecurityGroupIds` want ids, and the version/data/parent-image properties are
  template parameters.
- **No `AWS::ImageBuilder::Image` in the template.** That resource builds during stack operations,
  adding ~15 minutes to every `baas admin setup`.

**Other rules that exist because something broke**

- **One run is one prefix, one id and one instant.** Everything a run produces or consumes lives
  under `runs/<project>/<runId>/`, built only by `RunLayout`/`RunId` in `baas-model` — the same
  reason `ResultKeys` owns DynamoDB keys. A hand-built prefix does not fail to compile; it points at
  nothing, and that presents as an empty download rather than as an error. Splitting inputs and
  results back into two trees is the split this design removed.
- **`baas run` reads the clock once per run.** That instant names the prefix and travels to the
  runner as `--created-at`, so the id's timestamp and the stored `createdAt` are the same value
  rather than two that happen to be close. The instance's clock never reaches the record. CI mints
  its id in bash and must pass the same instant, or the property holds for `baas run` and quietly
  fails there.
- **The instance contacts no host outside the account.** Its only runner-JAR source is
  `releases/<version>/benchmark-runner.jar` in the bucket, seeded by the CLI. Restoring a
  network fetch reintroduces both the drift — two runs a week apart executing different runner
  code — and the egress `private-runner-network` exists to remove.
- **`releases/<version>/benchmark-runner.jar` is seeded once and never overwritten.** That
  immutability is what the whole pinning argument rests on. A corrupted object therefore does not
  self-repair: delete the key and the next run re-seeds it, checksum-verified.
- **Bucket versioning is `Suspended` and no lifecycle rule expires current objects under `runs/`.**
  Both are load-bearing. The deleted `expire-uploaded-benchmark-jars` rule assumed everything under
  `runs/` was re-creatable from source; results live there now, so restoring it is silent data loss
  (`CoreTemplateTest` pins its absence). Versioning only ever guarded an overwrite that the run
  id's 32-bit entropy suffix now prevents — and the consequence is stated, not implied: there is no
  server-side recovery from one.
- **`RunnerSecurityGroup` allows egress on 443 and 80 only — no 27017, and adding it back is a
  regression.** The rule existed because Atlas does not serve clients on 443. Nothing connects to
  Atlas now, and `CoreTemplateTest.runnerHasNoEgressToMongoAtlasAnyMore` pins its absence rather
  than merely not testing for it — a security group rule nobody can explain is one somebody
  restores.
- **Editing `RunnerSecurityGroup`'s `GroupDescription` replaces the security group.** It is an
  immutable property, so CloudFormation deletes and recreates the resource and the group *id
  changes*. Anything holding the old id is then pointing at a group that no longer exists —
  `~/.baas/config.yaml` most obviously, which `baas admin setup` rewrites, but not a run already in
  flight. Observed: removing the 27017 rule also touched the description, and the id moved. Change
  the rules without touching the description unless you intend the replacement.
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
- **The installer installs released artifacts only, and the repository copy refuses.**
  `scripts/install.sh` carries `BAAS_VERSION_DEFAULT`, rewritten at release time by `release.yml`'s
  `prepareCmd` and never committed back. A checkout copy holds the placeholder and exits naming
  `--version`, the same no-fallback stance `RunCommand` takes on an unreleased build. `--update`
  never installs anything itself: it resolves the newest release and re-executes *that release's*
  installer, so the script installing version X is always version X's own.
- **`commit` and `branch` are absent when unresolved, never `"unknown"`.** A placeholder value is
  indistinguishable from a real one at query time — the same non-answer wearing a value's clothing
  that produced `RESULT#unknown` (below). Tags are the entire query surface, so a fake value there
  is worse than a missing one.

## What isn't there, and what fails silently

- **`baas run` has no local mode.** It always provisions EC2. The only no-cost way to exercise the
  runner is `./jmh-with-profiler.sh` / `./jmh-with-async.sh` against LocalStack.
- **Measurements live in DynamoDB, and a verbatim `jmh-result.json` now exists in S3.** The item
  carries what a table view needs; `rawData` and `scorePercentiles` are dropped from it and are
  recoverable only from that JSON, via `baas download <runId>` (a literal result path also works,
  which is what keeps pre-unified-layout runs retrievable).
- **A reactor build cannot launch a run.** The CLI pins the runner JAR to its own released version,
  and `0.0.0-semantically-released` names no release — so `baas run` fails immediately, before
  resolving the project or the results table, unless `--runner-jar` is passed. Same no-fallback
  stance as the runner AMI. Every `baas` in
  existence is currently an alias onto a reactor build, so this is the case, not the exception;
  the reactor checkout is now the developer's explicit special case rather than the implicit
  default.
- **The runner refuses an unresolved project.** `getProject()` used to fall back to `"unknown"`,
  and CI has been writing `RESULT#unknown` because of it — a partition nobody queries. It now
  throws, before the benchmark runs. The historical `RESULT#unknown` rows stay where they are; 36
  of them are CI fixture runs against `fake-jmh-benchmarks` and nobody recorded what the rest
  measured.
- **Absent store configuration is a hard failure, not a silent no-op.** `baas run` resolves the
  table before the runner-image lookup and before any upload, and `benchmark-runner` rejects a
  missing selection outright. Discarding measurements takes an explicit `--no-database` on either. The old
  behaviour — unset URI selects a no-op store, run reports success, numbers vanish — is gone, and
  reintroducing any fallback brings it back.
- **`baas-cli` has no MongoDB path at all**; it neither ships the driver nor offers an option.
  `benchmark-runner` keeps one, selectable by `--mongo-connection-string`, purely so the JAR still
  works standalone against a user's own MongoDB. BaaS itself never selects it.
- **`ASYNC_PATH` gates async coverage.** `JmhWithAsyncProfilerSubcommandServiceIT` is annotated
  `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)`, so a plain `mvn verify` **silently
  skips** the only test exercising async-profiler end to end. Export it before trusting a green
  build on profiler changes. Same variable `jmh-with-async.sh` needs, since `--async-path`
  otherwise defaults to the on-instance path.
- **The GitHub Actions benchmark path is gone, not fixed.** It was broken six ways at once — a
  deleted SSM mongo parameter, a revoked IAM grant, no 27017 egress, an expired PAT, an
  unresolvable project, and an assertion (`tags.source == gha-e2e-test`) no job could ever satisfy
  because the runs were tagged `gha-e2e-test-async`. Deleting the bash orchestrator and calling
  `baas run` dissolved all six rather than repairing them. It was also forced:
  `private-runner-network` moves runners onto a subnet with no route to `github.com`, which a
  self-hosted Actions runner agent must reach. Any reference you find to
  `exec-single-benchmark.yml`, `MONGO_CONNECTION_STRING` in CI, `machulav/ec2-github-runner`,
  `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `SUBNET_ID`, `SECURITY_GROUP_ID` or `RESOURCE_NAME_PREFIX` is
  stale.
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
- **`e2e-cloud-test.yml` drives `baas run` end to end, and it is the only thing that does.** One
  `ubuntu-latest` job, `jmh-with-async` against `fake-jmh-benchmarks` on the real runner AMI, so a
  bad bake now fails CI rather than surviving it. It is path-filtered on `pull_request` plus
  `workflow_dispatch` because it provisions a paid instance per triggering event, and it tags
  itself `exclude_from_results=true` — which is why `queryByRequestId` carries no exclusion
  filter. What is still uncovered in-process: `RunCommand.call()`'s success path is executed by no
  JVM test (the JSON summary's shape is pinned against `printRunSummary`, and the wiring through
  `call()` only on a path that fails before AWS), and `jcstress` has no end-to-end coverage at all
  now that the old path is gone.
- **`docker-compose` has no init container.** Create the bucket and any SSM params by hand:
  `aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas`, and the results
  table if you want one. The local act E2E additionally needs `/baas/mongo/connection-string` as a
  SecureString, since the GHA path it exercises still writes to Mongo.
- **`scripts/install.sh` is the one script CI does invoke.** `release.yml`'s `prepareCmd` `sed`s the
  released version into it and publishes it as a release asset, but `install-test.yml` never installs
  that asset: it builds a fixture release in the job (`BAAS_BASE_URL: file://…/fixture`) and runs
  the working-tree installer against it, on `ubuntu-latest` and `macos-latest`. No CI job exercises
  a published installer or the release-time `sed` bake — those run only during a real release. The
  other utilities under `scripts/` still have no CI coverage.
- **`s3-hook-lambda` is gone** — module, CloudFormation resources, `<prefix>-lambda` bucket, and the
  S3-object-create trigger path. Any reference you find is stale.
- **The zsh orchestration helpers are gone** (`run-remote-benchmark.zsh`, `wait-for-gha-run.sh`,
  `benchmark_overview.sh`, `logger.sh`, `git_helpers.sh`, `aws_helpers.sh`). Use `baas run` /
  `baas results`, and don't reintroduce shell helpers for orchestration. The
  `.github/test/testing-scripts/` copies went with the `act` harness, so there is no live copy
  left anywhere.

## Gotchas that will waste your time

- **`--` is required before benchmark parameters**, and `baas` options must come before it.
  Without it picocli parses JMH flags as `baas` options: `Unknown options: '-f', '-wi', '-i'`.
  `baas run --instance-type c6i.4xlarge jmh -- MyBenchmark -f 1 -wi 1 -i 3`
- **`mvn -pl benchmark-runner verify` alone fails.** It needs the `fake-jmh-benchmarks` and
  `fake-stress-tests` shaded JARs already in the local repo (`classifier=shaded`). Run the full
  reactor first.
- **JUnit 6** (`6.0.2`) and **Testcontainers 2.x** — both differ from the versions you'd assume.
  Integration tests pin `mongo:7.0.5`; DynamoDB runs on LocalStack. One store contract suite runs
  against both adapters — a behavioural difference between them is a test failure, not a discovery.
- **JCStress writes `jcstress-results-*.bin.gz` to the module root**, not `target/`. `mvn clean`
  removes them via an extra fileset.
- **The mongo connection string must include a database name** (`mongodb://host:port/dbname`),
  enforced in `ResultsStoreBuilder` — which is runner-side only; the CLI no longer validates or
  even accepts one.
- **Morphia auto-maps everything under `pl.wsztajerowski.entities`** — new entity classes must live
  there.
- **A delta spec's `## REMOVED Requirements` heading must repeat the main spec's requirement name
  *verbatim*.** `openspec archive` matches on the name; a paraphrase warns
  ("not in the current spec; treating it as already removed") and then archives anyway, leaving the
  real requirement in `openspec/specs/` describing behaviour the code no longer has. Both of
  `dynamodb-results-store`'s REMOVED blocks missed this way, and the main spec kept documenting
  `--mongo-uri` after the option was deleted. Grep the main spec for the exact heading before
  writing the REMOVED block, and read the archive warnings rather than skimming them.
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
  so it must stay under CloudFormation's 4096-byte cap (guarded by a unit test). Three more —
  `GitHubOidcProviderArn`, `GitHubOrg`, `GitHubRepo` — declare the conditional federated principal
  on `OperatorRole`'s trust policy and create no resource. `GitHubRepo` is a `CommaDelimitedList`
  of **already-composed** `repo:<org>/<name>:*` subject patterns: `SetupCommand` builds them,
  because CloudFormation cannot iterate a list and every in-template trick for it leans on
  `Fn::Sub` re-scanning text substituted into it, which it does not do.
- **`runner-image.yaml`** — the measurement environment: image version, pinned parent AMI and tool
  versions, kernel tunables. Ships in the JAR as `/templates/runner-image.yaml` because both
  `setup` and `build-image` render it at runtime.
- **`cf-template-ci.yaml`** — the `GithubOidc` identity provider and **nothing else**. **Not
  deployed by the CLI** — deploy by hand, with an identity above the deployer, since
  `deployer-policy.json` scopes `iam:Get*`/`iam:List*` to roles and never to `oidc-provider/*`.
  Deploy order inverts: the provider is account-global (one per issuer URL per account), so it
  comes first and its ARN is handed to `baas admin setup`; an account that already has one must
  reuse that ARN rather than deploy this stack, which fails with `EntityAlreadyExists`.
  `WorkflowRole` is deleted — GitHub Actions federates straight into `OperatorRole`, because a
  role-chained session is capped at 60 minutes by STS whatever `MaxSessionDuration` says, against
  a 7200 s default benchmark timeout. That failure was not an error but a red job with a good
  measurement, an un-terminated instance and a full EC2 bill.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin setup`/`build-image`/`teardown`; `operator-policy.json` → the stack-created
`BaasCliOperatorRole`, narrow, for `baas run`/`results`/`env diff`. `operator-policy.json` and `cf-template-ci.yaml` reach the
**test** classpath only; the core template and the two deployer policy templates ship in the JAR
because the CLI renders them at runtime.

**The deployer policy is close to IAM's size ceiling.** It is attached as an inline policy on an
IAM group, capped at 5120 non-whitespace characters, *shared across every inline policy on that
group* — nothing else is currently attached to it, so the reserve below the cap is precautionary
rather than protecting a known consumer. A customer-managed policy gets 6144 to itself. The
rendered document sits at 4105 non-whitespace characters, and a
`renderedPolicyLeavesRoomInAnInlinePolicyBudget` test holds it under 4608. That is why whole verb classes are wildcarded (`ec2:Describe*`, `s3:Get*`,
`imagebuilder:Get*`, `dynamodb:Describe*`) rather than enumerated — naming every action
CloudFormation's bucket read handler needs is what pushed it over. `Create` is deliberately *not*
wildcarded: `imagebuilder:CreateImage` must stay excluded, and `s3:Put*` would grant `PutObject`,
which the deployer has no business holding.

**`deployer-policy.json` is a template, never a policy.** It carries `${ACCOUNT_ID}` / `${REGION}`
/ `${PREFIX}` placeholders and is rendered per caller by `DeployerPolicyRenderer` — every resource
it names is prefix-exact, so two developers cannot reach each other's stack, bucket or SSM
parameter. Attaching the file as-is grants nothing. `baas admin deployer-policy` prints the
rendered form; `--for-arn` renders it for someone else.

`baas admin setup`'s preflight (opportunistic `SimulatePrincipalPolicy`, plus translating any
`AccessDenied` into the rendered policy) is a **UX affordance, not a control** — anyone holding the
policy can call IAM directly. Don't try to make it one.

GHA values whose origin isn't obvious from the workflow files. All are plain `vars.`, no secrets:
a role ARN is not sensitive, and nothing else is left to hold. `WORKFLOW_ROLE_ARN`,
`RUNNER_ROLE_NAME`, `GHA_EC2_PAT`, `SUBNET_ID`, `SECURITY_GROUP_ID` and `RESOURCE_NAME_PREFIX` are
deleted along with the workflows that read them.

| Name | Source |
|---|---|
| `OPERATOR_ROLE_ARN` | Core stack output `OperatorRoleArn` — the role CI federates into directly |
| `CORE_STACK_NAME` | The installation `baas admin setup` printed (e.g. `baas-3q7i7s65`); `baas config sync` reads it |
| `AWS_REGION` | The installation's region |

## S3 result layout

One run is one prefix: `<result-path>` = `runs/<project>/<runId>/`, where `runId` is
`<UTC instant, ISO basic, milliseconds>Z-<8 hex>` (e.g. `20260820T174432812Z-a3f9c21b`) — fixed 28
characters, time-ordered so a listing reads chronologically, entropy-suffixed so two runs starting
in the same millisecond cannot collide. Nothing parses it; the format is a readability convention,
not a contract. Built only by `RunLayout`/`RunId` in `baas-model`, never by hand.

Per-type stdout lands in `jmh-output.txt`, `jmh-profiler-output.txt`, `jmh-with-async-output.txt`,
or `jcstress-output.txt`; profiling artifacts go under `<fully.qualified.BenchmarkName-Mode>/`. The
non-obvious entries:

| Key | Meaning |
|---|---|
| `run-status` | Sentinel written by user-data: `completed` or `failed:<exitCode>`. This is what the CLI polls. |
| `cloud-init-output.log` | Runner boot log, uploaded before self-termination — start here when a run fails before producing output |
| `environment.json` | The environment the run measured on: `schemaVersion`, image version + AMI, instance type, CPU model/topology, memory, OS + kernel, JVM and tool versions, kernel tunables. Written **before** the benchmark, so it survives a failed run. Read by `baas env diff`. |
| `jmh-result.json` | JMH's own machine-readable output, verbatim. The stored item drops `rawData` and `scorePercentiles` for the 400 KB cap, so this is the only place they survive; `resultJsonKey` on the item points here |
| `packages.txt` | `rpm -qa`, split out because several hundred lines would drown the manifest's ~20 fields |
| `logs/*.log` | Any `.log` found *below the working directory* (hence the `/app` invariant) |
| `input/` | The run's own inputs — `benchmark.jar`, and `runner.jar` only when `--runner-jar` overrode the pinned one. Inside the run prefix, so a consumer has one sub-prefix to skip rather than filenames to special-case |
| `releases/<version>/benchmark-runner.jar` | The version-pinned runner, outside the run tree. Seeded once by the CLI and never overwritten. `releases/`, not `runner/`, because a prefix one character from `runs/` would need disambiguating in every listing |
| `image-builds/` | Image Builder build logs (written by the build instance, not by a run) |

Two id shapes coexist and always will: runs recorded before the unified layout keep their original
`<type>-<timestamp>` request id, because that id is inside every sort key and *is* the GSI partition
key. Nothing parses the id, so nothing needs to tell them apart. `baas download` follows each item's
stored `resultPath` rather than reconstructing one, which is what keeps every historical path
resolving; it also accepts a bare run id, resolved through `requestId-index`.

`environment.json` is the **observation**; `infra/runner-image.yaml` is the **declaration**. The
observation is strictly richer — it carries what the image cannot control (instance type, CPU
model, resolved patch levels) and is the one that answers whether two results are comparable.
Never infer the environment of a past run from the declaration in the working tree.

## Results table

`baas-<prefix>-results`, DynamoDB, on-demand, `DeletionPolicy: Retain` — benchmark history outlives
any single stack, which is also why `baas admin setup` pre-checks for a retained table exactly as it
does for the retained bucket, and why teardown names both.

One item per measurement — per JMH benchmark method, per JCStress *run* (JCStress names only
non-passing tests, so per-test items would cover failures alone). No derived index items.

| | |
|---|---|
| `pk` | `RESULT#<project>` — `project` is the git repo name unless `--project` overrides it |
| `sk` | `<class>#<method>#<mode>#<timestamp>#<requestId>`, or `JCSTRESS#<timestamp>#<requestId>` |
| GSI `requestId-index` | `gsi1pk` = request ID; the one access path that is not a project sweep |

`mode` is in the sort key because a `-bm thrpt,avgt` run produces two results whose class and method
are identical; without it they differ only by a millisecond and one silently overwrites the other.
Timestamps are fixed-width UTC with exactly three fractional digits — `Instant.toString()` omits
trailing zeros, which makes keys of differing length that misorder as strings, and that surfaces as
missing rows rather than as an error.

**Keys are constructed only in `ResultKeys`, items only in `MeasurementItemMapper`**, both in
`baas-model`. Hand-encoding either elsewhere is how a query returns zero rows instead of failing to
compile. Non-finite scores are stored as absent: DynamoDB's `N` rejects `NaN`, and JMH reports one
for any single-iteration run.

## Result tagging

**Tags are the entire query surface.** There is no field-per-dimension: `baas results` filters,
groups and excludes on tags alone, so anything you want to slice by has to be one.

The vocabulary is defined once, in `baas-model`'s `TagKeys`:

| Group | Keys | Set by |
|---|---|---|
| Machine-observed | `imageVersion`, `instanceType`, `jdk`, `cpuModel`, `cpuArch` | The instance, from the same shell variables `environment.json` uses. A caller `--tag` for one of these is **rejected**, not overridden |
| Derived | `type`, `project`, `commit`, `branch`, `source` | `baas run`. `type` is reserved like the observed keys; `project`, `commit`, `branch` and `source` are caller-overridable by design. `source` is `ci` when the environment says so (`CI` or `GITHUB_ACTIONS` set and not `false`) and `local` otherwise — a `--tag source=nightly` is accepted, not rejected, because how a run was triggered is not something the instance observes |
| Convention | `options`, `exclude_from_results` | Free-form. `exclude_from_results=true` is filtered out server-side; it is a convention, not a field |

`branch` used to survive only as a segment of the result path and was stored nowhere. The unified
prefix drops that segment, so what the path stopped carrying the tags now carry — which is the
whole point of tags being the query surface.

Unknown keys pass through — `baas results` warns only when a `--tag` names a key no row carries.
Grouping keeps the highest score per `(benchmark, <group-tag>)`, group tag defaulting to `branch`,
and rows carrying no group tag are bucketed rather than dropped.

The retired `benchmark_overview.sh` also hard-coded `tags.project: 'lynx-journal'`. `baas results`
has no such filter, which explains row-count differences against historical output — and migrated
rows that carried no `project` tag at all are in `unknown-migrated`, deliberately not folded into
`lynx-journal`, since 36 of them are CI fixture runs against `fake-jmh-benchmarks`.

## Adding a benchmark type

A subcommand class in `commands/`, a service + builder in `services/`, an options record in
`services/options/`, and registration in `TestWrapper`'s `subcommands` list.

Storage is optional at runtime (no `--s3-bucket` → `LocalStorageService`); the results store is
not. Exactly one of `--results-table`, `--mongo-connection-string` or `--no-database` must be
named, and both-or-neither is an error. `AWS_ENDPOINT_URL_S3` / `--s3-service-endpoint` and
`AWS_ENDPOINT_URL_DYNAMODB` / `--dynamodb-endpoint` redirect to LocalStack.

## Accepted risks

Decisions already made and deliberately not revisited — don't file these as bugs.

| Area | Position |
|---|---|
| Deployer privilege | `iam:CreateRole` also writes the trust policy, so a deployer can recreate `<prefix>-operator-role` trusting itself with `Action:*` and assume it — the deployer policy is effectively account admin. Accepted: internal tool, development environments, deployer is a trusted developer. A permissions boundary was built and removed as not worth the bootstrap cost. Don't reintroduce one without a multi-principal account to justify it. |

| Relaxed kernel isolation on the runner | The image sets `perf_event_paranoid=1` and `kptr_restrict=0` so async-profiler can walk kernel stacks *and resolve kernel symbols* — without them the profiler is crippled. This weakens kernel isolation on a box that runs arbitrary benchmark JARs. Accepted: single-tenant, throwaway, terminated within `timeout + 300 s`. Recorded because these were previously AL2023 defaults that nobody chose; now they are a decision. |
| Re-measuring a historical environment | There is no command for it. A diff showing `jdk: 25.0.4 → 25.0.3` tells you the environment moved, but isolating whether it caused a score change means `git checkout <sha> -- infra/runner-image.yaml && baas admin build-image`, which clobbers the current image. Accepted: the question actually asked is "did it change", which `environment.json` answers directly. Git is the archive; nothing in S3 duplicates it. |
| Runner AMI snapshot cost | ~$0.20/month for the single retained 30 GB snapshot. The project previously had **zero** standing cost, so this is a real change in kind, not just degree. Bounded by the one-image-at-a-time rule: a build deregisters its predecessor and deletes that snapshot, so the figure does not grow with the number of builds. |
| ~~Runner JAR integrity~~ | **Closed, not dropped.** The risk was accepted while verification was impossible — the download happened on a throwaway instance mid-boot, with nothing to verify against. Moving the fetch to the laptop is what changed the trade-off: the CLI now verifies the asset against a `.sha256` published by the same release build, and a mismatch uploads nothing and launches nothing. |
| MongoDB | Retained in `benchmark-runner`, connect-only, and **no live user is known**. The standalone justification named java-wonderland, which sits on a branch frozen 2024-06-22 that cannot run today's runner at all: `--s3-result-prefix` is gone, no `--project` makes `getProject()` throw, and naming no store fails the exactly-one-of check. So this is no longer a settled trade — retirement is an open decision, deserving its own change and spec delta rather than a rider on someone else's. `baas` itself never provisions, selects or reaches it: no SSM parameter, no IAM grant, no egress rule. |
| `baas run` project layout | Assumes a pre-built JAR handed in by `--benchmark-jar`, which is required — `baas run` does not build. Anything that produces a JAR before invoking it is fine; the CLI has no opinion on how. |
| Distribution | Installable via `scripts/install.sh` as of this change. Homebrew tap, jpackage, native image and Docker image were specified but never built — backlog, not decisions. |
