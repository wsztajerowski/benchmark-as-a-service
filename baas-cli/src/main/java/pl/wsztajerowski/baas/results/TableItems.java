package pl.wsztajerowski.baas.results;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.console.Printable;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Reads one results-table item, skipping it with a warning when it cannot be read (X3). A runner
 * holds {@code UpdateItem} on the {@code JOB} partition and {@code PutItem} on {@code RESULT#*}, and
 * IAM cannot require the conditions BaaS's own writers send, so code on a runner can leave an item no
 * mapper accepts. No BaaS role can delete one, so failing on it would break every listing until an
 * account admin did; skipping it costs one line instead.
 */
public final class TableItems {

    private static final Logger logger = LoggerFactory.getLogger(TableItems.class);

    private TableItems() {
    }

    public static <T> Optional<T> read(Map<String, AttributeValue> item, Function<Map<String, AttributeValue>, T> mapper) {
        try {
            return Optional.of(mapper.apply(item));
        } catch (RuntimeException e) {
            logger.warn("Skipped an item BaaS cannot read (pk {}, sk {}): {}. No BaaS role can delete it; "
                    + "an account admin can.", key(item, MeasurementItemMapper.PK), key(item, MeasurementItemMapper.SK),
                Printable.of(String.valueOf(e.getMessage())));
            return Optional.empty();
        }
    }

    private static String key(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.s() == null ? "absent" : "'" + Printable.of(value.s()) + "'";
    }
}
