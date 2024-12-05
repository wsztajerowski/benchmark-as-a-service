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

## Local run

### Prerequisites

### Useful endpoints

 - Local S3 browser: http://localhost:4566/baas/ (http://localhost:4566/S3_BUCKET)
 - Local S3 document: http://localhost:4566/baas/test-request.json (http://localhost:4566/S3_BUCKET/OBJECT_KEY)
 - MongoDB viewer: http://localhost:8081/

### Run Benchmark Runner 
The easiest way is to run shell script: 
```bash 
docker-compose up
jmh-with-async.sh
```

### Run GitHub workflow locally

```bash
act -W .github/workflows/exec-single-benchmark.yml \
--secret-file .github/test/.secrets \
--var-file .github/test/.vars \
-e .github/test/exec-single-benchmark-act-payload.json
```

## E2E test

See: [README.md](.github%2Ftest%2FREADME.md)

### Required tools
 - act (https://nektosact.com/introduction.html)
 - docker-compose
 - localstack (can be used as a docker container - see docker compose)
 - aws cli (https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
 - mongosh (https://www.mongodb.com/docs/mongodb-shell/install/)

### Run
```bash
/bin/bash .github/test/exec-single-benchmark-e2e-test.sh
```