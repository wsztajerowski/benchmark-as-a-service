# Design

## Context

See proposal.md — Why. The decisions below were taken one at a time in the explore session of
2026-10-23; this records how they land in the code.

What the code offers today:

- **Two choke points for credentials.** Every command outside `admin` builds its clients through
  `OperatorSession.factory()` — a *deferred* `AwsClientFactory` that identifies the caller and
  assumes the operator role only when the first client is built, so a command that fails earlier,
  or a test that stands its AWS step in, makes no call. Every admin command except setup builds
  them through `DeployerSession.factory(config)`; setup's `AwsSetupAws` uses the
  `factory(region, profile)` overload, because it runs before the deployment's file exists.
- **The operator already reads the stack.** `cloudformation:DescribeStacks` on its own stack is
  granted (`CloudFormationReadOwnStack`), and `baas run` calls it on every job for the subnet and
  security group. The deployer policy grants it too.
- **`CloudFormationService.updateStackParameters`** sends a deployed, still-declared parameter with
  `UsePreviousValue` unless the caller names it. A declared parameter the deployed stack lacks and
  the caller does not name is not sent at all, so CloudFormation gives it the template's default.
- **`BaasVersion.current()`** reads `Implementation-Version`; a reactor build reports the placeholder
  `0.0.0-semantically-released`, and `isReleased()` is false for it.
- **A numeric comparison exists** — `RunnerImageParameters.compareVersions` — but in the `image`
  slice, which the `session` kernel may not import (`SliceBoundaryTest`).

## Goals / Non-Goals

**Goals:**
- One check, applied by construction to every command that builds clients through a session, with
  one exemption that is visible in the code.
- The check adds no failure mode of its own: anything it cannot decide proceeds.

**Non-Goals:**
- Protecting against CLIs released before this change — they do not know the parameter exists.
- Comparing unreleased builds (`git describe` versions were explored and dropped: every case they
  would catch is a developer's own deployment, and that edge already fails safe).
- A `baas update` command or any self-update.
- Telling a user that GitHub has a newer release than the one the deployment records.

## Decisions

### The recorded version is a stack parameter with a strict pattern and an empty default

`BaasCliVersion`, `Type: String`, `Default: ""`, `AllowedPattern: '^$|^[0-9]+\.[0-9]+\.[0-9]+$'`,
referenced by one conditional output of the same name (`Condition: RecordsCliVersion`, the value
non-empty). The output is not read; it exists because CloudFormation refuses an update whose only
change is the value of a parameter nothing references — "No updates are to be performed", and the
value is not stored, not even when the parameter is new (task 1.1, live on `baas-development`). With
the output, a value-only change is an update, an omitted parameter is carried forward, and empty
drops the output (an output may not be empty). The CLI reads the parameter. `DescribeStacks` already
returns parameters, so readers need no new call and no new grant. The pattern means the recorded value is always comparable — there is no "garbage on the
stack" branch to design — and the empty default is what lets every other update leave it alone: an
image build neither names it nor needs to know it exists, and on a stack that predates it the
template default fills it with empty.

*Rejected:* a stack **tag** propagates to every taggable resource, so changing it would churn the
whole stack. An **SSM parameter** or a **results-table item** needs a new grant for both roles and a
second call on `run`. A **reference from a resource** (a tag, or a `WaitConditionHandle`'s metadata)
would also register the change, but as a resource update on every raise; the output touches no
resource. A **parameter alone** was the first design and does not work (above).

### The check runs where credentials are resolved, once per session

`session.CliVersionCheck` (kernel; imports `aws` and `cli` only) takes the CLI's version, the
deployment name, a role (`OPERATOR` or `ADMIN`, which only picks the hint's wording) and a lookup of
the recorded version, and returns one of: proceed silently, proceed with the newer hint, proceed with
the unreleased warning, or throw a `CommandRefusal.refused` (exit 1) carrying the installer command.
Unreleased or non-`x.y.z` builds return before the lookup, so a reactor build makes no extra call.

- **Operator side:** `OperatorSession.factory()` passes `AwsClientFactory.deferred` a credentials
  supplier that resolves the operator credentials, then runs the check with them, once per session
  (a flag on the session, since a command may call `factory()` more than once). The region supplier
  is memoised and shared with the check, so the fileless path does not look the bucket region up
  twice. The lookup goes through `OperatorSession.Aws` — a new `recordedCliVersion(credentials,
  region, stack)` — so `OperatorSessionTest`'s fake stands it in like every other call.
- **Admin side:** `DeployerSession.factory(config)` runs the check **eagerly**, before it returns the
  factory. Admin commands have no deferred-resolution requirement, and their tests replace the
  `awsFor` function that calls `factory(config)`, so tests still make no call. Eager also means the
  check happens at a command's start, outside any `try`.
- **Exemption:** `OperatorSession.uncheckedFactory()`, used by `JobsTerminateSubcommand` alone. A
  source-reading test (in the style of `SliceBoundaryTest`) pins that it has exactly that one caller.
- **Setup:** the `factory(region, profile)` overload stays unchecked; setup checks as a step (below).

*Rejected:* a call at the top of each command — a command that forgets it is silently unchecked,
which is the failure this design exists to remove. A picocli execution-strategy hook before the
command runs — it would have to resolve the deployment and assume the operator role a second time,
an extra STS round trip on every command, or restructure how commands get their sessions.

### The operator check fires at the first client, so the commands' broad catches are audited

Deferred resolution means the refusal is thrown from wherever a command builds its first client.
A `catch (RuntimeException e)` around that first use could reword or, worse, swallow it. Apply
audits every broad catch an operator command can reach before its first client (`JobLaunch`,
`JobsConnectSubcommand`, `JobSession`, `ConfigSyncSubcommand`, `JobStop`) and lets a
`CommandRefusal` pass through unchanged where one is caught. A catch that ends the command with
exit 1 anyway is safe but loses the message, so it is fixed too.

### Setup checks in the names step, and records in the stack step

The names step already learns whether the stack is present. When it is, it reads
`SetupAws.stackParameters` — the same read the GitHub step makes — and runs the same
`CliVersionCheck` with a lookup over that map. An older CLI ends the walkthrough with
`Outcome.Stop(1, …)`: not a `Wait`, because the process that would wait is the CLI that has to be
replaced. The newer hint is not printed by setup — setup is about to record the newer version, and
announces it afterwards. The recorded value is kept on `SetupContext` for the stack step.

The stack step adds `BaasCliVersion = BaasVersion.current()` to its parameters when the build is a
released `x.y.z`, on create and update; otherwise it adds nothing, so a create records empty and an
update carries the deployed value forward. After an update that changed the value it logs the
announcement at `warn` — it changes what teammates can do — naming the old value or "none recorded".
A create announces nothing: there are no teammates yet.

*Rejected:* a new setup step — the guided-setup spec pins the step order, and the check needs nothing
the names step does not already have.

### The comparison lives in the kernel and the image slice delegates to it

`BaasVersion.compare(a, b)` — numeric, field by field — and `BaasVersion.comparable(v)` (`x.y.z`
only). `RunnerImageParameters.compareVersions` becomes a call to it, so one ordering governs both
the base-version refusal and the CLI one. A released version that is not `x.y.z` — semantic-release
pre-release channels, which this repository does not configure — counts as unreleased: never
compared, never submitted, so it cannot trip the stack's `AllowedPattern`.

### The installer drops the update mode and the stored copy, and keeps the probe seam

`install.sh` loses `--update`, `store_installer`, `installed_version`, `latest_tag`,
`version_newer`, the `BAAS_LATEST_TAG` and `BAAS_INSTALLED_VERSION` seams, and the usage line. `--update`
then fails as an unknown option. `BAAS_PROBE` stays: `install-test.sh` still sources the script for
`sha256_of` and `verify`. `--uninstall` keeps deleting `$BAAS_SHARE/install.sh`, now described as
an earlier release's copy. The install-mode message drops "re-fetch it for a newer release" for the
same curl line the refusal prints.

## Risks / Trade-offs

- **[A pre-change CLI is unprotected]** → stated in the release notes and the README's update
  section: update once, then have an admin run setup.
- **[A wrong comparison locks every operator out]** → the comparison is a unit-tested pure function,
  the stack's pattern guarantees its input, and the way out (`curl … | sh`) is printed with the
  refusal; an admin can also lower the floor only by running setup from an older CLI, which the same
  check refuses — so a bad floor is fixed by a release, not by hand. Editing the parameter in the
  console stays possible as a last resort.
- **[Someone removes the "unused" output]** → the template comment says why it is there, and a
  `CoreTemplateTest` case pins that the output references the parameter. Without it every raise
  would silently be "already up to date".
- **[Every operator command gains a `DescribeStacks`]** → ~100 ms, within the operator's existing
  grant; `baas run` makes a second one rather than threading the first through `RunAws`.
- **[The reactor-build warning is noise on every CI and `baas-dev` command]** → accepted: it is one
  stderr line, and it is what keeps the skip visible.

## Migration Plan

1. Release the CLI with the check. Nothing is enforced yet: no stack records a version.
2. An admin runs `baas admin deployment setup` with it; the stack records that release, and older
   checking CLIs are refused from then on.
3. Rollback: running setup from the previous release is refused by design. To lower the floor, edit
   `BaasCliVersion` in the CloudFormation console (Update stack → use current template) — or release
   a fix forward.

## Resolved Questions

- *Where is the version recorded?* A stack parameter written only by setup (explore, decision 1).
- *How strict?* Any older version, patch included (decision 2).
- *Which commands?* Every one that reaches AWS; only `jobs terminate` exempt (decision 3).
- *Reactor builds?* Unchecked with a warning; setup from one keeps the recorded value (decision 5).
- *`--update`?* Removed with the stored copy, in this change (decision 7).
