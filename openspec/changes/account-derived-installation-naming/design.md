# Design

## Context

See `proposal.md` — *Why*. The constraints that shape the approach:

- `ResourceNamePrefix` is already a plain CloudFormation parameter, referenced at ~40 sites in
  `cf-template-core.yaml` under three composition shapes (`baas-<p>` for stack, bucket and table;
  `<p>-suffix` for IAM and Image Builder; `/<p>/` for SSM), and it is also the `project` tag value
  on every resource. `deployer-policy.json` mirrors all three from a single `${PREFIX}`.
- Only `SetupCommand` and `DeployerPolicyCommand` derive the prefix. Every other command reads
  `config.getPrefix()`, and `config sync` / `config set --prefix` already write it without
  consulting an ARN. The derivation is therefore well fenced.
- `BaasCliOperatorRole` already holds `cloudformation:DescribeStacks` on its own stack
  (`operator-policy.json:103`), so resolving values from stack outputs at use time needs no new
  grant.
- The rendered deployer policy is attached as an inline policy on an IAM group, capped at 5120
  non-whitespace characters shared across every inline policy there, with a test holding it under
  4608. `${PREFIX}` appears 13 times, so each character of prefix costs 13 characters of policy.
- CLAUDE.md's *Accepted risks* table was checked. Nothing here reopens one. The deployer-privilege
  row (deployer is effectively account admin, permissions boundary deliberately removed) is
  untouched: this change alters names, not grants. The runner-AMI-snapshot row is touched only in
  degree — the migration briefly holds two snapshots instead of one, and ends at one.

## Goals / Non-Goals

**Goals:**

- One installation per account by default, so the pinned AMI and the results table each have
  exactly one instance.
- A name any machine holding account credentials can compute, so no value has to be carried
  between machines by hand.
- A single composition rule, so a reader who knows the prefix can predict every resource name.
- Failure rather than silent forking when an installation's identity is ambiguous.

**Non-Goals:**

- Copying the retired installation's history into the new one. Deliberately deferred; see
  *Migration Plan*.
- Any ability to delete a shared installation's bucket or results table. That belongs to
  `export-before-teardown`, gated on a verified export.
- Guarding against two concurrent `baas admin build-image` runs. An account-shared installation
  makes that reachable for the first time; closing it is its own change. See *Risks / Trade-offs*.
- Any CLI support for a second installation. Creating one is a by-hand procedure documented in
  `infra/README.md`; the CLI's only concession is `deployer-policy --prefix`, which prints.
- Multi-account or cross-account installations. The prefix binds an installation to one account by
  construction, and that is the intent.
- Isolating one developer's work from another's inside one account. IAM does not do that today
  either — the deployer policy is account admin by accepted risk — and per-identity naming only
  ever supplied distinct strings, not isolation.

## Effect on comparability with existing results

This change alters names and resolution paths. It does not alter the measurement path: the runner
JAR, the benchmark invocation, the process timeout, the shell watchdog, the environment manifest's
contents and the three termination layers are all untouched. `UserDataScriptBuilder` still receives
the same table name and still writes the manifest before the benchmark; the value now arrives by
derivation rather than from a stored config field, and is byte-identical either way.

What does change is that the migration bakes a **new AMI**, and a bake resolves patch levels afresh.
Results recorded before the migration and after it were therefore measured on two different images,
and may not be comparable. This is not new in kind — it is true of every `baas admin build-image` —
and it is answerable per result, because `environment.json` records the observation rather than the
declaration. No stored result changes meaning, and no existing `environment.json` becomes harder to
interpret: the retired installation's artifacts stay exactly where they are.

The AMI pointer's SSM path moves with the prefix (`/<prefix>/runner/ami-id`). That is resolution,
not measurement, and the spec text already expresses the path in terms of `<prefix>`.

## Decisions

### The prefix is derived from the account, not from a normalised ARN

`GetCallerIdentity().account()`, formatted `baas-<accountId>`. `computePrefix` and
`base32Encode` are deleted rather than fixed.

*Rejected — normalise the ARN* (the fix `docs/review/baas-cli-findings.md` A10 proposes: rewrite
`arn:aws:sts::123:assumed-role/Role/session` to `arn:aws:iam::123:role/Role` before hashing). It
survives re-login and session-name variation, but still forks when the permission set is switched,
still leaves the name underivable without local state, and still fragments the AMI and the results
table per identity. It also changes every SSO user's prefix, so it pays a full migration for a
partial fix. New information since that finding was written: the pinned AMI and the results table
both arrived after it, and both are account-level assets that want one instance — which is what
turns "unstable name" into "silently incomparable measurements".

*Rejected — an explicit `--name` per developer.* Derivable and memorable, but keeps one AMI and one
results table per person, which is the drift the pre-baked image exists to remove.

*Rejected — the account alias* (`baas-way-to-neo`). Readable and derivable, but an alias is mutable:
changing it would silently fork the installation, reintroducing the exact failure being removed.

*Rejected — a separate long-lived data stack* holding the bucket and table, with per-developer
infrastructure stacks. Gets shared history, but still fragments the AMI, and adds a third
independently-deployed stack against CLAUDE.md's two-stack shape.

### `baas-` lives inside the prefix value, not at the template's composition sites

`ResourceNamePrefix` becomes the full name stem (`baas-123456789012`), and every resource is
`${ResourceNamePrefix}` or `${ResourceNamePrefix}-<suffix>`. The hardcoded `baas-` is stripped from
the four template sites and four policy sites that carry it, and the SSM path re-roots to
`/${ResourceNamePrefix}/`.

*Rejected — keep `baas-` at the composition sites and pass the bare account ID.* It leaves three
composition shapes in place, which is the wart the naming rule exists to remove, and it makes the
stack name (`baas-` + prefix) and the IAM role name (prefix + `-role`) derive differently from one
value.

Measured consequence: the rendered deployer policy goes from 4122 non-whitespace characters today
to **4219** for `shared` and **4271** for `dev`, against the 4608 test cap and IAM's 5120 real cap.
It is near-flat rather than larger because collapsing the Image Builder wildcard from
`*/${PREFIX}-runner*` to `*/${PREFIX}-*` pays for the longer role names. The existing size test
renders with an 8-character prefix and must be updated to a realistic one, or it keeps
under-measuring by ~117 characters.

### The CLI has no way to name an installation at all

Exactly one per account, derived, with no `--mode`, no `--prefix`, no override. A user of BaaS
never has to ask which installation they are on.

This replaces an earlier decision here for a closed `--mode shared|dev` vocabulary. What changed:
dev mode exists for developing *BaaS itself*, which is not something a released artifact should
carry — it is one person's workflow, not a product capability. Trying to ship it produced a
problem with no good answer: a deployer policy is prefix-exact, so the dev installation needs its
own, and two rendered documents (4219 + 4271) do not fit in IAM's 5120-character inline budget.
Automating that attachment would have been machinery built to work around shipping something that
should not ship.

So the capability became a procedure instead (`infra/README.md`, *A second installation*): deploy
the core template by hand with another `ResourceNamePrefix`, adopt it with `baas config sync
--name`, and everything downstream already works, because every command except `setup` resolves
the installation from configuration. `baas admin deployer-policy --prefix` renders the policy such
an installation needs — a printing command that grants nothing.

*Rejected — a version-guarded `--mode`* honoured only on an unreleased build. Self-disabling, but
the code still ships and a version-string guard is magic the next reader has to be told about.

*Rejected — an undocumented `BAAS_INSTALLATION_SUFFIX` env var.* Least code, but a hidden feature
is found by grep rather than by docs, and a free-form suffix reopens exactly what a closed
vocabulary was there to shut.

*Noted, not rejected — a separate AWS account* for development. It gives the same isolation with
no prefix games at all and is recorded in `infra/README.md` as the alternative; it costs a second
account to administer, which is why it is not the default advice.

### VPC parameters are refused on change, not silently carried forward

`SetupCommand` currently puts `UseExistingVpc` and its three companions into the parameter map
unconditionally, so they are always sent and always overwrite. `CloudFormationService.updateStackParameters`
already carries unnamed parameters forward with `UsePreviousValue`, and its own Javadoc describes
this exact failure for `build-image`: *"`UseExistingVpc` silently flips back to false and the update
starts building a VPC over someone's existing networking."* Under per-identity stacks only the owner
could trigger it; on a shared stack a teammate's plain `baas admin setup` does.

*Rejected — carry the VPC parameters forward, matching the federation-parameter fix.* It closes the
hole, but it silently discards a flag the operator typed. Refusing says which value is deployed and
which was submitted, and submits nothing.

### `config sync --name` is required even though the name is derivable

The derivation works on a machine with no local state, so `--name` could default. It does not.

On a fresh machine — a CI runner most of all — a bare `baas config sync` would adopt whichever
installation the *currently active credentials* imply. A workflow federating into an unexpected
role, or a leftover `AWS_PROFILE`, would then bind the machine to a different account's installation
and fail later, after provisioning, somewhere unrelated. Requiring the name keeps the installation a
declared input to CI rather than an inference, and `--name` is needed anyway to reach the dev
installation, so making it optional would only shortcut one of two cases. The ceremony being removed
is "a name you must be told"; "a name you must type once per machine" is not the same thing.

`baas admin setup` stays asymmetric: it derives the name and prints it. Setup creates, sync adopts.

### Derivable and resolvable values leave the configuration file

`config.yaml` keeps credentials, region, the installation prefix and operator preferences. Names the
composition rule fixes (bucket, results table, runner instance profile) are derived at use time.
AWS-assigned identifiers (runner subnet, security group) are resolved from stack outputs per
invocation.

The resolution case is not tidiness. CLAUDE.md already records the failure caching causes: editing
`RunnerSecurityGroup`'s `GroupDescription` replaces the security group and *the id changes*, and
"anything holding the old id is then pointing at a group that no longer exists — `~/.baas/config.yaml`
most obviously." Resolving per run removes that failure class rather than documenting it. The cost
is one `DescribeStacks` call on a command that already provisions EC2, under a grant the operator
role already holds.

`prefix` itself stays stored rather than reduced to a `mode` key, so a machine's configuration states
plainly which installation it addresses instead of requiring a reader to recombine account and mode.

Two keys are deleted outright as dead, verified by tracing every getter:

- `benchmark.asyncProfilerVersion` — read only by `config show`. The manifest's value comes from
  `UserDataScriptBuilder:80` running `asprof --version` *on the instance*. The field reports `4.0`
  regardless of what the AMI holds, so it is not merely unused but misleading.
- `aws.vpcId` — written by `SetupCommand:253` and `ConfigSyncSubcommand:61`, read by nothing except
  `config show`.

### The retired installation becomes a read-only archive, and its data is not copied forward

CloudFormation cannot rename a stack, and neither S3 buckets nor DynamoDB tables can be renamed, so
adopting the old names is not available. Copying forward is available — stored `resultPath` values
are bucket-relative (`runs/<project>/<runId>/`), so an `aws s3 sync` preserves every pointer, and the
table's `pk`/`sk` need no rewrite — but it is deliberately not done here.

The reason is that it is not a one-way door. Bucket versioning is suspended with no lifecycle rule
expiring current objects under `runs/`, and both bucket and table are `DeletionPolicy: Retain`, so
the archive persists indefinitely and can be merged later at any time. Doing it now would couple a
naming change to a data migration, and `export-before-teardown` is already designing the bundle
format that a merge should use.

`--results-table` on the read-only commands is what keeps the archive reachable in the meantime.
Without it the archive would be write-once, read-never: `config set` has no such option, `baas
results` has no override, and `config sync` cannot help once the old stack is deleted because it
reads stack outputs.

## Risks / Trade-offs

**Concurrent `baas admin build-image` on a shared installation deregisters a live AMI — accepted,
unmitigated** → The "repoint the pointer before deregistering the replaced AMI" ordering is safe
only while one build runs at a time. Per-identity naming supplied that guarantee by accident: nobody
else could build against your installation. A shared installation removes it, so two people baking
concurrently can have the one that finishes second deregister the AMI the other just published,
leaving the pointer naming a deleted image and every subsequent `baas run` failing at AMI
validation.

A guard was scoped for this change — refuse to start while the installation's pipeline has an
execution in progress — and deliberately dropped, to keep the change to one idea. It is therefore a
real exposure this change opens and does not close, not a hazard that was analysed away. It is
narrow in practice: `build-image` is an infrequent, explicitly-invoked admin operation, the window
is the ~15 minutes of a bake, and recovery is running `baas admin build-image` again. It should be
its own change, and it becomes more pressing as soon as more than one person actually holds deployer
credentials on the account.

**Concurrent `baas admin setup` on a shared installation** → CloudFormation rejects the second with
a stack-in-progress error. Not corrupting, but the message is opaque; setup should translate it into
a statement that someone else is deploying this installation.

**A by-hand second installation needs a customer-managed deployer policy** → Confirmed against a
live account: with the policy rendered for `baas-<accountId>` attached, anything addressing
`baas-<accountId>-dev` is refused at `cloudformation:DescribeStacks`, and creates nothing. That is
prefix-exact scoping working as designed. Two rendered documents are 4219 + 4271 = 8490
non-whitespace characters against IAM's 5120 cap for inline policies *shared across the principal*,
so the second must be customer-managed (6144 each). Documented in `infra/README.md`; the failure
when forgotten is an opaque AccessDenied.

**A shared installation means a shared blast radius** → Mitigated for now by capability rather than
by a check: teardown still cannot delete the bucket or the results table at all. The exposure
arrives with `export-before-teardown`, together with the export gate that makes it survivable.

**The bucket name embeds the account ID** → S3 is a global namespace, so anyone who knows the
account ID can probe whether it runs BaaS. Accepted: the account ID appears in every ARN the project
hands out, and bucket existence reveals nothing further.

**Existing `config.yaml` files carry removed keys** → `ConfigService` disables
`FAIL_ON_UNKNOWN_PROPERTIES` (finding A6), so stale keys are ignored rather than fatal. The failure
mode is an operator believing a key still has effect. `config sync --name <prefix>` rewrites the file
in the new shape; `config show` reports only live fields.

**`private-runner-network` is in flight and changes the same networking parameters** → 55 tasks, none
complete. Whichever change lands second inherits the merge, and the VPC-immutability rule will
interact with whatever that change does to subnet selection. Worth sequencing deliberately rather
than discovering at merge.

**The deployer policy's headroom shrinks** → 4219 of 4608 leaves 389 characters, and each character
of prefix costs 13. The closed mode vocabulary is what keeps this safe; a free-form installation name
would have made the budget a live constraint.

## Migration Plan

`baas-3q7i7s65` is the live installation: CI's `CORE_STACK_NAME`, the laptop's configured target, and
the holder of the AMI pointer, bucket and results table.

1. Deploy the new installation: `baas admin setup` (derives `baas-<accountId>`), with the same
   federation options the current stack carries, since those are not carried forward onto a create.
2. `baas admin build-image` — ~15 minutes, publishes an AMI to `/baas-<accountId>/runner/ami-id`.
3. Verify with a real run against `fake-jmh-benchmarks`, tagged `exclude_from_results=true`.
4. Repoint CI: set `CORE_STACK_NAME` to the new prefix. `e2e-cloud-test.yml` is the only workflow
   that reaches AWS, and it is the regression check.
5. `baas admin teardown --stack-name baas-3q7i7s65`. Its bucket and results table are `Retain`, so
   they survive as the archive.
6. Manually deregister the retired AMI and delete its snapshot — the ~$0.20/month is not reclaimed
   otherwise, because the one-image rule only retires an image the *same* installation replaced.

**Rollback.** Until step 5, rollback is repointing `CORE_STACK_NAME` and `config.yaml` back at
`baas-3q7i7s65`; both installations exist and the old one is untouched. After step 5 the old stack is
gone, but its bucket and table are not, so results remain readable via `--results-table` and direct
S3 access; recovering the old *stack* means a fresh `baas admin setup` under the old naming code,
which is a git revert away.

**What breaks for a user who does not migrate.** A CLI carrying this change, run against a
`config.yaml` pointing at `baas-3q7i7s65`, resolves the bucket and table by derivation from a prefix
that no longer matches those resources. That is a hard failure at resolution time, not a silent
mis-target, because the derived names do not exist. `config sync --name` is the documented recovery.

## Resolved Questions

- **Does the SSO ARN really change per login?** No — the `AWSReservedSSO_<permission-set>_<hex>`
  suffix is minted when the permission set is provisioned into the account and does not rotate, and
  the session name is a stable identity-store attribute. Finding A10 states per-login instability;
  that part is inaccurate. The finding's conclusion survives anyway, because permission-set
  switching, permission-set re-provisioning, explicit `--role-session-name`, an IAM-user-to-SSO
  migration and two humans on one permission set all move the ARN — and none of them fail loudly.
- **Is `cloudformation:DescribeStacks` affordable on the run path?** Yes.
  `infra/operator-policy.json:103` already grants it, scoped to the installation's own stack, so
  per-run resolution of the subnet and security group needs no policy change.
- **Is the async-profiler version needed anywhere but the AMI bake?** No. After
  `benchmark.asyncProfilerVersion` is deleted, the version exists only in `infra/runner-image.yaml`.
  Every other reference is a *path* (`RunnerImageRenderer` unpacks with a
  `async-profiler-*-linux-x64` glob, so the install directory is version-free) or an observation
  (`asprof --version` at boot). CLAUDE.md's "`infra/runner-image.yaml` is the only place a tool
  version is declared" becomes literally true.

## Open Questions

- **Should the `project` resource tag be renamed?** Its value is the installation prefix, and the
  word collides with two unrelated meanings — the DynamoDB partition `RESULT#<project>` and the
  caller-overridable `project` result tag, both of which are the git repository name. Deferrable: it
  changes no behaviour any command depends on, and renaming it is a mechanical follow-up. Left as-is
  here so that a tag rename does not ride along with a naming change that already touches every
  resource.
