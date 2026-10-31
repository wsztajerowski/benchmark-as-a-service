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

## 8. End to end on `baas-development` (2026-10-31, eu-central-1, all `c5.2xlarge`)

12 paid jobs of the 20 the user allowed. The worktree's reactor CLI and runner (`--runner-jar`) for the new image;
the pre-change build (`c85bc8f`, its own CLI and runner) for the baseline. Benchmark:
`Summing_Array.sum -f 1 -wi 2 -w 2s -i 5 -r 2s`, as in `list-runner-profilers` 6.4.

- **8.1 image:** the Temurin experiment extension was replaced (first by none, for the baseline image 1.4.0
  `ami-0fb6489199a99e9c8`), then the new base was built with an extension of two `baas-install-jdk` lines
  (`corretto-27` from Amazon's 27.0.1.0.11 tarball, `temurin-26`): `1.5.0+ext.e383db2d`, `ami-061cc6355f7ae8dc4`,
  parent `ami-0c5768ce3a885d023` (al2023 2023.12.20261102.0). `admin image show` listed
  `corretto-25 25.0.5 Amazon.com Inc. (default)`, `corretto-27 27.0.1 Amazon.com Inc.`,
  `temurin-26 26.0.2.1 Eclipse Adoptium`; the AMI carries `baas-jdks=corretto-25,corretto-27,temurin-26`, and
  `image-builds/1.5.0+ext.e383db2d/` holds the list. The replaced image was retired.
- **8.2 tags and manifest:** every job without `--jdk` carries `jdkId=corretto-25 jdkVersion=25.0.5
  jdkVendor=Amazon.com Inc.`; `--jdk temurin-26` (`…17de370f`) carries `temurin-26 26.0.2.1 Eclipse Adoptium`, and its
  `environment.json` (schema 8) records `jdk.id temurin-26`, `jdk.home /opt/jdks/temurin-26`, `vendorVersion
  Temurin-26.0.2.1+1`, and `runnerJdk` `25.0.5` / `Corretto-25.0.5.0.10`. `jobs show` prints `jdk  temurin-26 ·
  Temurin 26.0.2.1+1 (Eclipse Adoptium) · vmName …` and `runnerJdk  Corretto 25.0.5.0.10`. `results diff` of a
  Corretto and the Temurin job on the same CPU (`…e4806334` → `…17de370f`) warned only about `jdkId`, `jdkVersion`,
  `jdkVendor`; across CPUs (`…ac812cb2`, 8124M → 8275CL) it first warned about the CPU models on their own line, then
  the JDK tags — where the table called size=2 a +10.3 % "improvement" that is the host, not the JDK.
- **8.3 `-prof jfr`** (`-p size=1 -i 2`): `corretto-25` `…608cc45c`, `temurin-26` `…678a2263`, and also `corretto-27`
  `…3819e5d7` — each stored its measurement with `profilers=jfr` and uploaded `…-size-1/profile.jfr`
  (243–257 KB); no "Cannot find jcmd" in any output. PT1 is closed on the real image.
- **8.4 refusals:** `--jdk temruin-26` exited 2 with `Runner image 1.5.0+ext.e383db2d (ami-061cc6355f7ae8dc4) holds no
  JDK 'temruin-26'. Its JDKs: corretto-25 (default), corretto-27, temurin-26.` and the project prefix held 21 objects
  before and after; `-- MyBenchmark -jvm /x` exited 2 naming `--jdk <id>`.
- **8.5 JCStress:** `--jdk temurin-26 jcstress -- -m sanity` (`…44ca9f1e`) completed with `jdkId=temurin-26`,
  `jdk.home /opt/jdks/temurin-26`, runner on Corretto 25.0.5.0.10.
- **Also:** `--jdk corretto-27` on the comparison benchmark (`…d4559973`, 8275CL): 513,417,933 / 449,167,993 ops/s.
- **8.6 comparability**, default JDK, before (1.4.0: August parent, `-headless` 25.0.4) and after (1.5.0: November
  parent, `-devel` 25.0.5):

  | Job | Base | CPU | size=1 (ops/s) | size=2 (ops/s) |
  |---|---|---|---|---|
  | before `…2dd31d8e` | 1.4.0 | 8275CL | 513,339,544 ± 279,537 | **598,870,972** ± 200,815 |
  | before `…abafadb0` | 1.4.0 | 8275CL | 513,301,686 ± 381,529 | 448,982,680 ± 350,882 |
  | before `…fb8da6dd` | 1.4.0 | 8124M | 467,842,674 ± 358,090 | 406,901,401 ± 504,821 |
  | after `…ac812cb2` | 1.5.0+ext | 8124M | 467,886,250 ± 306,868 | 407,035,963 ± 167,287 |
  | after `…e4806334` | 1.5.0+ext | 8275CL | 513,245,136 ± 479,099 | 449,143,885 ± 155,907 |
  | after `…807fdcc4` | 1.5.0+ext | 8275CL | 513,083,298 ± 256,751 | 448,925,238 ± 394,897 |

  Per CPU model, before and after agree within 0.05 % (8275CL size=1 513.08–513.34M, size=2 448.93–449.14M;
  8124M 467.84/467.89M and 406.90/407.04M). The one outlier, size=2 of `…2dd31d8e` (+33 %), ran on the *pre-change*
  base and code, with an error as tight as the others: a different steady state of the fixture (a JIT/alignment
  mode, like `Incrementing_Synchronized`'s), not something this change introduced. No score moved with it.
- **8.7, 8.8: not done yet** — 8.7 needs a deployer for a second deployment name (an IAM administrator), 8.8 a PR.
