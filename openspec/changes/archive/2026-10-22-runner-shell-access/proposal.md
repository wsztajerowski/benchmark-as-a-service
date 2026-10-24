## Why

A job that hangs or runs slow is a black box until it ends: there is no SSH ingress, no key pair, and the
only evidence is what the instance uploads on its way out (`cloud-init-output.log`, and since
`persist-logs-on-termination` the logs of a stopped job). Looking at the live box — `jstack`, `top`,
`/proc`, the benchmark's own output — needs a shell on it while it still runs. `amazon-ssm-agent` is
already in the runner AMI and runs unregistered, so Session Manager gives that shell with no inbound
port, no key and no new standing cost; a 12-job noise spike (2026-10-21, c5.large) measured no
difference between a registered and an unregistered agent at its ±3% resolution.

## What Changes

- New operator verb **`baas jobs connect <jobId>`**: resolves the job item, checks that its instance is
  `pending` or `running` and that the agent is online, then opens an interactive shell through Session
  Manager. Any job of the deployment qualifies, whoever launched it — a hung CI job is reached from a
  laptop by its id alone.
- `baas` calls `StartSession` through the AWS SDK and hands the response to the locally installed
  `session-manager-plugin`, the way the AWS CLI does internally. The AWS CLI is **not** a new
  dependency; the plugin is, for this one command, and its absence is refused before any session is
  started, naming the install command for the platform.
- Every runner is connectable, always: `RunnerRole` gains the Session Manager channel actions
  (`ssm:UpdateInstanceInformation`, `ssmmessages:*Channel`), so the agent registers on every job.
  There is no opt-in flag.
- The core stack gains its own session document, `<prefix>-session-runner` (bash, starting in `/app`,
  idle timeout), and `OperatorRole` may start sessions only with that document and only on its own
  deployment's runners. The account-global `SSM-SessionManagerRunShell` is never read or written.
- `OperatorRole` gains `StartSession`, `TerminateSession`/`ResumeSession` on its own sessions,
  `ssmmessages:OpenDataChannel`, and `ssm:DescribeInstanceInformation`; the deployer policy gains the
  document's lifecycle.
- The job item's `launching` reservation additionally records the job's watchdog bound, so `connect`
  and `baas jobs show` can state when the instance will be terminated. A session never extends an
  instance's life.
- The image contract checks that `amazon-ssm-agent` is enabled, so an extension or a parent release that
  removes it fails the bake.
- No session transcript is recorded, and **measurements of a job someone connected to carry no mark**:
  they are stored exactly like any other job's.

## Capabilities

### New Capabilities
- `runner-shell-access`: `baas jobs connect` — which jobs can be connected to, the local plugin
  requirement, the session it opens, what it says about the instance's remaining life, and what it
  deliberately does not record.

### Modified Capabilities
- `job-tracking`: the `jobs` group gains `connect`; the reservation records the watchdog bound;
  `baas jobs show` reports the termination deadline.
- `cli-command-structure`: the `jobs` noun's verb list gains `connect`.
- `core-stack-provisioning`: the stack declares the session document; `RunnerRole` gains the Session
  Manager channel actions; `OperatorRole` gains session start and own-session control; the deployer
  policy covers the document's lifecycle.
- `runner-image-provisioning`: the contract also checks the SSM agent.

## Impact

- **Code**: `baas-cli` — a new `jobs connect` subcommand with its AWS port (SSM `StartSession`,
  `DescribeInstanceInformation`, `TerminateSession`) and a plugin launcher; `RunCommand`'s reservation
  and `JobItemMapper` (one new attribute); `jobs show`; the contract renderer. `baas-model` —
  `JobItemMapper`. The runner (`benchmark-runner`) and user-data are unchanged.
- **Infra**: `cf-template-core.yaml` (one `AWS::SSM::Document`, `RunnerRole` and `OperatorRole`
  statements), `infra/operator-policy.json`, `infra/deployer-policy.json` (one statement; the rendered
  policy grows to 4936 non-whitespace characters, 696 under the 5632 test budget).
- **Dependencies**: none in the build — `baas-cli` already depends on the SDK's `ssm` module.
  `session-manager-plugin` on the operator's machine, for `connect` only.
- **Cost**: none standing. Session Manager is free for EC2 instances, a session document is free, and
  runners already reach the regional SSM endpoints over their existing 443 egress — no VPC endpoint is
  added. Session traffic is negligible.
- **Measurements**: every job from this change on runs with a registered agent. The spike found no
  difference at ±3%; a pre/post score comparison is part of the tasks. The image label does not change
  (the contract is not part of the base), so results before and after are not separable by tag — the
  ADR records this.
- **Open findings**: closes none. It interacts with X6 (every operator session is named `baas-cli`):
  own-session scoping keys on that name, so operators of one deployment can end each other's sessions
  until X6 is addressed.
- **`private-runner-network` stays parked because of this change.** A runner with no internet route
  would need `ssm` and `ssmmessages` interface endpoints (about $7/month per AZ each) to stay
  connectable; that reason is added to its `PARKED.md`.
- **Deliberately not changed**: no inbound rule on `RunnerSecurityGroup` and no change to its egress or
  `GroupDescription`; no key pair; the instance's tag set (`project`, `baas-role`, `baas-job-id`,
  `baas-deployment`); the three termination layers — nothing kills or postpones the watchdog, a session
  included; user-data installs nothing; the instance still writes only `status` and `instanceId` to the
  job item; the base image and its `imageVersion`; the tag vocabulary (no `interactive` key).
