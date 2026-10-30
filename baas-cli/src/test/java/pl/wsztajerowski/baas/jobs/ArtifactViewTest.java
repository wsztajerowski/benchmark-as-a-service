package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.aws.S3UploadService.StoredObject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactViewTest {

    /** A job stored before the profiled types were retired still groups its output as output. */
    @Test
    void aRetiredTypesProcessOutputIsStillOutput() {
        var groups = ArtifactView.of(List.of(
            new StoredObject("jmh-with-async-output.txt", 10),
            new StoredObject("jmh-profiler-output.txt", 20),
            new StoredObject("jmh-output.txt", 30)));

        assertThat(groups.get("output")).extracting(ArtifactView.Entry::name)
            .containsExactlyInAnyOrder("jmh-with-async-output.txt", "jmh-profiler-output.txt", "jmh-output.txt");
    }
}
