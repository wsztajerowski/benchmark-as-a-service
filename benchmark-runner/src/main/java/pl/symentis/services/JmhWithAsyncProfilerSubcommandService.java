package pl.symentis.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.symentis.JavaWonderlandException;
import pl.symentis.entities.jmh.BenchmarkMetadata;
import pl.symentis.entities.jmh.JmhBenchmark;
import pl.symentis.entities.jmh.JmhBenchmarkId;
import pl.symentis.entities.jmh.JmhResult;
import pl.symentis.infra.MorphiaService;
import pl.symentis.infra.S3Service;
import pl.symentis.services.options.AsyncProfilerOptions;
import pl.symentis.services.options.CommonSharedOptions;
import pl.symentis.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static java.nio.file.Files.list;
import static java.text.MessageFormat.format;
import static pl.symentis.FileUtils.ensurePathExists;
import static pl.symentis.FileUtils.getFilenameWithoutExtension;
import static pl.symentis.infra.ResultLoaderService.getResultLoaderService;
import static pl.symentis.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhWithAsyncProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithAsyncProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final S3Service s3Service;
    private final MorphiaService morphiaService;
    private final AsyncProfilerOptions asyncProfilerOptions;
    private final Path s3Prefix;

    JmhWithAsyncProfilerSubcommandService(S3Service s3Service, MorphiaService morphiaService, CommonSharedOptions commonOptions, JmhOptions jmhOptions, AsyncProfilerOptions asyncProfilerOptions) {
        this.s3Service = s3Service;
        this.morphiaService = morphiaService;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
        this.asyncProfilerOptions = asyncProfilerOptions;
        this.s3Prefix = Path.of(commonOptions.s3ResultPrefix(), "jmh-with-async");
    }

    public void executeCommand() {
        // Build process
        logger.info("Running JMH with async profiler - S3 bucket: {}", s3Service.getEndpoint());
        logger.info("Path to results within bucket: {}", s3Prefix);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .addArgumentWithValue("-prof", createAsyncCommand())
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output on S3");
            s3Service
                .saveFileOnS3(s3Prefix.resolve("output.txt").toString(), jmhOptions.outputOptions().processOutput());

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
                        String s3Key = s3Prefix.resolve(benchmarkFullname).resolve(path.getFileName()).toString();
                        logger.info("Saving profiler output: {}", s3Key);
                        s3Service
                            .saveFileOnS3(s3Key, path);
                        String profilerOutput = getFilenameWithoutExtension(path);
                        profilerOutputs.put(profilerOutput, s3Key);
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
                    String s3Key = s3Prefix.resolve("logs").resolve(path.getFileName()).toString();
                    s3Service
                        .saveFileOnS3(s3Key, path);
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
