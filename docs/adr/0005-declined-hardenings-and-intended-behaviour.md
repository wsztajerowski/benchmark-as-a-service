# ADR 0005 — Hardenings declined, and behaviour that only looks wrong

- **Status:** Accepted
- **Date accepted:** 2026-07-30 to 2026-10-04, as each finding was decided
- **Source:** the review files merged into `docs/review/open-findings.md` on 2026-10-05; this ADR
  keeps the reasoning they held so the deleted files need not be consulted

Each entry below was proposed as a fix and decided against, or recorded so that nobody "fixes" it.
Risks already in CLAUDE.md's *Accepted risks* table (deployer escalation, relaxed kernel isolation,
runners terminating each other, `OperatorRole` trusting the account root, snapshot cost) are listed
there with their reasons and are not repeated. Re-raise any of these only with the context named.

## No permissions boundary on the deployer (S4, 2026-07-30)

`iam:CreateRole` also writes the trust policy, so the deployer policy is effectively account admin
(CLAUDE.md, *Accepted risks*). A permissions boundary — `BaasCliDeployerBoundary`, a required
`PermissionsBoundaryArn` on the core template and an admin-owned bootstrap step — was built against
the tree and dropped before it landed: it worked, but it added a hard prerequisite before the first
setup and only pays off in a multi-principal account. If it is ever needed, two details cost the most
time the first time:

- The IAM statement must split into boundary-conditioned and unconditioned halves:
  `iam:PermissionsBoundary` is absent from the `GetRole`/`DeleteRole`/`TagRole` request contexts.
- The boundary needs its own two-statement `RunInstances` split, for the reason the template
  documents for `OperatorRole`.

Still unverified, and load-bearing for the acceptance: that a same-account trust policy naming a
*specific* user ARN grants `sts:AssumeRole` without an identity-based allow (unlike `:root`, which
delegates to identity policies). If false, the escalation is harder than described.

## No TLS-only bucket policy, no 0700 on `~/.baas` (S11, 2026-10-02)

Every bucket client — the CLI and runner through the SDK, the instance through `aws s3 cp` — already
speaks HTTPS, so a `aws:SecureTransport: false` deny would guard only a hypothetical client, at the
price of a template resource, two deployer actions and a live setup to prove them.
`~/.baas/config.yaml` holds a prefix, a region, profile *names* and preferences; the secrets are in
`~/.aws`. Revisit if a compliance check requires it.

## `baas admin image` stays a deployer command (U10, 2026-10-02)

The operator role could read the pointer and describe the AMI, and `baas run` does exactly that.
Kept under `admin` anyway: what an operator needs about the image is in each run's
`environment.json`, which is the observation rather than the declaration.

## No template method for the four JMH/JCStress services (A1, 2026-10-02)

The services had diverged in one respect — only `jmh` shipped `logs/*.log` — which `RunLogs` fixed
for all four. Apart from that the copies differ only in import noise, so a template method would
guard against drift that has not happened, at the cost of rewriting four services and four builders.

## Self-only runner termination declined (S8, 2026-10-02)

Scoping `RunnerRole`'s `ec2:TerminateInstances` to the calling instance (`ec2:SourceInstanceARN`)
was declined on its failure mode: a subtly wrong condition denies the runner its *own* termination,
disabling both the normal path and the watchdog, and shows up only when a paid run hangs. The risk
itself is in *Accepted risks*.

## The runner's table writes are not narrowed by attribute (X3, 2026-11-03)

A runner can create or rewrite any job item (`UpdateItem` creates a missing item, and IAM cannot
require the `attribute_exists(pk)` that user-data sends) and put a `RESULT#` row of any shape. BaaS
itself never does: `baas run` creates the job item before launch. Narrowing `UpdateItem` with
`dynamodb:Attributes` to `pk`, `sk`, `status` and `instanceId` was declined: a bare item can still be
created, so it closes only part of the gap, and it is an IAM change carrying the live check. Instead
every reader skips an item it cannot parse with a warning (`TableItems`), so a forged row costs a line
rather than every listing until an account admin deletes it. The trust position is that of *Runners
can terminate each other* and S7.

## CI's OIDC trust stays repo-wide (S2, 2026-11-03)

`OperatorRole`'s federated statement matches `repo:<org>/<repo>:*` and `e2e-cloud-test.yml` runs on
`pull_request`, so whoever can push a branch can obtain operator credentials. Pinning `sub` to `main`
plus an environment with required reviewers, and a label-gated trigger, was declined: every PR's E2E
run would wait on a manual step, and the two collaborators with push access (2026-11-03) are trusted
operators already. Fork PRs cannot obtain an `id-token`, and the job runs on `ubuntu-latest`. Revisit
when push access extends beyond trusted operators, together with *Runners can terminate each other*.

## The results table formats scores in the JVM's locale (U15, 2026-10-01)

`baas results`' table prints `9702970,774` under pl-PL. That is right for a person reading a table;
JSON and CSV are machine formats and use `Locale.ROOT` (CLAUDE.md). The table is not a machine
format — do not "fix" it into one.

## The deployer policy names the `aws` partition literally (P15, 2026-10-02)

Recorded in CLAUDE.md (*BaaS targets the commercial `aws` partition only*): making the policy alone
partition-aware would imply support nothing else has.

## No `jobs wait`, no `config remove` for now (G5, G13, 2026-11-03)

Both were valid and small, and closed as not needed now. Without `jobs wait`, `e2e-cloud-test.yml`
keeps its own poll loop with a copy of the terminal statuses (`JobStatus.EXACT_TERMINAL` plus the
computed `vanished`): a new terminal status must be added there too, or the loop spins until its
deadline. Without `config remove`, a deployment torn down from another machine is forgotten by
deleting `~/.baas/deployments/<name>.yaml` by hand, and a preference is reset to its default by
removing its line. The designs are in the 2026-10-13 review's `api-gaps.md`.
