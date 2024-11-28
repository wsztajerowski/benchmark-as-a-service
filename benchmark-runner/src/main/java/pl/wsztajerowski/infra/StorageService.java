package pl.wsztajerowski.infra;

import java.nio.file.Path;

public interface StorageService {
    void saveFile(Path storagePath, Path localPath);
}
