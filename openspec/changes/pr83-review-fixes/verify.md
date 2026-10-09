# Verify — pr83-review-fixes

## 1. Blocking assumptions

| Task | Result |
|---|---|
| 1.1 | `aws s3api head-bucket` with `baas-admin` on 2026-10-09: the own bucket `baas-381492019823` from eu-central-1 → 200 with `x-amz-bucket-region: eu-central-1`. The same bucket from us-east-1 → 301 with the header (the AWS CLI then follows it to 200). `test` from eu-central-1 → **301** with `x-amz-bucket-region: us-west-2`, then 403 in us-west-2. A random name → 404. **Deviation from the design:** a foreign bucket in another region answers with a redirect, not 403, so the probe re-asks in the region the redirect names (design.md updated). |
| 1.2 | LocalStack 4.14.0: `listObjectVersionsPaginator` on a missing bucket throws `NoSuchBucketException` (`S3UploadServiceIT.emptyingABucketThatDoesNotExistSucceeds` passes only because that exception is caught). |
| 1.3 | EC2 `RunInstances` `ClientToken`: "Unique, case-sensitive identifier … Constraints: Maximum 64 ASCII characters" (EC2 API Reference, RunInstances). A job id is 28 ASCII characters (`JobId`), so it qualifies. |
| 1.4 | `Ec2ProvisioningService.runInstance` sets `.instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)` (line 70). cloud-init runs user-data scripts as root (cloud-init docs, *User-data scripts*), so `shutdown -h now` is permitted. With that behaviour, an OS shutdown terminates the instance (EC2 User Guide, *Change instance initiated shutdown behavior*). |
| 1.5 | `e2e-cloud-test.yml` asserts length, `imageVersion`, `instanceType`, `benchmarkType`, `benchmarkName` and tags. It never reads `score`, so 8.4 is not needed. |

## 2. Setup

- 2.1–2.2: `BucketProbe` (absent / reachable / forbidden, a redirect asked again in the region it names) and `SetupCommand.nameConflict`. `BucketProbeTest` replays task 1.1's live answers, and `SetupCommandTest` covers the three refusals, free names, and a reachable bucket elsewhere not counting as a create conflict.
- 2.3: `CloudFormationService.stackStatus`. `deploy()` treats `ROLLBACK_COMPLETE` as a create: names are checked first, then the stack is deleted and waited out, then created. The `requireUpdatable` message now names re-running setup (`CloudFormationServiceTest`).
- **Gap W1:** the ordering inside `deploy()` (check → delete → create) has no unit test, because `deploy()` builds its clients from `AwsClientFactory`. It is covered by the live check in 12.2.

## 3. Teardown

- 3.1: `deleteAllObjects` catches `NoSuchBucketException`, logs it and returns; every other failure still throws (`S3UploadServiceIT`, 7 tests green).
- **Gap W2:** `TeardownCommand.removeDeployment` is all AWS calls, and its tests replace it whole. "A missing bucket still deletes the stack and the file" is covered by the live check in 12.2, not by a unit test.

## 4. User-data

- 4.1: both `kill $WATCHDOG_PID` lines and the then-unused `WATCHDOG_PID` are gone, and all three `terminate-instances` calls end in `|| shutdown -h now`. `UserDataScriptBuilderTest` has 62 green tests: the watchdog executed with a failing stub ends in `shutdown -h now`; the refused-`running` path executed with every call refused ends in terminate then shutdown; `bash -n`; and `aLargeJobStaysWellUnderTheUserDataLimit`.

## 5. Launch

- 5.1: `RunInstances` carries `.clientToken(jobId)` (`Ec2ProvisioningServiceTest.theLaunchIsIdempotentOnTheJobId`).
