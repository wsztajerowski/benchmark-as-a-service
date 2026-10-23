## ADDED Requirements

### Requirement: The core stack records the CLI version that last set it up
`cf-template-core.yaml` SHALL declare a String parameter `BaasCliVersion`, defaulting to empty and
accepting only empty or a numeric `<major>.<minor>.<patch>` version. It SHALL create no resource and
grant nothing. Only `baas admin deployment setup` SHALL submit a value for it, and only when the
running CLI is a released build with a numeric version; every other stack update — `baas admin image
build`'s included — SHALL carry the deployed value forward unchanged. Empty means no version is
recorded.

#### Scenario: Setup from a released CLI records its version
- **WHEN** `baas admin deployment setup` from release `8.2.0` creates or updates a stack
- **THEN** the stack's `BaasCliVersion` is `8.2.0`

#### Scenario: An image build leaves the recorded version alone
- **WHEN** `baas admin image build` updates a stack whose `BaasCliVersion` is `8.1.0`
- **THEN** the stack's `BaasCliVersion` is still `8.1.0`

#### Scenario: A stack from before the parameter gets one on its next update
- **WHEN** a stack created before `BaasCliVersion` existed is updated by `baas admin image build`
- **THEN** the update succeeds and the stack's `BaasCliVersion` is empty

#### Scenario: A malformed version is rejected by the stack
- **WHEN** a stack submission names `BaasCliVersion` as anything other than empty or `x.y.z`
- **THEN** CloudFormation rejects the submission
