package pl.wsztajerowski.services;

public class JmhUtils {

    public static String getProfilerOutputDirSuffix(String mode) {
        return switch (mode) {
            case "thrpt" -> "-Throughput";
            case "avgt" -> "-AverageTime";
            case "sample" -> "-SampleTime";
            case "ss" -> "-SingleShotTime";
            default -> throw new IllegalArgumentException("Unknown benchmark mode: " + mode);
        };
    }
}
