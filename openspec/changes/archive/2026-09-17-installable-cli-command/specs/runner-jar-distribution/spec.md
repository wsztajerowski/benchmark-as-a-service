## MODIFIED Requirements

### Requirement: The release publishes correctly versioned artifacts and a checksum
The release process SHALL publish, as release assets, the CLI JAR, the runner JAR, a SHA-256 checksum
for each of those JARs, and the installer script. Every published JAR SHALL be built after the
release version has been set, and every published checksum SHALL be computed from the artifact built
after that point, so that the version readable from a published artifact is the released version and
its checksum matches it. The CLI JAR SHALL carry its version in packaged metadata readable at run
time. The published installer SHALL carry the version of the release that published it, and that
version SHALL NOT be committed back to the repository, which SHALL retain the unreleased placeholder.

#### Scenario: A published artifact reports its own release version
- **WHEN** the CLI JAR from a release is executed
- **THEN** the version it reports is that release's version, not the unreleased placeholder

#### Scenario: The checksum matches the published JAR
- **WHEN** a published checksum is compared against the published JAR it names
- **THEN** they match

#### Scenario: The CLI JAR is a release asset
- **WHEN** a release is published
- **THEN** its assets include the CLI JAR

#### Scenario: The CLI JAR's checksum is a release asset
- **WHEN** a release is published
- **THEN** its assets include a SHA-256 checksum for the CLI JAR

#### Scenario: The installer is a release asset carrying that release's version
- **WHEN** a release is published
- **THEN** its assets include the installer, and running it with no version requested installs that
  release

#### Scenario: The repository keeps the unreleased placeholder
- **WHEN** a release completes
- **THEN** the installer in the repository still carries the unreleased placeholder version
