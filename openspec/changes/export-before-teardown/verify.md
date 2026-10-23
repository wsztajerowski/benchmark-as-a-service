# Verify — export-before-teardown

Live checks (tasks section 9) are recorded here as they run. The requirement → code → test → gap table
comes with `/opsx:verify` (task 10.1).

## Live checks

| Task | Date | Result |
|---|---|---|
| 9.1 | 2026-10-23 | **Pass.** Run with the reactor build, under `baas-admin-wiktor`'s current deployer policy: `java -jar baas-cli/target/baas-cli.jar --deployment baas-development admin deployment export --parts results -o <scratch>/live-9.1`. It logged `AWS refused dynamodb:Scan for baas-development (User: arn:aws:iam::381492019823:user/baas-admin-wiktor is not authorized to perform: dynamodb:Scan …)` and then `run \`baas --deployment baas-development admin deployment setup\`, which hands over the current one`. The directory held an empty `results.jsonl` and no `manifest.json`, so it is an incomplete export that import refuses. |
| 9.2–9.6a | — | Not run. 9.2 needs an IAM admin to attach the re-rendered `BaasCliDeployerPolicy-baas-development`, which `baas-admin-wiktor` cannot attach to itself. 9.4 and 9.5 need a person at a terminal: teardown and import both ask there, and neither has a `--yes`. |
