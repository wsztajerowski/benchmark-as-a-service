package pl.symentis.services;

import pl.symentis.infra.MorphiaService;
import pl.symentis.infra.S3Service;
import pl.symentis.services.options.CommonSharedOptions;
import pl.symentis.services.options.JCStressOptions;

import java.net.URI;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;
import static pl.symentis.infra.MorphiaServiceBuilder.getMorphiaServiceBuilder;
import static pl.symentis.infra.S3ServiceBuilder.getDefaultS3ServiceBuilder;

public final class JCStressSubcommandServiceBuilder {
    private CommonSharedOptions commonOptions;
    private S3Service s3Service;
    private URI mongoConnectionString;
    private Path benchmarkPath;
    private JCStressOptions jcStressOptions;
    private boolean useDefaultS3Service = false;
    private URI s3ServiceEndpoint;

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

    public JCStressSubcommandServiceBuilder withS3Service(S3Service s3Service){
        this.s3Service = s3Service;
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

    public JCStressSubcommandServiceBuilder withDefaultS3Service(URI s3ServiceEndpoint) {
        this.s3ServiceEndpoint = s3ServiceEndpoint;
        this.useDefaultS3Service = true;
        return this;
    }

    public JCStressSubcommandService build() {
        requireNonNull(mongoConnectionString, "Please provide connectionString for Mongo");
        if (useDefaultS3Service) {
            s3Service = getDefaultS3ServiceBuilder(s3ServiceEndpoint)
                .withBucketName(commonOptions.s3BucketName())
                .build();
        }
        requireNonNull(s3Service, "Please either provide a S3 service or invoke withDefaultS3Service method before");
        MorphiaService morphiaService = getMorphiaServiceBuilder()
            .withConnectionString(mongoConnectionString)
            .build();
        return new JCStressSubcommandService(s3Service, morphiaService, commonOptions, benchmarkPath, jcStressOptions);
    }
}
