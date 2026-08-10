## 1. Verify blocking assumptions

- [ ] 1.1 Determine whether `java-25-amazon-corretto-headless` resolves from AL2023's regional S3-hosted
  repositories or an external Corretto repository; record the answer in `design.md` Open Questions
- [ ] 1.2 Confirm the current AL2023 parent AMI ID for the target region and record it as the pin
- [ ] 1.3 Confirm `imagebuilder` is available in the target region and note the managed policy names
  (`AmazonSSMManagedInstanceCore`, `EC2InstanceProfileForImageBuilder`) resolve

## 2. Image definition

- [ ] 2.1 Create `infra/runner-image.yaml` declaring image version, parent AMI ID, and pinned Corretto,
  async-profiler, and AWS CLI versions
- [ ] 2.2 Add `infra/runner-image.yaml` to the `baas-cli` shipped resources so it lands in the JAR
- [ ] 2.3 Write `RunnerImageRenderer` rendering the Image Builder component and recipe from that file
- [ ] 2.4 Unit-test the renderer: pinned versions appear in the component, root volume is 30 GB gp3,
  parent image is an exact `ami-` ID
- [ ] 2.5 Unit-test that identical input renders byte-identical output (required for version-bump
  detection to be stable)

## 3. CloudFormation resources

- [ ] 3.1 Add `Component`, `ImageRecipe`, `InfrastructureConfiguration`, `DistributionConfiguration`, and
  `ImagePipeline` to `cf-template-core.yaml`, with the infrastructure configuration on the public subnet
- [ ] 3.2 Add the build-instance role and instance profile with only the two managed policies plus
  build-log write access scoped to the results bucket
- [ ] 3.3 Add stack outputs for the pipeline ARN, recipe name, and infrastructure configuration ARN
- [ ] 3.4 Add a template test asserting no resource has type `AWS::ImageBuilder::Image`
- [ ] 3.5 Add a template test asserting the build role grants no DynamoDB, `ec2:RunInstances`, or `iam:*`
  action

## 4. IAM policies

- [ ] 4.1 Add the `imagebuilder` action set to `deployer-policy.json`, prefix-scoped, with no
  `Resource: "*"`
- [ ] 4.2 Add `iam:PassRole` to `deployer-policy.json` limited to the build-instance profile ARN
- [ ] 4.3 Add the EC2 image actions and `ssm:PutParameter` on the two AMI pointer paths to
  `deployer-policy.json`
- [ ] 4.4 Add `ssm:GetParameter` on both pointer paths and `ec2:DescribeImages` to `operator-policy.json`
- [ ] 4.5 Extend `DeployerPolicyTest` to assert the new statements are present and prefix-exact
- [ ] 4.6 Add a test asserting the operator policy grants no `ssm:PutParameter` on the pointer paths

## 5. Build service

- [ ] 5.1 Add the `imagebuilder` SDK dependency to `baas-cli/pom.xml`
- [ ] 5.2 Write `ImageBuilderService`: register recipe version when rendered content changed, start a
  pipeline execution, poll to completion, return AMI ID and failure reason
- [ ] 5.3 Implement direct `CreateImage` against an on-demand recipe for the adhoc path, without touching
  the stack
- [ ] 5.4 Implement slot resolution and pointer read/write against SSM for both slots
- [ ] 5.5 Implement prune-at-build-start: deregister the slot's previous AMI and delete its snapshots
  before triggering the build
- [ ] 5.6 Implement AMI tagging with `baas-image-version`, `baas-slot`, and `baas-parent-ami`
- [ ] 5.7 Unit-test version-bump logic: unchanged content reuses a version, changed content bumps patch

## 6. Provenance archive

- [ ] 6.1 Write `RunnerImageArchive`: upload rendered template, component, `packages.txt`, and
  `build.json` under `images/by-version/<v>/`
- [ ] 6.2 Write the `images/by-ami/<amiId>` pointer object on each successful build
- [ ] 6.3 Add an `rpm -qa` capture step to the Image Builder component and retrieve it after the build
- [ ] 6.4 Implement archive fetch by version, failing clearly when the version is absent
- [ ] 6.5 Implement manifest diffing between a rebuild's capture and the archived `packages.txt`, marking
  the resulting AMI drifted when they differ
- [ ] 6.6 Unit-test the diff: identical manifests are clean, added/removed/changed packages are reported
- [ ] 6.7 Integration-test the archive round trip against LocalStack S3

## 7. CLI commands

- [ ] 7.1 Add `baas admin build-image` wiring renderer, stack update, build, archive, and pointer write
- [ ] 7.2 Add `--from-version <v>` building into the adhoc slot from the archive
- [ ] 7.3 Add `--clear-adhoc` deregistering the adhoc AMI and deleting its snapshots
- [ ] 7.4 Add `baas admin images` printing slot, version, AMI ID, build time, and drift status to
  `System.out`
- [ ] 7.5 Register the new subcommands under `AdminCommand` and confirm `-v` raises their log level
  (the argv pre-scan in `BaasApp.main` must still apply)
- [ ] 7.6 Assert all `build-image` variants resolve credentials from `aws.profile`, not
  `aws.operatorProfile`

## 8. Run path

- [ ] 8.1 Make `RunCommand` resolve the AMI from `/<prefix>/runner/ami-id` and fail before provisioning
  when absent or deregistered, naming `baas admin build-image`
- [ ] 8.2 Add `--image-version <v>` resolving against either slot, failing with a
  `--from-version` pointer when unmatched
- [ ] 8.3 Keep `--ami-id <id>` as a direct override, failing when the AMI does not exist
- [ ] 8.4 Pass `amiId`, `imageVersion`, `imageSlot`, and `imageDrifted` from `RunCommand` into user-data
- [ ] 8.5 Forward those four values to the runner as result tags
- [ ] 8.6 Surface drift in `baas results` output without excluding drifted rows
- [ ] 8.7 Test that a missing pointer launches no instance

## 9. Strip boot-time installation

- [ ] 9.1 Remove `yum update`, the Corretto install, and the async-profiler download from
  `UserDataScriptBuilder`
- [ ] 9.2 Verify the watchdog still starts immediately after `INSTANCE_ID` resolves, that there is still
  no `set -e`, and that the run still executes from `/app`
- [ ] 9.3 Assert the rendered user-data contains no `yum` invocation and no async-profiler download
- [ ] 9.4 Point `Ec2ProvisioningService` at the resolved AMI ID instead of the previous base image lookup

## 10. End-to-end verification

- [ ] 10.1 Run `baas admin setup` and confirm it completes without triggering a build and prints the
  build-image next step
- [ ] 10.2 Run `baas admin build-image` end to end; confirm archive contents, pointer value, and AMI tags
- [ ] 10.3 Run a `jmh` benchmark on the new AMI and confirm the result carries the four image tags
- [ ] 10.4 Run a `jmh-with-async` benchmark and confirm async-profiler works from the baked path with
  `ASYNC_PATH` exported
- [ ] 10.5 Compare one benchmark's score against a recent pre-change run; investigate any material
  difference rather than accepting it
- [ ] 10.6 Rebuild a historical version into the adhoc slot and confirm drift reporting behaves on both a
  clean and a divergent rebuild
- [ ] 10.7 Confirm `baas run` with no AMI launches nothing and exits non-zero
- [ ] 10.8 Run the full reactor `mvn clean verify` with `ASYNC_PATH` exported

## 11. Documentation

- [ ] 11.1 Add the `baas admin build-image` step to the `README.md` first-run flow
- [ ] 11.2 Update `CLAUDE.md`: user-data no longer installs tooling, the AMI is a `baas run` precondition,
  the two slots and their SSM paths, and the new `runner-image.yaml` classpath resource
- [ ] 11.3 Update `infra/README.md` with the Image Builder resources and the build-instance role
- [ ] 11.4 Update `docs/diagrams/` for the `run` sequence and add a diagram for `build-image`
- [ ] 11.5 Mark finding A8 fixed in `docs/review/baas-cli-findings.md` and update the status table
- [ ] 11.6 Record the ~$0.20/month per snapshot standing cost in the docs, since the project previously
  claimed zero standing cost
