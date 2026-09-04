#!/bin/zsh
# Prerequisites: see the header of jmh-with-async.sh. The results table must exist —
# docker-compose.yaml carries the create-table command.
#
# Absent store configuration is now a hard failure rather than a silent discard, so this
# script names one explicitly. Swap --results-table/--dynamodb-endpoint for --no-database
# to run without storing anything.
#
# NOTE: --mongo-connection-string defaults from $MONGO_CONNECTION_STRING. If you have that
# exported, naming a table too is rejected as ambiguous — unset it for local runs.
NOW=$(date "+%Y-%m-%d_%H:%M:%S")
AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar -v jmh-with-prof \
--profiler 'gc=churn=false;alloc=false' \
--profiler comp \
--profiler cl \
--profiler 'jfr=stackDepth=20' \
--benchmark-path=./fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar \
--project local-test \
--results-table baas-results \
--dynamodb-endpoint=http://localhost:4566 \
--s3-service-endpoint=https://s3.localhost.localstack.cloud:4566 \
--result-path=./benchmark-runner/target/$NOW \
--s3-bucket baas \
 -wi 1 -f 1 -i 1 \
"$@"