package pl.wsztajerowski.baas.testsupport;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps every test away from the developer's {@code ~/.aws} (T10), in Maven and an IDE alike. The SDK
 * finds that directory through the {@code HOME} environment variable before {@code user.home}, so
 * surefire's {@code user.home} never covered it: an unexpected default-chain client in a unit test
 * read the real config and credentials. Its system properties win over the environment, so they are
 * pointed at files that do not exist. Registered through {@code META-INF/services}.
 */
public final class NoRealAwsProfile implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        Path empty;
        try {
            empty = Files.createTempDirectory("baas-test-aws");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.setProperty("aws.configFile", empty.resolve("config").toString());
        System.setProperty("aws.sharedCredentialsFile", empty.resolve("credentials").toString());
        System.setProperty("aws.disableEc2Metadata", "true");
    }
}
