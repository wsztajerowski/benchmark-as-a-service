package pl.wsztajerowski.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkProcessBuilder {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkProcessBuilder.class);
    List<String> commands = new ArrayList<>();
    Map<String, Path> pathPrefixes = new LinkedHashMap<>();
    Path outputPath;

    private BenchmarkProcessBuilder(){
        outputPath = Paths.get("output.txt");
    }

    /** {@code java -jar <benchmark>} on {@code java} from {@code PATH}. */
    public static BenchmarkProcessBuilder benchmarkProcessBuilder(Path benchmarkPath) {
        return benchmarkProcessBuilder("java", benchmarkPath);
    }

    /** {@code <java> -jar <benchmark>}: the benchmark process, and every fork it starts, run on {@code java}. */
    public static BenchmarkProcessBuilder benchmarkProcessBuilder(String java, Path benchmarkPath) {
        return new BenchmarkProcessBuilder()
            .addArgument(java)
            .addArgument("-jar")
            .addArgument(benchmarkPath.toString());
    }

    /** The command line built so far, for tests that pin what reaches the benchmark process. */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /**
     * The variables this process gets {@code directory} put in front of, separated from any value it
     * inherits — by name, so a test can pin them without starting anything.
     */
    public Map<String, Path> pathPrefixes() {
        return Map.copyOf(pathPrefixes);
    }

    /** Puts {@code directory} first in the path-list variable {@code name}, keeping what it inherits. */
    public BenchmarkProcessBuilder prependToPathVariable(String name, Path directory) {
        pathPrefixes.put(name, directory);
        return this;
    }

    /** {@link #pathPrefixes()} applied to {@code environment}, the process's inherited one. */
    void applyPathPrefixes(Map<String, String> environment) {
        pathPrefixes.forEach((name, directory) -> {
            String inherited = environment.get(name);
            environment.put(name, inherited == null || inherited.isEmpty()
                ? directory.toString()
                : directory + File.pathSeparator + inherited);
        });
    }

    public BenchmarkProcessBuilder withOutputPath(Path path){
        this.outputPath = path;
        return this;
    }

    public BenchmarkProcessBuilder addArgumentWithValue(String name, Object value) {
        commands.add(name);
        commands.add(value.toString());
        return this;
    }

    public BenchmarkProcessBuilder addArgument(String argument) {
        commands.add(argument);
        return this;
    }

    public BenchmarkProcessBuilder addOptionalArgument(String argument) {
        if (argument != null){
            commands.add(argument);
        }
        return this;
    }

    public BenchmarkProcessBuilder addArgumentIfValueIsNotNull(String name, Object value) {
        if (value != null){
            commands.add(name);
            commands.add(value.toString());
        }
        return this;
    }

    public Process buildAndStartProcess() {
        ProcessBuilder processBuilder = new ProcessBuilder(commands);
        applyPathPrefixes(processBuilder.environment());
        try {
            logger.debug("Running process: {}", processBuilder.command());
            processBuilder.redirectErrorStream(true); // redirect error stream to standard output stream
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            processBuilder.redirectOutput(outputPath.toFile());
            Process process = processBuilder.start();
            return process;
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
    }
}
