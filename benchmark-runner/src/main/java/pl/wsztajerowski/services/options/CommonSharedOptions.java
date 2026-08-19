package pl.wsztajerowski.services.options;

import java.nio.file.Path;

/**
 * {@code project} is a first-class value rather than a tag lookup: it composes the results
 * partition key, so a measurement cannot be stored without one.
 */
public record CommonSharedOptions(Path resultPath, String requestId, String project, java.util.Map<String, String> tags) {
}
