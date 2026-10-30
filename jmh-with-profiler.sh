#!/bin/zsh
# Prerequisites: see the header of jmh-with-async.sh. The results table must exist —
# docker-compose.yaml carries the create-table command.
#
# The runner requires --results-table and --s3-bucket, so this script names a LocalStack table
# and bucket explicitly. Everything after the runner's -- reaches JMH as given, this script's own
# arguments included.
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
-wi 1 -f 1 -i 1 \
-prof 'gc:churn=false;alloc=false' \
-prof comp \
-prof cl \
-prof 'jfr:stackDepth=20' \
"$@"