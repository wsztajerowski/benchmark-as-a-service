# Proposal

## Why

Once an admin has run `baas admin deployment setup`, every other machine — CI above all — should
need as little as possible to use the deployment. Today CI passes three values (`OPERATOR_ROLE_ARN`,
`AWS_REGION`, `CORE_STACK_NAME`) where the role ARN already contains the deployment name, and runs a
`config sync` whose only output is a file that holds copies: the prefix (already the file's name),
the code's own defaults frozen at the moment the file
was first saved, and a runner source repository that is a fact about the CLI build, not the
deployment. A laptop additionally needs a hand-written `~/.aws` profile with `role_arn` +
`source_profile` before `baas run` runs as the operator, because the CLI cannot assume the role
itself.

## What Changes

- **A deployment is credentials plus a name.** Operator commands resolve their credentials into
  `<name>-role-operator` themselves:
  - **A** — the session already is `<name>-role-operator`: used as is. With no deployment configured
    and no `--deployment`, the name is read from that role (the CI path).
  - **B** — the session is *another* deployment's operator role: refused before any other AWS call.
  - **C** — any other credentials: the CLI assumes `arn:aws:iam::<account>:role/<name>-role-operator`,
    the account taken from `sts:GetCallerIdentity`, with a refreshing provider.
  - A failed assumption stops the command; it never falls back to running on the source credentials.
- **The operator credential source falls back to the deployer profile.** Operator commands take
  their source from `aws.operatorProfile`, else `aws.deployerProfile`, else the default chain — always
  narrowed by A/B/C, so deployer rights never reach a day-to-day command. The CLAUDE.md invariant
  is reworded: *`aws.operatorProfile` must not fall back to `aws.deployerProfile`* becomes *day-to-day
  commands always run as `<name>-role-operator`; any credentials, the deployer's included, are only
  the source of that assumption.*
- **The deployer policy may assume its own deployment's operator role** (one statement,
  prefix-exact), so the fallback works for an admin with no further setup.
- **The region stays in the file and is looked up only when there is no file.** It cannot be derived
  without an AWS call and never changes while the deployment exists, so setup and `config sync` keep
  recording it. A machine with no file (CI on the operator role) finds it from the bucket
  (`HeadBucket`), once per invocation — the step `config sync` used to do for CI.
- **The deployment file holds only what the user chose**, each key written only when set:
  `aws.deployerProfile`, `aws.operatorProfile`, `ec2.defaultInstanceType`,
  `ec2.benchmarkTimeoutSeconds`, `ec2.watchdogMarginSeconds`, `git.resolveProject` — plus the
  `aws.region` setup or sync found. `prefix` and `runner.sourceRepo` are no longer written. No
  migration: the existing deployments are test deployments, torn down and recreated with this CLI,
  and `~/.baas/deployments/` is deleted first. The release is a feature release, not a major.
- **The flat-`~/.baas/config.yaml` migration from `multiple-deployments` is removed** from
  `ConfigService`, for the same reason: no machine holds one.
- **`runner.sourceRepo` is baked into the release build** as a JAR manifest attribute, set from the
  releasing repository, like the version. `RunnerJarResolver`'s stale "Set runner.sourceRepo in
  ~/.baas/config.yaml" goes with it.
- **`baas config sync` becomes "adopt"**: proves the credentials reach `<name>-role-operator`, finds
  the region and checks the stack, then writes the file with the region (and `--operator-aws-profile`
  if given).
- **CI drops `config sync` and the `CORE_STACK_NAME` variable.** It keeps `OPERATOR_ROLE_ARN` and
  `AWS_REGION` (the credentials action needs a region for STS).
- **Setup's closing text** says the deployer's own credentials already operate the deployment; only a
  teammate needs an `sts:AssumeRole` grant, and no one needs a `role_arn` profile.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `cli-command-structure`: the deployment-name rule gains the role-derived case; the configuration
  file stores user choices and the region, never a prefix or a code default; with no file, the region
  is found from the bucket; the flat-file migration is removed.
- `core-stack-provisioning`: operator credentials are resolved into the operator role by the CLI,
  with the deployer profile as a fallback source; `config sync` adopts without copying; the deployer
  policy may assume its own operator role.
- `ci-benchmark-execution`: CI passes only the role ARN and a region, with no `config sync`.
- `runner-jar-distribution`: the runner source repository is a property of the release build, not
  configuration.

## Impact

- **Code:** `baas-cli` — `BaasConfig`, `ConfigService` (file shape; the prefix-mismatch refusal and the flat-file migration go), `AwsClientFactory`
  (takes a credentials provider), a new resolver for credentials, name and — with no file — region,
  used by every operator command, `ConfigSyncSubcommand`, `ConfigShowSubcommand`, `SetupCommand`
  (closing text, deployer source), `RunnerJarResolver`, `RunCommand.operatorCredentialsWarning`
  (removed). `baas-cli/pom.xml` (manifest attribute). `release.yml` (`-D` for the source repository).
- **IAM:** `infra/deployer-policy.json` gains `sts:AssumeRole` on
  `arn:aws:iam::${ACCOUNT_ID}:role/${PREFIX}-role-operator` (~110 characters against the 4608 test
  ceiling; currently 4105). No template, operator-policy or trust-policy change: the operator role
  already trusts the account root, and `GetCallerIdentity` needs no permission.
- **CI:** `e2e-cloud-test.yml` loses both `config sync` steps; the `CORE_STACK_NAME` repository
  variable is deleted.
- **Docs:** CLAUDE.md (the operator-profile invariant, the deployment-file, sync, region and
  absent-`--deployment` paragraphs, the GHA variable table), ADR 0006 amended (the role-derived name),
  `infra/README.md` (no `role_arn` profile).
- **Findings closed:** E3 (the admin's machine no longer needs an operator profile after a
  re-setup, and setup stops reading as a first-time grant), U8 (`runner.sourceRepo` is no longer a
  configuration value at all).
- **Cost:** none. No AWS resource is added or changed. Each operator command makes one more unbilled
  call (`GetCallerIdentity`); `AssumeRole` replaces the one a `role_arn` profile already made; the
  `HeadBucket` region lookup happens only on a machine with no file.
- **Comparability:** none. Nothing reaches the runner, the image, user-data or a stored measurement.
- **Deliberately not changed:** `--deployment` remains the only explicit selector and the
  one-configured / refuse-on-two rule stands (ADR 0006: no default, no `config use`, no
  `BAAS_DEPLOYMENT`); no global `--aws-profile`; admin commands use the deployer source as is, never
  an assumed role; job-policy defaults stay per-user and local (no SSM); the operator role's trust
  policy and the accepted risk *`OperatorRole` trusts the account root* are untouched; the
  `RunnerJarResolver` checksum verification and the never-overwritten `releases/<version>/` slot are
  untouched.
