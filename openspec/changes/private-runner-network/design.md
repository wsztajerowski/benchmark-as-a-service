## Context

Runners launch into a public subnet with a public IP and `0.0.0.0/0` egress on ports 443 and 80, because
they need to reach yum repositories for the JDK, GitHub Releases for the runner JAR and async-profiler,
MongoDB Atlas on 27017, and the EC2 API for self-termination.

`prebaked-runner-ami` removes the first and third of those, and `dynamodb-results-store` removes Atlas.
What remains is the runner JAR — which the CLI can stage into S3 — and the EC2 API call, which has a
replacement that costs nothing.

Both S3 and DynamoDB offer **gateway** endpoints, which are free. The project's recorded position is that a
private subnet needs NAT or PrivateLink at roughly $32/month; that was true when the data path required
Atlas over the public internet, and is no longer.

This change also closes finding S8: `RunnerRole`'s `ec2:TerminateInstances` is scoped to a shared tag, so
any runner can terminate any other.

## Goals / Non-Goals

**Goals:**

- Runners have no route to the internet and no public IP.
- Egress enumerates exactly the services the runner uses.
- Standing cost stays at zero: no NAT, no interface endpoint.
- All three termination layers keep working, including under a deadlocked JVM.
- Failure diagnostics — the boot log in particular — survive isolation.

**Non-Goals:**

- Private image builds. A build instance with no egress cannot fetch the tools it is baking, so this is
  incoherent rather than merely expensive.
- Supporting benchmarks that make outbound network calls. Explicitly dropped, and documented.
- Multi-AZ or multi-subnet runner placement. One private subnet, matching the current single-subnet model.
- Any change to the results schema, the query layer, or the image build process.

## Decisions

### Two subnets with opposite roles, rather than one private subnet

```
BaasVpc
├─ PublicSubnet   → IGW route.  Image Builder build instances only. Rare, short-lived.
└─ RunnerSubnet   → no IGW.  S3 + DynamoDB gateway endpoints. Every benchmark run.
```

The builder and the runner have inverted requirements by construction: the runner needs no internet
*because* the builder had it. Retaining the public subnet for builds costs nothing, since an internet
gateway carries no charge — only NAT does.

*Alternative considered.* Making every subnet private would require `ssm`, `ssmmessages`, and
`ec2messages` interface endpoints, because Image Builder orchestrates through Systems Manager — roughly $21
per month — and the build would *still* fail, since it could not download the JDK or async-profiler. Rejected
as both costly and self-defeating.

### Gateway endpoints only

S3 and DynamoDB are the only AWS services the runner touches. Instance credentials come from the instance
metadata service, which is link-local, so no STS call is needed, and the prefix derivation that requires
`sts:GetCallerIdentity` happens on the operator's laptop.

*Alternative considered.* Adding an EC2 interface endpoint to preserve API-based self-termination — about $7
per month, and unnecessary given the shutdown mechanism below.

### Self-termination via shutdown, not the EC2 API

Instances are launched with instance-initiated shutdown behaviour set to terminate, so the watchdog and the
cleanup path invoke `shutdown -h now` instead of `ec2:TerminateInstances`. This removes the need for an
interface endpoint *and* lets `RunnerRole` drop `ec2:TerminateInstances` entirely, closing finding S8.

The three-layer termination design is load-bearing and survives intact:

1. **Shell watchdog** — still fires under a deadlocked JVM, because `shutdown` is invoked from a subshell,
   independent of the JVM process.
2. **Process timeout** — unchanged.
3. **CLI shutdown hook** — still calls `TerminateInstances`, but from the operator's laptop under operator
   credentials, which network isolation does not affect.

The one new failure mode is a hung `shutdown`, which would leave a paid instance running. A `halt -f -p`
escalation 120 seconds later covers it. This is cheap insurance for the failure class the whole termination
design exists to prevent.

### The bring-your-own-VPC escape hatch is removed rather than validated

The private design depends on gateway endpoints being associated with the runner's route table. For a
caller-supplied VPC the CLI cannot guarantee that, and the failure mode is a run that hangs until the
wall-clock cap and then self-terminates — expensive and nearly undiagnosable, since the instance is gone by
the time anyone looks.

The alternative was keeping the parameters and pre-checking the supplied VPC for both endpoints at setup.
That is implementable, but it supports a configuration matrix nobody is using at the cost of a conditional
branch through every networking resource. Removing the parameters deletes that branch outright.

*Consequence:* this is a breaking change for any stack deployed with `UseExistingVpc=true`. Such a stack
will create the full networking set on update, leaving the caller's own resources untouched.

### Egress narrows to managed prefix lists

Because gateway endpoints route to the AWS-managed prefix lists for S3 and DynamoDB, egress can name exactly
those, on 443 only. Port 80 disappears with the yum dependency. The security group becomes a readable
statement of what a runner may reach, rather than an open default.

### Runner JAR staging becomes mandatory, and is verified

GitHub Releases is unreachable, so the conditional "stage from S3 if `--runner-jar` was given, otherwise
resolve the latest release from the GitHub API" logic collapses to a single path: always stage. Caching under
a version-scoped key keeps repeated runs cheap.

Usefully, the laptop can verify a checksum before upload — a partial answer to the standing accepted risk
that the runner JAR is fetched with no integrity verification. It is only partial: the laptop still obtains
the JAR from GitHub in the first place, so this establishes transport integrity, not provenance.

## Risks / Trade-offs

- **A hung `shutdown` leaves a paid instance running** → `halt -f -p` escalation after 120 seconds; the CLI's
  poll loop also observes instance state and its shutdown hook still terminates via the API.
- **Terminate-on-shutdown makes an accidental in-guest shutdown destructive** → That is the intent for a
  throwaway runner, and the instance holds no state that is not already in S3 and DynamoDB by then.
- **Removing the BYO-VPC parameters breaks any stack using them** → Called out as breaking in the proposal
  with migration notes. At current scale the affected surface is one operator.
- **A future need for runner internet access requires re-opening egress** → Deliberate. The constraint is
  documented in `--help` and the README so it fails at design time rather than mid-run.
- **Gateway endpoints are region- and service-specific** → If a future feature needs another AWS service
  from the runner, it needs either a new endpoint or an interface endpoint with a real monthly cost. The
  zero-cost property holds only for S3 and DynamoDB.
- **Diagnosing a failed private-subnet run depends entirely on the boot-log upload** → There is no SSH and no
  Session Manager. The upload happens on both the normal and watchdog paths, and this is now the single most
  important diagnostic in the system, so its failure would be severe. Test both paths explicitly.
- **Debuggability drops overall** → No public IP means no direct access to a misbehaving instance. Accepted:
  instances are throwaway and short-lived, and the boot log plus S3 artifacts have been sufficient in
  practice. Adding Session Manager access would cost three interface endpoints.

## Migration Plan

1. Confirm `prebaked-runner-ami` and `dynamodb-results-store` are both deployed and exercised. Neither
   dependency is optional: without the AMI there is no toolchain, and without DynamoDB the data path still
   requires the public internet.
2. Add the private subnet, its route table, and the endpoint associations while leaving runner provisioning
   on the public subnet. Deploy and confirm nothing changes.
3. Switch `Ec2ProvisioningService` to the private subnet, keeping API-based termination briefly, and confirm
   a full run — including the boot-log upload — succeeds.
4. Switch termination to shutdown, add the `halt -f -p` escalation, and drop `ec2:TerminateInstances` from
   `RunnerRole`. Verify normal completion, a timeout, and a deliberately deadlocked benchmark.
5. Narrow the security group egress to the two prefix lists.
6. Remove the BYO-VPC parameters and the networking condition.

**Rollback:** each step is independently revertible, and the public subnet remains in the template
throughout, so reverting the subnet selection restores the previous behaviour. Step 4 is the one to verify
hardest, since a termination regression costs money rather than correctness — keep the wall-clock watchdog
and the CLI shutdown hook in place while testing it.

## Open Questions

- Should the runner subnet span two availability zones for capacity resilience when a chosen instance type is
  unavailable in one? Not required today, and a second subnet would need its own endpoint associations.
- Is the `halt -f -p` escalation window of 120 seconds right? Long enough for an orderly shutdown of an
  instance with no meaningful state to flush, but unverified against a large-volume instance type.
- Should the CLI warn when a benchmark JAR appears to bundle a network client, as an early signal of the new
  constraint? Speculative; deferred until the constraint actually bites someone.
