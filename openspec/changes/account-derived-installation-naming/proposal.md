# Proposal

## Why

`SetupCommand.computePrefix` hashes the raw `sts:GetCallerIdentity` ARN, so an installation is
named after *whoever deployed it*. Under IAM Identity Center that ARN moves whenever the permission
set is switched or re-provisioned, and it differs per human on the same account — and when it
moves, `baas admin setup` does not fail. It deploys a second complete installation beside the
first, with its own bucket, results table and runner AMI, and rewrites `~/.baas/config.yaml` to
point at the empty one. That is finding **A10**, open since the `baas-cli` review.

The deeper problem is that the system outgrew the scheme. Two of the things a prefix now names —
the pinned runner AMI and the results table — are account-level assets that want exactly one
instance. Per-identity installations mean per-identity AMIs baked on different days from the same
`infra/runner-image.yaml`, which silently reintroduces the environment drift the pre-baked image
exists to remove, and split the benchmark history the results table exists to accumulate.

## What Changes

- **BREAKING** The resource prefix becomes `baas-<accountId>`, derived from
  `GetCallerIdentity().account()`. `computePrefix` and `base32Encode`
  are deleted. Existing installations are not renamed — CloudFormation cannot rename a stack — so
  this is a redeploy, covered under *Impact*.
- **BREAKING** All resource names follow one rule, `<prefix>-<resource_type>-<name>`, with the
  type and name segments dropped when nothing needs disambiguating. `baas-` moves inside the prefix
  value, so `ResourceNamePrefix` becomes the whole name stem and the template's three current
  composition shapes collapse to one. Stack and bucket are the bare prefix; the bucket name shape
  is unchanged.
- **New** `baas admin deployer-policy --prefix <prefix>` renders the policy for an installation
  other than the account's own — the by-hand installation a BaaS developer uses for scratch work.
  It prints; it grants and attaches nothing. Replaces `--for-arn`, whose rationale is gone.
- **New** VPC parameters are immutable on update. `--use-existing-vpc` and its three companions are
  honoured on create; on update, values identical to the deployed ones or omitted proceed, and
  differing values are refused before anything is submitted.
- **BREAKING** `baas config sync --core-stack-name <name>` becomes `baas config sync --name
  <prefix>`, and the option is **required** — a machine with no local state must declare which
  installation it is adopting rather than inferring one from whichever credentials are active.
- **New** `--results-table <name>` on `baas results` and `baas download` only. Deliberately not on
  `baas config set`: persisting it would redirect `baas run`'s writes as well.
- **BREAKING** Config keys removed: `benchmark.asyncProfilerVersion` (dead — the version is
  observed on the instance by `asprof --version`; this field is read only by `config show` and
  misreports), `aws.vpcId` (dead — written by setup and sync, read by nothing), and
  `aws.coreStackName` (merged into `prefix`, which is now the same string).
- **BREAKING** Config keys no longer stored, derived instead: `aws.bucket`, `aws.resultsTable`,
  `aws.runnerInstanceProfileName`. Resolved per invocation from `DescribeStacks` instead of cached:
  `aws.subnetId`, `aws.securityGroupId`.
- Stale defaults `prefix = "baas"` and `aws.coreStackName = "baas-main"` are removed; neither names
  anything the current templates produce.
- `baas admin deployer-policy --for-arn` loses its rationale — the rendered policy now depends on
  the account and the installation, not on who is asking.

## Capabilities

### New Capabilities

None. The behaviour changed here is already covered by existing capabilities.

### Modified Capabilities

- `core-stack-provisioning`: the prefix derivation, the absence of any option that selects an
  installation, VPC parameter immutability on update, `config sync`'s required `--name`,
  deployer-policy resource scoping under the new names, and removal of the `aws.coreStackName`
  config field.
- `cli-command-structure`: the results table resolved by derivation rather than from a stored
  config field, the read-only `--results-table` override, and teardown's retention message, which
  currently states that the bucket name comes from a hash of the caller ARN.

## Impact

**Code.** `SetupCommand` (derivation, `--mode`, VPC immutability), `DeployerPolicyCommand`,
`ConfigSyncSubcommand`, `ConfigSetSubcommand`, `ConfigShowSubcommand`, `BaasConfig`,
`TeardownCommand` (messages only), `ResultsCommand` and `DownloadCommand` (`--results-table`),
`RunCommand` (derived names, `DescribeStacks` resolution), `DeployerPreflight`
(`criticalActionsToResources`). Templates: `infra/cf-template-core.yaml` (~40 `ResourceNamePrefix`
sites), `infra/deployer-policy.json`, `infra/operator-policy.json`.

**Permissions.** No new grant is needed. `BaasCliOperatorRole` already holds
`cloudformation:DescribeStacks` on its own stack (`operator-policy.json:103`), which is what the
per-run subnet and security-group resolution uses. The rendered deployer policy measures **4219**
non-whitespace characters for `shared` and **4271** for `dev`, against the 4608 test cap — flat
against today's 4122, because simplifying the Image Builder wildcard to `${PREFIX}-*` pays for the
longer role names. `OperatorPolicyDriftTest` and the policy-size test both need their fixture
prefix updated so they stop measuring an 8-character prefix.

**Cost.** One additional ~15-minute Image Builder run during migration (a single build instance for
that duration — cents). The retired installation's 30 GB AMI snapshot continues to cost ~$0.20/month
until it is deregistered by hand, so the migration overlaps two snapshots rather than adding one
permanently. The retained archive bucket and results table keep costing their storage at rest. One
extra `DescribeStacks` call per `baas run`, which is free at this volume. **No new standing cost
once the old AMI is deregistered.**

**Migration.** `baas-3q7i7s65` is live — it is CI's `CORE_STACK_NAME`, the laptop's configured
installation, and the holder of the AMI pointer, bucket and results table. It cannot be renamed. The
path is: deploy the new installation, bake, repoint `CORE_STACK_NAME`, then tear down the old stack
leaving its bucket and table retained as a read-only archive. Nothing expires them — versioning is
suspended and no lifecycle rule covers `runs/` — so copying the history forward stays available
indefinitely and is deliberately not done here.

**Deliberately not changed.**

- All three termination layers, and the absence of `set -e` in user-data.
- One image, rebuilt in place, pointer repointed before the replaced AMI is deregistered.
- `S3MainBucket` stays `VersioningConfiguration.Status: Suspended` with no lifecycle rule
  expiring current objects under `runs/`.
- `RunnerSecurityGroup`'s `GroupDescription` is untouched, because editing it replaces the security
  group and moves its id.
- No TCP 27017 egress is reintroduced.
- `aws.operatorProfile` still does not fall back to `aws.profile`.
- The EC2 tag key stays `baas-role`.
- Teardown still retains both the bucket and the results table, and still offers no flag to delete
  the table. Deleting a shared installation's data belongs to `export-before-teardown`, gated on a
  verified export; building an interim typed-confirmation gate here would protect nothing, since
  versioning is suspended and there is no server-side recovery.
- `ResultKeys`, `MeasurementItemMapper`, the tag vocabulary and the run layout are untouched, so no
  stored result changes meaning and no measurement becomes incomparable.
- `baas run` still has no fallback when the AMI pointer is absent.

**Findings.**

- Closes **A10** (`docs/review/baas-cli-findings.md`, Med, Open) — but not by the fix that finding
  proposes. Normalising `assumed-role` ARNs to their role ARN would still fork on a permission-set
  switch and would still leave the name underivable; deriving from the account removes both.
- Partly closes **A6** (Low, Open): the stale `aws.coreStackName = "baas-main"` default and the
  stale `prefix = "baas"` default are removed, along with two dead keys. Silent unknown keys and
  the absent schema version stay open.
- Does **not** close **P13** (`docs/review/prebaked-runner-ami-review.md`, Low, Open) — the
  async-profiler install path hardcoded in a third place. Deleting
  `benchmark.asyncProfilerVersion` is adjacent but distinct: P13 concerns the *path*, which is
  still duplicated between `RunnerImageRendererTest`, `UserDataScriptBuilder` and
  `JmhWithAsyncProfilerSubcommand`'s `--async-path` default. It needs its own change.
