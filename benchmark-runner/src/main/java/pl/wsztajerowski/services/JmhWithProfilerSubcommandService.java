package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.entities.jmh.BenchmarkMetadata;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.entities.jmh.JmhBenchmarkId;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.DatabaseService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.list;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
import static pl.wsztajerowski.FileUtils.getFilenameWithoutExtension;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;
import static pl.wsztajerowski.services.JmhUtils.getProfilerOutputDirSuffix;

public class JmhWithProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final DatabaseService databaseService;
    private final Map<String, String> profilerOptions;
    private final Path outputPath;

    JmhWithProfilerSubcommandService(StorageService storageService, DatabaseService databaseService, CommonSharedOptions commonOptions, JmhOptions jmhOptions, Map<String, String> profilerOptions) {
        this.storageService = storageService;
        this.databaseService = databaseService;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
        this.profilerOptions = profilerOptions;
        this.outputPath = commonOptions.resultPath();
    }

    public void executeCommand() {
        // Build process
        logger.info("Running JMH with profiler(s). Output path: {}", outputPath);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            BenchmarkProcessBuilder benchmarkProcessBuilder = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions);
            profilerOptions.forEach((profilerName, profilerOptions) ->
                benchmarkProcessBuilder.addArgumentWithValue("-prof", createProfilerCommand(profilerName, profilerOptions)));
            int exitCode = benchmarkProcessBuilder
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark profiler(s) process output on S3");
            storageService
                .saveFile(outputPath.resolve("jmh-profiler-output.txt"), jmhOptions.outputOptions().processOutput());

            if (exitCode != 0) {
                logger.error("Jmh process exited with exit code: {}", exitCode);
                logger.info("Benchmark process logs:\n{}", Files.readString(jmhOptions.outputOptions().processOutput()));
                throw new JavaWonderlandException(format("Benchmark process exit with non-zero code: {0}", exitCode));
            }
        } catch (InterruptedException | IOException e) {
            throw new JavaWonderlandException(e);
        }

        logger.info("Processing JMH results: {}", jmhOptions.outputOptions().machineReadableOutput());
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(jmhOptions.outputOptions().machineReadableOutput())) {
            logger.debug("JMH result: {}", jmhResult);
            Map<String, String> profilerOutputs = new HashMap<>();
            String benchmarkFullname = jmhResult.benchmark() + getProfilerOutputDirSuffix(jmhResult.mode());
            Path profilerOutputDir = outputPath.resolve(benchmarkFullname);
            try (Stream<Path> paths = list(profilerOutputDir)) {
                paths
                    .forEach(path -> {
                        Path storagePath = outputPath.resolve(benchmarkFullname).resolve(path.getFileName());
                        logger.info("Saving profiler output: {}", storagePath);
                        storageService
                            .saveFile(storagePath, path);
                        String profilerOutput = getFilenameWithoutExtension(path);
                        profilerOutputs.put(profilerOutput, storagePath.toString());
                    });
            } catch (IOException e) {
                throw new JavaWonderlandException(e);
            }

            JmhBenchmarkId benchmarkId = new JmhBenchmarkId(
                commonOptions.requestId(),
                jmhResult.benchmark(),
                jmhResult.mode()
            );
            var tags = commonOptions.tags();
            var now = OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime();
            JmhBenchmark jmhBenchmark = new JmhBenchmark(benchmarkId, jmhResult, new BenchmarkMetadata(tags, now, profilerOutputs));
            logger.info("Saving results in DB with ID: {}", benchmarkId);
            databaseService
                .save(jmhBenchmark);
        }
    }

    private String createProfilerCommand(String profilerName, String profilerOptions) {
        String outputOption = Optional.of(profilerName)
            .map(this::getProfilerOutputOptionName)
            .map(outputOptionName -> "%s=%s".formatted(outputOptionName, outputPath.toString()))
            .orElse("");
        String options = Stream.of(profilerOptions, outputOption)
            .filter(this::isNotBlank)
            .collect(Collectors.joining(";"));
        return isNotBlank(options)? "%s:%s".formatted(profilerName, options) : profilerName;
    }

    private boolean isNotBlank(String string) {
        return string != null && !string.trim().isEmpty();
    }

    private String getProfilerOutputOptionName(String profilerName) {
        return switch (profilerName) {
            case "async", "jfr" -> "dir";
            default -> null;
        };
    }
}
