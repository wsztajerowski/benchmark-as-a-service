# Design

## Context

See `proposal.md` for the motivation and [`exploration.md`](exploration.md) for every decision (D1–D20)
with its rejected alternatives. This document covers how those decisions land in the code; it cites
D-numbers rather than restating them.

Today `SetupCommand.call()` is one method of ~250 lines (`SetupCommand.java:102`) that builds its AWS
clients inline: load config → `GetCallerIdentity` → bucket-region check → render policy →
`--policy-file` → preflight → refusal → `deploy()`, which runs the name checks, the stack create or
update, and saves the file at the very end (`:367`). Nothing about its ordering is unit-testable
(`setup-prerequisites` W1). The pieces the steps need already exist and are tested on their own:
`DeployerPreflight`, `BucketProbe` (sealed: `Absent | Reachable | Forbidden`),
`CloudFormationService` (`stackStatus`, `createStack`, `updateStackParameters`,
`StackOperationException.permissionFailure()`), `ImageBuilderService` (`publish`, `build`, `retire`),
`OperatorSession`, `administratorCommands`, `nameConflict`, `bucketBlocksSetup`. Teardown shows the
prompt idiom (`answerReader`, `Console.interactive()`). The module has no Mockito; fakes are written
by hand (`OperatorSession.Aws`).

## Goals / Non-Goals

**Goals:**
- The walkthrough's order, waiting, jump-backs and stop-versus-wait behaviour are unit-tested without
  AWS, and each step's probe and action are tested against one hand-written fake.
- A non-interactive run behaves as setup does today on every path the existing tests cover, apart from
  the intended changes (D8, D20, the removed ARN option).
- Every prompt has a flag, which a round-trip test pins (D11).

**Non-Goals:**
- No prompt library, no full-screen UI, no arrow-key menus: prompts are line reads.
- No saved progress, no resume token, no lock file (D1).
- No change to `config sync`, teardown's flow, or day-to-day commands beyond their hints (D7, D12).
- No GitHub API, `gh` or `aws` subprocess (D3).
- No guard against two concurrent `admin image build` starts in the same seconds (D19 leaves that race).

## Decisions

### The walkthrough is a list of steps run by a small engine, each step a probe and an outcome

A `SetupStep` has a name and `run(SetupContext)` returning one of:
`Met`, `Done` (setup acted), `WaitForUser(explanation)` (a manual step), `Stop(exitCode, message)`, or
`GoTo(step)` (the jump-backs of D17 and D18). The engine, `SetupWalkthrough`, iterates the eleven
steps in order. On `WaitForUser` it prints the explanation and, interactively, reads a line and runs
the same step again. Non-interactively it returns exit 1. On `GoTo` it continues from the named
step, so step 4 runs again after a name change or a stack permission failure. `SetupContext` carries
the answers gathered so far: deployment, identity, region, federation, the preflight result, the
"plan confirmed" flag, and whether anything changed. It is the one mutable object; steps read
earlier answers from it and never from each other.

The engine is tested with stub steps (order, re-run on Enter, stop when not interactive, `GoTo`,
no automatic re-probe) and holds no AWS knowledge. `SetupCommand` keeps the picocli options, builds
the context and the steps, and returns the engine's exit code.

*Rejected:* one long method with prompts inserted (today's shape — untestable, and the jump-backs
would become nested loops); a state machine with explicit transitions per pair of steps (eleven steps
and three jump-backs do not need a transition table, and a list reads in the order the user meets it).

### One `SetupAws` port returns domain results, and its fake is the steps' test double (D9)

`SetupAws` holds every AWS call setup makes, with domain return types:
`callerIdentity()`, `bucketRegion(name)` → `BucketProbe`, `tableExists(name)`,
`preflight(callerArn, policy)` → `Met | Denied(actions) | NotChecked`, `stackStatus(name)`,
`submitCreate(...)` / `submitUpdate(...)` / `awaitStack(name)`, `stackOutputs(name)`,
`assumeOperatorRole(name)`, `oidcProvider(arn)` → `Absent | Present(clientIds)`,
`createOidcProvider(...)`, `imageState(...)` and `buildOrAdoptImage(...)`. `AwsSetupAws` is a thin
adapter over the existing services, built from one `AwsClientFactory`. `FakeSetupAws` is
hand-written, as `OperatorSession`'s fake is. D8's evidence reads directly off
`PreflightResult.Met` and the stack outcome, so *not checked is never shown as met* holds by type.

### Prompts go through one `Prompter`, built on `Console` and an injectable line reader

`Prompter.ask(question, default)`, `confirm(question, defaultYes)` and `choose(question, options)`
print through `Console` (payload discipline: prompts are interactive-only, so they never reach a
redirect) and read through a `Supplier<String>`, which is teardown's `answerReader` pattern. A `null`
read (closed stdin) answers with the default for questions and *no* for confirmations, so a lost
terminal never confirms a plan. Every prompt is keyed by the option that answers it. The step asks
only when that option is absent, which makes D10's *a flag answers its prompt in advance* structural.

### The repeat command is rendered from the context and parsed back in a test (D11)

`RepeatCommand.render(SetupContext)` emits `baas --deployment <name> admin deployment setup
--non-interactive` plus `--region` (create only), `--deployer-aws-profile`, `--github-org`/`--github-repo`
or `--revoke-github-oidc`, and `--build-image` when an image was built. The test parses the rendered
line with the real picocli `CommandLine` into a fresh `SetupCommand` and asserts that the options
equal the answers. That also fails the build if a prompt gains no flag.

### On create, the file is written between `CreateStack` returning and the waiter (D20)

`CloudFormationService.createStack` currently sends the request and waits in one call. It is split
into `submitCreate` (returns once AWS accepted the request) and `awaitCreate` (the waiter and the
failure report); `createStack` stays as their composition for any other caller. Step 8 calls
`submitCreate`, saves the deployment file (name and region; the deployer profile only if the preflight
was `Met`, D8), then awaits. A refused `CreateStack` throws before the save. After the stack completes,
step 11 stores a profile that only the create proved (D8). An update saves at the end as today,
because its file already exists.

The `ROLLBACK_COMPLETE` recovery is unchanged: delete, wait, create. With a file now present,
`loadForSetup()` reads it like any existing deployment, and step 1 offers it.

### Step 8 waits on an in-progress stack before deciding create or update (D19)

The probe reads `stackStatus`. A status ending `_IN_PROGRESS` is awaited with the matching SDK
waiter (create, update, delete or rollback). The probe then re-reads the status. Only then does the
step choose between create (absent or `ROLLBACK_COMPLETE`) and update. A wait that ends in a
rollback is reported with the stack's events, as a failed operation is today.

### The image is adopted by recipe version, inside `ImageBuilderService` (D19)

`ImageBuilderService.publish` gains a first phase. It lists the pipeline's images
(`ListImagePipelineImages`, covered by the existing `imagebuilder:List*` grant) and takes the newest:
- **In progress** (the existing `IN_PROGRESS` list): wait for it with the same poll `build()` uses.
- **Available, and its AMI is not the one the pointer names:** adopt it, meaning identity tags,
  pointer, and retirement of the replaced AMI. This is the second half `publish` already has.
- **Otherwise:** start a build as today.

A found build is adopted only when its image version — the recipe version in its ARN — equals the
recipe version the stack now declares. A build of another version was started before an extension
push, and adopting it would publish the wrong extension. That build is retired with `retire(amiId)`
and a new one is started. This rule was not in the exploration. It was added while writing the
spec, because adopting by "newest" alone would publish a superseded extension after
`admin image build --extension`. It keeps the image invariant: nothing is published that the
command would not have built.

`admin image build` gets the same behaviour, since it calls `publish`. Setup's interactive question
("A build started at HH:MM is running; wait for it?") lives in the step, not the service. Answering
*no* leaves the build running and ends the run at step 11, naming `admin image build`.

*Comparability:* unchanged. An adopted image is the same recipe version, so the same base,
extension and contract the command would have built. It carries the same tags. A result's
`imageVersion` tag and `environment.json` are produced on the instance as before.

*Rejected:* cancelling the build on Ctrl+C. It fails under `kill -9` and a closed lid, and it
needs `imagebuilder:CancelImageCreation` (D19).

### The OIDC provider ARN is composed in the template and in the CLI, never passed (D4)

`cf-template-core.yaml` drops `GitHubOidcProviderArn`. The federated principal becomes
`!Sub arn:${AWS::Partition}:iam::${AWS::AccountId}:oidc-provider/token.actions.githubusercontent.com`.
`updateStackParameters` already drops deployed parameters the template no longer declares, so old
stacks update cleanly. Step 6 composes the same ARN from the caller's account, calls
`GetOpenIDConnectProvider` and creates the provider with `Url` and `ClientIDList=[sts.amazonaws.com]`
only when it is absent. The policy statement is `GitHubOidcProvider`, with those two actions on that
ARN (~211 characters). `validateFederationOptions` loses the ARN from its "all of" rule: org and
repos together, or neither.

### Hints are rendered by one helper that always adds `--deployment` (D7, D12)

`Hints.command(deployment, "admin image build")` returns `baas --deployment <name> admin image build`.
It is used at setup's next steps, `RunCommand`'s no-image refusal (which also says "deployment"
instead of "account") and results hint, `ConfigShowSubcommand`, and every existing `→` hint naming a
deployment-scoped command. `→ choose one: baas config list` stays bare, since no deployment is
resolved there. The `--deployment` description in `BaasApp` takes D12's text.

### Plan decline exits 1, like teardown's abort

Declining the plan exits 1, so a script that pipes `yes n` can tell it from a finished setup.
Teardown's abort uses the same convention (`TeardownCommand.java:180`).

## Risks / Trade-offs

- **[Largest interactive surface in the CLI]** → The steps reuse the existing probes. The engine has
  no AWS knowledge and is tested alone. Prompts are line reads. No library.
- **[A probe reports met wrongly]** → Every probe is read-only and conservative.
  `NotChecked` is its own result, and the GitHub variables are never probed.
- **[Re-checks hit IAM's throttle]** → Re-checks happen only on Enter, one preflight (13 calls,
  ~5 s) per press, never in parallel and never in a loop (`setup-prerequisites` facts).
- **[Every existing deployer must publish a new policy version]** → The policy gains
  `GitHubOidcProvider`, which is unconditioned and so simulated for everyone, including deployers
  without CI. The first setup after upgrading reports two missing actions. Interactively the
  hand-over now waits instead of exiting. This is the same one-off cost as 7.1's
  `AssumeOwnOperatorRole`.
- **[A stray file after a rolled-back create that is never retried]** → It is visible in
  `config list` and named in "two or more" refusals. Teardown removes it and the stack (D20).
- **[Adopting an image Image Builder still lists after a manual deregister]** → Adoption checks
  that the AMI exists (`describeImage`) before publishing, as `retireFailedOutput` already does.
  A missing AMI falls through to a new build.
- **[Teardown leaves an unpublished build's AMI]** → Teardown deletes image *records*, which can
  orphan the AMI of a build nobody adopted. This exists today and is not widened here. Recorded
  for the codebase-review triage rather than fixed in this change.
- **[`baas-admin-wiktor`'s policy may be inline]** → Nothing breaks while it stays under 5120.
  Step 4's hand-over is the migration path, and an IAM admin must check how it is attached (D13).

## Migration Plan

1. Land in `next-release`, as every queued change does. Not marked as a breaking change (user,
   2026-10-14): `--oidc-provider-arn` is removed, but the only known scripts passing it are this
   project's own, and a stale one fails loudly as an unknown option.
2. Both local deployments, `baas-development` and `baas-381492019823`, are torn down and recreated
   from scratch by the new setup (user, 2026-10-14), so the provider and the federation are tested as
   one flow through a single stack rather than as an upgrade of an old one. The deployer policy's new
   version is published first, by an IAM administrator. Another user's existing deployment upgrades in
   place: the first setup reports the two new actions missing and hands the new version over.
3. Existing `cf-template-ci.yaml` stacks are left as they are. Their provider is reused.
4. Rollback means installing the previous release. A stack updated by the new template no longer
   carries `GitHubOidcProviderArn`. The old CLI sends it as `UsePreviousValue`, which CloudFormation
   refuses for a parameter the stack lacks, so an old CLI's update of such a stack must name all
   three federation options again. The trust statement itself is unaffected.

## Open Questions

None. Every decision was settled in the exploration. The one rule added while writing the spec
(adoption by recipe version) is recorded above, with its reason.
