package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.aws.S3UploadService.StoredObject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactViewTest {

    @Test
    void everyTypesProcessOutputIsOutput() {
        var groups = ArtifactView.of(List.of(
            new StoredObject("jmh-output.txt", 10),
            new StoredObject("jcstress-output.txt", 20)));

        assertThat(groups.get("output")).extracting(ArtifactView.Entry::name)
            .containsExactlyInAnyOrder("jmh-output.txt", "jcstress-output.txt");
    }
}
