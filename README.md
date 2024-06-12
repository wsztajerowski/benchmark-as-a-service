# Benchmark as a Service (BaaS)

## Modules
### benchmark-runner

Self-executing JAR for running JMH/JCStress benchmarks.

### s3-hook-lambda  

AWS Lambda triggered by S3 hook and launching GitHub Action workflow for creating benchmark running env. Details: [README.md](s3-hook-lambda%2FREADME.md)

### infra  

CloudFormation's templates for setting up all required infrastructure on AWS. See details: [README.md](infra%2FREADME.md)

### GitHub Action workflow

Workflow for creating AWS environment and launching benchmarks using [benchmark-runner](benchmark-runner). Workflow file: [benchmark-runner.yml](.github%2Fworkflows%2Fbenchmark-runner.yml)

In order to use this workflow, you need to set following GHA settings:

Secrets
 - WORKFLOW_ROLE_ARN - created by CF template - you can find it in stack outputs
 - RUNNER_ROLE_NAME - created by CF template - you can find it in stack outputs
 - MONGO_CONNECTION_STRING - ConnectionString for MongoDB instance, which will keep benchmark results. It needs to contain database. See:benchmark-runner/pl.wsztajerowski.commands.ApiCommonSharedOptions.mongoConnectionString
 - GHA_EC2_PAT - GitHub classic token with `repo` scope

Variables
- SUBNET_ID - AWS subnet ID where EC2 instance with benchmark env should be created
- SECURITY_GROUP_ID - SecurityGroup ID used during creating EC2 instance
- AWS_REGION - AWS region where all infrastructure have been created
- ASYNC_PROFILER_VERSION - Async Profiler version (in format `MAJOR.MINOR`) used within jmh-with-async benchmark type

## Launch benchmark

Assuming all CloudFormation stacks have been created and having benchmark JAR file, create a JSON file request with following format:

```NOTE
Provided parameters are examples - for more see benchmark-runner options!
```

```json
{
  "request_id": "REQUEST_ID",
  "result_prefix": "FOLDER_WITHIN_PROVIDED_S3_BUCKET",
  "s3_result_bucket": "S3_BUCKET_NAME_RETURNED_BY_MAIN_CLOUDFORMATION_STACK",
  "benchmark_path": "s3://MAIN_S3_BUCKET/requests/my-request-1/benchmark.jar",
  "benchmark_type": "jmh-with-async",
  "parameters": "--async-path /home/ec2-user/async-profiler/lib/libasyncProfiler.so -wi 1 -i 2 -f 1"
}
```
Next, upload benchmark file:

```bash
aws s3 --profile YOUR-PROFILE cp path/to/benchmark.jar s3://MAIN_S3_BUCKET/requests/my-request-1/benchmark.jar
```

and finally upload request file:

```bash
aws s3 --profile YOUR-PROFILE cp path/to/request.json s3://MAIN_S3_BUCKET/requests/my-request-1/request.json
```