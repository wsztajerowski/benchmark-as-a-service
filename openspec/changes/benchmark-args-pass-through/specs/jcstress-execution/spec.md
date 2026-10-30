# Spec Delta

## ADDED Requirements

### Requirement: A JCStress job's mode is JCStress's own `-m`
A JCStress job's mode SHALL be selected with JCStress's own `-m <mode>`, passed after `baas run
jcstress … --` and forwarded to JCStress unchanged like every other benchmark argument. When no mode is
given, JCStress SHALL receive none, so it applies its own default. Neither `baas run` nor the runner
SHALL validate the value: JCStress owns its mode vocabulary. The runner SHALL NOT offer a mode option
of its own, and `baas run` SHALL NOT refuse `-m`.

#### Scenario: Sanity mode is requested
- **WHEN** a job is launched as `baas run jcstress … -- -m sanity`
- **THEN** JCStress runs in sanity mode, and the job completes and stores its JCStress item as any
  JCStress job does

#### Scenario: No mode is requested
- **WHEN** a JCStress job is launched without `-m`
- **THEN** JCStress receives no mode argument and runs in its default mode, so the job is
  comparable with JCStress jobs recorded before

#### Scenario: An unknown mode fails the job visibly
- **WHEN** a JCStress job is launched with a mode JCStress does not recognise
- **THEN** the job ends failed, with JCStress's own error in `jcstress-output.txt`, rather than
  running in some other mode

#### Scenario: The former runner option is not translated
- **WHEN** `baas run jcstress … -- --mode sanity` is run
- **THEN** `--mode sanity` reaches JCStress unchanged, and the job ends failed with JCStress's own error,
  rather than being read as a mode

## REMOVED Requirements

### Requirement: A JCStress job can select its mode
**Reason**: The runner no longer has options of its own for JCStress. `--mode` was a runner-side rename
of JCStress's `-m`, made only because `-m` was once the runner's Mongo connection string. With the
benchmark's arguments passed through, `-m` reaches JCStress unchanged, and the refusal that pointed
users from `-m` to `--mode` would now refuse JCStress's own spelling.
**Migration**: Replace `-- --mode <mode>` with `-- -m <mode>`.
