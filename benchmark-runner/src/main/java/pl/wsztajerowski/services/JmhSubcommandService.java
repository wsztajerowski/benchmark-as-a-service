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
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final DatabaseService databaseService;

    JmhSubcommandService(StorageService storageService, DatabaseService databaseService, CommonSharedOptions commonOptions, JmhOptions jmhOptions) {
        this.storageService = storageService;
        this.databaseService = databaseService;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
    }

    public void executeCommand() {
        Path outputPath = commonOptions.resultPath();
        logger.info("Running JMH. Output path: {}", outputPath);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output");
            storageService
                .saveFile(outputPath.resolve("jmh-output.txt"), jmhOptions.outputOptions().processOutput());

            Files.walkFileTree(Paths.get("."), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".log")) {
                        logger.info("Saving log file: {} ", file);
                        storageService.saveFile(outputPath.resolve("logs").resolve(file.getFileName()), file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

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
            JmhBenchmarkId benchmarkId = new JmhBenchmarkId(
                commonOptions.requestId(),
                jmhResult.benchmark(),
                jmhResult.mode());
            logger.info("Saving results in DB with ID: {}", benchmarkId);
            var tags = commonOptions.tags();
            var now = OffsetDateTime.now(ZoneOffset.UTC).toLocalDateTime();
            JmhBenchmark jmhBenchmark = new JmhBenchmark(benchmarkId, jmhResult, new BenchmarkMetadata(tags, now, null));
            databaseService.save(jmhBenchmark);
        }


    }
}
