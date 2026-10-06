# Design

## Context

The exploration ran 2026-10-06/07, in session `0d0d679c` and alongside the `jobs-command` exploration, which recorded the
shared decisions in commit `f567ae7` and later ones. Where things stand:

- `SetupCommand.computePrefix` returns `baas-<accountId>` and nothing else. The spec forbids any
  option that names a deployment ("No option selects a deployment").
- One configuration file, `~/.baas/config.yaml`, and an inherited `--config-path` are the only way to
  address a second deployment. The `-dev` deployment in `infra/README.md` is deployed by hand with
  `aws cloudformation deploy` and reached by swapping files.
- Runner instances carry `project=baas`, `baas-role`, `baas-job-id`
  (`Ec2ProvisioningService.instanceTags`). `listRunningBenchmarkInstances` filters on `baas-role`
  alone, so a same-region second deployment blocks the first one's teardown (U36).
- Names composed from the prefix are rebuilt by hand in four classes (C5).
- **Depends on `jobs-command`**, which provides the `--deployment` global option,
  `baas admin deployment setup | teardown`, removes `--stack-name` / `sync --name`, and makes teardown
  delete everything (the bucket and table lose `Retain`).

Scope split with `jobs-command`. **`jobs-command` owns the grammar**: the `--deployment` option
exists and replaces `--stack-name` and `sync --name`. **This change owns multiplicity**: setup
accepting a non-default name, name validation, per-deployment files and their migration, the
selection rule, removing `--config-path`, the runner tag, and `DeploymentNames`. Until this change,
`--deployment` can only name the single configured deployment.

## Goals / Non-Goals

**Goals:**
- A second deployment in the same account, created and torn down by the CLI with the same checks as
  the default one, and in any region.
- No ambiguity about which deployment a command hits, and no hidden state that decides it.
- A single-deployment user sees no new flag, no new file to manage and no behaviour change.

**Non-Goals:**
- Where the deployer policy lives, or how `deployer-policy --prefix` is spelt. That is parked with
  `jobs-command`. The extra deployment still needs its own prefix-exact policy, attached as
  customer-managed by an identity above the deployer.
- CI against a second deployment, or a throwaway deployment per PR.
- Any change to `RunnerRole`'s termination condition.
- Moving or renaming an existing deployment.

## Decisions

### The deployment name is the prefix, free-form, and the account-derived name is only the default
`--deployment wiktor-dev` creates stack and bucket `wiktor-dev`, table `wiktor-dev-results`, and so on.
There is one name, so there is no alias between a profile name and a prefix to keep consistent.

*Rejected:*
- **A fixed suffix (`baas-<acct>-<word>`).** It keeps the account in every name, but the user chose
  flexibility.
- **A single `--dev` boolean.** Simplest, but it caps the account at one dev deployment.
- **A separate profile name mapped to a prefix.** Two names for one thing.

### Validation is the lowest common denominator of every service the name reaches, and nothing else
The rule is: 3–N characters, `^[a-z][a-z0-9-]*[a-z0-9]$`, no `--`, not starting `aws`, `ssm`,
`sthree-` or `amzn-s3-demo-`, and not ending `-s3alias`. Each part comes from a specific service:

| Part | From |
|---|---|
| Starts with a letter | CloudFormation stack name |
| Lowercase, digits and hyphen; ends alphanumeric; length ≥ 3 | S3 bucket = prefix |
| No `--` | S3's `xn--`, `--ol-s3`, `--x-s3`, `--table-s3` |
| Not `aws…` / `ssm…` | SSM rejects `/aws…` and `/ssm…` hierarchies, which would break `/<prefix>/runner/ami-id` mid-deploy |
| N | 64 minus the longest role suffix `DeploymentNames` composes, today `-role-image-build`, so N = 47 |

It runs only in setup, the one command that creates a name. Everywhere else, an unconfigured name is
reported as an unknown deployment.

*Rejected:*
- **Refusing a `baas-<12 digits>` that names another account.** The user's call: a misleading name is
  their decision, not a defect.

### One file per deployment, no default pointer
Each deployment is `~/.baas/deployments/<name>.yaml`, and its content is today's `BaasConfig`
unchanged. Preferences are duplicated per file, with no inheritance (the AWS CLI does not inherit
either).

*Rejected:*
- **One file with a `profiles:`/`deployments:` map.** `ConfigService` drops unknown keys on save, so
  any older CLI's `config set` would silently delete every non-default deployment. That would happen
  exactly in this change's own workflow, where the main checkout's released CLI and the worktree's
  reactor build share `~/.baas`.
- **`deployments/default.yaml`.** It breaks *name = prefix*, and it would make `default` a reserved
  name.
- **A default pointer in `config.yaml` plus `config use`.** Unnecessary once the selection rule below
  holds. A switch command also invites the forgot-to-switch-back failure: production jobs launched
  on the dev deployment, or the reverse.

### Absent `--deployment` means "the only one", never a guess
With one file, that file is used. With two or more, every command fails listing them, teardown
included. With none, every command fails except setup, which derives `baas-<accountId>`.
`config sync` with none must be named: it is never derived.

This keeps CI's protection: a wrong role or a leftover `AWS_PROFILE` cannot adopt another account's
deployment. It also guards an accidental production teardown, which needs two deployments present,
and that is exactly when the name is required.

*Rejected:*
- **"Absent means `baas-<accountId>`."** Resolving it needs an STS call made with the operator
  credentials stored in the very file not yet chosen.
- **`BAAS_DEPLOYMENT`.** Invisible state that silently changes which deployment a command hits. The
  dev worktree runs its own reactor build through an alias anyway
  (`alias baas-dev='java -jar …/baas-cli.jar --deployment wiktor-dev'`). It can be added later without
  breaking anything.

### `--config-path` is removed, and tests get the root through code
`ConfigService` takes the `~/.baas` root from its constructor. `BaasApp` gets a package-private seam
for the nine test classes that use `--config-path` today. There is no user-facing root override.

*Rejected:*
- **`BAAS_HOME`.** Nobody outside the tests relocates `~/.baas`. An environment variable cannot be set
  in-process, and `DEFAULT_PATH` is a `static final` read of `user.home`, so it would not even serve
  the tests.

### The flat file is migrated on first run, and the newest write wins
Any command that finds `~/.baas/config.yaml` with a `prefix` moves it to `deployments/<prefix>.yaml`,
overwriting an existing one, because the flat file can only be newer, written by an older CLI. It then
deletes `config.yaml`. An older CLI run afterwards finds no file and fails with "No installation is
configured", loudly, never against the wrong deployment.

### Runners carry `baas-deployment=<prefix>`, and every live-runner query filters on it
It is a fourth fixed tag next to `baas-role` and `baas-job-id`, set by the CLI and never by a caller.
So the invariant "the instance carries only fixed tags" holds with one more fixed tag.
`listRunningBenchmarkInstances` adds the filter. `findLive(jobId)` does not need it, because job ids
are unique. `RunnerRole`'s condition stays on `baas-role`. Self-termination and the watchdog are
untouched, and so is the accepted risk *Runners can terminate each other*.

*Rejected:*
- **Refusing a second deployment in a region that already holds one.** It needs a cross-region
  lookup, protects only what setup happens to notice, and forces the dev deployment elsewhere.
- **Scoping `RunnerRole`'s IAM condition per deployment.** That is the declined self-only hardening
  under a new name: a wrong condition silently disables self-termination and the watchdog.

### `DeploymentNames.of(prefix)` is the only place a name is composed (closes C5)
The validation's N is derived from it. Hand-built copies in `RunCommand`, `BuildImageCommand`,
`TeardownCommand` and `DeployerPreflight` move onto it, and `BaasConfig`'s derivations delegate to it.

### CI stays on the default deployment; infra changes are verified on a second one
CI runs the PR's reactor CLI and runner against the default deployment's stack, IAM, AMI and table.
That leaves a known gap: a PR changing `infra/`, IAM or `runner-image.yaml` passes CI without
exercising the change. The gap is closed by a project rule in `openspec/config.yaml` (tasks):
such a change carries a manual task to set up a second deployment, `build-image`, `run`, tear it
down, and record the result in `verify.md`.

*Rejected:*
- **A throwaway deployment per PR in CI.** It adds about 15–20 minutes per run and puts deployer
  rights behind an OIDC trust that is already repo-wide (S2).
- **A template-version check in the CLI.** It turns silent passes into loud failures, but forces a
  `setup` after every CLI upgrade. Separate decision.

## Risks / Trade-offs

- **A runner launched by an older CLI is invisible to the scoped teardown gate.** Teardown can delete
  the stack under it. It lasts one benchmark timeout at most, and the watchdog still terminates the
  instance. Accepted.
- **Mixed CLIs on one machine during the transition.** After migration, the released CLI in the main
  checkout fails until it is upgraded. That is loud and recoverable, and preferred to a silent
  wrong-deployment hit.
- **With a dev deployment present, production commands also need `--deployment`.** This is the
  developer's own cost, and it is what makes the ambiguity safe.
- **Free-form names can mislead** (`baas-<other account>`). Accepted by the user.
- **The extra deployment's deployer policy is still a by-hand IAM step** (U21's blocker). This change
  does not remove it.
- **CI writes PR-shaped job items into the default deployment's `JOB` partition.** Pre-existing and
  unchanged; a review-time question when a PR changes item shapes.
- **Comparability:** no runner, image or user-data behaviour changes. The only runner-visible
  difference is one more EC2 tag, which nothing on the instance reads. Existing results stay
  comparable.

## Open Questions

- **The deployer-policy surface for a named deployment** (`deployer-policy --prefix`): parked with
  `jobs-command`. This change must not settle it.
- **`jobs-command`'s acceptance of the scope split above**, in particular that `--config-path` is
  removed here rather than there.

## Resolved Questions

- *Same account, different region: does the region separate deployments?* No. S3 bucket names and
  IAM names are global, so the prefix must differ. The region is optional.
- *Naming the selector:* `deployment`. "profile" collides with `--aws-profile` and `aws.profile`,
  "configuration" with `baas config` and `--config-path`, "environment" with `environment.json`, and
  "instance" with the EC2 runner. Shared with `jobs-command`'s grammar.
- *Should old data be reachable after teardown?* No. Nothing survives a teardown (`jobs-command`), so
  teardown deletes the file and no retired-deployment read path exists.
