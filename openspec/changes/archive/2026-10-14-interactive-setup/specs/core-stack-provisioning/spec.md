# Spec Delta

## ADDED Requirements

### Requirement: The CLI creates the GitHub OIDC provider and never modifies or deletes it
When a deployment is set up with GitHub federation, `baas admin deployment setup` SHALL ensure the
account's GitHub OIDC identity provider exists at
`arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`, deriving that ARN from the
caller's account. When it is absent, setup SHALL create it through the IAM API — outside the core
stack — with the issuer URL and the client ID `sts.amazonaws.com` only, no thumbprint and no tags. When
it exists, setup SHALL reuse it. An existing provider that does not list `sts.amazonaws.com` as a
client ID SHALL NOT be modified: setup SHALL print the IAM administrator's
`aws iam add-client-id-to-open-id-connect-provider` command with the ARN filled in, and wait
interactively or exit 1 unattended. No `baas` command SHALL modify or delete the provider, since it is
account-global and may serve other projects. A teardown of a deployment whose operator role trusted
it SHALL say that the provider is left in place.

#### Scenario: A missing provider is created
- **WHEN** setup runs with `--github-org org --github-repo a` in an account with no GitHub OIDC provider
- **THEN** the provider exists afterwards with client ID `sts.amazonaws.com`, it is not a resource of the core stack, and the operator role trusts it

#### Scenario: An existing provider is reused
- **WHEN** the provider already exists in the account with client ID `sts.amazonaws.com`
- **THEN** setup creates no provider and the operator role trusts the existing one

#### Scenario: A provider lacking the audience is left to an administrator
- **WHEN** the existing provider lists only another client ID
- **THEN** setup prints `aws iam add-client-id-to-open-id-connect-provider` with the provider's ARN and `sts.amazonaws.com`, does not call any IAM write on the provider, and waits interactively or exits 1 unattended

#### Scenario: Teardown leaves the provider
- **WHEN** `baas admin deployment teardown --yes` completes for a deployment with federation
- **THEN** the provider still exists, and the teardown's output says it was left in place

#### Scenario: The ARN option is gone
- **WHEN** `baas admin deployment setup --oidc-provider-arn arn:aws:iam::1:oidc-provider/x` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Deployer policy may create the GitHub OIDC provider, and nothing more of it
`BaasCliDeployerPolicy` SHALL grant `iam:GetOpenIDConnectProvider` and
`iam:CreateOpenIDConnectProvider` on the one provider ARN
`arn:aws:iam::<account>:oidc-provider/token.actions.githubusercontent.com`, and no other
OIDC-provider action on any resource. It SHALL grant `iam:UpdateAssumeRolePolicy`, scoped to the same
role resources as its other IAM statements, because deploying the core stack edits
`BaasCliOperatorRole`'s trust policy. Neither grant confers an escalation the policy did not already
allow, since `iam:CreateRole` writes a trust policy too.

#### Scenario: Only create and read on the one provider
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** its only actions ending `OpenIDConnectProvider` are `iam:GetOpenIDConnectProvider` and `iam:CreateOpenIDConnectProvider`, and their only resource is the `token.actions.githubusercontent.com` provider of the account

#### Scenario: The trust-policy edit is permitted and scoped
- **WHEN** an identity holding only `BaasCliDeployerPolicy` deploys a core stack carrying federation parameters
- **THEN** the deploy succeeds, and the policy's `iam:UpdateAssumeRolePolicy` grant names the same role resources as its role-creation statement rather than `Resource: "*"`

### Requirement: The deployer policy is sized as a customer-managed policy
The deployer policy SHALL be attached as the customer-managed policy
`BaasCliDeployerPolicy-<deployment>`, which IAM limits to 6144 non-whitespace characters, and the
rendered document SHALL stay below 5632 non-whitespace characters, keeping a 512-character reserve.
Setup's hand-over and the documentation SHALL describe only that attachment.

#### Scenario: Policy fits the budget
- **WHEN** the deployer policy is rendered
- **THEN** its non-whitespace length is below 5632

## MODIFIED Requirements

### Requirement: Core stack contains only CLI-owned infrastructure
The core CloudFormation stack (template `cf-template-core.yaml`) SHALL contain the VPC, public subnet, internet gateway, S3 gateway endpoint, `RunnerSecurityGroup`, `S3MainBucket`, `RunnerRole`, and `RunnerInstanceProfile`. It SHALL NOT contain any GitHub OIDC provider resource or `WorkflowRole`. It MAY take GitHub organisation and repository parameters, used solely to declare a conditional federated principal on `BaasCliOperatorRole`'s trust policy, naming the account's GitHub OIDC provider by an ARN composed from the stack's own account; it SHALL NOT take the provider's ARN as a parameter, and SHALL NOT create any other GitHub-related resource.

#### Scenario: Deploying the core stack creates no GitHub-related resources
- **WHEN** `baas admin deployment setup` deploys the core stack
- **THEN** the resulting CloudFormation stack contains no `AWS::IAM::OIDCProvider` resource and no `WorkflowRole`

#### Scenario: Federation parameters produce a trust statement, not a resource
- **WHEN** the core stack is deployed with GitHub organisation and repository supplied
- **THEN** the only difference from a deploy without them is a statement on the operator role's
  trust policy, naming `oidc-provider/token.actions.githubusercontent.com` in the stack's account

#### Scenario: No provider ARN parameter
- **WHEN** `cf-template-core.yaml` is inspected
- **THEN** it declares no parameter carrying an identity provider's ARN

### Requirement: baas admin deployment setup is self-sufficient
`baas admin deployment setup` SHALL accept `--region` and `--deployer-aws-profile` directly as command-line options, resolve the deployment name as *A deployment is named by its prefix, derived from the account by default* states, apply defaults for any omitted option, render the deployer policy for that deployment and check the caller's rights against it, deploy or update the core stack, and write the deployment's configuration file as *Each deployment has one configuration file, named by the deployment* states. It SHALL NOT require any configuration file to pre-exist. It SHALL NOT expose a `--prefix` option: the name is given only by the global `--deployment`. It SHALL NOT accept `--aws-profile`. When the deployment's stack is in `ROLLBACK_COMPLETE` — a first create that failed and rolled back, which leaves no resources and cannot be updated — setup SHALL delete that stack, wait for the deletion to complete, and then create the stack as for a new deployment. It SHALL NOT direct the user to `teardown` for that state.

#### Scenario: First run with no prior config
- **WHEN** `baas admin deployment setup` runs and no deployment is configured
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/deployments/<prefix>.yaml` is created with the deployment's region and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin deployment setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: The deployer profile option is renamed
- **WHEN** `baas admin deployment setup --aws-profile baas-admin` is invoked
- **THEN** picocli reports an unknown option error, and `--deployer-aws-profile baas-admin` is accepted and stored as `aws.deployerProfile` once there is evidence it can deploy

#### Scenario: A failed first create is recovered by re-running setup
- **WHEN** a first `baas admin deployment setup` failed, leaving the stack in `ROLLBACK_COMPLETE` and the deployment's configuration file, and setup is run again
- **THEN** setup deletes the rolled-back stack, creates the stack anew, and keeps the deployment's configuration file, without the user running any other command

#### Scenario: A failed first create can also be torn down
- **WHEN** a first create rolled back and the user decides not to retry
- **THEN** `baas --deployment <name> admin deployment teardown --yes` deletes the rolled-back stack and the deployment's configuration file

### Requirement: Deployer policy covers the table lifecycle
`BaasCliDeployerPolicy` SHALL grant `dynamodb:CreateTable`, `DescribeTable`, `UpdateTable`,
`TagResource`, `ListTagsOfResource`, `DescribeTimeToLive`, and `DeleteTable`, scoped to the
prefix-derived table ARN rather than `Resource: "*"`. The rendered policy SHALL remain within the
budget *The deployer policy is sized as a customer-managed policy* states.

#### Scenario: Setup creates the table under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin deployment setup`
- **THEN** the stack deploys with the results table created and the command exits 0

#### Scenario: Table actions are prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** every `dynamodb` statement names the prefix-derived table ARN and none uses `Resource: "*"`

#### Scenario: Policy still fits the budget
- **WHEN** the deployer policy is rendered
- **THEN** its non-whitespace length remains under the asserted customer-managed budget

### Requirement: Federation parameters are carried forward, and revoked only on request
`baas admin deployment setup` SHALL accept the GitHub organisation and repository list as options,
and SHALL NOT accept the identity provider's ARN, which it derives. When updating an existing stack it
SHALL carry forward the deployed value of every parameter the caller did not name, rather than
resubmitting a default in its place, so that a setup run for an unrelated reason cannot alter the
federated trust. It SHALL provide an explicit option that revokes federation by submitting those
parameters empty, and that option SHALL be the only way an update removes the federated trust
statement. The deployed stack SHALL be the single source of truth for the federation values; the CLI
SHALL NOT keep a second copy of them in its configuration file.

#### Scenario: An unrelated setup does not revoke CI access
- **WHEN** `baas admin deployment setup` is run again, naming no federation options, on a deployment whose
  federation parameters were supplied earlier
- **THEN** the federated trust statement is still present afterwards and continuous integration
  retains access

#### Scenario: Revocation is explicit
- **WHEN** `baas admin deployment setup` is run with the revocation option
- **THEN** the federated trust statement is absent from `BaasCliOperatorRole` afterwards, and the
  account-root principal remains

#### Scenario: Setting and revoking at once is refused
- **WHEN** `baas admin deployment setup` is invoked with both the revocation option and a GitHub organisation
  or repository
- **THEN** the command reports a usage error and deploys nothing

#### Scenario: A first deploy carries nothing forward
- **WHEN** the core stack is created for the first time with no federation options
- **THEN** the create succeeds with the federation parameters explicitly empty, rather than failing
  because a parameter has no previous value

#### Scenario: The values are read back from the stack, not from configuration
- **WHEN** an operator asks which repository the deployment trusts
- **THEN** the answer comes from the deployed stack's parameters, and the deployment's configuration file holds no
  copy of the organisation or repository list

#### Scenario: A stack from before the change updates cleanly
- **WHEN** setup updates a stack that still carries the removed provider-ARN parameter
- **THEN** the update is not refused for naming a parameter the template no longer declares, and the federated trust is kept

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

When rights are missing it SHALL state on standard error which actions are missing and that the
policy must be attached, name the file when `--policy-file` was given, and create nothing until the
check passes. A non-interactive run SHALL print the rendered policy to standard output and exit
non-zero. An interactive run without `--policy-file` SHALL ask whether to print the policy or save it
to a file, saving it as `policy.json` by default, and SHALL then wait for the user and check again, as
`guided-setup` states. Whenever it reports missing rights it SHALL also print, on standard error, the
commands an IAM administrator runs to attach the policy as a customer-managed policy named
`BaasCliDeployerPolicy-<deployment>`, with the account, the policy name and the caller filled in: one
set for a first attachment and one for publishing a new version of an existing policy.

When rights are present, or cannot be checked, it SHALL proceed. A permission failure during the
deployment SHALL still be reported together with the rendered policy, or with the path of the file
when `--policy-file` was given or the user saved it, whether the failure comes back from an AWS API
call made by setup or is reported by CloudFormation in the stack's events. There SHALL be no separate
command that prints the policy, and no option to render it for another account.

#### Scenario: Missing rights stop setup before anything is created
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup --non-interactive`
- **THEN** standard output holds the rendered policy JSON, standard error names the missing actions, no
  stack is created, and the exit code is non-zero

#### Scenario: The policy can be handed over
- **WHEN** `baas admin deployment setup > policy.json` runs for an identity lacking rights
- **THEN** `policy.json` is a valid IAM policy document for that deployment, and nothing else

#### Scenario: Missing rights are waited for interactively
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup` on a terminal and saves the policy
- **THEN** no stack is created, `policy.json` holds the rendered policy, the administrator's commands are shown, and setup waits rather than exiting

#### Scenario: Sufficient rights deploy as before
- **WHEN** an identity holding the rendered policy runs `baas admin deployment setup`
- **THEN** no policy is printed and the deployment is created or updated

#### Scenario: The command is gone
- **WHEN** `baas admin deployer-policy` is invoked
- **THEN** picocli reports an unknown command error

#### Scenario: A named deployment's policy names that deployment
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1 --non-interactive` runs for an identity holding only the default deployment's policy
- **THEN** the printed policy's resources are scoped to `wiktor-dev` in `us-east-1`, and nothing is created

#### Scenario: A statement newer than the attached policy is caught
- **WHEN** the attached policy lacks one statement the rendered policy has, such as `AssumeOwnOperatorRole`, and setup runs non-interactively
- **THEN** the check names that statement's missing action, nothing is created, and the exit code is non-zero

#### Scenario: Wildcard actions and pattern resources are checked as written
- **WHEN** the rendered policy grants `ec2:Describe*` on `*` and an Image Builder action on `…:component/<prefix>-*`
- **THEN** both are simulated with exactly those action names and resources, and a caller lacking either is reported missing it

#### Scenario: The policy file is written when rights are present
- **WHEN** an identity holding the rendered policy runs `baas admin deployment setup --policy-file p.json`
- **THEN** `p.json` holds the rendered policy and the deployment is created or updated

#### Scenario: The policy file is written when rights are missing
- **WHEN** an identity without the deployer policy runs `baas admin deployment setup --policy-file p.json --non-interactive`
- **THEN** `p.json` holds the rendered policy, standard error names the file and the administrator's commands, nothing is created, and the exit code is non-zero

#### Scenario: The administrator's commands are filled in
- **WHEN** setup reports missing rights for deployment `baas-development` in account `381492019823`
- **THEN** standard error shows an `aws iam create-policy` with policy name `BaasCliDeployerPolicy-baas-development`, an attach command for the caller, and an `aws iam create-policy-version … --set-as-default` for `arn:aws:iam::381492019823:policy/BaasCliDeployerPolicy-baas-development`

#### Scenario: A permission failure inside the stack is reported with the policy
- **WHEN** a deployment fails because CloudFormation reports a resource failed with a not-authorised reason
- **THEN** setup's report names that resource and reason and also hands the rendered policy over, or names the policy file when there is one

### Requirement: A deployment is named by its prefix, derived from the account by default
`baas admin deployment setup` SHALL create or update the deployment named by `--deployment`, using
that name verbatim as the resource name prefix. When `--deployment` is absent and no deployment is
configured on the machine, a non-interactive run SHALL derive the name from the caller's AWS account
identifier as `baas-<accountId>`, and an interactive run SHALL offer that name as the suggested
answer to its name prompt, accepting any valid name instead. When `--deployment` is absent and exactly
one deployment is configured, it SHALL update that one. When two or more are configured, a
non-interactive run SHALL require `--deployment`, and an interactive run SHALL ask which one. The
derived name SHALL NOT depend on which principal, role, session or permission set the caller is
using, and no name SHALL be used that the user neither typed, accepted nor left to the
non-interactive derivation.

#### Scenario: Two identities on one account resolve the same default deployment
- **WHEN** `baas admin deployment setup --non-interactive` is run with no `--deployment` on a machine with no configured deployment, by an IAM user and again by an SSO identity in the same AWS account and region
- **THEN** both invocations derive the prefix `baas-<accountId>` and target the same stack, bucket and results table

#### Scenario: Changing permission set does not fork the deployment
- **WHEN** the same human runs `baas admin deployment setup` under one permission set and later under a different one in the same account
- **THEN** the second run updates the existing deployment rather than creating a second one

#### Scenario: A named deployment beside the default one
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --region us-east-1` runs in an account whose default deployment `baas-<accountId>` exists in `eu-central-1`
- **THEN** a second deployment is created whose stack and bucket are named `wiktor-dev` and whose results table is `wiktor-dev-results`, and the default deployment is unchanged

#### Scenario: The default name is derivable without prior local state
- **WHEN** a machine that has never configured a deployment holds credentials for the account
- **THEN** the default deployment's prefix is computable from the caller's account identifier alone, with no value copied from another machine

#### Scenario: The derived name is a suggestion interactively
- **WHEN** an interactive setup with no deployment configured and no `--deployment` asks for the name
- **THEN** `baas-<accountId>` is the default answer, and a typed `team-bench` is used instead

### Requirement: Setup checks the stack's fixed names are free before creating it
The core stack creates a bucket named `<prefix>` and a table named `<prefix>-results`; if either name is
taken, the whole create rolls back. Before creating the stack — for a new deployment, and before
recovering a stack in `ROLLBACK_COMPLETE` — and after the deployer-permission check, `baas admin
deployment setup` SHALL check both names, and SHALL create and delete nothing while either is taken.
It SHALL name the cause and the remedy when: a bucket of that name exists in the deployment's region and
is accessible to the caller (a leftover of an earlier deployment: remove it with `aws s3 rb s3://<prefix>
--force`, after saving anything it holds); a bucket of that name is owned by another account (choose
another name); or a table of that name exists in the region (a leftover: remove it with `aws dynamodb
delete-table --table-name <prefix>-results`). A non-interactive run SHALL then exit non-zero. An
interactive run SHALL wait for a leftover to be removed, and for a name owned by another account SHALL
ask for another name, as `guided-setup` states. The early bucket-region lookup SHALL treat only a bucket
the caller can reach as the deployment's own, so that another account's bucket is never reported as this
deployment living in another region. An update of an existing stack SHALL NOT run these checks: its
bucket and table are its own.

#### Scenario: A leftover bucket is named before anything is created
- **WHEN** `baas admin deployment setup --non-interactive` creates a deployment whose bucket name already exists in its region with no stack of that name
- **THEN** setup exits non-zero naming the bucket and `aws s3 rb s3://<prefix> --force`, and no stack is created

#### Scenario: A leftover table is named before anything is created
- **WHEN** `baas admin deployment setup --non-interactive` creates a deployment whose table `<prefix>-results` already exists
- **THEN** setup exits non-zero naming the table and `aws dynamodb delete-table`, and no stack is created

#### Scenario: A name taken by another account
- **WHEN** `baas --deployment wiktor-dev admin deployment setup --non-interactive` runs and a bucket named `wiktor-dev` belongs to another account
- **THEN** setup exits non-zero saying the name is taken by another account and to choose another with `--deployment`, does not say the deployment lives in another region, and creates nothing

#### Scenario: A name taken by another account, interactively
- **WHEN** the same happens on an interactive run
- **THEN** setup asks for another name instead of exiting, and creates nothing for `wiktor-dev`

#### Scenario: A rolled-back stack with a leftover is not deleted
- **WHEN** the stack is in `ROLLBACK_COMPLETE` because its bucket name was taken, and setup is run again
- **THEN** setup refuses naming the bucket, and the rolled-back stack is left in place

#### Scenario: An update does not check its own names
- **WHEN** `baas admin deployment setup` runs against a deployment whose stack exists and is updatable
- **THEN** the existing bucket and table do not block it

### Requirement: The deployer may assume its own deployment's operator role
The rendered deployer policy SHALL allow `sts:AssumeRole` on
`arn:aws:iam::<account>:role/<prefix>-role-operator` for its own prefix only, and on no other role. It
SHALL still fit the customer-managed policy budget test.

#### Scenario: An admin runs a job with no operator setup
- **WHEN** a principal holding only the rendered deployer policy for `baas-123456789012` runs `baas run jmh -- MyBenchmark` with `aws.deployerProfile` set and no `aws.operatorProfile`
- **THEN** the CLI assumes `baas-123456789012-role-operator` and the job launches

#### Scenario: Another deployment's operator role is not granted
- **WHEN** the deployer policy rendered for `baas-123456789012` is inspected
- **THEN** its only `sts:AssumeRole` resource is `arn:aws:iam::<account>:role/baas-123456789012-role-operator`

### Requirement: Teardown removes every deployment resource
The working bucket and the results table SHALL carry `DeletionPolicy: Delete` and `UpdateReplacePolicy:
Delete`. `baas admin deployment teardown` SHALL empty the bucket — every object version and delete
marker — then delete the core stack, so that the bucket, the results table and every other stack
resource are removed, and then retire the runner image. A completed teardown SHALL leave no BaaS resource
of the deployment in the account, and SHALL report no retained resource of the deployment. The
account's GitHub OIDC provider is not a resource of any deployment and SHALL be left in place.

#### Scenario: Nothing is left after teardown
- **WHEN** `baas admin deployment teardown --yes` completes
- **THEN** the stack, the bucket, the results table, the runner image and its pointer no longer exist

#### Scenario: A later setup starts clean
- **WHEN** the same deployment is set up again after a teardown
- **THEN** setup creates the bucket and the table anew, and its check that their names are free passes

## REMOVED Requirements

### Requirement: baas-cli never manages the CI stack
**Reason**: The CI stack is deleted. The CLI now creates the account's GitHub OIDC provider through the IAM API when it is absent, and never modifies or deletes it (exploration D4), superseding `baas-cli-core-ci-split` D1/D2 in a new ADR.
**Migration**: Nothing to run. An account that deployed `cf-template-ci.yaml` keeps its provider, which setup reuses; the stack can stay, or be deleted only after confirming no other project uses the provider. Drop `--oidc-provider-arn` from scripts: the ARN is derived.

### Requirement: Deployer policy holds no CI-stack permissions
**Reason**: The deployer now creates the provider when it is absent; replaced by *Deployer policy may create the GitHub OIDC provider, and nothing more of it*, which keeps the `iam:UpdateAssumeRolePolicy` grant.
**Migration**: The first setup after upgrading reports the two new actions missing and hands over the new policy version; an IAM administrator publishes it with the printed `create-policy-version` command.

### Requirement: The CI stack contains only the identity provider
**Reason**: `infra/cf-template-ci.yaml` is deleted; the provider is created by setup.
**Migration**: None; an existing provider is reused as it is.
