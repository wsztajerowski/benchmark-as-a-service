---
paths:
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/jobs/**"
---

# Job launch, user-data and termination

Breaking these costs money or silently loses data.

## User-data generation (`UserDataScriptBuilder`)

- **No `set -e`.** If the IMDSv2 instance-id fetch fails under `set -e`, the script exits *before*
  starting the watchdog and orphans the instance. Errors are handled by exit code and the job
  item's status instead. (The Image Builder component rendered by `RunnerImageRenderer`
  *does* use `set -euxo pipefail` — opposite context: a half-installed toolchain must abort the
  bake, and there is no paid instance to orphan.)
- **The watchdog starts immediately after `INSTANCE_ID` resolves.** Every later failure has to be
  covered by it.
- **Comment-only lines are stripped from `SCRIPT_BODY` when it is rendered.** EC2 refuses user-data
  over 16 KB raw, and the comments were most of the script. So a line beginning with `#` never
  reaches the instance — nothing may depend on one, inside the manifest heredoc included.
  `aLargeJobStaysWellUnderTheUserDataLimit` holds an outsized run under 12 KB.
- **User-data installs nothing.** No `yum`, no JDK, no async-profiler download. The toolchain is
  baked into the AMI by `baas admin image build`; a runner that installed its own would measure on
  a slightly different machine every time, which is the drift this design exists to remove.
- **The environment manifest is written and uploaded *before* the benchmark starts.** A job that
  crashes still has to say what it crashed on — same reasoning as `cloud-init-output.log`.
- **Every manifest value is captured into a shell variable first.** The heredoc body is nothing but
  `${VAR}` references. Inlining command substitutions puts quotes, parens and awk programs inside a
  JSON string inside a heredoc — three levels of quoting, and a mistake in any of them yields a
  file that only fails weeks later in `baas jobs diff`. Values that can contain `"` or `\` go
  through `json_escape`.
- **Two `java`s, read apart.** `JDK_ID` is the JDK the job selected, already checked by `baas run`
  against the image's `baas-jdks` tag; the manifest's `jdk` group and the `jdkVersion`/`jdkVendor` tags
  come from `/opt/jdks/<id>/bin/java`, and `runnerJdk` from `java` on `PATH`, which runs the runner.
  The runner gets `--jdk <id>` and the result `jdkId=<id>` only when `JDK_ID` is set: an image from
  before JDK selection gets neither, records `jdk` from `java` on `PATH` with empty `id` and `home`, and
  carries no `jdkId`. Both are bash arrays, so an absent id expands to nothing rather than an empty
  argument.
- **`imageVersion`/`instanceType` reach the database via the runner's `--tag`, not EC2 tags.**
  `ResultsQueryService` reads the item's top-level `tags` map; tagging the *instance* leaves every stored
  result with a null `imageVersion`, and `--tag`/`--best-per` on it silently match nothing. The tag values are the
  ones observed on the box, so a result's tags cannot disagree with its own `environment.json`.
- **A stopped job's logs are shipped by a systemd stop hook, not by the CLI.** Ctrl+C, `baas jobs
  terminate` and the poll cap all end in `TerminateInstances`, whose default graceful OS shutdown
  runs `baas-ship-logs.service`'s `ExecStop` while the network is still up (billing has already
  stopped). It uploads what `ship_logs` does — boot log, the process output, `/app/**/*.log` at the
  runner's own keys, every name from `baas-model` (`BenchmarkType` gives both the local file and the
  key it is uploaded as) — and every exit of the script calls `ship_logs` itself too, so a hook that
  never ran loses only the external-stop case. The hook file is `declare -f ship_logs` run by an
  explicit `/bin/bash`: a `#!` line would be stripped with the comments. Never pass
  `--skip-os-shutdown` to a runner's termination; it bypasses the hook.
- **The benchmark runs from `/app`, never `/`.** The runner (`JobLogs`, every benchmark type) scans
  below its working directory for `.log` files to upload, and cloud-init starts user-data in `/`.
  The walk itself is now bounded — 8 levels, unreadable entries skipped — so from `/` it would no
  longer abort on vanishing `/proc` entries, but it would still ship any `.log` the root filesystem
  holds into the job's results.
- **The results table name *does* go into user-data, and that is deliberate.** It replaced an SSM
  fetch of the mongo connection string, which had to stay out of instance metadata because it
  carried credentials. A table name carries none — access comes from `RunnerRole`, not from knowing
  the name — so fetching it at boot would buy nothing and cost a round trip on every job. Don't
  "restore" the SSM indirection.
- **`baas run` forwards `project` and every `--tag` — `branch` and `commit` included — to the
  *runner*, and never to the instance.** They reach the item's top-level `tags` map, which is the
  only query surface `baas results query` has. The instance carries only `project=baas`, `baas-role`,
  `baas-job-id` and `baas-deployment` (`Ec2ProvisioningService.instanceTags`, which takes no caller
  tags on purpose; `baas-deployment` is fixed, set by the CLI, and scopes every live-runner query to
  its deployment, so two deployments in one region never count each other's runners): a
  copied `--tag project=…` was a duplicate key EC2 rejects for the whole launch, and every copied tag
  was exposed to EC2's 256-character and `aws:`-prefix limits. A caller `--tag` for a reserved key is
  rejected outright; the list and the reason are in `results-store.md`, *Result tagging*.
- **A runner's download of a missing key answers 403, not 404.** `RunnerRole` holds no
  `s3:ListBucket`, and without it S3 will not say whether a key exists. The only such reads are
  user-data's two `aws s3 cp` downloads of JARs the CLI wrote seconds earlier, so a 403 in
  `cloud-init-output.log` there means the key is missing as often as it means a grant is.

## Three termination layers, all required

Any one alone leaves a way to orphan a paid instance.
The watchdog is the only one that survives a deadlocked JVM.

1. Shell watchdog (`UserDataScriptBuilder`) — `sleep N && ec2:TerminateInstances`, fires
   `timeout + margin` after launch (`--watchdog-margin` / `ec2.watchdogMarginSeconds`, default 300,
   floor 60). Relative by construction: the absolute `--max-wall-clock` it replaced could be left
   below a raised timeout, so the watchdog killed a benchmark still inside its own budget. The floor
   is there because the watchdog counts from launch and `timeout` from JVM start — below it the
   instance can die before its final status is written. `RunCommand.watchdogBound` is the one place
   the bound is computed; it is also the CLI's poll cap. The watchdog records `timed-out` before
   its log upload, through `job_status` — which is why that function is defined *before* the
   watchdog forks: a subshell sees only the functions defined before it, and `bash -n` would not
   notice the difference. **Nothing kills the watchdog**, the script's own end included: it used to
   be killed just before `terminate-instances`, so a failed terminate left nothing able to end the
   instance, while a watchdog left running only writes a refused status. Every self-terminate,
   the watchdog's too, ends `|| shutdown -h now`. That relies on the launch's
   `ShutdownBehavior=TERMINATE`, and needs no API call, permission or instance id
2. Process `timeout` around `java -jar benchmark-runner.jar`
3. CLI JVM shutdown hook (`RunCommand`, armed from `JobLaunch`'s `Listener.reserved` — after the
   reservation, before the launch) for Ctrl+C, registered *before* `RunInstances` so an
   interrupt mid-launch can look the instance up by its `baas-job-id` tag. That lookup can
   miss — the instance may not exist yet, and `DescribeInstances` lags — so the instance covers it:
   its `running` write is refused over the recorded `cancelled`, and a refused `running` means it
   ships its boot log and terminates without starting the benchmark. It, the poll cap
   (`timed-out`) and `baas jobs terminate` share one stop step (`JobStop`): record why under a 5 s
   timeout, then terminate whatever the write did — unless the write was refused because the job had
   already reached a status after which the instance ends itself (`JobStatus.endsItself`:
   `completed`, `failed:<n>`, or a `timed-out` its watchdog wrote), when it is mid-upload and
   terminates itself. So the only stored status for which the CLI terminates a live instance it did
   not stop itself is `cancelled`. A status write never holds a termination back. `RunInstances`
   carries the job id as its client token, so an SDK retry cannot start a second, untracked
   instance. **`--detach` disarms the hook once the launch is confirmed** (ADR 0007): it fires on
   every JVM exit, not only on Ctrl+C, so left armed it would cancel the job the CLI had just
   returned from. A detached job's third layer is `baas jobs terminate`; until the confirmation, an
   interrupt cancels it like an attached one

## Launch and the instance

- **`baas run` reads the clock once per job.** That instant names the prefix and travels to the
  runner as `--created-at`, so the id's timestamp and the stored `createdAt` are the same value
  rather than two that happen to be close. The instance's clock never reaches the record. CI mints
  its id in bash and must pass the same instant, or the property holds for `baas run` and quietly
  fails there.
- **The instance contacts no host outside the account.** Its only runner-JAR source is
  `releases/<version>/benchmark-runner.jar` in the bucket, seeded by the CLI. Restoring a
  network fetch reintroduces both the drift — two jobs a week apart executing different runner
  code — and the egress `private-runner-network` exists to remove. The one other destination is AWS's
  own regional `ssm`/`ssmmessages` endpoints, where the AMI's `amazon-ssm-agent` registers so
  `baas jobs connect` can reach the runner. Same 443 egress; nothing reaches the job from there unless
  an operator opens a session.
- **`releases/<version>/benchmark-runner.jar` is seeded once and never overwritten.** That
  immutability is what the whole pinning argument rests on, and two things hold it: the seed is a
  `PutObject` with `If-None-Match: *`, so S3 refuses a second write — a 412 or 409 means another
  launch got there first, and is not an error (two first jobs of 7.0.0 both wrote the key while it
  was a head-then-put) — and `RunnerRole` can only *read* `releases/*`, so benchmark code cannot
  replace the runner every later job of that version executes. A corrupted object therefore does
  not self-repair: delete the key (deployer credentials — no other role can delete) and the next
  job re-seeds it, checksum-verified.
- **`baas jobs connect` ends its session when the instance terminates, and a session never holds
  the instance.** Session Manager leaves a session open after its target is gone and the plugin has
  no escape key — in raw mode Ctrl+C goes to a shell that no longer exists — so the terminal hung on a
  dead prompt (found live). While the plugin runs, `connect` checks the instance every 15 s and ends
  the session once it is ending or ended. Nothing about a session delays, extends or blocks any
  termination layer.
- **EC2 tags use the key `baas-role`, not `baas:role`.** `RunnerRole`'s `ec2:TerminateInstances`
  condition is scoped to it, so changing the key breaks self-termination.
- **Root volume is 30 GB gp3, not the AL2023 default.** 8 GB is exhausted by profiling artifacts.
