package pl.wsztajerowski.services;

import dev.morphia.annotations.Entity;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.MongoDbTestHelpers;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.entities.jcstress.JCStressTest;
import pl.wsztajerowski.entities.MongoMeasurementDocument;
import pl.wsztajerowski.infra.MongoResultsStore;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static pl.wsztajerowski.MongoDbTestHelpers.all;
import static pl.wsztajerowski.services.JCStressSubcommandServiceBuilder.serviceBuilder;
import static pl.wsztajerowski.services.options.JCStressOptionsBuilder.jcStressOptionsBuilder;

class JCStressSubcommandServiceIT extends TestcontainersWithS3AndMongoBaseIT {

    /** Fixed and in the past, so a clock read here cannot land near it by accident. */
    private static final Instant LAUNCH_INSTANT = Instant.parse("2026-08-20T17:44:32.812Z");

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
            .withResultsStore(new MongoResultsStore(datastore()))
            .withStorageService(new S3StorageService(awsS3Client, TEST_BUCKET_NAME))
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1", LAUNCH_INSTANT, "test-project", Collections.emptyMap()))
            .withJCStressOptions(jcStressOptions)
            .withBenchmarkPath(Path.of("target", "fake-stress-tests.jar").toAbsolutePath())
            .build();

        // when
        sut.executeCommand();

        // then
        String collectionName = MongoMeasurementDocument.class.getAnnotation(Entity.class).value();
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .extracting("measurement", as(MAP))
                .extracting("jcstress", as(MAP))
                    .containsEntry("totalTests", 2)
                    .containsEntry("passedTests", 1)
                    .extracting("failed", as(MAP))
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

        // and the stored timestamp is the caller's instant, not one read on this machine.
        //
        // JCStressSubcommandService passes commonOptions.createdAt() into the mapper — one line
        // that would compile and pass every other assertion here if it were reverted to
        // Instant.now(), silently breaking the one-instant-per-run property for every run whose
        // launcher and instance clocks differ. JmhStoreIntegrationIT pins the same line for the
        // three JMH-flavoured services; this is the fourth.
        //
        // Asserted through the document id rather than the createdAt field: MongoResultsStore
        // builds it as partitionKey|sortKey, and ResultKeys.sortKey formats measurement.createdAt()
        // directly — so this is a plain String with no Morphia type mapping in the way, and it
        // additionally proves the value reached the key the store orders and de-duplicates on.
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .extractingByKey("_id", as(STRING))
                .contains(ResultKeys.formatTimestamp(LAUNCH_INSTANT)));
    }

}