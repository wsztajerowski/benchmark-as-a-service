# ADR 0006 — An account may hold more than one deployment, each named explicitly

- **Status:** Accepted — implemented in `multiple-deployments`, verified live 2026-10-07; the same-region check is deferred
  (`openspec/changes/QUEUE.md`)
- **Date accepted:** 2026-10-07
- **Supersedes:** the "exactly one deployment per account, and the CLI cannot be told otherwise" rule
  (finding A10's fix, `443291f`)
- **Closes findings:** U36, C5, U33

## Context

Finding A10 found that the deployment's name was derived from the caller's ARN, so it moved when an SSO
permission set was switched, and differed per human on one account. A moved name did not fail. It
deployed a second, complete deployment beside the first, forking the AMI and the results table
without anyone noticing. The fix derived the name from the account alone, and went one step further:
no option could name a deployment at all. Exactly one per account, so that nobody would ever need to
ask which one they were on.

That second step blocked a real need. Developing BaaS itself wants a throwaway deployment in which a
template change, an IAM edit or an image bake can be exercised without touching the account's real
one. The only route was a by-hand `aws cloudformation deploy`. It skipped everything `SetupCommand`
does (per-region parent-AMI resolution, rendered image parameters, the networking guard, and since
`jobs-command` the deployer-policy step) and was reached by swapping `~/.baas/config.yaml` back and
forth. A second deployment in the same region also blocked the first one's teardown, because the
in-flight gate counted every runner in the region (U36).

## Decision

1. **A deployment's name is its prefix, free-form, given by the global `--deployment`.**
   `baas-<accountId>` is what setup derives when nothing is configured. That is the default name, no
   longer the only one.
2. **Nothing is derived silently.** The derived name applies only to setup on a machine with no
   deployment configured. `config sync` is never derived. Every other command uses the deployment
   it is told, or the only one configured, and refuses to guess between two or more, listing them.
   No environment variable, stored default or switch command selects a deployment.
3. **One file per deployment**, `~/.baas/deployments/<name>.yaml`. A teardown deletes its file. The
   flat `config.yaml` migrates once.
4. **Names are validated once, at setup**, against the lowest common denominator of every service
   that carries one (CloudFormation, S3, SSM, IAM's role-name length via `DeploymentNames`), and
   nothing else.
5. **Runners carry `baas-deployment=<prefix>`**, and every live-runner query filters on it.

## Why this does not reopen A10

A10's harm was a name that moved **silently**: the same command, typed by the same person, aimed at a
different deployment because their credentials had changed. Nothing here derives a name from the
caller, and the default name is still a function of the account alone. A second deployment exists
only because someone typed its name into `--deployment`. And while two exist, every command must
name one. The question "which deployment am I on?" is answered by the command line in front of the
user, never by state they cannot see.

## Alternatives rejected

- **A fixed suffix (`baas-<acct>-<word>`) or a single `--dev` flag.** Each keeps the account in the
  name or caps the account at one extra deployment. The user chose a free-form name.
- **A selector environment variable (`BAAS_DEPLOYMENT`) or a default pointer with `config use`.**
  Invisible state that changes which deployment a command hits. The dev worktree runs its own
  reactor build through an alias, which carries `--deployment` visibly.
- **One file with a map of deployments.** An older CLI's save drops unknown keys, so the map would
  vanish the first time an older CLI ran `config set`.
- **Refusing a second deployment in a region that already holds one.** It needs a cross-region
  lookup and protects only what setup notices. The tag makes every query correct instead.
- **Scoping `RunnerRole`'s terminate condition per deployment.** That is the self-only hardening
  declined in ADR 0005, under another name. The accepted risk *Runners can terminate each other*
  stands.

## Consequences

- With a second deployment configured, even the default one's commands need `--deployment`. This is
  the developer's own cost, and it is what keeps the ambiguity safe.
- After the first run, an older CLI finds no flat file and reports that nothing is configured. It
  fails loudly; it never addresses the wrong deployment.
- An extra deployment still needs its own prefix-exact deployer policy, attached as customer-managed
  by an identity above the deployer. Setup prints it.
- CI's e2e stays on the default deployment. A change touching `infra/`, IAM or the runner image is
  verified by hand on a second deployment (`openspec/config.yaml`, tasks rule).
- A runner launched by a CLI from before the tag is invisible to the scoped queries. The window lasts
  one benchmark timeout, and the watchdog still ends that runner.
