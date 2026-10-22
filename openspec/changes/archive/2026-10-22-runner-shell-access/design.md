# Design

## Context

See proposal.md for why. The design rests on the exploration of 2026-10-21/22 (Session Manager chosen
over EC2 Instance Connect, plain or endpoint, because it needs no inbound rule and no key) and its noise
spike. Every decision below was put to the user one at a time on 2026-10-22.

What exists today:

- `amazon-ssm-agent` 3.3.4624.0 ships in the AL2023 parent and is in the runner AMI. With no
  `ssm:UpdateInstanceInformation` grant it cannot register and hibernates: it retries every 5 minutes,
  backing off to hourly.
- `RunnerSecurityGroup` allows 443 to `0.0.0.0/0`, so the regional `ssm` and `ssmmessages` endpoints
  are already reachable (the spike's registered instances all came up `Online`).
- `OperatorSession` assumes `<prefix>-role-operator` in-process with the role session name `baas-cli`
  for every operator (finding X6). `baas-cli` already depends on the SDK's `ssm` module.
- The `jobs` slice: one subcommand per verb (`JobsTerminateSubcommand` is the nearest shape — job id
  in, job item resolved, instance looked at), AWS behind a hand-faked port per command.
- `RunCommand.watchdogBound(timeout, margin)` is the single computation of the watchdog's sleep and
  the CLI's poll cap. The reservation (`JobItem`, written by `JobRecorder.reserve`) does not record it.
- The only child process `baas` starts is `git` (`GitProject`).

**Noise spike, 2026-10-21** (12 × c5.large, `Incrementing_Synchronized`, 10 × 10 s, alternating
batches; A = today's unregistered agent, B = registered). On Xeon 8124M hosts: A 61.76M ops/s ±1.7%
(n=4), B 61.57M ±2.9% (n=4), −0.3% ± about 1.7%. One further B run was a steady 25% slower (46.45M,
±0.4% across its iterations) — uniform, not the periodic dips an agent waking every few minutes would
cause, so more likely a slow host, but six B runs cannot rule the agent out. The smallest effect 4 + 4
runs could detect is about ±3%.

## Goals / Non-Goals

**Goals:**
- A shell on any live runner of the deployment, from the job id alone, with nothing on the runner's
  network surface changing.
- Nothing about a session reaches a job's results.
- No termination layer is weakened, delayed or made conditional on a session.

**Non-Goals:**
- Port forwarding, file transfer, Run Command. Only the interactive shell document is allowed.
- Recording or marking connected jobs (decided against; see Decisions).
- Making runners connectable from a private subnet (`private-runner-network`, parked).
- Fixing X6. Own-session scoping inherits it (see Risks).

## Decisions

### Every runner is connectable; there is no opt-in

`RunnerRole` gains `ssm:UpdateInstanceInformation` and the four `ssmmessages:*Channel` actions on
`Resource: "*"` (the actions support no narrower resource) — exactly the spike's grant, which brought
all six B instances `Online`. `ec2messages` is not needed: it serves Run Command, which nothing uses.

*Alternative: `baas run --connectable`.* Lost because the case that matters is the job that hangs
when nobody planned to look at it, and the spike gave no measurable reason to pay for the flag.

*Alternative: the AWS-managed `AmazonSSMManagedInstanceCore`.* Lost: it also grants Run Command,
inventory, patching and parameter reads — far more than a session needs, and `RunnerRole` is an
allow-list everywhere else.

### The stack owns a session document; the account default is never touched

`AWS::SSM::Document` `<prefix>-session-runner` (`<prefix>-<type>-<name>`, built by `DeploymentNames`),
`DocumentType: Session`, `UpdateMethod: NewVersion` — a custom-named document is otherwise *replaced*
on a content change, which fails on the name it already holds. Content:

```yaml
schemaVersion: "1.0"
sessionType: Standard_Stream
inputs:
  idleSessionTimeout: "20"
  runAsEnabled: false          # ssm-user, sudo-capable; the box is root-owned but throwaway
  shellProfile:
    linux: "cd /app 2>/dev/null; exec bash"
  # no s3BucketName, cloudWatchLogGroupName or kmsKeyId: nothing the runner must reach
```

`cd /app` fails harmlessly while the instance is still booting (`launched`, before user-data creates
it). `exec bash` replaces the agent's `sh` (which on AL2023 is bash in POSIX mode) with an interactive
bash. Only bash is offered: other shells or `tmux` belong in a deployment's extension, never user-data.

*Alternative: the account default `SSM-SessionManagerRunShell`.* Lost because it is account-global,
like the GitHub OIDC provider BaaS never modifies: anyone who later enables S3/CloudWatch logging or
KMS there makes every BaaS session need grants `RunnerRole` does not have, and sessions then fail for
a reason BaaS neither caused nor reports. The default also starts `sh` in `/usr/bin`.

### No transcript, no `interactive` tag — a connected job is stored like any other

No logging in the document, and the runner does not look for `ssm-user` or tag anything. The user
revoked an earlier `interactive=true` measurement tag on 2026-10-22: BaaS stays blind to sessions.

*Alternatives considered:* a transcript under the job prefix (would need a deployment-wide S3 prefix,
since a document cannot name a per-job key, plus a grant and a lifecycle question; and stores whatever
is pasted); a reserved `interactive` tag, hidden by default or merely shown (both declined).

### `connect` starts the session through the SDK and execs `session-manager-plugin` itself

`StartSession(Target=<instanceId>, DocumentName=<prefix>-session-runner)` under the operator role,
then `ProcessBuilder("session-manager-plugin", <response JSON>, <region>, "StartSession", "",
<request JSON>, "https://ssm.<region>.amazonaws.com").inheritIO()` — the argument list the AWS CLI
itself passes. The response carries only the session id, its stream URL and a single-use stream token;
no credential reaches the child, its arguments or its environment. The plugin puts the terminal in raw
mode, so Ctrl+C reaches the remote shell as a byte, not the JVM as a signal. The command returns the
plugin's exit status, and calls `TerminateSession` best effort when the plugin exits abnormally or was
never started after a successful `StartSession` (an unattached session otherwise lingers until the
idle timeout).

`Console` is flushed before the hand-over; nothing is written while the plugin owns the terminal,
with one exception below.

While the plugin runs, `connect` checks the instance every 15 s. Session Manager does **not** close a
session whose target terminated, and the plugin has no escape key — in raw mode Ctrl+C goes to a
shell that no longer exists — so without the check the terminal hangs on a dead prompt (found live,
2026-10-22: a session stayed open well after its job's instance terminated itself). Once EC2 reports
the instance ending or ended, `connect` prints one line (`\r\n`-framed, the terminal being raw), ends
the session, gives the plugin 10 s to leave, then stops it, and exits 0. A describe that fails counts
as live, silently: a hiccup must not cut a session or write over the shell.

*Alternative: `aws ssm start-session`.* Lost: it makes the AWS CLI v2 a hard requirement (today only
SSO users need it) and means exporting the operator session's keys into a child's environment.

*Risk accepted:* the plugin's argument list is a de facto interface. It has been stable for years and
the ECS Exec ecosystem depends on it; a change breaks `connect` loudly, never silently.

### `connect` checks, in order: plugin, job item, instance state, agent — and asks the instance, not the status

1. `session-manager-plugin` on `PATH` (a `PATH` walk in Java, no `which`), before any AWS call. Absent:
   exit 1 naming `brew install --cask session-manager-plugin` on macOS and the AWS RPM/DEB package on
   Linux; nothing reaches AWS.
2. Job item by id through the job-ID index (`JobReference`, as `show`/`terminate` do). Absent: exit 1
   naming the id.
3. The job's pending or running instance, found by its `baas-job-id` tag within the
   `baas-deployment` (`Ec2ProvisioningService.findLiveInstance`, as `jobs show` does) — not by the
   item's `instanceId`, which the item lacks until the CLI's best-effort `launched` write or the
   instance's own `running` write lands: a job whose instance hangs before `running` after a failed
   `launched` write would otherwise be unreachable for its whole life, the case `connect` exists for.
   The tag is applied in the `RunInstances` call itself. Qualifies whatever the stored status says.
   None: exit 1 naming the stored status. (Settled 2026-10-22 at verify, W1.)
4. `ssm:DescribeInstanceInformation` filtered to the instance: not `Online` → exit 1, "not reachable
   yet — the agent registers about a minute after boot; retry shortly". Without this check a booting
   instance surfaces as `TargetNotConnected` from `StartSession`, which reads as a permission problem.

A job launched by CI is just a job item in the shared table, so the laptop reaches it identically; no
code distinguishes the two.

### The reservation stores the watchdog bound, and the deadline is computed from the instance's launch time

The user chose to show an exact deadline. The reservation is written *before* `RunInstances`, and
the watchdog sleeps `bound` seconds from when it starts on the booted instance, so a timestamp
computed at reservation would be early by the launch and boot time. The reservation therefore records
the fact it knows — `watchdogSeconds` (the `watchdogBound` value) — and `connect`/`jobs show` display
`LaunchTime + watchdogSeconds` from the `DescribeInstances` they already make, worded "terminates by
about HH:MM (in N min), or earlier when the job finishes". The watchdog starts within about a minute of
`LaunchTime`, so the real deadline is at most that much later — never earlier, so the display never
promises time that is not there.

`JobItem` gains one nullable field; `JobItemMapper` writes it only in the reservation and reads it
absent for older items, which get the generic warning. The instance's writes are unchanged — it still
writes only `status` and `instanceId`.

*Alternative: a generic warning only.* Simpler, but "how long do I have" is the first question when
looking into a slow job.

A session never touches termination: no hold, no extension, the watchdog is not killed. When the
instance terminates, the session drops.

### Operator grants are scoped to the deployment's runners, its document and the caller's own sessions

```yaml
- Action: ssm:StartSession
  Resource: arn:${Partition}:ec2:${Region}:${Account}:instance/*
  Condition:
    StringEquals: { aws:ResourceTag/baas-deployment: !Ref ResourceNamePrefix }
    BoolIfExists: { ssm:SessionDocumentAccessCheck: "true" }
- Action: ssm:StartSession
  Resource: arn:${Partition}:ssm:${Region}:${Account}:document/${ResourceNamePrefix}-session-runner
  Condition: { BoolIfExists: { ssm:SessionDocumentAccessCheck: "true" } }
- Action: [ssm:TerminateSession, ssm:ResumeSession]
  Resource: arn:${Partition}:ssm:*:${Account}:session/*
  Condition: { StringLike: { ssm:resourceTag/aws:ssmmessages:session-id: "${aws:userid}*" } }
- Action: ssmmessages:OpenDataChannel
  Resource: arn:${Partition}:ssm:*:${Account}:session/${aws:userid}-*     # to be verified, task 1.2
- Action: ssm:DescribeInstanceInformation
  Resource: "*"
```

`SessionDocumentAccessCheck` makes the document name mandatory: without it, a `StartSession` naming
no document would silently use the account default. The tag key is `baas-deployment`, which every
runner carries and which scopes every other live-runner query, so one deployment's operators cannot
open sessions on another's runners. Port-forwarding documents and `SendCommand` stay denied.
`infra/operator-policy.json` mirrors the same set (pinned by the sync test).

The `OpenDataChannel` resource is the documented form for IAM *users*; whether `${aws:userid}` (for an
assumed role, `<roleId>:baas-cli`) matches the session id an assumed-role session gets is the one
unverified IAM fact. It is task 1.2; if only an unscoped `session/*` works, the spec's "on those
sessions" is amended before implementation rather than quietly widened.

### The deployer gets one prefix-scoped statement for the document

`SsmSessionDocument`: `ssm:CreateDocument`, `GetDocument`, `DescribeDocument`, `UpdateDocument`,
`UpdateDocumentDefaultVersion`, `DeleteDocument`, `AddTagsToResource`, `RemoveTagsFromResource`,
`ListTagsForResource` on
`arn:aws:ssm:${REGION}:${ACCOUNT_ID}:document/${PREFIX}-*`. Rendered for `baas-<accountId>` the policy
is 4936 non-whitespace characters with it, leaving 696 under the 5632 test budget. Unconditioned, so the setup preflight probes it like every other
statement. Task 1.3 (2026-10-22) checked the list against the `AWS::SSM::Document` handler
permissions in CloudFormation's published resource schema: it added `RemoveTagsFromResource` (update),
and left out the handler's `s3:GetObject` and `iam:PassRole`, which serve only `Attachments` and
`Requires` — properties this document does not use. A real create, update and delete under the
deployer policy is part of task 6.1.

### The contract checks the agent; the base is untouched

One check, `systemctl is-enabled amazon-ssm-agent`, following the AWS CLI precedent (parent-supplied,
not declared, checked by the contract). The contract's version is the recipe's and changes
automatically with its content (`RunnerImageParameters.plan`), so no hand bump and no dependency on
`persist-logs-on-termination`'s unbuilt 1.4.0. The label — base version, plus extension hash — does
not change.

### `private-runner-network` stays parked because of this change

A runner with no internet route would need `ssm` and `ssmmessages` interface endpoints, about
$7/month per AZ each, to stay connectable — overturning that change's "isolation at zero standing
cost". The user chose (2026-10-22) to keep shell access and keep the network change parked; its
`PARKED.md` records the reason.

## Comparability

The image is unchanged apart from a contract check that runs only during the bake, so the measured
binaries, kernel and tunables are identical. What changes for every job is the agent's behaviour:
from hibernating (one ping per 5 min to 1 h) to registered (a heartbeat every few minutes and an open
control channel). The spike found no difference at ±3%. Results before and after this change carry the
same `imageVersion` and nothing else separates them by tag; the ADR records the change date as the
boundary. Task 6.3 compares a pre/post score and investigates any gap; the live session (task 6.2)
measures the agent's own CPU time against the job's length.

## Risks / Trade-offs

- [Operators share the role session name `baas-cli` (X6), so "own sessions" is every operator of the
  deployment] → accepted for now; any operator may already terminate any runner. Closing X6 (per-human
  session names) narrows it with no change here.
- [CI federates into the same `OperatorRole`, so CI can open sessions too] → accepted (2026-10-22): CI
  already terminates any runner and writes any job item; a shell on a box that self-terminates within
  `timeout + margin` adds nothing it could not already break. Recorded in CLAUDE.md's Accepted risks.
- [A connected job's measurements are indistinguishable from untouched ones] → accepted by decision;
  the operator who connected knows the job id.
- [The runner's `ssm-user` has sudo on a box running arbitrary benchmark JARs] → same trust boundary as
  today: only an operator supplies a JAR, and only an operator opens a session.
- [The plugin's argument interface changes] → `connect` fails loudly; the fallback is a documented
  `aws ssm start-session --document-name <prefix>-session-runner` printed in the error.
- [The plugin's reconnect (`ResumeSession`) uses its own default credentials, not the operator role]
  → a dropped connection is not resumed; the user re-runs `connect`. Not worth passing credentials to
  the child for.
- [The 25%-slow B run was the agent after all] → task 6.2 measures the agent's CPU time; task 6.3
  compares scores. Either finding reopens always-on with a number rather than an opinion.
- [A deployment not yet updated: `connect` gets `AccessDenied` or a missing document] → translated
  through `AwsErrors` into "this deployment predates `jobs connect`; run `baas admin deployment setup`".

## Migration Plan

1. Release the CLI. Existing deployments keep working; `connect` explains that setup must be re-run.
2. `baas admin deployment setup` (update) on each deployment adds the document and both roles'
   grants. From then on every new runner registers. No image rebuild is needed for `connect` to work —
   the agent is already in every AMI; the next `baas admin image build` ships the contract check.
3. CI needs nothing: it federates into the updated `OperatorRole` and never calls `connect`.
4. Rollback: setup with the previous CLI removes the document and the grants; runners go back to
   hibernating agents. Job items carrying `watchdogSeconds` are read by older CLIs without complaint
   (unknown attributes are ignored by `JobItemMapper`).

## Open Questions

None that change the specs or the tasks. The `OpenDataChannel` resource form (task 1.2) changes one
policy line and, only if it must be unscoped, one spec sentence — it is a blocking verification, not a
deferred question.

## Resolved Questions

- *Always on or opt-in?* Always on (2026-10-22), on the spike's evidence.
- *What about `private-runner-network`?* Stays parked because of this change (2026-10-22).
- *Own document or the account default?* Own (2026-10-22).
- *Transcripts?* None (2026-10-22).
- *Mark connected jobs?* No mark at all; an earlier `interactive=true` tag was revoked (2026-10-22).
- *AWS CLI or the plugin directly?* The plugin, via SDK `StartSession` (2026-10-22).
- *Which jobs?* Any whose instance is `pending`/`running` (2026-10-22).
- *Deadline?* Shown exactly, from a bound stored by the reservation (2026-10-22).
- *CI's reach?* Accepted; CI and local jobs are indistinguishable to `connect` (2026-10-22).
- *Missing plugin?* Refused before any AWS call, with the install command; `install.sh` unchanged
  (2026-10-22).
- *Image?* Contract checks the agent; no base change (2026-10-22).
- *Does `ssm-user` get created on first session?* Yes, since agent 2.3.612.0 — moot now that nothing
  is marked.
- *Task 1.2 (b), (c), live on `baas-development`, 2026-10-22* — the branch's stack deployed with
  `admin deployment setup`, a `c5.large` job launched, its agent `Online` 24 s after launch (agent
  3.3.4624.0). As the operator role with session name `baas-cli`: `StartSession` with
  `baas-development-session-runner` succeeded, and the session's own `TerminateSession` succeeded under
  the `ssm:resourceTag/aws:ssmmessages:session-id` condition (b). No document, `SSM-SessionManagerRunShell`
  and `AWS-StartPortForwardingSession` were each denied `ssm:StartSession` (c). The deployer cannot
  call `DescribeInstanceInformation`; the operator can.
- *Task 1.2 (a), live 2026-10-22*: `baas jobs connect` from this branch's JAR opened a working shell
  (`ssm-user`, `/app`, commands answered, `exit` → CLI exit 0) with the `OpenDataChannel` statement
  as designed. The session id is `baas-cli-<random>` — the role session name, while `${aws:userid}`
  for an assumed role is `AROA…:baas-cli` — so the statement's resource looks as if it cannot match.
  Either AWS authorises the data channel against something else, or the statement is not what allows
  it; a working session cannot tell the two apart. Kept as designed, since it works and is no wider
  than documented; noted in verify.md rather than claimed proven.
- *Which actions does CloudFormation need for the document, and does a content change replace it?*
  The handler permissions above (task 1.3). `Name` and `DocumentType` are create-only; `UpdateMethod`
  defaults to `Replace`, so `NewVersion` is set explicitly to update content in place.
- *Does a registered agent cost measurable throughput?* Not at ±3% (spike, 2026-10-21).
