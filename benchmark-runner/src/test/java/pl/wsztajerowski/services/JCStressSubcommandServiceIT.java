package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JCStressRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper.SK;

class JCStressSubcommandServiceIT extends TestcontainersWithDynamoDbBaseIT {

    /** Fixed and in the past, so a clock read here cannot land near it by accident. */
    private static final Instant LAUNCH_INSTANT = Instant.parse("2026-08-20T17:44:32.812Z");


    @Test
    void successful_scenario() throws IOException {
        // given
        Path tempDirectory = Files.createTempDirectory("jcstress");
        JCStressSubcommandService sut = new JCStressSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-1"), "req-1", LAUNCH_INSTANT, "test-project", Collections.emptyMap()),
            new JCStressRun(Path.of("target", "fake-stress-tests.jar").toAbsolutePath(),
                List.of("-f", "1", "-sc", "false"), tempDirectory));

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements()).singleElement().satisfies(measurement -> {
            assertThat(measurement.jcstress().totalTests()).isEqualTo(2);
            assertThat(measurement.jcstress().passedTests()).isEqualTo(1);
            assertThat(measurement.jcstress().failed())
                .containsKey("pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults");
        });

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
        // Instant.now(), silently breaking the one-instant-per-job property for every job whose
        // launcher and instance clocks differ. JmhStoreIntegrationIT pins the same line for the
        // three JMH-flavoured services; this is the fourth.
        //
        // Asserted through the sort key rather than the createdAt attribute: ResultKeys.sortKey
        // formats measurement.createdAt() directly, so this also proves the value reached the key
        // the table orders and de-duplicates on.
        assertThat(scanTestTable()).singleElement()
            .extracting(item -> item.get(SK).s(), as(STRING))
            .contains(ResultKeys.formatTimestamp(LAUNCH_INSTANT));
    }

}