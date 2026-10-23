package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.baas.model.dynamodb.JobItemMapper;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemJsonTest {

    @Test
    void everyAttributeTypeRoundTrips() {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("s", AttributeValue.fromS("RESULT#p"));
        item.put("n", AttributeValue.fromN("8234574.731914000"));
        item.put("b", AttributeValue.fromB(SdkBytes.fromByteArray(new byte[]{0, 1, (byte) 0xff})));
        item.put("bool", AttributeValue.fromBool(false));
        item.put("nul", AttributeValue.fromNul(true));
        item.put("m", AttributeValue.fromM(Map.of("inner", AttributeValue.fromM(Map.of("x", AttributeValue.fromN("1"))))));
        item.put("l", AttributeValue.fromL(List.of(AttributeValue.fromS("a"), AttributeValue.fromL(List.of(AttributeValue.fromBool(true))))));
        item.put("ss", AttributeValue.fromSs(List.of("a", "b")));
        item.put("ns", AttributeValue.fromNs(List.of("1", "2.50")));
        item.put("bs", AttributeValue.fromBs(List.of(SdkBytes.fromUtf8String("x"))));

        assertThat(ItemJson.decode(ItemJson.encode(item))).isEqualTo(item);
    }

    /** A number is DynamoDB's string, never re-parsed: trailing zeros and precision survive. */
    @Test
    void numbersAreCopiedAsWritten() {
        String line = ItemJson.encode(Map.of("score", AttributeValue.fromN("0.000000123400")));

        assertThat(line).isEqualTo("{\"score\":{\"N\":\"0.000000123400\"}}");
    }

    @Test
    void aMeasurementItemRoundTrips() {
        var measurement = new StoredMeasurement(
            "p", "20261023T090642860Z-5165200d", Instant.parse("2026-10-23T09:06:42.860Z"), MeasurementKind.JMH,
            "com.example.Bench", "m", "thrpt", Map.of("size", "10"), 1234.5, null, "ops/s",
            Map.of(), null, Map.of("imageVersion", "1.4.0"),
            "jobs/p/20261023T090642860Z-5165200d", "jobs/p/20261023T090642860Z-5165200d/jmh-result.json",
            "jobs/p/20261023T090642860Z-5165200d/environment.json", null);
        var item = MeasurementItemMapper.toItem(measurement);

        assertThat(ItemJson.decode(ItemJson.encode(item))).isEqualTo(item);
    }

    @Test
    void aJobItemRoundTrips() {
        var job = new JobItem("20261022T142436535Z-8133679f", "p", Instant.parse("2026-10-22T14:24:36.535Z"),
            "jobs/p/20261022T142436535Z-8133679f", "c5.large", "completed", "i-0abc", null,
            Map.of("source", "local"), null, 7500);
        Map<String, AttributeValue> item = new LinkedHashMap<>(JobItemMapper.key(job));
        item.putAll(JobItemMapper.identityAttributes(job));
        item.put(JobItemMapper.STATUS, AttributeValue.fromS(job.status()));

        assertThat(ItemJson.decode(ItemJson.encode(item))).isEqualTo(item);
    }

    /** The same item always encodes to the same line, whatever map it came in. */
    @Test
    void attributesAreWrittenInNameOrder() {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("sk", AttributeValue.fromS("b"));
        item.put("pk", AttributeValue.fromS("a"));

        assertThat(ItemJson.encode(item)).isEqualTo("{\"pk\":{\"S\":\"a\"},\"sk\":{\"S\":\"b\"}}");
    }

    @Test
    void anUnknownTypeIsRefused() {
        assertThatThrownBy(() -> ItemJson.decode("{\"pk\":{\"X\":\"a\"}}"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("X");
    }

    @Test
    void anAttributeWithTwoTypesIsRefused() {
        assertThatThrownBy(() -> ItemJson.decode("{\"pk\":{\"S\":\"a\",\"N\":\"1\"}}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLineThatIsNotAnObjectIsRefused() {
        assertThatThrownBy(() -> ItemJson.decode("[1]")).isInstanceOf(IllegalArgumentException.class);
    }
}
