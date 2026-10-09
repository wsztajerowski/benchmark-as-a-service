# Tasks

Each fix lands as its own commit on `next-release`, in the order below. Review IDs (R1–R16) are cited in
the commit bodies. Run `mvn verify` from the reactor root after each group. Never run
`mvn -pl benchmark-runner` alone.

## 1. Verify blocking assumptions

- [x] 1.1 R16: confirm `HeadBucket` returns the three outcomes the design relies on, with the
  deployer profile. Run `aws s3api head-bucket` against: the deployment's own bucket from its region
  (200); the same bucket from another region (301 or 400, carrying `x-amz-bucket-region`); a bucket name
  certainly owned by another account, e.g. `test` (403, carrying the header); and a random unused name
  (404). Record the four answers in `verify.md`. If a foreign bucket does not answer 403, stop and
  revisit the R16 design.
- [x] 1.2 R2: confirm against LocalStack 4.14.0 (an IT) that `listObjectVersionsPaginator` on a missing
  bucket throws `NoSuchBucketException` and not a generic `S3Exception` with 404. Use whichever it
  throws in 3.2.
- [x] 1.3 R7: confirm from the EC2 API reference that `ClientToken` accepts up to 64 ASCII characters
  and that a job id (`20260820T174432812Z-a3f9c21b`, 28 characters) qualifies. Note the reference in
  `verify.md`.
- [x] 1.4 R3/R10: confirm that `Ec2ProvisioningService.runInstance` still sets
  `instanceInitiatedShutdownBehavior(TERMINATE)`, and that user-data runs as root on AL2023 so
  `shutdown -h now` is permitted. Cite cloud-init's documentation in `verify.md`.
- [x] 1.5 R9: confirm the e2e workflow's JCStress and JMH assertions (`e2e-cloud-test.yml`) do not
  require `score` to be a number. If one does, adjust it in 8.4.

## 2. Setup: names free before a create, and recovery from a failed create (R16, R1)

- [x] 2.1 R16: make `S3UploadService`'s bucket probe distinguish absent, reachable-in-region-X and
  forbidden (403). The early check before the preflight acts only on reachable-in-another-region. Verify
  with unit tests: a stubbed 403 with a region header is no longer reported as "lives in".
- [x] 2.2 R16: on setup's create path, after the preflight and before `CreateStack`, refuse in three
  cases: a reachable same-region bucket (naming `aws s3 rb s3://<prefix> --force` and that it may hold
  earlier results), a forbidden bucket (name taken by another account; choose another `--deployment`),
  and an existing `<prefix>-results` table (naming `aws dynamodb delete-table`). The update path skips
  these checks. Verify with `SetupCommand` tests for each refusal: no `CreateStack`, exit non-zero, the
  remedy in the message. Also verify that an updatable existing stack is not checked.
- [x] 2.3 R1: `CloudFormationService` exposes the stack's status and a `deleteStackAndWait`. Setup
  treats `ROLLBACK_COMPLETE` as follows: run the 2.2 checks, then delete the stack, wait, and create.
  `requireUpdatable`'s message names re-running `baas admin deployment setup` instead of teardown.
  Verify with tests: a rolled-back stack is deleted and then created; a rolled-back stack with a
  leftover bucket is refused and not deleted.

## 3. Teardown: a missing bucket is empty (R2)

- [x] 3.1 R2: `deleteAllObjects` treats a missing bucket as already empty, logging that it is gone; any
  other failure still throws. Verify with an `S3UploadServiceIT` case on a nonexistent bucket. Add a
  `TeardownCommand` test showing a teardown with a missing bucket deletes the stack and the file and exits
  0, and that a failed object deletion still exits 1 before `deleteStack`.

## 4. User-data: the watchdog stays armed, and a failed terminate shuts the OS down (R3, R10)

- [x] 4.1 Remove both `kill $WATCHDOG_PID` lines. Add `|| shutdown -h now` to all three
  `terminate-instances` calls: the end of the script, the refused-`running` path and the watchdog. No
  comment line may carry meaning, since comments are stripped. Verify with `UserDataScriptBuilderTest`:
  no `kill $WATCHDOG_PID` in the script, and every `terminate-instances` line ends in
  `|| shutdown -h now`. `bash -n` on the rendered script passes, and
  `aLargeJobStaysWellUnderTheUserDataLimit` still holds.

## 5. Launch: one instance per job (R7)

- [x] 5.1 Pass `.clientToken(jobId)` on `RunInstances`. Verify with the request-shape test in
  `Ec2ProvisioningServiceTest`, which asserts the token equals the job id.

## 6. Job termination (R15 guard, R10, R6, R8, R14)

- [x] 6.1 R15: one helper in `DynamoDbJobRecorder` generates the `NOT_TERMINAL` text and its values from
  `JobStatus.EXACT_TERMINAL` and `FAILED_PREFIX`. `Update.request` and
  `UserDataScriptBuilder.guardValues()` use it. Verify with tests: the guard values cover exactly
  `EXACT_TERMINAL` plus the prefix, and `DynamoDbJobRecorderIT` still refuses every terminal status.
- [x] 6.2 R10: rename `JobStatus.isRecordedByInstance` to `endsItself` and include `timed-out`.
  `JobSession.finish` terminates a live instance only for `cancelled`. Verify with `JobStatusTest` and a
  `JobSession` test: a polled `timed-out` with a running instance is not terminated, and a polled
  `cancelled` still is.
- [x] 6.3 R6: `Ec2ProvisioningService.findLive` takes the deployment and filters on `baas-deployment`.
  Every caller passes it. Verify with a test asserting the request's filters.
- [x] 6.4 R8: extract the stop step (bounded write → on refusal a strong re-read → leave the instance if
  `endsItself`, else terminate by id or by scoped lookup) into one class used by `JobSession.stop` and
  `JobTermination`. `JobTermination` reads the item strongly after resolving the id, and reports a failed
  termination. Verify with tests: the review's race (the index reads `running`, the `cancelled` write is
  refused over `completed`) terminates nothing and exits 0; a `timed-out` item with a live instance is
  left alone; the existing `JobSession` and `JobTermination` tests pass unchanged in intent.
- [x] 6.5 R6: delete `JobTermination.terminateUnrecorded`. An id with no item fails "No job found" and
  terminates nothing. Verify with a test: an unknown id with a tagged instance of another deployment
  terminates nothing.
- [x] 6.6 R14: every end transition in `JobSession` (`stop`, `finish`, the instance-gone branch, both
  launch paths) claims the end through one `synchronized` method, and an already-ended `stop()` returns
  the recorded `endStatus`. Verify with a test: two threads call `stop(CANCELLED)` and
  `stop(TIMED_OUT)` concurrently, and exactly one status write and one terminate happen, both callers
  getting the stored status. Also check the `stop()` Javadoc against the code.

## 7. Listing: the vanished grace window (R12)

- [x] 7.1 `JobListing.resolve` and `filter` take the current instant. A non-terminal job younger than
  the named 5-minute constant keeps its stored status with no live instance. `jobs list`, `jobs show`
  and `--in-flight` pass the instant. Verify with `JobListingTest`: a 10-second-old `launching` job
  without an instance is shown as `launching` and counts as in flight; a 6-minute-old one is `vanished`.

## 8. Results query (R9, R11)

- [x] 8.1 R9: `ResultRow.from` maps an absent `score` or `scoreError` to `NaN`. Verify with a
  `ResultsGroupingTest` case: an `avgt` group with a missing score and a 9 ns/op score keeps 9 ns/op.
- [x] 8.2 R9: CSV prints an empty cell for a non-finite score or error, keeping `Locale.ROOT` for finite
  ones. `--sort-by score` puts non-finite values last in both directions. Verify with tests on
  `printCsv` and `ResultsGrouping.sorted`, ascending and descending.
- [x] 8.3 R11: `--limit` becomes nullable. The effective limit is the given value, else none under
  `--job-id`, else 20, and `--help` says so. Verify with tests: a 48-row job lookup returns 48 with no
  cut note, `--job-id … --limit 5` returns 5 and reports 5 of 48, and a project query still defaults
  to 20.
- [x] 8.4 If 1.5 found an assertion that reads `score` as a number, adjust it. Otherwise mark this
  not needed, with the reason.

## 9. Configuration: a file names its own deployment (R13)

- [ ] 9.1 Every `ConfigService` read path refuses a file whose `prefix` differs from its name, before
  any AWS client is built, naming the file, both values and "rename the file or correct `prefix`". An
  absent `prefix` is set from the name. Verify with `ConfigServiceTest`: a copied file is refused by
  `load`, `loadForSetup` and `loadForSync`, and `TeardownCommand` with that file deletes nothing.

## 10. Dead code (R15)

- [ ] 10.1 Delete `S3UploadService.deleteBucket` and its IT cases, and `JobListing.SHOWN_STATUSES`.
  Rewrite `RunnerImageExtension.hash` on `RunnerJarResolver.sha256Hex`. Verify: the existing hash test
  pins identical output, and `grep` finds no remaining reference.

## 11. Documentation

- [ ] 11.1 CLAUDE.md, *Three termination layers* and the user-data invariants:
  - `JobSession.stop` is shared through the new stop step;
  - a refused write leaves the instance alone for `completed`, `failed:<n>` and `timed-out`;
  - the watchdog is never killed;
  - every self-terminate falls back to `shutdown -h now`.

  *Job items*: `vanished` applies only after the 5-minute grace. Add a line under *Other rules* on
  setup's fixed-name pre-check and why it exists despite `Delete` policies. Verify by re-reading the
  edited paragraphs against the code.
- [ ] 11.2 Delete U31 from `docs/review/open-findings.md`. Verify by grepping for `U31`.
- [ ] 11.3 Update the affected Mermaid sources:
  - `baas-setup.mmd`: name pre-check, `ROLLBACK_COMPLETE` recovery;
  - `baas-teardown.mmd`: missing bucket;
  - `baas-jobs.mmd`: terminate without the branch for a job with no item, the shared stop step;
  - `baas-run.mmd`: watchdog `timed-out` left alone;
  - `baas-states-job.mmd`: `timed-out`/`vanished` grace;
  - `baas-states-deployment.mmd`: `ROLLBACK_COMPLETE` → setup.

  Render each with `mmdc` into the scratchpad and look at the PNG before committing.
- [ ] 11.4 R4/R5: the docs commit carries the release note as a `BREAKING CHANGE:` footer (text in
  design.md), then a `---` line, then the trailers. No line of any other commit body in this change
  opens with the keyword. Verify with `git log -1 --format=%B`, checking the footer and the separator.

## 12. Verification

- [ ] 12.1 Run the full reactor `mvn verify` green, with `ASYNC_PATH` exported. Record the test count
  in `verify.md`.
- [ ] 12.2 **Manual, live, on a second deployment** (`infra/README.md`, *A second deployment*). The
  deployer policy for the name is printed by setup.
  - R16: create a bucket named `<name>` by hand, run `baas --deployment <name> admin deployment setup`,
    and expect the leftover refusal with no stack created. Remove the bucket.
  - R16: run setup under a valid name whose bucket another account owns (e.g. `test`, confirmed in 1.1), and
    expect the "another account" refusal.
  - R2: delete the bucket of a set-up `<name>` by hand, then `teardown --yes`, and expect exit 0, the
    stack gone and the file gone.

  Record each in `verify.md`.
- [ ] 12.3 **Manual:** the `baas run` path is covered by no automated test. CI's two e2e jobs on PR #83
  must pass: they exercise user-data's success path with the new self-termination, plus R7's client
  token. Record the run ids in `verify.md`.
- [ ] 12.4 Measurement comparison: not applicable. No change reaches the benchmark process, the image or
  `environment.json` (design, *Comparability*). Record that in `verify.md` rather than running a
  comparison.
- [ ] 12.5 Run `/opsx:verify` and record the result in `verify.md`: the requirement → code → test → gap
  table, open warnings as W1, W2…, and any deviation from the design or these tasks.
