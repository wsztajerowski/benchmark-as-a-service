package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

/**
 * Discards measurements. Selected ONLY by an explicit {@code --no-database}, never as a fallback
 * for absent configuration — the previous behaviour, where an empty connection string silently
 * selected a no-op, let a paid run report success while throwing its measurements away.
 */
public class NoOpResultsStore implements ResultsStore {
    private static final Logger logger = LoggerFactory.getLogger(NoOpResultsStore.class);

    @Override
    public void write(List<StoredMeasurement> measurements) {
        int count = measurements == null ? 0 : measurements.size();
        logger.warn("--no-database: discarding {} measurement(s). Nothing was stored.", count);
    }
}
