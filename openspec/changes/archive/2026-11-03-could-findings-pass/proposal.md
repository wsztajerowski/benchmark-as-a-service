# Proposal

## Why

The 2026-11-03 pass over the Could findings kept four to build. Each is small, and together they
remove three ways a user meets a dead end or pays for nothing:

- an upload that fails before the reservation leaves an `input/` no job item names (N3);
- a typo after `--` is found only after a paid instance has booted (G9);
- the deployment's server-side state can be read only through the AWS CLI (G14);
- a unit test run from an IDE reads the developer's real `~/.baas` (T10).

## What Changes

- **`baas run` reserves the job before it uploads anything to the job's prefix (N3).** Every
  precondition, the pinned runner JAR included, is still checked before the reservation. An upload
  that fails after the reservation is recorded as `launch-failed`, like a failed instance request,
  so a stranded `input/` always has a job item that names it. Ctrl+C during the upload now cancels
  a recorded job instead of leaving nothing behind.
- **`baas run --dry-run` checks the benchmark arguments on this machine and launches nothing (G9).**
  It runs the same local checks as a real run, then the benchmark JAR's own listing with the same
  arguments (`java -jar <jar> <args> -l`). It fails, with exit 2, when JMH or JCStress rejects
  the arguments or when nothing matches. It makes no AWS call.
- **New `baas admin deployment show [--format json]` (G14).** A read-only view of the deployment
  under deployer credentials: stack status and last update, the CLI version that last set it up,
  the stack's outputs, the GitHub repositories trusted to run benchmarks, and the published runner
  image.
- **Tests never read the real `~/.baas` (T10).** The config root can be pointed elsewhere by a
  system property, which a test-session listener sets for every test run, IDE runs included.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `job-tracking`: the reservation moves before the upload, and a failed upload is recorded as
  `launch-failed`.
- `benchmark-arguments`: `baas run --dry-run` checks the arguments locally.
- `cli-command-structure`: `admin deployment` gains `show`.

T10 changes no behaviour a user sees, so it has no delta spec.

## Impact

- **Code:** `baas-cli` only. `jobs/JobLaunch` and `jobs/RunCommand` (N3, G9); a new
  `deployment/DeploymentShowSubcommand` (G14); `session/ConfigService.defaultRoot` and a new
  `testsupport` listener (T10). No change to `benchmark-runner`, `baas-model` or user-data.
- **AWS:** no new resource, no IAM change. `admin deployment show` uses only reads the deployer
  policy already grants: `cloudformation:DescribeStacks`, `ssm:GetParameter` and `ec2:Describe*`.
- **Cost:** none standing. A `--dry-run` launches nothing.
- **Comparability:** unchanged. The benchmark arguments, the runner, the image and user-data are
  untouched. The only change on the launch path is the order of the reservation and the upload.
- **Deliberately not changed:**
  - "a refused precondition creates no job item" still holds, because an upload is not a
    precondition;
  - the shutdown hook is still armed right after the reservation;
  - the reservation still comes before `RunInstances`;
  - the pinned runner JAR is still seeded and verified before anything else is written.
- **Findings:** closes N3, G9, G14 and T10 in `docs/review/open-findings.md`.
