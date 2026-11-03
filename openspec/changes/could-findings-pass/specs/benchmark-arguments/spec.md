# Spec Delta

## ADDED Requirements

### Requirement: `baas run --dry-run` checks the arguments on this machine and launches nothing
`baas run --dry-run` SHALL apply every check `baas run` makes on the arguments after `--` and on the
benchmark JAR's path, and then run the benchmark JAR's own listing on this machine with the same
arguments followed by `-l`. It SHALL make no AWS call and read no deployment, so it needs neither a
configured deployment nor credentials. It SHALL exit 0 and print the benchmarks or tests the listing
names when the listing succeeds and names at least one. It SHALL exit 2, printing the tool's own
output, when the listing exits non-zero, and exit 2 saying nothing matches when the listing names
none. Every outcome SHALL say that nothing was launched. `--dry-run` SHALL be refused with exit 2
beside `--format json`. With `-lprof` alone after `--`, it SHALL run nothing locally and say that the
profiler listing runs as a job on the runner.

#### Scenario: Valid arguments list what would run
- **WHEN** `baas run --dry-run --benchmark-jar b.jar jmh -- Summing -f 1 -wi 1` is run
- **THEN** it prints the matching benchmark names, says nothing was launched, exits 0, and makes no
  AWS call

#### Scenario: A mistyped option is caught locally
- **WHEN** `baas run --dry-run --benchmark-jar b.jar jmh -- -f 1 -wii 1` is run
- **THEN** it exits 2, showing JMH's own parse error

#### Scenario: A pattern that matches nothing is caught locally
- **WHEN** `baas run --dry-run --benchmark-jar b.jar jmh -- NoSuchBenchmark` is run
- **THEN** it exits 2, saying no benchmark matches

#### Scenario: JCStress arguments are checked the same way
- **WHEN** `baas run --dry-run --benchmark-jar stress.jar jcstress -- -t NoSuchTest` is run
- **THEN** it exits 2, saying no test matches

#### Scenario: Managed arguments are still refused first
- **WHEN** `baas run --dry-run --benchmark-jar b.jar jmh -- -rf csv` is run
- **THEN** it exits 2 with the managed-argument refusal, and the listing does not run

#### Scenario: A dry run needs no deployment
- **WHEN** `baas run --dry-run --benchmark-jar b.jar jmh -- Summing` is run on a machine with no
  configured deployment and no AWS credentials
- **THEN** it lists the matching benchmarks and exits 0

#### Scenario: A JSON summary is refused
- **WHEN** `baas run --dry-run --format json …` is run
- **THEN** it exits 2 and runs nothing
