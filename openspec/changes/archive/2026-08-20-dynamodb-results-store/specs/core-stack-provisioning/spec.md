## ADDED Requirements

### Requirement: Database traffic never leaves the VPC
The core stack SHALL create a DynamoDB gateway endpoint associated with the runner's route table, so
runner-to-table traffic is routed privately. A gateway endpoint SHALL be used rather than an interface
endpoint, because it carries no hourly charge.

#### Scenario: Gateway endpoint is present
- **WHEN** the core stack is deployed
- **THEN** an `AWS::EC2::VPCEndpoint` of type Gateway exists for `com.amazonaws.<region>.dynamodb`,
  associated with the runner's route table

#### Scenario: No hourly endpoint charge is introduced
- **WHEN** the template's endpoints are inspected
- **THEN** no interface endpoint is declared for DynamoDB

### Requirement: Runner egress no longer includes the database port
`RunnerSecurityGroup` SHALL NOT permit outbound TCP on port 27017.

#### Scenario: Database port is closed
- **WHEN** the deployed security group's egress rules are inspected
- **THEN** no rule covers port 27017

### Requirement: The runner can write results but not read them
`RunnerRole` SHALL be granted `dynamodb:PutItem` and `dynamodb:BatchWriteItem` on the results table ARN
and nothing else on that table. It SHALL NOT be granted `Query`, `Scan`, `GetItem`, or any delete action.

#### Scenario: Runner can store a result
- **WHEN** an instance using the runner instance profile writes a measurement
- **THEN** the write succeeds

#### Scenario: Runner cannot read the results history
- **WHEN** that instance attempts `dynamodb:Scan` on the results table
- **THEN** the request is denied

#### Scenario: Runner cannot delete results
- **WHEN** that instance attempts `dynamodb:DeleteItem` on the results table
- **THEN** the request is denied

### Requirement: The operator can read results but not write them
`BaasCliOperatorRole` SHALL be granted `dynamodb:Query` and `dynamodb:GetItem` on the results table ARN
and its index ARN, and SHALL NOT be granted write or delete actions on either.

#### Scenario: Operator can query results
- **WHEN** an identity that has assumed the operator role runs `baas results`
- **THEN** the query succeeds

#### Scenario: Operator can query the request-ID index
- **WHEN** that identity runs `baas results --request-id <id>`
- **THEN** the index query succeeds

#### Scenario: Operator cannot mutate results
- **WHEN** that identity attempts `dynamodb:PutItem` on the results table
- **THEN** the request is denied

### Requirement: Deployer policy covers the table lifecycle
`BaasCliDeployerPolicy` SHALL grant `dynamodb:CreateTable`, `DescribeTable`, `UpdateTable`,
`TagResource`, `ListTagsOfResource`, `DescribeTimeToLive`, and `DeleteTable`, scoped to the
prefix-derived table ARN rather than `Resource: "*"`. The rendered policy SHALL remain within the inline
policy budget already asserted by the existing renderer test.

#### Scenario: Setup creates the table under the deployer policy alone
- **WHEN** an identity holding only `BaasCliDeployerPolicy` runs `baas admin setup`
- **THEN** the stack deploys with the results table created and the command exits 0

#### Scenario: Table actions are prefix-scoped
- **WHEN** `infra/deployer-policy.json` is inspected
- **THEN** every `dynamodb` statement names the prefix-derived table ARN and none uses `Resource: "*"`

#### Scenario: Policy still fits the budget
- **WHEN** the deployer policy is rendered
- **THEN** its non-whitespace length remains under the asserted inline-policy limit

### Requirement: Setup detects a retained table from a previous stack
`baas admin setup` SHALL check for an existing results table left behind by a previous stack before
deploying, and SHALL fail with a message naming the table and the remedy, rather than surfacing a
CloudFormation error that does not identify the cause.

#### Scenario: Retained table blocks setup with a clear message
- **WHEN** a results table from a prior stack exists and `baas admin setup` is run
- **THEN** the command exits non-zero naming the table, mirroring the existing pre-check for a retained
  bucket

#### Scenario: Clean account proceeds
- **WHEN** no results table exists
- **THEN** setup proceeds without the pre-check interfering

### Requirement: The table name is a stack output
The core stack SHALL expose the results table name as a stack output, so the CLI can resolve it without
a parameter-store lookup.

#### Scenario: Output is present
- **WHEN** the core stack is deployed
- **THEN** its outputs include the results table name

## REMOVED Requirements

### Requirement: Runner egress reaches MongoDB Atlas
**Reason**: Measurements now go to a DynamoDB table created by the stack and reached over a gateway
endpoint. Nothing that BaaS provisions connects to Atlas, so port 27017 egress grants reach that is no
longer used, and removing it is a prerequisite for the private-subnet change. The MongoDB adapter
retained in `benchmark-runner` is for standalone use of that JAR outside BaaS-provisioned networking, so
it places no requirement on this security group.

**Migration**: No operator action. Existing Atlas history is carried over once by
`scripts/migrate-atlas-to-dynamodb`, after which the Atlas cluster and its `0.0.0.0/0` access list can be
decommissioned independently of BaaS.

### Requirement: Mongo connection string is provisioned as an SSM SecureString
**Reason**: The table name replaces the connection string, is not a secret, and comes from a stack output.
`/<prefix>/mongo/connection-string` and every grant referencing it — on the deployer, operator, runner and
CI workflow roles — become dead configuration.

**Migration**: Delete the `/<prefix>/mongo/connection-string` parameter by hand after migration completes;
the stack no longer manages it. `baas admin setup --mongo-uri` and `baas config set --mongo-uri` are
removed, and `exec-single-benchmark.yml` no longer reads the parameter.
