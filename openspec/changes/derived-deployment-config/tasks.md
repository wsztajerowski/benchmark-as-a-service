# Tasks

Branch `derived-deployment-config` from `next-release`; the PR targets `next-release`. Run `mvn verify`
from the reactor root after each group; never `mvn -pl benchmark-runner` alone.

Commits are `feat`/`fix`/`docs` with no `!` and no footer or body line opening with the breaking-change
keyword: this is a feature release, not a major (see the release-notes landmine in commit `478d9fa`).
Live checks use the reactor JAR against `baas-development` (`java -jar baas-cli/target/baas-cli.jar
--deployment baas-development`, the `baas-dev` alias) and the installed `baas` only for
`baas-381492019823`.

## 1. Verify blocking assumptions

- [ ] 1.1 Confirm the identity shapes the resolver matches on: `aws sts get-caller-identity` under the
  `baas-operator-381492019823` profile returns `arn:aws:sts::<acct>:assumed-role/baas-381492019823-role-operator/<session>`,
  and under `baas-admin` returns something that is not an assumed operator role. Record both ARNs
  (account masked) in `verify.md`. If the operator session's ARN does not carry the role name, stop
  and revisit path A.
- [ ] 1.2 Confirm a chained `StsAssumeRoleCredentialsProvider` renews past one hour: a throwaway
  `jshell`/test harness assuming `baas-381492019823-role-operator` from the SSO source with
  `asyncCredentialUpdateEnabled(true)` and no `durationSeconds` above 3600, calling
  `GetCallerIdentity` every 5 minutes for 70 minutes. Record the expiry times seen. If renewal fails,
  stop and revisit the design's chained-session risk.
- [ ] 1.3 Confirm `AssumeRole` with `DurationSeconds` > 3600 is refused on a chained session (so the
  provider must not request it): one `aws sts assume-role --duration-seconds 7200` from an assumed-role
  source. Record the error in `verify.md`.
- [ ] 1.4 Confirm `HeadBucket` region discovery works under an operator-role session (`baas-operator-381492019823`)
  from a region other than the bucket's, as the no-file path needs. Record the answer.
- [ ] 1.5 Confirm `${GITHUB_REPOSITORY}` is visible to `release.yml`'s semantic-release `prepareCmd`
  (it runs in the job's environment): read the step's env in the workflow and the
  `@semantic-release/exec` documentation; cite both in `verify.md`.

## 2. Configuration file shape

- [ ] 2.1 Make `BaasConfig`'s preference fields nullable with defaults applied in their getters, and
  serialise non-null values only; remove `prefix` and `RunnerConfig`; keep writing `aws.region`.
  Verify with `ConfigService` tests for the three scenarios of *A configuration file never records the
  CLI's own defaults*.
- [ ] 2.2 `ConfigService` sets the deployment name on the loaded object from the filename, and the
  prefix-mismatch refusal (`mismatch`) and its tests are deleted. Verify with a test that a file loads
  as its filename's deployment and with the three scenarios of *Each deployment has one configuration
  file, named by the deployment*.
- [ ] 2.3 Remove the flat-`~/.baas/config.yaml` migration from `ConfigService` (the move, the
  `aws.profile` → `aws.deployerProfile` rename and its `@JsonAlias("profile")`) and its tests. Verify by
  grep (no `config.yaml` handling left in main code) and by a test that a stray `~/.baas/config.yaml`
  is neither read nor removed.
- [ ] 2.4 `ConfigShowSubcommand` shows defaults as defaults (marked as such) and no prefix or source
  repository field. Verify with its tests.

## 3. Operator credential resolution

- [ ] 3.1 `AwsClientFactory` takes a region and an `AwsCredentialsProvider` (not a profile name);
  `s3In` keeps the same provider. Verify every existing factory test passes after the signature change.
- [ ] 3.2 Add the resolver (source chain `operatorProfile` → `deployerProfile` → default chain;
  `GetCallerIdentity`; paths A/B/C; C via `StsAssumeRoleCredentialsProvider` with async refresh and no
  duration above 3600 s). Verify with unit tests over a stubbed STS: A uses the source as is and issues
  no `AssumeRole`; B fails naming both deployments; C assumes
  `arn:aws:iam::<acct>:role/<name>-role-operator`; `operatorProfile` wins over `deployerProfile`.
- [ ] 3.3 A failed assumption stops the command: the resolver throws, and no client is ever built on
  the source credentials after identification. Verify with a test that a stubbed `AccessDenied` on
  `AssumeRole` leaves the EC2/S3/DynamoDB/CloudFormation stubs uncalled and the exit non-zero naming
  role and source (spec *A failed assumption stops the command*).
- [ ] 3.4 Admin commands (`SetupCommand`, `TeardownCommand`, `ImageBuildSubcommand`,
  `ImageShowSubcommand`) build their factory from `deployerProfile` → default chain, as is, never the
  resolver. Verify with a test that `admin image build` with both profiles set issues no `AssumeRole`.
- [ ] 3.5 Wire every operator command (`run`, `jobs *`, `results query`, `config sync`) through the
  resolver, once per process; delete `RunCommand.operatorCredentialsWarning`. Verify by grep (no
  `new AwsClientFactory(` with a profile string remains in operator commands) and by the commands'
  existing tests.

## 4. Deployment name and region

- [ ] 4.1 When no deployment is configured and no `--deployment` is given, a non-setup, non-sync
  command takes its name from an operator-role session; otherwise it fails as today. Verify with
  `ConfigService`/command tests for both scenarios of the new naming requirement, and that with one or
  two files configured the role never selects (only B can refuse).
- [ ] 4.2 With no region in a file (no file at all), an operator command finds the region with the
  bucket lookup after credential resolution, once per invocation, starting from `AWS_REGION` else
  `eu-central-1`; a missing bucket fails naming the deployment before any other deployment-scoped call.
  A file's `aws.region` is used with no lookup. Verify with tests for the three scenarios of *The
  region resolves from the file, then the environment*.

## 5. `config sync` as adopt

- [ ] 5.1 Add `--operator-aws-profile` to `config sync`; sync resolves credentials, finds the region,
  checks the stack, then writes the file with the region (and the profile if given) and writes nothing
  on any failure. Verify with `ConfigSyncSubcommand` tests for every scenario of *Operators can bootstrap
  config without the deployer's machine*, including the refused-credentials case writing no file.

## 6. Setup text and deployer policy

- [ ] 6.1 Add to `infra/deployer-policy.json` one statement allowing `sts:AssumeRole` on
  `arn:aws:iam::${ACCOUNT_ID}:role/${PREFIX}-role-operator`. Verify with `DeployerPolicyRenderer` tests:
  the rendered document's only `sts:AssumeRole` resource is its own operator role, and
  `renderedPolicyLeavesRoomInAnInlinePolicyBudget` still passes (record the new size in `verify.md`).
- [ ] 6.2 `SetupCommand.nextSteps` on create: state that the deployer's credentials already run
  day-to-day commands as the operator role; for teammates, the `sts:AssumeRole` grant and
  `baas --deployment <name> config sync --operator-aws-profile <profile>`; no `role_arn` profile. Verify
  with its existing text tests updated.

## 7. Runner source repository

- [ ] 7.1 Add `baas.sourceRepo` (default `wsztajerowski/benchmark-as-a-service`) to the parent POM and
  `Baas-Source-Repo: ${baas.sourceRepo}` to `baas-cli`'s shade `manifestEntries`; read it beside
  `BaasVersion`; remove `RunnerConfig`. Verify with `unzip -p baas-cli/target/baas-cli.jar
  META-INF/MANIFEST.MF` showing the attribute, and a build with `-Dbaas.sourceRepo=someone/fork`
  showing that value.
- [ ] 7.2 `RunnerJarResolver` takes the repository from the build, and its errors name the repository
  and version without pointing at any configuration key. Verify with its tests (spec *A retrieval
  failure names what it tried*).
- [ ] 7.3 `release.yml`'s `prepareCmd` passes `-Dbaas.sourceRepo=${GITHUB_REPOSITORY}` to its
  `package`. Verify by reading the rendered command; the bake itself is exercised only by a real
  release (note this in `verify.md`).

## 8. Continuous integration

- [ ] 8.1 Remove both `config sync` steps from `e2e-cloud-test.yml` and every `CORE_STACK_NAME`
  reference; keep `OPERATOR_ROLE_ARN` and `AWS_REGION`. Verify by grep, and by the PR's own e2e run
  going green on both jobs (`benchmark`, `jcstress`) with no configuration file.
- [ ] 8.2 After merge into `next-release`, delete the `CORE_STACK_NAME` repository variable
  (`gh variable delete CORE_STACK_NAME`) — only after confirming no workflow on `main` still reads it;
  if `main` does, defer the deletion until `next-release` reaches `main` and record that in `verify.md`.

## 9. Documentation

- [ ] 9.1 CLAUDE.md: reword the `aws.operatorProfile` invariant to *day-to-day commands always run as
  `<name>-role-operator`; any credentials, the deployer's included, are only the source of that
  assumption, and a failed assumption stops the command*; update the deployment-file, `config sync`,
  region (CI no longer stores one through sync) and absent-`--deployment` paragraphs; remove the `CORE_STACK_NAME` row from the GHA table.
  Verify by grep for `must not fall back`, `stores the region`, `CORE_STACK_NAME` and `sourceRepo`.
- [ ] 9.2 ADR 0006: append an amendment recording the operator-role name derivation and why A10's harm
  does not apply. ADR 0002: a note that CI, having no file, now looks the region up per invocation
  rather than through `config sync`. Verify both read coherently with the design's decisions.
- [ ] 9.3 `infra/README.md`: drop the `role_arn` profile step and the "does not fall back" paragraph;
  describe the source chain and the teammate's `config sync --operator-aws-profile`. Verify by grep for
  `role_arn` and `source_profile`.
- [ ] 9.4 Delete findings E3 and U8 from `docs/review/open-findings.md` (the decisions live in this
  change's design and the ADR amendment). Verify both IDs are gone from the file.
- [ ] 9.5 Update the `docs/diagrams/` sequence diagrams whose `config sync`, setup or credential steps
  change; render each edited `.mmd` with `mmdc` to the scratchpad and look at the PNG before committing.

## 10. Manual verification on a second deployment (IAM change)

- [ ] 10.1 **Manual.** On a second deployment (`infra/README.md`, *A second deployment*): setup with
  `--deployer-aws-profile baas-admin` and the new policy attached customer-managed; `admin image
  build`; with no `aws.operatorProfile` set, `baas --deployment <name> run jmh` on `fake-jmh-benchmarks`
  completes (path C from the deployer); `jobs list`, `results query --job-id` succeed; teardown.
  Record each step's outcome in `verify.md`.
- [ ] 10.2 **Manual.** On the same deployment before teardown: a teammate-style source (`lynx`) via
  `config sync --operator-aws-profile lynx` writes only that key, and `run` works; a profile that may
  not assume the role is refused by sync with no file written; credentials of the *default*
  deployment's operator role against `--deployment <name>` are refused (path B). Record in `verify.md`.
- [ ] 10.3 **Manual.** An attached `baas run` longer than one hour from a chained source (a `fake-jmh-benchmarks`
  run with enough iterations and iteration time, `-i`/`-r`, to exceed an hour, and `--timeout` raised
  to match) completes and the CLI, not the watchdog,
  terminates the instance. Record the duration and the terminating layer in `verify.md`.

## 11. Verify

- [ ] 11.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a
  requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation
  from design or tasks.
