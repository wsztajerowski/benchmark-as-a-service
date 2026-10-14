# Proposal

## Why

A new user meets `baas admin deployment setup` as one command plus steps documented across two
READMEs: attach a policy they must look up, build the image as a separate 15-minute command, grant a
teammate, deploy the OIDC provider by hand with an identity above the deployer, set two GitHub
variables. Most of those end in a bare refusal and a re-run, and the hints printed along the way fail
as printed once a second deployment exists (E2). With `setup-prerequisites` merged, every probe a
guided flow needs exists — the derived preflight, `BucketProbe`, `permissionFailure()` — so setup can
now walk the user through instead of refusing. Decided step by step in
[`exploration.md`](exploration.md) (D1–D20).

## What Changes

- **Setup becomes a guided walkthrough** of eleven steps — which deployment, credentials, name and
  region, deployer permissions, names are free, GitHub Actions, plan and confirm, stack, operator
  role, runner image, done. Each step probes AWS and this machine, then acts, or explains what the
  user must do and waits. No progress is saved: a re-run continues at the first unmet step (D1, D3,
  D14).
- **Interactive by default** on a terminal; `--non-interactive`, a redirect or CI run the same steps
  and exit 1 at the first one that needs the user, as setup does today on missing rights (D2).
  Every prompt has a flag that answers it in advance (D10); interactive runs that changed something
  end by printing the equivalent `--non-interactive` command (D11).
- **An update run asks the changeable inputs** (deployer profile, GitHub federation) with the
  current value as default, and confirms only on create or when an answer changed (D10). A healthy
  deployment's re-run is two Enter presses and changes nothing.
- **Recovery instead of refusal:** a name another account holds goes back to the name prompt, and
  `baas-<account>` is only a suggestion (D17); a permission failure in the stack hands the policy
  over and returns to the permission step, and the operator-role check first rides out IAM
  propagation (D18); with no `--policy-file`, an interactive run asks whether to print the policy
  or save it (D15).
- **The runner image is offered at the end of setup**, default yes; non-interactive builds only
  with `--build-image` (D5).
- **Interrupted runs resume without leaks.** Setup waits on a stack still in progress. Setup and
  `baas admin image build` wait for an in-flight build or adopt one that finished unseen, instead
  of starting a second build and orphaning the first AMI and its snapshot (D19). On create, the
  deployment file is written as soon as `CreateStack` is accepted, so an interrupted create never
  leaves a stack no file names and teardown cannot reach (D20).
- **The deployer profile is stored only on evidence** that it can deploy — a met preflight, or a
  stack operation that changed something — so a no-op re-run under the wrong identity no longer
  overwrites a working profile (D8; `setup-prerequisites` W2).
- **`baas` creates the GitHub OIDC provider when absent, and never modifies or deletes it** (D4).
  The provider ARN is derived from the account, so `--oidc-provider-arn` is removed, and
  `infra/cf-template-ci.yaml` is deleted. An existing provider is reused; one lacking the
  `sts.amazonaws.com` audience is left to an IAM administrator, with the command printed.
- **The deployer policy is documented and budgeted as customer-managed** (6144 characters, test
  budget 5632) rather than inline (D13), and gains the two OIDC-provider actions on the one ARN.
- **Every printed next step carries `--deployment`**, always, and the global option's help says so
  (D7, D12). Closes **E2**.

## Capabilities

### New Capabilities
- `guided-setup`: the walkthrough itself — the eleven steps and their order, interactive versus
  non-interactive behaviour, prompts and their flags, update runs, the plan confirmation, recovery
  paths back to earlier steps, resuming an interrupted run, the image offer, and the closing report
  with the repeat command.

### Modified Capabilities
- `core-stack-provisioning`: CI-stack and OIDC requirements reversed (the CLI creates the provider,
  the deployer policy grants it, the CI template and the ARN option go); the policy hand-over gains
  the interactive print-or-save choice and waits instead of stopping; the name rule's derived
  default becomes a suggestion interactively; a name another account holds is re-asked; the
  deployment file is written when a create is accepted; the policy budget is customer-managed.
- `cli-command-structure`: next-step hints always name `--deployment`; the deployer profile is
  stored only on evidence; the configuration file's write point on create.
- `runner-image-provisioning`: exactly one image also across an interrupted build — an in-flight
  build is waited for and a finished, unpublished one adopted, never a second build started.

## Impact

- **Code:** `SetupCommand` is restructured around a step engine and one `SetupAws` port with domain
  results (`Met | Denied | NotChecked`) and a hand-written fake (D9); `ImageBuilderService` gains
  in-flight and unpublished-build handling; a hint helper replaces the bare hints in `SetupCommand`,
  `RunCommand` and `ConfigShowSubcommand`; `BaasApp`'s `--deployment` description;
  `DeployerPolicyTest`'s budget test is renamed and moved to 5632.
- **Templates and policy:** `deployer-policy.json` gains `iam:GetOpenIDConnectProvider` and
  `iam:CreateOpenIDConnectProvider` on `oidc-provider/token.actions.githubusercontent.com` (~211
  characters; ~4581 rendered). `cf-template-core.yaml` loses the `GitHubOidcProviderArn` parameter;
  updates already carry forward only declared parameters, so existing stacks need nothing else.
  `infra/cf-template-ci.yaml` is deleted.
- **Upgrade:** an existing deployer's attached policy lacks the new statement, so the first setup
  after upgrading reports it missing and hands over the new version, as 7.1's `AssumeOwnOperatorRole`
  did. The interactive hand-over waits for it instead of exiting.
- **Docs:** CLAUDE.md (the CI template bullet and deploy-order text, the deployer-policy budget
  paragraph, the *fixed names* bullet's "no configuration file was ever written", the concurrent
  image-build entry), `infra/README.md`, the setup sequence diagram under `docs/diagrams/`, and a new
  ADR superseding `baas-cli-core-ci-split` D1/D2 with *create, never modify or delete*.
- **Cost:** no new standing cost. The OIDC provider is free. Adopting a finished, unpublished build
  removes today's leaked AMI snapshot (~$0.20/month each) after an interrupted build. The image build
  offered by setup is the same ~15-minute build `admin image build` already runs, and is never paid
  by a non-interactive run without `--build-image`.
- **Deliberately not changed:** the preflight stays a UX affordance, not a control; setup never
  deletes a leftover bucket or table; nothing derives a deployment name silently (ADR 0006) and no
  default deployment exists among several; the region is chosen once; payload stays on stdout and
  non-interactive setup still prints the policy there, so `setup > policy.json` keeps working;
  `baas` never modifies or deletes the OIDC provider and never touches GitHub (the variables are
  printed, not set); day-to-day commands still run only as the operator role; no `--yes` for setup;
  no prompt library.
