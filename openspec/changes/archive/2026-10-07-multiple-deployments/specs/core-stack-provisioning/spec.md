# Spec Delta

## ADDED Requirements

### Requirement: A deployment is named by its prefix, derived from the account by default
`baas admin deployment setup` SHALL create or update the deployment named by `--deployment`, using
that name verbatim as the resource name prefix. When `--deployment` is absent and no deployment is
configured on the machine, it SHALL derive the name from the caller's AWS account identifier as
`baas-<accountId>`. When `--deployment` is absent and exactly one deployment is configured, it SHALL
update that one. When two or more are configured, it SHALL require `--deployment`. The derived name
SHALL NOT depend on which principal, role, session or permission set the caller is using.

#### Scenario: Two identities on one account resolve the same default deployment
- **WHEN** `baas admin deployment setup` is run with no `--deployment` on a machine with no configured deployment, by an IAM user and again by an SSO identity in the same AWS account and region
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

### Requirement: A deployment name satisfies every service that carries it
`baas admin deployment setup` SHALL refuse, before any AWS call, a deployment name that some resource
composed from it could not carry. A name SHALL be accepted only if it:
- is 3 to N characters long, where N is 64 minus the length of the longest role-name suffix the
  composition rule appends, so that every composed IAM role name fits IAM's 64 characters;
- matches `^[a-z][a-z0-9-]*[a-z0-9]$`;
- contains no `--`;
- does not start with `aws`, `ssm`, `sthree-` or `amzn-s3-demo-`;
- does not end with `-s3alias`.

The refusal SHALL name the rule that was broken. No other check SHALL apply. In particular, a name of
the form `baas-<12 digits>` naming a different account is accepted.

#### Scenario: An SSM-reserved name is refused before deploying
- **WHEN** `baas --deployment aws-dev admin deployment setup` runs
- **THEN** the command exits non-zero naming the reserved `aws` prefix, issues no AWS call, and no policy is printed

#### Scenario: A name too long for the composed role names is refused
- **WHEN** the deployment name is one character longer than N
- **THEN** the command exits non-zero naming the length limit and the composed name that would exceed it

#### Scenario: Another account's default-looking name is accepted
- **WHEN** `baas --deployment baas-999999999999 admin deployment setup` runs in account `123456789012`
- **THEN** the name passes validation

### Requirement: Setup is the only command that validates a name
Commands other than `baas admin deployment setup` SHALL NOT validate a deployment name. They SHALL
resolve it against the deployments configured on the machine, and a name that is not configured
SHALL fail as an unknown deployment.

#### Scenario: An unknown name is reported as unknown, not invalid
- **WHEN** `baas --deployment Foo results query` runs and no deployment `Foo` is configured
- **THEN** the command exits non-zero naming the deployments that are configured, and issues no AWS call

## MODIFIED Requirements

### Requirement: baas admin deployment setup is self-sufficient
`baas admin deployment setup` SHALL accept `--region` and `--deployer-profile` directly as command-line options, resolve the deployment name as *A deployment is named by its prefix, derived from the account by default* states, apply defaults for any omitted option, render the deployer policy for that deployment and check the caller's rights against it, deploy or update the core stack, and write the result to that deployment's configuration file. It SHALL NOT require any configuration file to pre-exist. It SHALL NOT expose a `--prefix` option: the name is given only by the global `--deployment`. It SHALL NOT accept `--aws-profile`.

#### Scenario: First run with no prior config
- **WHEN** `baas admin deployment setup` runs and no deployment is configured
- **THEN** the core stack is deployed using the default region and the account-derived prefix, and `~/.baas/deployments/<prefix>.yaml` is created with the deployment's prefix and the credential settings it needs

#### Scenario: No --prefix option
- **WHEN** `baas admin deployment setup --prefix foo` is invoked
- **THEN** picocli reports an unknown option error

#### Scenario: The deployer profile option is renamed
- **WHEN** `baas admin deployment setup --aws-profile baas-admin` is invoked
- **THEN** picocli reports an unknown option error, and `--deployer-profile baas-admin` is accepted and stored as `aws.deployerProfile`

### Requirement: Setup renders the deployer policy and stops when the caller lacks it
Before calling any CloudFormation, DynamoDB or EC2 API — after only the name validation and the
bucket-region lookup, which must come first so that a deployment living in another region is named
rather than met with a policy for a second one — `baas admin deployment setup` SHALL render
the deployer policy for the deployment it is about to create or update — the account from
`sts:GetCallerIdentity`, the region from `--region` or its default, the name resolved as
*A deployment is named by its prefix, derived from the account by default* states — and, where the
caller may call `iam:SimulatePrincipalPolicy`, check the
caller's rights against it. When rights are missing it SHALL print the rendered policy to standard
output, state on standard error which actions are missing and that the policy must be attached before
re-running, create nothing, and exit non-zero. When rights are present, or cannot be checked, it SHALL
proceed; an `AccessDenied` during the deployment SHALL still be reported together with the rendered
policy. There SHALL be no separate command that prints the policy, and no option to render it for
another account.

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

### Requirement: Teardown safety gates
`baas admin deployment teardown` SHALL abort if any EC2 instance tagged `baas-role=benchmark-runner` and
`baas-deployment=<the deployment being torn down>` is in the `pending` or `running` state; runners of
another deployment SHALL NOT block it. It SHALL require explicit confirmation — retyping the
deployment's name, or `--yes`. Both gates SHALL pass before anything is deleted, the runner image
included. When it aborts over in-flight jobs, it SHALL name each job by the identifier in its
instance's `baas-job-id` tag, alongside the instance identifier and state, and SHALL name
`baas jobs terminate <jobId>` as the way to stop one. It SHALL read those identifiers from the
instances, not from the results table. Before the prompt it SHALL state that the deployment's bucket
and results table, with every job and measurement in them, will be deleted and cannot be recovered.

#### Scenario: Abort when a job is in flight
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `running`
- **THEN** the command exits with an error listing each in-flight job's identifier, instance ID and
  state, names `baas jobs terminate <jobId>`, and performs no destructive action

#### Scenario: Abort when a job is still booting
- **WHEN** `baas admin deployment teardown` runs while a runner instance of the same deployment is `pending`
- **THEN** the command exits with an error listing that job's identifier and instance ID and performs
  no destructive action, so a job launched moments earlier does not lose its role, subnet or image
  mid-boot

#### Scenario: Another deployment's runner does not block
- **WHEN** `baas --deployment wiktor-dev admin deployment teardown` runs while a runner of deployment `baas-123456789012` in the same region is `running`
- **THEN** the in-flight gate passes for `wiktor-dev`

#### Scenario: The gate needs no table access
- **WHEN** `baas admin deployment teardown` runs under the deployer policy, which grants no read of the results table
- **THEN** the in-flight check and its message succeed

#### Scenario: The prompt says everything will be deleted
- **WHEN** `baas admin deployment teardown` prompts for confirmation
- **THEN** the prompt states that the bucket and the results table will be deleted, and `--delete-bucket`
  is not an option

### Requirement: Day-to-day commands resolve operator credentials
A deployment's configuration file SHALL carry `aws.operatorProfile`. `baas run`, `baas results query`, and `baas config show` SHALL resolve AWS credentials from it; `baas admin deployment setup` and `baas admin deployment teardown` SHALL continue using `aws.deployerProfile`. When `aws.operatorProfile` is unset, day-to-day commands SHALL fall through to the default credential chain and SHALL NOT use `aws.deployerProfile`.

#### Scenario: Deployer profile is never silently reused
- **WHEN** the configuration has `aws.deployerProfile: baas-deployer` and no `aws.operatorProfile`, and `baas run jmh` is invoked
- **THEN** the AWS client is built without an explicit profile, and a warning naming `baas config set --operator-profile` is printed

#### Scenario: Operator profile is honoured
- **WHEN** the configuration has `aws.operatorProfile: baas-operator` and `baas run jmh` is invoked
- **THEN** the AWS client is built with the `baas-operator` profile

### Requirement: Operators can bootstrap config without the deployer's machine
`BaasCliOperatorRole` SHALL be granted `cloudformation:DescribeStacks` scoped to its own core stack.
`baas config sync` SHALL verify that the deployment's stack exists and write that deployment's
configuration file. When no deployment is configured on the machine, `--deployment` SHALL be
required, so that a machine with no prior configuration declares which deployment it adopts rather
than inferring one from whichever credentials happen to be active. The name SHALL never be derived
from the account. When exactly one deployment is configured, a bare `baas config sync` SHALL re-sync
that one. When two or more are configured, it SHALL require `--deployment` as every command does.
`baas config sync` SHALL NOT store values the CLI derives from the prefix or resolves from the stack
at use time.

#### Scenario: Operator on a fresh machine
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas config sync --deployment baas-123456789012` with no configured deployment
- **THEN** that deployment's configuration file is written and `baas run` works without hand-copying any file

#### Scenario: Sync without a name is refused
- **WHEN** `baas config sync` is invoked with no `--deployment` and no deployment is configured
- **THEN** the command exits non-zero asking for `--deployment`, issues no AWS call and writes nothing

#### Scenario: Adopting a deployment set up by hand
- **WHEN** an operator runs `baas config sync --deployment baas-123456789012-dev`, naming a deployment whose stack was deployed by hand rather than by `baas admin deployment setup`
- **THEN** that deployment's configuration file is written, and later `baas run`, `baas results query` and `baas admin` invocations naming it address that deployment

#### Scenario: Re-syncing the only deployment
- **WHEN** `baas config sync` is invoked with no `--deployment` and exactly one deployment is configured
- **THEN** that deployment is re-synced and no other file is written

#### Scenario: Adopting a second deployment
- **WHEN** `baas config sync --deployment wiktor-dev` runs on a machine where `baas-123456789012` is configured
- **THEN** a second configuration file is written, `baas-123456789012`'s is unchanged, and later commands must name one of the two

### Requirement: Resource names follow one composition rule
Every resource the core stack names SHALL be `<prefix>`, `<prefix>-<name>`, or
`<prefix>-<resource_type>-<name>`, where the `resource_type` and `name` segments are present only
when they are needed to distinguish one resource from another of the same kind. The prefix SHALL be
the deployment name verbatim. The derived default carries the `baas-` namespace itself, so no
resource name composes the namespace separately, and a named deployment's prefix carries whatever
its name carries.

#### Scenario: The stack and bucket take the bare prefix
- **WHEN** a deployment is set up for account `123456789012` with no name given
- **THEN** the stack is named `baas-123456789012` and the working bucket is named `baas-123456789012`

#### Scenario: A named deployment's prefix is used verbatim
- **WHEN** a deployment named `wiktor-dev` is set up
- **THEN** its stack and bucket are named `wiktor-dev` and its runner role `wiktor-dev-role-runner`, with no `baas-` prepended

#### Scenario: Resources of the same kind are distinguished by name
- **WHEN** the stack creates the runner, operator and image-build roles
- **THEN** they are named `<prefix>-role-runner`, `<prefix>-role-operator` and `<prefix>-role-image-build`

#### Scenario: Image Builder resources are distinguishable from one another
- **WHEN** the stack creates the Image Builder component, recipe, infrastructure configuration, distribution configuration and pipeline
- **THEN** no two of them share a name

## REMOVED Requirements

### Requirement: Resource names are derived from the caller's AWS account
**Reason**: More than one deployment per account is now supported, to develop BaaS itself. The
account-derived name becomes the default rather than the only name. Its stability across principals
is kept in *A deployment is named by its prefix, derived from the account by default*.
**Migration**: Nothing for an existing deployment: its name is the derived default, unchanged. A
second deployment is created with `baas --deployment <name> admin deployment setup`.
