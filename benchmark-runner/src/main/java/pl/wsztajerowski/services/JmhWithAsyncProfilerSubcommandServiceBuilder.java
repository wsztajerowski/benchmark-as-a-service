package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.DatabaseService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.AsyncProfilerOptions;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.net.URI;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.DatabaseServiceBuilder.getMorphiaServiceBuilder;

public final class JmhWithAsyncProfilerSubcommandServiceBuilder {
    private AsyncProfilerOptions asyncProfilerOptions;
    private CommonSharedOptions commonOptions;
    private StorageService storageService;
    private URI mongoConnectionString;
    private JmhOptions jmhOptions;

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

    public JmhWithAsyncProfilerSubcommandServiceBuilder withStorageService(StorageService storageService){
        this.storageService = storageService;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandServiceBuilder withMongoConnectionString(URI mongoConnectionString) {
        this.mongoConnectionString = mongoConnectionString;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandService build() {
        requireNonNull(storageService, "Please provide a storage service");
        DatabaseService databaseService = getMorphiaServiceBuilder()
            .withConnectionString(mongoConnectionString)
            .build();
        return new JmhWithAsyncProfilerSubcommandService(
            storageService,
            databaseService,
            commonOptions,
            jmhOptions,
            asyncProfilerOptions);
    }
}
