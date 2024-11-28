package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.entities.jmh.JmhBenchmarkId;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.MorphiaService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.nio.file.Path;

import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final MorphiaService morphiaService;

    JmhSubcommandService(StorageService storageService, MorphiaService morphiaService, CommonSharedOptions commonOptions, JmhOptions jmhOptions) {
        this.storageService = storageService;
        this.morphiaService = morphiaService;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
    }

    public void executeCommand() {
        Path outputPath = commonOptions.resultPath().resolve( "jmh");
        logger.info("Running JMH. Output path: {}", outputPath);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
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

        logger.info("Processing JMH results: {}", jmhOptions.outputOptions().machineReadableOutput());
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(jmhOptions.outputOptions().machineReadableOutput())) {
            logger.debug("JMH result: {}", jmhResult);
            JmhBenchmarkId benchmarkId = new JmhBenchmarkId(
                commonOptions.requestId(),
                jmhResult.benchmark(),
                jmhResult.mode());
            logger.info("Saving results in DB with ID: {}", benchmarkId);
            morphiaService
                .upsert(JmhBenchmark.class)
                .byFieldValue("benchmarkId", benchmarkId)
                .setValue("jmhResult", jmhResult)
                .execute();
        }


    }
}
