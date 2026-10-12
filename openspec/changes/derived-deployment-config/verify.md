# Verify — derived-deployment-config

## 1. Blocking assumptions

### 1.1 Identity shapes (2026-10-12)

| Profile | `sts get-caller-identity` ARN (account masked) |
|---|---|
| `baas-operator-381492019823` (`role_arn` profile) | `arn:aws:sts::<acct>:assumed-role/baas-<acct>-role-operator/botocore-session-…` |
| `baas-admin` | `arn:aws:iam::<acct>:user/baas-admin-wiktor` |
| `lynx` | `arn:aws:iam::<acct>:user/wsztajerowski_baas` |

The operator session's ARN carries the role name, so path A can match on it and strip
`-role-operator`. The deployer is an IAM user, not an assumed operator role, so it takes path C.

Finding: both source profiles on this machine are **IAM users**, not SSO sessions. AssumeRole from an
IAM user is not role chaining, so the one-hour chained-session cap does not apply here at all; it
applies to SSO (or any role-session) sources.

### 1.2 Renewal (deviation from the task as written)

No SSO source exists on this machine, so a chained session past one hour cannot be reproduced. What
the risk depends on is the provider's renewal, which is the same mechanism whatever the session
length. Tested instead: `StsAssumeRoleCredentialsProvider` with `asyncCredentialUpdateEnabled(true)`,
source `lynx`, `durationSeconds(900)` (the minimum), `GetCallerIdentity` every minute for 32 minutes —
two full session lifetimes. Harness: scratchpad `RenewHarness.java`, run against the shaded CLI JAR.

RESULT (UTC, account masked): every one-minute `GetCallerIdentity` succeeded as
`assumed-role/baas-<acct>-role-operator/baas-renew-test`.

| Session | Expiry | Replaced at |
|---|---|---|
| key `…CDNK` | 21:22:51 | ~21:19:44 (about 3 min early) |
| key `…NJ7C` | 21:34:44 | ~21:32:44 (about 2 min early) |
| key `…RKJA` | 21:47:05 | — |

The provider renews ahead of expiry with no failed call in between, so a one-hour chained session is
renewed the same way for as long as its source is valid. Not exercised: an SSO source itself
expiring — the same exposure a `role_arn` profile already has (design, Risks).

### 1.3 Duration above one hour on a chained session

Not reproducible here (no role-session source that may assume the operator role). Documented
behaviour, AWS STS `AssumeRole` API reference, `DurationSeconds`: role chaining limits the session to
one hour, and a `DurationSeconds` above one hour on a chained call makes the operation fail. Design
consequence kept: the resolver does not set a duration, so STS's default (3600 s) applies — valid for
chained and unchained sources alike, and renewed by the provider.

### 1.4 Region discovery under the operator role

`aws s3api head-bucket --bucket baas-381492019823 --region us-east-1 --profile
baas-operator-381492019823` → `BucketRegion: eu-central-1`; from `eu-central-1` the response carries
`x-amz-bucket-region: eu-central-1`. The no-file path works under operator credentials from a region
other than the bucket's.

### 1.5 `GITHUB_REPOSITORY` in the release

`release.yml`'s *Generate release configuration* step writes `release.config.js` with a double-quoted
`echo`, so `$GITHUB_REPOSITORY` (a default variable GitHub Actions sets in every step,
<https://docs.github.com/en/actions/reference/variables-reference#default-environment-variables>) is
expanded by bash when the file is written; the value is baked into the config before
`@semantic-release/exec` (which would otherwise treat `${…}` as a lodash template) ever sees it.
Plain `$GITHUB_REPOSITORY`, not `${GITHUB_REPOSITORY}`, so nothing is left for lodash.

Finding: `publishCmd` (`mvn deploy`) re-packages the CLI, so it needs the same
`-Dbaas.sourceRepo=…` as `prepareCmd`, or the deployed Maven artifact would carry the default.
Applied in 7.3.

## Implementation notes and deviations

- **7.1 — build property, not a manifest attribute.** The design named a `Baas-Source-Repo` manifest
  entry. `baas-cli` already carries a Maven-filtered `baas-build.properties` (read by
  `RunnerImageRenderer`), so `runner.sourceRepo=${baas.sourceRepo}` went there and
  `BaasVersion.sourceRepo()` reads it: the same in tests and in the shaded JAR, with no manifest
  lookup that could pick another JAR's manifest off the classpath. Verified: `process-resources
  -Dbaas.sourceRepo=someone/fork` writes `someone/fork`; without it, the upstream default.
- **7.3** — simulated the *Generate release configuration* step with `GITHUB_REPOSITORY=someone/fork`
  and loaded the resulting `release.config.js` in node: both `prepareCmd` and `publishCmd` carry
  `-Dbaas.sourceRepo=someone/fork`. The real bake runs only in a release.
- **6.1** — rendered deployer policy (fixture prefix `baas-123456789012`): 4219 → 4370 non-whitespace
  characters, under the test's 4608. CLAUDE.md's 4105 was already stale before this change; it now
  says 4370.
- **Unit-test isolation.** With no deployment configured, a day-to-day command now asks STS who the
  caller is. Surefire therefore excludes every AWS credential variable and sets
  `AWS_EC2_METADATA_DISABLED`, so no unit test can make a real STS call from a developer's shell; a
  failed identification there reads as "no deployment is configured".
- **3.4** — admin commands build `new AwsClientFactory(region, AwsClientFactory.profile(deployerProfile))`
  and never touch `OperatorSession`; checked by reading all four (`SetupCommand`, `TeardownCommand`,
  `ImageBuildSubcommand`, `ImageShowSubcommand`) rather than by a dedicated test.

## Live checks, read-only, `baas-development` (2026-10-12, reactor JAR)

| Case | Setup | Result |
|---|---|---|
| A, with a file | existing `baas-development.yaml`, `operatorProfile` = a `role_arn` profile | `jobs list` lists the deployment's jobs; file unchanged |
| A, no file (the CI path) | empty `user.home`, `AWS_PROFILE=baas-operator-baas-dev`, `AWS_REGION=eu-central-1` | name taken from the role, region found as `us-east-1`; jobs listed; nothing written |
| C from an IAM user | file with `operatorProfile: lynx` | role assumed, jobs listed |
| B | file for `baas-development`, `AWS_PROFILE=baas-operator-381492019823` | refused naming `baas-381492019823` and `baas-development`; nothing else called |
| C from the deployer, **old** policy | file with only `deployerProfile: baas-admin` | stops: "Cannot assume …baas-development-role-operator from profile baas-admin (aws.deployerProfile) … not authorized to perform: sts:AssumeRole"; nothing else called |

The last row is the expected state until the rebuilt deployment's policy (with
`AssumeOwnOperatorRole`) is attached — task 10.1 checks the fallback succeeding.

## Live combination tests (2026-10-12)

| # | Who | What | Result |
|---|---|---|---|
| T10 | CI (OIDC → operator role, no file, no sync) | PR #86's `e2e-cloud-test.yml`, run 38312732086, against `baas-381492019823` | both jobs green: `Benchmark on EC2` launched `i-0cde401178d1963fc` detached and followed it to `completed`; `JCStress on EC2` launched `i-0fc90096652e92163` attached, `completed`. Closes task 8.1 |
| T1 | Deployer (`baas-admin`, reactor JAR) | `--deployment baas-development admin deployment teardown --yes` on the old-format file | stack, bucket, table and image retired; only `baas-development.yaml` removed; `baas-381492019823.yaml` byte-identical (sha1 checked); the old file's `prefix` read silently, `runner` warned as unknown |

| T2 | Deployer | `--deployment baas-development admin deployment setup --region us-east-1 --deployer-aws-profile baas-admin` (no file, the user's grant attached) | stack created; closing text says the deployer's credentials already operate the role and gives the teammate's `config sync --operator-aws-profile`; file holds only `deployerProfile: baas-admin` and `region: us-east-1` |
| T4 | Teammate (`lynx`, IAM user), fresh home | `config sync --operator-aws-profile lynx` with `AWS_REGION=eu-central-1`, then `jobs list` | assumed the role (path C), found `us-east-1`, file holds only `operatorProfile: lynx` and `region`; `jobs list` reads the new table ("No jobs found.") |
| T6 | Another deployment's operator role as the source | `config sync --operator-aws-profile baas-operator-381492019823` for `baas-development`, fresh home | refused (path B) naming both deployments; no file, no directory written |
| T3 | Deployer | `admin image build` on the rebuilt deployment | image 1.3.0 published as `ami-02ecb592f67189e65` to `/baas-development/runner/ami-id`, ~13 min |
| T5 | Deployer as operator (no `operatorProfile`; the fallback, path C) | attached `run jmh` (`Incrementing_Synchronized -f 1 -wi 1 -i 1 -r 1s`), job `20261012T220953183Z-f400626f` | `completed`, exit 0: every call made as `baas-development-role-operator`, assumed from `baas-admin` |
| T8 | CI-style: no file, `AWS_PROFILE` = an operator-role session for `baas-development`, `AWS_REGION=eu-central-1` | detached `run jcstress -- --mode sanity`, then `jobs show` every 15 s | name taken from the role, region `us-east-1` found from the bucket; `launched` → `completed`; nothing written to the home |
| T9 | Mixed | teammate (`lynx`) reads the deployer's T5 result (`results query --job-id`) and CI's T8 job (`jobs show`); CI-style session launches a detached 10-minute job; teammate `jobs terminate --yes`; deployer and CI-style session `jobs show` it | all reads succeed across identities; terminate stopped the instance; both other identities see `cancelled`. (Without `--yes` and no terminal, terminate refused and changed nothing — existing behaviour) |
| T7 | Deployer as operator (fallback, path C), attached | `run --timeout 5400 jmh -- Incrementing_Synchronized -f 1 -wi 0 -i 1 -r 3900s`, job `20261012T220926912Z-7b2cd4a7`, 22:09:21 → 23:15:29 UTC | `completed`, exit 0, 1 measurement stored. The operator session (STS default, one hour, issued ~22:09) outlived by six minutes: 257 polls with no credential error and the result query after the hour succeeded, so the provider renewed mid-run. Instance terminated 23:15:16 UTC by itself on completion (`completed` ends itself), ~30 min before its watchdog bound — neither the watchdog nor the CLI had to act |

Ten tests in all (T1–T10). The rebuilt `baas-development` is kept as the development deployment, so
task 10.1's closing teardown was not repeated — T1 exercised teardown on the old deployment instead.
No chained (SSO) source exists on this machine, so T7 renews an IAM-user-sourced session; the
renewal mechanism is the same (1.2), the one-hour chained cap itself was not reproduced.


## /opsx:verify (2026-10-13)

32/33 tasks complete. The open one, 8.2 (delete the `CORE_STACK_NAME` variable), is post-merge by
design and stays open until `next-release` reaches `main`.

| Requirement (delta) | Code | Test / evidence | Gap |
|---|---|---|---|
| cli: Resource names derived or resolved, not cached | `BaasConfig` (name `@JsonIgnore`, field-only serialisation) | `BaasConfigYamlTest.theNameIsNeverWritten`, `namesTheCompositionRuleFixesAreNotStored`; T2/T4 file contents | — |
| cli: Each deployment has one file, named by the deployment | `ConfigService.readDeployment` | `ConfigServiceTest.aSavedFileDoesNotRecordTheName`, `aCopiedFileTakesItsNewName`; `TeardownConfigFileTest`; T1 | — |
| cli: The region resolves from the file, then the environment | `OperatorSession.region`, `ConfigSyncSubcommand` | `OperatorSessionTest.theFilesRegionIsUsedWithNoLookup`, `withNoFileTheRegionIsTheBucketsNotTheEnvironments`, `withNoFileAMissingBucketIsNoDeployment`; T8 | — |
| cli: Named by `--deployment`, or by its operator role when none is configured | `OperatorSession.config` | `OperatorSessionTest.withNothingConfigured*`, `withADeploymentConfiguredTheRoleSelectsNothing`; `ConfigServiceTest` selection tests; T8, T10 | W1 (fixed) |
| cli: A configuration file never records the CLI's own defaults | `BaasConfig` nullable fields + `EmptySection` filter | `BaasConfigYamlTest.aConfigurationWithNothingSetWritesNothing`, `anUnsetPreferenceReadsAsTheDefault`, `onlyASetValueIsWritten`; `ConfigShowSubcommandTest.marksDefaults…` | — |
| cli: REMOVED flat migration, copied-file refusal, old naming rule | `ConfigService` | `ConfigServiceTest.aStrayFlatFileIsNeitherReadNorRemoved` | — |
| core: Operator identity (setup's closing text) | `SetupCommand.nextSteps` | `SetupCommandTest.anUpdateDoesNotClaimTheOperatorRoleWasJustCreated`; T2 output | — |
| core: Day-to-day commands resolve operator credentials | `OperatorSession`, `AwsClientFactory.deferred`, all `jobs`/`results`/`run` commands | `OperatorSessionTest` (A/B/C, `aFailedAssumptionStopsBeforeAnyClientIsBuilt`, source precedence); live A/B/C (read-only table), T5, T7 | W3 |
| core: Operators bootstrap config without the deployer's machine | `ConfigSyncSubcommand` | `ConfigSyncSubcommandTest` (incl. `theOperatorProfileIsStoredOnceItWorks`, `credentialsThatCannotAssumeTheRoleWriteNothing`); T4, T6 | — |
| core: The deployer may assume its own operator role | `infra/deployer-policy.json` `AssumeOwnOperatorRole` | `DeployerPolicyTest.theDeployerMayAssumeOnlyItsOwnOperatorRole`, budget test; T5 | — |
| ci: Self-test names project/branch/commit (no config file) | `e2e-cloud-test.yml` | T10 (run 38312732086) | — |
| ci: CI names its deployment only through the operator role | `e2e-cloud-test.yml` | grep (no `config sync`, `--deployment`, `CORE_STACK_NAME`); T10 | — |
| runner-jar: Source repository is a property of the release build | `baas-build.properties`, `BaasVersion.sourceRepo`, `release.yml` | `RunnerJarResolverTest.theBuildNamesTheUpstreamRepositoryByDefault`, `theRejectionNamesTheBuildPropertyNotAConfigurationKey`; filtering and release-config simulation (7.1, 7.3) | W2 (fixed) |

### Warnings

- **W1 — fixed.** The naming requirement said every command except setup and sync takes the name
  from an operator role; the code does it for the AWS-reaching day-to-day commands only, while
  `config show`/`config set` and the `baas admin` commands still report that nothing is configured —
  the right behaviour for local and deployer commands. The delta spec now says exactly that.
- **W2 — fixed.** design.md described `runner.sourceRepo` as a manifest attribute; it is a filtered
  build property. design.md now records the property and the manifest as the dropped alternative.
- **W3 — open, accepted.** "Admin commands use the deployer as is" has no dedicated test; the four
  admin commands were checked by reading (they never touch `OperatorSession`). A regression would
  show as an admin command refusing with an operator-role error, not as a privilege leak.
- **W4 — open, accepted.** The one-hour *chained* session cap is not reproduced: this machine has only
  IAM-user sources. Renewal itself is proven (1.2, T7).

### Deviations from design or tasks

- 7.1 build property instead of a manifest attribute (W2).
- 10.1's closing teardown not repeated: the rebuilt `baas-development` is kept as the dev deployment;
  T1 exercised teardown on the old one.
- The user attached `AssumeOwnOperatorRole` to `baas-admin-wiktor` as a separate inline policy
  covering both deployments; once each deployment's deployer policy is re-rendered by setup, that
  standalone policy is redundant.

No critical issue apart from the post-merge task 8.2. Ready to archive after the merge and 8.2.
