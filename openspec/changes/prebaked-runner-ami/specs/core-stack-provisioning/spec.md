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

### Requirement: AMI pointers are published as SSM parameters
The stack SHALL provide for two String parameters, `/<prefix>/runner/ami-id` and
`/<prefix>/runner/adhoc-ami-id`, holding the AMI IDs of the current and adhoc slots. Values SHALL be
written by `baas admin build-image` rather than by the stack, since the AMI ID is not known until a build
completes.

#### Scenario: Operator can read both pointers
- **WHEN** an identity holding only `BaasCliOperatorRole` reads both parameter paths
- **THEN** both reads are permitted

#### Scenario: Operator cannot write the pointers
- **WHEN** that identity attempts `ssm:PutParameter` on `/<prefix>/runner/ami-id`
- **THEN** the request is denied

### Requirement: Deployer policy covers the image build path
`BaasCliDeployerPolicy` SHALL grant the `imagebuilder` actions needed to create and execute builds
(including `CreateImage`, `CreateImageRecipe`, `CreateComponent`, `GetImage`, `GetImageRecipe`,
`ListImageRecipes`, `StartImagePipelineExecution`, `TagResource`), `iam:PassRole` limited to the
build-instance profile, the EC2 image actions (`CreateImage`, `DeregisterImage`, `DeleteSnapshot`,
`DescribeImages`, `DescribeSnapshots`, `CreateTags`), and `ssm:PutParameter` on the two AMI pointer paths.
Every resource SHALL remain prefix-scoped.

#### Scenario: Build succeeds under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin build-image`
- **THEN** the build completes, the archive is uploaded, the pointer is written, and the command exits 0

#### Scenario: PassRole cannot be redirected
- **WHEN** the deployer policy is evaluated for `iam:PassRole` on a role other than the build-instance
  profile
- **THEN** the request is not permitted

#### Scenario: Image actions stay prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** no `imagebuilder` statement uses `Resource: "*"`
