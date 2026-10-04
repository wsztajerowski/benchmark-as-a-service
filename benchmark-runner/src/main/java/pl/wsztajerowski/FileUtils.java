package pl.wsztajerowski;

import pl.wsztajerowski.commands.TestWrapper;

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
}
