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
one item per measurement, and each job's status to one job item in the same table; process output,
the verbatim result JSON and profiling artifacts go to S3.

| Module | Runs where |
|---|---|
| `baas-cli` | **Your laptop.** Provisions infrastructure, launches runners, polls for results. `pl.wsztajerowski.baas.BaasApp` |
| `benchmark-runner` | **The EC2 instance.** Executes benchmarks, uploads to S3, writes measurements to the results table. `pl.wsztajerowski.commands.TestWrapper` |
| `baas-model` | The one contract the CLI, the runner and the generated user-data read: the stored measurement shape, the key encoding, the tag vocabulary, the artifact names inside a job's prefix (`JobLayout`), the benchmark types (`BenchmarkType`) and the runner's option names (`RunnerArgs`). `model` imports no AWS SDK (`ModelBoundaryTest`); `model.dynamodb` holds the two item mappers and the job item's guard (`JobItemConditions`). No MongoDB dependency, enforced by the build |
| `fake-jmh-benchmarks`, `fake-stress-tests` | Test fixtures |

**`baas-cli`'s packages are the command tree's nouns over a small kernel** (ADR 0009). `baas` is the
composition root and nothing imports it; the kernel — `cli`, `console`, `session`, `aws` — imports no
slice; the slices — `jobs`, `results`, `deployment` (with `setup`), `image`, `config` — import each
other only along deployment → jobs → {image, results}. Command classes import no AWS SDK type; `run`
and setup reach AWS through a port each (`JobLaunch` over `RunAws`, `SetupSteps` over `SetupAws`),
while the other commands still build their clients inline from a session's factory (ADR 0009, T2) —
give one a port when its sequence needs a test. `SliceBoundaryTest` enforces the package rules and the
import rule, reading import lines only; a new package fails it until it is given a place. Strict slice isolation was measured and rejected — setup builds and teardown retires
the image, and `run` prints its own measurements.

**The command tree is `baas [--deployment X] [admin] <noun> <verb>`, and its exceptions are aliases,
never shapes.** Nouns: `jobs` (the execution), `results` (the measurements), `config`; under `admin`
(deployer credentials) `deployment` and `image`. A top-level alias exists only for a verb owned by
exactly one noun — `run` (`jobs run`) and `query` (`results query`) — so `list` and `show` can never
be aliased and `baas list` cannot mean two things. A noun alone prints its usage. A new command
finds its noun; nothing else joins the top level. Decided in `jobs-command` (its `exploration.md`
records every alternative).

One trigger path: `baas run`. CI does not have a second one — `e2e-cloud-test.yml` is two
`ubuntu-latest` CI jobs that federate into `OperatorRole` and call `baas run`, so a regression in the
CLI cannot pass CI. `benchmark-runner.yml`, `exec-single-benchmark.yml`, `start-ec2-runner.yml`,
`stop-ec2-runner.yml` and the `act` harness under `.github/test/` are deleted; the consumer
contract is *install the CLI*, not *call our reusable workflow*.

Sequence diagrams for the main CLI commands and the C4 views: [`docs/diagrams/`](docs/diagrams/) (Mermaid sources,
no checked-in SVGs — update the `.mmd` when a command changes; `mmdc` rejects a `;` in sequence-diagram text, it
ends the statement). **Every `.mmd` edit is rendered and looked at before it is committed:**
`mmdc -i docs/diagrams/<file>.mmd -o <scratch>/<file>.png`, then open the PNG and check the change reads as
intended — a clean exit only proves it parsed, not that the arrow landed in the right branch. The render stays out
of the repository. Mermaid CLI is installed globally (`npm install -g @mermaid-js/mermaid-cli`); nothing in CI
renders these files, so a broken diagram is otherwise found by its next reader. State machines for the deployment, an operator machine and a job: `docs/diagrams/baas-states-*.mmd`. Design rationale and open risks:
[`docs/adr/0001-self-contained-baas-cli.md`](docs/adr/0001-self-contained-baas-cli.md); later decisions, and the hardenings declined
with their reasons, in `docs/adr/0002`–`0010`. Per-change
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

**Open review findings live in one file, [`docs/review/open-findings.md`](docs/review/open-findings.md)**
— every finding neither fixed nor accepted, with the designs of the changes queued to close them. IDs
keep the prefix of the review that filed them (`S`/`A`/`D`/`C`, `P`, `U`, `N`, `X`/`G`/`K`/`T`/`B`),
so citations still resolve. Read it before proposing security or architecture work. Closing a finding deletes its
entry; a decision worth keeping becomes an ADR (`docs/adr/0005` collects the declined hardenings).
Items in *Accepted risks* below are excluded on purpose. The per-module review files and the CLI
usage analysis were merged into it on 2026-10-05 — `git log -- docs/review docs/analysis`.

## Invariants — breaking these costs money or silently loses data

**User-data generation (`UserDataScriptBuilder`)**

- **No `set -e`.** If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before*
  starting the watchdog and orphans the instance. Errors are handled by exit code and the job
  item's status instead. (The Image Builder component rendered by `RunnerImageRenderer`
  *does* use `set -euxo pipefail` — opposite context: a half-installed toolchain must abort the
  bake, and there is no paid instance to orphan.)
- **The watchdog starts immediately after `INSTANCE_ID` resolves.** Every later failure has to be
  covered by it.
- **Comment-only lines are stripped from `SCRIPT_BODY` when it is rendered.** EC2 refuses user-data
  over 16 KB raw, and the comments were most of the script. So a line beginning with `#` never
  reaches the instance — nothing may depend on one, inside the manifest heredoc included.
  `aLargeJobStaysWellUnderTheUserDataLimit` holds an outsized run under 12 KB.
- **User-data installs nothing.** No `yum`, no JDK, no async-profiler download. The toolchain is
  baked into the AMI by `baas admin image build`; a runner that installed its own would measure on
  a slightly different machine every time, which is the drift this design exists to remove.
- **The environment manifest is written and uploaded *before* the benchmark starts.** A job that
  crashes still has to say what it crashed on — same reasoning as `cloud-init-output.log`.
- **Every manifest value is captured into a shell variable first.** The heredoc body is nothing but
  `${VAR}` references. Inlining command substitutions puts quotes, parens and awk programs inside a
  JSON string inside a heredoc — three levels of quoting, and a mistake in any of them yields a
  file that only fails weeks later in `baas jobs diff`. Values that can contain `"` or `\` go
  through `json_escape`.
- **`imageVersion`/`instanceType` reach the database via the runner's `--tag`, not EC2 tags.**
  `ResultsQueryService` reads the item's top-level `tags` map; tagging the *instance* leaves every stored
  result with a null `imageVersion`, and `--tag`/`--best-per` on it silently match nothing. The tag values are the
  ones observed on the box, so a result's tags cannot disagree with its own `environment.json`.
- **A stopped job's logs are shipped by a systemd stop hook, not by the CLI.** Ctrl+C, `baas jobs
  terminate` and the poll cap all end in `TerminateInstances`, whose default graceful OS shutdown
  runs `baas-ship-logs.service`'s `ExecStop` while the network is still up (billing has already
  stopped). It uploads what `ship_logs` does — boot log, the process output, `/app/**/*.log` at the
  runner's own keys, every name from `baas-model` (`BenchmarkType` gives both the local file and the
  per-type key, which differ for the JMH profiler types) — and every exit of the script calls `ship_logs` itself too, so a hook that
  never ran loses only the external-stop case. The hook file is `declare -f ship_logs` run by an
  explicit `/bin/bash`: a `#!` line would be stripped with the comments. Never pass
  `--skip-os-shutdown` to a runner's termination; it bypasses the hook.
- **The benchmark runs from `/app`, never `/`.** The runner (`JobLogs`, every benchmark type) scans
  below its working directory for `.log` files to upload, and cloud-init starts user-data in `/`.
  The walk itself is now bounded — 8 levels, unreadable entries skipped — so from `/` it would no
  longer abort on vanishing `/proc` entries, but it would still ship any `.log` the root filesystem
  holds into the job's results.
- **The results table name *does* go into user-data, and that is deliberate.** It replaced an SSM
  fetch of the mongo connection string, which had to stay out of instance metadata because it
  carried credentials. A table name carries none — access comes from `RunnerRole`, not from knowing
  the name — so fetching it at boot would buy nothing and cost a round trip on every job. Don't
  "restore" the SSM indirection.
- **`baas run` forwards `project` and every `--tag` — `branch` and `commit` included — to the
  *runner*, and never to the instance.** They reach the item's top-level `tags` map, which is the
  only query surface `baas results query` has. The instance carries only `project=baas`, `baas-role`,
  `baas-job-id` and `baas-deployment` (`Ec2ProvisioningService.instanceTags`, which takes no caller
  tags on purpose; `baas-deployment` is fixed, set by the CLI, and scopes every live-runner query to
  its deployment, so two deployments in one region never count each other's runners): a
  copied `--tag project=…` was a duplicate key EC2 rejects for the whole launch, and every copied tag
  was exposed to EC2's 256-character and `aws:`-prefix limits. A caller `--tag` for a
  machine-observed key (`imageVersion`, `instanceType`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch`,
  `type`) is
  rejected outright rather than dropped or allowed to win — the
  same rule that keeps a result's tags from disagreeing with its own `environment.json`.

**Three termination layers, all required.** Any one alone leaves a way to orphan a paid instance.
The watchdog is the only one that survives a deadlocked JVM.

1. Shell watchdog (`UserDataScriptBuilder`) — `sleep N && ec2:TerminateInstances`, fires
   `timeout + margin` after launch (`--watchdog-margin` / `ec2.watchdogMarginSeconds`, default 300,
   floor 60). Relative by construction: the absolute `--max-wall-clock` it replaced could be left
   below a raised timeout, so the watchdog killed a benchmark still inside its own budget. The floor
   is there because the watchdog counts from launch and `timeout` from JVM start — below it the
   instance can die before its final status is written. `RunCommand.watchdogBound` is the one place
   the bound is computed; it is also the CLI's poll cap. The watchdog records `timed-out` before
   its log upload, through `job_status` — which is why that function is defined *before* the
   watchdog forks: a subshell sees only the functions defined before it, and `bash -n` would not
   notice the difference. **Nothing kills the watchdog**, the script's own end included: it used to
   be killed just before `terminate-instances`, so a failed terminate left nothing able to end the
   instance, while a watchdog left running only writes a refused status. Every self-terminate,
   the watchdog's too, ends `|| shutdown -h now`. That relies on the launch's
   `ShutdownBehavior=TERMINATE`, and needs no API call, permission or instance id
2. Process `timeout` around `java -jar benchmark-runner.jar`
3. CLI JVM shutdown hook (`RunCommand`, armed from `JobLaunch`'s `Listener.reserved` — after the
   reservation, before the launch) for Ctrl+C, registered *before* `RunInstances` so an
   interrupt mid-launch can look the instance up by its `baas-job-id` tag. That lookup can
   miss — the instance may not exist yet, and `DescribeInstances` lags — so the instance covers it:
   its `running` write is refused over the recorded `cancelled`, and a refused `running` means it
   ships its boot log and terminates without starting the benchmark. It, the poll cap
   (`timed-out`) and `baas jobs terminate` share one stop step (`JobStop`): record why under a 5 s
   timeout, then terminate whatever the write did — unless the write was refused because the job had
   already reached a status after which the instance ends itself (`JobStatus.endsItself`:
   `completed`, `failed:<n>`, or a `timed-out` its watchdog wrote), when it is mid-upload and
   terminates itself. So the only stored status for which the CLI terminates a live instance it did
   not stop itself is `cancelled`. A status write never holds a termination back. `RunInstances`
   carries the job id as its client token, so an SDK retry cannot start a second, untracked
   instance. **`--detach` disarms the hook once the launch is confirmed** (ADR 0007): it fires on
   every JVM exit, not only on Ctrl+C, so left armed it would cancel the job the CLI had just
   returned from. A detached job's third layer is `baas jobs terminate`; until the confirmation, an
   interrupt cancels it like an attached one

**The runner image (`infra/runner-image.yaml`, `baas admin image build`)**

- **`baas run` has no fallback.** No AMI at `/<prefix>/runner/ami-id` → the runner-image lookup
  fails there, before any upload. Two provisioning paths would produce silently incomparable
  results.
- **Exactly one image, rebuilt in place.** No slots, no AMI history, no second pointer. The base's
  archive is git: `git log -p infra/runner-image.yaml`, and `git checkout <sha> -- …` to
  reconstruct — from 1.2.0 onward; 1.0.0 and 1.1.0 predate the file and cannot be rebuilt. An
  extension has no archive: the stack holds only the current one.
- **The pointer is repointed *before* the replaced AMI is deregistered.** Retiring first aims the
  pointer at a deleted AMI for the whole ~15-minute build, failing every job launched in that window.
- **Teardown retires the image, always, and only after the stack is gone.** The pointer, the AMI,
  its snapshot and the recipe's Image Builder records live outside the stack, so deleting the stack
  left them, and a later setup could launch that inherited AMI without any `build-image`. Retiring
  after the stack means a failed stack deletion still leaves a deployment with an image to run
  on. The pointer is deleted even when its AMI cannot be: the pointer is what a later setup would
  inherit, while a leftover AMI is only a cost leak. No step fails the teardown; leftovers are named
  with the command that removes each. `--deployment` retires *that* deployment's image.
  `build-image` itself still leaves one Image Builder record per build, which cost nothing.
  Teardown's AWS calls go through `TeardownAws`, and `TeardownSequenceTest` pins this order and the
  gates before it; a build's go through `ImageBuildAws`, pinned by `RunnerImageBuildSequenceTest`.
- **The image is three components: base, extension, contract — in that order.** The base is
  rendered from the bundled `infra/runner-image.yaml`, the only place a *base* tool version is
  declared; any edit needs `imageVersion` bumped, because Image Builder components are immutable at
  a version. The extension is an operator's raw AWSTOE document; the contract runs last and fails
  the bake when a BaaS workflow would break. Only the base's version is hand-bumped: the extension's
  and the recipe's are derived from the deployed stack (`RunnerImageParameters`), and the contract
  shares the recipe's because it carries the label.
- **The contract lives in the AWSTOE `test` phase, so `ImageTestsEnabled` must stay `true`.** With
  tests off the checks never run and an image that broke BaaS is published silently. A build-phase
  check would be cheaper and wrong: sysctls are applied, and THP and an upgraded kernel take effect,
  only on the booted image. A test-stage failure comes *after* Image Builder registered the AMI, and
  Image Builder keeps it, so `build()` retires a failed build's output AMI — without that, every
  contract failure leaked a snapshot no command would ever name.
- **The base writes nothing that depends on the extension** — the label in
  `/etc/baas-image-version` included, which the contract writes. Otherwise every extension edit
  would change the base and demand a hand-bumped version.
- **The extension is stored verbatim in the stack parameter `RunnerImageExtensionData`, and only
  `build-image --extension` changes it.** It is the only copy — overwritten, it cannot be recovered
  from AWS — so a push whose `# baas-extension-base:` marker no longer names the deployed extension
  is refused. With none deployed, any file is accepted (a file kept across a teardown), and a file a
  teammate deliberately emptied can come back that way: accepted. The copy that outlives a teardown
  is the export's `extension` part, marker included; teardown itself saves nothing, and `import`
  only prints the push, so this same guard decides it. Two pushes in the same seconds are
  not guarded, as for concurrent builds below. Anyone with `DescribeStacks` can read it, so it must
  never hold a credential. **ASCII only, trailing whitespace dropped:** `DescribeStacks` returns
  each non-ASCII character as `?` (the component itself gets the real bytes) and drops the trailing
  newline, so anything else makes the stack's copy — which the pull, the guard, the change detection
  and `admin image` all hash — differ from what was baked. Found live: an em dash in the starter's
  own comment.
- **`baas admin deployment setup` submits rendered image parameters only for what the stack lacks.** On create
  that is all of them — letting the template's placeholders stand would register a no-op at a
  version and Image Builder would then refuse the real one at that same version. On update every
  deployed image parameter is carried forward, so a plain setup can neither revert the base nor drop
  an extension; a parameter a pre-change stack lacks still gets a real value.
- **The preflight must not query components with `byName`.** That collapses every version into one
  row with no version field and an ARN ending in a literal `x.x.x`, so the version filter matches
  nothing, the preflight concludes the version is free, and a doomed build proceeds. Cost a real
  9-minute build to find.
- **`perf` is never pinned; the base installs the running kernel's build.** The RPM is built from one
  kernel build, the build instance boots the parent, so `uname -r` names the only right one and a pin
  could only disagree with it. The contract checks it still matches on the booted image, which is
  what catches an extension that upgraded the kernel.
- **The parent is an AL2023 release named by AMI name (`parentImage.amiName`), never an AMI ID.**
  An ID binds the definition to one region; the name resolves to the same release — kernel and
  repository — in every region, and exactly one Amazon-owned match is required.
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
  adding ~15 minutes to every `baas admin deployment setup`.

**Other rules that exist because something broke**

- **One job is one prefix, one id and one instant.** Everything a job produces or consumes lives
  under `jobs/<project>/<jobId>/`, built only by `JobLayout`/`JobId` in `baas-model` — the same
  reason `ResultKeys` owns DynamoDB keys. A hand-built prefix does not fail to compile; it points at
  nothing, and that presents as an empty download rather than as an error. Splitting inputs and
  results back into two trees is the split this design removed.
- **`baas run` reads the clock once per job.** That instant names the prefix and travels to the
  runner as `--created-at`, so the id's timestamp and the stored `createdAt` are the same value
  rather than two that happen to be close. The instance's clock never reaches the record. CI mints
  its id in bash and must pass the same instant, or the property holds for `baas run` and quietly
  fails there.
- **The instance contacts no host outside the account.** Its only runner-JAR source is
  `releases/<version>/benchmark-runner.jar` in the bucket, seeded by the CLI. Restoring a
  network fetch reintroduces both the drift — two jobs a week apart executing different runner
  code — and the egress `private-runner-network` exists to remove. The one other destination is AWS's
  own regional `ssm`/`ssmmessages` endpoints, where the AMI's `amazon-ssm-agent` registers so
  `baas jobs connect` can reach the runner. Same 443 egress; nothing reaches the job from there unless
  an operator opens a session.
- **Every runner is connectable, always, through the stack's own session document.** `RunnerRole`
  holds `ssm:UpdateInstanceInformation` and the four `ssmmessages:*Channel` actions, nothing else of
  SSM — no Run Command, no `ec2messages`, no managed policy — and `RunnerSecurityGroup` gains no
  ingress. The document is `<prefix>-session-runner`, never the account-global
  `SSM-SessionManagerRunShell`: settings someone puts on that one (S3/CloudWatch logging, KMS) would
  need grants the runner lacks, and every session would then fail for a reason BaaS cannot see. The
  operator's `StartSession` names the deployment's `baas-deployment` tag and that document, with
  `ssm:SessionDocumentAccessCheck` making the document mandatory. In a private subnet this needs
  `ssm` and `ssmmessages` interface endpoints (~$7/month per AZ each), which is why
  `private-runner-network` stays parked (ADR 0010).
- **`baas jobs connect` ends its session when the instance terminates, and a session never holds
  the instance.** Session Manager leaves a session open after its target is gone and the plugin has
  no escape key — in raw mode Ctrl+C goes to a shell that no longer exists — so the terminal hung on a
  dead prompt (found live). While the plugin runs, `connect` checks the instance every 15 s and ends
  the session once it is ending or ended. Nothing about a session delays, extends or blocks any
  termination layer.
- **`releases/<version>/benchmark-runner.jar` is seeded once and never overwritten.** That
  immutability is what the whole pinning argument rests on, and two things hold it: the seed is a
  `PutObject` with `If-None-Match: *`, so S3 refuses a second write — a 412 or 409 means another
  launch got there first, and is not an error (two first jobs of 7.0.0 both wrote the key while it
  was a head-then-put) — and `RunnerRole` can only *read* `releases/*`, so benchmark code cannot
  replace the runner every later job of that version executes. A corrupted object therefore does
  not self-repair: delete the key (deployer credentials — no other role can delete) and the next
  job re-seeds it, checksum-verified.
- **The runner's bucket grant is an allow-list: `PutObject`/`GetObject` on `jobs/*`,
  `GetObject` on `releases/*`, and nothing else** — no delete, no listing, no other prefix. The
  operator holds `PutObject`/`GetObject`/`ListBucket` and no delete either; teardown empties the
  bucket under the deployer's rights. A new top-level prefix a runner must write to fails its first
  job loudly until it is added (`CoreTemplateTest` pins both grants).
- **Bucket versioning is `Suspended` and no lifecycle rule expires current objects under `jobs/`.**
  Both are load-bearing. The deleted `expire-uploaded-benchmark-jars` rule assumed everything under
  `jobs/` was re-creatable from source; results live there now, so restoring it is silent data loss
  (`CoreTemplateTest` pins its absence). Versioning only ever guarded an overwrite that the job
  id's 32-bit entropy suffix now prevents — and the consequence is stated, not implied: there is no
  server-side recovery from one.
- **`RunnerSecurityGroup` allows egress on 443 only — no 80, no 27017, and adding either back is a
  regression.** 27017 existed because Atlas does not serve clients on 443; nothing connects to Atlas
  now. 80 existed for `dnf`, which only the image bake runs, and the bake has its own
  `ImageBuildSecurityGroup` (443 + 80) — sharing one group handed the runner the builder's internet
  egress. `CoreTemplateTest` pins both absences rather than merely not testing for them — a security
  group rule nobody can explain is one somebody restores. Every deployment uses the VPC its own
  stack creates: existing-VPC support was removed in `setup-prerequisites`, no deployment used it.
- **Editing `RunnerSecurityGroup`'s `GroupDescription` replaces the security group.** It is an
  immutable property, so CloudFormation deletes and recreates the resource and the group *id
  changes*. Anything holding the old id is then pointing at a group that no longer exists —
  a deployment's `~/.baas/deployments/<name>.yaml` most obviously, which `baas admin deployment setup` rewrites, but not a job already in
  flight. Observed: removing the 27017 rule also touched the description, and the id moved. Change
  the rules without touching the description unless you intend the replacement. Its text still says
  "443/80" for exactly this reason, and `theRunnerSecurityGroupDescriptionIsNeverEdited` pins it.
- **Setup checks the stack's fixed names before every create, despite `DeletionPolicy: Delete`.**
  The bucket is named `<prefix>` and the table `<prefix>-results`, so a taken name fails the whole
  create into `ROLLBACK_COMPLETE`. Leftovers still happen: a pre-`Delete` teardown, a bucket made by
  hand, or another account owning the globally unique bucket name. Setup names the leftover and the
  command that removes it; it never deletes one. `BucketProbe` asks a redirect again in the region
  it names, because from elsewhere another account's bucket answers 301 like your own, and only its
  own region answers 403. A stack in `ROLLBACK_COMPLETE` holds nothing, so setup deletes and
  recreates it rather than pointing at teardown. A file may exist for it: on create, setup writes
  the deployment's file as soon as CloudFormation accepts `CreateStack`, before the stack is
  complete, so an interrupted create — Ctrl+C, a closed lid — never leaves a stack no file names.
  Teardown resolves a deployment only through its file, so without one a re-run under another
  name built a second deployment and left the first out of reach (`interactive-setup` D20).
- **Setup is a walkthrough that records no progress; each step re-checks AWS.** On a terminal it
  asks and waits at a step the user must do outside `baas`; unattended (`--non-interactive`, a
  redirect, CI) the same steps exit 1 there. A waiting step is re-checked only when the user presses
  Enter, never on a timer: the permission check is 15 `SimulatePrincipalPolicy` calls and IAM
  throttles that API hard enough to turn the check off. Every prompt has an option that answers it,
  which the repeat-command round-trip test pins. The deployer profile is stored only on evidence it
  can deploy — a met check, or a stack operation that changed something — because a no-op re-run
  under an identity that may not simulate "succeeds" while proving nothing. All of setup's AWS calls
  go through `SetupAws`, faked by hand in tests.
- **EC2 tags use the key `baas-role`, not `baas:role`.** `RunnerRole`'s `ec2:TerminateInstances`
  condition is scoped to it, so changing the key breaks self-termination.
- **Root volume is 30 GB gp3, not the AL2023 default.** 8 GB is exhausted by profiling artifacts.
- **Day-to-day commands always run as `<name>-role-operator`; any credentials, the deployer's
  included, are only the source of that assumption, and a failed assumption stops the command.**
  `OperatorSession` takes the source from `aws.operatorProfile`, else `aws.deployerProfile`, else the
  default chain, identifies the caller, and then uses an operator-role session for this deployment as
  is, refuses another deployment's operator role, or assumes the role itself. The deployer fallback
  is safe only because nothing but `GetCallerIdentity` and `AssumeRole` is ever called with the
  source — so never let an error path carry on with it (`aFailedAssumptionStopsBeforeAnyClientIsBuilt`
  pins that). It replaced *operatorProfile must not fall back to deployerProfile*, which guarded the
  same rights when the profile *was* the identity. `baas admin` uses the deployer source as is and
  never assumes the operator role; the deployer policy grants `sts:AssumeRole` on its own operator
  role only, so an admin needs no operator setup at all.
- **Every resource name is `<prefix>` or `<prefix>-<type>-<name>`, and the prefix is the deployment's
  name, verbatim.** One rule, no exceptions: `ResourceNamePrefix` is the whole name stem and knowing
  it predicts every name (`<prefix>` stack and bucket, `<prefix>-results`, `<prefix>-role-runner`,
  `<prefix>-profile-runner`, `<prefix>-pipeline-runner`, `/<prefix>/runner/ami-id`), and only
  `DeploymentNames` composes them (C5). The default name, `baas-<accountId>`, carries the `baas-`
  namespace inside it — composing `baas-` again at a use site yields `baas-baas-123456789012` — and
  a named deployment carries whatever its name does.
  The default is derived from `sts:GetCallerIdentity().account()` and **nothing about the calling
  principal reaches it** — that is the point. It used to be
  `lowercase(base32(sha256(callerArn)))[0:8]`, which moved when an SSO permission set was switched
  or re-provisioned and differed per human on one account; a moved prefix did not fail, it deployed
  a second complete deployment beside the first (finding A10). **A second deployment exists only
  because someone typed its name** into the global `--deployment` (`baas --deployment wiktor-dev
  admin deployment setup`), which is what keeps A10 closed (ADR 0006): nothing derives a name from
  the caller, and nothing derives one silently. Setup validates a name once, as the lowest common
  denominator of every service carrying it — 3–47 characters (the longest composed role name within
  IAM's 64), `^[a-z][a-z0-9-]*[a-z0-9]$`, no `--`, not starting `aws`/`ssm` (SSM refuses the
  `/<prefix>/…` hierarchy) or `sthree-`/`amzn-s3-demo-`, not ending `-s3alias` — and checks nothing
  else; a name that looks like another account's default is the user's call.
- **An update carries forward only the parameters the new template declares.**
  `updateStackParameters` sends every other deployed parameter as `UsePreviousValue` — which is how a
  plain setup or an image build cannot revoke CI's federation — but CloudFormation refuses a whole
  update naming a parameter the template no longer has. Every stack created before
  `setup-prerequisites` still carries `UseExistingVpc` and its three companions, so without the filter
  their first update after the removal fails. A removed template parameter needs no other handling.
- **Each deployment is one file, `~/.baas/deployments/<name>.yaml`, storing credential *profile
  names*, preferences and the region — each preference only once set, and no secret.** The name is
  the file's name and is not written inside it, so a copy under another name configures that other
  name and can never aim at the original. Defaults are supplied when read, never written, so a
  changed default reaches every machine. The region is the one fact about AWS kept: it cannot be
  derived without a call and does not change while the deployment exists. `runner.sourceRepo` is
  gone — the build carries it (`baas.sourceRepo`, set by the release to the releasing repository).
  The credentials themselves stay in `~/.aws`. One file per deployment, not one file with a map: an
  older CLI's save drops keys it does not know, so a map would vanish the first time an older CLI
  ran `config set`. There is no migration — not of the flat `config.yaml` of earlier releases, nor
  of files carrying a `prefix`: the test deployments were rebuilt (`derived-deployment-config`), and
  a stray old key is warned about and ignored. `~/.baas` is not
  relocatable — no option, no environment variable; tests pass a root to `new BaasApp(root)`, and
  surefire gives unit tests their own `user.home` so none can touch a developer's real `~/.baas`.
  The bucket, results table and runner instance profile are *derived* from the prefix; the runner
  subnet and security group are *resolved* from the stack's outputs on every job. Neither kind is
  cached, because a stored name can point at one deployment while `prefix` names another, and a
  stored security-group id outlives the group when `GroupDescription` forces a replacement — the
  failure that rule previously only documented. `aws.coreStackName` is gone (the stack name *is*
  the prefix), as are `aws.vpcId` and `benchmark.asyncProfilerVersion`, both of which were read by
  nothing but `config show`; the latter printed `4.0` regardless of what the AMI held.
- **Absent `--deployment` means "the only one", never a guess.** With exactly one deployment
  configured a command uses it; with two or more every command — teardown included — refuses,
  listing them and hinting `baas config list`; with none, setup derives `baas-<accountId>` and a
  day-to-day command takes the name from an operator-role session (`<name>-role-operator`, the CI
  path) and otherwise refuses. That role is created by the stack, cannot move, and can only operate
  the deployment it names, which is why it does not reopen A10 (ADR 0006's amendment); with any file
  present it never selects, it can only be refused. Nothing else selects a deployment: no `BAAS_DEPLOYMENT`, no stored default, no
  `config use` — each is invisible state that changes what a command hits. A developer with a
  second deployment reaches it through an alias that carries the flag. `--config-path` is gone for
  the same reason. Teardown deletes the deployment's file, the last one included.
- **`baas config sync` is never derived, though the prefix is derivable.** With nothing configured it
  must be named: a bare sync on a machine with no local state would adopt whatever deployment the
  active credentials imply — in CI, a wrong role or a leftover `AWS_PROFILE` binds the machine to
  another account's deployment and fails later, after provisioning. With exactly one configured, a
  bare sync re-verifies that one. Setup derives and prints; sync adopts what it is told, proving
  first that the credentials reach the operator role (`--operator-aws-profile` is checked, then stored). CI does not sync at all. A deployment
  is named only by the global `--deployment`, never by a per-command `--results-table` or
  `--bucket`, which are gone: an override of one resource could aim a command at one deployment's
  table while its configuration named another.
- **The region is chosen once, by `baas admin deployment setup --region`, and never typed again.** `config sync`
  finds it: the bucket carries the prefix's name, bucket names are global, and `HeadBucket` from any
  region answers a wrong-region request with 301/400 carrying `x-amz-bucket-region` (a 404 is no
  bucket) — under the `s3:ListBucket` the operator already holds. Setup and sync store the region;
  a machine with no file (CI) looks it up once per invocation, so a CI job follows the deployment
  rather than its `AWS_REGION`, whose only job is the credential exchange. There is no
  `config set --region`: set by hand it aimed a machine at a region with no deployment, and `run`
  then advised building an image there. Moving a deployment is a rebuild in the new region, after
  which every machine re-runs the same `config sync --deployment`.
- **A build is waited for or adopted, never repeated — only two starts in the same seconds race.**
  The CLI runs the whole build, so Ctrl+C or a closed lid mid-build used to leave Image Builder
  finishing an AMI no pointer named and nothing retired, and a re-run started a second build.
  `ImageBuilderService.publish` now looks at the pipeline's newest build first: still running, it
  is waited for; finished but unpublished, it is adopted (tags, pointer, retire the replaced AMI).
  Only a build of the recipe version the stack declares qualifies — one from before an extension
  push is retired instead, or the runners would get the superseded extension. Two
  `baas admin image build` runs started in the same seconds can still both start a build and have
  the second deregister what the first published: deliberately not guarded.
- **A deployer policy is prefix-exact, so it covers one deployment only** — except its Image
  Builder statement, whose `<prefix>-*` also matches a deployment named `<prefix>-<more>` (accepted
  below). Each is attached as its
  own customer-managed `BaasCliDeployerPolicy-<deployment>` (6144 characters each): two rendered
  documents are ~9k non-whitespace characters, far over IAM's 5120-character *inline* budget,
  which is shared across every inline policy on the principal. Setup hands over the one it needs
  and creates nothing until it is attached.
- **The installer installs released artifacts only, and the repository copy refuses.**
  `scripts/install.sh` carries `BAAS_VERSION_DEFAULT`, rewritten at release time by `release.yml`'s
  `prepareCmd` and never committed back. A checkout copy holds the placeholder and exits naming
  `--version`, the same no-fallback stance `RunCommand` takes on an unreleased build. There is no
  `--update` and no installer copy stored beside the jar (`cli-version-floor`): updating is running
  the newest release's published installer, the line the version refusal prints, so the script
  installing version X is always version X's own. `--uninstall` still deletes a stored
  `install.sh` an earlier release left; that copy's `--update` keeps working, because it hands off
  to the newest installer in plain install mode.
- **A deployment records the release that last ran setup on it, and every command that reaches AWS
  refuses a CLI older than that — patch level included.** The record is the stack parameter
  `BaasCliVersion`, written only by setup and only from a released `x.y.z` build; an image build
  carries it forward, and a reactor build's setup sends nothing, so the value stays. The check lives
  in the two sessions, not in commands: `OperatorSession.factory()` runs it when the first client is
  built, `DeployerSession.factory(config)` before it returns, setup in its names step — so a new
  command is covered by construction. `jobs terminate` alone takes `uncheckedFactory()` (stopping a
  paid instance never waits on an install), and `VersionCheckCoverageTest` pins that, and that no
  client is built outside a session. A newer CLI runs with one stderr line; an unreleased build
  runs unchecked and says so. **The parameter's conditional output looks unused and is not:**
  CloudFormation answers "No updates are to be performed" to a change of a parameter nothing
  references and stores nothing, so without it every raise would silently fail to record (verified
  live, `cli-version-floor` `verify.md` 1.1). CLIs released before the check ignore the parameter.
- **`commit` and `branch` are absent unless the caller tags them, never `"unknown"`.** A placeholder
  value is indistinguishable from a real one at query time — the same non-answer wearing a value's
  clothing that produced `RESULT#unknown` (*What isn't there*). Tags are the entire query surface, so a fake value
  there is worse than a missing one. They are never derived: only `--tag branch=… --tag commit=…`
  supplies them.
- **Git is consulted only when `git.resolveProject` is on, and only for `project`.** Off by default
  (`baas config set --git-resolve-project true`). `baas run` then derives the project from the
  repository **holding `--benchmark-jar`** — never the working directory, which is incidental to
  what is measured — and `baas results query` from the working directory's repository, skipping its
  project picker. A derived default that applied without being asked is what made the same command
  record or read a different partition depending on where it was typed.
- **Teardown offers the export first, and saves nothing itself.** On a terminal the offer comes after
  the in-flight gate — the export then sees a quiet table, the one case where a scan is as good as a
  snapshot — and before the typed name, so a failed export never leaves a confirmed teardown
  half-done. `--yes` asks nothing, so it offers nothing and exports nothing: unattended, run
  `admin deployment export` first. Teardown used to save the extension unasked; that copy now lives
  only in a bundle. A teardown without `--yes` was tried and reverted the same day (2026-10-23): an
  agent has no terminal, and every way of faking one is refused, so only a person could tear down.
- **A bundle is the wire shape, and import never overwrites.** `results.jsonl` is every item in
  DynamoDB's typed JSON, decoded through no mapper, so an attribute the model does not know survives
  and `aws dynamodb put-item` can read any line. `manifest.json` is written last: a directory
  without one is an export that did not finish. Import writes each item under
  `attribute_not_exists(pk)` and each object under `If-None-Match: *`, has no overwrite mode, and
  uploads only keys under `jobs/` — never `releases/`, which stays write-once. It targets whatever
  `--deployment` resolves to, never the deployment the manifest names; that works because every
  stored pointer is a bucket key, never an `s3://` URI.

## What isn't there, and what fails silently

- **A session leaves no trace in a job's results.** No transcript is stored, and measurements of a
  job someone connected to carry no tag or attribute that differs from an untouched job's — decided,
  not overlooked (ADR 0010). A result measured while a shell was open is indistinguishable by query;
  the operator who connected knows the job id. Results from before and after every runner registered
  its agent share one `imageVersion`, too: the change date is the only boundary.
- **`baas run` has no local mode.** It always provisions EC2. The only no-cost way to exercise the
  runner is `./jmh-with-profiler.sh` / `./jmh-with-async.sh` against LocalStack.
- **Measurements live in DynamoDB, and a verbatim `jmh-result.json` now exists in S3.** The item
  carries what a table view needs; `rawData` and `scorePercentiles` are dropped from it and are
  recoverable only from that JSON, via `baas jobs download <jobId>`.
- **A reactor build cannot launch a job.** The CLI pins the runner JAR to its own released version,
  and `0.0.0-semantically-released` names no release — so `baas run` fails immediately, before
  resolving the project or the results table, unless `--runner-jar` is passed. Same no-fallback
  stance as the runner AMI. Installed releases are the ordinary case; a reactor build is the
  developer's explicit special case — the `baas-dev` alias and CI's `e2e-cloud-test.yml` — which
  is also why the version check skips it with a warning instead of comparing.
- **The runner refuses an unresolved project.** `getProject()` used to fall back to `"unknown"`,
  and CI had been writing `RESULT#unknown` because of it — a partition nobody queries. It now
  throws, before the benchmark runs.
- **Absent store configuration is a hard failure, and nothing discards measurements.** `baas run`
  resolves the table before the runner-image lookup and before any upload, and `benchmark-runner`
  rejects a missing `--results-table` outright. `--no-database` is gone from both: every job records its
  status in the table, so a job without one could not be seen at all, and local runner invocations name a
  LocalStack table. The old behaviour — unset URI selects a no-op store, the job reports success,
  numbers vanish — is gone, and reintroducing any fallback brings it back.
- **There is no MongoDB anywhere, and no local storage mode.** `retire-mongodb` removed the runner's
  Mongo store, `--mongo-connection-string`/`-m` and `MONGO_CONNECTION_STRING`, and the runner's
  write-to-disk mode: `--results-table` and `--s3-bucket` are both required, so a local run names a
  LocalStack table and bucket. `baas-model`'s `ban-mongodb` enforcer rule stays as the guard.
- **A runner's download of a missing key answers 403, not 404.** `RunnerRole` holds no
  `s3:ListBucket`, and without it S3 will not say whether a key exists. The only such reads are
  user-data's two `aws s3 cp` downloads of JARs the CLI wrote seconds earlier, so a 403 in
  `cloud-init-output.log` there means the key is missing as often as it means a grant is.
- **`ASYNC_PATH` gates async coverage.** `JmhWithAsyncProfilerSubcommandServiceIT` is annotated
  `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", ...)`, so a plain `mvn verify` **silently
  skips** the only test exercising async-profiler end to end. Export it before trusting a green
  build on profiler changes. Same variable `jmh-with-async.sh` needs, since `--async-path`
  otherwise defaults to the on-instance path.
- **The GitHub Actions benchmark path is gone, not fixed.** It was broken six ways at once — a
  deleted SSM mongo parameter, a revoked IAM grant, no 27017 egress, an expired PAT, an
  unresolvable project, and an assertion (`tags.source == gha-e2e-test`) no job could ever satisfy
  because the jobs were tagged `gha-e2e-test-async`. Deleting the bash orchestrator and calling
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
- **Diagnostics go to the logger (stderr); command payloads go through `Console`, never
  `System.out`.** `Console` wraps picocli's `getOut()`, which *buffers*, so a stray `System.out`
  write elsewhere lands out of order with everything else — hence all-or-nothing, and no
  `System.out` anywhere in `baas-cli` main code. Payload that must never be styled: JSON, CSV,
  `RunCommand`'s JSON summary, `DeployerPolicyCommand`. A timestamp prefix on any of it breaks
  `--format json | jq` and `--format csv > file`, which is why none of it is logged. The test for
  which side a line belongs on is whether a redirect of stdout should keep it: an empty answer
  ("No results found.", "No differences.") is payload; the unknown-`--tag` warning, both
  `ImageCommand` warnings and `BaasApp.reportFailure` are not, and are logged.
- **Terminal effects happen only when `Console` says interactive: `System.console()` +
  `isTerminal()` + `TERM != dumb`, decided once.** Every misdetection is a *no*, so the worst case
  is today's plain output and never an escape sequence in a file. `NO_COLOR` drops colour.
  picocli's `Ansi.AUTO` is deliberately not used — it colours `TERM=dumb`, and `CLICOLOR_FORCE`
  makes it colour a pipe. Tables pad *then* colour (`console.Table`), so columns never move.
- **`baas run`'s status line is on stdout, not stderr, on purpose.** Java cannot tell whether
  stderr alone is a terminal (the `cli-console-output` spike: a child `test -t 2` or `/dev/fd/2`
  can, at a cost), and a wrong guess writes `\r` into `2> run.log`. Gated by `System.console()`,
  every wrong guess falls back to the log line instead. While shown, it swaps `System.err` for a
  clear-write-redraw stream — SimpleLogger resolves `System.err` per write, which is what makes
  that reach it — and the shutdown hook closes it *before* logging the termination.
- **`printJson`/`printCsv` must format with `Locale.ROOT`.** Under a comma-decimal locale (pl-PL
  among many) a bare `%.6f` emits `8234574,731914`, which is not a JSON number and splits a CSV
  column in two — silently, and only on some machines. Non-finite values become JSON `null`, since
  JSON has no `NaN` literal and JMH reports one for any single-iteration run.
- **`e2e-cloud-test.yml` drives `baas run` end to end, and it is the only thing that does.** Two
  `ubuntu-latest` CI jobs, each launching its own instance: `benchmark` runs `jmh-with-async` against
  `fake-jmh-benchmarks` on the real runner AMI, so a bad bake fails CI rather than surviving it —
  though it asserts only that a flame graph *file* exists, and its `flamegraph,jfr` flags yield
  empty graphs (finding E1);
  `jcstress` runs `fake-stress-tests` in JCStress sanity mode (`-- --mode sanity`) and asserts the
  stored item's shape only, never pass/fail counts — the fixture's `TestWithForbiddenResults` races
  nondeterministically. It is path-filtered on `pull_request` plus `workflow_dispatch` because it
  provisions a paid instance per CI job per triggering event, and it tags
  itself `exclude_from_results=true` — which is why `queryByJobId` carries no exclusion
  filter. **The path filter bounds it per PR, not per push:** GitHub evaluates a `pull_request`
  path filter against the PR's cumulative diff, so once a PR touches `baas-cli/**` *every*
  subsequent push to it launches another instance, including one that only edits an unrelated
  workflow. Accepted (2026-09-20) — cents per push, and a measurement per revision is worth
  having. `cancel-in-progress` stays `false` deliberately: cancelling mid-`baas run` can kill the
  CLI before its shutdown hook fires, leaving the shell watchdog as the only termination layer,
  which is the red-CI-job-and-full-bill failure this design exists to remove. In-process,
  `JobLaunchTest` and `RunCommandLaunchTest` drive whole launches, success path included, against
  `FakeRunAws`; what only this job proves is the real AWS behind the port.
- **`docker-compose` has no init container.** Create the bucket and any SSM params by hand:
  `aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas`, and the results
  table if you want one.
- **`scripts/install.sh` is the one script CI does invoke.** `release.yml`'s `prepareCmd` `sed`s the
  released version into it and publishes it as a release asset, but `install-test.yml` never installs
  that asset: it builds a fixture release in the CI job (`BAAS_BASE_URL: file://…/fixture`) and runs
  the working-tree installer against it, on `ubuntu-latest` and `macos-latest`. No CI job exercises
  a published installer or the release-time `sed` bake — those job only during a real release. The
  other utilities under `scripts/` still have no CI coverage.
- **`s3-hook-lambda` is gone** — module, CloudFormation resources, `<prefix>-lambda` bucket, and the
  S3-object-create trigger path. Any reference you find is stale.
- **The zsh orchestration helpers are gone** (`run-remote-benchmark.zsh`, `wait-for-gha-run.sh`,
  `benchmark_overview.sh`, `logger.sh`, `git_helpers.sh`, `aws_helpers.sh`). Use `baas run` /
  `baas results query`, and don't reintroduce shell helpers for orchestration. The
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
  Every integration test runs on LocalStack (S3 and DynamoDB); the store contract suite's one real
  binding is the DynamoDB adapter.
- **LocalStack is pinned to `4.14.0`, the newest release confirmed (2026-10-05) to start without an account.** `2026.09.0`
  (and so `latest`) exits 55 without `LOCALSTACK_AUTH_TOKEN`, and this repository holds no secrets. The pin
  replaced `0.12.16`, which took 25–65 s to start against Testcontainers' 60 s wait and stored the SDK's
  `aws-chunked` upload framing as object bytes — `StorageServiceIT` had asserted that corrupted size.
- **A JCStress job's mode is `--mode`, not `-m`.** The runner forwards it to JCStress as `-m`, but
  `-m` is no runner option at all: it used to be the Mongo connection string, and was deliberately
  left unassigned rather than made an alias (`retire-mongodb`). `baas run jcstress -- -m sanity` is
  refused before launching, pointing at `--mode`; the runner itself rejects `-m` as unknown.
- **Text after `--` may not name a runner option `baas run` sets** (`RunnerArgs.SET_BY_THE_CLI`:
  `--tag`, `--project`, `--job-id`, `--results-table`, `--s3-bucket`, the endpoints, …). User-data
  passes it after the CLI's own options and picocli keeps the last value, so it would override the
  guarded tags or the project, or fail the job on the instance. Refused with exit 2, before any AWS call.
- **JCStress writes `jcstress-results-*.bin.gz` to the module root**, not `target/`. `mvn clean`
  removes them via an extra fileset.
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
  the build-instance role. Deployed by `baas admin deployment setup`, bundled into the CLI as the
  classpath resource `/templates/cf-template-core.yaml`. It always creates its own networking.
  Eight parameters
  describe the runner image (`RunnerImageParameters.ALL`): the base, parent, extension and contract
  documents and their versions, and the label. Each component travels as a parameter value, so it
  must stay under CloudFormation's 4096-byte cap — unit-tested for base and contract, refused before
  submission for an extension. Two more — `GitHubOrg`, `GitHubRepo` — declare the conditional
  federated principal on `OperatorRole`'s trust policy and create no resource; the provider's ARN
  is composed in the template from the account, since its issuer URL fixes it. `GitHubRepo` is a
  `CommaDelimitedList` of **already-composed** `repo:<org>/<name>:*` subject patterns: setup builds them,
  because CloudFormation cannot iterate a list and every in-template trick for it leans on
  `Fn::Sub` re-scanning text substituted into it, which it does not do.
- **`runner-image.yaml`** — the measurement environment's base: base version, pinned parent
  release, Corretto and async-profiler versions, kernel tunables. Ships in the JAR as
  `/templates/runner-image.yaml` because both `setup` and `build-image` render it at runtime.
- **The GitHub OIDC identity provider is created by setup, never by a stack, and never modified or
  deleted by `baas`.** It is account-global (one per issuer URL) and may serve other projects, so
  it lives outside every deployment: setup creates it through the IAM API, with the
  `sts.amazonaws.com` audience and no thumbprint, when federation is asked for and it is absent;
  an existing one is reused, and one lacking that audience is left to an IAM administrator, with
  the command printed. Teardown says it stays. The deployer policy's `GitHubOidcProvider`
  statement grants only `Get` and `Create` on that one ARN. This reverses `baas-cli-core-ci-split`
  (ADR 0008); `cf-template-ci.yaml` is deleted.
  `WorkflowRole` is deleted too — GitHub Actions federates straight into `OperatorRole`, because a
  role-chained session is capped at 60 minutes by STS whatever `MaxSessionDuration` says, against
  a 7200 s default benchmark timeout. That failure was not an error but a red CI job with a good
  measurement, an un-terminated instance and a full EC2 bill.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin deployment setup`/`build-image`/`teardown`; `operator-policy.json` → the stack-created
`BaasCliOperatorRole`, narrow, for `baas jobs`/`results`/`config`. `operator-policy.json` reaches the
**test** classpath only; the core template and the two deployer policy templates ship in the JAR
because the CLI renders them at runtime.

**The deployer policy has a size ceiling.** It is attached as the customer-managed
`BaasCliDeployerPolicy-<deployment>`, which IAM caps at 6144 non-whitespace characters to itself (an
inline policy gets 5120, shared across every inline policy on the principal, so two deployments'
documents never fit inline side by side). The rendered document sits at about 4990 non-whitespace
characters (measured 2026-10-23), and `renderedPolicyLeavesRoomInACustomerManagedPolicyBudget` holds it under 5632,
keeping 512 in reserve. That is why whole verb classes are wildcarded (`ec2:Describe*`, `s3:Get*`,
`imagebuilder:Get*`, `dynamodb:Describe*`) rather than enumerated — naming every action
CloudFormation's bucket read handler needs is what pushed it over. `Create` is deliberately *not*
wildcarded: `imagebuilder:CreateImage` must stay excluded, and `s3:Put*` would grant bucket policies
and ACLs. The deployer's object and item writes are named: `s3:PutObject` and `dynamodb:PutItem`
(with `dynamodb:Scan`) exist for `admin deployment export`/`import` alone, and no batch write,
item update or delete is granted — import's writes are conditional, and a batch cannot carry the
condition.

**BaaS targets the commercial `aws` partition only.** `deployer-policy.json` writes `arn:aws:`
literally while `cf-template-core.yaml` uses `${AWS::Partition}`; the template's form is free and
harmless, but making the policy partition-aware alone would imply support nothing else has — the
CLI fetches the runner JAR from GitHub releases, for one. Not a gap to close piecemeal.

**`deployer-policy.json` is a template, never a policy.** It carries `${ACCOUNT_ID}` / `${REGION}`
/ `${PREFIX}` placeholders and is rendered per caller by `DeployerPolicyRenderer` — every resource
it names is prefix-exact, so two developers cannot reach each other's stack, bucket or SSM
parameter (Image Builder alone is `<prefix>-*`; see *Accepted risks*). Attaching the file as-is grants nothing. There is no command that prints it:
`baas admin deployment setup` renders it for the deployment it is about to create — `--region`
included, since the region is in seven of its ARNs and conditions — and, when the caller's rights
fall short, hands it over having created nothing: unattended it prints it on standard output and
exits 1, so `setup > policy.json` works; on a terminal it saves it to a file or prints it, as the
user chooses, and waits for Enter to check again. `--policy-file` also writes it to a file before
the check, whatever it finds, then setup carries on. It is attached as a
customer-managed `BaasCliDeployerPolicy-<deployment>`, and setup prints the IAM administrator's
filled-in commands whenever it reports missing rights, a permission failure in stack events
included.

**The preflight probes what the rendered policy grants, never a list kept beside it.** Every action of
every `Allow` statement without a `Condition` is simulated with that statement's resources as written
— the simulator accepts wildcard actions and pattern resources — one call per action, because one
call mixing actions that need "different authorization information" is refused, and judged on the
action's overall decision, because the per-resource results report `implicitDeny` for every resource
type an action does not apply to. A hand-kept list let a stale policy pass three times
(`dynamodb:CreateTable`, `iam:UpdateRole`, 7.1's `sts:AssumeRole`). Conditioned statements stay
unprobed: the simulator has no `aws:RequestedRegion` or `iam:AWSServiceName` to evaluate.

`baas admin deployment setup`'s preflight (opportunistic `SimulatePrincipalPolicy`, plus translating any
`AccessDenied` into the rendered policy) is a **UX affordance, not a control** — anyone holding the
policy can call IAM directly. Don't try to make it one.

GHA values whose origin isn't obvious from the workflow files. All are plain `vars.`, no secrets:
a role ARN is not sensitive, and nothing else is left to hold. `WORKFLOW_ROLE_ARN`,
`RUNNER_ROLE_NAME`, `GHA_EC2_PAT`, `SUBNET_ID`, `SECURITY_GROUP_ID` and `RESOURCE_NAME_PREFIX` are
deleted along with the workflows that read them, and `CORE_STACK_NAME` with CI's `config sync`.

| Name | Source |
|---|---|
| `OPERATOR_ROLE_ARN` | Core stack output `OperatorRoleArn` — the role CI federates into directly |
| `AWS_REGION` | Any region, for the credential exchange only — the CLI finds the deployment's region from its bucket. CI names no deployment: the CLI takes it from the operator role |

## S3 result layout

One job is one prefix: `<result-path>` = `jobs/<project>/<jobId>/`, where `jobId` is
`<UTC instant, ISO basic, milliseconds>Z-<8 hex>` (e.g. `20260820T174432812Z-a3f9c21b`) — fixed 28
characters, time-ordered so a listing reads chronologically, entropy-suffixed so two jobs starting
in the same millisecond cannot collide. Nothing parses it; the format is a readability convention,
not a contract. Built only by `JobLayout`/`JobId` in `baas-model`, never by hand.

Per-type stdout lands in `jmh-output.txt`, `jmh-profiler-output.txt`, `jmh-with-async-output.txt`,
or `jcstress-output.txt`; profiling artifacts go under the directory JMH names each benchmark's
profile, `<fully.qualified.BenchmarkName>-<Mode>` plus `-<param>-<value>` per `@Param`
(`BenchmarkParams.id()`). **The runner never predicts that name:** it uploads the profiler's whole
output tree as written, because the JMH that chooses it is the one inside the benchmark JAR, and
predicting `<benchmark>-<Mode>` silently uploaded nothing for every `@Param` benchmark (#115). For
the same reason no measurement carries a profiler path — a job's artifacts are reached by its id. The
non-obvious entries:

| Key | Meaning |
|---|---|
| `launch-error.txt` | Only for a job whose `RunInstances` failed: the AWS error code, message and job id, and what was requested. There is no instance and so no boot log; this is what `baas jobs download <jobId>` then has to show |
| `cloud-init-output.log` | Runner boot log, uploaded before every termination, a cancelled or timed-out job's included (with the process output and `logs/` so far) — start here when a job fails before producing output |
| `environment.json` | The environment the job measured on, and nothing else: `schemaVersion` (6) and seven groups — `machine` (image version, AMI, instance type), `cpu`, `memory`, `os`, `jvm`, `tools`, `tunables`. No job identity: that is on the job item and its tags. Written **before** the benchmark, so it survives a failed job. Read by `baas jobs show` and compared group by group by `baas jobs diff`, which also compares `packages.txt` when the AMIs differ. |
| `jmh-result.json` | JMH's own machine-readable output, verbatim. The stored item drops `rawData` and `scorePercentiles` for the 400 KB cap, so this is the only place they survive; `resultJsonKey` on the item points here |
| `packages.txt` | `rpm -qa`, split out because several hundred lines would drown the manifest's ~20 fields |
| `logs/**/*.log` | Any `.log` up to 8 levels *below the working directory* (hence the `/app` invariant), keyed by its relative path so same-named files cannot collide. Every benchmark type ships them; async-profiler's own logs land under `logs/async-output/` |
| `input/` | The job's own inputs — `benchmark.jar`, and `runner.jar` only when `--runner-jar` overrode the pinned one. Inside the job prefix, so a consumer has one sub-prefix to skip rather than filenames to special-case |
| `releases/<version>/benchmark-runner.jar` | The version-pinned runner, outside the job tree. Seeded once by the CLI, with a conditional write, and never overwritten; runners can only read it. `releases/`, not `runner/`, because a prefix one character from `jobs/` would need disambiguating in every listing |
| `image-builds/` | Image Builder build logs (written by the build instance, not by a job) |

`environment.json` is the **observation**; `infra/runner-image.yaml` is the **declaration**. The
observation is strictly richer — it carries what the image cannot control (instance type, CPU
model, resolved patch levels) and is the one that answers whether two results are comparable.
Never infer the environment of a past run from the declaration in the working tree.

## Results table

`baas-<prefix>-results`, DynamoDB, on-demand, `DeletionPolicy: Delete` — like the bucket, it leaves
with its deployment: **nothing survives a teardown**. Teardown says so before its prompt and empties
the bucket first (CloudFormation will not delete a non-empty one). Data leaves a deployment before a
teardown through `baas admin deployment export`, which teardown offers before its confirmation, not
by being retained.

Two kinds of item: one per measurement — per JMH benchmark method, per JCStress *job* (JCStress
names only non-passing tests, so per-test items would cover failures alone) — and one per job (*Job
items*, below). No derived index items.

| | |
|---|---|
| `pk` | `RESULT#<project>` — `--project`, or the benchmark JAR's git repository when `git.resolveProject` is on |
| `sk` | `<class>#<method>#<mode>#<timestamp>#<jobId>[#<params>]`, or `JCSTRESS#<timestamp>#<jobId>` |
| GSI `jobId-index` | `gsi1pk` = job ID; the one access path that is not a project sweep |

`mode` is in the sort key because a `-bm thrpt,avgt` run produces two results whose class and method
are identical; without it they differ only by a millisecond and one silently overwrites the other.
Timestamps are fixed-width UTC with exactly three fractional digits — `Instant.toString()` omits
trailing zeros, which makes keys of differing length that misorder as strings, and that surfaces as
missing rows rather than as an error.

`#<params>` is there for the same reason as `mode`, one level down: a `@Param` sweep's variants share
class, method, mode and the job's single timestamp. Without it, `BatchWriteItem` rejected the whole batch
(`Provided list of item keys contains duplicates`), failing the job and taking any other benchmark in the
batch with it; across batches, the last variant silently overwrote the rest. It is `name=value` sorted by
name, joined by `,` (`ResultKeys.formatParams`), and **appended only when present** — not a fixed field
like `mode` — so every key written before it, and every benchmark without params, is byte-identical.
Nothing parses a key, so an optional trailing field costs nothing. The GSI sort key carries no params:
that index is queried by job id alone and need not be unique. Params are a benchmark's identity,
not a tag: they are a separate `params` map on the item, not part of the query surface.

**Keys are constructed only in `ResultKeys`, items only in `MeasurementItemMapper`**, both in
`baas-model`. Hand-encoding either elsewhere is how a query returns zero rows instead of failing to
compile. Non-finite scores are stored as absent: DynamoDB's `N` rejects `NaN`, and JMH reports one
for any single-iteration run.

## Result tagging

**Tags are the entire query surface.** There is no field-per-dimension: `baas results query` filters,
groups and excludes on tags alone, so anything you want to slice by has to be one.

The vocabulary is defined once, in `baas-model`'s `TagKeys`:

| Group | Keys | Set by |
|---|---|---|
| Machine-observed | `imageVersion`, `instanceType`, `jdk`, `jvmVendor`, `cpuModel`, `cpuArch` | The instance, from the same shell variables `environment.json` uses. A caller `--tag` for one of these is **rejected**, not overridden |
| Derived | `type`, `project`, `source` | `baas run`. `type` is reserved like the observed keys, and a `--tag project=` is rejected: `--project` (or git) is its only input, since the runner partitions by the tag while the S3 prefix and job item take `--project`, and two inputs split one job across two projects. `source` alone is caller-overridable by design. `source` is `ci` when the environment says so (`CI` or `GITHUB_ACTIONS` set and not `false`) and `local` otherwise — a `--tag source=nightly` is accepted, not rejected, because how a job was triggered is not something the instance observes |
| Caller-supplied | `commit`, `branch` | `--tag` only — never derived, absent when not passed |
| Convention | `options`, `exclude_from_results` | Free-form. `exclude_from_results=true` is filtered out server-side — except under `--show-excluded` (shown faint) and `--job-id`; the picker also omits a project holding only excluded rows. It is a convention, not a field |

`imageVersion` is the image's *label*: the base version (`1.3.0`), or `1.3.0+ext.<sha256[0:8]>` when
the deployment has an extension. A filter on `1.3.0` therefore never returns an extended image's
results, and one extension is labelled alike in every deployment. `jvmVendor` exists because an
extension may swap the JDK for another vendor's build of the same version, which `jdk` cannot tell
apart.

`branch` used to survive only as a segment of the result path and was stored nowhere. The unified
prefix drops that segment, so what the path stopped carrying the tags now carry — which is the
whole point of tags being the query surface.

Unknown keys pass through — `baas results query` warns only when a `--tag` names a key no row carries.
`baas results query` lists every measurement by default, newest first, 20 at a time in a table and
all of them in `json`/`csv` — a pipe cannot see the stderr note about a cut (same for `jobs list`). `--best-per <tag>`
keeps the best score per `(project, benchmark, params, mode, <tag>)` — the highest for throughput, the
lowest for a time-per-operation mode — and rows carrying no such tag are bucketed rather than dropped.
Project is in the key because `--all-projects` can put two projects' identically named benchmarks side
by side; params because a sweep's variants are different workloads; mode because one benchmark run as
`thrpt` and `avgt` is two numbers in two units — before `jobs-command`, "best" ignored the mode and
always kept the highest score, the slowest result for `avgt`.
The table never shows params in its columns — `-v` prints a `params` line above the `tags` line,
and between them any JMH secondary metrics (`metrics`) and a JCStress job's failing tests (`failed`).
A JCStress row's SCORE is `passed/total`, marked `✗` when a test failed or errored, and JSON carries
`jcstress {total, passed, failed, errors, failedTests}`: the job itself ends `completed` and `baas run`
exits 0 whatever its tests found — the status says JCStress ran, not what it measured (decided 2026-10-20).
`--all-projects` and the project picker are the only `Scan`s; a named project is one `Query`.

### Job items

Every job since `run-status-in-dynamodb` also has one **job item**: `pk = JOB` (one partition for
every project — the questions it answers, *what is in flight* and *what happened to my job*, are
deployment-wide), `sk = <createdAt>#<jobId>`, `gsi1pk = <jobId>`, `gsi1sk = JOB`. Keys come from
`ResultKeys`, the item from `JobItemMapper`, the status vocabulary and terminal set from `JobStatus`.

- **Only the CLI's `launching` reservation creates it**, with every identity field and the
  CLI-side tags. It is written before `RunInstances`, and a reservation that cannot be written
  launches nothing. Every later write — `launched`, `running`, `completed`/`failed:<n>`,
  `timed-out`, `cancelled`, `launch-failed` — is a conditional `UpdateItem` requiring
  `attribute_exists(pk)` and refusing to replace a terminal status: the first outcome wins.
- **The instance writes only `status` and `instanceId`, and no timestamp**, with the key the CLI
  built (`JOB_SORT_KEY`). The shell never rebuilds the key from `CREATED_AT`, which is
  `Instant.toString()` and varies in width. Its guard expression is the CLI's own
  (`JobItemConditions.NOT_TERMINAL`, in `baas-model`), exported verbatim, and every attribute and
  artifact name in the script is substituted from the model when the class loads.
- **`vanished` is never stored**: a non-terminal status whose instance is not pending or running,
  once the job is older than `JobListing.VANISH_GRACE` (5 min). Before that, a job is still
  launching or not yet visible to `DescribeInstances`, and shows its stored status. `baas jobs
  list` decides from one `DescribeInstances` of the live runners and writes nothing.
- **Every measurement reader excludes job items**: both Scans filter `begins_with(pk, RESULT#)`, the
  index query filters `attribute_exists(kind)` — a filter on `gsi1sk` is refused, it is a key
  attribute — and `MeasurementItemMapper.fromItem` throws on anything else, so a reader that forgets
  fails loudly. A CLI from before job items does none of this: once one exists, its
  `--all-projects`, picker and lookups by id fail. Every CLI must be upgraded (accepted 2026-10-02).
- **IAM**: operator and runner may `UpdateItem` only where `dynamodb:LeadingKeys = JOB`; the runner's
  `PutItem`/`BatchWriteItem` only `RESULT#*`. Teardown reads no item — it names in-flight jobs from
  their instances' tags, since the deployer holds no read of the table.

## Adding a benchmark type

A `BenchmarkType` constant in `baas-model` (its name is the runner subcommand, the `baas run`
argument and an entry in `run`'s help, and it names the process-output file), then on the runner a subcommand class in
`commands/` named by that constant, a service in `services/`, an options record in
`options/`, and registration in `TestWrapper`'s `subcommands` list.
`RunnerArgsContractTest` fails until the runner and the model agree.

`--s3-bucket` and `--results-table` are both required on every subcommand; there is no local-disk
storage and no other store. `AWS_ENDPOINT_URL_S3` / `--s3-service-endpoint` and
`AWS_ENDPOINT_URL_DYNAMODB` / `--dynamodb-endpoint` redirect to LocalStack.

## Accepted risks

Decisions already made and deliberately not revisited — don't file these as bugs.

| Area | Position |
|---|---|
| Deployer privilege | `iam:CreateRole` also writes the trust policy, so a deployer can recreate `<prefix>-operator-role` trusting itself with `Action:*` and assume it — the deployer policy is effectively account admin. Accepted: internal tool, development environments, deployer is a trusted developer. A permissions boundary was built and removed as not worth the bootstrap cost. Don't reintroduce one without a multi-principal account to justify it. |
| Deployer Image Builder wildcard | The deployer policy's `ImageBuilder` statement names `…:*/<prefix>-*`, so the deployer of `baas-123456789012` may also update, delete or start the Image Builder resources of a deployment named `baas-123456789012-dev` — never its stack, bucket, table, roles, pointer or AMIs, which are exact (finding X4). Accepted (2026-10-22): it needs one deployment's name to extend another's, the deployer is already effectively account admin (above), and the CLI only composes exact names through `DeploymentNames`. The exact form — seven `*/<prefix>-<suffix>` patterns, ~550 characters, inside the policy budget — was declined as mechanism for a case no deployment has. The `SsmSessionDocument` statement's `document/<prefix>-*` is the same shape and accepted for the same reason. |

| CI can open a shell on a runner | GitHub Actions federates into the same `OperatorRole`, so the session grants reach CI too, although CI never calls `connect`. Accepted (2026-10-22): CI already terminates any runner and writes any job item, and a shell on a box that terminates itself within `timeout + margin` adds nothing it could not already break. The flip side is wanted: a hung CI job is reached from a laptop with `baas jobs connect <jobId>`, by id alone. |
| Operators can end each other's sessions | `TerminateSession`/`ResumeSession` are scoped to sessions whose id starts with the caller's `aws:userid`, but every operator session is named `baas-cli` (finding X6), so "own sessions" means every operator's of that deployment. Accepted (2026-10-22): any operator can already terminate any runner, which ends its sessions anyway; seen live, when one operator session ended another's hung one. Closing X6 narrows it with no change to the policy. |
| Relaxed kernel isolation on the runner | The image sets `perf_event_paranoid=1` and `kptr_restrict=0` so async-profiler can walk kernel stacks *and resolve kernel symbols* — without them the profiler is crippled. This weakens kernel isolation on a box that runs arbitrary benchmark JARs. Accepted: single-tenant, throwaway, terminated within `timeout + margin` (300 s by default). Recorded because these were previously AL2023 defaults that nobody chose; now they are a decision. |
| Runners can terminate each other | `RunnerRole`'s `ec2:TerminateInstances` is scoped by the shared `baas-role=benchmark-runner` tag, not to the calling instance, so code on one runner can kill every concurrent job. Accepted (2026-10-02): only an operator can supply a benchmark JAR, and `OperatorRole` already terminates any runner; the runner role's remaining bucket and table writes are the larger exposure — it can overwrite another job's objects under `jobs/*` (finding S7), though no longer delete them or touch `releases/`. The self-only scoping (`ec2:SourceInstanceARN`) was declined because a subtly wrong condition would silently disable self-termination *and* the watchdog, found only when a paid job hangs. Revisit if more than one team ever shares a deployment. |
| `OperatorRole` trusts the account root | The trust policy names `:root` with no condition, so any principal in the account whose identity policy allows `sts:AssumeRole` on `*` can become an operator. Accepted (2026-10-02): that is AWS's standard same-account delegation, and in practice such principals (admins, `PowerUserAccess`) already hold the EC2, S3 and DynamoDB rights the role grants. A `aws:PrincipalArn` allow-list was declined — every new teammate would need a deployer re-run, SSO role ARNs churn on re-provisioning (the A10 failure), and a wrong pattern locks every operator out. `sts:ExternalId` was declined as a same-account no-op. |
| Re-measuring a historical environment | There is no command for it. A diff showing `jdk: 25.0.4 → 25.0.3` tells you the environment moved, but isolating whether it caused a score change means `git checkout <sha> -- infra/runner-image.yaml && baas admin image build`, which clobbers the current image. Accepted: the question actually asked is "did it change", which `environment.json` answers directly. Git is the archive; nothing in S3 duplicates it. |
| Runner AMI snapshot cost | ~$0.20/month for the single retained 30 GB snapshot. The project previously had **zero** standing cost, so this is a real change in kind, not just degree. Bounded by the one-image-at-a-time rule: a build deregisters its predecessor and deletes that snapshot, so the figure does not grow with the number of builds. Teardown retires the image, so a torn-down deployment costs nothing. |
| Operators can overwrite the pinned runner JAR | `OperatorRole` holds `s3:PutObject` on the whole bucket, so any operator — CI on any ref included — can replace `releases/<version>/benchmark-runner.jar` after it was seeded, and nothing re-checks it: every later job of that version runs whatever is there. Accepted (2026-10-21, review X1): only operators can supply benchmark code at all, which already runs on the same instance with the same rights. A per-launch check against a runner digest built into the CLI was implemented and reverted as not worth its build step. Revisit with the runner-terminates-runners risk above, if more than one team ever shares a deployment. |
| ~~Runner JAR integrity~~ | **Closed, not dropped.** The risk was accepted while verification was impossible — the download happened on a throwaway instance mid-boot, with nothing to verify against. Moving the fetch to the laptop is what changed the trade-off: the CLI now verifies the asset against a `.sha256` published by the same release build, and a mismatch uploads nothing and launches nothing. |
| `baas run` project layout | Assumes a pre-built JAR handed in by `--benchmark-jar`, which is required — `baas run` does not build. Anything that produces a JAR before invoking it is fine; the CLI has no opinion on how. |
| Distribution | Installable via `scripts/install.sh` as of this change. Homebrew tap, jpackage, native image and Docker image were specified but never built — backlog, not decisions. |
