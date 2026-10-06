# Archive-time renames (design D5)

Not an OpenSpec artifact. Applied by hand in the editorial commit after `openspec archive`.

## Capability directories

| From | To |
|---|---|
| `run-artifact-layout` | `job-artifact-layout` |
| `run-identity` | `job-identity` |
| `run-tracking` | `job-tracking` |

## Scenario titles

| From | To |
|---|---|
| All benchmarks of a run are returned | All benchmarks of a job are returned |
| Unknown request ID returns nothing | Unknown job ID returns nothing |
| Excluded run is omitted | Excluded job is omitted |
| An explicit run lookup returns an excluded run | An explicit job lookup returns an excluded job |
| `--all-runs` includes excluded rows | `--all-jobs` includes excluded rows |
| Every project and every run is the whole table | Every project and every job is the whole table |
| A successful excluded run reports its own measurements | A successful excluded job reports its own measurements |
| Best of several runs is kept | Best of several jobs is kept |
| `--all-runs` reports every row | `--all-jobs` reports every row |
| Whole run is retrieved | Whole job is retrieved |
| A run identifier is resolved through the index | A job identifier is resolved through the index |
| A failed run is retrievable by identifier | A failed job is retrievable by identifier |
| A run from before run items resolves | A job from before job items resolves |
| Unknown run reports clearly | Unknown job reports clearly |
| One job, one run | One CI job, one job |
| Credentials outlive the run they poll | Credentials outlive the job they poll |
| An excluded run is still assertable | An excluded job is still assertable |
| A failed run is diagnosable | A failed job is diagnosable |
| Assertion and run agree by construction | Assertion and job agree by construction |
| The stored run carries the workflow's values | The stored job carries the workflow's values |
| The JCStress run is asserted by shape | The JCStress job is asserted by shape |
| A run lookup does not take a project | A job lookup does not take a project |
| A failed run still reports its identifier | A failed job still reports its identifier |
| No lifecycle rule expires run artifacts | No lifecycle rule expires job artifacts |
| Abort when a run is in flight | Abort when a job is in flight |
| Abort when a run is still booting | Abort when a job is still booting |
| Runner can record its run's status | Runner can record its job's status |
| Operator can query the request-ID index | Operator can query the job-ID index |
| Operator can record a run's status | Operator can record a job's status |
| A session outlasts the run it polls | A session outlasts the job it polls |
| An unknown mode fails the run visibly | An unknown mode fails the job visibly |
| A JMH run writes one item per benchmark method | A JMH job writes one item per benchmark method |
| A run's results are reachable by request ID | A job's results are reachable by job ID |
| A JCStress run keeps its summary shape | A JCStress job keeps its summary shape |
| A laptop run is tagged as local | A job launched from a laptop is tagged as local |
| A continuous-integration run is tagged as such | A CI-launched job is tagged as such |
| A local run names a local table | A local job names a local table |
| A completed run is one prefix | A completed job is one prefix |
| A failed run is still one prefix | A failed job is still one prefix |
| The project segment identifies an unmeasured run | The project segment identifies an unmeasured job |
| A development runner override is per-run | A development runner override is per-job |
| One copy serves many runs | One copy serves many jobs |
| CI runs are attributed | CI jobs are attributed |
| An older run resolves after the layout changes | An older job resolves after the layout changes |
| Two runs in the same millisecond do not collide | Two jobs in the same millisecond do not collide |
| One run's measurements share one timestamp | One job's measurements share one timestamp |
| Two runs are distinguishable in the output | Two jobs are distinguishable in the output |
| One query lists every project's runs | One query lists every project's jobs |
| A run is found by its identifier | A job is found by its identifier |
| Observed tags stay off the run item | Observed tags stay off the job item |
| A refused run leaves no item | A refused job leaves no item |
| A launched run records its instance | A launched job records its instance |
| A run cancelled while launching does not run | A job cancelled while launching does not run |
| A successful run reports completion | A successful job reports completion |
| A run cancelled during its launch does not run | A job cancelled during its launch does not run |
| A run that died early | A job that died early |
| Interrupt cancels the run | Interrupt cancels the job |
| Interrupt just after the run completed | Interrupt just after the job completed |
| The poll cap is reached as the run completes | The poll cap is reached as the job completes |
| A filter that matches older runs | A filter that matches older jobs |
| Many vanished runs | Many vanished jobs |
| CI runs are not hidden | CI jobs are not hidden |
| Terminating an in-flight run | Terminating an in-flight job |
| A run launched before run items existed | A job launched before job items existed |
| A finished run is left alone | A finished job is left alone |
| A project with only failed runs is not offered | A project with only failed jobs is not offered |
| Every-project report ignores runs | Every-project report ignores job items |
| Results by run identifier | Results by job identifier |
| The runner cannot forge a run outside its partition | The runner cannot forge a job outside its partition |
| Manifest accompanies a successful run | Manifest accompanies a successful job |
| Manifest survives a failed run | Manifest survives a failed job |
| Manifest identifies a run that stored nothing | Manifest identifies a job that stored nothing |
| Runs named by id | Jobs named by id |
| An unknown run id | An unknown job id |
| Two runs from one CLI version execute one runner build | Two jobs from one CLI version execute one runner build |
| First run of a version seeds the slot | First job of a version seeds the slot |
| Later runs reuse the slot | Later jobs reuse the slot |
