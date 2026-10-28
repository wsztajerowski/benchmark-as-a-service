package pl.wsztajerowski.fake;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * A {@code @Param} sweep: JMH names each variant's profiler directory after its parameters, which is
 * what a runner predicting {@code <benchmark>-<Mode>} never uploaded (issue #115).
 */
@State(Scope.Benchmark)
public class Summing_Array {

    @Param({"1", "2"})
    int size;

    int[] values;

    @Setup
    public void setUp() {
        values = new int[size];
    }

    @Benchmark
    public int sum() {
        int sum = 0;
        for (int value : values) {
            sum += value;
        }
        return sum;
    }

}
