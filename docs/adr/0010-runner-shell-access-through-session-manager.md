# ADR 0010 — Every runner is reachable by shell through Session Manager, and nothing records it

- **Status:** Accepted — implemented in `runner-shell-access`
- **Date accepted:** 2026-10-22
- **Amends:** CLAUDE.md's *The instance contacts no host outside the account* (the agent now registers
  with the regional `ssm`/`ssmmessages` endpoints); keeps `private-runner-network` parked
- **Closes findings:** none. Interacts with X6 (shared operator session name)

## Context

A job that hangs or runs slow was a black box until it ended: no SSH ingress, no key pair, and only
what the instance uploads on its way out. Looking at a live box — `jstack`, `top`, `/proc`, the
benchmark's own output — needs a shell while it still runs.

EC2 Instance Connect, plain or through an endpoint, was considered and lost to Session Manager
(2026-10-21): it needs an inbound rule or an endpoint, plus a key pushed per connection.
`amazon-ssm-agent` 3.3.4624.0 already ships in the AL2023 parent and the runner AMI, running
unregistered (it hibernates: a retry every 5 minutes, backing off to hourly).

A noise spike (2026-10-21, 12 × c5.large, `Incrementing_Synchronized`, alternating batches) compared
today's unregistered agent with a registered one. On Xeon 8124M hosts: unregistered 61.76M ops/s ±1.7%
(n=4), registered 61.57M ±2.9% (n=4) — −0.3% at a resolution of about ±3%. One further registered run
was a steady 25% slower, uniform across its iterations rather than the periodic dips a waking agent
would cause, so more likely a slow host; six runs cannot rule the agent out. A live job on
`baas-development` (2026-10-22) then measured the agent directly: after 8.5 minutes, 7.5 of them
benchmarking with a session attached, `amazon-ssm-agent` and `ssm-agent-worker` had used under a
second of CPU each against the benchmark JVM's 899 s — under about 0.1%.

## Decision

1. **Always on.** `RunnerRole` grants `ssm:UpdateInstanceInformation` and the four
   `ssmmessages:*Channel` actions, so every runner's agent registers. No opt-in flag: the case that
   matters is the job that hangs when nobody planned to look, and the measurements gave no reason to
   pay for one. No Run Command, no `ec2messages`, no `AmazonSSMManagedInstanceCore`.
2. **The stack owns the session document**, `<prefix>-session-runner`: bash, starting in `/app`,
   20-minute idle timeout, no logging, no KMS. The account-global `SSM-SessionManagerRunShell` is
   never read or written — it is shared with everything else in the account, and preferences set there
   would make every session need grants the runner lacks.
3. **`baas jobs connect <jobId>`** starts the session through the SDK under the operator role and runs
   the local `session-manager-plugin` with the arguments the AWS CLI passes it. The AWS CLI is not a
   dependency; the plugin is, for this command only, refused before any AWS call when missing. The
   instance decides who may connect — pending or running, whatever the stored status — and a job
   launched by CI is reached from a laptop by its id like any other.
4. **The operator may start sessions only on its deployment's runners** (`baas-deployment` tag) **with
   its document** (`ssm:SessionDocumentAccessCheck`), end its own sessions, and check agent
   registration. Live: no document, the account default and port forwarding are each denied.
5. **Nothing records a session.** No transcript, and no mark on the measurements of a job someone
   connected to — an `interactive=true` tag was proposed and then revoked by the owner. BaaS stays
   blind to sessions.
6. **A session never holds an instance, and ends with it.** The watchdog is not killed, extended or
   paused. Session Manager leaves a session open after its target terminates, and the plugin has no
   escape key, so `connect` checks the instance every 15 s and ends the session once it is gone (found
   live: a session hung on a dead prompt).
7. **`private-runner-network` stays parked because of this.** A runner with no internet route would
   need `ssm` and `ssmmessages` interface endpoints, about $7/month per AZ each — overturning that
   change's case of isolation at zero standing cost.

## Consequences

- No new standing cost: Session Manager is free for EC2, the document is free, and the runners reach
  the endpoints over their existing 443 egress.
- **Comparability.** Every job from this change on runs with a registered agent; the image label is
  unchanged (the contract's new check is not part of the base). Results before and after are not
  separable by tag; this date is the boundary. Measured cost: none at ±3% (spike), under 0.1% of CPU
  (live).
- A connected job's numbers are indistinguishable from untouched ones by query. The operator who
  connected knows the job id.
- CI holds the session grants too (accepted). Operators of one deployment can end each other's sessions,
  because every operator session is named `baas-cli` (X6) — seen live when one ended another's hung
  session.
- The image contract fails a bake whose `amazon-ssm-agent` is not enabled, so an extension or a parent
  release cannot silently remove the shell.
- The plugin's argument list is a de facto interface; a change breaks `connect` loudly.
- The `OpenDataChannel` statement (`session/${aws:userid}-*`) works live, though a session id
  (`baas-cli-<random>`) does not look as if it matches that resource; whether AWS authorises the data
  channel against something else, or the statement is not what allows it, is not settled.
