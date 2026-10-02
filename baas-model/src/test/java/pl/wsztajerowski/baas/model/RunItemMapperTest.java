package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunItemMapperTest {

    static RunItem run() {
        return new RunItem("20261003T000000000Z-a3f9c21b", "lynx-journal",
            Instant.parse("2026-10-03T00:00:00Z"), "runs/lynx-journal/20261003T000000000Z-a3f9c21b",
            "c5.2xlarge", RunStatus.LAUNCHING, null, null,
            Map.of("project", "lynx-journal", "source", "local", "type", "jmh", "branch", "main"), null);
    }

    /** What a stored item looks like once the CLI and the instance have each written to it. */
    private static Map<String, AttributeValue> stored(RunItem run, String status, String instanceId) {
        var item = new HashMap<>(RunItemMapper.key(run));
        item.putAll(RunItemMapper.identityAttributes(run));
        item.put(RunItemMapper.STATUS, AttributeValue.fromS(status));
        if (instanceId != null) {
            item.put(RunItemMapper.INSTANCE_ID, AttributeValue.fromS(instanceId));
        }
        return item;
    }

    @Test
    void theKeyIsTheRunPartitionAndTheRunSortKey() {
        assertThat(RunItemMapper.key(run())).isEqualTo(Map.of(
            "pk", AttributeValue.fromS("RUN"),
            "sk", AttributeValue.fromS("2026-10-03T00:00:00.000Z#20261003T000000000Z-a3f9c21b")));
    }

    @Test
    void identityCarriesTheIndexKeysSoALookupByRunIdFindsTheRunItem() {
        var identity = RunItemMapper.identityAttributes(run());

        assertThat(identity.get("gsi1pk").s()).isEqualTo("20261003T000000000Z-a3f9c21b");
        assertThat(identity.get("gsi1sk").s()).isEqualTo(ResultKeys.RUN_INDEX_SORT_KEY);
        assertThat(identity.get("createdAt").s()).isEqualTo("2026-10-03T00:00:00.000Z");
    }

    /** The reservation is the only write that sets identity; it must never carry a status. */
    @Test
    void identityHoldsNoStatusOrInstance() {
        assertThat(RunItemMapper.identityAttributes(run()))
            .doesNotContainKeys(RunItemMapper.STATUS, RunItemMapper.INSTANCE_ID, RunItemMapper.UPDATED_AT);
    }

    @Test
    void aStoredRunRoundTrips() {
        var read = RunItemMapper.fromItem(stored(run(), RunStatus.RUNNING, "i-0abc"));

        assertThat(read.runId()).isEqualTo(run().runId());
        assertThat(read.project()).isEqualTo("lynx-journal");
        assertThat(read.createdAt()).isEqualTo(run().createdAt());
        assertThat(read.resultPath()).isEqualTo(run().resultPath());
        assertThat(read.instanceType()).isEqualTo("c5.2xlarge");
        assertThat(read.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(read.instanceId()).isEqualTo("i-0abc");
        assertThat(read.tags()).isEqualTo(run().tags());
        assertThat(read.updatedAt()).isNull();
        assertThat(read.isTerminal()).isFalse();
    }

    @Test
    void anItemWithNoTagsReadsAsEmptyTags() {
        var bare = new RunItem("r", "p", Instant.parse("2026-10-03T00:00:00Z"), null, null,
            RunStatus.LAUNCHING, null, null, Map.of(), null);

        assertThat(RunItemMapper.identityAttributes(bare)).doesNotContainKey(RunItemMapper.TAGS);
        assertThat(RunItemMapper.fromItem(stored(bare, RunStatus.LAUNCHING, null)).tags()).isEmpty();
    }

    @Test
    void aMeasurementIsNotARunItem() {
        var measurement = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh());

        assertThat(RunItemMapper.isRunItem(measurement)).isFalse();
        assertThatThrownBy(() -> RunItemMapper.fromItem(measurement))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not a run item");
    }
}
