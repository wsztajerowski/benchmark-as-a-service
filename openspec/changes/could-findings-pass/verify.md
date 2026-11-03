# Verify — could-findings-pass

## Paid launches

Budget: 5, CI's e2e jobs included.

| # | When | What | Outcome |
|---|---|---|---|

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

- **2.4** `RunCommand` refuses a `--benchmark-jar` that is not a regular file. The check moved up beside
  `refuseManagedOrNoRun()`, so it now precedes the unreleased-build refusal as well;
  `RunCommandTest.refusesToLaunchFromAnUnreleasedBuildWithoutARunnerJar` gives it a real file.
- **4.1** The federation read-back moved into a new public `deployment.setup.GitHubTrust` record.
  `GitHubStep.deployed` delegates to it, so `SetupContext` and `GitHubStep` stay package-private.
  `CloudFormationService.view` returns a plain `StackView`, so the command imports no SDK type.
- **5.1 (deviation)** The IDE check ran through the JUnit 6 launcher API from a single-file program,
  on the module's test classpath, without surefire, instead of through IntelliJ: the worktree is not
  an open IntelliJ project. `user.home` was the real `/Users/wiktor` and `baas.home` a temporary
  `baas-test-home…`; `NoRealBaasHomeTest` passed (1/1).
