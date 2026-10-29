# Spec Delta

## MODIFIED Requirements

### Requirement: Commands are nouns with verbs, and only a one-noun verb has a top-level alias
Every canonical command SHALL have the form `baas [admin] <noun> <verb>`. The operator nouns SHALL be
`jobs` (`run`, `list`, `show`, `diff`, `download`, `terminate`, `connect`), `results` (`query`, `diff`)
and `config` (`show`, `set`, `sync`). A top-level alias SHALL exist only for a verb that belongs to
exactly one noun, and the aliases SHALL be `baas run` for `baas jobs run` and `baas query` for `baas
results query`. A noun given without a verb SHALL print its usage and do nothing else. No alias SHALL
exist for a verb shared by several nouns.

#### Scenario: An alias runs its canonical command
- **WHEN** `baas run jmh -- MyBenchmark -f 1` is invoked
- **THEN** it behaves exactly as `baas jobs run jmh -- MyBenchmark -f 1`

#### Scenario: A bare noun prints usage
- **WHEN** `baas jobs` is invoked with no verb
- **THEN** its verbs are listed and nothing is executed

#### Scenario: A shared verb has no alias
- **WHEN** `baas list`, `baas show` or `baas diff` is invoked
- **THEN** picocli reports an unknown command error

#### Scenario: Connect has no top-level alias
- **WHEN** `baas connect` is invoked
- **THEN** picocli reports an unknown command error, and `baas jobs connect` is the only form
