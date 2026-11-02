package pl.wsztajerowski.baas.testsupport;

import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one LocalStack every CLI integration test starts, so the pin is changed in one place. Why
 * {@code 4.14.0}: CLAUDE.md, *Gotchas*. The runner's suite pins the same version in
 * {@code TestcontainersWithS3BaseIT}.
 */
public final class LocalStack {

    public static final DockerImageName IMAGE = DockerImageName.parse("localstack/localstack:4.14.0");

    private LocalStack() {
    }

    public static LocalStackContainer with(LocalStackContainer.Service... services) {
        return new LocalStackContainer(IMAGE).withServices(services);
    }
}
