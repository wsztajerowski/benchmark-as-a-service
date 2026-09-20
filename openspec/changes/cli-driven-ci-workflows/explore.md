# Explore — CLI-driven CI workflows

> **Not an artifact.** The stock `spec-driven` schema's artifacts are `proposal`, `specs`, `design`
> and `tasks`; `brainstorm` belonged to the retired `superspec` schema. Per CLAUDE.md, explore
> replaces `superpowers:brainstorming` for this workflow, so this file is that session's record —
> the companion to `assumptions.md`, in the same not-an-artifact category as `verify.md`.
>
> Session of 2026-09-17 → 2026-09-20. Reads `assumptions.md` as input, **corrects it in two
> places**, and settles fourteen decisions it deliberately left open. `/opsx:propose` should treat
> the Decisions section below as given and the Open Questions section as still live.

## Framing that changed during the session

`assumptions.md` treats "the GHA path" as one thing. It is two, and they want opposite designs:

```
  ┌─ BaaS's own CI ─────────────────┐   ┌─ BaaS as CI elsewhere ──────────┐
  │ question: "does BaaS work?"     │   │ question: "did my code regress?"│
  │ subject:  the orchestration     │   │ subject:  the consumer's code   │
  │ payload:  fake-jmh-benchmarks   │   │ payload:  their real benchmarks │
  │ numbers:  meaningless, discard  │   │ numbers:  the entire point      │
  │ types:    jmh-with-async only   │   │ types:    jmh, jmh-with-async,  │
  │                                 │   │           jcstress — their call │
  │ → exclude_from_results=true     │   │ → kept, compared, grouped       │
  └─────────────────────────────────┘   └─────────────────────────────────┘
        THIS change builds this              THIS change must not foreclose this
```

**Standing constraint, stated by the user 2026-09-20:** nothing here may narrow `baas run` to the
types the self-test happens to exercise. Consumer repositories will run `jmh`, `jmh-with-async` and
`jcstress` against their own benchmarks through this same mechanism. The design satisfies this by
construction — the type is a positional parameter — but any task that "simplifies" by hard-coding a
type violates it.

## Decisions

**D1 — CI stops provisioning and calls `baas run`.** `assumptions.md` option 4, chosen over options
1-3. The doc argued option 4 *dominates* option 1; this session found it is also **forced**. See F1.

**D2 — `benchmark-runner.yml` is deleted, and four more files go with it.** It was the only
`workflow_dispatch` entry point, so deleting it leaves `e2e-cloud-test.yml` as the sole caller of
the three sub-workflows — and D1 stops that caller calling them. The act harness's subject is
`exec-single-benchmark.yml`, so it dies too.

```
  benchmark-runner.yml  ──DELETED
        ├── start-ec2-runner.yml      ─┐
        ├── exec-single-benchmark.yml  │  other caller: e2e-cloud-test.yml
        └── stop-ec2-runner.yml       ─┘  ...which stops calling them under D1
                                          ↓  ALL THREE ORPHANED → deleted
  .github/test/  — drives `act -W .github/workflows/exec-single-benchmark.yml`
                   its subject no longer exists → deleted
```

Also dead: `machulav/ec2-github-runner`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `SUBNET_ID`,
`SECURITY_GROUP_ID`, `ASYNC_PROFILER_VERSION`, `RESOURCE_NAME_PREFIX`, `MONGOSH_VERSION`,
`verify-mongo.sh`, `verify-s3.sh`, `run-tools.sh`, `validate-tools.sh`, `utils.sh`, the
`.github/test/testing-scripts/logger.sh` copy, `yum update -y`, per-run async-profiler download,
`setup-java` on the paid instance, and bash id-minting.

**`RESOURCE_NAME_PREFIX` dying is what closes open finding A10** — the table and bucket come from
stack outputs via `baas config sync`, so the hand-maintained variable that drifted to
`baas-lynx-main` stops existing rather than being corrected.

**The consumer contract changes from reusable-workflow to install-the-CLI.** This resolves
`assumptions.md` open questions 0, 2 and 4 together, in the direction history already took: one
installation per consumer, each with its own stack, bucket, table and single-repo OIDC trust. The
multi-repo trust work nothing has ever needed stays unneeded. See OQ2 for what consumers receive.

**D3 — CI runs on the existing `baas-3q7i7s65` installation.** No second AMI bake, no second
standing snapshot, and one measurement environment so CI and laptop results are comparable by
construction. Separating CI runs from local runs is a tagging problem, not an infrastructure one
(D5).

**D4 — Shape B: delete `WorkflowRole`; federate GitHub OIDC directly into `OperatorRole`.**

```
  A · federation role + chain           B · federation on the workload role  ← CHOSEN
  ─────────────────────────────         ───────────────────────────────────
  OIDC → WorkflowRole → OperatorRole    OIDC → OperatorRole
         ≤ 60 min, HARD AWS LIMIT              up to MaxSessionDuration (12 h)
         2 grant lists, 1 has drifted          1 grant list
         trust implied by identity             trust declared on the role
           policies + account-root
```

Decided by F6: role chaining caps a session at 60 minutes, `MaxSessionDuration` is set nowhere so
both roles default to 3600 s, and `benchmarkTimeoutSeconds` defaults to 7200. Shape A cannot serve
a consumer benchmark run longer than an hour, and the failure is ugly — see F6.

`WorkflowRole` had no remaining justification: the only grants it held that `OperatorRole` lacks
are `ec2:ReplaceIamInstanceProfileAssociation` / `AssociateIamInstanceProfile` and the
`/aws/service/ami-amazon-linux-latest/*` SSM path, all three machulav-only and all three deleted by
D1/D2. Its extra hop was never a security boundary either: the chaining statement is unconditional,
so anything that can assume it can assume `OperatorRole` one call later. Its real value was
*optionality* — a seam you could later narrow — traded away against a duplicated grant list that
has already drifted once (the `s3:PutObject` `AccessDenied` in `assumptions.md`).

Merging trades scope in both directions, deliberately:

| | `WorkflowRole` today | `OperatorRole` |
|---|---|---|
| `ec2:RunInstances` | `Resource:"*"`, unscoped | scoped by region **and** `c5/c6i/c7i/m5/m6i/m7i.*` |
| `s3:PutObject` | `${Bucket}/runs/*` only | whole bucket, plus `DeleteObject` |

CI *gains* an instance-family guardrail it never had and *loses* the `runs/*` write scoping. The
latter is unexercised: CI must pass `--runner-jar` (a reactor build is `0.0.0-semantically-released`,
which names no release), so it writes to `runs/…/input/runner.jar` and never touches `releases/`.

Consequences to implement:

- `cf-template-core.yaml` gains `GitHubOidcProviderArn`, `GitHubOrg`, `GitHubRepo` (default `""`),
  a `Condition`, a conditional federated trust statement on `OperatorRole`, and a raised
  `MaxSessionDuration`.
- **`GitHubRepo` should be a `CommaDelimitedList`** rendering several `StringLike` values. One
  installation serving two repositories is a template migration otherwise, and D2's consumer model
  makes that plausible. Cheap now.
- `SetupCommand` grows an option and `BaasConfig` a field. **This is load-bearing, not polish:**
  `baas admin setup` re-deploys without `UsePreviousValue`, so a parameter the CLI does not know
  falls back to `Default: ""`, the condition evaluates false, the trust statement vanishes and CI
  **silently loses access on the next setup**.
- `deployer-policy.json` gains `iam:UpdateAssumeRolePolicy` (~35 bytes against ~300 of headroom
  under the 4608 test cap). Grants nothing new: the accepted-risks table already records that
  `iam:CreateRole` writes trust policies, making the deployer effectively account admin.
- `cf-template-ci.yaml` reduces to the OIDC provider alone and takes **no** parameters from core.
  Deploy order inverts: provider first, then `baas admin setup`.
- The `WORKFLOW_ROLE_ARN` secret is replaced by the operator role ARN, which can be a plain `vars.`
  — a role ARN is not sensitive.

**D5 — CI and local runs are separated by tags, not by identity or infrastructure.** Tags are the
entire query surface; IAM answers "who may call `RunInstances`", never "which rows came from CI".
Three categories, two mechanisms:

| Category | Mechanism |
|---|---|
| local `baas run` — real measurement | (nothing; the default) |
| CI self-test — `fake-jmh-benchmarks`, measures nothing | `exclude_from_results=true` |
| consumer CI run — real measurement, PR-triggered | a trigger tag — **key name still open, OQ1** |

The tag also earns its keep analytically: `--group-by <trigger-key>` is how you *verify* that CI and
laptop runs really are comparable, rather than assuming it because both boot the same AMI.

**D6 — Trigger: path-filtered `pull_request` + `workflow_dispatch`.** Nightly schedule deferred
until it has been green for a while. Rationale: the workflow is currently free because it fails in
~90 s; fixed, it becomes a real paid EC2 run per triggering push, and a docs-only PR should not pay
it. `workflow_dispatch` is required regardless — a workflow that has never passed needs to be
provable on demand rather than by opening a PR.

**D7 — `ResultsQueryService.queryByRequestId` drops `EXCLUDE_FILTER`.** New contract: *exclusion
applies to project sweeps; an explicit single-run lookup returns the run.* This is what
`assumptions.md` already assumed to be true (F4) and what makes D5's self-test tagging usable.

```java
// queryByRequestId
  .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK))  // #tags, #excluded GO TOO
  .expressionAttributeValues(Map.of(":pk", ...))                          // :excluded GOES TOO
- .filterExpression(EXCLUDE_FILTER)
```

Purely additive in tests — see F11. Carries the F8 landmine. Free side-effect:
`RunCommand.showResults` stops printing an empty summary after a successful excluded run.

**D8 — `baas run` gains `--format json`.** A single summary object on stdout carrying at least
`runId`, `project`, `resultPath`, `status`, `exitCode`, `instanceId`; diagnostics stay on the
logger so stdout holds exactly the object. Follows `ResultsCommand.printJson`'s precedent.

Forced by D7 + F7: `--tag` filters client-side over `queryProject`, which keeps `EXCLUDE_FILTER`, so
the correlation-tag trick cannot find an excluded run. CI must have the run id, and the run id
currently reaches only `logger.info` → stderr with a timestamp prefix.

**The summary must print on the failure path too**, with `status: "failed"` / `exitCode: N`, while
`baas run` still exits non-zero. The failure case is precisely when CI needs the id — to
`baas download` and surface `cloud-init-output.log`, the documented starting point when a run dies
before producing output.

**D9 — The self-test covers `jmh-with-async` only.** One run is one instance is one type now, so
coverage scales linearly in money. `jmh-with-async` is the only type whose failure modes live in
what nothing else tests: the baked AMI, the `perf_event_paranoid` / `kptr_restrict` tunables, the
pinned `perf` and async-profiler versions, and profiler artifact upload. The others re-test
orchestration that is identical across types.

**D10 — This change lands before `private-runner-network`, and unblocks it.** They compose: CI runs
on `ubuntu-latest` with ordinary internet; only the benchmark instance goes private.

**D11 — `ci-pr-build.yml` exports `ASYNC_PATH`.** In scope. `JmhWithAsyncProfilerSubcommandServiceIT`
is `@EnabledIfEnvironmentVariable`, so a plain `mvn verify` silently skips the only test exercising
async-profiler end to end — the green tick on every PR has never covered it. Two lines, and the most
literal reading of "fix the CI tests".

**D12 — Leave the OIDC `sub` condition as `repo:${Org}/${Repo}:*`.** Fork PRs are believed unable to
obtain an `id-token: write` token at all, which would moot the fork concern — but that is *believed,
not verified*, and it guards a workflow that spends money. **Confirm during apply**; tightening the
condition is a one-line follow-up if it turns out otherwise.

**D13 — Delete the `baas-lynx` CI stack. Leave the `baas-lynx-main` bucket.** `WorkflowRole` there
still trusts `repo:wsztajerowski/benchmark-as-a-service:*` via OIDC and holds unscoped
`ec2:RunInstances` plus `iam:PassRole`; after D4 the account would carry two roles this repo can
assume, one of them unmaintained. That is the only part that is a live risk. The bucket holds S3
artifacts from `fake-jmh-benchmarks` runs — the GHA path's measurements went to Atlas, not DynamoDB
— so no migration question arises. See OQ3.

**D14 — `benchmark-runner` keeps its MongoDB path; the record gets corrected.** Retiring it is a
user-visible BREAKING removal that deserves its own change and its own spec delta, not a rider on a
CI redesign already carrying four workflow deletions, a core-template trust change, two `baas-cli`
features, a query-behaviour change and an IAM edit. But the accepted-risks entry becomes factually
wrong the moment this lands — it names a standalone justification whose only holder
(java-wonderland) is on a branch frozen 2024-06-22 that cannot run today's runner at all
(`--s3-result-prefix` removed, no `--project` so `getProject()` throws, no store selected so the
exactly-one-of check fails). Rewrite it to say: retained, **no known live user**, retirement is an
open decision.

## Facts verified this session

Each of these was checked against the code or a live API call, not inferred.

**F1 — `private-runner-network` and the GitHub runner agent are structurally incompatible, and that
change mentions GitHub Actions nowhere.** Verified by grepping all its artifacts. It moves runners
to a private subnet with no IGW route and narrows egress to the S3 and DynamoDB prefix lists on 443.
A self-hosted Actions runner must reach `github.com`. The machulav path would survive only by
keeping a second, deliberately less-isolated network path — the thing that change exists to delete.
This turns D1 from a preference into a sequencing constraint (D10).

**F2 — `baas-cli` has no endpoint-override support whatsoever.** Zero hits for `ENDPOINT_URL`,
`endpointOverride` or `localstack` across `baas-cli/src/main/java`; `AwsClientFactory` takes region
plus an optional profile and nothing else. So a workflow whose one real step is `baas run` cannot be
driven under `act` against LocalStack. `assumptions.md` asserts this consequence ("loss of the
`env.ACT`/LocalStack workflow test") without the evidence. The coverage does not vanish, it
relocates: what `act` tested — JAR staging, profiler provisioning, invocation assembly, result key
layout — is `RunnerJarResolver`, `S3UploadService`, `UserDataScriptBuilder`, `RunLayout`/`ResultKeys`
and the store contract suite, all already Java-testable.

**F3 — sixth blocker, unrecorded in `assumptions.md`'s table of five.** `verify-mongo.sh` matches
exactly (`findOne({'$KEY': '$VALUE'})`), and `e2e-cloud-test.yml` asserts
`benchmarkMetadata.tags.source == gha-e2e-test` while its two jobs write `gha-e2e-test-async` and
`gha-e2e-test-profilers`. That assertion has never been capable of passing. Visible only because the
job has never got far enough to fail on it. It also shows `source` smearing two dimensions — trigger
and benchmark variant — into one string, which is the argument for a dedicated trigger key (OQ1).

**F4 — CORRECTION to `assumptions.md`.** The doc's option 3 says excluded runs stay "reachable by
`--request-id`/`--tag`, excluded from grouping". The code does not do that: `EXCLUDE_FILTER` is
applied to **both** `queryProject` and `queryByRequestId`. An excluded row is invisible to
`baas results` by every filter, and `RunCommand.showResults` — which calls `queryByRequestId` —
would print an empty summary after a successful excluded run. D7 makes the doc's assumption true.

**F5 — `resultPathForRun` carries no exclude filter**, so `baas download <runId>` works on excluded
runs today and would have been the only surviving assertion surface had D7 gone the other way.

**F6 — role chaining caps a session at 60 minutes, and nothing here sets `MaxSessionDuration`.**
`grep -rn MaxSessionDuration infra/` returns nothing, so both roles default to 3600 s;
`BaasConfig` defaults `benchmarkTimeoutSeconds = 7200` and `wallClockHardKillSeconds = 7500`.
`configure-aws-credentials` writes static `AWS_*` env vars that never refresh, and `baas run` polls
`run-status` for the life of the run. The failure mode is worse than an error:

```
  t=0      upload, launch, begin polling
  t=60m    session expires → polling dies; the CLI shutdown hook needs credentials too,
           so the instance is NOT terminated
  t=60m+   benchmark completes, runner uploads and writes its item (instance profile, unaffected)
  t≈2h05m  the shell watchdog terminates — the only termination layer that works here
  ─────────────────────────────────────────────────────────────────────────────────
  CI job RED. Measurement GOOD, sitting in the table. Full EC2 bill paid.
```

(The shutdown-hook step is reasoned from how layers 2 and 3 are wired, not executed; the credential
expiry and the watchdog backstop are certain.)

**F7 — the run id never reaches stdout.** `RunCommand` emits it only via `logger.info` (lines 231,
300, 400) → stderr with a timestamp prefix. `run` has no `--format` option. Forces D8.

**F8 — implementation landmine for D7.** DynamoDB rejects a request whose `ExpressionAttributeNames`
or `ExpressionAttributeValues` contain entries unused in any expression
(`ValidationException: Value provided in ExpressionAttributeNames unused in expressions`). Deleting
only the `filterExpression` line orphans `#tags`, `#excluded` and `:excluded`, and **every**
`--request-id` query fails at runtime. The new IT catches it only because it round-trips against
LocalStack; a builder-level unit test would not.

**F9 — the deployer identity cannot inspect OIDC providers.** Verified live:
`aws iam list-open-id-connect-providers --profile baas-admin` →
`AccessDenied … not authorized to perform: iam:ListOpenIDConnectProviders`. By design;
`deployer-policy.json` scopes `iam:Get*`/`iam:List*` to roles, never `oidc-provider/*`. So deploying
the provider needs a third identity above the deployer — the `lynx` user is the likely one, being
the `source_profile` behind `baas-operator`.

**The OIDC provider is account-global — one per URL per account.** Something already created
`token.actions.githubusercontent.com` in `381492019823` (the `baas-lynx` role exists and the
resource carries `DeletionPolicy: Retain`). Leaving `OIDCProviderArn` empty makes CloudFormation
attempt a duplicate and fail with `EntityAlreadyExists`. Check before deploying.

**F10 — `OperatorRole`'s trust is `Principal: {AWS: …:root}`**, which delegates entirely to identity
policies. This is what made shape A possible with no trust edit — and, inverted, is an argument for
shape B: under A, "who may be the operator" is answered only by scanning every identity policy in
the account, whereas B declares it on the role.

**F11 — D7 needs no existing test changed.** Both `excludedRowsAreDroppedServerSide` and
`aRowCarryingNoTagsAtAllSurvivesTheExcludeFilter` in `ResultsQueryServiceIT` exercise
`queryProject`. The work is one additive IT pinning that `queryByRequestId` returns an excluded row.

**F12 — `operatorCredentialsWarning` will fire on every CI run with advice that is wrong there.**
It warns whenever `aws.operatorProfile` is null; in CI credentials arrive from the environment,
which the method's own Javadoc calls the correct fallback. Either recognise ambient credentials or
suppress when a profile would be meaningless.

**F13 — attribution never needed a second IAM role.** `configure-aws-credentials` sets a role
session name, so CloudTrail already distinguishes `assumed-role/<role>/GitHubActions` from a local
session — visible in `assumptions.md`'s own quoted error message. An objection raised and withdrawn
during this session; recorded so it is not raised again.

## The resulting shape

```
  BEFORE — 6 jobs, 4 workflow files, 12 responsibilities re-implemented in bash
  ┌──────────────┐ ┌────────────────┐ ┌──────────────┐ ┌───────────────┐
  │  setup-env   │ │  prepare-test  │ │ start-runner │ │  stop-runner  │
  │ bash date +  │ │ mvn, aws s3 cp │ │ machulav+PAT │ │ machulav+PAT  │
  │ openssl rand │ │ hand-built key │ │ AL2023 latest│ └───────────────┘
  └──────────────┘ └────────────────┘ │ + yum update │
                   ┌───────────────────┴──────────────┴───┐
                   │ exec-single-benchmark.yml ×2          │
                   │ setup-java │ async dl │ SSM mongo     │
                   └───────────────────────────────────────┘
                   ┌───────────────────────────────────────┐
                   │ verify-test-result: mongosh + shell   │
                   └───────────────────────────────────────┘

  AFTER — 1 job, 1 workflow file, 0 responsibilities in bash
  ┌────────────────────────────────────────────────────────────────┐
  │ runs-on: ubuntu-latest                                         │
  │   checkout → setup-java → mvn package                          │
  │   configure-aws-credentials  (OIDC → OperatorRole, one step)   │
  │   baas config sync --core-stack-name baas-3q7i7s65             │
  │   baas run jmh-with-async --format json … --runner-jar … -- …  │
  │   jq -r .runId  →  baas results --request-id / baas download   │
  └────────────────────────────────────────────────────────────────┘
```

Workflow inventory after this change:

| File | Fate |
|---|---|
| `ci-pr-build.yml` | keep, `+ ASYNC_PATH` (D11) |
| `install-test.yml` | keep — the model for how the rewritten e2e should read |
| `release.yml` | keep |
| `e2e-cloud-test.yml` | rewritten as a single CLI-driven job |
| `benchmark-runner.yml`, `exec-single-benchmark.yml`, `start-ec2-runner.yml`, `stop-ec2-runner.yml` | deleted |
| `.github/test/**` | deleted |

All five blockers in `assumptions.md`'s table dissolve rather than being fixed: the PAT stops
existing, S3 access comes from the operator role, the SSM Mongo fetch is deleted,
`environment.json` is written because user-data writes it, and `baas run` resolves the project.
F3's sixth blocker dissolves with the script that contained it.

## Accepted risks introduced

| Risk | Position |
|---|---|
| `jcstress` has no end-to-end coverage | Its storage path is genuinely distinct code (`JCSTRESS#<timestamp>#<requestId>`, one item per *run*, `jcstress-results-*.bin.gz` handling) and after D9 nothing exercises it end to end. A deliberate trade for per-type instance cost, recorded so it is not later "discovered" as a bug. |
| Per-event AWS spend | The project's first recurring *per-event* cost. Bounded by D6's path filter. Roughly one instance-lifetime (~10 min on `c5.2xlarge`) per triggering push. |
| CI and laptop share one installation | D3. A teardown of `baas-3q7i7s65`, or a change of the deriving caller ARN, breaks CI. Accepted against a second AMI bake, a second standing snapshot and siloed results. |
| CI holds `DeleteObject` bucket-wide | D4's widening. Unexercised — CI writes only under `runs/…/input/`. |

## Open questions carried into `/opsx:propose`

**OQ1 — the trigger tag's key name, and whether it joins `TagKeys.KNOWN`.** Raised twice, never
settled. `TagKeys.KNOWN` currently holds 9 keys of which `MACHINE_OBSERVED` reserves 6;
`exclude_from_results` and `options` are deliberately outside it as free-form conventions. F3 argues
against reusing `source`, which already smears trigger and benchmark variant together. Candidates:
`trigger=ci|local`, or a new `source` vocabulary with fixed values.

**OQ2 — what consumers actually receive.** D2 replaces the reusable-workflow contract with
"install the CLI", and right now nothing is shipped behind that: a documented snippet in
`README.md`/`infra/README.md`, a composite action published from this repo, or something else. The
user marked this "only a note for now" (2026-09-20) — it is a real deliverable question, not
resolved by D2.

**OQ3 — whether anything still reads `baas-lynx-main`.** Unanswerable from the `3q7i7s65` deployer
identity (prefix-exact policy refuses even `DescribeStacks` on `baas-lynx-*`). D13 deliberately does
not depend on the answer; the bucket is left alone.

**Carried from `assumptions.md` and now believed moot, but not formally closed:** its question 1
(does anything else write to `baas-lynx-main`) is OQ3; questions 0, 2 and 4 are resolved by D2/D3;
question 3 (an `imageVersion` bump to bake `docker`/`git`/`libicu`) dissolves entirely, since that
cost existed only because the instance had to be a GitHub runner.

## Relationship to other changes

- **`private-runner-network`** (active, 0/55) — this change lands first and unblocks it (F1, D10).
  That change's artifacts mention GitHub Actions nowhere and will need no carve-out once D2 lands.
- **`export-before-teardown`** (brainstormed, unbuilt) — targets whatever `~/.baas/config.yaml`
  names, never a foreign installation, so it cannot help with `baas-lynx` (OQ3). No coupling.
- **Archived changes still cite this change's former name**, `gha-workflow-migration-to-dynamodb`
  (`2026-08-20-dynamodb-results-store`, `2026-09-09-unified-run-prefix`,
  `2026-09-17-installable-cli-command`). Historical records, deliberately not rewritten; the rename
  note in `assumptions.md` is the trail.
