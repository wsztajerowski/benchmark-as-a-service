package pl.wsztajerowski.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.FileUtils;
import pl.wsztajerowski.JavaWonderlandException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkProcessBuilder {
    private static final Logger logger = LoggerFactory.getLogger(BenchmarkProcessBuilder.class);
    List<String> commands = new ArrayList<>();
    Path outputPath;

    private BenchmarkProcessBuilder(){
        outputPath = Paths.get("output.txt");
    }

    public static BenchmarkProcessBuilder benchmarkProcessBuilder(Path benchmarkPath) {
        return new BenchmarkProcessBuilder()
            .addArgument("java")
            .addArgument("-jar")
            .addArgument(benchmarkPath.toString());
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
        try {
            logger.debug("Running process: {}", processBuilder.command());
            processBuilder.redirectErrorStream(true); // redirect error stream to standard output stream
            FileUtils.ensurePathExists(outputPath);
            processBuilder.redirectOutput(outputPath.toFile());
            Process process = processBuilder.start();
            return process;
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
    }
}
