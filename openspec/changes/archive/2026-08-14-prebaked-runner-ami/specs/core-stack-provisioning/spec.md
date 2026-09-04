## ADDED Requirements

### Requirement: Core stack declares the image build pipeline
`cf-template-core.yaml` SHALL declare `AWS::ImageBuilder::Component`,
`AWS::ImageBuilder::ImageRecipe`, `AWS::ImageBuilder::InfrastructureConfiguration`,
`AWS::ImageBuilder::DistributionConfiguration`, and `AWS::ImageBuilder::ImagePipeline`. The recipe's root
volume SHALL be 30 GB gp3, preserving the existing volume requirement. The infrastructure configuration
SHALL place build instances in the public subnet.

#### Scenario: Pipeline resources are present
- **WHEN** the core stack is deployed
- **THEN** it contains an image pipeline, recipe, component, infrastructure configuration, and
  distribution configuration

#### Scenario: Build volume matches the runner volume
- **WHEN** the image recipe is inspected
- **THEN** its root block device is 30 GB and of type gp3

### Requirement: The stack never performs an image build
`cf-template-core.yaml` SHALL NOT declare `AWS::ImageBuilder::Image`, because that resource performs a
build during stack operations and would add a full image build to the duration of every
`baas admin setup`.

#### Scenario: No build resource in the template
- **WHEN** `infra/cf-template-core.yaml` is parsed
- **THEN** no resource has type `AWS::ImageBuilder::Image`

#### Scenario: Setup does not build an image
- **WHEN** `baas admin setup` runs against an account with no runner AMI
- **THEN** it completes without triggering an image build and reports that `baas admin build-image` is
  the next step

### Requirement: Build instances carry only the permissions Image Builder requires
The stack SHALL create a build-instance role and instance profile attaching
`AmazonSSMManagedInstanceCore` and `EC2InstanceProfileForImageBuilder`, plus write access limited to the
build-log prefix of the results bucket.

#### Scenario: Build role is scoped
- **WHEN** the build-instance role is inspected
- **THEN** its only S3 write permission is scoped to the results bucket, and it grants no DynamoDB, no
  `ec2:RunInstances`, and no `iam:*` action

### Requirement: The runner AMI pointer is published as a single SSM parameter
The stack SHALL provide for exactly one String parameter, `/<prefix>/runner/ami-id`, holding the AMI ID of
the current runner image. Its value SHALL be written by `baas admin build-image` rather than by the stack,
since the AMI ID is not known until a build completes. The stack SHALL NOT declare a second AMI pointer.

#### Scenario: Operator can read the pointer
- **WHEN** an identity holding only `BaasCliOperatorRole` reads `/<prefix>/runner/ami-id`
- **THEN** the read is permitted

#### Scenario: Operator cannot write the pointer
- **WHEN** that identity attempts `ssm:PutParameter` on `/<prefix>/runner/ami-id`
- **THEN** the request is denied

#### Scenario: Only one pointer exists
- **WHEN** the stack's SSM parameters are listed
- **THEN** there is exactly one runner AMI pointer

### Requirement: Deployer policy covers the image build path
`BaasCliDeployerPolicy` SHALL grant the `imagebuilder` actions needed to register and execute the pipeline
(including `CreateComponent`, `CreateImageRecipe`, `CreateInfrastructureConfiguration`,
`CreateDistributionConfiguration`, `CreateImagePipeline`, the corresponding `Get`/`List`/`Update`/`Delete`
actions, `StartImagePipelineExecution`, and `TagResource`), `iam:PassRole` limited to the build-instance
profile, the EC2 image actions needed to retire a replaced image (`DeregisterImage`, `DeleteSnapshot`,
`DescribeImages`, `DescribeSnapshots`, `CreateTags`), and `ssm:PutParameter` on `/<prefix>/runner/ami-id`.
Every resource SHALL remain prefix-scoped.

#### Scenario: Build succeeds under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin build-image`
- **THEN** the build completes, the pointer is written, the replaced AMI is retired, and the command
  exits 0

#### Scenario: PassRole cannot be redirected
- **WHEN** the deployer policy is evaluated for `iam:PassRole` on a role other than the build-instance
  profile
- **THEN** the request is not permitted

#### Scenario: Image actions stay prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** no `imagebuilder` statement uses `Resource: "*"`

### Requirement: Operator policy can resolve but not publish the image
`BaasCliOperatorRole` SHALL grant `ssm:GetParameter` on `/<prefix>/runner/ami-id` and `ec2:DescribeImages`
so that `baas run` can resolve and validate the AMI. It SHALL NOT grant any `imagebuilder` action, any
EC2 image-mutating action, or `ssm:PutParameter` on the pointer path.

#### Scenario: Operator resolves the AMI
- **WHEN** `baas run` executes under operator credentials
- **THEN** it can read `/<prefix>/runner/ami-id` and describe the resulting image

#### Scenario: Operator cannot build or retire an image
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no `imagebuilder` action and no `ec2:DeregisterImage` or `ec2:CreateImage` action

## MODIFIED Requirements

### Requirement: Operator role permissions
`BaasCliOperatorRole` SHALL cover: `ec2:RunInstances`/`Describe*` to launch and observe benchmark runner instances, tag-scoped `ec2:TerminateInstances` (condition `aws:ResourceTag/baas-role=benchmark-runner`), `ec2:CreateTags` scoped to the `RunInstances` create action, `ssm:GetParameter`/`PutParameter` on the mongo connection-string path, `ssm:GetParameter` on the runner AMI pointer path (`/<prefix>/runner/ami-id`), `ec2:DescribeImages` to validate the resolved AMI, S3 object access scoped to the core stack's bucket, and `iam:PassRole` scoped to `RunnerRole` only. It SHALL NOT cover the public AL2023 AMI lookup path (`/aws/service/ami-amazon-linux-latest/*`), which is no longer used now that the runner boots from a purpose-built image. `baas run`/`baas results`/`baas config`/`baas env` SHALL succeed when invoked by an identity that has assumed `BaasCliOperatorRole`.

#### Scenario: Operator role suffices for daily use
- **WHEN** an identity that has assumed `BaasCliOperatorRole` runs `baas run jmh -- ...`, `baas results`, or `baas env diff`
- **THEN** every AWS API call made succeeds under that role's permissions, including the SSM read of `/<prefix>/runner/ami-id` needed to resolve the runner's AMI ID

#### Scenario: Public AMI lookup path is no longer granted
- **WHEN** `infra/operator-policy.json` is inspected
- **THEN** it contains no statement granting `ssm:GetParameter` on `/aws/service/ami-amazon-linux-latest/*`
