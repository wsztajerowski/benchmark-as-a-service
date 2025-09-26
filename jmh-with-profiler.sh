#!/bin/zsh
NOW=$(date "+%Y-%m-%d_%H:%M:%S")
AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar -v jmh-with-prof \
--profiler 'gc=churn=false;alloc=false' \
--profiler comp \
--profiler cl \
--profiler 'jfr=stackDepth=20' \
--benchmark-path=./fake-jmh-benchmarks/target/fake-jmh-benchmarks.jar \
--mongo-connection-string mongodb://localhost:27017/local_test \
--s3-service-endpoint=https://s3.localhost.localstack.cloud:4566 \
--result-path=./benchmark-runner/target/$NOW \
--s3-bucket baas \
 -wi 1 -f 1 -i 1 \
"$@"