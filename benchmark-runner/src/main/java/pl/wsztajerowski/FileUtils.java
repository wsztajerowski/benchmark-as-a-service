package pl.wsztajerowski;

import pl.wsztajerowski.commands.TestWrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileUtils {

    public static Path getWorkingDirectory() {
        return Path.of(TestWrapper.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
    }

    public static String getFilenameWithoutExtension(Path pathToFile) {
        return getFilenameWithoutExtension(pathToFile.getFileName().toString());
    }

    public static String getFilenameWithoutExtension(String filename) {
        if (filename.indexOf(".") > 0) {
            return filename.substring(0, filename.lastIndexOf("."));
        }
        return filename;
    }

    public static void ensurePathExists(Path path){
        Path parent = path.getParent();
        if(parent != null && Files.notExists(parent)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new JavaWonderlandException(e);
            }
        }
    }
}
