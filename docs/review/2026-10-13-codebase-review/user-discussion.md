# Proposals to discuss with users

Features the 2026-10-13 review found missing, which the triage (2026-10-20) decided not to build
until the people using BaaS say they want them. Each entry is written to be shown to a user as is:
the problem in their terms, the proposal, and the questions to ask them. A proposal that users want
becomes an OpenSpec change; one they do not is deleted here, and its finding closed in
[alive-findings.md](alive-findings.md).

## G3 — Correcting a measurement after its run

**The problem.** A result can be hidden from `baas results query` and `--best-per` only by tagging
it `exclude_from_results=true` when it is launched. Once a job has run, its measurements cannot be
re-tagged or hidden. A job run with a wrong flag, on a noisy host, or before a fix landed, stays in
every default listing and every "best" for as long as the deployment exists. The only way out today
is a raw `aws dynamodb update-item` under credentials an operator does not have.

**The proposal.**

```
baas results tag --job-id <jobId> --set exclude_from_results=true
baas results tag --job-id <jobId> --set branch=main
baas results tag --job-id <jobId> --unset branch
```

- The unit is the whole job: every measurement it recorded is re-tagged together.
- Tags a machine observed (`imageVersion`, `instanceType`, `jdk`, `jvmVendor`, `cpuModel`,
  `cpuArch`) and `type` and `project` are refused, as `baas run` refuses them, so a result's tags
  still agree with the `environment.json` it was measured on.
- Scores and every other measured value stay immutable. Deleting results is a separate question
  (finding G15, prune).
- Who may run it is open:
  - **operator** (`baas results tag`): the stack grants operators `UpdateItem` on measurement items,
    restricted to the `tags` attribute, so a correction can never touch a score. CI could exclude
    its own runs afterwards;
  - **admin only** (`baas admin results tag`): no new operator right, but every correction needs
    deployer credentials.

  The review recommends the operator form.

**Questions for users.**

1. Have you had a result you wanted hidden or corrected after the run? What was wrong with it?
2. Is hiding enough, or do you need to delete results?
3. Should anyone who can run jobs be able to re-tag results, or only an admin?
4. Is the whole job the right unit, or do you need to correct a single benchmark within a job?

## G4 — Comparing scores between jobs or against a baseline

**The problem.** BaaS can tell you whether two jobs ran on the same environment (`baas jobs diff`),
but not whether their scores differ. `baas results query --job-id` takes one job. Answering "did my
branch make this benchmark slower than `main`?" — and failing a pull request when it did — means
running two queries and joining them with `jq` on benchmark, mode and parameters, which every user
writes for themselves.

**The proposal.**

```
baas results compare <jobA> <jobB>
baas results compare --tag branch=main --tag branch=my-feature --threshold 5
baas results compare <jobA> <jobB> --format json
```

- Each side is a job id or a tag filter; benchmarks are matched on project, benchmark, parameters
  and mode, the same way `--best-per` matches them.
- For each benchmark: both scores, the change in percent, and whether the two error intervals
  overlap (an overlap means the difference may be noise).
- With `--threshold <pct>`, exits non-zero when any benchmark got worse by more than that, which
  makes it a CI regression gate. Without it, it only reports.
- Warns, or refuses, when the two sides ran on different environments — the comparison `jobs diff`
  already makes.

**Questions for users.**

1. Do you compare results between runs today? How — by eye, a spreadsheet, a script?
2. What do you compare against: the previous run, a fixed baseline job, or the latest result on a
   branch?
3. Would you gate pull requests on it? If so, what threshold, and should overlapping error
   intervals still fail?
4. When the two sides ran on different environments, should the comparison warn or refuse?

## G8 — Recording what a job ran

The triage treats this as part of a larger design of what BaaS records about a result, not as a
field-by-field addition. Users' answers should shape that design.

**The problem.** A job's record says who ran it, when, on which instance and with which tags. It
does not say what ran: the benchmark arguments after `--` (`-f 1` or `-f 5`), the runner version
or whether `--runner-jar` replaced it, the timeout, the benchmark JAR's digest, or JCStress's
`--mode`. The arguments existed only in the instance's startup script, which is gone with the
instance. When two results differ because of how they were run, no `baas` command can say so. The
only trace is the banner at the top of `jmh-output.txt`.

**The starting proposal.**

- At launch, the CLI records on the job: the arguments, the runner version (or that it was
  overridden), the timeout and watchdog margin, and the benchmark JAR's SHA-256. JCStress's mode
  becomes a tag. All of these are known to the CLI before the instance starts.
- `baas jobs show` and `baas run --format json` show them; `baas jobs diff` compares them as an
  "invocation" group beside the environment.
- Jobs recorded before the change show these fields as absent.

**Questions for users.**

1. Have you had two results you could not explain, which turned out to be run differently?
2. Which of these would you want to see or compare: arguments, runner version, timeout, JAR digest,
   JCStress mode? Anything missing — the benchmark's source commit, for example?
3. Should any of them be queryable, like tags (`--tag runnerVersion=7.2.0`), or is seeing them on
   one job enough?
4. Could your benchmark arguments ever contain something that should not be stored, such as a
   credential passed through `-jvmArgs`?
