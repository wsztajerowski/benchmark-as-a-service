# Verify

Running record for `cli-driven-ci-workflows`. Section 1's evidence is captured as it was gathered,
against the live `381492019823` account; the requirement → code → test → gap table is filled in by
task 8.2.

## Section 1 — blocking assumptions

| Task | Question | Answer | Evidence |
|---|---|---|---|
| 1.1 | Does the account already have an OIDC provider for `token.actions.githubusercontent.com`? | **Yes, exactly one.** `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com` | `aws iam list-open-id-connect-providers --profile lynx` returns one entry; the same call as `baas-admin` returns `AccessDenied` on `iam:ListOpenIDConnectProviders`, confirming the deployer is scoped out by design |
| 1.2 | Can a directly-federated assume request more than 3600 s? | **Yes**, up to the role's `MaxSessionDuration` | `AssumeRoleWithWebIdentity` docs: `DurationSeconds` ranges 900 → "the maximum session duration setting for the role … If you specify a value higher than this setting, the operation fails." The 1-hour role-chaining cap is an `AssumeRole` restriction and does not appear on this operation. `configure-aws-credentials` accepts `role-duration-seconds` over 900–43200. Confirmed live: `3q7i7s65-operator-role` is at `MaxSessionDuration: 3600`, and a 7500 s request fails `ValidationError: The requested DurationSeconds exceeds the MaxSessionDuration set for this role` |
| 1.3 | Does the deployer policy still fit with `iam:UpdateAssumeRolePolicy`? | **Yes, comfortably** | Rendered at the test fixture's account/region/prefix the policy is **4076** non-whitespace characters today. `"iam:UpdateAssumeRolePolicy",` adds 29 → **4105**: 503 under the 4608 test budget, 1015 under IAM's 5120 hard cap |
| 1.4 | Does a reactor-built CLI refuse to resolve a pinned runner JAR? | **Yes**, before any AWS call | `java -jar baas-cli/target/baas-cli.jar run jmh --benchmark-jar … -- FakeBenchmark` exits 1 with "This is an unreleased build (0.0.0-semantically-released), so there is no runner release to pin to… Nothing was built or launched." `--runner-jar` is therefore mandatory in the workflow |
| 1.5 | What does `baas-lynx`'s `WorkflowRole` actually hold? | Captured — see W1 | `baas-lynx-github-actions-workflow-role`: trust is `sts:AssumeRoleWithWebIdentity` from the account's OIDC provider, `sub` `StringLike` `repo:wsztajerowski/benchmark-as-a-service:*`, `MaxSessionDuration` 3600. Inline `…-workflow-policy` grants `ec2:RunInstances`/`TerminateInstances`/`Describe*`/`ReplaceIamInstanceProfileAssociation`/`AssociateIamInstanceProfile` and `iam:PassRole`, both on `Resource: "*"`; inline `…-workflow-s3-put-object-policy` grants `s3:PutObject` on `baas-lynx-main/ci/*`. Confirms finding **S3** as written |
| 1.6 | Can a fork pull request obtain an `id-token`? | **No** | GitHub withholds OIDC token permissions for `pull_request` runs from forks even when `id-token: write` is declared — the runner is not given `ACTIONS_ID_TOKEN_REQUEST_TOKEN`/`_URL`. The `sub: repo:<org>/<repo>:*` wildcard is therefore not reachable from a fork and needs no tightening (design's third open question, closed) |

### Notes on 1.6's evidence quality

GitHub's own documentation does not state this restriction in the pages that would be expected to
carry it (`controlling-permissions-for-github_token`, the OIDC concept and secure-use references).
The answer rests on consistent reported behaviour across GitHub community discussions and issues
rather than a quotable specification line. It is the right answer to act on, but it is not
contractual: if GitHub ever grants fork PRs an `id-token`, the wildcard `sub` becomes reachable and
tightening to `repo:<org>/<repo>:ref:refs/heads/*` plus `:pull_request` is the one-line follow-up the
design already anticipated.

## Implementation record (sections 2-5, 7, 8.1)

| § | What landed | Evidence |
|---|---|---|
| 2.1-2.2 | `TagKeys.SOURCE` + `SOURCE_CI`/`SOURCE_LOCAL` in `KNOWN`, absent from `MACHINE_OBSERVED`; `RunCommand.deriveSource(env)` with caller-wins | `TagKeysTest` 6, `RunCommandTest` 40, `UserDataScriptBuilderTest` 40 — includes `CI=false` meaning local, and the tag reaching the rendered user-data as `--tag "source=ci"` |
| 2.3-2.5 | `queryByRequestId` drops the exclusion filter **and** its orphaned `#tags`/`#excluded`/`:excluded` | `ResultsQueryServiceIT` 10 against LocalStack. The landmine was proven load-bearing: re-introducing the orphans alone fails 4 tests with `Value provided in ExpressionAttributeNames unused in expressions: keys: {#excluded, #tags}` |
| 3.1-3.4 | `baas run --format json`, printed on the failure path, payload on stdout | `RunCommandSummaryTest` 5, plus an out-of-process check against the built JAR: `-v` puts DEBUG on stderr while stdout holds exactly one object |
| 4.1-4.3 | Core template federation parameters, condition, conditional trust statement, `MaxSessionDuration: 9000`; CI template reduced to the provider | `CoreTemplateTest` 32, `CiTemplateTest` 5, and `aws cloudformation validate-template` against real CloudFormation for both |
| 4.4-4.7 | `--github-org`/`--github-repo`/`--oidc-provider-arn`/`--revoke-github-oidc`; update path on `updateStackParameters`, create path explicit | `SetupCommandTest` 14 |
| 4.8-4.9 | `iam:UpdateAssumeRolePolicy` added, scoped to the same roles | `DeployerPolicyTest` 42 (rendered size **4105**, exactly as predicted in 1.3), `OperatorPolicyDriftTest` 7 |
| 5.1 | Four workflows and `.github/test/**` deleted | No live reference to `machulav`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME`, `RESOURCE_NAME_PREFIX`, `MONGOSH_VERSION`, `SUBNET_ID`, `SECURITY_GROUP_ID` outside `docs/` and `openspec/` |
| 5.2-5.4 | `e2e-cloud-test.yml` rewritten as one `ubuntu-latest` job | `actionlint` exit 0 across all workflows |
| 5.5 | `ci-pr-build.yml` installs async-profiler, exports `ASYNC_PATH`, **and asserts the test reported `Skipped: 0`** | `actionlint` exit 0 |
| 5.6 | `operatorCredentialsWarning` silent under ambient credentials | `RunCommandTest` |
| 7.1-7.7 | CLAUDE.md, README.md, infra/README.md, review findings, the `baas run` diagram | Every MODIFIED/REMOVED heading checked verbatim against its main spec — all 7 match |
| 8.1 | Full reactor `mvn clean verify` with `ASYNC_PATH` exported | **BUILD SUCCESS**; 310 unit tests in `baas-cli`, 20 ITs, **nothing skipped anywhere**, and `JmhWithAsyncProfilerSubcommandServiceIT` ran (1 test, 40.5 s) rather than silently skipping |

## Open warnings

### W1 — there is no separate `baas-lynx` CI stack, and the stack that exists owns the OIDC provider

The design and task 6.8 speak of deleting "the `baas-lynx` CI stack", by analogy with today's
`cf-template-ci.yaml`. No such stack exists. The live account carries three stacks: `baas-3q7i7s65`
(the current installation), `baas-parameters`, and `baas-main` — a single legacy stack using the
`baas-lynx` resource-name prefix, which declares **all** of:

| Logical ID | Type | Physical |
|---|---|---|
| `GithubOidc` | `AWS::IAM::OIDCProvider` | `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com` |
| `WorkflowRole` | `AWS::IAM::Role` | `baas-lynx-github-actions-workflow-role` |
| `RunnerRole` + `RunnerInstanceProfile` | `AWS::IAM::Role` / `InstanceProfile` | `baas-lynx-github-actions-runner-role` |
| `S3MainBucket` | `AWS::S3::Bucket` | `baas-lynx-main` |
| `S3HookLambdaFunction`, `S3HookLambdaExecutionRole`, `S3HookLambdaPermissionForS3` | Lambda | the `s3-hook-lambda` CLAUDE.md records as gone |

**No resource in `baas-main` carries a `DeletionPolicy`.** Three consequences the plan does not
account for:

1. The provider task 1.1 found — the one this change federates through — *is* `baas-main`'s
   `GithubOidc`. Deleting the stack deletes it, breaking the federation that replaces it.
2. `baas-lynx-main`, which the design explicitly says to leave alone, is a stack resource with no
   retain policy, so a stack delete would attempt to delete it (and fail while it holds objects,
   leaving `DELETE_FAILED`).
3. The reduced `cf-template-ci.yaml` cannot be deployed into this account as written: creating a
   second provider for the same issuer fails with `EntityAlreadyExists`, and the existing one is
   owned by another stack rather than unmanaged.

Task 6.8 therefore cannot be executed as written, and 6.1 resolves to "confirm and reuse the existing
provider" rather than "deploy the reduced CI template".

**Resolved 2026-09-20 by the user: targeted removal.** `WorkflowRole` and its `WorkflowRoleArn`
output are removed from `baas-main`'s template and the stack is updated; the identity provider, the
bucket, `RunnerRole` and the lambda resources all stay. That closes the live risk — two roles this
repository can assume, one unmaintained and holding unscoped `ec2:RunInstances` plus `iam:PassRole`
— without touching the provider the new federation depends on. `design.md`, `proposal.md` and task
6.8 are updated to match.

A change set named `remove-workflowrole` is **staged and `AVAILABLE`** on `baas-main`, built from
the stack's own deployed template with all four parameters carried forward by `UsePreviousValue`.
CloudFormation reports its entire content as one action:

```
Action: Remove | LogicalResourceId: WorkflowRole | Type: AWS::IAM::Role | Replacement: null
```

Executing it was refused by this session's tool sandbox (`Protected-Scope IaC Apply`), so it awaits
the user:

```bash
aws cloudformation execute-change-set --stack-name baas-main \
  --change-set-name remove-workflowrole --profile lynx
```

The role's full definition — trust policy and both inline policies — is captured under task 1.5, so
the removal is reversible. Backups of the stack's original and modified templates are in this
session's scratchpad (`baas-main-original.yaml`, `baas-main-updated.yaml`).

**Residual, deliberately not fixed:** `GithubOidc` still carries no `DeletionPolicy`, so a future
delete of `baas-main` would still destroy the identity provider. Out of scope for this change; worth
a one-line protective edit before anyone retires that stack.


### W2 — the repository list is composed by the CLI, not rendered by the template

Task 4.2 says the template should render "one `StringLike` value per repository" from a
`CommaDelimitedList` of bare repository names. It cannot, and the reason is worth recording because
it looks like it should work. CloudFormation cannot iterate a list, `Fn::Join`'s delimiter must be
a literal, and the one in-template trick that appears to work —

```yaml
!Split [",", !Sub ["repo:${GitHubOrg}/${J}:*", {J: !Join [":*,repo:${GitHubOrg}/", !Ref GitHubRepo]}]]
```

— relies on `Fn::Sub` re-scanning text it substituted from a variable, which it does not do. It
renders correctly on LocalStack (verified: `repo:org/repo-a:*|repo:org/repo-b:*`) and would leave
the second and later repositories as literal `repo:${GitHubOrg}/<name>:*` in real CloudFormation.
That fails closed rather than open, but silently.

Resolved by the user on 2026-09-20: `SetupCommand` composes the full subject patterns and the
template refs the list straight into the `StringLike` condition. Any number of repositories, no
template change, correct in real CloudFormation. The cost is that the stack parameter holds
`repo:<org>/<name>:*` patterns rather than bare names — arguably more informative on readback.
`GitHubOrg` remains a parameter so the installation still records which organisation it trusts.

### W3 — `source=ci` is not asserted by the workflow itself

`baas results --format json` projects score columns plus `imageVersion`/`instanceType`, not the
whole tag map; `--request-id` refuses to combine with `--tag` (the filter would be silently
ignored); and the project sweep that does take `--tag` is exactly what this run is excluded from.
Asserting the stored `source` from the job would mean re-implementing a DynamoDB query in workflow
shell, which is the class of thing this change exists to stop doing.

Covered instead by `RunCommandTest` (all three derivation paths, plus the tag reaching the rendered
user-data) and by task **6.5**, which inspects the stored item by hand. Worth revisiting if
`baas results` ever projects the full tag map.

### W4 — `RunCommand.call()`'s success path is still executed by no test

CLAUDE.md recorded this before the change and it is still true. The JSON summary's shape is pinned
against `printRunSummary` directly, and the wiring through `call()` is driven only on a path that
fails before any AWS call. The success path now has end-to-end coverage in CI rather than in the
JVM — which is the point of the change, but it means a `--format json` regression on the success
path would be caught by a paid workflow run, not by `mvn verify`.

### W5 — three sequence diagrams had never rendered

Not caused by this change, found while doing 7.6. `docs/diagrams/baas-run.mmd`,
`baas-build-image.mmd` and `baas-teardown.mmd` all failed to parse; only `baas-setup.mmd` rendered.
Two independent causes, both invisible without actually running a renderer — and CLAUDE.md's "no
checked-in SVGs" rule means nothing ever did:

1. **`;` in note or message text.** Mermaid treats it as a statement separator, so everything after
   it parses as a new statement.
2. **`&lt;` / `&gt;` entities.** Rejected by mermaid's lexer in both notes and messages; raw `<`
   and `>` parse fine. `<br/>` is a real mermaid line break and stays.

All four now render (`minlag/mermaid-cli`). Worth a lint step if diagrams keep drifting.


## Section 6 — live account, in progress

| Task | State | Detail |
|---|---|---|
| 6.1 | **Done** | Provider confirmed as `arn:aws:iam::381492019823:oidc-provider/token.actions.githubusercontent.com`, matching 1.1. The reduced `cf-template-ci.yaml` is deliberately *not* deployed here — the provider already exists (owned by `baas-main`), and a second for the same issuer fails `EntityAlreadyExists`. The template stands for a fresh account |
| 6.2 | **Done** | Both halves verified live — see below. Took three attempts and two missing deployer grants (W6, W7), one of which stranded the stack in `UPDATE_ROLLBACK_FAILED` |
| 6.3 | **Partly done** | `OPERATOR_ROLE_ARN` and `CORE_STACK_NAME` set as repository variables; `AWS_REGION` already existed. Secret deletion left to the user — see below |
| 6.4-6.7, 6.9 | Not started | Depend on 6.2, and on the branch reaching GitHub |
| 6.8 | **Staged** | `remove-workflowrole` change set `AVAILABLE` on `baas-main`; execution refused by the session sandbox — see W1 |

### W6 — the deployer policy must be re-attached before 6.2 can run

`deployer-policy.json` is a template rendered per caller, so adding `iam:UpdateAssumeRolePolicy` to
the file changes nothing in AWS until the rendered policy is attached again. Confirmed live:

```
iam:UpdateAssumeRolePolicy  implicitDeny
iam:CreateRole              allowed
cloudformation:UpdateStack  allowed   (against the stack ARN)
```

Neither `baas-admin` nor `lynx` can attach IAM policies, so this is the user's step. The rendered
document for this caller is 4105 non-whitespace characters — the same figure the budget test
measures — and `baas admin deployer-policy` prints it.

**A gap this change introduced, found here and fixed.** `DeployerPreflight` did not probe the new
action, so the preflight would have passed and the stack update would have failed partway on the
trust-policy edit, leaving `baas-3q7i7s65` in `UPDATE_ROLLBACK_COMPLETE`. That is precisely the
failure the `dynamodb:CreateTable` probe was added to prevent, arriving from a new direction —
`iam:UpdateAssumeRolePolicy` is newer than any policy attached before it existed, so the
stale-policy case is the expected one, not the exotic one. `iam:UpdateAssumeRolePolicy` now joins
the probed actions, scoped to the operator role, pinned by
`simulatesTheTrustPolicyEditSoAStalePolicyFailsBeforeTheStackRollsBack`.

Verified end to end against the live account: `baas admin setup --oidc-provider-arn … --github-org …
--github-repo …` exits 1 with "This identity cannot iam:UpdateAssumeRolePolicy", prints the rendered
policy, and deploys nothing. The stack's `LastUpdatedTime` is unchanged.


### W7 — federating changes OperatorRole through *two* IAM APIs, and missing the second stuck the stack

W6 found `iam:UpdateAssumeRolePolicy`. That was half the answer. Raising `MaxSessionDuration` to
9000 is a *different* API — `iam:UpdateRole` — and the deployer policy granted neither. With only
the first attached, `baas admin setup` passed preflight and CloudFormation failed partway:

```
OperatorRole: User ... is not authorized to perform: iam:UpdateRole on resource:
role 3q7i7s65-operator-role ... (HandlerErrorCode: AccessDenied)
```

The rollback then failed on the *same* action — unwinding `MaxSessionDuration` needs
`iam:UpdateRole` just as setting it does — so the stack landed in **`UPDATE_ROLLBACK_FAILED`**,
which no retry clears. The role itself was never damaged: `MaxSessionDuration` 3600, account-root
principal only, exactly its pre-update state.

**Fixed:**
- `deployer-policy.json` grants `iam:UpdateRole` alongside `iam:UpdateAssumeRolePolicy`, scoped to
  the same roles. Rendered size 4122 non-whitespace, 486 under the 4608 budget.
- `DeployerPreflight` probes **both**, with the comment saying why enumerating one was not enough.
- `everyProbedIamActionIsGrantedByTheRenderedPolicy` states the invariant as a rule rather than as
  two cases: a probe for an action the printed policy does not grant would tell the user to attach
  a policy that still cannot deploy.

Verified live: with the first policy attached, `baas admin setup` now exits 1 with "This identity
cannot iam:UpdateRole", prints the policy and deploys nothing. A full simulation of every action an
`AWS::IAM::Role` update can issue shows `iam:UpdateRole` as the only remaining denial
(`iam:UpdateRoleDescription` is a deprecated API CloudFormation does not use, and the template sets
no description), so the next attach should be the last.

**Not fixed, needs a decision.** The deployer policy grants no
`cloudformation:ContinueUpdateRollback`, so the deployer cannot recover a stack it is itself capable
of stranding — recovery needs a higher-privileged identity. Adding it was refused by this session's
tool sandbox as a permission grant. It is a named, stack-scoped, recovery-only action and arguably
belongs in the policy; left to the user.

### Lesson worth keeping

Both W6 and W7 are the same mistake: a template edit changes what the deployer must be allowed to
do, and the policy is a *template rendered and attached out of band*, so the code and the deployed
permission drift silently until a real deploy. The preflight is the only thing that converts that
into a cheap failure — which is why it must probe every action a change adds, not just the one that
motivated it. Worth a line in CLAUDE.md if this recurs.


## 6.2 verified live against `baas-3q7i7s65`

**Federation deployed.** Stack `UPDATE_COMPLETE`. `3q7i7s65-operator-role` afterwards:

- `MaxSessionDuration: 9000` — above the 7500 s wall-clock default, as the design requires
- **Both** principals present: `arn:aws:iam::381492019823:root` with `sts:AssumeRole`, and the
  federated provider with `sts:AssumeRoleWithWebIdentity`
- `aud` pinned to `sts.amazonaws.com`; `sub` `StringLike` `repo:wsztajerowski/benchmark-as-a-service:*`

The CLI-composed subject pattern (W2) reached the trust policy intact, and the stack parameter
`GitHubRepo` holds `repo:wsztajerowski/benchmark-as-a-service:*` — confirming the decision to
compose in `SetupCommand` rather than in the template renders correctly in **real** CloudFormation,
which the in-template alternative would not have.

**Carry-forward proved in the environment that matters.** A second `baas admin setup` naming no
federation options reported *"Stack baas-3q7i7s65 is already up to date"* — a genuine no-op — and
the federated statement, both principals and `MaxSessionDuration: 9000` were all still in place
afterwards. Had the old `createOrUpdateStack` path survived, this run would instead have resubmitted
`Default: ""`, flipped the condition false and silently removed CI's access. That is the failure the
`updateStackParameters` switch exists to prevent, and it is now observed not to happen.

## Preconditions confirmed for 6.4

| Precondition | State |
|---|---|
| Runner AMI | `ami-0aa25ec7fbf1c80f5` (`3q7i7s65-runner-2026-08-14T18-18-41.539Z`), `available` — `baas run` has an image to boot |
| `baas config sync --core-stack-name baas-3q7i7s65` | Succeeds |
| `baas results --format json` | Returns well-formed JSON with `.` decimal separators, so the workflow's `jq` assertions parse (the pl-PL locale hazard does not apply — `printJson` uses `Locale.ROOT`) |
| Repository variables | `OPERATOR_ROLE_ARN`, `CORE_STACK_NAME`, `AWS_REGION` all set |
| Workflow on GitHub | Pushed as branch `cli-driven-ci-workflows` (commit `5ec43ee`) |

**Baseline for 6.6, already in the table.** Project `benchmark-as-a-service` holds two prior runs of
the same fixture benchmark on the same image and instance type, so the comparison needs no
archaeology:

| Run | Score (ops/s) | imageVersion | instanceType |
|---|---|---|---|
| `jmh-20260819_082707` | 9,071,763 | 1.2.0 | c5.2xlarge |
| `20260908T152147852Z-ee088133` | 10,025,544 | 1.2.0 | c5.2xlarge |

Both carry error bars larger than the gap between them (±12.5M and ±6.3M on a single-iteration run),
which is the variance the task warns about — the finding will be whether the new score sits in that
band, not whether it matches either number.


## 6.4 — first dispatch

Branch `cli-driven-ci-workflows` pushed, workflow dispatched manually:
<https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/35522172677>

`workflow_dispatch` resolves the workflow file from the dispatched ref, so this runs the rewritten
single-job version even though `main` still carries the old one. Nothing fires automatically — no
pull request was opened, so the path-filtered `pull_request` trigger has not been exercised yet.

The commit is marked `feat(ci)!` with an explicit `BREAKING CHANGE` footer, so merging it to `main`
will bump the major version. That is faithful to the change (the consumer contract moves from
*call our reusable workflow* to *install the CLI*), but it is a release-visible consequence worth
confirming before merge rather than discovering in the release notes.


### W8 — the first real CI run found a defect no JVM test could have

Run [35522172677](https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/35522172677)
**benchmarked successfully** and stored its measurement, then failed on the first assertion:

```
$BAAS results --request-id "" --format json
One or more parameter values are not valid. The AttributeValue for a key attribute
cannot contain an empty string value. Key: gsi1pk
```

The run id was empty. `RunCommand.showResults` prints the post-run table through
`ResultsQueryService.printTable`, which writes to `System.out` — correctly, because a table is a
command *payload*, not a diagnostic, and CLAUDE.md lists it among the deliberately un-migrated
writers. But the `--format json` summary is a payload on that same stream, and two payloads on one
stream is not a stream anyone can parse. The table landed first; `jq` failed on the opening token.

**Why the tests missed it.** `verboseDiagnosticsDoNotCorruptTheObject` drove a path that fails
before launching, which never reaches `showResults`. That is precisely W4 — "`RunCommand.call()`'s
success path is executed by no test" — and it bit within one run of being written down. The CI run
was the first thing ever to execute that path.

**Fixed.** `reportRunResults` suppresses the table under `--format json` and says on the logger
where the rows are instead. The rows are deliberately *not* folded into the summary object: they are
a separate concern with a separate command, and the object carries the run id so
`baas results --request-id` can fetch them. The printer is passed as a `Consumer` so the reporting
path is testable without an AWS client — `theResultTableIsSuppressedUnderJsonSoStandardOutputHoldsTheObjectAlone`
now asserts stdout holds exactly one JSON object, and `theResultTableStillPrintsWithoutTheOption`
pins the default.

The workflow also now fails loudly when a successful run yields no `runId`, naming itself, rather
than passing an empty string three steps downstream into an error that mentions `gsi1pk`.

### What run 35522172677 did prove

Everything except the stdout collision worked on the first attempt:

| Checked | Result |
|---|---|
| OIDC federation into `OperatorRole` | `Configure AWS credentials: success` — the trust statement, the `sub` pattern and `role-duration-seconds: 9000` all accepted. The last would have failed outright against the old 3600 `MaxSessionDuration` |
| `baas config sync` from the deployed stack | success |
| `baas run` on the real runner AMI | `Run the benchmark on EC2: success`, instance `i-03c3ad558f45b388c` (`c5.2xlarge`, `ami-0aa25ec7fbf1c80f5`) |
| One clock read per run | `createdAt` `2026-09-20T16:16:36.923…` ↔ runId `20260920T161636923Z-08785de7` — same millisecond |
| `perf` pinned to the kernel | `perfVersion` and `kernelRelease` both `6.1.177-224.371.amzn2023` |
| Profiler tunables live | `perfEventParanoid: 1`, `kptrRestrict: 0` |
| Image-dependent artifacts | `flame-wall-forward.html`, `flame-wall-reverse.html`, `jfr-wall.jfr` under the benchmark directory |
| `--runner-jar` stays out of `releases/` | both JARs under `runs/…/input/` |
| Manifest written before the benchmark | `environment.json` + `packages.txt` at 18:17:00, benchmark output after |
| **Exclusion contract (§2.3)** | `baas results --request-id 20260920T161636923Z-08785de7` returns the row **despite `exclude_from_results=true`** — the change verified in production, not just against LocalStack |
| Termination | `run-status: completed`, instance `terminated` — by the CLI, not the watchdog |

**Score: 11,590,125 ops/s**, `imageVersion 1.2.0`, `instanceType c5.2xlarge` — the same image and
instance as both baselines (9.07M, 10.02M) and inside the CI history band (10.0M-29.6M). Preliminary
for 6.6, pending a green run.


## 6.4-6.6 — green

Run [35526129095](https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/35526129095)
**succeeded**, every step green, on commit `11c2a64`. Run id `20260920T173156909Z-fedd5ee4`,
instance `i-0cda61e728d925e03`.

**6.5 — the stored item.** Queried through the operator role:

```
pk  RESULT#benchmark-as-a-service
sk  pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized#thrpt
    #2026-09-20T17:31:56.909Z#20260920T173156909Z-fedd5ee4
tags
    source               = ci
    exclude_from_results = true
    imageVersion = 1.2.0    instanceType = c5.2xlarge    jdk = 25.0.4
    cpuModel = Intel(R) Xeon(R) Platinum 8275CL CPU @ 3.00GHz    cpuArch = x86_64
    project = benchmark-as-a-service    type = jmh-with-async
    branch = cli-driven-ci-workflows    commit = 11c2a6429d9c43d9392fdcc07418ca72e595c028
```

`source = ci` is derived by `baas run` from the environment, not passed by the workflow — **W3 is
resolved**: the derivation works in a real continuous-integration environment, which is the check
the workflow itself cannot make. The sort key carries `mode` and a fixed-width millisecond
timestamp, as the schema requires.

Both halves of the exclusion contract, live:

- `baas results --request-id 20260920T173156909Z-fedd5ee4` → returns the row
- `baas results --project benchmark-as-a-service --all` → 5 rows, **not** including it

S3 prefix holds `environment.json`, `jmh-result.json`, `jmh-with-async-output.txt`, `packages.txt`,
`cloud-init-output.log`, `run-status` (`completed`), `input/{benchmark,runner}.jar`, and under
`pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized-Throughput/`:
`flame-wall-forward.html`, `flame-wall-reverse.html`, `jfr-wall.jfr`. Instance `terminated` — by the
CLI, within the job, not by the shell watchdog.

**6.6 — score comparison.**

| Run | Type | Score (ops/s) | ±error | image | instance |
|---|---|---|---|---|---|
| `jmh-20260819_082707` | jmh | 9,071,763 | ±12,462,803 | 1.2.0 | c5.2xlarge |
| `20260908T152147852Z-ee088133` | jmh | 10,025,544 | ±6,261,229 | 1.2.0 | c5.2xlarge |
| `20260920T161636923Z-08785de7` | jmh-with-async | 11,590,125 | 0 | 1.2.0 | c5.2xlarge |
| `20260920T173156909Z-fedd5ee4` | jmh-with-async | 12,239,764 | 0 | 1.2.0 | c5.2xlarge |
| `20260920T174231871Z-9afe54f3` | jmh-with-async | 11,295,939 | 0 | 1.2.0 | c5.2xlarge |

Same `imageVersion`, `instanceType` and `jdk` throughout, and both new scores sit inside the
10.0M-29.6M band CI history spans. **The comparison cannot say more than that, and the reason is
worth stating rather than glossing:** the self-test runs `-f 1 -wi 1 -i 1`, so a single iteration
with no error estimate — hence `scoreError = 0`, which means "not measured", not "perfectly
precise". The two baselines carry error bars (±12.5M, ±6.3M) wider than the entire spread between
all four numbers. No regression is indicated, and with this configuration none could be detected.
The self-test is a *functional* check of the image, profiler and upload path; it is not, and should
not be read as, a performance guard.

Mildly counter-intuitive and explained by the above: the `jmh-with-async` runs score *higher* than
the un-profiled `jmh` baselines, despite profiling overhead. At one iteration that is noise.


## 6.7 — failure path, and a second defect it caught

Forced a failing run locally (`-- NoSuchBenchmarkClassXYZ`), run id
`20260920T173454249Z-87d8abb2`, instance `i-07f766937b3c9318f`.

Everything the task asks for holds:

```
EXIT CODE: 1
stdout: {"runId":"20260920T173454249Z-87d8abb2","project":"benchmark-as-a-service",
         "resultPath":"runs/benchmark-as-a-service/20260920T173454249Z-87d8abb2",
         "status":"failed","exitCode":1,"instanceId":"i-07f766937b3c9318f"}
stderr: Run status: failed:1 / Benchmark failed. Runner log: s3://…/cloud-init-output.log
        Terminating instance i-07f766937b3c9318f ...
```

One JSON object on stdout, `status: "failed"`, the command still exits non-zero, diagnostics on
stderr, and the shutdown hook terminated the instance.

### W9 — `baas download <runId>` cannot retrieve a failed run

```
$ baas download 20260920T173454249Z-87d8abb2
ERROR DownloadCommand - No run found with id '20260920T173454249Z-87d8abb2'. Nothing was written.

$ baas download runs/benchmark-as-a-service/20260920T173454249Z-87d8abb2
INFO  DownloadCommand - Downloaded 7 artifact(s) …
```

`download` resolves a bare run id through `requestId-index`, and a run that **failed stored no
measurement** — so it has no item, no index entry, and the lookup reports "No run found" precisely
when `cloud-init-output.log` is most wanted. CLAUDE.md calls that log "the documented starting
point when a run fails before producing output"; by run id, it was unreachable.

The result path resolves it (7 artifacts, boot log included), which is exactly why the spec lists
`resultPath` among the summary's required fields — a detail that reads as redundant next to `runId`
until this case, and is load-bearing here.

**Fixed in the workflow:** the failure step downloads by `result_path`, not `run_id`, gated on
`result_path != ''`. Caught only because 6.7 was executed rather than assumed — the workflow had
shipped the broken form.

**Left open deliberately:** `baas download` itself still cannot resolve a failed run by its id. The
natural fix is to fall back to reconstructing the prefix from the id, or to consult S3 when the
index misses — but the run id alone does not name the project, and `RunLayout` needs both, so it is
not a one-liner. Out of scope here; worth its own change. Nothing in this change depends on it now
that the workflow uses the path.


## 6.9 — revocation, in progress

`baas admin setup --revoke-github-oidc` updated the stack (`UPDATE_COMPLETE`) and submitted all
three federation parameters explicitly empty:

```
GitHubOrg = ""   GitHubOidcProviderArn = ""   GitHubRepo = ""
```

`3q7i7s65-operator-role` afterwards carries **only** `arn:aws:iam::381492019823:root` — the
federated statement is gone and the account-root principal survives, so revoking cannot lock a
local operator out. `MaxSessionDuration` stays 9000, correctly untouched: it is not a federation
parameter, and carry-forward preserved it through an update that changed three others.

**Revoked — CI loses access.** Run
[35526505294](https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/35526505294):

```
Configure AWS credentials: failure
##[error]Could not assume role with OIDC: Not authorized to perform sts:AssumeRoleWithWebIdentity
Sync configuration from the deployed core stack: skipped
```

It launched **no instance** (0 benchmark runners running while it executed), because it fails before
`baas run` is reached — so revocation is free to test as well as effective.

**Restored — CI regains access.** `baas admin setup` with the three federation options put the
statement back (`UPDATE_COMPLETE`), and run
[35526693984](https://github.com/wsztajerowski/benchmark-as-a-service/actions/runs/35526693984)
passed `Configure AWS credentials` and `baas config sync`, launched instance
`i-0d2bb541ec5fcfee0` for run `20260920T174231871Z-9afe54f3`, and **completed green** with every
assertion passing. Score 11,295,939 ops/s, instance `terminated`.

Both directions observed, on the live installation. `--revoke-github-oidc` is the supported way to
cut CI's access, and nothing else does it by accident — proven by 6.2's carry-forward run, which
left the trust untouched.


## 6.3 — repository secrets and variables swapped

The repository now carries **zero secrets** and three variables:

```
AWS_REGION         eu-central-1
CORE_STACK_NAME    baas-3q7i7s65
OPERATOR_ROLE_ARN  arn:aws:iam::381492019823:role/3q7i7s65-operator-role
```

Deleted, after confirming nothing on **either** branch still reads them — `release.yml` uses only
the automatic `secrets.GITHUB_TOKEN`, and `ci-pr-build.yml` and `install-test.yml` reference none:

| Deleted | Was read by |
|---|---|
| `WORKFLOW_ROLE_ARN`, `GHA_EC2_PAT`, `RUNNER_ROLE_NAME` | the deleted benchmark workflows only |
| `GHA_COMMIT_PAT` | **nothing** — checked specifically, since the name suggests release use; `release.yml` uses `secrets.GITHUB_TOKEN` |
| `MONGO_CONNECTION_STRING`, `RUNNER_ROLE_ARN` | nothing |
| `SUBNET_ID` = `subnet-0a1f95224665189f3` | the deleted benchmark workflows only |
| `SECURITY_GROUP_ID` = `sg-0794efe9e1c8c597a` | the deleted benchmark workflows only |
| `ASYNC_PROFILER_VERSION` = `4.1` | the deleted workflows; `ci-pr-build.yml` now pins it as a job-level `env` instead, tracking `infra/runner-image.yaml` |

`RESOURCE_NAME_PREFIX` needed no deletion — it never existed as a repository variable; the old
workflows fell back to their `|| 'baas'` default, which is finding **A10**'s drift to
`baas-lynx-main` in its original form.

Variable values are recorded above because they are recoverable; **secret values are not** — a
secret has to be reissued rather than restored. Nothing needs them: the three secrets tied to the
old path die with it, and the operator role ARN is deliberately a plain variable because a role ARN
is not sensitive.

**Known consequence, accepted.** `main` still carries the old workflows, which reference the deleted
names, so they would now fail earlier than before. They have never passed a run — that is this
change's premise — and `ci-pr-build.yml`, `install-test.yml` and `release.yml` are unaffected. The
branch merge removes those files.


## 8.2 — verification report

Full reactor re-run after the last code change, with `ASYNC_PATH` exported: **BUILD SUCCESS** —
315 unit tests and 20 integration tests in `baas-cli`, `JmhWithAsyncProfilerSubcommandServiceIT`
executed (38.7 s), and **0 skipped tests anywhere in the reactor**.

| Dimension | Status |
|---|---|
| Completeness | 46/48 tasks; 17/17 requirements implemented |
| Correctness | 17/17 requirements covered; 2 scenarios satisfied by construction |
| Coherence | Design followed; 2 deviations, both resolved with the user and folded into design.md |

### Requirement → evidence

| Capability | Requirement | Evidence |
|---|---|---|
| ci-benchmark-execution | CI drives the CLI, not its own orchestrator | `e2e-cloud-test.yml` is one job; no launch/terminate/tag call, no id or key built in shell |
| | Authenticates as operator, no intermediate role | Live: `Configure AWS credentials: success` against a trust policy with one federated statement; `WorkflowRole` deleted from the template |
| | Mechanism not narrowed to the self-test's type | `benchmarkType` is still `@Parameters(index="0")` against `VALID_TYPES`; the workflow passes it via `BENCHMARK_TYPE` |
| | Self-test covers the image-dependent type, discards its numbers | 3 green runs producing flamegraphs + JFR; every measurement tagged `exclude_from_results=true` |
| | Can correlate and diagnose the run it launched | `--format json`; verified on both success and a forced failure |
| | Assertions satisfiable by the run that produces them | Every assertion reads `steps.run.outputs.*`; no standalone literal |
| | Triggered deliberately, not on every push | Path-filtered `pull_request` + `workflow_dispatch`, no schedule |
| cli-command-structure | `baas run --format json` | `RunCommandSummaryTest` (7), plus live stdout on success and failure |
| core-stack-provisioning | Federation into the operator role | `CoreTemplateTest` (32) + live trust policy |
| | Parameters carried forward, revoked only on request | `SetupCommandTest` (14) + live: an unrelated setup was a no-op, `--revoke-github-oidc` removed the statement |
| | CI stack holds only the identity provider | `CiTemplateTest` (5) |
| | (MOD) Core stack holds only CLI-owned infrastructure | `federationAddsNoResourceToTheCoreStack` |
| | (MOD) baas-cli never manages the CI stack | Only IAM call in `SetupCommand` is the preflight simulation |
| | (MOD) Deployer policy holds no CI-stack permissions | `DeployerPolicyTest` (42) |
| | (MOD) Operator identity is an assumable role | `theAccountRootPrincipalSurvivesAlongsideTheFederatedOne` + live |
| benchmark-results-query | Exclusion applies to sweeps, not to id lookups | `ResultsQueryServiceIT` (10) + live both ways |
| results-store-schema | `source` in the shared vocabulary | `TagKeysTest` (6), `RunCommandTest` (40); live item carries `source = ci` |

### Remaining

**6.8** — `remove-workflowrole` change set `AVAILABLE` on `baas-main`, one action (`Remove
WorkflowRole`). Execution refused by the session sandbox; awaits the user.

### Suggestions (not blocking)

1. `bestPerGroup` is tag-generic, so grouping by `source` works by construction, but every grouping
   test passes `ResultsFilters.BRANCH`. One case with `source` would pin the scenario.
2. Nothing asserts that `SetupCommand` performs *no* IAM lookup for the provider ARN — it is true
   by construction and the ARN is pinned as forwarded verbatim.
3. W9 (`baas download` cannot resolve a failed run by id) is worked around in the workflow and left
   open in the CLI; it needs its own change.

### Assessment

No critical issues in the implementation. One task remains and it is an AWS operation the user must
run. **Ready to archive once 6.8 is executed** — and note that merging carries an explicit
`BREAKING CHANGE` footer, so the release will bump the major version.
