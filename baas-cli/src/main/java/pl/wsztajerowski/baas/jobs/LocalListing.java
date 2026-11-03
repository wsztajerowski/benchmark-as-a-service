package pl.wsztajerowski.baas.jobs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@code baas run --dry-run}'s check (G9): the benchmark JAR's own listing, run on this machine with
 * the job's arguments followed by {@code -l}. JMH and JCStress parse every option before they list,
 * so a mistyped one fails here for free rather than on a booted instance. The JAR is the one place
 * the right JMH or JCStress version is already present, so nothing of either is on the CLI's
 * classpath.
 */
final class LocalListing {

    /** How long a listing may take: JCStress probes the CPU count first. */
    static final long LIMIT_SECONDS = 120;

    /**
     * A listed benchmark or test: a dotted Java name alone on its line. Every header either tool
     * prints ({@code Benchmarks: }, {@code Java Concurrency Stress Tests}, {@code Rev: …},
     * {@code Burning up …}) and every JVM warning holds a space or a colon, so none of them matches.
     * {@code LocalListingIT} pins this against both fixture JARs.
     */
    static final Pattern NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");

    /** What the listing did: its exit code ({@code -1} when it was killed at the limit) and output. */
    record Result(int exitCode, String output) {
        boolean timedOut() {
            return exitCode == -1;
        }

        List<String> names() {
            return output.lines().map(String::strip).filter(line -> NAME.matcher(line).matches()).toList();
        }
    }

    /** Starts a listing; replaced in tests. */
    interface Runner {
        Result run(List<String> command) throws IOException, InterruptedException;
    }

    private LocalListing() {
    }

    /** {@code <java.home>/bin/java -jar <jar> <args…> -l}, on the JVM running this CLI. */
    static List<String> command(Path javaHome, Path jar, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(javaHome.resolve("bin").resolve("java").toString());
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(args);
        command.add("-l");
        return command;
    }

    /** The output goes through a file, so a listing that never ends cannot block the read. */
    static Result run(List<String> command) throws IOException, InterruptedException {
        Path output = Files.createTempFile("baas-dry-run", ".txt");
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
            if (!process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                return new Result(-1, Files.readString(output, StandardCharsets.UTF_8));
            }
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(output);
        }
    }
}
