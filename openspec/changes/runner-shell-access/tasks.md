# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 *(user)* Install the plugin locally (`brew install --cask session-manager-plugin`) and confirm
  `session-manager-plugin --version` prints a version.
- [x] 1.2 *(user applies the IAM; the classifier blocks IAM writes from the agent)* On `baas-development`,
  attach the design's runner grants to `<prefix>-role-runner` and the design's operator statements to
  `<prefix>-role-operator` as temporary inline policies, launch a `fake-jmh-benchmarks` job, and open a
  session as the operator role with `aws ssm start-session --target <id>`. Confirm, from the result and
  CloudTrail: (a) `ssmmessages:OpenDataChannel` on `session/${aws:userid}-*` admits an assumed-role
  session — if not, find the narrowest resource that does, and amend the `core-stack-provisioning`
  delta's "on those sessions" before any implementation if it must be unscoped; (b) `TerminateSession`
  on the caller's own session succeeds under the `ssm:resourceTag/aws:ssmmessages:session-id` condition;
  (c) `StartSession` with no `--document-name` is denied once `SessionDocumentAccessCheck` is in force.
  Remove both temporary policies and record the outcomes in design.md's Resolved Questions. *(Done on
  the branch's deployed stack instead of temporary policies, by the user's choice; outcomes in
  design.md.)*
- [x] 1.3 Confirm the deployer statement's action list against the `AWS::SSM::Document` handler
  permissions (`aws cloudformation describe-type --type RESOURCE --type-name AWS::SSM::Document`,
  `handlers.{create,read,update,delete}.permissions`), and confirm that `UpdateMethod: NewVersion` is
  what lets a custom-named document change content in place; update design.md if either differs.

## 2. Core stack and policies

- [x] 2.1 Add `<prefix>-session-runner` to `DeploymentNames` and the `AWS::SSM::Document` to
  `cf-template-core.yaml` (type `Session`, `UpdateMethod: NewVersion`, bash in `/app`, idle timeout, no
  logging or KMS keys); `CoreTemplateTest` pins its name, type, update method and the absence of
  `s3BucketName`, `cloudWatchLogGroupName` and `kmsKeyId`.
- [x] 2.2 Grant `RunnerRole` `ssm:UpdateInstanceInformation` and the four `ssmmessages:*Channel`
  actions; `CoreTemplateTest` pins exactly that set (no `ssm:SendCommand`, no `ec2messages`, no managed
  policy) and that `RunnerSecurityGroup`'s ingress, egress and `GroupDescription` are unchanged
  (`theRunnerSecurityGroupDescriptionIsNeverEdited` still passes).
- [x] 2.3 Add the operator statements (StartSession on tagged instances and on the document, both with
  `SessionDocumentAccessCheck`; own-session Terminate/Resume; `OpenDataChannel` as task 1.2 found;
  `DescribeInstanceInformation`) to the template and `infra/operator-policy.json`; the sync test and a
  new `CoreTemplateTest` case pin the tag key `baas-deployment` and the document ARN. *(Written in the
  design's form; left open until task 1.2(a) confirms or replaces the `OpenDataChannel` resource.)*
- [x] 2.4 Add the `SsmSessionDocument` statement to `infra/deployer-policy.json`, scoped to
  `document/${PREFIX}-*`; `renderedPolicyLeavesRoomInACustomerManagedPolicyBudget` still passes, and a
  renderer test asserts the statement names no other document.

## 3. Job item carries the watchdog bound

- [x] 3.1 Add nullable `watchdogSeconds` to `JobItem` and `JobItemMapper`; mapper tests round-trip it and
  read an item without it as absent.
- [x] 3.2 Write it in `RunCommand`'s reservation from `watchdogBound`, and only there; a test asserts
  `--timeout 600 --watchdog-margin 300` reserves 900, and that `DynamoDbJobRecorder`'s later updates
  and `UserDataScriptBuilder`'s rendered `job_status` never name the attribute.
- [x] 3.3 `baas jobs show`: print "terminates by about HH:MM (in N min)" from `LaunchTime +
  watchdogSeconds` while the instance is `pending`/`running`, nothing otherwise, and carry
  `watchdogSeconds` in `--format json`'s `job` member; `JobsShowSubcommandTest` covers running, terminated
  and bound-absent jobs.

## 4. `baas jobs connect`

- [x] 4.1 A plugin locator that walks `PATH` for `session-manager-plugin` and, when absent, produces the
  macOS and Linux install instructions; unit-tested with a temporary `PATH`.
- [x] 4.2 A `ConnectAws` port (job item lookup, `DescribeInstances` state and launch time,
  `DescribeInstanceInformation` ping status, `StartSession`, `TerminateSession`) with its AWS adapter, and
  a hand-written fake bound to the same contract as the other `jobs` ports. *(Done as the command's
  nested `Sources` port with the adapter `AwsConnectSources`, faked in `JobsConnectCommandTest` — the
  `jobs show` pattern. No shared contract suite: LocalStack does not emulate Session Manager, so the
  adapter's SSM calls are exercised only by task 6.)*
- [x] 4.3 `JobsConnectSubcommand`, registered in `JobsCommand`, applying the checks in the design's order
  (plugin → job item → instance state → agent online); tests with the fake cover: plugin missing (no AWS
  call at all), unknown id, `launching` with no instance, terminated instance, agent not online, a
  `launched` job with a live instance (allowed), and that `AccessDenied` or a missing document is
  reported as "re-run `baas admin deployment setup`".
- [x] 4.4 The deadline line before the shell opens (exact when `watchdogSeconds` is present, the generic
  warning otherwise), written through `Console` and flushed before hand-over; tested for both.
- [x] 4.5 The plugin launcher: `ProcessBuilder` with `inheritIO`, the AWS CLI's argument order, region and
  `ssm` endpoint, returning the plugin's exit status, and calling `TerminateSession` best effort when the
  plugin fails to start or exits abnormally; a test with a stub plugin script on `PATH` asserts the
  arguments carry the session id, stream URL and token, and that neither its arguments nor its
  environment contain any `AWS_*` credential variable.
- [x] 4.6 `baas jobs` usage lists `connect`, and `baas connect` is an unknown command; the existing
  command-tree tests are extended.

## 5. Image contract

- [x] 5.1 Add the `systemctl is-enabled amazon-ssm-agent` check to the contract rendered by
  `RunnerImageRenderer`, failing with a message that names the SSM agent; the rendered-contract test
  asserts the check and the contract stays under the 4096-byte parameter cap test.

## 6. End-to-end verification — manual, on the `baas run` path (no automated test covers it)

- [x] 6.1 Second deployment (infra and IAM change): with the reactor CLI on `baas-development`, run
  `admin deployment setup` (update), `admin image build`, a `fake-jmh-benchmarks` run, `jobs connect`
  to it, and `admin deployment teardown` + setup again to confirm the document is removed and re-created
  with the stack; record each step in verify.md.
- [ ] 6.2 One live session on a running job (needs task 1.1): confirm the shell is bash as `ssm-user` in
  `/app`; the deadline printed matches `LaunchTime + timeout + margin`; the agent's CPU time over the job
  (`ps -o times` for `amazon-ssm-agent` and `ssm-session-worker`) set against the job's length; a
  `--detach`ed job is reached by id from a second terminal; the session ends when the instance
  terminates; and a port-forwarding document and `SendCommand` are denied. Run three more registered
  c5.large `Incrementing_Synchronized` jobs to see whether the spike's 25%-slow run recurs. Record all of
  it in verify.md.
- [ ] 6.3 Score comparison (measurement-affecting): compare `fake-jmh-benchmarks`'
  `Incrementing_Synchronized` on the same instance type and CPU model before (a pre-change job on the
  same deployment) and after this change; investigate any difference beyond the spike's ±3% rather than
  accept it, and record the spread in verify.md.

## 7. Documentation

- [x] 7.1 CLAUDE.md: the `jobs` noun's verbs include `connect` *(no edit: CLAUDE.md names the nouns,
  never the verbs)*; the "instance contacts no host outside
  the account" invariant names the regional SSM endpoints the agent uses; *Accepted risks* gains CI's
  reach through `OperatorRole`, unmarked connected jobs, and shared own-session scoping (X6); *What isn't
  there* states no session transcript and no mark on connected jobs.
- [x] 7.2 ADR 0010 *Runner shell access through Session Manager*: always on (with the spike's numbers and
  the change date as the comparability boundary), own document, no mark, the plugin interface,
  `private-runner-network` left parked, and the rejected alternatives.
- [x] 7.3 `private-runner-network/PARKED.md`: add that shell access needs the `ssm` and `ssmmessages`
  interface endpoints (~$7/month per AZ each) in a private subnet, and is why the change stays parked.
- [x] 7.4 A sequence diagram `docs/diagrams/` for `baas jobs connect`, rendered with `mmdc` to the
  scratchpad and looked at before committing.
- [x] 7.5 README: `session-manager-plugin` as a prerequisite for `jobs connect` only; `infra/README.md`
  mentions the session document among the stack's resources.
- [x] 7.6 `docs/review/open-findings.md`: note under X6 that own-session scoping for `jobs connect` also
  keys on the shared session name (closes nothing).

## 8. Verify

- [ ] 8.1 Run `/opsx:verify` and record the result in verify.md in the change directory — a requirement
  → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or
  tasks.
