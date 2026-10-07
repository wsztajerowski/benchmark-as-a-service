# Proposal

## Why

Developing BaaS itself needs a second, throwaway deployment, somewhere a template change, an IAM edit or
an image bake can be exercised without touching the account's real one. Today that is a by-hand
procedure (`infra/README.md`, *A second deployment*). It deploys the core template directly, so it
skips everything `SetupCommand` does: per-region parent-AMI resolution, rendered image parameters,
the retained-resource pre-checks and the networking guard. It is also reached by swapping
`~/.baas/config.yaml` back and forth. A second deployment in the same region also blocks the first one's
teardown (U36). This is also the deployment the U21/U40 live checks have been waiting for.

Built on `jobs-command` (archived 2026-10-07), which introduced the `--deployment` global option,
`baas admin deployment setup | teardown` and the deployer policy rendered by setup. This change makes
more than one deployment a supported state.

## What Changes

- **`baas admin deployment setup` creates a deployment under any valid name.** The name is the
  prefix, used verbatim. With no name and no local deployment, setup derives `baas-<accountId>` as
  today. **BREAKING (spec):** the "no option selects a deployment" requirement is removed.
- **A deployment name is validated once, at setup, against every service that carries it.** The rule
  is 3–47 characters, `^[a-z][a-z0-9-]*[a-z0-9]$`, no `--`, not starting `aws`, `ssm`, `sthree-` or
  `amzn-s3-demo-`, and not ending `-s3alias`. Nothing else is checked: a name that looks like
  another account's default is the user's call.
- **One configuration file per deployment, `~/.baas/deployments/<name>.yaml`.** There is no default
  pointer and no `config use`. Today's flat `~/.baas/config.yaml` is migrated into it on first run
  and removed. **BREAKING:** after migration, an older CLI reports "No deployment is configured".
- **The deployment is implied when there is exactly one.** With two or more local deployments, every
  command must name one with `--deployment`, and the error lists them. With none, every command fails
  except setup, which derives the name. `baas config sync` is never derived: with no local deployment
  it must be named.
- **`baas config list`**: a local listing of the configured deployments (DEPLOYMENT, REGION,
  OPERATOR PROFILE, DEPLOYER PROFILE, `--format json`). No AWS call. The ambiguity error hints at it.
- **The deployer's credential key and option are named for the deployer. BREAKING.** `aws.profile`
  becomes `aws.deployerProfile`, renamed by the migration, and `--aws-profile` becomes
  `--deployer-profile` on `admin deployment setup` and `config set`, matching `--operator-profile`.
- **Teardown deletes the deployment's configuration file**, the last one included.
- **`--config-path` is removed. BREAKING.** A deployment is addressed by name only. Tests get the
  `~/.baas` root injected through code. No `BAAS_DEPLOYMENT` and no `BAAS_HOME`, deliberately.
- **Runners carry a fourth fixed tag, `baas-deployment=<prefix>`.** Every runner lookup filters on
  it: teardown's in-flight gate, the live-runner query behind `jobs list`, and `jobs terminate`.
- **Composed names come from one place (`DeploymentNames.of(prefix)`).** The 47-character cap is
  computed from the longest of them rather than hard-coded (closes C5).
- **Project rule:** a change touching `infra/`, IAM or the runner image is verified by hand on a
  second deployment, and the result recorded in its `verify.md`. CI's e2e stays on the default
  deployment.
- `infra/README.md`'s by-hand procedure becomes `baas --deployment <name> admin deployment setup`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `core-stack-provisioning`: the account-derived name becomes the default rather than the only name.
  Names are validated, and the composition rule no longer assumes the `baas-` namespace. Sync's naming
  rule is restated for several deployments. Teardown's in-flight gate is scoped to its deployment.
  Setup's options (`--deployer-profile`, no `--prefix`) and the credential-resolution rule use
  `aws.deployerProfile`. Setup's policy step renders for the resolved name, after name validation.
- `cli-command-structure`: the alternative-configuration-file requirement is replaced by per-deployment
  files, and `jobs-command`'s *A deployment is named only by `--deployment`* gains the selection rule.
  `baas admin image build` runs under `aws.deployerProfile`. `baas config list` is added, and `config set` takes
  `--deployer-profile`. The instance's fixed tags gain `baas-deployment`.
- `job-tracking`: runner lookups are scoped to the deployment.
- `runner-image-provisioning`: `baas jobs diff`'s credential scenario names `aws.deployerProfile`.

## Impact

- **Code:** `BaasConfig`, `ConfigService` (layout, migration, root injection), `BaasApp` (selection,
  `--config-path` removal), `SetupCommand` (`computePrefix` → default only, name validation),
  `TeardownCommand` (file deletion, scoped gate), `ConfigSyncSubcommand`, `ConfigSetSubcommand` and
  `ConfigShowSubcommand` (deployer-profile rename), a new `ConfigListSubcommand`, `Ec2ProvisioningService`
  (`instanceTags`, runner filter), the new `DeploymentNames`, and every caller C5 lists. The nine test
  classes that pass `--config-path`. `install-test.yml`'s sentinel check moves to `deployments/`.
- **Docs:** `CLAUDE.md` (the "there is no option to name a different one" invariant, the instance-tag
  invariant, the `--config-path` and `config sync --name` paragraphs, the `aws.operatorProfile`
  no-fallback invariant), `README.md` and `infra/README.md` (including the profile keys), and
  `openspec/config.yaml` (the verify rule). `docs/review/open-findings.md` loses U36 and C5. A new ADR
  records the reversal of the one-deployment-per-account rule.
- **Cost:** no standing cost for the default deployment. Each extra deployment costs what one
  deployment costs, about $0.20/month for its AMI snapshot while it exists, and nothing after teardown.
  The deployments are created deliberately; nothing creates one implicitly.
- **Not changed:**
  - The default deployment's name, stack, bucket, table and IAM.
  - `RunnerRole`'s `ec2:TerminateInstances` condition, which stays on `baas-role`; the "runners can
    terminate each other" accepted risk is unchanged.
  - The deployer-policy step, which `jobs-command` builds into setup. The extra deployment still
    needs its own prefix-exact policy, which setup prints for its name, attached as customer-managed.
  - Comparability with existing results: no runner, image or user-data behaviour changes, apart from
    one more instance tag.
  - The region rule (ADR 0002): each deployment's region is chosen once at setup and found from its
    bucket.
  - Nothing implicitly selects a deployment: no environment variable, no switch command.
  - The operator profile never falls back to the deployer's. The rename changes only the name.
