package pl.wsztajerowski;

import pl.wsztajerowski.commands.TestWrapper;

import java.nio.file.Path;

public class FileUtils {

    public static Path getWorkingDirectory() {
        return Path.of(TestWrapper.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent();
    }
}
