#!/bin/zsh
# Runs benchmark-runner locally against LocalStack, with a real
# async-profiler attached. `baas run jmh -- … -prof async:…` is the remote equivalent —
# this exists to eyeball profiler output without paying for EC2.
#
# Prerequisites:
#   mvn clean package -DskipTests     (builds the runner and the fixture JAR)
#   docker-compose up                 (LocalStack on 4566)
#   aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas
#   the dynamodb create-table command in docker-compose.yaml
#   an AWS_PROFILE=localstack entry in ~/.aws/config
#   export ASYNC_PATH=/path/to/libasyncProfiler.{so,dylib}
#
# The runner requires --results-table and --s3-bucket, so this script names a LocalStack table
# and bucket explicitly.
#
# Everything after the runner's -- reaches JMH as given; the async-profiler options are JMH's own
# (`java -jar fake-jmh-benchmarks.jar -prof async:help` lists them). The runner adds dir= and
# libPath= itself. Extra arguments to this script are appended after -- too.

if [[ -z "$ASYNC_PATH" || ! -f "$ASYNC_PATH" ]]; then
  # --async-path defaults to the on-instance path (/app/async-profiler/lib/...),
  # which never exists locally, and the runner checks it before starting a -prof async job.
  echo "ASYNC_PATH must point at a local libasyncProfiler shared library." >&2
  echo "  export ASYNC_PATH=~/async-profiler/lib/libasyncProfiler.dylib" >&2
  exit 1
fi

NOW=$(date "+%Y-%m-%d_%H:%M:%S")
AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar -v jmh \
--benchmark-path=./fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar \
--project local-test \
--results-table baas-results \
--dynamodb-endpoint=http://localhost:4566 \
--s3-service-endpoint=https://s3.localhost.localstack.cloud:4566 \
--result-path=./benchmark-runner/target/$NOW \
--s3-bucket baas \
 -- \
-wi 1 -f 1 -i 2 \
-prof 'async:event=wall;threads=true;output=flamegraph' \
"$@"
