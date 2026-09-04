## 1. Confirm prerequisites

- [ ] 1.1 Confirm `prebaked-runner-ami` is deployed and a benchmark runs from the baked AMI with no
  package installation
- [ ] 1.2 Confirm `dynamodb-results-store` is deployed and results are written through the DynamoDB gateway
  endpoint
- [ ] 1.3 Confirm nothing in the current user-data reaches a destination outside S3 and DynamoDB, other than
  the runner JAR fetch

## 2. Private subnet, added alongside the existing one

- [ ] 2.1 Add the private runner subnet with `MapPublicIpOnLaunch: false` to `cf-template-core.yaml`
- [ ] 2.2 Add its route table with no internet-gateway route, and associate the subnet
- [ ] 2.3 Associate the S3 and DynamoDB gateway endpoints with the private route table
- [ ] 2.4 Add stack outputs for the runner subnet and the build subnet as distinct values
- [ ] 2.5 Point the Image Builder infrastructure configuration explicitly at the build subnet
- [ ] 2.6 Deploy and confirm no behaviour change while runners still use the public subnet
- [ ] 2.7 Add a template test asserting the runner route table has no `0.0.0.0/0` route
- [ ] 2.8 Add a template test asserting every VPC endpoint is of type Gateway

## 3. Move the runner

- [ ] 3.1 Add the runner subnet identifier to `BaasConfig`, populated by `baas admin setup` and
  `baas config sync`
- [ ] 3.2 Switch `Ec2ProvisioningService` to launch into the runner subnet, with no fallback to the build
  subnet
- [ ] 3.3 Fail `baas run` before provisioning when the runner subnet is unresolvable, naming
  `baas config sync`
- [ ] 3.4 Run a full benchmark on the private subnet with API-based termination still in place
- [ ] 3.5 Confirm the S3 output upload, the DynamoDB write, and the `cloud-init-output.log` upload all
  succeed
- [ ] 3.6 Confirm the instance has no public IP address

## 4. Runner JAR staging

- [ ] 4.1 Make staging unconditional in `RunCommand`, removing the `--runner-jar`-conditional branch
- [ ] 4.2 Cache the staged JAR under a version-scoped S3 key and reuse it when present
- [ ] 4.3 Compute and verify the JAR checksum before upload, failing before launch on mismatch
- [ ] 4.4 Remove the GitHub Releases resolution and download from `UserDataScriptBuilder`
- [ ] 4.5 Assert the rendered user-data contains no GitHub request and no conditional JAR branch
- [ ] 4.6 Test that a second run with the same version performs no re-upload

## 5. Shutdown-based self-termination

- [ ] 5.1 Set instance-initiated shutdown behaviour to terminate in `Ec2ProvisioningService`
- [ ] 5.2 Replace the watchdog's `ec2 terminate-instances` with `shutdown -h now`, keeping the boot-log
  upload before it
- [ ] 5.3 Replace the cleanup path's `ec2 terminate-instances` with `shutdown -h now`
- [ ] 5.4 Add the `halt -f -p` escalation if the instance is still running 120 seconds after shutdown
- [ ] 5.5 Verify the watchdog still starts immediately after `INSTANCE_ID` resolves and there is still no
  `set -e`
- [ ] 5.6 Remove `ec2:TerminateInstances` from `RunnerRole` in `cf-template-core.yaml`
- [ ] 5.7 Assert the rendered user-data contains no `ec2 terminate-instances` invocation
- [ ] 5.8 Add a policy test asserting `RunnerRole` grants no `ec2:TerminateInstances`
- [ ] 5.9 Verify termination on normal completion
- [ ] 5.10 Verify termination on benchmark timeout
- [ ] 5.11 Verify termination by the watchdog against a deliberately deadlocked benchmark, and that the boot
  log is uploaded first
- [ ] 5.12 Verify the CLI shutdown hook still terminates the instance on Ctrl+C

## 6. Narrow egress

- [ ] 6.1 Replace `RunnerSecurityGroup` egress with the AWS-managed prefix lists for S3 and DynamoDB on TCP
  443
- [ ] 6.2 Remove the port 80 rule and any `0.0.0.0/0` rule
- [ ] 6.3 Add a template test asserting no egress rule targets `0.0.0.0/0` and none covers port 80
- [ ] 6.4 Run a full benchmark and confirm nothing broke
- [ ] 6.5 Confirm an outbound connection to an address outside both prefix lists fails from the instance

## 7. Remove the bring-your-own-VPC escape hatch

- [ ] 7.1 Remove the `UseExistingVpc`, `ExistingVpcId`, `ExistingSubnetId`, and `ExistingSecurityGroupId`
  parameters
- [ ] 7.2 Remove the networking condition and the condition attribute from every resource carrying it
- [ ] 7.3 Remove any CLI option or config key that supplied those parameters
- [ ] 7.4 Add a template test asserting no networking condition remains
- [ ] 7.5 Deploy a stack update against an existing stack and confirm it succeeds

## 8. Documentation

- [ ] 8.1 Rewrite the three-termination-layers invariant in `CLAUDE.md` for the shutdown mechanism, keeping
  the reason each layer exists
- [ ] 8.2 Rewrite the `baas-role` tag entry in `CLAUDE.md`: the tag remains for identification and cost
  attribution but no longer gates termination
- [ ] 8.3 Rewrite the *Accepted risks* row claiming a private subnet costs ~$32/month, recording that gateway
  endpoints plus a prebaked AMI achieved it at zero standing cost
- [ ] 8.4 Update the *Accepted risks* row on runner JAR integrity to reflect laptop-side checksum
  verification, and state plainly that it establishes transport integrity rather than provenance
- [ ] 8.5 Remove the bring-your-own-VPC documentation from `CLAUDE.md` and `infra/README.md`
- [ ] 8.6 Document in `README.md` and `baas run --help` that a benchmark making outbound network calls will
  fail, and that only S3 and DynamoDB are reachable
- [ ] 8.7 Update `docs/diagrams/` for the two-subnet topology and the shutdown-based termination
- [ ] 8.8 Update `docs/adr/0001-self-contained-baas-cli.md` where it assumes public-subnet runners
- [ ] 8.9 Mark finding S8 fixed in `docs/review/baas-cli-findings.md` and update the status table
- [ ] 8.10 Document that diagnosing a failed run now depends entirely on the S3 boot log, since there is no
  SSH or Session Manager access
