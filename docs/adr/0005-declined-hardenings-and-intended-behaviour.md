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

## The results table formats scores in the JVM's locale (U15, 2026-10-01)

`baas results`' table prints `9702970,774` under pl-PL. That is right for a person reading a table;
JSON and CSV are machine formats and use `Locale.ROOT` (CLAUDE.md). The table is not a machine
format — do not "fix" it into one.

## The deployer policy names the `aws` partition literally (P15, 2026-10-02)

Recorded in CLAUDE.md (*BaaS targets the commercial `aws` partition only*): making the policy alone
partition-aware would imply support nothing else has.
