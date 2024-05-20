package pl.symentis.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.symentis.JavaWonderlandException;
import pl.symentis.entities.jcstress.JCStressResult;
import pl.symentis.entities.jcstress.JCStressTest;
import pl.symentis.entities.jcstress.JCStressTestMetadata;
import pl.symentis.infra.MorphiaService;
import pl.symentis.infra.S3Service;
import pl.symentis.services.options.CommonSharedOptions;
import pl.symentis.services.options.JCStressOptions;

import java.nio.file.Path;

import static pl.symentis.commands.JCStressHtmlResultParser.getJCStressHtmlResultParser;
import static pl.symentis.process.BenchmarkProcessBuilder.benchmarkProcessBuilder;

public class JCStressSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JCStressSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final S3Service s3Service;
    private final MorphiaService morphiaService;
    private final Path benchmarkPath;

    private final JCStressOptions jcStressOptions;

    JCStressSubcommandService(S3Service s3Service, MorphiaService morphiaService, CommonSharedOptions commonOptions, Path benchmarkPath, JCStressOptions jcStressOptions) {
        this.s3Service = s3Service;
        this.morphiaService = morphiaService;
        this.commonOptions = commonOptions;
        this.benchmarkPath = benchmarkPath;
        this.jcStressOptions = jcStressOptions;
    }

    public void executeCommand() {
        Path reportPath = jcStressOptions.reportPath();
        Path s3Prefix = Path.of(commonOptions.s3ResultPrefix(), "jcstress");
        logger.info("Running JCStress - S3 bucket: {}", s3Service.getEndpoint());
        logger.info("Path to results within bucket: {}", s3Prefix);
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
        s3Service
            .saveFileOnS3(s3Prefix.resolve("output.txt").toString(), jcStressOptions.processOutput());

        Path resultFilepath = reportPath.resolve( "index.html");
        logger.info("Parsing JCStress html output: {}", resultFilepath);
        JCStressResult jcStressResult = getJCStressHtmlResultParser(resultFilepath, s3Prefix)
            .parse();

        logger.info("Saving benchmarks into DB with id: {}", commonOptions.requestId());
        JCStressTest stressTestResult = new JCStressTest(
            commonOptions.requestId(),
            new JCStressTestMetadata(),
            jcStressResult);

        logger.debug("JCStress results: {}", stressTestResult);
        morphiaService
            .save(stressTestResult);

        jcStressResult
            .getAllUnsuccessfulTest()
            .forEach((testName, s3Key) ->
                {
                    String testOutputFilename = testName + ".html";
                    s3Service
                        .saveFileOnS3(s3Key, reportPath.resolve(testOutputFilename));
                }
            );
    }
}
