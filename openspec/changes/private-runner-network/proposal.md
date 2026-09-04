## Why

Benchmark runners launch into a public subnet with a public IP and `0.0.0.0/0` egress, because they need
to reach yum repositories, GitHub Releases, MongoDB Atlas, and the EC2 API. Once a prebaked AMI supplies
the tooling and DynamoDB replaces Atlas, none of those reasons survive — and S3 and DynamoDB both have
*free* gateway endpoints.

That makes full network isolation achievable at **zero standing cost**, overturning the recorded position
that a private subnet requires NAT or PrivateLink at ~$32/month. It also closes open finding S8, where a
shared-tag `TerminateInstances` grant let any runner terminate any other.

## What Changes

- New private `RunnerSubnet` (`MapPublicIpOnLaunch: false`, no internet gateway route) with its own route
  table carrying the S3 and DynamoDB gateway endpoints. `Ec2ProvisioningService` launches runners there.
- The existing `PublicSubnet` is **retained but demoted** to Image Builder build instances only. The
  builder and the runner have opposite requirements by nature: the runner needs no internet *because* the
  builder had it. Building privately would additionally require `ssm`, `ssmmessages`, and `ec2messages`
  interface endpoints (~$7/month each, since Image Builder orchestrates through SSM) and would still fail
  on its own terms, because a build instance with no egress cannot fetch the tools it is baking.
- **BREAKING**: `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, `ExistingSecurityGroupId`, and the
  `CreateNetworking` condition are removed; every networking resource becomes unconditional. The CLI
  cannot verify that a foreign VPC provides the required gateway endpoints, and the failure mode — runs
  that hang until the wall-clock cap — is close to undiagnosable from a terminated instance.
- Security group egress narrows to the AWS-managed prefix lists `com.amazonaws.<region>.s3` and
  `com.amazonaws.<region>.dynamodb` on 443. No `0.0.0.0/0`, no port 80.
- **BREAKING**: self-termination changes mechanism. `InstanceInitiatedShutdownBehavior: terminate` on
  `RunInstances` lets the watchdog call `shutdown -h now` instead of `ec2:TerminateInstances`, so no EC2
  interface endpoint is needed and `RunnerRole` loses the `ec2:TerminateInstances` grant entirely
  (finding S8). A `halt -f -p` fallback covers a hung shutdown, the one new failure mode. All three
  termination layers survive: the watchdog subshell still fires under a deadlocked JVM because `shutdown`
  is a separate process, the process `timeout` is unchanged, and the CLI's shutdown hook still terminates
  from the operator's laptop.
- **BREAKING**: runner JAR staging becomes mandatory. GitHub Releases is unreachable from a private
  subnet, so `baas run` always stages the JAR into S3, cached under `runners/<version>/` so repeated runs
  do not re-upload. The laptop verifies a checksum before upload — a partial answer to the standing
  accepted risk that the runner JAR is fetched with no integrity verification.
- **BREAKING** for benchmark authors: a benchmark that makes outbound network calls of its own will fail.
  Maven runs on the laptop and only the built JAR is uploaded, so the build path is unaffected, but
  reaching an external service from inside a benchmark is no longer possible.

## Capabilities

### New Capabilities
- `runner-network-isolation`: the private runner subnet, its endpoint-only reachability, the narrowed
  egress contract, and the shutdown-based self-termination mechanism.

### Modified Capabilities
- `core-stack-provisioning`: adds the private subnet and its route table, removes the bring-your-own-VPC
  parameters and the `CreateNetworking` condition, narrows egress, and drops `ec2:TerminateInstances`
  from `RunnerRole`.
- `cli-command-structure`: makes runner-JAR staging unconditional and adds checksum verification before
  upload.

## Impact

- **Infra**: `infra/cf-template-core.yaml` (networking rewrite), `infra/operator-policy.json`,
  `infra/deployer-policy.json`, `infra/README.md`.
- **Code**: `infra/Ec2ProvisioningService.java` (subnet selection, shutdown behaviour),
  `infra/UserDataScriptBuilder.java` (watchdog and cleanup use `shutdown`), `commands/RunCommand.java`
  (mandatory staging, checksum), `infra/S3UploadService.java` (runner JAR cache prefix),
  `config/BaasConfig.java` (runner subnet id).
- **Docs**: `CLAUDE.md` — the three-termination-layers invariant, the `baas-role` tag entry (whose
  rationale changes once the `TerminateInstances` condition is gone), and the *Accepted risks* row
  claiming a private subnet costs ~$32/month, which is rewritten to record that gateway endpoints plus a
  prebaked AMI achieved it for free. Also `README.md`, `infra/README.md`, `docs/adr/`,
  `docs/diagrams/`, `docs/review/baas-cli-findings.md` (S8).
- **Depends on**: `prebaked-runner-ami` (tooling must be baked in) and `dynamodb-results-store` (Atlas is
  on the public internet and unreachable without NAT). Neither dependency is optional.
- **Cost**: $0 standing. Gateway endpoints and the internet gateway are free; there is no NAT and no
  interface endpoint.
- **No changes** to the results schema, the query layer, or the AMI build process.
