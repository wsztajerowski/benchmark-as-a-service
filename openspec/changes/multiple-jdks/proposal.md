# Proposal

## Why

The point of BaaS is comparing measurements, and "how does this benchmark do on Corretto, Temurin and
Zulu?" is a comparison it cannot make: the runner image has exactly one JDK, the runner, JMH and every
fork use whatever `java` is on `PATH`, and the environment and tags describe that one JVM. JMH's own
`-jvm` after `--` can point the forks elsewhere today, and then the tags silently describe a JVM that
did not measure. Separately, the base's `java-25-amazon-corretto-headless` has no `jcmd`, so JMH's
`-prof jfr` fails every iteration on the image (PT1, proven on `baas-development` 2026-10-31).

## What Changes

- **BREAKING — JDK naming unified, existing data erased.** Tags `jdk` → `jdkVersion` and
  `jvmVendor` → `jdkVendor`; `environment.json` group `jvm` → `jdk` (its `name` → `vmName`), schema 8.
  JDK names what is installed, chosen and ran; JVM is left to runtime behaviour and to JMH's own options.
  Both deployments' current measurements and jobs are erased at rollout, so no reader maps old names.
  Ships in 9.0.0.
- **The base installs Corretto 25 as `java-25-amazon-corretto-devel`** (same build, with `jcmd`), and links
  it as `/opt/jdks/corretto-25`. `imageVersion` is bumped.
- **The base ships `baas-install-jdk <id> <url> <sha256>`**, which an extension uses to add a JDK: it
  downloads, verifies the checksum, unpacks into `/opt/jdks/<id>`, and refuses an existing id, a
  checksum mismatch or a JDK without `bin/jcmd`.
- **The contract records the image's JDKs.** It checks every `/opt/jdks/<id>` on the booted image and
  writes `image-builds/<label>/jdks.json`; publishing reads it, refuses an image without it, and tags
  the AMI `baas-jdks=<ids>`. `baas admin image show` lists them.
- **`baas run --jdk <id>`** (default `corretto-25`) runs the benchmark on that JDK: the runner stays on
  `java` from `PATH` and starts `java -jar <benchmark>` with `/opt/jdks/<id>/bin/java`, which JMH's forks
  and JCStress follow. An id the image's `baas-jdks` tag does not name is refused with exit 2 before any
  upload, listing the ids. The `-lprof` listing job takes `--jdk` too.
- **JMH's `-jvm` becomes managed** and is refused after `--` in every spelling, so `--jdk` is the only way
  to choose the measuring JVM. JCStress has no such option.
- **The selected JDK is what is recorded.** `environment.json`'s `jdk` group gains `id` and `home`, a
  `runnerJdk` group records the runner's own JDK, and every measurement carries `jdkId` (`corretto-25`
  when no `--jdk` was given), `jdkVersion` and `jdkVendor` from the selected JDK — all reserved.
- **`baas results diff` warns about a different CPU model on its own line**, apart from the intended
  `jdkId`/`jdkVersion`/`jdkVendor` differences: one job per JDK can land on different host CPUs.

## Capabilities

### New Capabilities

- `jdk-selection`: JDKs installed under `/opt/jdks`, the image publishing their ids, and a job choosing
  the JDK it measures on.

### Modified Capabilities

- `benchmark-arguments`: JMH's `-jvm` is managed; the listing job runs on the selected JDK.
- `results-store-schema`: tags `jdkVersion`, `jdkVendor` and `jdkId` replace `jdk` and `jvmVendor`.
- `runner-image-provisioning`: the base installs Corretto with `jcmd` and the `baas-install-jdk` helper,
  the contract checks and records `/opt/jdks`, and `environment.json` schema 8 records the selected JDK
  and the runner's.
- `results-diff`: a CPU model difference is warned about on its own.
- `job-tracking`: `baas jobs show` reads the renamed `jdk` group.
- `benchmark-results-query`: the tag-filter examples use `jdkVersion`.

## Impact

- **Code:** `baas-model` (`TagKeys`, `BenchmarkArgs` managed `-jvm`, `RunnerArgs` for `--jdk`);
  `baas-cli` (`RunCommand` `--jdk` and the refusal, `JobLaunch`, `UserDataScriptBuilder` manifest and tags,
  `RunnerImageRenderer` base/helper/contract, `ImageBuilderService` publish tag, `RunnerImage`,
  `ImageShowSubcommand`, `EnvironmentManifest`/`EnvironmentView`, `ResultsDiff`); `benchmark-runner`
  (`--jdk` → the benchmark process's `java`); `infra/runner-image.yaml`.
- **Docs:** `.claude/rules/results-store.md`, `runner-image.md`, `benchmark-runner.md`, `user-data.md`;
  `CLAUDE.md` (Accepted risks quote `jdk: 25.0.4 → 25.0.3`); `README.md`; `docs/diagrams/baas-run.mmd`,
  `baas-build-image.mmd`, `baas-results.mmd`.
- **Data:** both deployments' results tables and job prefixes are erased at rollout; old rows and
  manifests would answer to the old names.
- **Cost:** no new AWS resources and no standing cost beyond the image snapshot growing by each extra
  JDK an extension installs (~200 MB, about $0.01/month each). A multi-JDK comparison costs one ordinary
  job per JDK.
- **IAM:** none. The build role already writes `image-builds/*`, the deployer reads the bucket and tags
  AMIs, and `baas run` already reads AMI tags.
- **Deliberately not changed:** the runner's own JVM (still `java` on `PATH`, at least the runner's
  release); one job is one environment; one image per deployment, rebuilt in place; user-data installs
  nothing; the extension stays a free-form AWSTOE document; every other JMH and JCStress option passes
  through. perfasm's save locations (`savePerfTo`, `savePerfBinTo`, `saveLogTo` and their `…ToFile`
  forms) stay unmanaged — a known gap, untestable without hardware counters on an allowed instance type.
- **Findings:** closes PT1. Widens PT2 to jobs where only some benchmarks failed (`-foe false`); its fix
  stays separate.
