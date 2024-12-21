package pl.wsztajerowski.services.options;

import java.nio.file.Path;

public record CommonSharedOptions(Path resultPath, String requestId, java.util.Map<String, String> tags) {
}
