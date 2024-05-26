package pl.wsztajerowski.fake;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

public class Incrementing_Synchronized {

    @State(Scope.Benchmark)
    public static class Value {
        int val = 0;

        synchronized int incrementAndGet() {
            return ++val;
        }
    }

    @Benchmark
    @GroupThreads(2)
    public int incrementUsingSynchronized(Value value) {
        return value.incrementAndGet();
    }

}
