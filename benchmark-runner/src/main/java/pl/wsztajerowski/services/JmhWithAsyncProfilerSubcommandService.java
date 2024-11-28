package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.entities.jmh.BenchmarkMetadata;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.entities.jmh.JmhBenchmarkId;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.MorphiaService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.AsyncProfilerOptions;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.Files.list;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
import static pl.wsztajerowski.FileUtils.getFilenameWithoutExtension;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhWithAsyncProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithAsyncProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final MorphiaService morphiaService;
    private final AsyncProfilerOptions asyncProfilerOptions;
    private final Path outputPath;

    JmhWithAsyncProfilerSubcommandService(StorageService storageService, MorphiaService morphiaService, CommonSharedOptions commonOptions, JmhOptions jmhOptions, AsyncProfilerOptions asyncProfilerOptions) {
        this.storageService = storageService;
        this.morphiaService = morphiaService;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
        this.asyncProfilerOptions = asyncProfilerOptions;
        this.outputPath = commonOptions.resultPath().resolve("jmh-with-async");
    }

    public void executeCommand() {
        // Build process
        logger.info("Running JMH with async profiler. Output path: {}", outputPath);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .addArgumentWithValue("-prof", createAsyncCommand())
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output on S3");
            storageService
                .saveFile(outputPath.resolve("output.txt"), jmhOptions.outputOptions().processOutput());

            if (exitCode != 0) {
                throw new JavaWonderlandException(format("Benchmark process exit with non-zero code: {0}", exitCode));
            }
        } catch (InterruptedException e) {
            throw new JavaWonderlandException(e);
        }

//        logger.info("S3 url: {}", s3Service.);
        logger.info("Processing JMH results: {}", jmhOptions.outputOptions().machineReadableOutput());
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(jmhOptions.outputOptions().machineReadableOutput())) {
            logger.debug("JMH result: {}", jmhResult);
            Map<String, String> profilerOutputs = new HashMap<>();
            String benchmarkFullname = jmhResult.benchmark() + getProfilerOutputDirSuffix(jmhResult.mode());
            Path profilerOutputDir = asyncProfilerOptions.asyncOutputPath().resolve(benchmarkFullname);
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
            logger.info("Saving results in DB with ID: {}", benchmarkId);
            morphiaService
                .upsert(JmhBenchmark.class)
                .byFieldValue("benchmarkId", benchmarkId)
                .setValue("benchmarkMetadata", new BenchmarkMetadata(profilerOutputs))
                .setValue("jmhWithAsyncResult", jmhResult)
                .execute();
        }

        logger.info("Saving JMH logs on S3");
        try (Stream<Path> paths = list(asyncProfilerOptions.asyncOutputPath())){
            paths
                .filter(f -> f.toString().endsWith("log"))
                .forEach(path -> {
                    Path s3Key = outputPath.resolve("logs").resolve(path.getFileName());
                    storageService
                        .saveFile(s3Key, path);
                });
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
    }


    private static String getProfilerOutputDirSuffix(String mode) {
        return switch (mode) {
            case "thrpt":
                yield "-Throughput";
            case "avgt":
                yield "-AverageTime";
            case "sample":
                yield "-SampleTime";
            case "ss":
                yield "-SingleShotTime";
            default:
                throw new IllegalArgumentException("Unknown benchmark mode: " + mode);
        };
    }

    private String createAsyncCommand() {
        return "async:libPath=%s;output=%s;dir=%s;interval=%d".formatted(
            asyncProfilerOptions.asyncPath(),
            asyncProfilerOptions.asyncOutputType(),
            asyncProfilerOptions.asyncOutputPath().toAbsolutePath(),
            asyncProfilerOptions.asyncInterval());
    }
}
