package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementItemMapperTest {

    @Test
    void aJmhMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jmh();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void aJcstressMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jcstress();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void theItemCarriesBothKeyPairs() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh());

        assertThat(item.get("pk").s()).isEqualTo("RESULT#lynx-journal");
        assertThat(item.get("sk").s()).startsWith("pl.wsztajerowski.fake.Incrementing_Synchronized#");
        assertThat(item.get("gsi1pk").s()).isEqualTo("jmh-20260817_220706");
        assertThat(item.get("gsi1sk").s())
            .isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized");
    }

    @Test
    void absentOptionalAttributesAreOmittedRatherThanStoredAsNull() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jcstress());

        assertThat(item).doesNotContainKey("resultJsonKey");
        assertThat(item).doesNotContainKey("benchmarkMethod");
    }

    @Test
    void aRealisticMeasurementIsFarUnderTheItemLimit() {
        var bytes = MeasurementItemMapper.serializedSize(
            MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));

        assertThat(bytes).isLessThan(4 * 1024);
    }

    @Test
    void anOversizedMeasurementFailsLoudlyRatherThanBeingTruncated() {
        Map<String, String> hugeTags = IntStream.range(0, 20_000)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "value".repeat(10)));

        var oversized = StoredMeasurementFixtures.jmh().withTags(hugeTags);

        assertThatThrownBy(() -> MeasurementItemMapper.toItem(oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("jmh-20260817_220706");
    }

    @Test
    void anUnknownAttributeInStoredDataDoesNotBreakReads() {
        var item = new HashMap<>(MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));
        item.put("attributeAddedByALaterVersion", AttributeValue.fromS("whatever"));

        assertThat(MeasurementItemMapper.fromItem(item))
            .isEqualTo(StoredMeasurementFixtures.jmh());
    }
}
