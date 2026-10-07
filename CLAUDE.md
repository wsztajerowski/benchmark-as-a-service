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
| `baas-model` | The stored measurement shape, the key encoding and the tag vocabulary — shared by the CLI and the runner so the two cannot drift. No MongoDB dependency, enforced by the build |
| `fake-jmh-benchmarks`, `fake-stress-tests` | Test fixtures |

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
with their reasons, in `docs/adr/0002`–`0006`. Per-change
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
keep the prefix of the review that filed them (`S`/`A`/`D`/`C`, `P`, `U`, `N`), so citations
still resolve. Read it before proposing security or architecture work. Closing a finding deletes its
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
   notice the difference
2. Process `timeout` around `java -jar benchmark-runner.jar`
3. CLI JVM shutdown hook (`RunCommand`) for Ctrl+C, registered *before* `RunInstances` so an
   interrupt mid-launch can look the instance up by its `baas-job-id` tag. That lookup can
   miss — the instance may not exist yet, and `DescribeInstances` lags — so the instance covers it:
   its `running` write is refused over the recorded `cancelled`, and a refused `running` means it
   ships its boot log and terminates without starting the benchmark. It, the poll cap
   (`timed-out`) and `baas jobs terminate` share `JobSession.stop`: record why under a 5 s timeout,
   then terminate whatever the write did — unless the write was refused because the instance had
   already recorded `completed`/`failed:<n>`, when it is mid-upload and terminates itself. A status
   write never holds a termination back

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
  teammate deliberately emptied can come back that way: accepted. Two pushes in the same seconds are
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
  code — and the egress `private-runner-network` exists to remove.
- **`releases/<version>/benchmark-runner.jar` is seeded once and never overwritten.** That
  immutability is what the whole pinning argument rests on. A corrupted object therefore does not
  self-repair: delete the key and the next job re-seeds it, checksum-verified.
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
  group rule nobody can explain is one somebody restores. Under `--use-existing-vpc` both runner
  and build use the operator's supplied group; no group is created in a foreign VPC.
- **Editing `RunnerSecurityGroup`'s `GroupDescription` replaces the security group.** It is an
  immutable property, so CloudFormation deletes and recreates the resource and the group *id
  changes*. Anything holding the old id is then pointing at a group that no longer exists —
  a deployment's `~/.baas/deployments/<name>.yaml` most obviously, which `baas admin deployment setup` rewrites, but not a job already in
  flight. Observed: removing the 27017 rule also touched the description, and the id moved. Change
  the rules without touching the description unless you intend the replacement. Its text still says
  "443/80" for exactly this reason, and `theRunnerSecurityGroupDescriptionIsNeverEdited` pins it.
- **EC2 tags use the key `baas-role`, not `baas:role`.** `RunnerRole`'s `ec2:TerminateInstances`
  condition is scoped to it, so changing the key breaks self-termination.
- **Root volume is 30 GB gp3, not the AL2023 default.** 8 GB is exhausted by profiling artifacts.
- **`aws.operatorProfile` must not fall back to `aws.deployerProfile`.** That field holds deployer
  credentials; the fallback would silently hand day-to-day commands elevated rights. Don't
  "helpfully" add it. (It was `aws.profile`, and `--aws-profile`, until `multiple-deployments`; the
  old key is still read and is rewritten on the next save.)
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
- **Networking is immutable once a deployment exists.** `--use-existing-vpc` and its three
  companions are honoured on create; on update, `SetupCommand` compares them against the deployed
  values and refuses a difference before submitting anything. They used to be sent unconditionally,
  so on a shared deployment a teammate's plain `baas admin deployment setup` submitted `UseExistingVpc=false`
  and rebuilt the networking underneath everyone. Carrying them forward silently would close the
  hole while discarding a flag the operator typed; refusing names both values instead.
- **Each deployment is one file, `~/.baas/deployments/<name>.yaml`, storing credential *profile
  names*, region, `prefix` and preferences — nothing else, and no secret.** The credentials themselves
  stay in `~/.aws`. One file per deployment, not one file with a map: an older CLI's save drops keys
  it does not know, so a map would vanish the first time an older CLI ran `config set`. The flat
  `~/.baas/config.yaml` of earlier releases is moved there on first look and removed; an older CLI
  then finds nothing and says so, loudly, rather than addressing any deployment. `~/.baas` is not
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
  listing them and hinting `baas config list`; with none only setup proceeds, deriving
  `baas-<accountId>`. Nothing else selects a deployment: no `BAAS_DEPLOYMENT`, no stored default, no
  `config use` — each is invisible state that changes what a command hits. A developer with a
  second deployment reaches it through an alias that carries the flag. `--config-path` is gone for
  the same reason. Teardown deletes the deployment's file, the last one included.
- **`baas config sync` is never derived, though the prefix is derivable.** With nothing configured it
  must be named: a bare sync on a machine with no local state would adopt whatever deployment the
  active credentials imply — in CI, a wrong role or a leftover `AWS_PROFILE` binds the machine to
  another account's deployment and fails later, after provisioning. With exactly one configured, a
  bare sync re-syncs that one. Setup derives and prints; sync adopts what it is told. A deployment
  is named only by the global `--deployment`, never by a per-command `--results-table` or
  `--bucket`, which are gone: an override of one resource could aim a command at one deployment's
  table while its configuration named another.
- **The region is chosen once, by `baas admin deployment setup --region`, and never typed again.** `config sync`
  finds it: the bucket carries the prefix's name, bucket names are global, and `HeadBucket` from any
  region answers a wrong-region request with 301/400 carrying `x-amz-bucket-region` (a 404 is no
  bucket) — under the `s3:ListBucket` the operator already holds. Sync stores the region, CI
  included, so a CI job follows the deployment rather than its `AWS_REGION`. There is no
  `config set --region`: set by hand it aimed a machine at a region with no deployment, and `run`
  then advised building an image there. Moving a deployment is a rebuild in the new region, after
  which every machine re-runs the same `config sync --deployment`.
- **An account-shared deployment makes two concurrent `baas admin image build` runs reachable.**
  The one-image invariant's ordering — repoint the pointer, then deregister the replaced AMI —
  assumes a single builder, which per-identity naming supplied by accident. Two concurrent bakes
  can have the second to finish deregister the AMI the first just published. Deliberately **not**
  guarded; it needs its own change.
- **A deployer policy is prefix-exact, so it covers one deployment only.** Two rendered documents
  are ~8.5k non-whitespace characters against IAM's 5120-character *inline* budget, which is shared
  across every inline policy on the principal — so a second deployment needs its policy attached as
  customer-managed (6144 each), not inline alongside the first. Setup prints the one it needs and
  creates nothing until it is attached.
- **The installer installs released artifacts only, and the repository copy refuses.**
  `scripts/install.sh` carries `BAAS_VERSION_DEFAULT`, rewritten at release time by `release.yml`'s
  `prepareCmd` and never committed back. A checkout copy holds the placeholder and exits naming
  `--version`, the same no-fallback stance `RunCommand` takes on an unreleased build. `--update`
  never installs anything itself: it resolves the newest release and re-executes *that release's*
  installer, so the script installing version X is always version X's own.
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

## What isn't there, and what fails silently

- **`baas run` has no local mode.** It always provisions EC2. The only no-cost way to exercise the
  runner is `./jmh-with-profiler.sh` / `./jmh-with-async.sh` against LocalStack.
- **Measurements live in DynamoDB, and a verbatim `jmh-result.json` now exists in S3.** The item
  carries what a table view needs; `rawData` and `scorePercentiles` are dropped from it and are
  recoverable only from that JSON, via `baas jobs download <jobId>`.
- **A reactor build cannot launch a job.** The CLI pins the runner JAR to its own released version,
  and `0.0.0-semantically-released` names no release — so `baas run` fails immediately, before
  resolving the project or the results table, unless `--runner-jar` is passed. Same no-fallback
  stance as the runner AMI. Every `baas` in
  existence is currently an alias onto a reactor build, so this is the case, not the exception;
  the reactor checkout is now the developer's explicit special case rather than the implicit
  default.
- **The runner refuses an unresolved project.** `getProject()` used to fall back to `"unknown"`,
  and CI had been writing `RESULT#unknown` because of it — a partition nobody queries. It now
  throws, before the benchmark runs.
- **Absent store configuration is a hard failure, and nothing discards measurements.** `baas run`
  resolves the table before the runner-image lookup and before any upload, and `benchmark-runner`
  rejects a missing selection outright. `--no-database` is gone from both: every job records its
  status in the table, so a job without one could not be seen at all, and local runner invocations name a
  LocalStack table. The old behaviour — unset URI selects a no-op store, the job reports success,
  numbers vanish — is gone, and reintroducing any fallback brings it back.
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
  `fake-jmh-benchmarks` on the real runner AMI, so a bad bake fails CI rather than surviving it;
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
  which is the red-CI-job-and-full-bill failure this design exists to remove. What is still uncovered in-process: `RunCommand.call()`'s success path is executed by no
  JVM test (the JSON summary's shape is pinned against `printJobSummary`, and the wiring through
  `call()` only on a path that fails before AWS).
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
  Integration tests pin `mongo:7.0.5`; DynamoDB runs on LocalStack. One store contract suite runs
  against both adapters — a behavioural difference between them is a test failure, not a discovery.
- **LocalStack is pinned to `4.14.0`, the newest release confirmed (2026-10-05) to start without an account.** `2026.09.0`
  (and so `latest`) exits 55 without `LOCALSTACK_AUTH_TOKEN`, and this repository holds no secrets. The pin
  replaced `0.12.16`, which took 25–65 s to start against Testcontainers' 60 s wait and stored the SDK's
  `aws-chunked` upload framing as object bytes — `StorageServiceIT` had asserted that corrupted size.
- **A JCStress job's mode is `--mode`, not `-m`.** The runner forwards it to JCStress as `-m`, but
  on the runner `-m` is `--mongo-connection-string` on every subcommand, so `baas run jcstress -- -m
  sanity` is read as a Mongo URI. The short form can follow once `retire-mongodb` frees `-m`.
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
  the build-instance role. Deployed by `baas admin deployment setup`, bundled into the CLI as the
  classpath resource `/templates/cf-template-core.yaml`. `UseExistingVpc` + `ExistingVpcId` /
  `ExistingSubnetId` / `ExistingSecurityGroupId` reuse existing networking. Eight parameters
  describe the runner image (`RunnerImageParameters.ALL`): the base, parent, extension and contract
  documents and their versions, and the label. Each component travels as a parameter value, so it
  must stay under CloudFormation's 4096-byte cap — unit-tested for base and contract, refused before
  submission for an extension. Three more —
  `GitHubOidcProviderArn`, `GitHubOrg`, `GitHubRepo` — declare the conditional federated principal
  on `OperatorRole`'s trust policy and create no resource. `GitHubRepo` is a `CommaDelimitedList`
  of **already-composed** `repo:<org>/<name>:*` subject patterns: `SetupCommand` builds them,
  because CloudFormation cannot iterate a list and every in-template trick for it leans on
  `Fn::Sub` re-scanning text substituted into it, which it does not do.
- **`runner-image.yaml`** — the measurement environment's base: base version, pinned parent
  release, Corretto and async-profiler versions, kernel tunables. Ships in the JAR as
  `/templates/runner-image.yaml` because both `setup` and `build-image` render it at runtime.
- **`cf-template-ci.yaml`** — the `GithubOidc` identity provider and **nothing else**. **Not
  deployed by the CLI** — deploy by hand, with an identity above the deployer, since
  `deployer-policy.json` scopes `iam:Get*`/`iam:List*` to roles and never to `oidc-provider/*`.
  Deploy order inverts: the provider is account-global (one per issuer URL per account), so it
  comes first and its ARN is handed to `baas admin deployment setup`; an account that already has one must
  reuse that ARN rather than deploy this stack, which fails with `EntityAlreadyExists`.
  `WorkflowRole` is deleted — GitHub Actions federates straight into `OperatorRole`, because a
  role-chained session is capped at 60 minutes by STS whatever `MaxSessionDuration` says, against
  a 7200 s default benchmark timeout. That failure was not an error but a red CI job with a good
  measurement, an un-terminated instance and a full EC2 bill.

IAM is split deliberately: `deployer-policy.json` → `BaasCliDeployerPolicy`, elevated, only for
`baas admin deployment setup`/`build-image`/`teardown`; `operator-policy.json` → the stack-created
`BaasCliOperatorRole`, narrow, for `baas jobs`/`results`/`config`. `operator-policy.json` and `cf-template-ci.yaml` reach the
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

**BaaS targets the commercial `aws` partition only.** `deployer-policy.json` writes `arn:aws:`
literally while `cf-template-core.yaml` uses `${AWS::Partition}`; the template's form is free and
harmless, but making the policy partition-aware alone would imply support nothing else has — the
CLI fetches the runner JAR from GitHub releases, for one. Not a gap to close piecemeal.

**`deployer-policy.json` is a template, never a policy.** It carries `${ACCOUNT_ID}` / `${REGION}`
/ `${PREFIX}` placeholders and is rendered per caller by `DeployerPolicyRenderer` — every resource
it names is prefix-exact, so two developers cannot reach each other's stack, bucket or SSM
parameter. Attaching the file as-is grants nothing. There is no command that prints it:
`baas admin deployment setup` renders it for the deployment it is about to create — `--region`
included, since the region is in seven of its ARNs and conditions — and, when the caller's rights
fall short, prints it on standard output and stops having created nothing.

`baas admin deployment setup`'s preflight (opportunistic `SimulatePrincipalPolicy`, plus translating any
`AccessDenied` into the rendered policy) is a **UX affordance, not a control** — anyone holding the
policy can call IAM directly. Don't try to make it one.

GHA values whose origin isn't obvious from the workflow files. All are plain `vars.`, no secrets:
a role ARN is not sensitive, and nothing else is left to hold. `WORKFLOW_ROLE_ARN`,
`RUNNER_ROLE_NAME`, `GHA_EC2_PAT`, `SUBNET_ID`, `SECURITY_GROUP_ID` and `RESOURCE_NAME_PREFIX` are
deleted along with the workflows that read them.

| Name | Source |
|---|---|
| `OPERATOR_ROLE_ARN` | Core stack output `OperatorRoleArn` — the role CI federates into directly |
| `CORE_STACK_NAME` | The deployment `baas admin deployment setup` printed (e.g. `baas-381492019823`); passed to `baas config sync --deployment` |
| `AWS_REGION` | The deployment's region |

## S3 result layout

One job is one prefix: `<result-path>` = `jobs/<project>/<jobId>/`, where `jobId` is
`<UTC instant, ISO basic, milliseconds>Z-<8 hex>` (e.g. `20260820T174432812Z-a3f9c21b`) — fixed 28
characters, time-ordered so a listing reads chronologically, entropy-suffixed so two jobs starting
in the same millisecond cannot collide. Nothing parses it; the format is a readability convention,
not a contract. Built only by `JobLayout`/`JobId` in `baas-model`, never by hand.

Per-type stdout lands in `jmh-output.txt`, `jmh-profiler-output.txt`, `jmh-with-async-output.txt`,
or `jcstress-output.txt`; profiling artifacts go under `<fully.qualified.BenchmarkName-Mode>/`. The
non-obvious entries:

| Key | Meaning |
|---|---|
| `launch-error.txt` | Only for a job whose `RunInstances` failed: the AWS error code, message and job id, and what was requested. There is no instance and so no boot log; this is what `baas jobs download <jobId>` then has to show |
| `cloud-init-output.log` | Runner boot log, uploaded before self-termination — start here when a job fails before producing output |
| `environment.json` | The environment the job measured on, and nothing else: `schemaVersion` (6) and seven groups — `machine` (image version, AMI, instance type), `cpu`, `memory`, `os`, `jvm`, `tools`, `tunables`. No job identity: that is on the job item and its tags. Written **before** the benchmark, so it survives a failed job. Read by `baas jobs show` and compared group by group by `baas jobs diff`, which also compares `packages.txt` when the AMIs differ. |
| `jmh-result.json` | JMH's own machine-readable output, verbatim. The stored item drops `rawData` and `scorePercentiles` for the 400 KB cap, so this is the only place they survive; `resultJsonKey` on the item points here |
| `packages.txt` | `rpm -qa`, split out because several hundred lines would drown the manifest's ~20 fields |
| `logs/**/*.log` | Any `.log` up to 8 levels *below the working directory* (hence the `/app` invariant), keyed by its relative path so same-named files cannot collide. Every benchmark type ships them; async-profiler's own logs land under `logs/async-output/` |
| `input/` | The job's own inputs — `benchmark.jar`, and `runner.jar` only when `--runner-jar` overrode the pinned one. Inside the job prefix, so a consumer has one sub-prefix to skip rather than filenames to special-case |
| `releases/<version>/benchmark-runner.jar` | The version-pinned runner, outside the job tree. Seeded once by the CLI and never overwritten. `releases/`, not `runner/`, because a prefix one character from `jobs/` would need disambiguating in every listing |
| `image-builds/` | Image Builder build logs (written by the build instance, not by a job) |

`environment.json` is the **observation**; `infra/runner-image.yaml` is the **declaration**. The
observation is strictly richer — it carries what the image cannot control (instance type, CPU
model, resolved patch levels) and is the one that answers whether two results are comparable.
Never infer the environment of a past run from the declaration in the working tree.

## Results table

`baas-<prefix>-results`, DynamoDB, on-demand, `DeletionPolicy: Delete` — like the bucket, it leaves
with its deployment: **nothing survives a teardown**. Teardown says so before its prompt and empties
the bucket first (CloudFormation will not delete a non-empty one). Data leaves a deployment before a
teardown through export (the queued `export-before-teardown` change), not by being retained.

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
`baas results query` lists every measurement by default, newest first, 20 at a time. `--best-per <tag>`
keeps the best score per `(project, benchmark, params, mode, <tag>)` — the highest for throughput, the
lowest for a time-per-operation mode — and rows carrying no such tag are bucketed rather than dropped.
Project is in the key because `--all-projects` can put two projects' identically named benchmarks side
by side; params because a sweep's variants are different workloads; mode because one benchmark run as
`thrpt` and `avgt` is two numbers in two units — before `jobs-command`, "best" ignored the mode and
always kept the highest score, the slowest result for `avgt`.
The table never shows params in its columns — `-v` prints a `params` line above the `tags` line.
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
  (`DynamoDbJobRecorder.NOT_TERMINAL`), exported verbatim.
- **`vanished` is never stored**: a non-terminal status whose instance is not pending or running.
  `baas jobs list` decides that from one `DescribeInstances` of the live runners and writes nothing.
- **Every measurement reader excludes job items**: both Scans filter `begins_with(pk, RESULT#)`, the
  index query filters `attribute_exists(kind)` — a filter on `gsi1sk` is refused, it is a key
  attribute — and `MeasurementItemMapper.fromItem` throws on anything else, so a reader that forgets
  fails loudly. A CLI from before job items does none of this: once one exists, its
  `--all-projects`, picker and lookups by id fail. Every CLI must be upgraded (accepted 2026-10-02).
- **IAM**: operator and runner may `UpdateItem` only where `dynamodb:LeadingKeys = JOB`; the runner's
  `PutItem`/`BatchWriteItem` only `RESULT#*`. Teardown reads no item — it names in-flight jobs from
  their instances' tags, since the deployer holds no read of the table.

## Adding a benchmark type

A subcommand class in `commands/`, a service + builder in `services/`, an options record in
`services/options/`, and registration in `TestWrapper`'s `subcommands` list.

Storage is optional at runtime (no `--s3-bucket` → `LocalStorageService`); the results store is
not. Exactly one of `--results-table` or `--mongo-connection-string` must be named, and
both-or-neither is an error. `AWS_ENDPOINT_URL_S3` / `--s3-service-endpoint` and
`AWS_ENDPOINT_URL_DYNAMODB` / `--dynamodb-endpoint` redirect to LocalStack.

## Accepted risks

Decisions already made and deliberately not revisited — don't file these as bugs.

| Area | Position |
|---|---|
| Deployer privilege | `iam:CreateRole` also writes the trust policy, so a deployer can recreate `<prefix>-operator-role` trusting itself with `Action:*` and assume it — the deployer policy is effectively account admin. Accepted: internal tool, development environments, deployer is a trusted developer. A permissions boundary was built and removed as not worth the bootstrap cost. Don't reintroduce one without a multi-principal account to justify it. |

| Relaxed kernel isolation on the runner | The image sets `perf_event_paranoid=1` and `kptr_restrict=0` so async-profiler can walk kernel stacks *and resolve kernel symbols* — without them the profiler is crippled. This weakens kernel isolation on a box that runs arbitrary benchmark JARs. Accepted: single-tenant, throwaway, terminated within `timeout + margin` (300 s by default). Recorded because these were previously AL2023 defaults that nobody chose; now they are a decision. |
| Runners can terminate each other | `RunnerRole`'s `ec2:TerminateInstances` is scoped by the shared `baas-role=benchmark-runner` tag, not to the calling instance, so code on one runner can kill every concurrent job. Accepted (2026-10-02): only an operator can supply a benchmark JAR, and `OperatorRole` already terminates any runner; the runner role's bucket and table writes are the larger exposure. The self-only scoping (`ec2:SourceInstanceARN`) was declined because a subtly wrong condition would silently disable self-termination *and* the watchdog, found only when a paid job hangs. Revisit if more than one team ever shares a deployment. |
| `OperatorRole` trusts the account root | The trust policy names `:root` with no condition, so any principal in the account whose identity policy allows `sts:AssumeRole` on `*` can become an operator. Accepted (2026-10-02): that is AWS's standard same-account delegation, and in practice such principals (admins, `PowerUserAccess`) already hold the EC2, S3 and DynamoDB rights the role grants. A `aws:PrincipalArn` allow-list was declined — every new teammate would need a deployer re-run, SSO role ARNs churn on re-provisioning (the A10 failure), and a wrong pattern locks every operator out. `sts:ExternalId` was declined as a same-account no-op. |
| Re-measuring a historical environment | There is no command for it. A diff showing `jdk: 25.0.4 → 25.0.3` tells you the environment moved, but isolating whether it caused a score change means `git checkout <sha> -- infra/runner-image.yaml && baas admin image build`, which clobbers the current image. Accepted: the question actually asked is "did it change", which `environment.json` answers directly. Git is the archive; nothing in S3 duplicates it. |
| Runner AMI snapshot cost | ~$0.20/month for the single retained 30 GB snapshot. The project previously had **zero** standing cost, so this is a real change in kind, not just degree. Bounded by the one-image-at-a-time rule: a build deregisters its predecessor and deletes that snapshot, so the figure does not grow with the number of builds. Teardown retires the image, so a torn-down deployment costs nothing. |
| ~~Runner JAR integrity~~ | **Closed, not dropped.** The risk was accepted while verification was impossible — the download happened on a throwaway instance mid-boot, with nothing to verify against. Moving the fetch to the laptop is what changed the trade-off: the CLI now verifies the asset against a `.sha256` published by the same release build, and a mismatch uploads nothing and launches nothing. |
| MongoDB | Retained in `benchmark-runner`, connect-only, and **no live user is known**. The standalone justification named java-wonderland, which sits on a branch frozen 2024-06-22 that cannot run today's runner at all: `--s3-result-prefix` is gone, no `--project` makes `getProject()` throw, and naming no store fails the exactly-one-of check. So this is no longer a settled trade — retirement is an open decision, deserving its own change and spec delta rather than a rider on someone else's. `baas` itself never provisions, selects or reaches it: no SSM parameter, no IAM grant, no egress rule. |
| `baas run` project layout | Assumes a pre-built JAR handed in by `--benchmark-jar`, which is required — `baas run` does not build. Anything that produces a JAR before invoking it is fine; the CLI has no opinion on how. |
| Distribution | Installable via `scripts/install.sh` as of this change. Homebrew tap, jpackage, native image and Docker image were specified but never built — backlog, not decisions. |
