package pl.wsztajerowski.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithS3BaseIT;

import java.nio.file.Path;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class StorageServiceIT extends TestcontainersWithS3BaseIT {
    private StorageService sut;

    @BeforeEach
    void setupSut(){
        sut = new S3StorageService(awsS3Client, TEST_BUCKET_NAME);
    }

    @Test
    void test_isLocalstackRunning() {
        assertThat(LOCAL_STACK_CONTAINER.isRunning())
            .isTrue();
    }

    @Test
    void test_uploadObjectSuccess() {
        // given
        Path sampleFilePath = createPathForTestResource("sample.json");

        // when
        sut.saveFile(Path.of("sample.json"), sampleFilePath);

        // then
        assertThatJson(listObjectsInTestBucket())
            .isArray()
            .extracting("Key", "Size")
            .contains(tuple("sample.json", 59));
    }
}