#!/bin/zsh

sha=$(hexdump -n 4 -v -e '/1 "%02X"' /dev/urandom)

AWS_PROFILE=localstack \
AWS_ENDPOINT=https://s3.localhost.localstack.cloud:4566 \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar jmh-with-async \
--mongo-connection-string mongodb://localhost:27017/local_test \
--async-additional-param Key1=1 \
--async-additional-param Key2=2 \
--async-additional-param Key3=3 \
--s3-result-prefix=sePrefix \
--request-id=req1 \
--benchmark-path=./benchmark-runner/target/jmh-benchmarks.jar \
--machine-readable-output ./benchmark-runner/target/jmh-results.json \
--process-output ./benchmark-runner/target/jmh-output.txt \
 -wi 1 -f 1 -i 2 \
"$@"