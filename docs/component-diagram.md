```mermaid
stateDiagram-v2
    Script --> RepoApp : (local code)
    Script --> CLI : uses gh, git, mvn, mongosh
    Script --> S3 : upload jmh-benchmarks.jar
    Script --> RepoBaaS : gh workflow run (passes inputs)\nrequest_id, results_path,\nbenchmark_type, benchmark_path, parameters
    GHActions --> Orchestrator : runs workflow steps to provision\nand schedule benchmark job
    Orchestrator --> Worker : provision/start worker(s)
    Worker --> S3 : download jar / upload results
    Worker --> MongoDB : write benchmark documents
    GHActions --> Script : run status (Script polls using gh run list/wait script)
    Script --> MongoDB : (optionally) query results via mongosh
    Script --> Developer : prints results & S3 link
note left of Script
note right of Worker
```