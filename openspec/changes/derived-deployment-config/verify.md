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
