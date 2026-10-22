# Verify — runner-shell-access

*Draft: live evidence gathered during apply. The requirement → code → test → gap table is written by
task 8.1.*

## Live checks on `baas-development` (2026-10-22, this branch's JAR and stack)

- Setup (update) with the branch's template: the deployer preflight first refused the missing
  `SsmSessionDocument` statement and created nothing; after the user published the new policy version,
  setup passed the preflight and updated the stack in about a minute.
- Job `20261022T125053111Z-7fcf8587` (c5.large): agent `Online` 24 s after launch.
  `StartSession` with `baas-development-session-runner` allowed; own `TerminateSession` allowed;
  no document, `SSM-SessionManagerRunShell` and `AWS-StartPortForwardingSession` denied. `jobs connect`
  after the job completed: refused, naming `completed`.
- Job `20261022T130353548Z-c4baa457` (c5.large): `jobs connect` while booting → "not reachable yet";
  ~20 s later a shell as `ssm-user` in `/app`, `exit` → CLI exit 0; deadline line printed
  ("about 13:49 UTC").
- Agent cost, same job, 8.5 min uptime / 7.5 min benchmarking, one session attached:
  `amazon-ssm-agent` and `ssm-agent-worker` under 1 s CPU each; the forked benchmark JVM 899 s.
  Under about 0.1% of the benchmark's CPU. One job only.
- `ps` showed a session `baas-cli-25vqp7s7liiyndc5x5cjkku7qa` (started ~13:07 UTC): the user's own,
  testing what happens when the instance terminates under a session. It **hung** on the dead prompt
  after the instance terminated itself at 13:15:42 UTC — Session Manager does not close a session
  whose target is gone, and the plugin has no escape key. Ended server-side with `TerminateSession`.
  This broke the requirement "when the instance terminates, the session ends"; fixed in `connect`
  (instance check every 15 s while the plugin runs).
- After the fix, job `20261022T132452435Z-20d8c421`: a session held open with no input; when the job
  finished and the instance terminated, `connect` printed "Instance i-050368d9e84ee25df has terminated;
  ending the session.", the plugin left on its own ("Exiting session…") and the CLI exited 0.

- **Task 6.1, second deployment cycle** (2026-10-22, 15:40–16:19 local): `admin image build` built
  and published 1.4.0 (`ami-093dffb26a7a142c6`) — the contract's test phase passed, the new
  `amazon-ssm-agent` check included; a job on it (`20261022T135014998Z-f1242a48`) was connected to
  (`/etc/baas-image-version` = 1.4.0, `ssm-user`, `/app`, exit 0); `admin deployment teardown --yes`
  emptied the bucket, deleted the stack and retired the image, and `describe-document` then answered
  *does not exist*; `admin deployment setup --build-image` (same region and GitHub federation)
  recreated the stack — the deployer preflight passing — and image 1.4.0 (`ami-098da03cae0f78b8e`),
  with `baas-development-session-runner` back, `Active`, version 1.

## Score comparison (task 6.3) — data, saved before the 6.1 teardown deleted the table

`fake-jmh-benchmarks` `Incrementing_Synchronized`, c5.large, thrpt, `-f 1 -r 10s`, all on
`baas-development`. Unregistered = arm A of the 2026-10-21 spike (the agent as before this change);
registered = arm B and every job since this change's grants were deployed.

| Job | Agent | Image | CPU | Iters | Mean ops/s | Within-job shape |
|---|---|---|---|---|---|---|
| 0b7e3f4c | unregistered | 1.3.1 | 8124M | 10 | 63.04M | flat (cv 1.3%) |
| a3ec6845 | unregistered | 1.3.1 | 8124M | 10 | 61.16M | |
| 6e5c1e37 | unregistered | 1.3.1 | 8124M | 10 | 62.13M | |
| a67884d3 | unregistered | 1.3.1 | 8124M | 10 | 60.70M | |
| 2ca66034 | unregistered | 1.3.1 | 8275CL | 10 | 65.48M | |
| cdba0f3a | unregistered | 1.3.1 | 8275CL | 10 | 58.49M | |
| c2a7f558 | registered | 1.3.1 | 8124M | 10 | 63.07M | |
| 055d670e | registered | 1.3.1 | 8124M | 10 | 46.45M | flat but low (cv 0.2%) |
| 0ec22d3d | registered | 1.3.1 | 8124M | 10 | 59.05M | |
| 84b27bec | registered | 1.3.1 | 8124M | 10 | 62.37M | |
| d8bb525e | registered | 1.3.1 | 8124M | 10 | 61.79M | |
| 1fc3facb | registered | 1.3.1 | 8223CL | 10 | 61.74M | |
| 7fcf8587 | registered, probes | 1.3.1 | 8124M | 60 | 57.02M | **two levels**: ~47.5M (iters 1–5, 12–29) / ~62–63M |
| c4baa457 | registered, 2 sessions | 1.3.1 | 8275CL | 60 | 64.50M | flat (cv 1.1%) |
| 20d8c421 | registered, idle session | 1.3.1 | 8124M | 6 | 61.81M | flat |
| f1242a48 | registered, session at start | 1.4.0 | 8223CL | 12 | 57.14M | **step**: ~63M (1–5) then 53.3M flat (6–12) |
| a5022e56 | unregistered (2026-10-15) | 1.3.0 | 8124M | 1 | 56.79M | one iteration |

Reading: the *fast* level is the same with and without registration (~61–63M on 8124M). What differs is
a **slow plateau** — a steady 15–25% drop lasting minutes, then sometimes back — seen in 3 of 10
registered jobs and 0 of 6 unregistered ones. The unregistered jobs ran only 100 s each (600 s in
all) against ~1900 s registered, so the counts alone cannot separate "the agent" from "rare and
longer runs see it more". In `7fcf8587` the first slow block (12:51:38–12:52:28 UTC) overlaps the
agent's first minute after registering and the operator's `StartSession` probes; the second
(12:53:28–12:56:28) overlaps nothing known. A drop of ~24% held steady is the signature of the core's
sibling hyperthread being busy (a c5.large is one core, two threads); the agent's own measured CPU
(under 1 s per process over 8.5 min in `c4baa457`) argues against the agent, but that job never hit
the slow level. **Unexplained — not accepted.** See W1.
