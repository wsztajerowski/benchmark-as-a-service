# Verify — export-before-teardown

Live checks (tasks section 9) are recorded here as they run. The requirement → code → test → gap table
comes with `/opsx:verify` (task 10.1).

## Live checks

| Task | Date | Result |
|---|---|---|
| 9.1 | 2026-10-23 | **Pass.** Run with the reactor build, under `baas-admin-wiktor`'s current deployer policy: `java -jar baas-cli/target/baas-cli.jar --deployment baas-development admin deployment export --parts results -o <scratch>/live-9.1`. It logged `AWS refused dynamodb:Scan for baas-development (User: arn:aws:iam::381492019823:user/baas-admin-wiktor is not authorized to perform: dynamodb:Scan …)` and then `run \`baas --deployment baas-development admin deployment setup\`, which hands over the current one`. The directory held an empty `results.jsonl` and no `manifest.json`, so it is an incomplete export that import refuses. |
| 9.2 | 2026-10-23 | **Pass.** The re-rendered policy (`~/.baas/deployer-policy.baas-development.json`, 4947 non-whitespace characters) was attached by an IAM admin. `baas --deployment baas-development admin deployment setup --non-interactive` logged `Deployer permissions: met` and `Stack baas-development is already up to date`, found runner image 1.4.0 current, started no build and exited 0. As an extra check, `admin deployment export --parts results,extension` then completed: 14 items (6 measurements, 8 jobs), the same count as the direct table scan in task 1.1, and `extensionHash: null`, since `baas-development` holds no extension. |
| 9.3–9.6a | — | Not run yet. 9.3 needs an extension pushed (`admin image build --extension`, a paid build of about 15 minutes). 9.4 and 9.5 need a person at a terminal. |
