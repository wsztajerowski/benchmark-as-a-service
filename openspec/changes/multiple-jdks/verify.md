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
