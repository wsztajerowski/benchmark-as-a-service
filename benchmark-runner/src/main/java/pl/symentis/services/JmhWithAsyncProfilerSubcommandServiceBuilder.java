package pl.symentis.services;

import pl.symentis.infra.MorphiaService;
import pl.symentis.infra.S3Service;
import pl.symentis.services.options.AsyncProfilerOptions;
import pl.symentis.services.options.CommonSharedOptions;
import pl.symentis.services.options.JmhOptions;

import java.net.URI;

import static java.util.Objects.requireNonNull;
import static pl.symentis.infra.MorphiaServiceBuilder.getMorphiaServiceBuilder;
import static pl.symentis.infra.S3ServiceBuilder.getDefaultS3ServiceBuilder;

public final class JmhWithAsyncProfilerSubcommandServiceBuilder {
    private AsyncProfilerOptions asyncProfilerOptions;
    private CommonSharedOptions commonOptions;
    private S3Service s3Service;
    private URI mongoConnectionString;
    private JmhOptions jmhOptions;
    private boolean useDefaultS3Service = false;
    private URI s3ServiceEndpoint;

    private JmhWithAsyncProfilerSubcommandServiceBuilder() {
    }

    public static JmhWithAsyncProfilerSubcommandServiceBuilder serviceBuilder() {
        return new JmhWithAsyncProfilerSubcommandServiceBuilder();
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withCommonOptions(CommonSharedOptions commonOptions) {
        this.commonOptions = commonOptions;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withJmhOptions(JmhOptions jmhOptions) {
        this.jmhOptions = jmhOptions;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withAsyncProfilerOptions(AsyncProfilerOptions asyncProfilerOptions) {
        this.asyncProfilerOptions = asyncProfilerOptions;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withS3Service(S3Service s3Service){
        this.s3Service = s3Service;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withMongoConnectionString(URI mongoConnectionString) {
        this.mongoConnectionString = mongoConnectionString;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withDefaultS3Service(URI s3ServiceEndpoint) {
        this.s3ServiceEndpoint = s3ServiceEndpoint;
        this.useDefaultS3Service = true;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandService build() {
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
        return new JmhWithAsyncProfilerSubcommandService(
            s3Service,
            morphiaService,
            commonOptions,
            jmhOptions,
            asyncProfilerOptions);
    }
}
