# Design

## Context

See proposal.md — Why. The current shape, as of `next-release` at `b705a64`:

- `BaasConfig` serialises every field, defaults included, so each `~/.baas/deployments/<name>.yaml`
  holds `prefix`, `aws.region`, both profiles, the three `ec2` values, `runner.sourceRepo` and
  `git.resolveProject`, whether or not anyone chose them.
- `AwsClientFactory(region, profile)` builds every client from one region and an optional
  `ProfileCredentialsProvider`. Running as the operator is delegated entirely to `~/.aws`: a profile
  with `role_arn` + `source_profile`, named by `aws.operatorProfile`.
- `aws.region` is written by setup and by `config sync` (which finds it with `S3UploadService
  .bucketRegion`); every other command reads it, falling back to `AWS_REGION` and then
  `eu-central-1`.
- `infra/deployer-policy.json` grants no `sts:AssumeRole`. On this machine the operator profiles'
  `source_profile` is a third profile, not the deployer's.
- `RunnerJarResolver` takes the source repository from configuration; `BaasVersion` takes the version
  from the shaded JAR's `Implementation-Version`, set by the release's `versions:set`.

The exploration that produced this change walked every field, the CI inputs and the credential
paths; its conclusions are the decisions below.

## Goals / Non-Goals

**Goals:**
- Every value in a configuration file is one a person chose, or the region — the one fact about AWS
  that cannot be derived without a call and never changes; everything else is derived or resolved.
- A machine holding an operator-role session needs no file at all.
- The narrowing to the operator role is done by the CLI and cannot be skipped by configuration.

**Non-Goals:**
- A default deployment among several, or any other selector besides `--deployment` (ADR 0006 stands).
- Shared, admin-owned job defaults.
- Cross-account deployments (credentials in one account, deployment in another).
- Changing what `baas admin` commands run as.

## Decisions

### The operator role is resolved by the CLI, from any source credentials

A single resolver runs before every non-admin command's first deployment-scoped call:

```
 source = operatorProfile ?: deployerProfile ?: default chain
 id     = sts:GetCallerIdentity(source)

 id is assumed-role/<name>-role-operator/…      → A  use source as is
 id is assumed-role/<other>-role-operator/…     → B  refuse: "<other>'s operator role, not <name>'s"
 anything else                                   → C  StsAssumeRoleCredentialsProvider(
                                                         arn:aws:iam::<id.account>:role/<name>-role-operator,
                                                         session "baas-cli", asyncCredentialUpdate)
 C fails                                         → stop; nothing further is called with `source`
```

A role is recognised as an operator role by its name ending `-role-operator`
(`DeploymentNames.OPERATOR_ROLE_SUFFIX`), stripped to give the deployment name. A non-BaaS role that
happens to end that way names a deployment whose bucket does not exist, which the region lookup then
reports — the failure is loud.

*Alternatives:* keep delegating to `~/.aws` `role_arn` profiles (works, but every machine needs a
hand-written profile and the narrowing depends on that profile being right); a global
`--aws-profile` flag (rejected by the user: typed on every command).

### The operator source falls back to the deployer profile, and the invariant moves from configuration to construction

The old invariant forbade this fallback because it used to hand day-to-day commands the deployer's
rights. Under the resolver the deployer's credentials are only ever the input to an `AssumeRole`; the
command's calls are made as the operator role, so the hazard the rule guarded is gone by
construction. The one way to bring it back is an error path that continues on the source
credentials — which is why *a failed assumption stops the command* is a requirement with its own
test, not an implementation detail.

The fallback also closes E3: the admin's machine needs no operator profile at all, so a teardown that
deletes the file loses nothing that a re-setup does not restore.

*Alternative:* require the admin to set `operatorProfile` explicitly too — one more step on the one
machine guaranteed to hold credentials that can assume the role.

### The deployer policy gains one prefix-exact `sts:AssumeRole`

Without it the fallback fails for a deployer whose only rights are the rendered policy. The statement
names `arn:aws:iam::${ACCOUNT_ID}:role/${PREFIX}-role-operator` and nothing else. It grants nothing
new in substance: the deployer can already rewrite that role's trust policy (accepted risk *Deployer
privilege*). Size: ~110 non-whitespace characters on 4105, against the test's 4608.

### A deployment is named by the operator role only when nothing else names one

The resolution order is unchanged — `--deployment`, else the only configured file, else the ambiguity
error — with one new last step: when *no* deployment is configured and the session is an operator
role, its name is used. With files present the role never selects; it can only be refused by B. So
the laptop rules of ADR 0006 are untouched, and the one new path is exactly CI's.

This amends ADR 0006 ("nothing derives a name from the caller"). A10's harm was a name that moved
when a human's SSO permission set changed. An operator role's name is created by the stack, cannot
move without a new deployment, and can only name the deployment that role is already scoped to. A
wrong role therefore cannot bind CI to a wrong deployment — B now catches the mismatch that today
surfaces as the first `AccessDenied`.

`config sync` is deliberately excluded and still requires the name: it writes a file that binds a
machine, and that binding should be typed.

### The region stays in the file; only a machine with no file looks it up

The region is unlike the other values this change removes. `prefix` is free to read from the filename
and `sourceRepo` from the build, so storing them only duplicated something. The region can only be
learned with an AWS call, and it never changes while the deployment exists — it moves only through a
teardown and a rebuild elsewhere, after which ADR 0002 already has every machine re-run `config sync`.
Storing it remembers an answer rather than copying a source.

So setup records the region it deployed to and `config sync` the region it found, exactly as today,
and every command with a file reads it from there. A machine with no file — CI on the operator role,
path A — runs `S3UploadService.bucketRegion` (`HeadBucket` → `x-amz-bucket-region`, under the
`s3:ListBucket` the operator holds) once per invocation, starting from `AWS_REGION` else
`eu-central-1`. That is the step `config sync` used to perform for CI, moved inline; CI still follows
the deployment rather than its `AWS_REGION`. Setup's existing refusal of a bucket reachable in another
region (`SetupCommand.java:161-169`) is unchanged, and a re-setup with no `--region` keeps working
because the file supplies it.

*Alternative rejected:* look the region up on every command and store it nowhere. It was the first
draft of this change; it bought nothing — the stored value cannot go stale while the deployment
exists — and cost a `HeadBucket` per command (two when `AWS_REGION` differs from the bucket's region,
as for `baas-development` in `us-east-1` here), plus a change to setup's default region on update so
that the existing cross-region check would not refuse a plain re-setup.

### The file stores only what was set, and the name is the filename

`BaasConfig`'s preference fields become nullable with defaults applied by their getters, and the
mapper writes non-null values only. `prefix` and `runner.sourceRepo` leave the class; `aws.region` is
written as before. The deployment name lives on the in-memory object, set by `ConfigService` from the
filename, so the refusal of a file whose `prefix` disagrees with its name has nothing left to check
and is deleted.

No migration. The only deployments are test deployments, torn down and recreated with this CLI, and
`~/.baas/deployments/` is deleted before the first setup — so no file holds a frozen default, a
`prefix` or a `sourceRepo` to shed. An unknown key in a stray old file is ignored, as it already is
(`FAIL_ON_UNKNOWN_PROPERTIES` off). The flat-`config.yaml` migration from `multiple-deployments` is
removed with it: no machine holds a flat file any more, and a stray one is simply never read — the same
outcome the migration already gave a flat file with no `prefix`.

Shipped as a feature release (`feat`, no `!`): nothing is migrated because nothing needs to be, and
the only users are the test deployments being rebuilt.

*Alternatives:* SSM `/<name>/config/*` for job defaults (rejected: a default is not a limit — `--timeout`
overrides it anyway — and `instanceType` is a result tag, so mixing is visible; it would cost an
operator-policy grant, an admin verb and a call per command); a separate machine-wide preferences file
for `git.resolveProject` (rejected by the user: a second file kind for one key).

### `runner.sourceRepo` is a build property of the CLI

`baas-cli`'s Maven-filtered `baas-build.properties` gains `runner.sourceRepo=${baas.sourceRepo}`, with
the property defaulting to `wsztajerowski/benchmark-as-a-service` in the parent POM, and
`BaasVersion.sourceRepo()` reads it. `release.yml` passes `-Dbaas.sourceRepo=$GITHUB_REPOSITORY` to both
`prepareCmd`'s `package` and `publishCmd`'s `deploy` (which re-packages); bash expands the variable when
it writes `release.config.js`, so `@semantic-release/exec` never sees it as a template. A reactor build
carries the default but never reads it: with no released version `baas run` refuses without
`--runner-jar`, and with it the resolver is skipped. This closes U8 by removing the setting rather than
adding a `config set` option.

*Alternative, first planned:* a `Baas-Source-Repo` manifest attribute. Dropped at implementation — the
filtered properties file already exists for `RunnerImageRenderer`, reads the same in tests and in the
shaded JAR, and needs no manifest lookup that could pick another JAR's manifest off the classpath.

### `config sync` verifies and writes; it copies nothing

Sync resolves credentials exactly as any operator command (so `--operator-aws-profile` is honoured for
the check itself), finds the region from the bucket, checks the stack exists there (the existing
`DescribeStacks`), and only then writes the file — the region, and `aws.operatorProfile` if given,
otherwise preserving what the file held. Any failure writes nothing.

### Comparability is unaffected

Nothing here reaches user-data, the runner, the image or a stored measurement. A job launched after
this change is provisioned, tagged and measured exactly as before; only how the CLI obtains its own
credentials and region differs.

## Risks / Trade-offs

- [A chained session is capped at one hour] → `StsAssumeRoleCredentialsProvider` with
  `asyncCredentialUpdateEnabled` re-assumes before expiry for as long as the source is valid; the
  request does not ask for more than 3600 s, which STS refuses on a chained session. Verified by task
  1.2 before implementation. CI is unaffected: it is path A, on its 9000 s OIDC session.
- [A long-running source expires (SSO token)] → same as today with a `role_arn` profile: the next
  refresh fails. The shutdown hook's terminate then fails too, leaving the watchdog — unchanged from
  the current exposure, and `--detach` avoids it.
- [Extra calls per command] → `GetCallerIdentity` is new on every operator command; `AssumeRole`
  replaces the one a `role_arn` profile already makes in every process (the SDK shares no cache with
  the AWS CLI, finding U14); `HeadBucket` only without a file. Unbilled, resolved once per process.
- [A misnamed non-BaaS role ending `-role-operator`] → resolves to a name with no bucket, refused by
  the region lookup naming it.
- [A file copied to another deployment's name] → it carries the original's region, so commands aim at
  a region where the named stack does not exist and fail loudly (stack not found). It carries no
  name, so it can never aim at the original deployment.
- [An old CLI reading a new file] → it finds no `prefix` and reports that nothing is configured. Not
  mitigated: every machine moves to the new CLI together with the rebuild.
- [The deployer fallback hides that a teammate has no operator profile] → a teammate's machine has no
  deployer profile unless they set one, so they fall to the default chain and C; a failure names the
  role and the source.

## Migration Plan

No migration (decided 2026-10-12): only test deployments exist.

1. Tear down `baas-381492019823` and `baas-development` with the current CLI; delete
   `~/.baas/deployments/`.
2. With this CLI: `admin deployment setup` (the rendered deployer policy now carries the
   `sts:AssumeRole` statement; attach it when setup prints it), then `admin image build`.
3. CI: delete the two `config sync` steps and the `CORE_STACK_NAME` variable in the same PR; point
   `OPERATOR_ROLE_ARN` at the recreated role if its ARN changed (it does not when the name is reused).

Rollback: rebuild with the previous CLI the same way.

## Open Questions

_None._

## Resolved Questions

- **Should the region still be stored?** Yes — decided 2026-10-12 after the first draft dropped it:
  it is set once at setup and cannot change under a live deployment, so a per-command lookup protects
  against nothing.
- **Should job defaults be shared through SSM?** No — explored 2026-10-12; a default is not a limit,
  and the instance type is visible on every result.
- **Does a laptop need `--deployment` on every command now?** No — the per-deployment files and the
  one-configured rule stay; this laptop already needs the flag (or the `baas-dev` alias) because two
  are configured.
- **Is any profile still stored?** Yes — both, as before: the user chose them, and a per-command flag
  was rejected.
- **Does testing an unreleased runner need `sourceRepo`?** No — `--runner-jar` bypasses the resolver
  (`RunCommand.java:428-444`).
