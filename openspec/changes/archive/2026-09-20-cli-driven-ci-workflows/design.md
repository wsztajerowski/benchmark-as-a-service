# Design

## Context

See `proposal.md` — Why. The session record is `explore.md`, which settles fourteen decisions and
records the facts each was checked against; `assumptions.md` is its input and is corrected there in
two places. This document carries those decisions as design, plus the one open question the propose
step closed.

Three constraints shape everything below.

- **`private-runner-network` and a self-hosted Actions runner are structurally incompatible.** That
  change moves benchmark instances onto a subnet with no internet gateway route and narrows egress
  to the S3 and DynamoDB prefix lists on 443; an Actions runner agent must reach `github.com`.
  Keeping the agent means keeping a second, deliberately less-isolated network path — the thing that
  change exists to delete. This is why CI calling `baas run` is a sequencing constraint rather than
  a preference.
- **`baas-cli` has no endpoint-override support at all.** `AwsClientFactory` takes a region and an
  optional profile; there is no `AWS_ENDPOINT_URL` or `endpointOverride` anywhere in
  `baas-cli/src/main/java`. A workflow whose one real step is `baas run` therefore cannot be driven
  under `act` against LocalStack, which is why the `act` harness is deleted rather than retargeted.
- **Tags are the entire query surface.** `baas results` filters, groups and excludes on tags alone,
  so "was this run triggered by CI" has to be a tag; IAM answers who may call `RunInstances`, never
  which rows came from where.

**Effect on comparability with existing results.** This change touches user-data only to forward one
additional `--tag`. It changes no image version, no parent AMI, no pinned tool version, no kernel
tunable, no instance type and nothing the runner executes. Results produced after it remain directly
comparable with results produced before it. The one substantive comparability *gain* is that CI and
laptop runs now boot the same AMI from the same installation by construction (see "CI runs on the
existing installation"), and `source` makes that checkable with `--group-by source` rather than
assumable.

## Goals / Non-Goals

**Goals:**

- CI exercises the same code path a developer's laptop does, so a regression in `baas run` cannot
  pass CI.
- The e2e workflow becomes provable on demand and green, from a state where it has never passed.
- The federation identity survives an ordinary `baas admin setup` re-run rather than being silently
  revoked by it.
- Leave `private-runner-network` nothing to carve out.

**Non-Goals:**

- Shipping a consumer-facing artifact for the install-the-CLI contract (see Open Questions).
- End-to-end coverage of `jcstress` or plain `jmh`, or restoring the `act`/LocalStack workflow test.
- Closing S9 (`OperatorRole`'s account-root trust), despite this change editing that trust policy.
- Retiring the runner's MongoDB adapter.
- A nightly schedule for the self-test.

## Decisions

### CI calls `baas run` instead of reimplementing it

`assumptions.md` framed this as one option among four and argued it *dominates*; the constraint
above makes it *forced*. The rewritten `e2e-cloud-test.yml` is one `ubuntu-latest` job: checkout,
`setup-java`, `mvn package`, `configure-aws-credentials`, `baas config sync --core-stack-name
<name>`, `baas run jmh-with-async --format json …`, then assertions driven by the run id.

*Rejected:* fixing the six blockers in place (two orchestrators that must stay behaviourally
identical, and `private-runner-network` still blocked); a hybrid keeping `machulav/ec2-github-runner`
for the instance (same blockage, plus the PAT); pointing the existing workflows at DynamoDB without
restructuring (leaves twelve bash-implemented responsibilities in place).

**CI must pass `--runner-jar`.** A reactor build's version is `0.0.0-semantically-released`, which
names no release, so `baas run` refuses to resolve a pinned runner JAR. This is the documented
no-fallback stance, not a gap to work around: CI builds the runner and hands it in, which also means
CI tests the runner it just built rather than the last released one.

### `benchmark-runner.yml`, three sub-workflows and the `act` harness are deleted

`benchmark-runner.yml` is the only `workflow_dispatch` entry point into the three sub-workflows, so
deleting it leaves `e2e-cloud-test.yml` as their sole caller — and the decision above stops that
caller calling them. All three are then orphaned. The `act` harness's subject is
`exec-single-benchmark.yml`, so it goes too.

What the `act` harness actually covered does not vanish, it relocates: JAR staging, profiler
provisioning, invocation assembly and result-key layout are `RunnerJarResolver`, `S3UploadService`,
`UserDataScriptBuilder`, `RunLayout`/`ResultKeys` and the store contract suite — all already
Java-testable, all already tested.

*Rejected:* keeping `benchmark-runner.yml` as a manual escape hatch. Its value is dispatching an
arbitrary benchmark at an arbitrary ref, which `baas run` does better from a laptop and without a
self-hosted runner carrying an instance profile (finding S1's entire exploit surface).

### CI runs on the existing `baas-3q7i7s65` installation

One installation means one AMI bake, one retained snapshot, and a measurement environment CI and
laptop share by construction. A second installation would double the standing snapshot cost — a cost
CLAUDE.md's accepted-risks table records as a change in kind for this project, not merely of degree —
and silo CI results into a table nobody queries.

*Rejected:* a dedicated CI installation. Its only real advantage is blast-radius isolation, bought
with a second bake, a second snapshot, incomparable results and a second thing to keep in sync.

### GitHub OIDC federates directly into `OperatorRole`, and `WorkflowRole` is deleted

*Rejected alternative:* keep `WorkflowRole` as the federation target and chain into `OperatorRole`.
It loses on a hard AWS limit: a role-chained session is capped at 60 minutes, `MaxSessionDuration` is
set nowhere in `infra/` so both roles default to 3600 s, and `benchmarkTimeoutSeconds` defaults to
7200. `configure-aws-credentials` writes static `AWS_*` variables that never refresh, and `baas run`
polls `run-status` for the life of the run. The failure is worse than an error:

```
  t=0      upload, launch, begin polling
  t=60m    session expires → polling dies; the CLI shutdown hook needs credentials too,
           so the instance is NOT terminated
  t=60m+   benchmark completes, runner uploads and writes its item (instance profile, unaffected)
  t≈2h05m  the shell watchdog terminates — the only termination layer that works here
  ─────────────────────────────────────────────────────────────────────────────────
  CI job RED. Measurement GOOD, sitting in the table. Full EC2 bill paid.
```

`WorkflowRole` has no remaining justification either: the only grants it holds that `OperatorRole`
lacks are `ec2:ReplaceIamInstanceProfileAssociation`, `ec2:AssociateIamInstanceProfile` and the
`/aws/service/ami-amazon-linux-latest/*` SSM path — all three exist solely for `machulav/ec2-github-runner`
and all three die with it. Its extra hop was never a security boundary: its chaining statement is
unconditional, so anything able to assume it can assume `OperatorRole` one call later. Its real value
was optionality — a seam you could later narrow — traded here against a duplicated grant list that
has already drifted once.

Merging trades scope in both directions, deliberately. CI *gains* the region and
`c5/c6i/c7i/m5/m6i/m7i.*` instance-family conditions on `ec2:RunInstances` that `WorkflowRole` never
had (this is what closes finding S3), and *loses* `WorkflowRole`'s `${Bucket}/runs/*` scoping on
`s3:PutObject`, since `OperatorRole` holds the whole bucket plus `DeleteObject`. The widening is
unexercised: CI passes `--runner-jar`, so it writes under `runs/…/input/` and never touches
`releases/`.

**`GitHubRepo` is a `CommaDelimitedList`** rendering several `StringLike` values. One installation
serving two repositories is otherwise a template migration, and the consumer model makes that
plausible. Cheap now, expensive later.

**The deployer policy's new `iam:UpdateAssumeRolePolicy` grant is not an escalation.** CLAUDE.md's
accepted-risks table already records that `iam:CreateRole` writes a trust policy, making the deployer
effectively account admin; this adds ~35 bytes against ~300 of headroom under the 4608-character test
cap and no new capability. That row is settled and is not reopened here.

### `baas admin setup` carries the federation parameters forward, and revokes only when told to

`SetupCommand.deploy` currently assembles a full parameter map and calls `createOrUpdateStack`, so a
parameter it does not send falls back to `Default: ""` — the condition evaluates false, the trust
statement vanishes and CI loses access on the next setup, with no error anywhere because the deploy
succeeds.

The fix is a mechanism this codebase already has. `CloudFormationService.updateStackParameters`
sends only the parameters the caller names and carries every other one forward with
`UsePreviousValue`, and its Javadoc records the identical failure from the other direction: `baas
admin build-image` owns three parameters and knows nothing about the rest, so a plain
`createOrUpdateStack` there would silently flip `UseExistingVpc` back to `false` and start building a
VPC over someone's existing networking. Federation parameters are the same shape of problem, so
setup takes the same path for updates.

`SetupCommand` grows `--github-org`, `--github-repo` and `--oidc-provider-arn` to *set* the values.
Omitting them on a later setup leaves the deployed trust untouched rather than dropping it.

**Because omission is now non-destructive, revocation needs its own gesture.** `--revoke-github-oidc`
sends the three parameters explicitly empty; the condition goes false and the federated statement
disappears. That flag is the supported way to cut CI's access to an installation — nothing else
should be able to do it by accident, which is the whole point of carrying values forward.

Two constraints the implementation has to respect. `UsePreviousValue` is rejected on stack
*creation* and on any parameter with no previous value — the existing carry-forward code says so in
a comment, and it is safe there only because a key absent from the deployed stack is necessarily in
its `changed` map. So a first deploy sends explicit values, empty when no federation is wanted, and
carry-forward governs updates only. And `--revoke-github-oidc` is mutually exclusive with the three
setting options; naming both is a usage error rather than a precedence puzzle.

`BaasConfig` gains no fields. The deployed stack's parameters are the single source of truth for
what the trust policy says, readable with `aws cloudformation describe-stacks`.

*Rejected:* recording the three values in `~/.baas/config.yaml` and re-supplying them on every
deploy. It works, but it puts a second copy of a value CloudFormation already holds into a file the
user edits, and a copy that drifts is exactly the class of bug the `UseExistingVpc` comment exists to
prevent. It also makes "whose laptop ran setup last" part of whether CI keeps working.

**Deploy order inverts.** `cf-template-ci.yaml` reduces to the OIDC provider alone and takes no
parameters from core, so the provider is deployed first and its ARN handed to `baas admin setup`.
The provider is account-global — one per issuer URL per account — and
`token.actions.githubusercontent.com` already exists in `381492019823`, so the deploy must reuse it
rather than create a duplicate, which fails with `EntityAlreadyExists`. Deploying it needs an
identity above the deployer: `deployer-policy.json` scopes `iam:Get*`/`iam:List*` to roles and never
to `oidc-provider/*`, verified live — `aws iam list-open-id-connect-providers --profile baas-admin`
returns `AccessDenied`.

### `source` is the trigger tag, derived by `baas run` and caller-overridable

`source` joins `TagKeys.KNOWN` with the derived values `ci` and `local`. `baas run` sets it from the
environment; an explicit `--tag source=…` wins, exactly as `project`, `commit` and `branch` do. It is
deliberately *not* added to `MACHINE_OBSERVED`: the reserved keys exist so a result's tags cannot
disagree with its own `environment.json`, and how a run was triggered is not something the instance
observes.

Reusing `source` rather than minting a `trigger` key keeps one vocabulary for one dimension, and
fixes what is wrong with today's usage: the workflows write `gha-e2e-test-async` and
`gha-e2e-test-profilers`, smearing trigger and benchmark variant into one string — which is exactly
why `e2e-cloud-test.yml`'s assertion on `tags.source == gha-e2e-test` has never been capable of
passing. The variant dimension already has a home in the `type` tag.

*Rejected:* a caller-supplied convention key outside `KNOWN` (a local run carries the tag only if the
person types it, so absence means "unknown" rather than "local", and the comparability check becomes
unreliable in exactly the direction that matters); reserving `source` outright (forecloses a consumer
labelling a nightly or release run, for no safety gain — a forged `source` misleads nobody about the
environment, which the reserved keys are what protect).

### CI and local runs are separated by tags, not by identity or infrastructure

Three categories, two mechanisms: a local `baas run` is the default and needs nothing; the CI
self-test measures fixture code and carries `exclude_from_results=true`; a consumer CI run is a real
measurement distinguished only by `source=ci`. The tag also earns its keep analytically —
`--group-by source` is how you *verify* that CI and laptop runs are comparable rather than assuming
it because both boot the same AMI.

Attribution needed no second IAM role: `configure-aws-credentials` sets a role session name, so
CloudTrail already distinguishes `assumed-role/<role>/GitHubActions` from a local session.

### `queryByRequestId` stops applying the exclusion filter

New contract: exclusion is a property of project sweeps; naming one run by its id is a request for
that run. Without this an excluded self-test is invisible to every assertion surface except
`baas download` (which carries no exclude filter and would have been the only survivor), and
`RunCommand.showResults` — which calls `queryByRequestId` — prints an empty summary after a
*successful* excluded run.

**Implementation landmine.** DynamoDB rejects a request whose `ExpressionAttributeNames` or
`ExpressionAttributeValues` carry entries unused in any expression. Deleting only the
`filterExpression` line orphans `#pk`'s companions `#tags`, `#excluded` and `:excluded`, and then
*every* `--request-id` query fails at runtime with `ValidationException`. The names and values must
go with the filter. A builder-level unit test would not catch this; the new case must round-trip
against LocalStack.

No existing test changes: both `excludedRowsAreDroppedServerSide` and
`aRowCarryingNoTagsAtAllSurvivesTheExcludeFilter` exercise `queryProject`. The work is one additive
integration test.

### `baas run` gains `--format json`, printed on the failure path too

Forced by the decision above plus a fact: the run id reaches only `logger.info` → stderr with a
timestamp prefix, and `run` has no `--format` option. `--tag` filtering happens client-side over
`queryProject`, which keeps the exclusion filter, so the "correlate by a unique tag" trick cannot
find an excluded run either. CI must be handed the id.

The object carries at least `runId`, `project`, `resultPath`, `status`, `exitCode` and `instanceId`,
follows `ResultsCommand.printJson`'s precedent (payload on `System.out`, diagnostics on the logger),
and **must print on failure** with `status: "failed"` while `baas run` still exits non-zero — the
failure case is precisely when CI needs the id, to `baas download` and surface
`cloud-init-output.log`, the documented starting point when a run dies before producing output.

### The self-test covers `jmh-with-async` only

One run is one instance is one type, so coverage scales linearly in money. `jmh-with-async` is the
only type whose failure modes live in what nothing else tests: the baked AMI, the
`perf_event_paranoid` / `kptr_restrict` tunables, the pinned `perf` and async-profiler versions, and
profiling-artifact upload. The others re-test orchestration that is identical across types.

**This does not narrow `baas run`.** The type stays a positional parameter; consumer repositories
will run `jmh`, `jmh-with-async` and `jcstress` against their own benchmarks through the same
mechanism. Any task that "simplifies" by hard-coding a type violates the spec's requirement that the
mechanism not be narrowed to the self-test's type.

### The self-test is triggered by a path-filtered `pull_request` plus `workflow_dispatch`

The workflow is currently free because it fails in ~90 seconds; fixed, it becomes a real paid EC2 run
per triggering push, and a docs-only PR should not pay it. `workflow_dispatch` is required
regardless — a workflow that has never passed must be provable on demand rather than by opening a PR.
A nightly schedule is deferred until it has been green for a while.

### `ci-pr-build.yml` exports `ASYNC_PATH`

`JmhWithAsyncProfilerSubcommandServiceIT` is `@EnabledIfEnvironmentVariable(named = "ASYNC_PATH")`,
so a plain `mvn verify` silently skips the only test exercising async-profiler end to end — the green
tick on every PR has never covered it. Two lines, and the most literal reading of "fix the CI tests".
Closes finding D3.

### The OIDC `sub` condition stays `repo:${Org}/${Repo}:*` for now

Fork PRs are believed unable to obtain an `id-token: write` token at all, which would moot the fork
concern — but that is believed, not verified, and it guards a workflow that spends money. Confirm
during apply; tightening to `:pull_request` or a branch-scoped value is a one-line follow-up if it
turns out otherwise. Finding S2 is therefore materially reduced — the self-hosted runner carrying an
instance profile disappears — but not closed.

### `WorkflowRole` is removed from the legacy stack, which otherwise stays standing

**Corrected during apply (2026-09-20).** There is no separate `baas-lynx` CI stack to delete. The
live account carries one monolithic legacy stack, `baas-main`, using the `baas-lynx` resource-name
prefix, and it declares *all* of: `GithubOidc` (the account's OIDC identity provider — the very one
this change federates through), `WorkflowRole`, `RunnerRole` + instance profile, `S3MainBucket`
(`baas-lynx-main`) and the `s3-hook-lambda` resources CLAUDE.md records as gone. **No resource in
it carries a `DeletionPolicy`.**

Deleting the stack would therefore destroy the identity provider the new federation depends on, and
would attempt to delete the bucket this design says to leave alone — failing while it holds objects
and leaving the stack `DELETE_FAILED`.

So the deletion is targeted rather than wholesale: `WorkflowRole` and its `WorkflowRoleArn` output
are removed from the stack's template and the stack is updated. That closes the live risk — after
federating into `OperatorRole` the account would otherwise carry two roles this repository can
assume, one of them unmaintained, holding unscoped `ec2:RunInstances` and `iam:PassRole` — while
leaving the provider, the bucket and the rest untouched. A change set confirmed the update's only
action is `Remove WorkflowRole`.

*Rejected:* deleting `baas-main` outright (takes the provider and the bucket with it); adding
`DeletionPolicy: Retain` to the provider and bucket first and then deleting the stack (orphans both
as unmanaged resources, for no gain while the stack is harmless once the role is gone); deleting the
role by hand outside CloudFormation (leaves the stack drifted from its template).

**Residual, recorded rather than fixed:** `GithubOidc` still has no `DeletionPolicy`, so a future
delete of `baas-main` would still take the identity provider with it. Out of scope here; worth a
one-line protective edit before anyone retires that stack.

### `benchmark-runner` keeps its MongoDB adapter; only the record is corrected

Retiring it is a user-visible BREAKING removal deserving its own change and its own spec delta, not a
rider on a redesign already carrying four workflow deletions, a core-template trust change, two
`baas-cli` features, a query-behaviour change and an IAM edit. But CLAUDE.md's accepted-risks entry
becomes factually wrong the moment this lands: it names a standalone justification whose only holder
(java-wonderland) sits on a branch frozen 2024-06-22 that cannot run today's runner at all —
`--s3-result-prefix` is gone, no `--project` means `getProject()` throws, and no store selection fails
the exactly-one-of check. The entry is rewritten to say: retained, **no known live user**, retirement
is an open decision.

## Risks / Trade-offs

- **Deleting only the filter expression breaks every `--request-id` query at runtime** → the orphaned
  expression names and values must be deleted with it, and the new integration test must run against
  LocalStack, where a `ValidationException` actually surfaces.
- **Carry-forward means a federated trust outlives the intent behind it** → the inverse of the risk
  this design started with, and the better trade: an unwanted trust persists until someone runs
  `--revoke-github-oidc`, rather than a wanted one vanishing on an unrelated setup. Mitigated by
  making revocation an explicit, documented flag and by 6.8's check that exactly one role trusts the
  repository.
- **A first deploy cannot carry anything forward** → `UsePreviousValue` is rejected on create and on
  a parameter with no previous value, so the create path sends explicit values (empty when no
  federation is wanted); a test should cover create-then-update, not update alone.
- **`jcstress` loses what little end-to-end coverage the old path gave it** → accepted, and recorded
  so it is not later "discovered" as a bug. Its storage path is genuinely distinct code
  (`JCSTRESS#<timestamp>#<requestId>`, one item per run, `jcstress-results-*.bin.gz` handling), and
  after this change nothing exercises it end to end. A deliberate trade for per-type instance cost.
- **The project's first recurring per-event AWS spend** → bounded by the path filter; roughly one
  `c5.2xlarge` instance-lifetime per triggering push. No new standing cost.
- **CI and the laptop share one installation** → a teardown of `baas-3q7i7s65`, or a change to the
  ARN the prefix derives from, breaks CI. Accepted against a second bake, a second snapshot and
  siloed results.
- **CI holds `DeleteObject` bucket-wide** → the widening is real but unexercised; CI writes only under
  `runs/…/input/`. Re-narrowing means re-splitting the roles, which is what this design removed.
- **`operatorCredentialsWarning` will fire on every CI run with advice that is wrong there** → it
  warns whenever `aws.operatorProfile` is null, and in CI credentials arrive from the environment,
  which the method's own Javadoc calls the correct fallback. Either recognise ambient credentials or
  suppress the warning where a profile would be meaningless.
- **`--format json` must not be corrupted by the diagnostics around it** → the payload goes to
  `System.out` and everything else to the logger, following the rule already applied to
  `ResultsCommand.printJson`/`printCsv`; a redirected-output test pins it.

## Migration Plan

1. Deploy the OIDC identity provider from the reduced `cf-template-ci.yaml`, by hand, with an identity
   above the deployer. Check first whether the account already has one for
   `token.actions.githubusercontent.com` — it does, in `381492019823` — and reuse its ARN rather than
   creating a duplicate.
2. `baas admin setup --github-org … --github-repo … --oidc-provider-arn …` against `baas-3q7i7s65`,
   with the updated deployer policy attached. This adds the trust statement and raises
   `MaxSessionDuration`; it changes no runner, image or network resource.
3. Replace the `WORKFLOW_ROLE_ARN` secret with the operator role ARN as a plain repository `vars.`
   entry — a role ARN is not sensitive. Delete `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `SUBNET_ID`,
   `SECURITY_GROUP_ID` and `RESOURCE_NAME_PREFIX`.
4. Merge the workflow deletions and the rewritten `e2e-cloud-test.yml` together, so no window exists
   in which an orphaned sub-workflow is still callable.
5. Prove it with `workflow_dispatch` before relying on the `pull_request` trigger.
6. Execute the staged `remove-workflowrole` change set on `baas-main` once the new path is green,
   removing `WorkflowRole` alone. Leave the identity provider, `baas-lynx-main` and the rest of
   that stack standing.
7. Land `private-runner-network` afterwards; it needs no carve-out once the self-hosted runner is
   gone.

**Rollback.** Steps 1-3 are additive and independently reversible: `baas admin setup
--revoke-github-oidc` drops the trust statement, and the account-root principal keeps local operators
working throughout. Note that plain `baas admin setup` no longer undoes step 2 — carrying the values
forward is deliberate, and revocation is the explicit gesture. Step 4 is a git revert. `RESOURCE_NAME_PREFIX`'s drift to
`baas-lynx-main` (finding A10) closes by the variable ceasing to exist, so a revert restores a known-
broken path rather than a working one — which is the point at which rollback stops being attractive
and forward-fixing starts.

## Open Questions

- **What consumers actually receive.** The contract changes from *call our reusable workflow* to
  *install the CLI*, and nothing is currently shipped behind that: a documented snippet in
  `README.md`/`infra/README.md`, a composite action published from this repo, or something else. The
  user marked this "only a note for now" (2026-09-20); it is a real deliverable question that does not
  change these specs, this approach or this task breakdown, and it is not answered here.
- **Whether anything still reads `baas-lynx-main`.** Unanswerable from the `3q7i7s65` deployer
  identity, whose prefix-exact policy refuses even `DescribeStacks` on `baas-lynx-*`. Deliberately not
  depended on: the bucket is left alone either way.
- **Whether a fork PR can obtain an `id-token`.** Deferred to apply, where it can be checked against
  the live workflow rather than reasoned about. Either answer leaves the specs unchanged; only the
  `sub` condition's literal value moves.

## Resolved Questions

- **The trigger tag's key name, and whether it joins `TagKeys.KNOWN`** — raised twice during explore
  and never settled there. Resolved 2026-09-20 by the user: reuse `source` with a fixed `ci`/`local`
  vocabulary, add it to `KNOWN`, derive it in `baas run`, and let a caller override it like
  `project`/`commit`/`branch` rather than reserving it. Evidence weighed: `source` exists today only
  as a free-form `--tag source=…` in two workflow files — zero occurrences in any Java source — so
  promoting it collides with nothing; and the values it carries there
  (`gha-e2e-test-async`, `gha-e2e-test-profilers`) smear trigger and variant together, which is the
  documented cause of an assertion that has never been able to pass.
