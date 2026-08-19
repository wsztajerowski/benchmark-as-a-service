package pl.wsztajerowski.services;

import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;


import static java.util.Objects.requireNonNull;

public final class JmhSubcommandServiceBuilder {
    private StorageService storageService;
    private CommonSharedOptions commonOptions;
    private JmhOptions jmhOptions;
    private ResultsStore resultsStore;

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

    public JmhSubcommandServiceBuilder withResultsStore(ResultsStore resultsStore) {
        this.resultsStore = resultsStore;
        return this;
    }

    public JmhSubcommandServiceBuilder withStorageService(StorageService storageService){
        this.storageService = storageService;
        return this;
    }

    public JmhSubcommandService build() {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        return new JmhSubcommandService(storageService, resultsStore, commonOptions, jmhOptions);
    }
}
