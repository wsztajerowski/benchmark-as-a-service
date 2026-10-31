# jdk-selection Specification

## Purpose
Lets one benchmark be measured on several JDKs: the JDKs a runner image holds, how the image publishes
which ones it holds, and how a job chooses the one it measures on.

## Requirements

### Requirement: A runner image holds its selectable JDKs under one directory
Every JDK a job can select SHALL be a directory `/opt/jdks/<id>` on the runner image, holding a full JDK.
The base's Corretto SHALL be present as `corretto-25`, the image's default JDK. Further JDKs SHALL come
only from the image's extension. Selecting a JDK SHALL NOT change the `java` on `PATH`, which the runner
itself runs on.

#### Scenario: An image without an extension holds only the default
- **WHEN** an image is built with no extension
- **THEN** `/opt/jdks` holds exactly `corretto-25`

#### Scenario: The runner keeps its own JDK
- **WHEN** a job selects `temurin-26`
- **THEN** the runner process runs on the `java` on `PATH`, and only the benchmark process and its forks
  run on `/opt/jdks/temurin-26/bin/java`

### Requirement: The image publishes the JDKs it holds
The contract SHALL write, on the booted image, a JDK list naming every `/opt/jdks/<id>` with its release,
vendor and whether it is the default, to `image-builds/<label>/jdks.json` in the deployment's bucket. When
the image is published — after a build or when an interrupted build is adopted — the CLI SHALL read that
list and tag the AMI `baas-jdks` with the ids, joined by `,`, the default first. An image whose list cannot be read SHALL NOT
be published, and SHALL be retired like an image that failed its contract. `baas admin image show` SHALL
list the image's JDKs with their releases and vendors, marking the default. The list SHALL be what the
contract observed, never what the extension's text declares.

#### Scenario: A published image names its JDKs
- **WHEN** an image is built whose extension installs `temurin-26` and `zulu-21`
- **THEN** its AMI carries `baas-jdks=corretto-25,temurin-26,zulu-21`, and `baas admin image show` lists all
  three with their releases and vendors, `corretto-25` marked as the default

#### Scenario: An image without its JDK list is not published
- **WHEN** a build's `jdks.json` is missing when the image would be published
- **THEN** the pointer is not moved, the image is retired, and the command names the missing list

### Requirement: A job chooses the JDK it measures on
`baas run` SHALL accept `--jdk <id>` and SHALL default to the image's default JDK when it is absent. It
SHALL read the runner image's `baas-jdks` tag in the same lookup that resolves the image, and SHALL
refuse an id the tag does not name with exit 2, before any upload, naming the image and listing the ids
it holds. For an image without the tag it SHALL accept no `--jdk` and refuse any, saying the image
predates JDK selection and must be rebuilt; a job without `--jdk` on such an image SHALL run on the `java`
on `PATH`, as before this change, and SHALL carry no `jdkId`, since no id was observed. The job item SHALL
carry the selected id as its `jdkId` tag, from the reservation on, so a job that stores no measurement is
found by its JDK too. The runner SHALL start the benchmark process with
`/opt/jdks/<id>/bin/java`, for `jmh` and `jcstress` alike, and SHALL refuse an id it does not find there
before running anything.

#### Scenario: A JDK the image holds is used
- **WHEN** `baas run --jdk temurin-26 jmh -- MyBenchmark -f 2` runs on an image holding `temurin-26`
- **THEN** JMH and both forks run on `/opt/jdks/temurin-26/bin/java`, and the job carries
  `jdkId=temurin-26`

#### Scenario: An unknown JDK is refused before anything is paid for
- **WHEN** `baas run --jdk temruin-26 jmh -- MyBenchmark` runs against an image holding `corretto-25` and
  `temurin-26`
- **THEN** it exits 2 naming the image and listing `corretto-25 (default), temurin-26`, and nothing is
  uploaded or launched

#### Scenario: No --jdk selects the default
- **WHEN** `baas run jmh -- MyBenchmark` runs
- **THEN** the benchmark runs on `/opt/jdks/corretto-25/bin/java`, and the job carries `jdkId=corretto-25`

#### Scenario: An image from before JDK selection
- **WHEN** `baas run --jdk corretto-25 jmh -- MyBenchmark` runs against an image without a `baas-jdks` tag
- **THEN** it exits 2 saying the image predates JDK selection and naming `baas admin image build`, while the
  same command without `--jdk` is launched, runs on the `java` on `PATH` and carries no `jdkId`

#### Scenario: A job is found by its JDK before and without measurements
- **WHEN** `baas run --jdk temurin-26 jmh -- -lprof` is launched
- **THEN** its job item carries `jdkId=temurin-26` from the reservation on, and `baas jobs list --tag
  jdkId=temurin-26` lists it

#### Scenario: JCStress runs on the selected JDK
- **WHEN** `baas run --jdk temurin-26 jcstress -- -m sanity` completes
- **THEN** JCStress and its forks ran on `/opt/jdks/temurin-26/bin/java`, and its measurement carries
  `jdkId=temurin-26`
