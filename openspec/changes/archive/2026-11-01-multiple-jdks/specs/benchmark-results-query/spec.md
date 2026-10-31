# Spec Delta

## MODIFIED Requirements

### Requirement: Results are filterable by any tag
`baas results query --tag <key>=<value>` SHALL return measurements carrying that tag, for any tag key, whether
or not the key is in the known-key vocabulary.

#### Scenario: Known tag filters
- **WHEN** results tagged `jdkVersion=25.0.4` exist and `--tag jdkVersion=25.0.4` is queried
- **THEN** those results are returned

#### Scenario: Custom tag filters
- **WHEN** results tagged `experiment=gc-tuning` exist and `--tag experiment=gc-tuning` is queried
- **THEN** those results are returned

#### Scenario: Repeated tag options combine conjunctively
- **WHEN** `--tag jdkVersion=25.0.4 --tag cpuArch=aarch64` is given
- **THEN** only measurements carrying both tags are returned
