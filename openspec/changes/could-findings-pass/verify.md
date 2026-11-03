# Verify — could-findings-pass

## Paid launches

Budget: 5, CI's e2e jobs included.

| # | When | What | Outcome |
|---|---|---|---|
| 1 | 2026-11-03 20:29 | 7.4 attached `fake-jmh-benchmarks` run on `baas-development` | completed, exit 0, 47 s from launch to results |

## 1. Blocking assumptions (2026-11-03)

- **1.1 Deployer grants.** `infra/deployer-policy.json`:
  - `CloudFormation` allows `cloudformation:DescribeStacks` on `stack/${PREFIX}/*`;
  - `SsmRunnerAmiPointer` allows `ssm:GetParameter` on `parameter/${PREFIX}/runner/ami-id`;
  - `EC2` allows `ec2:Describe*`.

  `ImageBuilderService.currentImage` is `readPointer` (`ssm:GetParameter`) followed by
  `describeImage` (`ec2:DescribeImages`), and nothing else. No IAM change is needed.
- **1.2 No AWS before `context.operator()`.** `BaasApp.executionStrategy` only sets the log level.
  The CLI version check and deployment selection both live in `OperatorSession`, which
  `CommandContext.operator()` opens on first use. A branch before `context.operator()` therefore
  makes no AWS call and needs no configured deployment.
- **1.3 Upload time against the vanish grace.** `JobListing.VANISH_GRACE` is 5 minutes. A 50 MB
  random file uploaded to `s3://baas-development/jobs/…` as `baas-operator-baas-dev` took 15.9 s,
  from home broadband. The deployer deleted the object afterwards (`head-object` returns 404). A
  `launching` item stays in flight for the whole upload.

## Implementation notes

- **2.4** `RunCommand.requireBenchmarkJarFile` refuses a `--benchmark-jar` that is not a regular file.
  A real run checks it where the existence check was, after the tag and timeout refusals, which
  `OptionValidationTest` pins. A dry run checks it right before the listing. Moving it up for both
  was tried first, and failed three `OptionValidationTest` cases.
- **4.1** The federation read-back moved into a new public `deployment.setup.GitHubTrust` record.
  `GitHubStep.deployed` delegates to it, so `SetupContext` and `GitHubStep` stay package-private.
  `CloudFormationService.view` returns a plain `StackView`, so the command imports no SDK type.
- **5.1 (deviation)** The IDE check ran through the JUnit 6 launcher API from a single-file program,
  on the module's test classpath, without surefire, instead of through IntelliJ: the worktree is not
  an open IntelliJ project. `user.home` was the real `/Users/wiktor` and `baas.home` a temporary
  `baas-test-home…`; `NoRealBaasHomeTest` passed (1/1).

## 7. End-to-end (manual)

Reactor build of `could-findings-pass` (`baas-cli/target/baas-cli.jar`, `--runner-jar`
`benchmark-runner/target/benchmark-runner.jar`), on `baas-development`.

### 7.1 Dry runs (free)

| Command after `baas run --dry-run --benchmark-jar …` | Output | Exit |
|---|---|---|
| `jmh -- Summing -f 1 -wi 1` | `pl.wsztajerowski.fake.Summing_Array.sum`; "JMH would run 1 benchmark(s)" | 0 |
| `jmh -- -f 1 -wii 1` | "JMH rejected the arguments on this machine (Java 25, exit 1): Error parsing command line: Cannot parse argument 'ii' of option w", then the command | 2 |
| `jmh -- NoSuchBenchmark` | "Nothing matches: JMH lists nothing for these arguments." | 2 |
| `jcstress -- -t NoSuchTest` | "Nothing matches: JCStress lists nothing …" | 2 |
| `jcstress -- -t IntegerIncrementing` | two test names; "JCStress would run 2 test(s)" (it said "benchmark(s)" until fixed during 7.1) | 0 |
| `jmh -- -lprof` | "-lprof … runs as a job on the runner; nothing to check on this machine" | 0 |
| `--format json … jmh -- Summing` | "--dry-run has no job to summarise …", nothing on stdout | 2 |
| `jmh -- -rf csv` | the managed-argument refusal; no listing ran | 2 |

Isolated run: `AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null AWS_PROFILE=` with
`-Dbaas.home=<empty dir>`, so no deployment and no credentials. `jmh -- Summing` listed the
benchmark and exited 0, and the empty home stayed empty. Two deployments are configured on this
machine and no `--deployment` was passed, so selection was never reached either.

### 7.2 `admin deployment show` (free)

Text and `--format json | jq .` against `baas-development`, both exit 0:
- `UPDATE_COMPLETE`, last updated `2026-10-31T23:13:33.072Z`;
- "Set up by (not recorded)" / `cliVersion: null`;
- "no repository may run benchmarks" / `github: null`;
- image `1.5.0+ext.e383db2d (ami-02f0dc3f67b976fa8)`;
- 14 outputs.

Cross-checked against `aws cloudformation describe-stacks`: `BaasCliVersion`, `GitHubOrg` and
`GitHubRepo` are all empty, because the stack was last set up from a reactor build. The status and
last update agree. `admin image show --format json` reports the same version and AMI. stdout held
only the JSON; the version-check warning went to stderr.

### 7.3 A failed upload (free, no instance)

`--benchmark-jar` was a `chmod 000` copy of the fixture JAR: a regular file, so the check passes, and
the read then fails. Job `20261103T192837463Z-c8326a47`:
- the log shows the job named, "Uploading benchmark JAR to S3...", then "Uploading the inputs of
  job … failed: java.nio.file.AccessDeniedException", then "Launch error recorded";
- `baas run` exited 1;
- `jobs list --project could-findings-pass` shows it `launch-failed`, with no instance;
- `jobs download` fetched `launch-error.txt` with `step: upload`, an empty `errorCode` (not an AWS
  error), the AMI, the subnet and the message;
- no `RunInstances` was sent.

### 7.4 One attached run (paid launch 1)

Job `20261103T192903031Z-d1f8e85d`, `jmh -- Summing -f 1 -wi 1 -i 2 -w 1 -r 1`, c5.2xlarge, image
`1.5.0+ext.e383db2d`:
- the log runs: named → "Uploading benchmark JAR" → "Uploading runner JAR" → launched
  `i-020e1aca8bb0f5955` → `completed` after 45 s of polling;
- two `Summing_Array.sum` rows were printed;
- exit 0.

The reservation writes no log line. Its place before the upload is pinned by `JobLaunchTest`, and
shown live by 7.3 and 7.5, where an upload that failed or was interrupted left an item.

### 7.5 Ctrl+C during the upload (free, no instance)

A 50 MB random file passed as `--benchmark-jar`. The CLI ran in the foreground of a script, and a
watcher sent SIGINT to its JVM 5 s after "Uploading benchmark JAR to S3...". Job
`20261103T192931661Z-4bbcde48`:
- the CLI logged "no instance to terminate" and exited 130;
- `jobs list` shows it `cancelled`, with no instance;
- `describe-instances` by its `baas-job-id` tag finds nothing;
- `head-object` on its `input/benchmark.jar` returns 404, so the interrupted upload left no object.
