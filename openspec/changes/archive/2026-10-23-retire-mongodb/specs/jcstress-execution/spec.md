## MODIFIED Requirements

### Requirement: A JCStress job can select its mode
The runner's JCStress subcommand SHALL accept a `--mode <mode>` option and pass its value to JCStress
as JCStress's run mode. When `--mode` is not given, the runner SHALL pass no mode at all, so that
JCStress applies its own default exactly as it did before this option existed. The runner SHALL NOT
validate the value itself: JCStress owns its mode vocabulary. The option SHALL have no short form, and
`-m` SHALL NOT be a runner option at all. `baas run` SHALL refuse a `-m` among the benchmark's
arguments before any AWS call, naming `--mode` as what to type instead.

#### Scenario: Sanity mode is requested
- **WHEN** a job is launched as `baas run jcstress … -- --mode sanity`
- **THEN** JCStress runs in sanity mode, and the job completes and stores its JCStress item as any
  JCStress job does

#### Scenario: No mode is requested
- **WHEN** a JCStress job is launched without `--mode`
- **THEN** JCStress receives no mode argument and runs in its default mode, so the job is
  comparable with JCStress jobs recorded before the option existed

#### Scenario: An unknown mode fails the job visibly
- **WHEN** a JCStress job is launched with a mode JCStress does not recognise
- **THEN** the job ends failed, with JCStress's own error in `jcstress-output.txt`, rather than
  running in some other mode

#### Scenario: The short option is refused before launch
- **WHEN** `baas run jcstress … -- -m sanity` is invoked
- **THEN** it exits non-zero naming `--mode`, before any upload or launch

#### Scenario: The short option keeps its meaning
- **WHEN** `-m` is passed to the runner directly
- **THEN** it is still not read as a JCStress mode: the runner rejects it as an unknown option and
  runs no benchmark
