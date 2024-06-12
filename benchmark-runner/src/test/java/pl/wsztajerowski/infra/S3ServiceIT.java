package pl.wsztajerowski.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithS3BaseIT;

import java.nio.file.Path;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static pl.wsztajerowski.infra.S3ServiceBuilder.getS3ServiceBuilder;

class S3ServiceIT extends TestcontainersWithS3BaseIT {
    private S3Service sut;

    @BeforeEach
    void setupSut(){
        sut = getS3ServiceBuilder()
            .withS3Client(awsS3Client)
            .withBucketName(TEST_BUCKET_NAME)
            .build();
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
        sut.saveFileOnS3("sample.json", sampleFilePath);

        // then
        assertThatJson(listObjectsInTestBucket())
            .isArray()
            .extracting("Key", "Size")
            .contains(tuple("sample.json", 59));
    }
}