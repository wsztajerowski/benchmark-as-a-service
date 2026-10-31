package pl.wsztajerowski.services;

import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.JobLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jcstress.JCStressResult;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;
import pl.wsztajerowski.results.JCStressMeasurementMapper;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JCStressRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.JCStressHtmlResultParser.getJCStressHtmlResultParser;
import static pl.wsztajerowski.process.BenchmarkProcessBuilder.benchmarkProcessBuilder;

public class JCStressSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JCStressSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final StorageService storageService;
    private final ResultsStore resultsStore;
    private final JCStressRun run;

    public JCStressSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JCStressRun run) {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        this.storageService = storageService;
        this.resultsStore = resultsStore;
        this.commonOptions = commonOptions;
        this.run = run;
    }

    /**
     * JCStress's command line, without the output redirection: the report directory the runner
     * parses, then the user's arguments as given.
     */
    BenchmarkProcessBuilder jcstressProcess() {
        BenchmarkProcessBuilder builder = benchmarkProcessBuilder(run.java(), run.benchmarkPath())
            .addArgumentWithValue("-r", run.reportPath());
        run.arguments().forEach(builder::addArgument);
        return builder;
    }

    public void executeCommand() {
        Path reportPath = run.reportPath();
        Path outputPath = commonOptions.resultPath();
        logger.info("Running JCStress. Output path: {}", outputPath);
        int exitCode;
        try {
            exitCode = jcstressProcess()
                .withOutputPath(run.processOutput())
                .buildAndStartProcess()
                .waitFor();
        } catch (AssertionError | InterruptedException e) {
            throw new JavaWonderlandException(e);
        }

        logger.info("Saving test outputs on S3");
        storageService
            .saveFile(outputPath.resolve(BenchmarkType.JCSTRESS.processOutputName()), run.processOutput());
        JobLogs.upload(storageService, outputPath);

        // Not fatal on its own: whether JCStress exits non-zero for failed tests is not something
        // this runner relies on, and a job whose tests failed must still store its summary. A job
        // that left no report is what fails — naming the process, not the missing file it implies.
        Path resultFilepath = reportPath.resolve( "index.html");
        if (exitCode != 0) {
            logger.warn("JCStress exited with code {}", exitCode);
        }
        if (!Files.isRegularFile(resultFilepath)) {
            logger.error("JCStress output:\n{}", readQuietly(run.processOutput()));
            throw new JavaWonderlandException(
                "JCStress exited with exit code %d and wrote no report at %s".formatted(exitCode, resultFilepath));
        }
        logger.info("Parsing JCStress html output: {}", resultFilepath);
        JCStressResult jcStressResult = getJCStressHtmlResultParser(resultFilepath, outputPath)
            .parse();

        jcStressResult
            .getAllUnsuccessfulTest()
            .forEach((testName, s3Key) ->
                {
                    String testOutputFilename = testName + ".html";
                    storageService
                        .saveFile(Path.of(s3Key), reportPath.resolve(testOutputFilename));
                }
            );

        // Store last: a job that fails here must still leave its S3 artifacts behind.
        List<StoredMeasurement> measurements = List.of(JCStressMeasurementMapper.toMeasurement(
            jcStressResult,
            commonOptions.project(),
            commonOptions.jobId(),
            commonOptions.createdAt(),
            commonOptions.tags(),
            outputPath.toString(),
            outputPath.resolve(JobLayout.ENVIRONMENT_NAME).toString()));

        logger.info("Storing JCStress summary for request {}", commonOptions.jobId());
        resultsStore.write(measurements);
    }

    /** Diagnostics on a path that is already failing: an unreadable output must not mask why. */
    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }
}
