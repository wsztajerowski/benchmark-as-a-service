package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static pl.wsztajerowski.FileUtils.getWorkingDirectory;

public class LocalStorageService implements StorageService {
    private static final Logger logger = LoggerFactory.getLogger(LocalStorageService.class);

    @Override
    public void saveFile(Path storagePath, Path localPath) {
        try {
            logger.trace("File content : {}", Files.readString(localPath));
            Path targetPath = getWorkingDirectory().resolve(storagePath);
            Files.createDirectories(targetPath);
            Files.copy(localPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
