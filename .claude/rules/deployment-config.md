---
paths:
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/config/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/session/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/deployment/**"
---

# Deployments: setup, credentials, selection, teardown

How a deployment is created, named, selected, reached and removed.

## Setup

- **Setup checks the stack's fixed names before every create, despite `DeletionPolicy: Delete`.**
  The bucket is named `<prefix>` and the table `<prefix>-results`, so a taken name fails the whole
  create into `ROLLBACK_COMPLETE`. Leftovers still happen: a pre-`Delete` teardown, a bucket made by
  hand, or another account owning the globally unique bucket name. Setup names the leftover and the
  command that removes it; it never deletes one. `BucketProbe` asks a redirect again in the region
  it names, because from elsewhere another account's bucket answers 301 like your own, and only its
  own region answers 403. A stack in `ROLLBACK_COMPLETE` holds nothing, so setup deletes and
  recreates it rather than pointing at teardown. A file may exist for it: on create, setup writes
  the deployment's file as soon as CloudFormation accepts `CreateStack`, before the stack is
  complete, so an interrupted create — Ctrl+C, a closed lid — never leaves a stack no file names.
  Teardown resolves a deployment only through its file, so without one a re-run under another
  name built a second deployment and left the first out of reach (`interactive-setup` D20).
- **Setup is a walkthrough that records no progress; each step re-checks AWS.** On a terminal it
  asks and waits at a step the user must do outside `baas`; unattended (`--non-interactive`, a
  redirect, CI) the same steps exit 1 there. A waiting step is re-checked only when the user presses
  Enter, never on a timer: the permission check is 15 `SimulatePrincipalPolicy` calls and IAM
  throttles that API hard enough to turn the check off. Every prompt has an option that answers it,
  which the repeat-command round-trip test pins. The deployer profile is stored only on evidence it
  can deploy — a met check, or a stack operation that changed something — because a no-op re-run
  under an identity that may not simulate "succeeds" while proving nothing. All of setup's AWS calls
  go through `SetupAws`, faked by hand in tests.

## Credentials

- **Day-to-day commands always run as `<name>-role-operator`; any credentials, the deployer's
  included, are only the source of that assumption, and a failed assumption stops the command.**
  `OperatorSession` takes the source from `aws.operatorProfile`, else `aws.deployerProfile`, else the
  default chain, identifies the caller, and then uses an operator-role session for this deployment as
  is, refuses another deployment's operator role, or assumes the role itself. The deployer fallback
  is safe only because nothing but `GetCallerIdentity` and `AssumeRole` is ever called with the
  source — so never let an error path carry on with it (`aFailedAssumptionStopsBeforeAnyClientIsBuilt`
  pins that). It replaced *operatorProfile must not fall back to deployerProfile*, which guarded the
  same rights when the profile *was* the identity. `baas admin` uses the deployer source as is and
  never assumes the operator role; the deployer policy grants `sts:AssumeRole` on its own operator
  role only, so an admin needs no operator setup at all.

## Deployment files, selection and region

- **Each deployment is one file, `~/.baas/deployments/<name>.yaml`, storing credential *profile
  names*, preferences and the region — each preference only once set, and no secret.** The name is
  the file's name and is not written inside it, so a copy under another name configures that other
  name and can never aim at the original. Defaults are supplied when read, never written, so a
  changed default reaches every machine. The region is the one fact about AWS kept: it cannot be
  derived without a call and does not change while the deployment exists. `runner.sourceRepo` is
  gone — the build carries it (`baas.sourceRepo`, set by the release to the releasing repository).
  The credentials themselves stay in `~/.aws`. One file per deployment, not one file with a map: an
  older CLI's save drops keys it does not know, so a map would vanish the first time an older CLI
  ran `config set`. There is no migration — not of the flat `config.yaml` of earlier releases, nor
  of files carrying a `prefix`: the test deployments were rebuilt (`derived-deployment-config`), and
  a stray old key is warned about and ignored. `~/.baas` is not
  relocatable — no option, no environment variable; tests pass a root to `new BaasApp(root)`, and
  surefire gives unit tests their own `user.home` so none can touch a developer's real `~/.baas`.
  The bucket, results table and runner instance profile are *derived* from the prefix; the runner
  subnet and security group are *resolved* from the stack's outputs on every job. Neither kind is
  cached, because a stored name can point at one deployment while `prefix` names another, and a
  stored security-group id outlives the group when `GroupDescription` forces a replacement — the
  failure that rule previously only documented.
- **Absent `--deployment` means "the only one", never a guess.** With exactly one deployment
  configured a command uses it; with two or more every command — teardown included — refuses,
  listing them and hinting `baas config list`; with none, setup derives `baas-<accountId>` and a
  day-to-day command takes the name from an operator-role session (`<name>-role-operator`, the CI
  path) and otherwise refuses. That role is created by the stack, cannot move, and can only operate
  the deployment it names, which is why it does not reopen A10 (ADR 0006's amendment); with any file
  present it never selects, it can only be refused. Nothing else selects a deployment: no `BAAS_DEPLOYMENT`, no stored default, no
  `config use` — each is invisible state that changes what a command hits. A developer with a
  second deployment reaches it through an alias that carries the flag. `--config-path` is gone for
  the same reason. Teardown deletes the deployment's file, the last one included.
- **`baas config sync` is never derived, though the prefix is derivable.** With nothing configured it
  must be named: a bare sync on a machine with no local state would adopt whatever deployment the
  active credentials imply — in CI, a wrong role or a leftover `AWS_PROFILE` binds the machine to
  another account's deployment and fails later, after provisioning. With exactly one configured, a
  bare sync re-verifies that one. Setup derives and prints; sync adopts what it is told, proving
  first that the credentials reach the operator role (`--operator-aws-profile` is checked, then stored). CI does not sync at all. A deployment
  is named only by the global `--deployment`, never by a per-command `--results-table` or
  `--bucket`, which are gone: an override of one resource could aim a command at one deployment's
  table while its configuration named another.
- **The region is chosen once, by `baas admin deployment setup --region`, and never typed again.** `config sync`
  finds it: the bucket carries the prefix's name, bucket names are global, and `HeadBucket` from any
  region answers a wrong-region request with 301/400 carrying `x-amz-bucket-region` (a 404 is no
  bucket) — under the `s3:ListBucket` the operator already holds. Setup and sync store the region;
  a machine with no file (CI) looks it up once per invocation, so a CI job follows the deployment
  rather than its `AWS_REGION`, whose only job is the credential exchange. There is no
  `config set --region`: set by hand it aimed a machine at a region with no deployment, and `run`
  then advised building an image there. Moving a deployment is a rebuild in the new region, after
  which every machine re-runs the same `config sync --deployment`.

## CLI version floor

- **A deployment records the release that last ran setup on it, and every command that reaches AWS
  refuses a CLI older than that — patch level included.** The record is the stack parameter
  `BaasCliVersion`, written only by setup and only from a released `x.y.z` build; an image build
  carries it forward, and a reactor build's setup sends nothing, so the value stays. The check lives
  in the two sessions, not in commands: `OperatorSession.factory()` runs it when the first client is
  built, `DeployerSession.factory(config)` before it returns, setup in its names step — so a new
  command is covered by construction. `jobs terminate` alone takes `uncheckedFactory()` (stopping a
  paid instance never waits on an install), and `VersionCheckCoverageTest` pins that, and that no
  client is built outside a session. A newer CLI runs with one stderr line; an unreleased build
  runs unchecked and says so. **The parameter's conditional output looks unused and is not:**
  CloudFormation answers "No updates are to be performed" to a change of a parameter nothing
  references and stores nothing, so without it every raise would silently fail to record (verified
  live, `cli-version-floor` `verify.md` 1.1). CLIs released before the check ignore the parameter.

## Teardown, export and import

- **Nothing survives a teardown.** The bucket and the results table are `DeletionPolicy: Delete`, so
  data leaves a deployment only through `baas admin deployment export`, never by being retained.
  Teardown says so before its prompt and empties the bucket first (CloudFormation will not delete a
  non-empty one).
- **Teardown offers the export first, and saves nothing itself.** On a terminal the offer comes after
  the in-flight gate — the export then sees a quiet table, the one case where a scan is as good as a
  snapshot — and before the typed name, so a failed export never leaves a confirmed teardown
  half-done. `--yes` asks nothing, so it offers nothing and exports nothing: unattended, run
  `admin deployment export` first. Teardown used to save the extension unasked; that copy now lives
  only in a bundle. A teardown without `--yes` was tried and reverted the same day (2026-10-23): an
  agent has no terminal, and every way of faking one is refused, so only a person could tear down.
- **A bundle is the wire shape, and import never overwrites.** `results.jsonl` is every item in
  DynamoDB's typed JSON, decoded through no mapper, so an attribute the model does not know survives
  and `aws dynamodb put-item` can read any line. `manifest.json` is written last: a directory
  without one is an export that did not finish. Import writes each item under
  `attribute_not_exists(pk)` and each object under `If-None-Match: *`, has no overwrite mode, and
  uploads only keys under `jobs/` — never `releases/`, which stays write-once. It targets whatever
  `--deployment` resolves to, never the deployment the manifest names; that works because every
  stored pointer is a bucket key, never an `s3://` URI.
