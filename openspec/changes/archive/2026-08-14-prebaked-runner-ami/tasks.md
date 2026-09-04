## 1. Verify blocking assumptions

- [x] 1.1 Determine whether `java-25-amazon-corretto-headless` resolves from AL2023's regional S3-hosted
  repositories or an external Corretto repository; record the answer in `design.md` Open Questions
- [x] 1.2 Confirm the current AL2023 parent AMI ID for the target region and record it as the pin
- [x] 1.3 Confirm `imagebuilder` is available in the target region and that the managed policy names
  (`AmazonSSMManagedInstanceCore`, `EC2InstanceProfileForImageBuilder`) resolve
- [x] 1.4 Determine whether the AL2023 base image supplies a `perf` binary, and confirm whether JMH's
  `-prof perf` / `perfnorm` / `perfasm` fail on the current boot-time-install path

## 2. Image definition

- [x] 2.1 Create `infra/runner-image.yaml` declaring image version, exact parent AMI ID, and pinned
  Corretto, async-profiler, `perf`, and AWS CLI versions
- [x] 2.2 Add the `kernel:` block declaring `perf_event_paranoid`, `kptr_restrict`, transparent hugepage
  mode, and swap state
- [x] 2.3 Pin the async-profiler install path to `/app/async-profiler`, matching the `--async-path` default
  in `JmhWithAsyncProfilerSubcommand`
- [x] 2.4 Add `infra/runner-image.yaml` to the `baas-cli` shipped resources so it lands in the JAR
- [x] 2.5 Write `RunnerImageRenderer` rendering the Image Builder component and recipe from that file
- [x] 2.6 Unit-test the renderer: pinned versions appear in the component, kernel tunables are applied,
  root volume is 30 GB gp3, parent image is an exact `ami-` ID (volume assertion lives in
  `CoreTemplateTest`, where the recipe is declared)

## 3. CloudFormation resources

- [x] 3.1 Add `Component`, `ImageRecipe`, `InfrastructureConfiguration`, `DistributionConfiguration`, and
  `ImagePipeline` to `cf-template-core.yaml`, with the infrastructure configuration on the public subnet
- [x] 3.2 Add the build-instance role and instance profile with only the two managed policies plus
  build-log write access scoped to the results bucket
- [x] 3.3 Add stack outputs for the pipeline ARN, recipe name, and infrastructure configuration ARN
- [x] 3.4 Add a template test asserting no resource has type `AWS::ImageBuilder::Image`
- [x] 3.5 Add a template test asserting the build role grants no DynamoDB, `ec2:RunInstances`, or `iam:*`
  action
- [x] 3.6 Add a template test asserting exactly one runner AMI pointer parameter is provided for

## 4. IAM policies

- [x] 4.1 Add the `imagebuilder` action set to `deployer-policy.json`, prefix-scoped, with no
  `Resource: "*"` and without `imagebuilder:CreateImage`
- [x] 4.2 Add `iam:PassRole` to `deployer-policy.json` limited to the build-instance profile ARN
- [x] 4.3 Add `ec2:DeregisterImage`, `DeleteSnapshot`, `DescribeImages`, `DescribeSnapshots`, `CreateTags`
  and `ssm:PutParameter` on `/<prefix>/runner/ami-id` to `deployer-policy.json`
- [x] 4.4 Add `ssm:GetParameter` on `/<prefix>/runner/ami-id` and `ec2:DescribeImages` to
  `operator-policy.json`, and remove the now-unused `SsmAmiLookup` statement granting
  `/aws/service/ami-amazon-linux-latest/*`
- [x] 4.4a Mirror the same operator change in the `OperatorRole` inline policy in
  `cf-template-core.yaml`, so the `operator-policy.json` drift test stays green
- [x] 4.5 Extend `DeployerPolicyTest` to assert the new statements are present and prefix-exact
- [x] 4.6 Add a test asserting the operator policy grants no `imagebuilder` action, no
  `ec2:DeregisterImage`, no `ec2:CreateImage`, and no `ssm:PutParameter` on the pointer path

## 5. Build service

- [x] 5.1 Add the `imagebuilder` SDK dependency to `baas-cli/pom.xml`
- [x] 5.2 Write `ImageBuilderService`: start a pipeline execution, poll to completion, return the AMI ID
  and any failure reason
- [x] 5.3 Implement the version preflight: fail before building when the declared `imageVersion` is already
  registered with different content, naming the field to edit
- [x] 5.4 Implement pointer write to `/<prefix>/runner/ami-id`
- [x] 5.5 Implement retirement of the replaced AMI — deregister and delete snapshots — **after** the
  pointer is repointed, never before
- [x] 5.6 Implement AMI tagging with `baas-image-version` and `baas-parent-ami`
- [x] 5.7 Unit-test that a failed build leaves the pointer and the previous AMI untouched
- [x] 5.8 Unit-test the ordering: the pointer write precedes the deregister call

## 6. Admin commands

- [x] 6.1 Add `baas admin build-image` wiring renderer, preflight, stack update, build, pointer write, and
  retirement
- [x] 6.2 Add `baas admin image` printing version, AMI ID, build time, and parent AMI to `System.out`
- [x] 6.3 Report the no-image-yet case from `baas admin image` naming `baas admin build-image`
- [x] 6.4 Register both subcommands under `AdminCommand` and confirm `-v` raises their log level (the argv
  pre-scan in `BaasApp.main` must still apply)
- [x] 6.5 Assert both commands resolve credentials from `aws.profile`, not `aws.operatorProfile`
- [x] 6.6 Make `baas admin setup` print `baas admin build-image` as the explicit next step

## 7. Environment capture

- [x] 7.1 Add the environment-capture block to `UserDataScriptBuilder`, writing `environment.json` with
  `schemaVersion`, image version and AMI ID, instance type and region, CPU model and topology, memory, OS
  and kernel, JVM version, tool versions, and kernel tunables
- [x] 7.2 Add `rpm -qa` capture to `packages.txt`
- [x] 7.3 Upload both to `<result-path>/` **before** the benchmark process starts
- [x] 7.4 Pass `imageVersion` and `amiId` from `RunCommand` into user-data so the manifest can record them
- [x] 7.5 Assert the rendered user-data uploads both files before invoking `benchmark-runner.jar`
- [x] 7.6 Test that a non-zero benchmark exit still leaves both files present
- [x] 7.7 Unit-test that `environment.json` is valid JSON for a representative capture

## 8. Run path

- [x] 8.1 Make `RunCommand` resolve the AMI from `/<prefix>/runner/ami-id` and fail before provisioning
  when absent or deregistered, naming `baas admin build-image`
- [x] 8.2 Keep `--ami-id <id>` as a direct override, failing when the AMI does not exist
- [x] 8.3 Point `Ec2ProvisioningService` at the resolved AMI ID instead of the previous base image lookup
- [x] 8.4 Forward `imageVersion` and `instanceType` to the runner as result tags
- [x] 8.5 Test that a missing pointer launches no instance
- [x] 8.6 Test that an override naming a missing AMI launches no instance

## 9. Environment comparison

- [x] 9.1 Add `EnvironmentManifest` parsing `environment.json`, tolerant of an unknown `schemaVersion`
- [x] 9.2 Add the top-level `baas env` command group with a `diff` subcommand
- [x] 9.3 Implement field-level diffing of two manifests, writing the payload to `System.out`
- [x] 9.4 Fail clearly when either result path has no `environment.json`, naming the path
- [x] 9.5 Assert `baas env diff` resolves credentials from `aws.operatorProfile`
- [x] 9.6 Surface differing `imageVersion` values within a `baas results` comparison group without
  excluding any row
- [x] 9.7 Unit-test the diff: identical manifests report nothing, changed/added/removed fields are reported

## 10. Strip boot-time installation

- [x] 10.1 Remove `yum update`, the Corretto install, and the async-profiler download from
  `UserDataScriptBuilder`, along with the now-unused `asyncProfilerVersion` parameter
- [x] 10.2 Verify the watchdog still starts immediately after `INSTANCE_ID` resolves, that there is still
  no `set -e`, and that the run still executes from `/app`
- [x] 10.3 Assert the rendered user-data contains no `yum` invocation and no async-profiler download

## 11. End-to-end verification

- [x] 11.1 Run `baas admin setup` and confirm it completes without triggering a build and prints the
  build-image next step
- [x] 11.2 Run `baas admin build-image` end to end; confirm the pointer value, the AMI tags, and that the
  replaced AMI and its snapshots are gone
- [x] 11.3 Confirm a second `build-image` without a version bump fails the preflight and starts no build
- [x] 11.4 Run a `jmh` benchmark and confirm `environment.json`, `packages.txt`, and the `imageVersion` /
  `instanceType` tags are present
- [x] 11.5 Run a `jmh-with-async` benchmark and confirm async-profiler works from the baked path with
  `ASYNC_PATH` exported, including kernel stacks
- [x] 11.6 Confirm the declared kernel tunables are in effect on a launched runner
- [x] 11.7 Compare one benchmark's score against a recent pre-change run; investigate any material
  difference rather than accepting it — **no difference attributable to the change, and the
  benchmark is too noisy to detect a modest one.** Pre-change `baas run` scores for
  `Incrementing_Synchronized` were 11.23M and 9.67M ops/s; post-change 8.23M (image 1.0.0) and
  10.80M (1.1.0), i.e. inside the pre-change spread. The CI history for the same benchmark spans
  10.0M-29.6M ops/s, with a 55% swing between two runs of a single CI job, so run-to-run variance
  dwarfs any plausible effect. The 31% gap between the 1.0.0 and 1.1.0 runs is that variance, not
  the JDK patch: `-f 1 -wi 1 -i 2` yields a NaN score error, i.e. no usable confidence interval.
  Investigated rather than accepted, and the conclusion is that this benchmark cannot answer the
  question — which is itself an argument for the manifest, since the environment is now recorded
  even when the numbers are inconclusive.
- [x] 11.8 Run `baas env diff` across two runs on different image versions and confirm the differing fields
- [x] 11.9 Confirm `baas run` with no AMI launches nothing and exits non-zero
- [x] 11.10 Run the full reactor `mvn clean verify` with `ASYNC_PATH` exported

## 12. Documentation

- [x] 12.1 Add the `baas admin build-image` step to the `README.md` first-run flow
- [x] 12.2 Update `CLAUDE.md`: user-data no longer installs tooling, the AMI is a `baas run` precondition,
  the single pointer path, the `runner-image.yaml` classpath resource, and the per-run environment manifest
- [x] 12.3 Add `environment.json` and `packages.txt` to the S3 result layout table in `CLAUDE.md`
- [x] 12.4 Record in *Accepted risks* that `perf_event_paranoid` and `kptr_restrict` are deliberately
  relaxed on a single-tenant throwaway instance
- [x] 12.5 Record in *Accepted risks* that historical re-measurement is a manual git-mediated rebuild, not
  a command
- [x] 12.6 Update `infra/README.md` with the Image Builder resources and the build-instance role
- [x] 12.7 Update `docs/diagrams/` for the `run` sequence and add a diagram for `build-image`
- [x] 12.8 Mark finding A8 fixed in `docs/review/baas-cli-findings.md` and update the status table
- [x] 12.9 Record the ~$0.20/month snapshot standing cost, since the project previously claimed zero
