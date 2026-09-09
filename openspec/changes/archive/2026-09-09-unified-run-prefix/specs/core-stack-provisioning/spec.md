## ADDED Requirements

### Requirement: The working bucket accumulates no new object versions
`S3MainBucket` SHALL declare `VersioningConfiguration.Status: Suspended`. Overwrite recovery SHALL NOT
be relied upon as a safeguard; run identifiers SHALL be unique enough that an overwrite does not occur.

#### Scenario: Suspended versioning is declared
- **WHEN** the rendered core template is inspected
- **THEN** `S3MainBucket`'s versioning status is `Suspended`

#### Scenario: A new write creates no noncurrent version
- **WHEN** an object is overwritten after suspension
- **THEN** no additional noncurrent version is retained for it

---

## MODIFIED Requirements

### Requirement: Working bucket survives stack deletion by default
`S3MainBucket` SHALL declare `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`, and SHALL declare lifecycle rules expiring noncurrent versions, reaping orphaned delete markers, and aborting incomplete multipart uploads. It SHALL NOT declare any rule that expires current objects under the run prefix, since a run's uploaded input is the only record of what that run measured.

#### Scenario: Default teardown retains the bucket by design
- **WHEN** `baas admin teardown --yes` deletes the core stack without `--delete-bucket`
- **THEN** the stack reaches `DELETE_COMPLETE` and the bucket still exists

#### Scenario: No lifecycle rule expires run artifacts
- **WHEN** the rendered core template's lifecycle rules are inspected
- **THEN** none of them expires current objects under the run prefix

### Requirement: Bucket emptying handles object versions
`S3UploadService.deleteAllObjects` SHALL delete every object version and delete marker, not only current versions. This SHALL remain true after versioning is suspended, because versions written before suspension persist until a lifecycle rule reaps them.

#### Scenario: Versioned bucket is fully emptied
- **WHEN** `deleteAllObjects` runs against a bucket whose keys have multiple versions
- **THEN** a subsequent `listObjectVersions` returns no versions and no delete markers

#### Scenario: Suspension does not remove the need to walk versions
- **WHEN** `deleteAllObjects` runs against a bucket whose versioning is suspended but which still holds versions written earlier
- **THEN** those earlier versions and any delete markers are deleted

### Requirement: Failed runs leave diagnosable output
The user-data script SHALL upload `/var/log/cloud-init-output.log` into the run's S3 prefix, alongside the run's other artifacts, before terminating the instance, on both the success and failure paths.

#### Scenario: Log survives self-termination
- **WHEN** a benchmark run exits non-zero and the instance self-terminates
- **THEN** the run's S3 prefix contains `cloud-init-output.log` alongside `run-status`
