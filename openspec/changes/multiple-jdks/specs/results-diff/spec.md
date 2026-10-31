# Spec Delta

## MODIFIED Requirements

### Requirement: Other environment differences are warned about
When the two jobs' measurements disagree on `imageVersion`, `jdkId`, `jdkVersion`, `jdkVendor` or
`cpuArch`, the command SHALL write one warning to standard error naming each differing tag with both
values and the `baas jobs diff <jobA> <jobB>` command that compares the full environments. When they
disagree on `cpuModel`, it SHALL write a separate warning, before that one, naming both CPU models and
saying that a hardware difference can exceed the one being compared: the JDK tags differ on purpose in a
comparison of JDKs, and a CPU difference must not be lost among them. It SHALL
NOT read either job's `environment.json`, and the warning SHALL NOT change any row.

#### Scenario: A different image is warned about
- **WHEN** job A carries `imageVersion=1.3.0` and job B `imageVersion=1.4.0`
- **THEN** standard error names `imageVersion: 1.3.0 → 1.4.0` and `baas jobs diff <jobA> <jobB>`, and
  standard output is unchanged by it

#### Scenario: A different CPU is warned about on its own
- **WHEN** job A ran `--jdk corretto-25` on an Intel Xeon Platinum 8275CL and job B `--jdk temurin-26` on an
  8124M
- **THEN** standard error has one warning naming both CPU models, and another naming `jdkId`, `jdkVersion`
  and `jdkVendor`, and standard output is unchanged by either
