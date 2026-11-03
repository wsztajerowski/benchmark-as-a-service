package pl.wsztajerowski.baas.testsupport;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Keeps every test away from the developer's {@code ~/.baas} (T10), in Maven and an IDE alike. A
 * command built outside a {@code BaasApp} tree reads {@code ConfigService.defaultRoot()}, and
 * surefire's {@code user.home} covers only Maven's unit tests: an IDE run read, and could write, the
 * real deployments. {@code user.home} itself is left alone, because ITs need the real one for
 * {@code ~/.testcontainers.properties}. Registered through {@code META-INF/services}.
 */
public final class NoRealBaasHome implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            System.setProperty("baas.home", Files.createTempDirectory("baas-test-home").toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
