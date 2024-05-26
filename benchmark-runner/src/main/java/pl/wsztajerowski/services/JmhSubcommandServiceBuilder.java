package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.MorphiaService;
import pl.wsztajerowski.infra.S3Service;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.net.URI;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.MorphiaServiceBuilder.getMorphiaServiceBuilder;
import static pl.wsztajerowski.infra.S3ServiceBuilder.getDefaultS3ServiceBuilder;

public final class JmhSubcommandServiceBuilder {
    private S3Service s3Service;
    private CommonSharedOptions commonOptions;
    private JmhOptions jmhOptions;
    private URI mongoConnectionString;
    private boolean useDefaultS3Service = false;
    private URI s3ServiceEndpoint;

    private JmhSubcommandServiceBuilder() {
    }

    public static JmhSubcommandServiceBuilder serviceBuilder() {
        return new JmhSubcommandServiceBuilder();
    }

    public JmhSubcommandServiceBuilder withCommonOptions(CommonSharedOptions commonOptions) {
        this.commonOptions = commonOptions;
        return this;
    }

    public JmhSubcommandServiceBuilder withJmhOptions(JmhOptions jmhOptions) {
        this.jmhOptions = jmhOptions;
        return this;
    }

    public JmhSubcommandServiceBuilder withMongoConnectionString(URI mongoConnectionString) {
        this.mongoConnectionString = mongoConnectionString;
        return this;
    }

    public JmhSubcommandServiceBuilder withS3Service(S3Service s3Service){
        this.s3Service = s3Service;
        return this;
    }

    public JmhSubcommandServiceBuilder withDefaultS3Service(URI s3ServiceEndpoint) {
        this.s3ServiceEndpoint = s3ServiceEndpoint;
        this.useDefaultS3Service = true;
        return this;
    }

    public JmhSubcommandService build() {
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
        return new JmhSubcommandService(s3Service, morphiaService, commonOptions, jmhOptions);
    }
}
