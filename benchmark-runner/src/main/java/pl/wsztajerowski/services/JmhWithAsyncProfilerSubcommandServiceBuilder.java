package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.AsyncProfilerOptions;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;


import static java.util.Objects.requireNonNull;

public final class JmhWithAsyncProfilerSubcommandServiceBuilder {
    private AsyncProfilerOptions asyncProfilerOptions;
    private CommonSharedOptions commonOptions;
    private StorageService storageService;
    private ResultsStore resultsStore;
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

    public JmhWithAsyncProfilerSubcommandServiceBuilder withResultsStore(ResultsStore resultsStore) {
        this.resultsStore = resultsStore;
        return this;
    }

    public JmhWithAsyncProfilerSubcommandService build() {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        return new JmhWithAsyncProfilerSubcommandService(
            storageService,
            resultsStore,
            commonOptions,
            jmhOptions,
            asyncProfilerOptions);
    }
}
