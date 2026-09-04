## ADDED Requirements

### Requirement: Runners launch into a subnet with no route to the internet
Benchmark runners SHALL launch into a private subnet whose route table contains no route to an internet
gateway and no route to a NAT device, and which does not assign public IP addresses on launch.

#### Scenario: Runner subnet has no internet route
- **WHEN** the runner subnet's route table is inspected
- **THEN** it contains no `0.0.0.0/0` route to an internet gateway or NAT device

#### Scenario: Runner instances have no public address
- **WHEN** a benchmark instance is launched
- **THEN** it has no public IP address and no Elastic IP association

#### Scenario: General internet egress fails from the runner
- **WHEN** a process on a running benchmark instance attempts to reach an address outside the VPC that is
  not covered by a gateway endpoint
- **THEN** the connection does not succeed

### Requirement: The runner reaches AWS services only through gateway endpoints
The runner subnet's route table SHALL carry gateway endpoints for S3 and DynamoDB, and these SHALL be the
runner's only means of reaching AWS services. No interface endpoint SHALL be created, so no hourly endpoint
charge is incurred.

#### Scenario: Both gateway endpoints serve the runner subnet
- **WHEN** the runner subnet's route table is inspected
- **THEN** it is associated with gateway endpoints for both S3 and DynamoDB

#### Scenario: No interface endpoints exist
- **WHEN** the stack's VPC endpoints are listed
- **THEN** every endpoint is of type Gateway

#### Scenario: Result upload and store write succeed
- **WHEN** a benchmark completes on a private-subnet instance
- **THEN** its output is uploaded to S3 and its result is written to DynamoDB

### Requirement: Runner egress enumerates only the services it uses
`RunnerSecurityGroup` egress SHALL be restricted to the AWS-managed prefix lists for S3 and DynamoDB in the
stack's region, on TCP 443. It SHALL NOT permit egress to `0.0.0.0/0`, and SHALL NOT permit TCP 80.

#### Scenario: Egress is prefix-list scoped
- **WHEN** the security group's egress rules are inspected
- **THEN** every rule targets an AWS-managed prefix list and none targets `0.0.0.0/0`

#### Scenario: Plain HTTP is closed
- **WHEN** the egress rules are inspected
- **THEN** no rule covers TCP port 80

### Requirement: Build instances retain internet access in a separate subnet
Image build instances SHALL launch into the public subnet, which retains its internet gateway route. The
public subnet SHALL NOT be used for benchmark runners.

#### Scenario: Builds still reach package repositories
- **WHEN** `baas admin build-image` runs
- **THEN** the build instance reaches its package sources and the build completes

#### Scenario: Runner and builder use different subnets
- **WHEN** the stack's subnets are inspected
- **THEN** the image builder infrastructure configuration names the public subnet and runner provisioning
  names the private subnet

### Requirement: The instance terminates itself without calling the EC2 API
Instances SHALL be launched with shutdown-initiated termination behaviour so that an operating-system
shutdown terminates the instance. The watchdog and the normal cleanup path SHALL terminate by invoking
shutdown rather than `ec2:TerminateInstances`. A forced power-off SHALL follow if the instance is still
running 120 seconds after shutdown is requested.

#### Scenario: Normal completion terminates the instance
- **WHEN** a benchmark run completes and the cleanup path runs
- **THEN** the instance shuts down and is terminated rather than stopped

#### Scenario: Watchdog terminates a deadlocked run
- **WHEN** the benchmark JVM deadlocks and the wall-clock cap elapses
- **THEN** the watchdog uploads the boot log, requests shutdown, and the instance is terminated

#### Scenario: Hung shutdown is escalated
- **WHEN** shutdown has been requested and the instance is still running 120 seconds later
- **THEN** a forced power-off is issued

#### Scenario: No EC2 API call is made from the instance
- **WHEN** the rendered user-data is inspected
- **THEN** it contains no `ec2 terminate-instances` invocation

### Requirement: All three termination layers remain in force
The change SHALL preserve three independent termination mechanisms: the shell watchdog, the process
timeout around the benchmark, and the CLI's shutdown hook. The watchdog SHALL remain the layer that
survives a deadlocked JVM.

#### Scenario: Watchdog is independent of the JVM
- **WHEN** the benchmark JVM stops responding
- **THEN** the watchdog subshell still fires, because shutdown is invoked from a separate process

#### Scenario: Process timeout still bounds the benchmark
- **WHEN** the benchmark exceeds its own timeout but the JVM is responsive
- **THEN** the process is killed by the timeout and the run reports failure

#### Scenario: Interrupting the CLI still terminates the instance
- **WHEN** the operator interrupts `baas run` with Ctrl+C
- **THEN** the CLI's shutdown hook terminates the instance using operator credentials from the laptop

### Requirement: The runner role cannot terminate instances
`RunnerRole` SHALL NOT be granted `ec2:TerminateInstances`.

#### Scenario: Termination permission is absent
- **WHEN** `RunnerRole`'s policies are inspected
- **THEN** no statement grants `ec2:TerminateInstances`

#### Scenario: One runner cannot terminate another
- **WHEN** a process on a benchmark instance attempts to terminate a different instance
- **THEN** the request is denied

### Requirement: The runner JAR is always staged through S3 and verified
`baas run` SHALL upload the `benchmark-runner` JAR to S3 before launching an instance, caching it under a
version-scoped key so repeated runs do not re-upload. It SHALL verify the JAR's checksum before upload.
The runner SHALL NOT download the JAR from GitHub Releases.

#### Scenario: JAR is fetched from S3 by the instance
- **WHEN** the rendered user-data is inspected
- **THEN** it fetches the runner JAR from S3 and contains no GitHub Releases request

#### Scenario: Cached JAR is reused
- **WHEN** a second run uses the same runner version
- **THEN** the cached object is reused and no re-upload occurs

#### Scenario: Checksum mismatch aborts the run
- **WHEN** the JAR's computed checksum does not match the expected value
- **THEN** the command exits non-zero before launching an instance

### Requirement: Failure diagnostics survive network isolation
The boot log SHALL continue to be uploaded to the run's S3 result path before self-termination, on both the
normal and watchdog paths, so a run that fails before producing output remains diagnosable.

#### Scenario: Boot log is uploaded on failure
- **WHEN** a run fails before producing benchmark output
- **THEN** `cloud-init-output.log` is present at the run's S3 result path

#### Scenario: Boot log is uploaded on the watchdog path
- **WHEN** the watchdog fires
- **THEN** the boot log is uploaded before the instance terminates
