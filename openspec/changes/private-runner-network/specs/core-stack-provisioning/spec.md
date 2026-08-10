## ADDED Requirements

### Requirement: The stack creates its own networking unconditionally
`cf-template-core.yaml` SHALL always create the VPC, both subnets, the internet gateway, the route tables,
the gateway endpoints, and the security group. No parameter SHALL make networking creation conditional, and
no resource SHALL carry a networking condition.

#### Scenario: Networking resources are unconditional
- **WHEN** `infra/cf-template-core.yaml` is parsed
- **THEN** no resource declares a condition governing networking creation, and the template declares no
  condition for reusing existing networking

#### Scenario: Deploy needs no networking parameters
- **WHEN** `baas admin setup` deploys the stack
- **THEN** it supplies no VPC, subnet, or security-group identifier

### Requirement: The stack provides two subnets with distinct roles
The stack SHALL create a public subnet retaining its internet gateway route, used only for image build
instances, and a private subnet with no internet route, used only for benchmark runners. Both SHALL be
exported as separate stack outputs.

#### Scenario: Outputs distinguish the two subnets
- **WHEN** the deployed stack's outputs are read
- **THEN** they include separate identifiers for the runner subnet and the build subnet

#### Scenario: Provisioning selects the runner subnet
- **WHEN** `baas run` launches an instance
- **THEN** it uses the runner subnet identifier, not the build subnet

### Requirement: Instances are launched with terminate-on-shutdown behaviour
`baas run` SHALL launch instances with instance-initiated shutdown behaviour set to terminate, so an
operating-system shutdown destroys the instance rather than stopping it.

#### Scenario: Shutdown terminates rather than stops
- **WHEN** a benchmark instance shuts down
- **THEN** it reaches the terminated state and does not remain in the stopped state accruing storage cost

### Requirement: The operator policy no longer grants instance termination to the runner
Neither `RunnerRole` nor the runner instance profile SHALL grant `ec2:TerminateInstances`. The CLI's own
termination path SHALL continue to use operator credentials.

#### Scenario: Runner cannot terminate
- **WHEN** `RunnerRole`'s policies are inspected
- **THEN** no `ec2:TerminateInstances` statement is present

#### Scenario: Operator retains termination for the shutdown hook
- **WHEN** an identity that has assumed `BaasCliOperatorRole` terminates a benchmark instance it launched
- **THEN** the request succeeds

## REMOVED Requirements

### Requirement: Existing networking can be reused via stack parameters
**Reason**: The private-subnet design depends on S3 and DynamoDB gateway endpoints being associated with the
runner's route table. The CLI cannot verify that a caller-supplied VPC provides them, and the failure mode —
runs that hang until the wall-clock cap and then self-terminate — is close to undiagnosable. Supporting a
configuration that cannot be validated is worse than not supporting it.

**Migration**: Anyone deploying with `UseExistingVpc=true` must let the stack create its own VPC. The
parameters `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, and `ExistingSecurityGroupId` are removed;
passing them fails the deploy. A stack previously deployed with existing networking will create the full
networking set on update, and the caller-supplied resources are left untouched for the caller to remove.

### Requirement: Runner egress permits general outbound access
**Reason**: Egress on `0.0.0.0/0` for ports 443 and 80 existed to reach yum repositories, GitHub Releases,
and Atlas. The prebaked AMI supplies the toolchain, the runner JAR is staged through S3, and DynamoDB
replaces Atlas, so no destination outside the S3 and DynamoDB prefix lists remains.

**Migration**: No operator action for self-contained benchmarks. A benchmark that makes outbound network
calls of its own will now fail; such a benchmark requires a deliberate, documented egress addition rather
than relying on the previous open default.

### Requirement: The instance self-terminates through the EC2 API
**Reason**: Calling `ec2:TerminateInstances` from a private subnet would require an EC2 interface endpoint
at roughly $7 per month, and the grant that made it work was scoped to a shared tag, letting any runner
terminate any other (finding S8). Shutdown-initiated termination achieves the same outcome with no endpoint
and no cross-instance permission.

**Migration**: No operator action. The watchdog and cleanup paths invoke shutdown instead; instances are
launched with terminate-on-shutdown behaviour. The `baas-role` tag remains for identification and cost
attribution, but no longer gates termination, so its documented rationale must be rewritten rather than
carried forward.
