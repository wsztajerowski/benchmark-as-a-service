package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

/**
 * Where a run's measurements go. The port speaks the domain, not storage: each adapter owns its
 * physical layout, so one item per measurement maps cleanly to one document per measurement and
 * nothing DynamoDB-specific leaks through.
 *
 * <p>Write-only by design. Reads belong to {@code baas-cli}, which never learns MongoDB exists.
 */
public interface ResultsStore {

    /**
     * Writes every measurement from one run, or throws. Partial success is never reported — a
     * caller that sees no exception may assume every measurement landed.
     *
     * @throws ResultsStoreException when the write ultimately fails after any configured retries
     */
    void write(List<StoredMeasurement> measurements);
}
