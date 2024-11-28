package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.entities.jcstress.JCStressResult;
import pl.wsztajerowski.entities.jcstress.JCStressTest;
import pl.wsztajerowski.entities.jcstress.JCStressTestMetadata;
import pl.wsztajerowski.infra.DatabaseService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.nio.file.Path;

import static pl.wsztajerowski.commands.JCStressHtmlResultParser.getJCStressHtmlResultParser;
import static pl.wsztajerowski.process.BenchmarkProcessBuilder.benchmarkProcessBuilder;

public class JCStressSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JCStressSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final StorageService storageService;
    private final DatabaseService databaseService;
    private final Path benchmarkPath;

    private final JCStressOptions jcStressOptions;

    JCStressSubcommandService(StorageService storageService, DatabaseService databaseService, CommonSharedOptions commonOptions, Path benchmarkPath, JCStressOptions jcStressOptions) {
        this.storageService = storageService;
        this.databaseService = databaseService;
        this.commonOptions = commonOptions;
        this.benchmarkPath = benchmarkPath;
        this.jcStressOptions = jcStressOptions;
    }

    public void executeCommand() {
        Path reportPath = jcStressOptions.reportPath();
        Path outputPath = commonOptions.resultPath().resolve("jcstress");
        logger.info("Running JCStress. Output path: {}", outputPath);
        try {
            benchmarkProcessBuilder(benchmarkPath)
                .addArgumentWithValue("-r", reportPath)
                .addArgumentIfValueIsNotNull("-c", jcStressOptions.cpuNumber())
                .addArgumentIfValueIsNotNull("-f", jcStressOptions.forks())
                .addArgumentIfValueIsNotNull("-fsm", jcStressOptions.forkMultiplier())
                .addArgumentIfValueIsNotNull("-hs", jcStressOptions.heapSize())
                .addArgumentIfValueIsNotNull("-jvmArgs", jcStressOptions.jvmArgs())
                .addArgumentIfValueIsNotNull("-jvmArgsPrepend", jcStressOptions.jvmArgsPrepend())
                .addArgumentIfValueIsNotNull("-pth", jcStressOptions.preTouchHeap())
                .addArgumentIfValueIsNotNull("-sc", jcStressOptions.splitCompilationModes())
                .addArgumentIfValueIsNotNull("-spinStyle", jcStressOptions.spinStyle())
                .addArgumentIfValueIsNotNull("-strideCount", jcStressOptions.strideCount())
                .addArgumentIfValueIsNotNull("-strideSize", jcStressOptions.strideSize())
                .addArgumentIfValueIsNotNull("-t", jcStressOptions.testNameRegex())
                .withOutputPath(jcStressOptions.processOutput())
                .buildAndStartProcess()
                .waitFor();
        } catch (AssertionError | InterruptedException e) {
            throw new JavaWonderlandException(e);
        }

        logger.info("Saving test outputs on S3");
        storageService
            .saveFile(outputPath.resolve("output.txt"), jcStressOptions.processOutput());

        Path resultFilepath = reportPath.resolve( "index.html");
        logger.info("Parsing JCStress html output: {}", resultFilepath);
        JCStressResult jcStressResult = getJCStressHtmlResultParser(resultFilepath, outputPath)
            .parse();

        logger.info("Saving benchmarks into DB with id: {}", commonOptions.requestId());
        JCStressTest stressTestResult = new JCStressTest(
            commonOptions.requestId(),
            new JCStressTestMetadata(),
            jcStressResult);

        logger.debug("JCStress results: {}", stressTestResult);
        databaseService
            .save(stressTestResult);

        jcStressResult
            .getAllUnsuccessfulTest()
            .forEach((testName, s3Key) ->
                {
                    String testOutputFilename = testName + ".html";
                    storageService
                        .saveFile(Path.of(s3Key), reportPath.resolve(testOutputFilename));
                }
            );
    }
}
