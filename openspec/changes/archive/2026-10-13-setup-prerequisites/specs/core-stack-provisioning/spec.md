# Spec Delta

## MODIFIED Requirements

### Requirement: Setup renders the deployer policy and stops when the caller lacks it
Before calling any CloudFormation, DynamoDB or EC2 API — after only the name validation and the
bucket-region lookup, which must come first so that a deployment living in another region is named
rather than met with a policy for a second one — `baas admin deployment setup` SHALL render
the deployer policy for the deployment it is about to create or update — the account from
`sts:GetCallerIdentity`, the region from `--region` or its default, the name resolved as
*A deployment is named by its prefix, derived from the account by default* states — and, where the
caller may call `iam:SimulatePrincipalPolicy`, check the caller's rights against it.

The check SHALL cover every statement of the rendered policy that carries no `Condition`, simulating
that statement's actions against its resources exactly as the rendered document names them, wildcard
actions and pattern resources included. It SHALL NOT rely on a list of actions maintained apart from
the policy, so a statement added to the policy is checked in the release that adds it.

With `--policy-file <path>`, setup SHALL write the rendered policy to that path before acting on the
check's result, whether or not rights are missing, and then continue exactly as it would without the
option.

When rights are missing it SHALL print the rendered policy to standard output, state on standard
error which actions are missing and that the policy must be attached before re-running, name the file
when `--policy-file` was given, create nothing, and exit non-zero. Whenever it reports missing rights
it SHALL also print, on standard error, the commands an IAM administrator runs to attach the policy
as a customer-managed policy named `BaasCliDeployerPolicy-<deployment>`, with the account, the policy
name and the caller filled in: one set for a first attachment and one for publishing a new version
of an existing policy.

When rights are present, or cannot be checked, it SHALL proceed. A permission failure during the
deployment SHALL still be reported together with the rendered policy, or with the path of the file
when `--policy-file` was given, whether the failure comes back from an AWS API call made by setup or
is reported by CloudFormation in the stack's events. There SHALL be no separate command that prints
the policy, and no option to render it for another account.

#### Scenario: Missing rights stop setup before anything is created
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup`
- **THEN** standard output holds the rendered policy JSON, standard error names the missing actions, no
  stack is created, and the exit code is non-zero

#### Scenario: The policy can be handed over
- **WHEN** `baas admin deployment setup > policy.json` runs for an identity lacking rights
- **THEN** `policy.json` is a valid IAM policy document for that deployment, and nothing else

#### Scenario: Sufficient rights deploy as before
- **WHEN** an identity holding the rendered policy runs `baas admin deployment setup`
- **THEN** no policy is printed and the deployment is created or updated

#### Scenario: The command is gone
- **WHEN** `baas admin deployer-policy` is invoked
- **THEN** picocli reports an unknown command error

#### Scenario: A named deployment's policy names that deployment
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1` runs for an identity holding only the default deployment's policy
- **THEN** the printed policy's resources are scoped to `wiktor-dev` in `us-east-1`, and nothing is created

#### Scenario: A statement newer than the attached policy is caught
- **WHEN** the attached policy lacks one statement the rendered policy has, such as `AssumeOwnOperatorRole`, and setup runs
- **THEN** the check names that statement's missing action, nothing is created, and the exit code is non-zero

#### Scenario: Wildcard actions and pattern resources are checked as written
- **WHEN** the rendered policy grants `ec2:Describe*` on `*` and an Image Builder action on `…:component/<prefix>-*`
- **THEN** both are simulated with exactly those action names and resources, and a caller lacking either is reported missing it

#### Scenario: The policy file is written when rights are present
- **WHEN** an identity holding the rendered policy runs `baas admin deployment setup --policy-file p.json`
- **THEN** `p.json` holds the rendered policy and the deployment is created or updated

#### Scenario: The policy file is written when rights are missing
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup --policy-file p.json`
- **THEN** `p.json` holds the rendered policy, standard error names the file and the administrator's commands, nothing is created, and the exit code is non-zero

#### Scenario: The administrator's commands are filled in
- **WHEN** setup reports missing rights for deployment `baas-development` in account `381492019823`
- **THEN** standard error shows an `aws iam create-policy` with policy name `BaasCliDeployerPolicy-baas-development`, an attach command for the caller, and an `aws iam create-policy-version … --set-as-default` for `arn:aws:iam::381492019823:policy/BaasCliDeployerPolicy-baas-development`

#### Scenario: A permission failure inside the stack is reported with the policy
- **WHEN** a deployment fails because CloudFormation reports a resource failed with a not-authorised reason
- **THEN** setup's report names that resource and reason and also prints the rendered policy, or names the `--policy-file` path when one was given, and the exit code is non-zero

### Requirement: Day-to-day commands resolve operator credentials
Every command outside `baas admin` that makes an AWS call SHALL run as the deployment's operator role,
`<name>-role-operator`. Its source credentials SHALL come from the deployment's `aws.operatorProfile`
when set, else its `aws.deployerProfile` when set, else the default credential chain. The command
SHALL then identify the source with `sts:GetCallerIdentity` and:

- when the source is the assumed role `<name>-role-operator`, use it as is;
- when the source is an assumed role named `<other>-role-operator` for a different `<other>`, fail
  naming both deployments before any further AWS call;
- otherwise assume `arn:aws:iam::<account>:role/<name>-role-operator`, where `<account>` is the
  source's own account, and keep those credentials valid for as long as the command runs.

When the assumption fails, the command SHALL fail naming the role and the source, and SHALL NOT make
any further call with the source credentials. When the source was `aws.deployerProfile` and the
assumption was denied, the failure SHALL also say that the deployer policy attached to that identity
may predate this CLI, and name the `baas --deployment <name> admin deployment setup --policy-file
<file>` command that renders the current one. `baas admin` commands SHALL use their source —
`aws.deployerProfile`, else the default credential chain — as is, and SHALL NOT assume the operator
role.

#### Scenario: Deployer profile is never silently reused
- **WHEN** the configuration has `aws.deployerProfile: baas-admin` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** every AWS call after identification is made as `<name>-role-operator`, with credentials obtained by assuming it from `baas-admin`

#### Scenario: Operator profile is honoured
- **WHEN** the configuration has `aws.operatorProfile: teammate` and `aws.deployerProfile: baas-admin`, and `baas run jmh` is invoked
- **THEN** the operator role is assumed from `teammate`, and `baas-admin` is not used

#### Scenario: A failed assumption stops the command
- **WHEN** the source credentials may not assume `<name>-role-operator` and `baas jobs list` is invoked
- **THEN** the command exits non-zero naming the role and the source, and makes no EC2, S3, DynamoDB or CloudFormation call

#### Scenario: A deployer denied the assumption is pointed at a stale policy
- **WHEN** the configuration has `aws.deployerProfile: baas-admin` and no `aws.operatorProfile`, `baas-admin`'s attached policy has no `sts:AssumeRole` on `<name>-role-operator`, and `baas jobs list` is invoked
- **THEN** the command exits non-zero naming the role and `baas-admin`, says the attached deployer policy may predate this CLI, and names `baas --deployment <name> admin deployment setup --policy-file <file>`

#### Scenario: An operator profile denied the assumption gets no policy hint
- **WHEN** `aws.operatorProfile: teammate` may not assume `<name>-role-operator` and `baas jobs list` is invoked
- **THEN** the command exits non-zero naming the role and `teammate`, and does not mention the deployer policy

#### Scenario: Another deployment's operator role is refused
- **WHEN** `baas --deployment wiktor-dev results query` runs with credentials for the assumed role `baas-123456789012-role-operator`
- **THEN** the command exits non-zero naming `wiktor-dev` and `baas-123456789012`, and makes no call after identification

#### Scenario: A legacy role_arn profile keeps working
- **WHEN** `aws.operatorProfile` names a `~/.aws` profile whose `role_arn` is `<name>-role-operator`
- **THEN** the command uses that session as is and makes no `sts:AssumeRole` call of its own

#### Scenario: A command outlives one role session
- **WHEN** an attached `baas run` lasts longer than the one-hour limit on a chained role session
- **THEN** the operator credentials are renewed before they expire, and the CLI can still terminate the instance at the end

#### Scenario: Admin commands use the deployer as is
- **WHEN** `baas admin image build` runs with `aws.deployerProfile: baas-admin`
- **THEN** its AWS calls are made with `baas-admin`'s own credentials and no `sts:AssumeRole` call is made

## ADDED Requirements

### Requirement: The image build and the runner use separate groups in the stack's own VPC
`RunnerImageInfrastructure` SHALL use a dedicated `ImageBuildSecurityGroup` permitting outbound TCP 443
and 80, and `RunnerSecurityGroup` SHALL permit outbound TCP 443 only. The stack SHALL create both
groups in the VPC it creates. `RunnerSecurityGroup`'s `GroupDescription` SHALL NOT change, since
editing it replaces the group.

#### Scenario: Port 80 is the build's alone
- **WHEN** the two groups' egress rules are inspected
- **THEN** the build group covers 443 and 80, and the runner group covers 443 and nothing else

#### Scenario: No operator-supplied group is accepted
- **WHEN** the core template's parameters are inspected
- **THEN** it declares no parameter naming an existing VPC, subnet or security group, and no condition choosing between created and supplied networking

## REMOVED Requirements

### Requirement: The image build and the runner do not share a security group
**Reason**: Its existing-VPC case (`UseExistingVpc`, `ExistingSecurityGroupId`, and the scenario *An
existing VPC keeps the operator's group*) is removed with existing-VPC support, and OpenSpec cannot
drop a scenario from a modified requirement. Its remaining content moves unchanged to *The image
build and the runner use separate groups in the stack's own VPC*.
**Migration**: None. The two security groups, their rules and `RunnerSecurityGroup`'s
`GroupDescription` are unchanged.


### Requirement: VPC parameters are immutable once the deployment exists
**Reason**: Existing-VPC support is removed. Every deployment uses the VPC its own stack creates, so
setup has no networking options left to compare or carry forward. No live deployment used the option
(both were created with `UseExistingVpc=false`, checked 2026-10-13).
**Migration**: None needed for stacks created with their own networking: their resources keep their
logical IDs on the next setup. A deployment set up against an existing VPC would have to be torn
down and set up again; none exists.
