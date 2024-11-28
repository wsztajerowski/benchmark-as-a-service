#!/bin/zsh
#  event=<event>                    Event to sample: cpu, alloc, lock, wall, itimer;
#                                   com.foo.Bar.methodName; any event from `perf list`
#                                   e.g. cache-misses (default: [cpu])
#  threads=<bool>                   Profile threads separately.

AWS_PROFILE=localstack \
exec java -jar ./benchmark-runner/target/benchmark-runner.jar jmh-with-async \
--async-additional-param event=wall \
--async-additional-param threads=true \
--benchmark-path=./benchmark-runner/target/jmh-benchmarks.jar \
--mongo-connection-string mongodb://localhost:27017/local_test \
--s3-service-endpoint=https://s3.localhost.localstack.cloud:4566 \
--s3-bucket baas \
 -wi 1 -f 1 -i 2 \
"$@"