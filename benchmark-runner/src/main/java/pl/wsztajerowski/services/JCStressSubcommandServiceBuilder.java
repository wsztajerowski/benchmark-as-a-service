package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

public final class JCStressSubcommandServiceBuilder {
    private CommonSharedOptions commonOptions;
    private StorageService storageService;
    private ResultsStore resultsStore;
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

    public JCStressSubcommandServiceBuilder withStorageService(StorageService storageService){
        this.storageService = storageService;
        return this;
    }

    public JCStressSubcommandServiceBuilder withResultsStore(ResultsStore resultsStore) {
        this.resultsStore = resultsStore;
        return this;
    }

    public JCStressSubcommandServiceBuilder withJCStressOptions(JCStressOptions jcStressOptions) {
        this.jcStressOptions = jcStressOptions;
        return this;
    }

    public JCStressSubcommandService build() {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        return new JCStressSubcommandService(storageService, resultsStore, commonOptions, benchmarkPath, jcStressOptions);
    }
}
