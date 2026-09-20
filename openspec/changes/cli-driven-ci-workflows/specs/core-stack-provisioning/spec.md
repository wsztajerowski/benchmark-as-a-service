# Spec Delta

## ADDED Requirements

### Requirement: GitHub Actions federates directly into the operator role
The core stack SHALL be able to declare, on `BaasCliOperatorRole`'s trust policy, a federated
principal permitting a named GitHub repository's workload identity to assume that role directly. The
statement SHALL be conditional on the federation parameters being supplied, so an installation that
supplies none deploys exactly as before. The repository parameter SHALL accept more than one
repository, so one installation can serve several without a template migration. The role's
`MaxSessionDuration` SHALL be raised to at least the configured benchmark timeout plus its
termination margin. No intermediate role SHALL stand between the workload identity and the operator
role.

#### Scenario: A federated workload assumes the operator role in one step
- **WHEN** a workflow in the named repository requests credentials with its workload identity token
- **THEN** it assumes `BaasCliOperatorRole` directly, and no second `sts:AssumeRole` call is required

#### Scenario: An installation without federation is unchanged
- **WHEN** the core stack is deployed with no GitHub organisation or repository supplied
- **THEN** `BaasCliOperatorRole`'s trust policy carries the account-root principal alone and no
  federated statement

#### Scenario: Two repositories share one installation
- **WHEN** two repository names are supplied
- **THEN** the trust policy admits both, without any template change

#### Scenario: A session outlasts the run it polls
- **WHEN** a benchmark configured with the default timeout is launched under federated credentials
- **THEN** the session does not expire before the run completes and the instance is terminated

### Requirement: Federation parameters are carried forward, and revoked only on request
`baas admin setup` SHALL accept the GitHub organisation, repository list and OIDC provider ARN as
options. When updating an existing stack it SHALL carry forward the deployed value of every
parameter the caller did not name, rather than resubmitting a default in its place, so that a setup
run for an unrelated reason cannot alter the federated trust. It SHALL provide an explicit option
that revokes federation by submitting those parameters empty, and that option SHALL be the only way
an update removes the federated trust statement. The deployed stack SHALL be the single source of
truth for the federation values; the CLI SHALL NOT keep a second copy of them in its configuration
file.

#### Scenario: An unrelated setup does not revoke CI access
- **WHEN** `baas admin setup` is run again, naming no federation options, on an installation whose
  federation parameters were supplied earlier
- **THEN** the federated trust statement is still present afterwards and continuous integration
  retains access

#### Scenario: Revocation is explicit
- **WHEN** `baas admin setup` is run with the revocation option
- **THEN** the federated trust statement is absent from `BaasCliOperatorRole` afterwards, and the
  account-root principal remains

#### Scenario: Setting and revoking at once is refused
- **WHEN** `baas admin setup` is invoked with both the revocation option and a GitHub organisation
  or repository
- **THEN** the command reports a usage error and deploys nothing

#### Scenario: A first deploy carries nothing forward
- **WHEN** the core stack is created for the first time with no federation options
- **THEN** the create succeeds with the federation parameters explicitly empty, rather than failing
  because a parameter has no previous value

#### Scenario: The values are read back from the stack, not from configuration
- **WHEN** an operator asks which repository the installation trusts
- **THEN** the answer comes from the deployed stack's parameters, and `~/.baas/config.yaml` holds no
  copy of the organisation, repository list or provider ARN

### Requirement: The CI stack contains only the identity provider
`cf-template-ci.yaml` SHALL declare the GitHub OIDC identity provider and nothing else. It SHALL
take no parameter from, and produce no output consumed by, the core stack. Because the identity
provider is account-global and the core stack's trust statement references it, the provider SHALL be
deployed before `baas admin setup` is first run with federation parameters.

#### Scenario: No workload role remains in the CI stack
- **WHEN** `infra/cf-template-ci.yaml` is inspected
- **THEN** it declares no IAM role, and the only resource it declares is the OIDC identity provider

#### Scenario: Deploy order is provider first
- **WHEN** an installation is set up from scratch with federation
- **THEN** the identity provider exists before the core stack is deployed, and the core stack is
  given its ARN

#### Scenario: An existing account-global provider is reused
- **WHEN** the identity provider for the workload-identity issuer already exists in the account
- **THEN** its ARN is supplied to the core stack and no duplicate provider is created

## MODIFIED Requirements

### Requirement: Core stack contains only CLI-owned infrastructure
The core CloudFormation stack (template `cf-template-core.yaml`) SHALL contain the VPC, public subnet, internet gateway, S3 gateway endpoint, `RunnerSecurityGroup`, `S3MainBucket`, `RunnerRole`, and `RunnerInstanceProfile`. It SHALL NOT contain any GitHub OIDC provider resource or `WorkflowRole`. It MAY take GitHub organisation, repository and identity-provider ARN parameters, used solely to declare a conditional federated principal on `BaasCliOperatorRole`'s trust policy; it SHALL NOT create any other GitHub-related resource.

#### Scenario: Deploying the core stack creates no GitHub-related resources
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the resulting CloudFormation stack contains no `AWS::IAM::OIDCProvider` resource and no `WorkflowRole`

#### Scenario: Federation parameters produce a trust statement, not a resource
- **WHEN** the core stack is deployed with GitHub organisation, repository and provider ARN supplied
- **THEN** the only difference from a deploy without them is a statement on the operator role's
  trust policy

### Requirement: baas-cli never manages the CI stack
No `baas` command SHALL create, update, delete, or read the CI stack (`cf-template-ci.yaml`, containing the GitHub OIDC identity provider). The CI stack SHALL be deployed and torn down independently, outside of `baas-cli`. Accepting the provider's ARN as an opaque parameter value SHALL NOT be considered managing or reading that stack.

#### Scenario: No command references the CI stack
- **WHEN** any `baas` subcommand executes
- **THEN** it makes no CloudFormation or IAM identity-provider API call scoped to the CI stack

#### Scenario: CI template is not bundled in the CLI
- **WHEN** the `baas-cli` JAR is built
- **THEN** `baas-cli/src/main/resources/templates/` contains only `cf-template-core.yaml`, and `cf-template-ci.yaml` is absent from the JAR's classpath resources

#### Scenario: The provider ARN is passed through unresolved
- **WHEN** `baas admin setup` is given an identity-provider ARN
- **THEN** it forwards the value to CloudFormation without calling IAM to look the provider up

### Requirement: Deployer policy holds no CI-stack permissions
`BaasCliDeployerPolicy` SHALL NOT grant any OIDC-provider action, so the identity provider stays outside the deployer's reach and is created by a separate, higher-privileged identity. It SHALL grant `iam:UpdateAssumeRolePolicy`, scoped to the same role resources as its other IAM statements, because deploying the core stack now edits `BaasCliOperatorRole`'s trust policy. That grant confers no escalation the policy did not already allow, since `iam:CreateRole` writes a trust policy too.

#### Scenario: No OIDC actions present
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** it contains no action beginning `iam:` and ending `OpenIDConnectProvider`

#### Scenario: The trust-policy edit is permitted and scoped
- **WHEN** an identity holding only `BaasCliDeployerPolicy` deploys a core stack carrying federation parameters
- **THEN** the deploy succeeds, and the policy's `iam:UpdateAssumeRolePolicy` grant names the same role resources as its role-creation statement rather than `Resource: "*"`

### Requirement: Operator identity is an assumable role created by the core stack
The core stack SHALL create `BaasCliOperatorRole` as an `AWS::IAM::Role` resource (not a policy attached to any user), scoped to that stack's own `S3MainBucket`, `RunnerRole`, results table, and the runner AMI pointer path. Its trust policy SHALL allow the AWS account root, so `baas admin setup` requires no additional parameter to identify who the operator is, and SHALL additionally allow the federated workload principal when federation parameters are supplied. The role's ARN SHALL be a stack output (`OperatorRoleArn`) and SHALL be printed by `baas admin setup`. The stack SHALL NOT grant `sts:AssumeRole` on this role to any specific IAM identity — that remains a manual, per-identity step performed outside the stack.

#### Scenario: Operator role is created unattached
- **WHEN** `baas admin setup` deploys the core stack
- **THEN** the stack contains an `AWS::IAM::Role` named `<prefix>-operator-role` with a trust policy allowing `arn:aws:iam::<account-id>:root`, and no `sts:AssumeRole` grant exists for any specific principal until one is added manually

#### Scenario: Operator role ARN is surfaced to the operator
- **WHEN** `baas admin setup` completes
- **THEN** it prints the `OperatorRoleArn` stack output together with instructions for granting `sts:AssumeRole` to a specific IAM identity

#### Scenario: The federated principal is additive
- **WHEN** the core stack is deployed with federation parameters
- **THEN** the account-root principal remains, and the federated statement is present alongside it

## REMOVED Requirements

### Requirement: baas admin setup accepts no GitHub/OIDC options
**Reason**: Deleting `WorkflowRole` moves the federated trust statement onto `BaasCliOperatorRole`, which the core stack owns. Setting and revoking that trust is therefore something `baas admin setup` has to be able to express, which an option surface forbidding every GitHub/OIDC option cannot.

**Migration**: `--github-org`, `--github-repo` and `--oidc-provider-arn` are accepted again, together with an explicit revocation option; their values live in the deployed stack rather than in `~/.baas/config.yaml`, and an update that names none carries the deployed values forward. `--workflow-id` and `--workflow-branch` are not reinstated. An installation that has never supplied them behaves exactly as before.
