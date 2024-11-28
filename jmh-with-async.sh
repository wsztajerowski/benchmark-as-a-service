#!/bin/zsh

AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar jmh-with-async \
--mongo-connection-string mongodb://localhost:27017/local_test \
--benchmark-path=./benchmark-runner/target/jmh-benchmarks.jar \
 -wi 1 -f 1 -i 2 \
"$@"