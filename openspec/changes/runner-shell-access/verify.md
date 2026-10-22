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
