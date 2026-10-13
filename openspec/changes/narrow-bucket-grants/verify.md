# Verify: narrow-bucket-grants

## Live checks (section 5), 2026-10-13

On `baas-development` (us-east-1), the second deployment, updated by `baas admin deployment setup`
from the reactor CLI at `1ec4598`. Every `baas run` used a copy of that CLI whose manifest reads
`Implementation-Version: 7.1.0`, so it pinned and seeded the real v7.1.0 runner from
`releases/7.1.0/` instead of `--runner-jar`. That is the only way to exercise the runner's
`releases/*` read before a release. Nothing in `benchmark-runner`, `baas-model`, `baas-cli/src/main` or
`infra` differs between `v7.1.0` and the proposal commit `4e8ee85`, so that runner is the one this
branch would ship.

| # | Task | Check | Result |
|---|---|---|---|
| L1 | 5.1 | `admin deployment setup` (stack update) | Exit 0, about 1 minute, two inline policies replaced |
| L2 | 5.3 | `iam simulate-principal-policy`, one key per call | Runner: `PutObject` `releases/…` **implicitDeny**, `GetObject` `releases/…` allowed, `Put`/`GetObject` `jobs/…` allowed, `DeleteObject` both **implicitDeny**, `ListBucket` **implicitDeny**. Operator: `Put`/`Get` both prefixes allowed, `ListBucket` allowed, `DeleteObject` both **implicitDeny** |
| L3 | 5.1, 5.2 | Two `run jmh-with-async --detach` started in the same second against an empty `releases/` (async `wall`, `flamegraph`, `threads=true`) | Both CLIs logged `Seeding runner JAR releases/7.1.0/…` (15:03:20, 15:03:26). The second's conditional put was refused at 15:03:48: `…was seeded by another launch; using it`. One object, SHA-256 `ad6b9f07…c0be`, equal to the published `benchmark-runner.jar.sha256` of v7.1.0. Both jobs `completed` (`…-19aa8a09`, `…-0b1eb203`) |
| L4 | 5.1 | Artifacts of L3 | Each job has `cloud-init-output.log`, `environment.json`, `packages.txt`, `input/benchmark.jar`, `jmh-result.json`, `jmh-with-async-output.txt` and two flame graphs. No `denied`/`Forbidden`/`403` in either boot log. No `logs/` prefix: no job on this deployment ever produced one, before or after the change |
| L5 | 5.1 | `run jcstress -- --mode sanity`, attached | `completed`, exit 0 (`…-f509c710`): `jcstress-output.txt` and two per-test HTML reports, no denial in the boot log. A first attempt failed `VcpuLimitExceeded` (the account's 16 vCPUs were held by L3). It launched nothing and is unrelated to the change |
| L6 | 5.1 | `jobs download` and `jobs show` under the operator role | Downloaded 8 artifacts; `show` read the job item and tags |
| L7 | 5.4 | Bucket removed by hand (versions and delete markers first), then `admin deployment teardown --yes` | Exit 0, "does not exist; nothing to empty", stack deleted, image retired, deployment file removed. This is also pr83's R2 / W4 step 4 (recorded in its archive `verify.md`) |
| L8 | — | Recreate: `admin deployment setup --region us-east-1 --deployer-aws-profile baas-admin`, then `admin image build` | Setup exit 0, deployment file identical to before. Image build exit 0 in ~11 min: `ami-0d7ec69cf6ed538c9`, version 1.3.0, same parent `ami-07a5b367e8dc8bd92`, no extension — the deployment is as it was |

Paid instances: three benchmark runs (L3 ×2, L5) and one image build (L8), out of the 10 allowed.

## Verification (`/opsx:verify`), 2026-10-13

### Summary

| Dimension | Status |
|---|---|
| Completeness | 19/20 tasks before this record; 6.1 is this record. 3/3 delta requirements implemented |
| Correctness | 13/13 scenarios have a test, a live check or both (W1, W2) |
| Coherence | Design followed. Two deviations, both in how the live checks ran (D1, D2) |
| Build | `mvn -o verify` on the reactor: BUILD SUCCESS, 977 tests, 0 failures (model 73, runner 44 + 18 IT, CLI 800 + 42 IT). `ASYNC_PATH` was unset, so the async-profiler IT did not run; the runner is unchanged and L3 exercised async live |

### Requirement → code → test

| Requirement / scenario | Code | Test | Live | Gap |
|---|---|---|---|---|
| **RunnerRole S3 policy matches the bucket name** | `infra/cf-template-core.yaml:389-402` (two statements) | | | |
| · Runner can access its own bucket | `jobs/*` Put+Get | `CoreTemplateTest.theRunnerWritesOnlyUnderJobsAndOnlyReadsReleases` | L2, L4, L5 | — |
| · Runner reads the pinned runner JAR | `releases/*` Get | same | L2, L3 (instances ran the v7.1.0 runner from `releases/`) | — |
| · Runner cannot replace the pinned runner JAR | no write on `releases/*` | same (exact pair list) | L2: implicitDeny | — |
| · Runner cannot delete objects | no `DeleteObject` | same | L2: implicitDeny | — |
| · Runner cannot list the bucket | no `ListBucket`, no bucket ARN | same | L2: implicitDeny | — |
| **Operator role permissions** | `cf-template-core.yaml` `-policy-operator-s3`; `infra/operator-policy.json` `S3WorkingBucketAccess` | | | |
| · Operator role suffices for daily use | unchanged apart from the delete | `OperatorPolicyDriftTest` | L3, L5 (`run`), L6 (`jobs download`, `jobs show`), `jobs list` (the wait loop) | W1 |
| · Public AMI lookup path is no longer granted | unchanged | `OperatorPolicyDriftTest:108` | — | — |
| · An operator session cannot delete job artifacts | no `DeleteObject` | `CoreTemplateTest.theOperatorCannotDeleteObjects`; drift test keeps the JSON in step | L2: implicitDeny; L7: teardown still empties under the deployer | — |
| **The runner artifact slot is seeded once per version** | `RunnerJarResolver.resolve` / `seed` (`:76-89`) | | | |
| · First job of a version seeds the slot | `seed` returns `true` | `RunnerJarResolverIT.theFirstSeedWritesTheSlot` | L3, launch a | — |
| · Later jobs reuse the slot | `exists` head, unchanged | — (head path is unchanged) | L5: no seeding line, `Runner JAR: releases/7.1.0/…` | — |
| · Seeding failure prevents the launch | `fetchAsset`/`verify`, unchanged | `RunnerJarResolverTest` (checksum mismatch, blank checksum) | — | carried, unchanged |
| · Two first jobs of a version write the slot once | `ifNoneMatch("*")`; 412/409 → `false` | `RunnerJarResolverIT.aSecondSeedIsRefusedAndLeavesTheFirstObjectUnchanged`; `RunnerJarResolverTest` 412, 409, 403-rethrows, header sent | L3: launch b refused, one object, checksum equal to the release | W2 |

Mutation check (task 3.2): with `.ifNoneMatch("*")` removed, both
`RunnerJarResolverIT.aSecondSeedIsRefusedAndLeavesTheFirstObjectUnchanged` and
`RunnerJarResolverTest.theSeedAsksS3ToRefuseAnExistingObject` fail.

### Warnings

- **W1:** of the commands the daily-use scenario names, `jobs terminate`, `results query` and
  `jobs diff` were not run after the change. None of them deletes from or writes to the bucket.
  `diff` reads `environment.json` with the same `GetObject` that `show` and `download` exercised, and
  the other two make no S3 call. Low risk, so no action.
- **W2 (fixed):** the live refusal in L3 did not log its status code, so whether S3 answered 412 or
  409 that time is unknown. The refusal line now carries `(HTTP <code>)`. Both codes are handled and
  unit-tested, and 412 is also tested against LocalStack.
- **W3:** `baas-381492019823` (the default deployment) still holds the old grants. It takes the new
  ones on its first `baas admin deployment setup` after this change is released. The CLI works
  under either grant set.

### Deviations

- **D1:** the live checks ran on `baas-development`, the standing second deployment, rather than a new
  one. Its image (1.3.0, built 2026-10-12) was current, so no build was needed before the runs. The
  image build in task 5.1's list ran after the teardown (L8), to restore the deployment.
- **D2:** task 5.2's "released version" CLI was the reactor JAR with its manifest's
  `Implementation-Version` rewritten to 7.1.0 in the scratchpad, not `mvn versions:set` in a worktree.
  The effect is the same, and nothing was committed.
- Not from the design: the R2 missing-bucket teardown (pr83 W4 step 4) ran here as L7, because this
  change needed a teardown anyway. R16 still needs an IAM admin and stays in `QUEUE.md`.

### Assessment

No critical issues. W2 is fixed; W1 and W3 are low and need no change. Ready for archive once CI is green.
