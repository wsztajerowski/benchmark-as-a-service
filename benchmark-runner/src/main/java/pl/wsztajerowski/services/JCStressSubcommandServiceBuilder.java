package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.MorphiaService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.net.URI;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.MorphiaServiceBuilder.getMorphiaServiceBuilder;

public final class JCStressSubcommandServiceBuilder {
    private CommonSharedOptions commonOptions;
    private StorageService storageService;
    private URI mongoConnectionString;
    private Path benchmarkPath;
    private JCStressOptions jcStressOptions;

    private JCStressSubcommandServiceBuilder() {
    }

    public static JCStressSubcommandServiceBuilder serviceBuilder() {
        return new JCStressSubcommandServiceBuilder();
    }

    public JCStressSubcommandServiceBuilder withBenchmarkPath(Path benchmarkPath) {
        this.benchmarkPath = benchmarkPath;
        return this;
    }

    public JCStressSubcommandServiceBuilder withCommonOptions(CommonSharedOptions commonOptions) {
        this.commonOptions = commonOptions;
        return this;
    }

    public JCStressSubcommandServiceBuilder withS3Service(StorageService storageService){
        this.storageService = storageService;
        return this;
    }

    public JCStressSubcommandServiceBuilder withMongoConnectionString(URI mongoConnectionString) {
        this.mongoConnectionString = mongoConnectionString;
        return this;
    }

    public JCStressSubcommandServiceBuilder withJCStressOptions(JCStressOptions jcStressOptions) {
        this.jcStressOptions = jcStressOptions;
        return this;
    }

    public JCStressSubcommandService build() {
        requireNonNull(mongoConnectionString, "Please provide connectionString for Mongo");
        requireNonNull(storageService, "Please provide a S3 service");
        MorphiaService morphiaService = getMorphiaServiceBuilder()
            .withConnectionString(mongoConnectionString)
            .build();
        return new JCStressSubcommandService(storageService, morphiaService, commonOptions, benchmarkPath, jcStressOptions);
    }
}
