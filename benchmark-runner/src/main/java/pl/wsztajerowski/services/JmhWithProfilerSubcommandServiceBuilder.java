package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.DatabaseService;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.net.URI;
import java.util.Map;

import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.infra.DatabaseServiceBuilder.getMorphiaServiceBuilder;

public final class JmhWithProfilerSubcommandServiceBuilder {
    private Map<String, String> profilerOptions;
    private CommonSharedOptions commonOptions;
    private StorageService storageService;
    private URI mongoConnectionString;
    private JmhOptions jmhOptions;

    private JmhWithProfilerSubcommandServiceBuilder() {
    }

    public static JmhWithProfilerSubcommandServiceBuilder serviceBuilder() {
        return new JmhWithProfilerSubcommandServiceBuilder();
    }

    public JmhWithProfilerSubcommandServiceBuilder withCommonOptions(CommonSharedOptions commonOptions) {
        this.commonOptions = commonOptions;
        return this;
    }

    public JmhWithProfilerSubcommandServiceBuilder withJmhOptions(JmhOptions jmhOptions) {
        this.jmhOptions = jmhOptions;
        return this;
    }

    public JmhWithProfilerSubcommandServiceBuilder withProfilerOptions(Map<String, String> profilerOptions) {
        this.profilerOptions = profilerOptions;
        return this;
    }

    public JmhWithProfilerSubcommandServiceBuilder withStorageService(StorageService storageService){
        this.storageService = storageService;
        return this;
    }

    public JmhWithProfilerSubcommandServiceBuilder withMongoConnectionString(URI mongoConnectionString) {
        this.mongoConnectionString = mongoConnectionString;
        return this;
    }

    public JmhWithProfilerSubcommandService build() {
        requireNonNull(storageService, "Please provide a storage service");
        DatabaseService databaseService = getMorphiaServiceBuilder()
            .withConnectionString(mongoConnectionString)
            .build();
        return new JmhWithProfilerSubcommandService(
            storageService,
            databaseService,
            commonOptions,
            jmhOptions,
            profilerOptions);
    }
}
