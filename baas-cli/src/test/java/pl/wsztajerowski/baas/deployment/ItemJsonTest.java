package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.model.TestMeasurements;
import pl.wsztajerowski.baas.model.TestJobs;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.dynamodb.JobItemMapper;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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
        var measurement = TestMeasurements.jmh()
            .project("p")
            .jobId("20261023T090642860Z-5165200d")
            .createdAt("2026-10-23T09:06:42.860Z")
            .benchmarkMethod("m")
            .params(Map.of("size", "10"))
            .scoreError(null)
            .tags(Map.of("imageVersion", "1.4.0"))
            .resultPath("jobs/p/20261023T090642860Z-5165200d")
            .resultJsonKey("jobs/p/20261023T090642860Z-5165200d/jmh-result.json")
            .environmentJsonKey("jobs/p/20261023T090642860Z-5165200d/environment.json")
            .build();
        var item = MeasurementItemMapper.toItem(measurement);

        assertThat(ItemJson.decode(ItemJson.encode(item))).isEqualTo(item);
    }

    @Test
    void aJobItemRoundTrips() {
        var job = TestJobs.job("20261022T142436535Z-8133679f").createdAt("2026-10-22T14:24:36.535Z")
            .instanceType("c5.large").status("completed").instanceId("i-0abc").tags(Map.of("source", "local"))
            .watchdogSeconds(7500).build();
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
