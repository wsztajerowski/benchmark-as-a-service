# Spec Delta

## MODIFIED Requirements

### Requirement: The job is reserved before the instance is launched
`baas run` SHALL write the job item with status `launching` after every precondition has passed, the
runner JAR's release included, and before anything is uploaded to the job's prefix. If that write
fails, `baas run` SHALL NOT upload to the job's prefix, SHALL NOT request an instance, and SHALL exit
non-zero. A refused precondition SHALL NOT create a job item. From the reservation on, an interrupt SHALL
record the job as `cancelled`. Once the instance request returns, `baas run` SHALL be able to terminate
that instance on interrupt before it writes anything further. It SHALL then record `launched` with the
instance identifier, only while the status is still `launching`. That write SHALL be best effort: if it
fails, the job proceeds, and the instance's own `running` write supplies the instance identifier. If it
is refused because the job is already terminal (cancelled elsewhere while launching), `baas run` SHALL
terminate the instance it launched and exit non-zero.

#### Scenario: A refused job leaves no item
- **WHEN** `baas run` fails a precondition such as a missing benchmark JAR or a missing runner image
- **THEN** no job item exists for it

#### Scenario: A failed reservation launches nothing
- **WHEN** the `launching` write is rejected
- **THEN** nothing is uploaded to the job's prefix, no instance is requested, and the command exits
  non-zero naming the failed write

#### Scenario: Nothing reaches the job's prefix before its item exists
- **WHEN** `baas run` uploads the benchmark JAR
- **THEN** the job item already reads `launching`

#### Scenario: Interrupt during the upload
- **WHEN** the operator presses Ctrl+C while the benchmark JAR is uploading
- **THEN** the job item reads `cancelled` and no instance is requested

#### Scenario: A launched job records its instance
- **WHEN** the instance request succeeds
- **THEN** the job item's status becomes `launched` and it carries the instance identifier

#### Scenario: Interrupt during the launched write
- **WHEN** the operator presses Ctrl+C after the instance request returned and before `launched` is
  recorded
- **THEN** the instance is terminated

#### Scenario: A job cancelled while launching does not run
- **WHEN** `baas jobs terminate` cancels a job whose item still reads `launching`, and the launching
  CLI's instance request then succeeds
- **THEN** the launching CLI's `launched` write is refused, it terminates the instance it launched,
  and it exits non-zero

#### Scenario: A late launched write does not move the status back
- **WHEN** the instance has already recorded `running` and the CLI's `launched` write arrives later
- **THEN** the item keeps `running`

### Requirement: A failed launch is recorded and diagnosable
When the upload to the job's prefix or the instance request fails, `baas run` SHALL record status
`launch-failed` on the job item, with the AWS error code when the failure carries one. It SHALL upload
`launch-error.txt` to the job's S3 prefix holding the error code, message, AWS request identifier,
time, the step that failed, and the requested instance type, image and subnet. Both writes SHALL be best
effort: the CLI SHALL report the original error and exit non-zero whether or not they succeed. A failed
upload SHALL NOT request an instance.

#### Scenario: Insufficient capacity is recorded
- **WHEN** the instance request fails with `InsufficientInstanceCapacity`
- **THEN** the job item reads `launch-failed` with that error code, the job prefix holds
  `launch-error.txt`, and `baas run` exits non-zero

#### Scenario: A failed upload is recorded
- **WHEN** uploading the benchmark JAR fails after the job was reserved
- **THEN** no instance is requested, the job item reads `launch-failed`, `baas jobs list` shows the
  job, and `baas run` exits non-zero naming the failed upload

#### Scenario: The recording itself fails
- **WHEN** the instance request fails and the S3 upload and the item write fail too
- **THEN** `baas run` still reports the original launch error and exits non-zero
