# Verify — multiple-jdks

## 1. Blocking assumptions (2026-10-31)

All in an `amazonlinux:2023` container (linux/amd64) with `java-25-amazon-corretto-devel-25.0.4+7-1.amzn2023.1`
installed — the version `infra/runner-image.yaml` pins; the RPM pulls in the `-headless` package of the same build.

- **1.1 `jcmd` beside `java`: confirmed.** `alternatives` selects
  `/usr/lib/jvm/java-25-amazon-corretto.x86_64/bin/java`; `jcmd` is in the same `bin` and is owned by
  `java-25-amazon-corretto-devel`. `java.home` is `/usr/lib/jvm/java-25-amazon-corretto.x86_64`. The design's open
  question is answered.
- **1.2 `-prof jfr` on that Corretto: works.** `fake-jmh-benchmarks.jar -f 1 -wi 1 -i 1 -prof jfr` wrote a
  `profile.jfr` per benchmark (three), with no "Cannot find jcmd anywhere".
- **1.3 a second JDK under `/opt/jdks`: works for both types.** Temurin 26.0.2.1 unpacked into
  `/opt/jdks/temurin-26` (digest checked), Corretto 25.0.4 still `java` on `PATH`.
  JMH: `/opt/jdks/temurin-26/bin/java -jar fake-jmh-benchmarks.jar … -f 1` printed
  `# VM version: JDK 26.0.2.1 … # VM invoker: /opt/jdks/temurin-26/bin/java`, so the fork ran on Temurin.
  JCStress: `-m sanity -t IntegerIncrementing`, sampling `ps` once a second for 15 s: all 22 `java` processes seen
  were `/opt/jdks/temurin-26/bin/java`. The run exits 1 with `TEST FAILURES`, the same on Corretto: the fixture
  test has forbidden outcomes by design.
- **1.4 AMI tag value: 256 characters, `,` and `.` allowed.** EC2 user guide, *Tag your Amazon EC2 resources* →
  *Tag restrictions*: "Maximum value length – 256 Unicode characters in UTF-8"; "Although EC2 allows for any
  character in its tags, other AWS services are more restrictive" (the cross-service set excludes `,`).
  `baas-jdks` is only written with EC2 `CreateTags` and read with `DescribeImages`, so `,` is safe.

## 3. The base image (2026-10-31)

Rendered base 2975 bytes, contract 3572 bytes, both within the 4096-byte parameter limit
(`RunnerImageRendererTest` pins both). Each rendered step was run in the `amazonlinux:2023` container with the
pinned `java-25-amazon-corretto-devel`:

- **3.2 link and helper:** the base's two lines made `/opt/jdks/corretto-25 -> /usr/lib/jvm/java-25-amazon-corretto.x86_64`;
  its helper step wrote `/usr/local/bin/baas-install-jdk` (mode 755).
- **3.3 helper refusals, on the real Temurin 26.0.2.1 archive:** a wrong digest exited 1 naming the URL and the
  digest, and left `/opt` holding only `jdks/corretto-25` (no temporary directory); the correct digest installed
  `/opt/jdks/temurin-26` and printed its `java -version` (Temurin-26.0.2.1+1); a second install of the same id exited 1
  (`/opt/jdks/temurin-26 already exists`); `java` on `PATH` stayed Corretto 25.0.4. The JRE-without-`jcmd`, malformed-id
  and wrong-digest cases also run in `RunnerImageRendererTest` against local archives (`file://` URLs).
- **3.4 the contract's JDK block** on that image: no failure, ids `corretto-25,temurin-26`, and the list
  `[{"id":"corretto-25","release":"25.0.4","vendor":"Amazon.com Inc.","default":true},{"id":"temurin-26","release":"26.0.2.1","vendor":"Eclipse Adoptium","default":false}]`.
  The S3 upload itself is not exercised here (no credentials in the container); 8.1 covers it.

### Base moved to the newest AL2023 release (user decision, 2026-10-31)

The August parent's repository holds no Corretto 27 (newest 26.0.2; `dnf --releasever=2023.12.20260803`), so with
the user the parent moved to `al2023-ami-2023.12.20261102.0-kernel-6.1-x86_64` (eu-central-1, published 2026-10-30),
keeping Corretto 25 LTS as the default at that release's newest patch, `25.0.5.0.10-1.amzn2023.1`. Corretto 27 is the
starter's commented example, not a default. Rechecked in the container at `--releasever=2023.12.20261102`: the pinned
NVR installs, `/opt/jdks/corretto-25/bin/jcmd` exists, `baas-install-jdk corretto-27` (27.0.1.0.11 tarball, Amazon's
published SHA-256 `2a8a5a83…`) and `temurin-26` install, `java` on `PATH` stays Corretto-25.0.5.0.10, the contract block
lists `corretto-25,corretto-27,temurin-26` with no failure, and the fixture's `-prof jfr` on
`/opt/jdks/corretto-27/bin/java` wrote `profile.jfr` (`# VM version: JDK 27.0.1`).
