## MODIFIED Requirements

### Requirement: The contract fails a bake that breaks a BaaS workflow
The contract SHALL verify, on an instance booted from the newly built image, that: a `java` is on
`PATH` and its specification version is at least the Java version the runner is compiled for; `aws`
runs; async-profiler's library and `asprof` are present at the path the runner defaults to; `perf`
runs and matches the running kernel; `amazon-ssm-agent` is installed and enabled to start at boot;
`/etc/baas-image-version` exists; `kernel.perf_event_paranoid` is at most 1; and
`kernel.kptr_restrict` is 0. When any check fails, the build SHALL fail, the pointer SHALL remain
unchanged, and the failure SHALL name the check. The contract SHALL NOT constrain the JVM vendor, a
JVM version above the floor, the SSM agent's version, the transparent hugepage mode or the swap state.

#### Scenario: An extension that removes async-profiler fails the bake
- **WHEN** an extension deletes the async-profiler deployment and an image is built
- **THEN** the build fails naming the async-profiler check, and `/<prefix>/runner/ami-id` still names
  the previous image

#### Scenario: An extension that replaces the JVM with another of the same version
- **WHEN** an extension removes Corretto and installs another vendor's Java 25
- **THEN** the build succeeds

#### Scenario: A JVM below the floor fails the bake
- **WHEN** an extension leaves only a Java 17 on `PATH`
- **THEN** the build fails naming the Java check

#### Scenario: A kernel upgrade that strands perf fails the bake
- **WHEN** an extension upgrades the kernel after the base installed `perf`
- **THEN** the build fails naming the `perf` check

#### Scenario: Measurement choices are the extension's to change
- **WHEN** an extension sets transparent hugepages to `madvise`
- **THEN** the build succeeds, and jobs on the image record `madvise` in `environment.json`

#### Scenario: An extension that disables the SSM agent fails the bake
- **WHEN** an extension disables or removes `amazon-ssm-agent` and an image is built
- **THEN** the build fails naming the SSM agent check, and `/<prefix>/runner/ami-id` still names the
  previous image
