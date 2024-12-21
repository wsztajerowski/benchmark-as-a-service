package pl.wsztajerowski.services;

import dev.morphia.annotations.Entity;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.MongoDbTestHelpers;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.jcstress.JCStressTest;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static pl.wsztajerowski.MongoDbTestHelpers.all;
import static pl.wsztajerowski.services.JCStressSubcommandServiceBuilder.serviceBuilder;
import static pl.wsztajerowski.services.options.JCStressOptionsBuilder.jcStressOptionsBuilder;

class JCStressSubcommandServiceIT extends TestcontainersWithS3AndMongoBaseIT {

    private static MongoDbTestHelpers helper;

    @BeforeAll
    static void setupHelper(){
        helper = new MongoDbTestHelpers(getConnectionString());
    }

    @Test
    void successful_scenario() throws IOException {
        // given
        Path tempDirectory = Files.createTempDirectory("jcstress");
        JCStressOptions jcStressOptions =
            jcStressOptionsBuilder()
                .withForks(1)
                .withReportPath(tempDirectory.resolve("results"))
                .withSplitCompilationModes(false)
                .withProcessOutput(tempDirectory.resolve("jcstress.txt"))
                .build();
        JCStressSubcommandService sut = serviceBuilder()
            .withMongoConnectionString(getConnectionString())
            .withStorageService(new S3StorageService(awsS3Client, TEST_BUCKET_NAME))
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1", Collections.emptyMap()))
            .withJCStressOptions(jcStressOptions)
            .withBenchmarkPath(Path.of("target", "fake-stress-tests.jar").toAbsolutePath())
            .build();

        // when
        sut.executeCommand();

        // then
        String collectionName = JCStressTest.class.getAnnotation(Entity.class).value();
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .containsEntry("_t", "JCStressTest")
                .extracting("result", as(MAP))
                    .containsEntry("totalTests", 2)
                    .containsEntry("passedTests", 1)
                    .extracting("testsWithFailedResults", as(MAP))
                        .containsKey("pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults")
        );

        // and
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .endsWith("TestWithForbiddenResults.html"));

        // and
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo("test-1/jcstress-output.txt"));
    }

}