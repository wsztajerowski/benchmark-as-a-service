# Archive-time renames

Not an OpenSpec artifact. OpenSpec refuses to rename a scenario inside a MODIFIED block, so these
titles keep their old wording in the deltas and are changed by hand in the editorial commit after
`openspec archive` (design D12).

## Scenario titles

| Capability | Requirement | From | To |
|---|---|---|---|
| `benchmark-results-query` | Excluded results are filtered out | `--all-jobs` includes excluded rows | `--show-excluded` includes excluded rows |
| `core-stack-provisioning` | Teardown safety gates | Bucket retained unless explicitly deleted | The prompt says everything will be deleted |
| `results-store-schema` | Results table configuration | Benchmark history survives teardown | Benchmark history leaves with the teardown |
| `cli-command-structure` | `baas jobs diff` compares two jobs' environments | Command is top-level, not under admin | Diff is a verb of the jobs noun |
