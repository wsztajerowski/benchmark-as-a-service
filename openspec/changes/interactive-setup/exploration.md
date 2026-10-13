# Exploration — interactive-setup

Explored 2026-10-10 → 2026-10-13 with the user, from the live end-to-end test of v7.0.0
(`archive/2026-10-12-pr83-review-fixes/verify.md`, *Full end-to-end test*), re-grounded on `main` at
v7.1.1. It builds on [`setup-prerequisites`](../archive/2026-10-13-setup-prerequisites/exploration.md): the derived
preflight, `--policy-file`, and the removal of existing-VPC support land there first.

**Status (2026-10-13):** every decision below is taken; nothing is waiting on the user.
`setup-prerequisites` was merged into `next-release` (PR #94) and archived with its specs synced
(`dd3cde1`). This change is #1 in `QUEUE.md`. **Next step: `/opsx:propose interactive-setup`**,
which resolves the items under *Open for the proposal* one question at a time.

## Why

A new user meets setup as one command plus a set of steps documented across two READMEs. As of
v7.1.1 (`derived-deployment-config` removed the operator profile and `config sync` from the admin's
and CI's paths), the remaining journey is:

```
 SOLO ADMIN                      setup → image build → run            (3 commands; 2 could be 1)
 DEPLOYER WITHOUT ADMIN RIGHTS   setup > policy.json → IAM admin attaches → setup again
 TEAMMATE                        IAM admin grants sts:AssumeRole → config sync
 CI                              OIDC provider by hand (identity above the deployer) →
                                 setup --oidc-provider-arn … → two GitHub variables
```

What a guided flow can take off the user:
- **Steps they must look up:** what to attach and where, the image build as a separate 15-minute
  command, the teammate's grant, the OIDC provider and the GitHub variables.
- **Steps that end in a bare refusal and a re-run:** missing rights, a leftover bucket or table,
  a stale policy (`setup-prerequisites`).
- **Hints printed without `--deployment`**, which fail as printed once two deployments exist
  (finding E2).

## Decisions

### D1 — A guided walkthrough, not a form

Two shapes were compared (2026-10-11):
- **A, the wizard:** collect every value, then deploy in one batch.
- **B, the walkthrough:** an ordered list of steps, each a desired state with a probe that checks it
  against AWS and the local machine; the step is then done by `baas` or explained to the user, waited
  for, and probed again.

B was chosen. Manual steps are its core primitive rather than exceptions. It needs no saved progress:
every step re-probes reality, so quitting and re-running later (once an IAM admin has acted)
continues at the first unmet step. CLAUDE.md treats invisible state as the thing to avoid, and B
stores none. A re-run on a finished deployment shows every step met and does nothing. The values A
would collect remain, as the inputs of the early steps.

### D2 — Interactive by default; `--non-interactive` and no terminal run the same steps without waiting

Interactive exactly when `Console.interactive()` (a terminal, `TERM` not `dumb`) and
`--non-interactive` is absent (user, 2026-10-13). Without a terminal (a redirect, CI, a pipe) the
flow is non-interactive automatically, so `setup > policy.json` and scripts behave as today.

| At a step that needs… | interactive | non-interactive |
|---|---|---|
| an input | ask, showing the default | the flag, else today's default |
| the user's action outside `baas` | explain, wait, probe again | explain, **stop with exit 1** |
| `baas`'s own action | do it | do it |

The non-interactive row is today's behaviour on missing rights, generalised to every manual step.
Teardown's `--yes` keeps its meaning ("confirmed"); setup gains no `--yes`.

### D3 — The steps

| # | Step | Probe | Done by `baas` | Done by the user (explained, waited for) |
|---|---|---|---|---|
| 1 | Credentials | `sts:GetCallerIdentity` on the chosen deployer profile; shows account and ARN for confirmation | — | `aws configure` / `aws configure sso`, or `aws sso login` on an expired token |
| 2 | Deployment and region | configured files; `DeploymentNameRule`; the stack exists? | — | choose a name (the derived `baas-<account>` shown as a default the user accepts); choose the region on create, with "cannot be changed later". With ≥2 deployments configured, choose one instead of being refused |
| 3 | Names are free | `BucketProbe`, `tableExists` (existing) | — | remove a leftover (the refusal's own command); setup never deletes one |
| 4 | Deployer permissions | the derived preflight (`setup-prerequisites` D1) | write the policy file (D2 there) and print the filled-in `aws iam` commands | an IAM admin attaches it; IAM propagation can lag, so "not visible yet" is distinct from "missing" |
| 5 | GitHub Actions (optional) | org and repos asked; provider exists, and lists `sts.amazonaws.com` as an audience (D4) | create the provider when absent (D4) | add the audience to an existing provider that lacks it (never modified by `baas`) |
| 6 | Plan and confirm | — | show account, region, name, create/update, federation, standing cost (~$0.20/month for the image snapshot) | confirm |
| 7 | Stack | stack status | create/update, with a status line | — |
| 8 | You can operate it | `AssumeRole` into `<name>-role-operator` from the deployer source, as `OperatorSession` does | — | — (the deployer policy grants it since 7.1; a failure here means step 4's policy is stale) |
| 9 | Runner image | the AMI pointer, and the image version against the bundled base | build it (D5) | — |
| 10 | Done | — | print the first `baas run`, with `--deployment` when ≥2 are configured; for a teammate: the `sts:AssumeRole` grant (filled in) and `baas --deployment <name> config sync --operator-aws-profile <p>`; with federation: `gh variable set OPERATOR_ROLE_ARN …` and `AWS_REGION …` | — |

The GitHub variables are **printed, never shown as met**: `baas` holds no GitHub credentials and
shells out to neither `gh` nor `aws`. The teammate's half needs no walkthrough of its own, since
`config sync` already proves access before it writes.

### D4 — `baas` creates the GitHub OIDC provider, and never modifies or deletes it

Decided by the user, 2026-10-13. This reverses `baas-cli-core-ci-split` D1/D2 ("the local CLI never
creates, reads, updates or deletes anything related to GitHub OIDC; a hard security boundary"),
whose rationale was limiting the deployer's blast radius. That rationale was overtaken by the
accepted risk *Deployer privilege* (`iam:CreateRole` plus a trust policy already makes the deployer
policy effectively account admin), and `cli-driven-ci-workflows` already had setup write the
federated trust onto `OperatorRole`. A new ADR supersedes those decisions and records the rule that
survives: **create, never modify or delete.**

- **ARN derived:** `arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`.
  `--oidc-provider-arn` goes; `--github-org`, `--github-repo` and `--revoke-github-oidc` stay.
- **Created through the IAM API, outside the stack**, so teardown cannot delete it. It is
  account-global (one per issuer URL) and may serve other projects. Teardown says it leaves it.
- **An existing provider is reused.** If it lacks the `sts.amazonaws.com` audience, `baas` prints the
  IAM admin's `aws iam add-client-id-to-open-id-connect-provider …` line and waits (interactive) or
  stops (non-interactive). It does not modify a shared provider.
- **Deployer policy:** `iam:GetOpenIDConnectProvider` and `iam:CreateOpenIDConnectProvider` on that
  one ARN, plus `iam:TagOpenIDConnectProvider` only if creation tags it. Budget: the rendered policy
  is 4370 non-whitespace characters against `renderedPolicyLeavesRoomInAnInlinePolicyBudget`'s 4608;
  ~250 more crosses it. Keep the statement minimal, or revisit the ceiling (IAM allows 6144 for a
  customer-managed policy, which a second deployment already requires).
- **Deleted:** `infra/cf-template-ci.yaml`, its section of `infra/README.md` (line 132's stale
  `--deployment baas-ci` included), CLAUDE.md's `cf-template-ci.yaml` bullet and its "deploy order
  inverts" text, and the reference under *IAM is split deliberately*.
- **Specs (`core-stack-provisioning`):** *Deployer policy holds no CI-stack permissions* is reversed
  (its scenario *No OIDC actions present* asserts the opposite), so REMOVED verbatim with a new
  requirement, or MODIFIED. *GitHub Actions federates directly into the operator role* and
  *Federation parameters are carried forward, and revoked only on request* lose the ARN parameter;
  the scenario *An existing account-global provider is reused* changes owner. Check every header
  verbatim before writing the deltas.
- **Spike:** whether `CreateOpenIDConnectProvider` still requires a thumbprint list for GitHub's
  issuer (AWS stopped using it for GitHub in 2023 and later made it optional; confirm from the API
  reference).

### D5 — The image build is offered, default yes

Step 9 asks "Build the runner image now? ~15 min [Y/n]" when the image is absent or behind the
bundled base, and never when it is current (user, 2026-10-13). Non-interactive builds only with
`--build-image`, so a scripted re-setup never pays 15 minutes by surprise. This keeps the reason
CLAUDE.md gives for setup not building (*every re-setup would pay for it*): it argues against
building by default, not against an offer.

### D6 — v1 covers steps 1–10, CI included

User, 2026-10-13. There is no existing-VPC picker, because `setup-prerequisites` removes the option
altogether.

### D7 — Every printed next step carries `--deployment` when it is needed

Finding E2: setup's closing text, `RunCommand`'s no-image refusal (`Build one: baas admin image
build`, which also says "account" where it means the deployment), `RunCommand`'s results hint, and
`ConfigShowSubcommand`. One helper renders a hint with `--deployment <name>`, either whenever more
than one deployment is configured or always. Folded in here because step 10 prints most of them.

## What `setup-prerequisites` settled, and what it means here

Learned while implementing and verifying it (`archive/2026-10-13-setup-prerequisites/verify.md`).
Each item is a fact the walkthrough builds on, not a new decision.

- **Step 4's probe exists:** `DeployerPreflight.simulate(callerArn, renderedPolicy)` returns the
  denied actions, or nothing when the caller may not simulate.
  - It costs **13 `SimulatePrincipalPolicy` calls, ~4–6 s**, so an "Enter to re-check" loop costs
    that per press.
  - IAM throttles this API hard. One call per action took ~26 s, and eight parallel callers
    exhausted the SDK's retries and silently turned the check off. **Never parallelise it, and
    never re-probe in a tight loop**; re-check only on the user's keypress.
  - A throttle that survives the retries reports "cannot check". The walkthrough must show that
    as *not checked*, never as met.
- **Step 4's hand-over exists:**
  - `--policy-file` writes the policy, then setup carries on (write and continue).
  - `SetupCommand.administratorCommands(account, deployment, callerArn, file)` renders the IAM
    admin's commands under `BaasCliDeployerPolicy-<deployment>`: `create-policy`, then the attach
    command for the caller's principal type (user, role, or an Identity Center note), and
    `create-policy-version`.
  - The walkthrough's step 4 should call these rather than re-render.
- **Step 7 can tell a permission failure apart.** A failed stack operation throws
  `CloudFormationService.StackOperationException`, whose `permissionFailure()` comes from the root-cause
  events (IAM, EC2, S3 and `AccessDenied` shapes). That is the hook for "the policy is stale, here
  it is" after a failed create or update, rather than a dead end.
- **Updates carry forward only declared parameters** (`updateStackParameters`). Removing the OIDC
  ARN parameter in D4 is therefore safe for existing stacks: no other handling is needed. Note
  that CloudFormation treats an update that only drops parameters or conditions as "No updates are
  to be performed" (W4). Both live stacks still list the old networking parameters, harmlessly.
- **Step 3 has no networking question:** existing-VPC support is gone, and every deployment builds
  its own VPC.
- **W2 matters for step 1, and is not yet decided.** An identity that may not simulate, re-running
  setup on a stack with nothing to change, "succeeds" because CloudFormation makes no resource call
  that could be denied. Setup then stores that identity as `aws.deployerProfile`. Seen live with
  `lynx`. In a walkthrough, the profile chosen in step 1 would be stored the same way. See *Open for
  the proposal*.
- **The test seam is missing (W1 there, and `pr83-review-fixes` W1).** `SetupCommand.call()` builds
  its AWS clients inline, so nothing about its ordering is unit-testable. Steps as probes need an
  injectable AWS seam, the way `OperatorSession.Aws` and `TeardownCommand.removeDeployment` do, or
  the walkthrough's own logic stays untested. Prompts follow teardown's injectable `answerReader`,
  and interactivity follows `Console.interactive()`.
- **The deployer policy is still 4370 non-whitespace characters**, so D4's budget note stands.

## Rules the walkthrough keeps

| Rule (CLAUDE.md, ADRs) | In the walkthrough |
|---|---|
| ADR 0006: nothing derives a deployment name silently | the derived default is shown and accepted; non-interactive keeps today's derivation |
| No default deployment among several | interactive asks which; non-interactive still refuses |
| The region is chosen once | asked on create only, with the warning; shown on update |
| The preflight is a UX affordance, not a control | the re-check loop is a convenience; nothing new gates on it |
| Payload on stdout, diagnostics on stderr | without a terminal, today's behaviour; interactively, the policy goes to a file, never dumped on screen |
| Setup never deletes a leftover | step 3 shows the command and waits |
| Day-to-day commands always run as `<name>-role-operator` | step 8 probes exactly that assumption |

## Risks

- **Ctrl+C during step 7 or 9.** CloudFormation and the image pipeline carry on without the CLI.
  "Just run it again" holds only if step 7's probe waits on `CREATE_IN_PROGRESS`/`UPDATE_IN_PROGRESS`
  and step 9's notices a running pipeline execution rather than starting a second build. Check
  today's behaviour for both before relying on it.
- **A probe that reports met wrongly** misleads more than no probe at all. Every probe must be
  cheap, read-only and conservative; what cannot be checked (GitHub variables) says so.
- **Size.** This is the largest interactive surface in the CLI. Keeping it honest: the steps reuse
  existing probes (`BucketProbe`, the preflight, stack status, image lookup, `OperatorSession`);
  prompts are line reads through `Console`, with teardown's injectable `answerReader` pattern for
  tests; no prompt library.

## Open for the proposal

Ask these one at a time (CLAUDE.md).

- **W2:** store `--deployer-aws-profile` (or step 1's choice) only after a create or an applied
  update, or only when the preflight could check; or keep storing it and warn when the check was
  skipped.
- **The AWS seam:** what interface lets steps be probed in tests: one `SetupAws` covering STS,
  IAM simulation, S3 probe, DynamoDB, CloudFormation, SSM and Image Builder, or per-step ones.
- An update run: the same steps, with only the changeable inputs asked (federation on/off and its
  repos; image rebuild), or all met and nothing asked unless a flag says otherwise.
- Whether step 6 prints the equivalent `--non-interactive` command line (a wizard-era idea; useful
  for repeatability, but with B a re-run is itself the repeat).
- Whether D7's helper adds `--deployment` always or only with ≥2 deployments.
- The OIDC thumbprint spike and the policy budget (D4).

## Working context for whoever picks this up

- **Deployments** (both on `baas-admin` as deployer):
  - `baas-development` (us-east-1) is the one to tear down and rebuild for live checks. It was
    recreated on 2026-10-13 from the post-change template, image 1.3.0 `ami-05f4851d3c30e3cb2`, and
    holds only `exclude_from_results` jobs.
  - `baas-381492019823` (eu-central-1) is CI's and carries the GitHub federation. Update it only
    after the same step has passed on `baas-development`.
  - us-east-1 allows 16 vCPU, which is two `c5.2xlarge` at once.
- **Identities:**
  - `baas-admin` may simulate.
  - `lynx` (`user/wsztajerowski_baas`, the teammate identity) may not simulate; it is the natural
    "cannot check" case.
  - `baas-admin` cannot attach IAM policies, so an IAM-admin step in a live check needs the user.
- **Paid runs** (image builds and `baas run` jobs) need the user's budget each time.
  `setup-prerequisites` used 3 of the 5 allowed.
- **Live checks that touch `~/.baas`:** run them under a scratch `-Duser.home=…`, with
  `AWS_CONFIG_FILE` and `AWS_SHARED_CREDENTIALS_FILE` pointing at the real `~/.aws`. A setup in the
  real home rewrites the deployment file (it happened once with `lynx`; restored from a backup).

