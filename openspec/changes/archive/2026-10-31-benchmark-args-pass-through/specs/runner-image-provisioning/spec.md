# Spec Delta

## MODIFIED Requirements

### Requirement: The image declares the kernel tunables that affect measurement
`infra/runner-image.yaml` SHALL declare `perf_event_paranoid`, `kptr_restrict`, the transparent hugepage
mode, and the swap state, and the base SHALL apply them so they are in effect at boot. These values
SHALL NOT be left to the parent image's defaults. An extension MAY change the transparent hugepage mode
and the swap state; `perf_event_paranoid` and `kptr_restrict` are additionally bounded by the contract.

#### Scenario: Tunables are declared alongside tool versions
- **WHEN** `infra/runner-image.yaml` is read
- **THEN** it declares `perf_event_paranoid`, `kptr_restrict`, transparent hugepage mode, and swap state

#### Scenario: Tunables are in effect on a launched runner
- **WHEN** an instance launched from an image built without an extension is inspected
- **THEN** the running kernel reports the values declared in `infra/runner-image.yaml`

#### Scenario: async-profiler can walk kernel stacks
- **WHEN** a `jmh` benchmark profiled with `-prof async` runs on the current AMI
- **THEN** profiling succeeds without a permissions error from `perf_event_open`

### Requirement: The runner installs no tooling at run time
The user-data script SHALL NOT invoke `yum update`, install a JDK, or download async-profiler. Every tool
the benchmark job requires SHALL already be present in the AMI.

#### Scenario: User-data performs no package deployment
- **WHEN** `UserDataScriptBuilder.build(...)` output is decoded
- **THEN** it contains no `yum` invocation and no async-profiler download

#### Scenario: Baked tooling is present and pinned
- **WHEN** an instance launched from an image built without an extension is inspected
- **THEN** `java -version` reports the Corretto version declared in `infra/runner-image.yaml`, and
  `/app/async-profiler/lib/libasyncProfiler.so` exists at the declared version

#### Scenario: Baked async-profiler is at the path the runner defaults to
- **WHEN** a `jmh` job names `-prof async` without a library path
- **THEN** the library path the runner injects resolves to the baked async-profiler
