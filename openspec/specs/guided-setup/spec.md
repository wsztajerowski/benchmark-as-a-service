# guided-setup Specification

## Purpose
Walks a user through setting up or changing a deployment: each step checks the real state of AWS and
of this machine, then acts, or explains what the user must do and waits, so no step ends in a bare
refusal and a re-run.

## Requirements

### Requirement: Setup is an ordered series of probed steps
`baas admin deployment setup` SHALL run these steps in this order: which deployment, credentials,
name and region, deployer permissions, names are free, GitHub Actions, plan and confirm, stack,
operator role, runner image, done. Each step SHALL first check its desired state against AWS and this
machine. A met step SHALL change nothing. An unmet step SHALL be done by setup when setup can do it,
and otherwise SHALL be explained to the user. Setup SHALL keep no record of its progress between runs:
a later run SHALL find each step's state again by checking it, and continue at the first unmet step.

#### Scenario: A re-run continues after an administrator acted
- **WHEN** a run stopped at the permission step, an IAM administrator then attached the policy, and setup is run again
- **THEN** the steps before it are met, the permission step is met, and setup continues with the names step without asking again for anything already answered in the deployment's file

#### Scenario: A healthy deployment's re-run changes nothing
- **WHEN** setup runs interactively on a deployment whose stack, operator role and image are current, and the user accepts every default
- **THEN** no stack operation, image build or file change takes place, and setup exits 0

#### Scenario: No progress file exists
- **WHEN** a run is interrupted at any step
- **THEN** nothing besides the deployment's configuration file records how far it got

### Requirement: Setup is interactive on a terminal and runs unattended otherwise
Setup SHALL be interactive exactly when the console is interactive, by the same definition that gates
colour, and `--non-interactive` is absent. An interactive run SHALL ask for each input, showing its
default, and SHALL wait at a step that needs the user's action outside `baas`, re-checking that step
only when the user presses Enter. A non-interactive run SHALL take each input from its option or its
default, and SHALL stop with exit code 1 at the first step that needs the user's action outside
`baas`, explaining that action. Both modes SHALL perform every action setup itself can do. Setup SHALL
NOT accept `--yes`.

#### Scenario: A redirect runs unattended
- **WHEN** `baas admin deployment setup > policy.json` runs for an identity lacking rights
- **THEN** setup asks nothing, `policy.json` holds the rendered policy, and the exit code is 1

#### Scenario: The option forces unattended on a terminal
- **WHEN** `baas admin deployment setup --non-interactive` runs on a terminal and the deployer policy is missing
- **THEN** setup asks nothing, explains what to attach, and exits 1

#### Scenario: A manual step waits interactively
- **WHEN** an interactive run reaches a step the user must do outside `baas`
- **THEN** setup explains it and waits, and on Enter checks that step again before going on

#### Scenario: Re-checking is driven by the user
- **WHEN** an interactive run waits at the permission step
- **THEN** the permission check runs once per Enter press and never repeats on its own

#### Scenario: There is no `--yes`
- **WHEN** `baas admin deployment setup --yes` is invoked
- **THEN** picocli reports an unknown option error

### Requirement: Every prompt has an option that answers it
Every input setup asks for SHALL also be accepted as an option. An option given on the command line
SHALL answer its prompt in advance, so that prompt is not asked. An answer that cannot be used SHALL be
asked again interactively, whether it was typed or given as an option, and SHALL fail a
non-interactive run with exit code 1.

#### Scenario: An option skips its prompt
- **WHEN** an interactive run is given `--region us-east-1` for a new deployment
- **THEN** setup does not ask for the region and uses `us-east-1`

#### Scenario: An unusable option is asked again
- **WHEN** an interactive run is given `--deployment` naming a bucket another account owns
- **THEN** setup asks for another name rather than exiting

### Requirement: The deployment is chosen from this machine's files before any AWS call
The first step SHALL decide which deployment the run acts on from the deployments configured on this
machine and `--deployment` alone, before any AWS call. With `--deployment`, or exactly one deployment
configured, that is the deployment. With none configured, the run creates a new one. With two or more
configured and no `--deployment`, an interactive run SHALL ask which one, offering also a new
deployment, and a non-interactive run SHALL refuse listing them, as every other command does.

#### Scenario: Choosing among several
- **WHEN** an interactive setup runs with `baas-123456789012` and `wiktor-dev` configured and no `--deployment`
- **THEN** setup lists both and a new deployment as choices, and acts on the one chosen

#### Scenario: Several deployments, unattended
- **WHEN** `baas admin deployment setup --non-interactive` runs with two deployments configured and no `--deployment`
- **THEN** setup exits non-zero listing both names, and calls no AWS API

### Requirement: Credentials are shown before they are used
The credentials step SHALL identify the caller with `sts:GetCallerIdentity` and show the account and
the caller's ARN. For an existing deployment the stored deployer profile SHALL be the default.
Credentials that cannot be resolved, or an expired session, SHALL be explained with the command that
fixes them (`aws configure`, `aws configure sso`, or `aws sso login`).

#### Scenario: The identity is shown
- **WHEN** an interactive run passes the credentials step
- **THEN** the account and the caller's ARN were shown before any later step ran

#### Scenario: An expired session is explained
- **WHEN** the deployer profile's SSO session has expired
- **THEN** setup names `aws sso login` for that profile, and waits interactively or exits 1 unattended

### Requirement: A new deployment's name is suggested, never required
For a new deployment an interactive run SHALL ask for the name, suggesting `baas-<accountId>` as the
default and accepting any name the deployment name rule accepts, and SHALL ask for the region, saying
that it cannot be changed later. A name the rule refuses SHALL be asked again, naming the broken rule.
For an existing deployment the name and region SHALL be shown and not asked. A non-interactive run
SHALL take the name from `--deployment`, else derive `baas-<accountId>`, and the region from
`--region`, else the default.

#### Scenario: The suggestion is accepted
- **WHEN** a new deployment's interactive run accepts the suggested name in account `123456789012`
- **THEN** the deployment is named `baas-123456789012`

#### Scenario: Any valid name is accepted
- **WHEN** a new deployment's interactive run is given the name `team-bench`
- **THEN** the deployment is named `team-bench`, with no account identifier in it

#### Scenario: An existing deployment's region is not asked
- **WHEN** setup runs interactively for an existing deployment
- **THEN** its name and region are shown and no prompt asks for either

### Requirement: Missing deployer rights are handed over, then waited for
When the permission check finds actions missing, setup SHALL hand the rendered policy over and print
the IAM administrator's commands. Interactively, with no `--policy-file`, setup SHALL ask whether to
print the policy or save it to a file, offering to save it as `policy.json`; `--policy-file` SHALL
answer that question. It SHALL then wait, and on Enter run the permission check again. A check that
could not run SHALL be shown as not checked, never as met, and the run SHALL continue. Unattended, the
policy SHALL go to standard output as before, and setup SHALL exit 1.

#### Scenario: The user saves the policy
- **WHEN** an interactive run finds rights missing, no `--policy-file` was given, and the user accepts the default
- **THEN** `policy.json` holds the rendered policy, the administrator's commands name that file, and setup waits

#### Scenario: The user prints the policy
- **WHEN** the user chooses to print it instead
- **THEN** the rendered policy is printed, and setup waits

#### Scenario: Attached while waiting
- **WHEN** the administrator attaches the policy and the user presses Enter
- **THEN** the permission check runs again, is met, and setup continues with the names step

#### Scenario: The check cannot run
- **WHEN** the caller may not call `iam:SimulatePrincipalPolicy`
- **THEN** the step is reported as not checked, and setup continues

### Requirement: A name taken by another account is asked again
The names step SHALL run after the permission step, so that a bucket the caller cannot reach is known
to belong to another account rather than to lack the caller's rights. When the deployment's bucket
name belongs to another account, an interactive run SHALL return to the name prompt and then run the
permission step again for the new name, since the policy names the deployment. A non-interactive run
SHALL exit 1 naming the cause and `--deployment`. A leftover bucket or table in this account SHALL be
named with the command that removes it; setup SHALL NOT delete it, and SHALL wait interactively or
exit 1 unattended.

#### Scenario: Another account owns the name
- **WHEN** an interactive run's new deployment is named `wiktor-dev` and another account owns a bucket of that name
- **THEN** setup says the name belongs to another account and asks for the name again, and the next name goes through the permission check before the names check

#### Scenario: A leftover in this account
- **WHEN** a bucket named for the new deployment exists in this account with no stack of that name
- **THEN** setup names `aws s3 rb s3://<prefix> --force`, deletes nothing, and waits interactively or exits 1 unattended

### Requirement: GitHub Actions is an optional step that never touches GitHub
The GitHub Actions step SHALL ask whether continuous integration should federate into the operator
role, and for the organisation and the repositories. When federation is wanted it SHALL ensure the
account's GitHub OIDC identity provider exists, as *The CLI creates the GitHub OIDC provider and never
modifies or deletes it* states. The GitHub repository variables SHALL be printed at the done step, and
SHALL never be shown as met: setup holds no GitHub credentials and invokes no other program.

#### Scenario: Federation is skipped
- **WHEN** a new deployment's interactive run answers no to GitHub Actions
- **THEN** no identity provider is created or checked, and the stack is created without federation

#### Scenario: The variables are printed, not checked
- **WHEN** a run with federation reaches the done step
- **THEN** it prints `gh variable set OPERATOR_ROLE_ARN …` and `gh variable set AWS_REGION …` with the values filled in, and makes no claim that they are set

### Requirement: An update run asks what can change, defaulting to the current value
On an existing deployment an interactive run SHALL ask every input that can still change — the
deployer profile and the GitHub federation — with the current value as the default; accepting the
default SHALL change nothing. The name and region SHALL only be shown. The current repository list
SHALL be shown when asking about federation, so that the answer states the whole list.

#### Scenario: Adding a repository
- **WHEN** an interactive update run shows federation for `org/a`, and the user changes it to `org/a,org/b`
- **THEN** the stack is updated so the operator role trusts both repositories

#### Scenario: Defaults change nothing
- **WHEN** an interactive update run accepts every default
- **THEN** the plan is not shown and no stack operation changes anything

### Requirement: The plan is confirmed when it creates or changes something the user chose
Before the stack step, an interactive run SHALL show the plan — account, region, name, create or
update, federation, and the standing cost of the image snapshot — and ask for confirmation, when the
run creates a deployment or an answer differs from its current value. Declining SHALL change nothing
and exit 1. An update that only carries a newer template SHALL proceed without confirmation. A
non-interactive run SHALL never ask.

#### Scenario: A create is confirmed
- **WHEN** an interactive run is about to create a deployment
- **THEN** it shows the plan with about $0.20 a month for the image snapshot, and creates nothing unless confirmed

#### Scenario: Declining changes nothing
- **WHEN** the user declines the plan
- **THEN** no stack, file or provider is created or changed, and the exit code is 1

### Requirement: A stack failure on permissions returns to the permission step
When the stack operation fails for lack of permission, an interactive run SHALL hand the policy over
as the permission step does, wait, and on Enter return to the permission step and run forward again,
without asking to confirm the plan a second time. A non-interactive run SHALL exit 1 with the policy
hand-over, as before. Any other stack failure SHALL exit 1 with the stack's failure events.

#### Scenario: A stale policy found by CloudFormation
- **WHEN** the permission check could not run and the stack create fails because a resource was not authorised
- **THEN** setup hands the policy over, waits, and after Enter runs the permission check, the names check and the stack step again, creating the stack anew from `ROLLBACK_COMPLETE`

### Requirement: The operator role is proven, allowing for IAM propagation
After the stack step, setup SHALL assume `<prefix>-role-operator` from the deployer's credentials,
as day-to-day commands do. A failed assumption SHALL be retried for about 30 seconds with backoff
before it is reported, since a role created seconds earlier may not yet be usable. A failure that
outlasts the retries SHALL be handled as a permission failure of the stack step.

#### Scenario: A new role becomes assumable
- **WHEN** the first assumption right after a create is refused and a retry ten seconds later succeeds
- **THEN** the step is met and nothing is reported to the user

### Requirement: The runner image is offered, never built by surprise
When the deployment has no image, or its image's base is older than the base bundled with the CLI, an
interactive run SHALL ask whether to build it now, saying it takes about 15 minutes, with yes as the
default. A non-interactive run SHALL build only with `--build-image`. A current image SHALL never be
offered for rebuild.

#### Scenario: A new deployment builds its image
- **WHEN** a new deployment's interactive run accepts the image offer
- **THEN** the image is built and published before the done step, and the first `baas run` needs no other command

#### Scenario: A scripted re-setup does not build
- **WHEN** `baas admin deployment setup --non-interactive` runs on a deployment with no image and no `--build-image`
- **THEN** no build starts, and the done step names `baas --deployment <name> admin image build`

#### Scenario: A current image is not offered
- **WHEN** the deployment's image is built from the bundled base
- **THEN** setup does not ask about building

### Requirement: An interrupted run resumes where AWS is
A run SHALL find a stack operation still in progress, and wait for it to finish before checking the
stack again, rather than submitting an operation the stack would refuse. It SHALL find an image build
still running or finished but not published as `runner-image-provisioning` states. An interrupted
create SHALL be found from this machine's files by the next run, as *Each deployment has one
configuration file, named by the deployment* states.

#### Scenario: A closed lid during the create
- **WHEN** the laptop slept while the stack was being created, and setup runs again
- **THEN** the first step offers the deployment by its name, and the stack step waits for the create to finish instead of submitting an update

### Requirement: Setup ends with what to do next and how to repeat it
The done step SHALL print the next steps, each carrying `--deployment <name>`: the first `baas run`;
for a teammate, the `sts:AssumeRole` grant with the account and role filled in and
`baas --deployment <name> config sync --operator-aws-profile <profile>`; with federation, the GitHub
variables; and, when no image was built, `admin image build`. An interactive run that created or
changed something SHALL also print the equivalent non-interactive command, rendered from its answers,
with `--deployment` always and `--region` only for a create.

#### Scenario: The repeat command reproduces the answers
- **WHEN** an interactive run created `team-bench` in `us-east-1` with federation for `org/a`
- **THEN** it prints `baas --deployment team-bench admin deployment setup --non-interactive --region us-east-1 --github-org org --github-repo a …`, and parsing that line yields the same answers

#### Scenario: Nothing changed, nothing to repeat
- **WHEN** an interactive run changed nothing
- **THEN** no repeat command is printed
