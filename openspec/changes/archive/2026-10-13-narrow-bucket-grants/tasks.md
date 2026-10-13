# Tasks

## 1. Verify blocking assumptions

- [x] 1.1 Confirm the AWS SDK in use (`awssdk.java.bom.version` 2.54.12) exposes `PutObjectRequest.ifNoneMatch` and maps a refused conditional put to an `S3Exception` with status 412 (and 409 for a concurrent conflict); verify by compiling a call and reading the SDK model. If either is missing, stop and revise design.md
- [x] 1.2 Confirm LocalStack 4.14.0 honours `If-None-Match: *` on `PutObject` (second put of an existing key answers 412, object unchanged); verify with a throwaway probe against the pinned container. Record the answer in design.md's risk on it — it decides whether 3.2 is a LocalStack IT or a stubbed unit test
- [x] 1.3 Re-confirm nothing under the runner role lists, deletes or writes outside `jobs/`: grep `UserDataScriptBuilder` and `benchmark-runner/src/main` for every `aws s3`/`s3Client` call and check each key against the design's Context table; verify that the runner's async-profiler and log uploads all resolve under the job's `RESULT_PATH`
- [x] 1.4 Confirm `aws s3 cp s3://… <local>` needs only `s3:GetObject` (no `ListBucket`) on AL2023's CLI; verify against the AWS CLI docs or a live call under a GetObject-only policy, and note the result in design.md

## 2. Grants

- [x] 2.1 Narrow `${prefix}-policy-runner-s3` in `infra/cf-template-core.yaml` to `PutObject`+`GetObject` on `jobs/*` and `GetObject` on `releases/*`, without touching `RunnerSecurityGroup`'s `GroupDescription`; verify with new `CoreTemplateTest` cases pinning: no `DeleteObject`, no `ListBucket`, no bucket-level resource, no write outside `jobs/*` (these fail before the edit)
- [x] 2.2 Drop `s3:DeleteObject` from `${prefix}-policy-operator-s3` and from `infra/operator-policy.json`'s `S3WorkingBucketAccess`; verify with a `CoreTemplateTest` case pinning its absence and `OperatorPolicyDriftTest` still green
- [x] 2.3 Run `aws cloudformation validate-template` (cfn-lint is not installed) over `cf-template-core.yaml` and the full `baas-cli` unit suite; verify both pass

## 3. Conditional seed

- [x] 3.1 Extract the put in `RunnerJarResolver.resolve` into a package-private `seed(S3Client, bucket, key, bytes)` that sends `If-None-Match: *`, returns `true` when it wrote and `false` on 412 or 409 (logged at info, naming the key), and rethrows every other error; `resolve` keeps its `HeadObject` first and returns the key either way. Verify the existing `RunnerJarResolverTest` stays green
- [x] 3.2 Test the seed (LocalStack IT if 1.2 said yes, else stubbed `S3Client` unit tests for 412 and 409 plus a non-conditional error that still throws): first seed writes, second seed of the same key returns `false` and leaves the first bytes unchanged; verify the test fails with the condition removed
- [x] 3.3 Update `RunnerJarResolver`'s javadoc so "upload-if-absent, never overwrite" names the conditional write as what enforces it; verify by reading

## 4. Documentation

- [x] 4.1 Update CLAUDE.md: the `releases/<version>/…` invariant says the seed is a conditional write and the runner cannot write `releases/`; S3 layout and IAM paragraphs reflect the narrowed grants; add the runner-has-no-`ListBucket` 403-not-404 note under *What isn't there*; verify by grep for `DeleteObject` and `seeded once` in CLAUDE.md
- [x] 4.2 In `docs/review/open-findings.md`, delete S14 and S15 (index rows and the `narrow-bucket-grants` design section), and reduce S7 to its remainder — another job's objects under `jobs/*` — with no change queued; verify every remaining citation of S14/S15 in `docs/` and `openspec/` either points at an archive or is removed
- [x] 4.3 Remove `narrow-bucket-grants` from `openspec/changes/QUEUE.md` (renumber the rest); verify the table reads in order
- [x] 4.4 If a diagram in `docs/diagrams/` shows the runner-JAR seed or the role grants, update it, render with `mmdc` to the scratchpad and look at the PNG; verify by `grep -l "releases/\|seed" docs/diagrams/*.mmd`

## 5. Live verification (manual — no automated test covers the `baas run` path)

- [x] 5.1 On a second deployment (infra/README.md, *A second deployment*): `baas admin deployment setup` with the reactor CLI, `baas admin image build`, then a `jmh-with-async` run of `fake-jmh-benchmarks` and a JCStress sanity run; verify both complete with flame graph, `logs/async-output/`, `environment.json`, `packages.txt` and `cloud-init-output.log` in `baas jobs download`, i.e. no upload was denied
- [x] 5.2 Build the reactor CLI as a released version (`mvn versions:set -DnewVersion=<latest release>` in a scratch worktree, never committed) so it seeds from that release; on the second deployment, with `releases/<version>/` absent, start two `baas run --detach` in the same second; verify exactly one seed write lands, the other logs the refusal, and both jobs complete
- [x] 5.3 `aws iam simulate-principal-policy` against `<prefix>-role-runner`: verify `s3:PutObject` on `releases/x`, `s3:DeleteObject` on `jobs/x` and `s3:ListBucket` are denied while `s3:PutObject` on `jobs/x` and `s3:GetObject` on `releases/x` are allowed; against `<prefix>-role-operator`, verify `s3:DeleteObject` is denied
- [x] 5.4 `baas admin deployment teardown` of the second deployment; verify the stack and its `~/.baas/deployments/<name>.yaml` are gone (teardown's bucket emptying still works under the deployer)
- [x] 5.5 Record 5.1–5.4 in `verify.md` in the change directory

## 6. Verify

- [x] 6.1 Run `/opsx:verify` and record the result in `verify.md` in the change directory — a requirement → code → test → gap table, open warnings under stable IDs (W1, W2…), and any deviation from design or tasks
