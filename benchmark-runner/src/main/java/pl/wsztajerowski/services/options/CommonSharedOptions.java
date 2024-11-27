package pl.wsztajerowski.services.options;

import java.nio.file.Path;

public record CommonSharedOptions(Path resultPath, String requestId) {
}
