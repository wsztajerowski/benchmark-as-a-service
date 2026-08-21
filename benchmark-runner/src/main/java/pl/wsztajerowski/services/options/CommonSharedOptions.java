package pl.wsztajerowski.services.options;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * {@code project} is a first-class value rather than a tag lookup: it composes the results
 * partition key, so a measurement cannot be stored without one.
 *
 * <p>{@code createdAt} is the run's single instant, supplied by whoever named the run. It sits
 * between the two {@code String} fields on purpose — a positional slip between {@code requestId}
 * and {@code project} then fails to compile instead of silently swapping them.
 */
public record CommonSharedOptions(
    Path resultPath, String requestId, Instant createdAt, String project, Map<String, String> tags) {
}
