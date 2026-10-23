# Spec Delta

## MODIFIED Requirements

### Requirement: Results table configuration
The core stack SHALL create a DynamoDB table named `baas-<prefix>-results` with a partition key `pk` and
a sort key `sk`, both of type String, using on-demand billing. It SHALL declare exactly one global
secondary index, partitioned on `jobId`, and SHALL declare no TTL attribute. It SHALL carry
`DeletionPolicy: Delete` and `UpdateReplacePolicy: Delete`: a deployment's history leaves with its teardown
unless exported first.

#### Scenario: Table is created with the expected key schema
- **WHEN** the core stack is deployed
- **THEN** the table exists with String `pk` as partition key, String `sk` as sort key, on-demand
  billing, and exactly one global secondary index

#### Scenario: Benchmark history leaves with the teardown
- **WHEN** `baas admin deployment teardown` deletes the core stack
- **THEN** the stack reaches `DELETE_COMPLETE` and the results table no longer exists
