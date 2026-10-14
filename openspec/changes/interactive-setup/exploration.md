# Exploration — interactive-setup

Explored 2026-10-10 → 2026-10-14 with the user, from the live end-to-end test of v7.0.0
(`archive/2026-10-12-pr83-review-fixes/verify.md`, *Full end-to-end test*), re-grounded on `main` at
v7.1.1. It builds on [`setup-prerequisites`](../archive/2026-10-13-setup-prerequisites/exploration.md): the derived
preflight, `--policy-file`, and the removal of existing-VPC support land there first.

**Status (2026-10-14):** every decision below is taken, including the six that were left open for
the proposal (D8–D13), six found while drawing the flow (D14–D19) and when the deployment file is
written (D20), all settled one at a time on 2026-10-14; nothing is waiting on the user.
`setup-prerequisites` was merged into `next-release` (PR #94) and archived with its specs synced
(`dd3cde1`). This change is #1 in `QUEUE.md`. **Next step: `/opsx:propose interactive-setup`.**

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
stores none. A re-run on a finished deployment that the user accepts as-is (Enter through the
changeable inputs, D10) does nothing. The values A would collect remain, as the inputs of the early
steps.

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

Eleven steps (renumbered by D14; earlier conversation and the first diagram page used 1–10).

| # | Step | Probe | Done by `baas` | Done by the user (explained, waited for) |
|---|---|---|---|---|
| 1 | Which deployment | this machine's files only: `--deployment`, the only one, else a choice among several or "a new one"; no AWS call | — | with ≥2 configured, choose one (or "new") instead of being refused |
| 2 | Credentials | `sts:GetCallerIdentity` on the deployer profile (the stored one as default for an existing deployment); shows account and ARN | — | `aws configure` / `aws configure sso`, or `aws sso login` on an expired token |
| 3 | Name and region | new: `DeploymentNameRule`; existing: shown and fixed. Also the bucket-in-another-region check (today's, before the preflight) | — | new only: a name (`baas-<account>` suggested, any valid name accepted, D17); the region, with "cannot be changed later" |
| 4 | Deployer permissions | the derived preflight (`setup-prerequisites` D1) | write the policy file or print it (D15) and print the filled-in `aws iam` commands | an IAM admin attaches it; IAM propagation can lag, so "not visible yet" is distinct from "missing" |
| 5 | Names are free | `BucketProbe`, `tableExists` — after step 4, so `Forbidden` means another account (D17) | — | remove a leftover (the refusal's own command; setup never deletes one); a name another account holds goes back to step 3 |
| 6 | GitHub Actions (optional) | org and repos asked; provider exists, and lists `sts.amazonaws.com` as an audience (D4) | create the provider when absent (D4) | add the audience to an existing provider that lacks it (never modified by `baas`) |
| 7 | Plan and confirm | — | show account, region, name, create/update, federation, standing cost (~$0.20/month for the image snapshot) | confirm (on create, or when an answer changed: D10) |
| 8 | Stack | stack status; waits on `*_IN_PROGRESS` (D19) | create/update, with a status line; on create, write the deployment file once `CreateStack` is accepted (D20) | a permission failure hands the policy over and returns to step 4 (D18) |
| 9 | You can operate it | `AssumeRole` into `<name>-role-operator` from the deployer source, as `OperatorSession` does; retried ~30 s against IAM propagation (D18) | — | — (only then: the policy may be stale, as step 8) |
| 10 | Runner image | the AMI pointer against the bundled base; an in-flight or finished-but-unpublished build (D19) | build it (D5), or adopt the existing build | — |
| 11 | Done | — | store the profile on evidence (D8) when step 8 has not already; print the first `baas run` with `--deployment` (D12); for a teammate: the `sts:AssumeRole` grant (filled in) and `baas --deployment <name> config sync --operator-aws-profile <p>`; with federation: `gh variable set OPERATOR_ROLE_ARN …` and `AWS_REGION …`; the repeat command (D11) | — |

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
- **Spike, done 2026-10-14 (API reference):** `ThumbprintList` is optional (AWS verifies the JWKS
  endpoint against its trusted root CAs and uses thumbprints only for an IdP outside them; omitted,
  IAM fetches one itself), and `Tags` is optional. `baas` sends `Url` and
  `ClientIDList=[sts.amazonaws.com]` only, and does not tag the provider, so
  `iam:TagOpenIDConnectProvider` is not needed. The statement is
  `iam:GetOpenIDConnectProvider` + `iam:CreateOpenIDConnectProvider` on the one ARN: 211
  non-whitespace characters, ~8 fewer with a short Sid. Budget: D13.

### D5 — The image build is offered, default yes

Step 10 asks "Build the runner image now? ~15 min [Y/n]" when the image is absent or behind the
bundled base, and never when it is current (user, 2026-10-13). Non-interactive builds only with
`--build-image`, so a scripted re-setup never pays 15 minutes by surprise. This keeps the reason
CLAUDE.md gives for setup not building (*every re-setup would pay for it*): it argues against
building by default, not against an offer.

### D6 — v1 covers every step (1–11 after D14), CI included

User, 2026-10-13. There is no existing-VPC picker, because `setup-prerequisites` removes the option
altogether.

### D7 — Every printed next step carries `--deployment` when it is needed

Finding E2: setup's closing text, `RunCommand`'s no-image refusal (`Build one: baas admin image
build`, which also says "account" where it means the deployment), `RunCommand`'s results hint, and
`ConfigShowSubcommand`. One helper renders a hint with `--deployment <name>` **always** (D12).
Folded in here because step 11 prints most of them.

### D8 — The deployer profile is stored only on evidence (W2)

User, 2026-10-14. `--deployer-aws-profile`, or step 2's choice, is written to the deployment file
only when there is evidence the identity can deploy: the preflight ran and denied nothing, **or** a
stack create or update actually changed something. Otherwise the previous value stays and setup says
so ("not stored: could not verify this identity can deploy"). Today `SetupCommand` sets it before
anything is verified (`:127`) and `deploy()` saves on every exit-0 path, a no-op update included,
which is how `lynx` replaced a working `baas-admin`. Same rule as `config sync`, which checks
`--operator-aws-profile` before storing it. A first create by an identity that may not simulate
still stores: the create is the evidence.

Rejected: store only after a create or applied update (a correct new profile is never stored on a
no-op re-setup whose preflight passed); keep storing and warn (still overwrites a working value).

### D9 — One `SetupAws` port with domain results

User, 2026-10-14. One interface in the shape of `OperatorSession.Aws`, faked by hand (the module has
no Mockito; fakes are the idiom). Its methods return domain results, not SDK types — the preflight
returns `Met | Denied(actions) | NotChecked`, so *not checked is never shown as met* is a type, not a
convention, and D8's evidence reads off those results. Two test layers: the walkthrough engine
(order, re-probing, wait versus stop, Enter-to-re-check) against fake steps, with no AWS at all; the
steps against `FakeSetupAws`. The real adapter stays thin over what is already tested
(`DeployerPreflight`, `BucketProbe`, `CloudFormationService`, the image lookup). Prompts keep
teardown's injectable `answerReader`.

Rejected: a port per step (~8 interfaces and fakes, with caller identity and the bucket probe
duplicated across them).

### D10 — An update run asks the changeable inputs, with the current value as default

User, 2026-10-14, after rejecting "only unmet steps are asked": the most common deliberate re-run is
*to change something* (add CI, add a repo, revoke, switch the deployer profile), and a walkthrough
that answers "all met" sends the user to the flags — the lookup it exists to remove.

- Every changeable input is asked on every interactive run, its current value the default: step 2
  `Deployer profile [baas-admin]:`, step 6 `GitHub Actions: <org>/<repos>. Change? [y/N]`. Name and
  region are fixed after create and only shown. The image is offered by D5 only when absent or
  behind; a current image has no reason to rebuild (an extension goes through `admin image build
  --extension`). A healthy deployment's re-run is two Enter presses and changes nothing.
- **Flags and prompts are the same inputs.** A flag answers its prompt in advance; non-interactive
  takes flags only, as today. The prompt shows the current repo list, which settles whether a value
  replaces or adds.
- **Step 7 (plan and confirm) runs on create, and on an update where an answer differs from its
  current value.** A template-only update after a CLI upgrade proceeds unconfirmed, as today:
  predicting it would need `cloudformation:GetTemplate`, which the deployer policy lacks. Step 8
  keeps no "met" probe of its own: it submits, and "No updates are to be performed" is met.

### D11 — Step 11 prints the equivalent non-interactive command

User, 2026-10-14. Since D10 makes flags and prompts one set of inputs, the command is the answers
rendered as flags: `To repeat this without prompts: baas --deployment <name> admin deployment setup
--non-interactive …`. Printed at step 11 (not 7, which precedes the image answer), on interactive
runs only, and only when the run created or changed something. Always with `--deployment`; the
region only on create. A test renders answers to a command line and parses it back through picocli
to the same answers — which also pins D2's requirement that every prompt has a flag.

### D12 — Hints carry `--deployment` always

User, 2026-10-14. Fails safe (a hint is never wrong, only longer), needs no count of
`~/.baas/deployments/` and no exception for the teammate's `config sync` (which refuses an unnamed
deployment when nothing is configured), and matches D11. Every place that prints a hint has already
resolved the name. The inherited `--deployment` description (`BaasApp.java:79`) states the rule
instead, once for every command's `--help`:

```
--deployment <name>   Deployment to act on. Optional when exactly one is configured on this
                      machine; required with two or more (see `baas config list`). Printed
                      hints always include it, so they stay valid when you add another.
```

### D13 — The deployer policy is customer-managed; its budget moves to 6144

User, 2026-10-14. Setup's hand-over already prints `create-policy` + `attach-…-policy` for a
customer-managed `BaasCliDeployerPolicy-<deployment>` (6144 characters to itself), while CLAUDE.md's
*close to IAM's size ceiling* paragraph and `DeployerPolicyTest`'s comment (`:276`) still size it
for an inline group policy (5120, shared; test budget 4608). Customer-managed becomes the one
documented target: new users get it from setup, and the budget test moves to 6144 minus the same
512 reserve (5632). With D4's statement the policy is ~4581, so this is headroom, not a fix.

- Rewrite the CLAUDE.md paragraph, the test's comment and name
  (`renderedPolicyLeavesRoomInAnInlinePolicyBudget`), and any `infra/README.md` text that says
  inline.
- **The existing deployer `baas-admin-wiktor`:** how its policy is attached is unknown — on
  2026-10-14 neither `baas-admin` nor `lynx` could list it (`iam:List*` is scoped to roles). It needs
  an IAM admin (console: IAM → Users → `baas-admin-wiktor` → Permissions). Nothing breaks while the
  policy stays under 5120; migrate it, if it is inline, before the policy crosses that, and step 4's
  hand-over is then the migration path.

### D14 — Which deployment first, then credentials; permissions before names

User, 2026-10-14, found while drawing the flow. D3's first order (credentials, then deployment)
could not work: on an update the default deployer profile comes *from the deployment's file*, while
a new deployment's suggested name needs the account. `SetupCommand` already has the working order —
`loadForSetup()` picks the deployment from local files before `GetCallerIdentity`. So step 1 is
*which deployment* (local files only), step 2 credentials, step 3 name and region for a new one.
Renumbered 1–11 rather than "2a/2b": the D-entries and the specs cite step numbers.

Permissions (4) come before names (5), again as today (`nameConflict` runs inside `deploy()`, after
the preflight): `BucketProbe.Forbidden` means either another account's bucket or this account's
bucket and a caller without `s3:ListBucket`, and only after a *Met* preflight is it the former. When
the preflight said *not checked*, the message keeps today's caveat line. The bucket-in-another-region
check stays before the preflight, with the region (step 3), because the policy names the region.

### D15 — Interactively, the policy is printed or saved as the user chooses

User, 2026-10-14. Replaces "interactively the policy goes to a file, never dumped on screen". When
step 4 (or a step 8/9 hand-over) has a policy to give and `--policy-file` is absent, an interactive
run asks whether to print it or save it, offering the file (`policy.json`) as the default; a file
named by the flag skips the question. Non-interactive is unchanged: stdout, so `setup > policy.json`
keeps working.

### D16 — Only a deployer gets a meaningful re-run

User, 2026-10-14. Setup runs as the deployer, so the "is my deployment fine?" re-run needs at least
the deployer's rights. A non-deployer is *Denied* at step 4 or, worse, *not checked* (as `lynx`)
followed by a no-op update that looks like "all met" — W2's shape. The walkthrough does not promise
a health check to anyone else; an operator checks their own access with `config sync`, which
already proves it. Docs and the journey table say "admin (deployer rights)", not "anyone".

### D17 — A name another account holds goes back to the name prompt; the suggestion is only that

User, 2026-10-14. After step 4 is met, a `Forbidden` bucket is another account's: interactively the
walkthrough returns to step 3's name prompt — as for a `DeploymentNameRule` violation — and the new
name goes through step 4 again, since the policy names the prefix. A name from `--deployment` is
asked again too: a flag answers its prompt in advance, it does not make an unusable answer usable.
Non-interactive exits 1 with today's "choose another name" hint. `baas-<account>` is the prompt's
**suggested** default only; any valid name is accepted, and nothing requires the account in it
(without a terminal, today's derivation applies only when `--deployment` is absent). Leftovers in
this account are unchanged: the command, then wait or exit 1.

### D18 — Step 8's permission failure returns to step 4; step 9 first rides out IAM propagation

User, 2026-10-14.

- **Step 8** (stack fails with `StackOperationException.permissionFailure()`): after a *not checked*
  preflight, or a statement the simulator cannot evaluate. The stack is then `ROLLBACK_COMPLETE`
  (setup already deletes and recreates it) or `UPDATE_ROLLBACK_COMPLETE` (updatable), so a retry
  needs no new mechanism: interactively, hand the policy over as in step 4 (D15 included), wait, and
  on Enter return to step 4 and run forward; the plan stays confirmed for the rest of the run.
  Non-interactive exits 1, as today. A wrong policy fails again and the user quits — no loop guard.
- **Step 9** (`AssumeRole` into the operator role fails): right after a create the likely cause is
  IAM propagation of a seconds-old role, not a missing grant — step 4 simulates
  `AssumeOwnOperatorRole`, which has no condition. Both modes retry quietly for ~30 s with backoff;
  only then say the policy may be stale and hand it over as step 8 does.

### D19 — An interrupted run resumes: step 8 waits, step 10 adopts

User, 2026-10-14, after checking today's behaviour.

- **Stack.** A re-run while the stack is still `*_IN_PROGRESS` takes the update path today:
  `requireUpdatable` checks only `ROLLBACK_COMPLETE`, so `UpdateStack` is sent and refused — a dead
  end, not damage. Step 8's probe waits on it with the SDK waiter, both modes, then re-probes. The
  deployment file exists from the moment `CreateStack` was accepted (D20), so a re-run's step 1
  lists the deployment and nothing has to be retyped.
- **Image — a leak that exists today in `admin image build` too.** `ImageBuilderService.publish()`
  starts, polls, repoints and retires inside the CLI process, so Ctrl+C (or a closed lid) mid-build
  leaves Image Builder to finish an AMI that no pointer names and nothing retires — billing for its
  snapshot indefinitely; a re-run then starts a *second* execution. Step 10's probe gains two cases:
  an **in-flight** build of this pipeline (the `IN_PROGRESS` statuses `ImageBuilderService` already
  lists) is waited for — "A build started at HH:MM is running; wait for it? [Y/n]", non-interactive
  waits — and a **finished but unpublished** one (the pipeline's newest available image newer than
  the pointer's AMI) is adopted without waiting. Adoption is `publish()`'s second half: identity
  tags, pointer, retire the replaced AMI. Never a second build.
- **Implemented in `ImageBuilderService`**, so `baas admin image build` gains it too. It mostly closes
  CLAUDE.md's deliberately unguarded *two concurrent `baas admin image build` runs* — two starts in
  the same seconds still race — so that entry is rewritten.
- A Ctrl+C hook that cancels the build was rejected: it fails under `kill -9` or a closed lid, needs
  `imagebuilder:CancelImageCreation`, and probing reality is D1's model anyway.

### D20 — On create, the deployment file is written once `CreateStack` is accepted

User, 2026-10-14. Today the file is written once, at the end of `deploy()`, after the stack
operation completes — so a closed lid or Ctrl+C during a create leaves a stack no file names. A
re-run under a different name then builds a second complete deployment, and the first cannot be
torn down: `TeardownCommand` goes through `ConfigService.load()`, which refuses a `--deployment`
with no file. That is A10's silent second deployment, reached by a retyped name instead of a
derived one (no standing cost — Gateway endpoints, empty bucket and table, no image yet — but
invisible to `config list`).

- **When:** as soon as AWS accepts `CreateStack`, so the file always names a stack that exists,
  whatever its state. Not at step 7's confirmation: a refused `CreateStack` would leave a file
  naming nothing. An update changes nothing here — its file already exists.
- **What:** the name (the file's name) and the region. The deployer profile follows D8: stored then
  if step 4's preflight was Met, otherwise once the create completes.
- **A rolled-back create** leaves a visible, named file: it shows in `config list` and in every
  "two or more deployments" refusal, and `baas --deployment <name> admin deployment teardown`
  removes the `ROLLBACK_COMPLETE` stack and the file. A re-run's step 8 still deletes and recreates
  it, as today.
- **CLAUDE.md:** the *fixed names* bullet's "no configuration file was ever written for it" is
  rewritten — a file may now exist; setup still deletes and recreates a `ROLLBACK_COMPLETE` stack
  rather than pointing at teardown.

Rejected: write after step 8 completes (an interrupted create orphans the stack under a retyped
name, unreachable by teardown); write at step 7's confirmation (a refused create leaves a file for
nothing).

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
- **Step 8 can tell a permission failure apart.** A failed stack operation throws
  `CloudFormationService.StackOperationException`, whose `permissionFailure()` comes from the root-cause
  events (IAM, EC2, S3 and `AccessDenied` shapes). That is the hook for "the policy is stale, here
  it is" after a failed create or update, rather than a dead end.
- **Updates carry forward only declared parameters** (`updateStackParameters`). Removing the OIDC
  ARN parameter in D4 is therefore safe for existing stacks: no other handling is needed. Note
  that CloudFormation treats an update that only drops parameters or conditions as "No updates are
  to be performed" (W4). Both live stacks still list the old networking parameters, harmlessly.
- **Step 5 has no networking question:** existing-VPC support is gone, and every deployment builds
  its own VPC.
- **W2 matters for step 2** (decided: D8). An identity that may not simulate, re-running
  setup on a stack with nothing to change, "succeeds" because CloudFormation makes no resource call
  that could be denied. Setup then stores that identity as `aws.deployerProfile`. Seen live with
  `lynx`. In a walkthrough, the profile chosen in step 2 would be stored the same way.
- **The test seam is missing (W1 there, and `pr83-review-fixes` W1).** `SetupCommand.call()` builds
  its AWS clients inline, so nothing about its ordering is unit-testable. Steps as probes need an
  injectable AWS seam (decided: D9), the way `OperatorSession.Aws` and `TeardownCommand.removeDeployment` do, or
  the walkthrough's own logic stays untested. Prompts follow teardown's injectable `answerReader`,
  and interactivity follows `Console.interactive()`.
- **The deployer policy is still 4370 non-whitespace characters**; with D4's statement ~4581 (D13).

## Rules the walkthrough keeps

| Rule (CLAUDE.md, ADRs) | In the walkthrough |
|---|---|
| ADR 0006: nothing derives a deployment name silently | the derived default is shown and accepted; non-interactive keeps today's derivation |
| No default deployment among several | interactive asks which; non-interactive still refuses |
| The region is chosen once | asked on create only, with the warning; shown on update |
| The preflight is a UX affordance, not a control | the re-check loop is a convenience; nothing new gates on it |
| Payload on stdout, diagnostics on stderr | without a terminal, today's behaviour; interactively, the policy is printed or saved as the user chooses (D15) |
| Setup never deletes a leftover | step 5 shows the command and waits |
| Day-to-day commands always run as `<name>-role-operator` | step 9 probes exactly that assumption |

## Risks

- **Ctrl+C during step 8 or 10** — checked and decided (D19): step 8 waits on a stack still in
  progress, step 10 adopts a build it did not see finish.
- **A probe that reports met wrongly** misleads more than no probe at all. Every probe must be
  cheap, read-only and conservative; what cannot be checked (GitHub variables) says so.
- **Size.** This is the largest interactive surface in the CLI. Keeping it honest: the steps reuse
  existing probes (`BucketProbe`, the preflight, stack status, image lookup, `OperatorSession`);
  prompts are line reads through `Console`, with teardown's injectable `answerReader` pattern for
  tests; no prompt library.

## Open for the proposal

Nothing. The six items left here on 2026-10-13 were settled on 2026-10-14, one question at a time:
W2 → D8, the AWS seam → D9, an update run → D10, the repeat command → D11, `--deployment` in hints →
D12, the OIDC spike → D4, the policy budget → D13. D14–D20 were found and settled while drawing
and reviewing the flow.

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

