# Design

## Context

See proposal.md — Why. The facts the approach rests on, checked on 2026-10-31:

- **One `java` does everything today.** User-data runs `java -jar /app/benchmark-runner.jar`, the runner
  starts the benchmark as `java -jar <jar>` (`BenchmarkProcessBuilder`), and JMH's forks reuse the host
  JVM's `java.home`. User-data reads `java -version` and `java -XshowSettings:properties` for the
  manifest's `jvm` group and the `jdk`/`jvmVendor` tags. Nothing names a JDK path (`git grep` for
  `JAVA_HOME`, `/usr/lib/jvm`, `corretto` finds only the image definition).
- **The headless Corretto package has no `jcmd`**, and JMH's JFR profiler looks for it in its own
  `java.home`: on `baas-development` (image 1.4.0, `c5.2xlarge`) `-prof jfr` failed every iteration with
  `IllegalStateException: Cannot find jcmd anywhere` (job `20261031T180707969Z-0f0b206f`), while JFR
  itself recorded through `-XX:StartFlightRecording` (job `20261031T180832192Z-be4db888`).
- **A second JDK works through an extension.** Image `1.4.0+ext.2d6c1959`, whose extension unpacked
  Temurin 26.0.2.1 into `/opt/temurin-26` and pointed `java` and `jcmd` at it with `alternatives`, passed
  the contract; a plain job and `-prof jfr` both completed on it (jobs `20261031T183059087Z-1face477`,
  `20261031T183059096Z-844d343f`), `profile.jfr` uploaded, `environment.json` and the tags naming Temurin.
- **The contract** checks a `java` on `PATH` at or above the runner's release and constrains neither the
  JVM vendor nor a version above that floor. It runs in Image Builder's test phase on the booted image.
- **Channels that already exist:** the build role writes `image-builds/*` (`ImageBuildRole`); the
  deployer reads the bucket (`s3:Get*`) and tags AMIs (`ImageBuilderService.ensureIdentityTags`); `baas run`
  reads the AMI's tags in its image lookup (`RunAws.currentImage`, `baas-image-version`).
- **JCStress has no `-jvm`** (its `-h` lists `-jvmArgs` and `-jvmArgsPrepend` only); it forks with its
  host's JVM.
- **Hosts vary within one instance type:** six `c5.2xlarge` jobs of one benchmark landed on Xeon 8275CL and
  8124M hosts, 9% apart (`list-runner-profilers` `verify.md` 6.4).

## Goals / Non-Goals

**Goals:**
- One benchmark measured on several JDKs, each job saying truthfully which JDK measured.
- A JDK id refused before anything is paid for when the image does not hold it.
- `-prof jfr` working on the base image.

**Non-Goals:**
- Running several JDKs inside one job on one host.
- Re-measuring on JDKs an image does not hold, or installing a JDK at run time.
- Choosing a JDK for the runner itself.
- Managing perfasm's save locations, and fixing PT2.

## Decisions

### One job measures on one JDK

A comparison of JDKs is one job per JDK, compared with `results diff` and `results query --best-per
jdkId`. One job stays one environment, so the manifest, the tags, the job item and `jobs diff` keep their
shape. The cost is host variance between jobs, which `results diff` now names on its own line.

*Rejected:* one job running every JDK in sequence on one host — removes host variance, but every
artifact and status gains a per-JDK dimension. Revisit when a same-host comparison is needed.

### Every selectable JDK is a directory under `/opt/jdks`, and extensions add them

`/opt/jdks/<id>` is the registry: the base links its Corretto there as `corretto-25`, and an extension adds
more with `baas-install-jdk <id> <url> <sha256>`. Each deployment chooses its set, and the base stays one
JDK.

*Rejected:* a `jdks:` list in `infra/runner-image.yaml` — every new JDK would bump the base for every
deployment and grow every image; SDKMAN in the base — `sdk use` only changes an interactive shell's
`PATH` (cloud-init's is not one), the default `java` would still need `alternatives`, and its catalogue
decides what an identifier installs (on 2026-10-31 its list held Corretto 25.0.5 but not the base's
25.0.4, rejected `25.0.5-amzn` while listing it, and resolved `26.0.2-tem` to `26.0.2+10`); a JDK tarball
seeded per job into S3 and unpacked by user-data — breaks "user-data installs nothing" and moves ~200 MB per
job.

### The base installs Corretto's full JDK, and the helper refuses a JDK without `jcmd`

`java-25-amazon-corretto-devel` is Corretto with its tools, so `jcmd` sits in its own
`java.home` and JMH's JFR profiler finds it (closes PT1). `baas-install-jdk` refuses an archive without
`bin/jcmd`, and the contract checks every `/opt/jdks/<id>` for it, so `-prof jfr` works on every
selectable JDK. The helper is a short shell script rendered by `RunnerImageRenderer` into the base
component: `curl`, `sha256sum -c`, `tar --strip-components=1` into a temporary directory, the checks, then
a rename to `/opt/jdks/<id>`.

### The runner stays on `java` from `PATH`; the benchmark process gets the selected JDK

`--jdk <id>` travels to the runner as a runner option (`RunnerArgs`), and the runner builds the benchmark
command with `/opt/jdks/<id>/bin/java` instead of `java`. JMH's forks and JCStress's follow their host's
`java.home`, and `-f 0` runs inside the selected JDK too. The runner itself still needs the release it is
compiled for, which the contract keeps checking on `PATH`; a benchmark may now run on an older JDK.

*Rejected:* injecting JMH's `-jvm` — covers only JMH's forks, not JCStress or the JMH host, and the
host's own JVM would still be Corretto.

### JMH's `-jvm` is managed

A user `-jvm` after `--` sends the forks to a JVM the job does not record. It joins the managed set in
`BenchmarkArgs`, refused in every spelling by the CLI and by the runner, and the refusal names `--jdk`.
Every other JMH and JCStress option still passes through (each was checked against "would a user value
break BaaS"); perfasm's `savePerfTo`/`savePerfBinTo`/`saveLogTo` and their `…ToFile` forms would lose their
files outside the uploaded tree, and are recorded as a known gap rather than managed: no allowed instance
type exposes the hardware counters perfasm needs, so the injection could not be tested.

### The contract records what it found; publishing turns it into an AMI tag; `run` checks the tag

The contract scans `/opt/jdks` on the booted image and writes `image-builds/<label>/jdks.json`
(`[{id, release, vendor, default}]`, the default first). `ImageBuilderService.publish` reads it and tags
the AMI `baas-jdks=<ids>`; a missing list retires the image like a contract failure, and adoption of an
interrupted build goes through the same step. `baas run` reads the tag in the image lookup it already makes
and refuses an unknown id before the JAR upload. `baas admin image show` reads `jdks.json` for releases and
vendors. The contract fails a bake whose ids, joined, would not fit an AMI tag value (256 characters), and
an id outside `[a-z0-9][a-z0-9.-]*` or longer than 32.

*Rejected:* the CLI parsing the extension for `baas-install-jdk` lines — reports a declaration, not the
image, and a loop or a variable defeats it; an SSM parameter — a second store to keep in step with the
AMI; parsing Image Builder's logs — brittle; the runner alone checking — a typo costs a boot.

### An image from before JDK selection runs as before, with no `jdkId`

CI's e2e runs the PR's CLI against the default deployment's current image, which has neither `/opt/jdks`
nor `baas-jdks` until it is rebuilt. On such an image `baas run` refuses `--jdk`, passes no `--jdk` to the
runner, and the job runs on `java` from `PATH`; user-data then fills `jdk` from that `java` with `id` and
`home` empty, and the job carries no `jdkId` — absent says unknown, as `commit` and `branch` do.

### Names are unified on JDK, and existing data is erased instead of mapped

JDK names what is installed, chosen and ran: `--jdk`, the tags `jdkId`, `jdkVersion` (was `jdk`) and
`jdkVendor` (was `jvmVendor`), the manifest groups `jdk` (was `jvm`; `name` → `vmName`) and `runnerJdk`.
JVM is left to runtime behaviour — the parked `jvm-flags.json` — and to JMH's own `-jvm`/`-jvmArgs`. Six
of the seven `jdk` fields identify the distribution and its build; `vmName` is named for the one that
does not.

Tags are the only query surface, and no BaaS role can rewrite stored measurements (the operator updates
only `JOB` items, the runner only writes new `RESULT#` rows, the deployer cannot read the table). So
instead of a migration or an alias layer, both deployments' current data is erased at rollout, and 9.0.0
— already pending — carries the rename. Running setup with 9.0.0 raises each deployment's CLI version
floor, so an older CLI cannot query the new names.

*Rejected:* keeping every name and writing the rule down (U1) — cheaper only while old data must be
kept; unifying on JVM (U3) — `baas run --jvm` would sit beside JMH's `-jvm`, which is refused.

### `environment.json` schema 8 records the selected JDK and the runner's

`jdk` {`id`, `home`, `version`, `release`, `vendor`, `vendorVersion`, `vmName`} comes from
`/opt/jdks/<id>/bin/java`; `runnerJdk` {`release`, `vendorVersion`} from `java` on `PATH`. The list of the
image's other JDKs is not recorded: they did not measure, and `baas-jdks`/`jdks.json` hold it per image.
The CLI passes the id into user-data, which captures every value into a shell variable as today.

### Comparability with existing results

None is kept: existing results are erased. Within the new data, a job without `--jdk` measures on
Corretto 25 as today, from the `-devel` package instead of `-headless`, and a new `imageVersion`. Since
the AMI is rebuilt anyway, the parent also moves to the newest AL2023 release (`2023.12.20261102.0`,
decided with the user 2026-10-31), which brings Corretto 25.0.5.0.10 (was 25.0.4+7), a newer kernel and
newer packages — so the score comparison against a pre-change run (a task) compares the whole base move,
not only the package split. Corretto 25 stays the default: it is the LTS, and the runner's compile
release.

### Corretto 27 is an option, not a default

The newest Corretto (27, non-LTS, updates until 28 ships) is offered as the extension starter's
commented example, `baas-install-jdk corretto-27 <Amazon's tarball> <its SHA-256>`, so a first build is the
base alone and one uncommented step adds it. The tarball, not the `java-27-amazon-corretto-devel` RPM: a
second Corretto RPM becomes the `alternatives` default and moves the runner's own `java`. (Corretto 27 is
also absent from the August parent's repository; AL2023 locks an AMI to its release's package snapshot.)

## Risks / Trade-offs

- **[Host variance can exceed a JDK difference]** → the separate CPU-model warning in `results diff`; repeat
  jobs until both sides share a `cpuModel`.
- **[An extension's JDK archive moves]** → `baas-install-jdk` checks the digest and fails the bake; nothing
  is unpacked from an unverified archive. A vendor removing a release breaks a later rebuild, as for
  async-profiler.
- **[Image size]** → each JDK adds ~200 MB to the one retained snapshot (~$0.01/month each).
- **[JDKs older than the runner's release]** → allowed for the benchmark; the runner keeps its own
  `java`. A benchmark JAR compiled for a newer release than the selected JDK fails at start, loudly.
- **[async-profiler and JFR on other VMs]** → HotSpot-based JDKs (Corretto, Temurin, Zulu, Liberica,
  GraalVM) support both; Eclipse OpenJ9 (Semeru) and Azul Prime do not. The contract checks only that the
  JDK runs and has `jcmd`; a profiler failing on such a JDK fails its job loudly.
- **[Erasing data]** → both deployments' results and job prefixes go. `baas admin deployment export`
  before erasing keeps a file of anything worth keeping.
- **[`baas-jdks` tag limit]** → about 15–20 ids per image; the contract fails the bake beyond it.

## Migration Plan

1. Merge into `next-release`, release 9.0.0.
2. Per deployment (`baas-381492019823`, `baas-development`): export if wanted, erase the results table's
   items and the `jobs/` prefix (or tear down and set up again), run setup with 9.0.0 (raises the version
   floor), then `baas admin image build` with the new base. On `baas-development`, replace the Temurin
   extension (`1.4.0+ext.2d6c1959`, `alternatives`) with one `baas-install-jdk temurin-26 …` line.
3. Rollback: none for the data; the code reverts by releasing a previous CLI, which the raised floor then
   refuses — a rollback means a new release.

## Open Questions

- Does `java-25-amazon-corretto-devel` put `jcmd` in the same `bin` as `java` (`java.home/bin`)? Expected
  from the package layout; task 1.1 confirms it on the parent AMI before the base changes.

## Resolved Questions

All on 2026-10-31, with the user:
- *One job per JDK or all JDKs in one job?* One job per JDK, with a separate CPU-model warning.
- *How do JDKs reach the image?* Extensions, under `/opt/jdks/<id>`, through `baas-install-jdk`; Corretto
  stays an RPM. SDKMAN in the base was considered and rejected (above).
- *How does `baas run` know the ids?* Contract → `jdks.json` → AMI tag `baas-jdks` → checked before upload.
- *JMH's `-jvm`?* Managed. JCStress has no equivalent (its `-h`).
- *Other options?* All pass through; perfasm's save locations are a known gap; `-foe` passes through and
  PT2 widens to partial failures (exit codes and result files under both `-foe` values checked locally).
- *Tag for the id?* `jdkId`, always set (default `corretto-25`), absent only on an image from before JDK
  selection.
- *Manifest shape and names?* `jdk` and `runnerJdk`, schema 8; names unified on JDK, data erased, 9.0.0.
- *One change or two?* One; the naming is its first section.
