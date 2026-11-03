# Tasks

Paid-run budget for this change: **at most five instance launches**, counting CI's e2e jobs. Each
launch is counted in `verify.md` as it happens. Everything in sections 1–6 runs locally or makes
only free AWS reads.

## 1. Verify blocking assumptions

- [x] 1.1 Confirm that `infra/deployer-policy.json` grants `cloudformation:DescribeStacks` on the stack,
  `ssm:GetParameter` on the image pointer and `ec2:Describe*`, and that `ImageBuilderService.currentImage`
  calls nothing else. Record the statements in `verify.md`. If anything is missing, stop: the design
  assumes no IAM change.
- [x] 1.2 Trace `baas run`'s path from `BaasApp` to `RunCommand.execute`: the CLI version check
  (`CliVersionCheck`, `VersionCheckCoverageTest`) and deployment selection. Confirm that none of it
  makes an AWS call or needs a configured deployment before `context.operator()`. If one does, record
  how `--dry-run` is exempted before writing section 3.
- [x] 1.3 Read `JobListing.VANISH_GRACE`, and time a 50 MB upload to the `baas-development` bucket
  (`aws s3 cp`, free). Confirm that a `launching` item stays in flight for the whole upload. Record
  both numbers.

## 2. N3: reserve before the upload

- [x] 2.1 In `JobLaunch.run`, move the pinned-runner-JAR resolution before naming the job, and move
  the reservation and `listener.reserved` to just after naming, before any upload to the job's
  prefix. Update the class comment and `Listener.reserved`'s Javadoc to the new order. Verify with
  `JobLaunchTest`: the recorder's reservation precedes the first upload, and a refused
  reservation uploads nothing.
- [x] 2.2 Record an upload failure as `launch-failed`: `recordLaunchFailure` takes the failed step,
  writes it to `launch-error.txt` and the log, launches nothing, and returns 1. Verify with a
  `JobLaunchTest` case where `FakeRunAws.upload` throws: the item reads `launch-failed`,
  `launch-error.txt` names `upload`, and no instance is requested.
- [x] 2.3 Verify that an interrupt during the upload cancels the job: in a `JobLaunchTest` /
  `RunCommandLaunchTest` case, the hook fires while `upload` runs, the item reads `cancelled`, and no
  instance is requested.
- [x] 2.4 Make `RunCommand`'s existence check refuse a `--benchmark-jar` that is not a regular file:
  a directory passes `exists()` today and then fails as an upload. Verify with a `RunCommandTest`
  case.

## 3. G9: `baas run --dry-run`

- [x] 3.1 Add a `LocalListing` in `jobs`: it builds `<java.home>/bin/java -jar <jar> <args…> -l`, runs
  it with merged output and a 120 s limit, and returns the exit code, the output and the listed
  names, read by the dotted-identifier rule in design.md. Verify with unit tests over captured JMH
  and JCStress outputs: valid, a parse error, and an empty match.
- [x] 3.2 Add `--dry-run` to `RunCommand`. Move the JAR existence check up beside
  `refuseManagedOrNoRun()`, then branch before `context.operator()`: refuse `--format json`
  (exit 2), handle `-lprof` alone (say it runs on the runner, exit 0), otherwise run the listing.
  Print the names to standard output, or exit 2 with the tool's output or "nothing matches". End
  every outcome with "Nothing was launched". The process start is injected (a field, like
  `awsFactory`). Verify with `RunCommandTest` cases for each outcome, asserting no AWS client was
  requested.
- [x] 3.3 Add `LocalListingIT`, with both fixture JARs copied into `baas-cli/target` at
  `pre-integration-test` as `benchmark-runner/pom.xml` does. It runs the real listing for valid
  arguments, a typo and a non-matching pattern in both tools, and pins the name count. Verify:
  `mvn -pl baas-cli -am verify` runs it green.
- [x] 3.4 Add `--dry-run` to the help footer, beside the no-run refusals. Verify with
  `HelpAndHintsTest` or a help-text assertion.

## 4. G14: `baas admin deployment show`

- [x] 4.1 Make `GitHubStep.deployed` reachable from `deployment`, public or moved beside `CoreStack`,
  without crossing a slice rule. Verify: `SliceBoundaryTest` stays green.
- [x] 4.2 Add `DeploymentShowSubcommand` (`show`, `--format text|json`) under `DeploymentCommand`. It
  reads the stack, its parameters and outputs, the federation and the image under
  `DeployerSession.factory(config)`, and refuses a missing stack with exit 1 naming setup. The
  rendering is a static function of a `Report` record. Verify with unit tests of both renderings,
  including with and without GitHub trust and image, and JSON escaping through `Json.string`.
- [x] 4.3 Update `CommandTreeTest` (or the test covering `admin deployment`'s usage) to the five verbs,
  and the shell-completion expectations if they enumerate verbs. Verify that the suite is green.

## 5. T10: no test reads the real `~/.baas`

- [x] 5.1 Make `ConfigService.defaultRoot()` honour the `baas.home` system property. Add
  `testsupport.NoRealBaasHome`, registered in
  `META-INF/services/org.junit.platform.launcher.LauncherSessionListener` beside
  `NoRealAwsProfile`. Update surefire's `user.home` comment. Verify with a `NoRealBaasHomeTest`:
  `defaultRoot()` does not start with `$HOME`. Run it once from IntelliJ (the `idea` MCP runner)
  as well as from Maven.

## 6. Documentation

- [x] 6.1 Update `.claude/rules/user-data.md`, *Three termination layers*, item 3: the hook is armed
  after the reservation and before the upload. Update `docs/diagrams/baas-run.mmd`,
  `baas-states-job.mmd` and `baas-lifecycle.mmd` wherever they show the upload before the
  reservation. Render each edited `.mmd` with `mmdc` into the scratchpad and look at the PNG before
  committing.
- [x] 6.2 Add `admin deployment show` to `.claude/rules/deployment-config.md`. Mention `--dry-run`
  in `.claude/rules/benchmark-runner.md` beside the no-run refusals.
- [x] 6.3 Delete N3, G9, G14 and T10 from `docs/review/open-findings.md`. Verify with
  `grep -nE '\b(N3|G9|G14|T10)\b' docs/review/open-findings.md`, which should print nothing.
- [ ] 6.4 After `/opsx:archive`, rename the scenario "The deployment noun lists its four verbs" to "…five
  verbs" in `openspec/specs/cli-command-structure/spec.md` by hand. `openspec validate` refuses a
  MODIFIED block that drops a scenario name, so the delta keeps the old name with the new body.

## 7. End-to-end verification (manual — no automated test covers the `baas run` path)

Uses the reactor JAR via the `baas-dev` alias (`--deployment baas-development`, `--runner-jar`).

- [x] 7.1 Dry runs, free: valid JMH arguments, a typo, a non-matching pattern, JCStress `-t NoSuchTest`,
  `-lprof`, and `--format json`, with exit codes as specified. Run one with
  `AWS_CONFIG_FILE=/dev/null AWS_SHARED_CREDENTIALS_FILE=/dev/null` and a `baas.home` pointing at
  an empty directory, to prove that no deployment or credentials are needed. Record the outputs.
- [x] 7.2 `admin deployment show` against `baas-development`, text and `--format json | jq .`, free.
  Compare it with `aws cloudformation describe-stacks` and `admin image show`.
- [x] 7.3 A failed upload, free (no instance): pass a `--benchmark-jar` that cannot be read
  (`chmod 000`). Expect exit 1, a `launch-failed` item in `jobs list`, and `launch-error.txt` naming
  `upload` via `jobs download`. If 2.4's regular-file check or the local read refuses it first,
  record that and instead revoke the upload with a deny-only session policy on the operator profile.
- [x] 7.4 One attached `baas run` of `fake-jmh-benchmarks` on `baas-development` (paid launch 1):
  completed, measurements printed, and the log order shows the reservation before the upload.
- [x] 7.5 One `baas run` interrupted with Ctrl+C during the upload, using a large JAR, in the
  foreground (no paid launch if the interrupt lands before `RunInstances`). Expect the item to read
  `cancelled` and no instance. Skip if the upload is too fast to interrupt reliably, and say so in
  `verify.md`.
- [x] 7.6 Open the PR into `next-release`. CI's `e2e-cloud-test.yml` runs two launches (paid launches
  2–3). Record their outcome. One paid launch is held in reserve for a re-run.

## 8. Verify

- [x] 8.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory:
  - a requirement → code → test → gap table;
  - open warnings under stable IDs (W1, W2…);
  - the paid-launch count;
  - any deviation from design or tasks.
