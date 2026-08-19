#!/bin/zsh
# Runs benchmark-runner locally against LocalStack + local MongoDB, with a real
# async-profiler attached. `baas run jmh-with-async` is the remote equivalent —
# this exists to eyeball profiler output without paying for EC2.
#
# Prerequisites:
#   mvn clean package -DskipTests     (builds the runner and the fixture JAR)
#   docker-compose up                 (LocalStack on 4566, MongoDB on 27017)
#   aws --endpoint-url=http://localhost:4566 --profile localstack s3 mb s3://baas
#   the dynamodb create-table command in docker-compose.yaml
#   an AWS_PROFILE=localstack entry in ~/.aws/config
#   export ASYNC_PATH=/path/to/libasyncProfiler.{so,dylib}
#
# Absent store configuration is now a hard failure rather than a silent discard, so this
# script names one explicitly. Swap --results-table/--dynamodb-endpoint for --no-database
# to run without storing anything.
#
# NOTE: --mongo-connection-string defaults from $MONGO_CONNECTION_STRING. If you have that
# exported, naming a table too is rejected as ambiguous — unset it for local runs.
#
# Async profiler options passed via --async-additional-param:
#  event=<event>                    Event to sample: cpu, alloc, lock, wall, itimer;
#                                   com.foo.Bar.methodName; any event from `perf list`
#                                   e.g. cache-misses (default: [cpu])
#  threads=<bool>                   Profile threads separately.

if [[ -z "$ASYNC_PATH" || ! -f "$ASYNC_PATH" ]]; then
  # --async-path defaults to the on-instance path (/app/async-profiler/lib/...),
  # which never exists locally, and the runner validates it before doing any work.
  echo "ASYNC_PATH must point at a local libasyncProfiler shared library." >&2
  echo "  export ASYNC_PATH=~/async-profiler/lib/libasyncProfiler.dylib" >&2
  exit 1
fi

NOW=$(date "+%Y-%m-%d_%H:%M:%S")
AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar -v jmh-with-async \
--async-additional-param event=wall \
--async-additional-param threads=true \
--benchmark-path=./fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar \
--project local-test \
--results-table baas-results \
--dynamodb-endpoint=http://localhost:4566 \
--s3-service-endpoint=https://s3.localhost.localstack.cloud:4566 \
--result-path=./benchmark-runner/target/$NOW \
--s3-bucket baas \
 -wi 1 -f 1 -i 2 \
"$@"
