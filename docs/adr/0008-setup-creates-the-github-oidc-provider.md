# ADR 0008 — Setup creates the GitHub OIDC provider, and never modifies or deletes it

- **Status:** Accepted — implemented in `interactive-setup`
- **Date accepted:** 2026-10-14
- **Supersedes:** `baas-cli-core-ci-split` D1/D2, "the local CLI never creates, reads, updates or
  deletes anything related to GitHub OIDC; a hard security boundary"
  (`openspec/changes/archive/2026-08-11-baas-cli-core-ci-split/design.md`)
- **Closes findings:** none. Part of the guided setup (`interactive-setup` exploration, D4)

## Context

GitHub Actions federates into a deployment's operator role through the account's OIDC identity
provider for `token.actions.githubusercontent.com`. The provider is account-global: one per issuer
URL per account. Until this change it lived in its own stack, `cf-template-ci.yaml`, which had to be
deployed by hand, with an identity above the deployer, before setup. Setup then needed the
provider's ARN typed in (`--oidc-provider-arn`). That made CI the one part of a deployment the CLI
could not set up, and a new user met it as a README section, an admin hand-off and an ARN to copy.

The split's rationale was to limit the deployer's blast radius. Two later decisions overtook it:
- The accepted risk *Deployer privilege*: `iam:CreateRole` also writes the trust policy, so the
  deployer policy is effectively account admin already.
- `cli-driven-ci-workflows` had setup write the federated trust onto `OperatorRole` itself.

A provider is also the one federation resource that is not the deployment's. It may serve other
projects in the account, and a deployment's lifetime is not its lifetime.

## Decision

`baas admin deployment setup` creates the provider when federation is asked for and it is absent.
It **never modifies or deletes it**.

- **The ARN is derived:** `arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`.
  The template composes it the same way, and `--oidc-provider-arn` and the `GitHubOidcProviderArn`
  parameter are gone.
- **It is created through the IAM API, outside any stack,** with the issuer URL and the
  `sts.amazonaws.com` audience only. There is no thumbprint (IAM verifies GitHub's endpoint against
  its trusted CAs) and there are no tags. It is created only after the plan is confirmed, so
  declining changes nothing.
- **An existing provider is reused.** One whose audiences lack `sts.amazonaws.com` is left to an IAM
  administrator. Setup prints the `aws iam add-client-id-to-open-id-connect-provider` command and
  waits, or exits 1 unattended. Changing a shared provider is not the deployment's call.
- **Teardown leaves it,** and says so.
- **The deployer policy grants** `iam:GetOpenIDConnectProvider` and
  `iam:CreateOpenIDConnectProvider` on that one ARN, and nothing else of any provider.
- `infra/cf-template-ci.yaml` is deleted. An account that deployed it keeps its provider, and setup
  reuses it.

## Consequences

- CI is one more step of the walkthrough rather than a separate procedure. Setup prints the GitHub
  variables to set, `OPERATOR_ROLE_ARN` and `AWS_REGION`, and never claims they are set: `baas`
  holds no GitHub credentials.
- Every existing deployer's attached policy lacks the new statement. The first setup after
  upgrading reports the two actions missing and hands the new policy version over.
- The deployer can create one more kind of IAM resource. It is bounded to one ARN that can only
  ever name the GitHub issuer, and it adds no escalation beyond what *Deployer privilege* already
  records.
- Nothing ever removes the provider. A provider no deployment uses is left for an IAM administrator
  to delete, which is free to leave.

## Rejected

- **Keep the CI stack:** a hand step behind an admin, for a resource the deployer may as well
  create.
- **A provider resource in the core stack:** the provider is account-global, so a second
  deployment's stack would fail with `EntityAlreadyExists`. Deleting a deployment would also delete
  a provider other deployments and projects rely on.
- **Updating a provider's audiences:** a shared resource changed as a side effect of one
  deployment's setup.
