package pl.wsztajerowski;

import java.nio.file.Path;

public class FileUtils {

    /**
     * The directory holding the runner's JAR — on the instance {@code /app}, never {@code /}, since
     * the log walk below it must not reach the root filesystem. Anchored on this class, which ships
     * in the same JAR as every other runner class.
     */
    public static Path getWorkingDirectory() {
        return Path.of(FileUtils.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
    }
}
