package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.MorphiaService;
import pl.wsztajerowski.infra.S3Service;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.net.URI;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.MorphiaServiceBuilder.getMorphiaServiceBuilder;

public final class JmhSubcommandServiceBuilder {
    private S3Service s3Service;
    private CommonSharedOptions commonOptions;
    private JmhOptions jmhOptions;
    private URI mongoConnectionString;

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

    public JmhSubcommandService build() {
        requireNonNull(mongoConnectionString, "Please provide connectionString for Mongo");
        requireNonNull(s3Service, "Please provide a S3 service");
        MorphiaService morphiaService = getMorphiaServiceBuilder()
            .withConnectionString(mongoConnectionString)
            .build();
        return new JmhSubcommandService(s3Service, morphiaService, commonOptions, jmhOptions);
    }
}
