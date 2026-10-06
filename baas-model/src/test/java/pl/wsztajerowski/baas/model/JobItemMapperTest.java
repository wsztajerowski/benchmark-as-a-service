package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobItemMapperTest {

    static JobItem run() {
        return new JobItem("20261003T000000000Z-a3f9c21b", "lynx-journal",
            Instant.parse("2026-10-03T00:00:00Z"), "jobs/lynx-journal/20261003T000000000Z-a3f9c21b",
            "c5.2xlarge", JobStatus.LAUNCHING, null, null,
            Map.of("project", "lynx-journal", "source", "local", "type", "jmh", "branch", "main"), null);
    }

    /** What a stored item looks like once the CLI and the instance have each written to it. */
    private static Map<String, AttributeValue> stored(JobItem job, String status, String instanceId) {
        var item = new HashMap<>(JobItemMapper.key(job));
        item.putAll(JobItemMapper.identityAttributes(job));
        item.put(JobItemMapper.STATUS, AttributeValue.fromS(status));
        if (instanceId != null) {
            item.put(JobItemMapper.INSTANCE_ID, AttributeValue.fromS(instanceId));
        }
        return item;
    }

    @Test
    void theKeyIsTheJobPartitionAndTheJobSortKey() {
        assertThat(JobItemMapper.key(run())).isEqualTo(Map.of(
            "pk", AttributeValue.fromS("JOB"),
            "sk", AttributeValue.fromS("2026-10-03T00:00:00.000Z#20261003T000000000Z-a3f9c21b")));
    }

    @Test
    void identityCarriesTheIndexKeysSoALookupByJobIdFindsTheJobItem() {
        var identity = JobItemMapper.identityAttributes(run());

        assertThat(identity.get("gsi1pk").s()).isEqualTo("20261003T000000000Z-a3f9c21b");
        assertThat(identity.get("gsi1sk").s()).isEqualTo(ResultKeys.JOB_INDEX_SORT_KEY);
        assertThat(identity.get("createdAt").s()).isEqualTo("2026-10-03T00:00:00.000Z");
    }

    /** The reservation is the only write that sets identity; it must never carry a status. */
    @Test
    void identityHoldsNoStatusOrInstance() {
        assertThat(JobItemMapper.identityAttributes(run()))
            .doesNotContainKeys(JobItemMapper.STATUS, JobItemMapper.INSTANCE_ID, JobItemMapper.UPDATED_AT);
    }

    @Test
    void aStoredJobRoundTrips() {
        var read = JobItemMapper.fromItem(stored(run(), JobStatus.RUNNING, "i-0abc"));

        assertThat(read.jobId()).isEqualTo(run().jobId());
        assertThat(read.project()).isEqualTo("lynx-journal");
        assertThat(read.createdAt()).isEqualTo(run().createdAt());
        assertThat(read.resultPath()).isEqualTo(run().resultPath());
        assertThat(read.instanceType()).isEqualTo("c5.2xlarge");
        assertThat(read.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(read.instanceId()).isEqualTo("i-0abc");
        assertThat(read.tags()).isEqualTo(run().tags());
        assertThat(read.updatedAt()).isNull();
        assertThat(read.isTerminal()).isFalse();
    }

    @Test
    void anItemWithNoTagsReadsAsEmptyTags() {
        var bare = new JobItem("r", "p", Instant.parse("2026-10-03T00:00:00Z"), null, null,
            JobStatus.LAUNCHING, null, null, Map.of(), null);

        assertThat(JobItemMapper.identityAttributes(bare)).doesNotContainKey(JobItemMapper.TAGS);
        assertThat(JobItemMapper.fromItem(stored(bare, JobStatus.LAUNCHING, null)).tags()).isEmpty();
    }

    @Test
    void aMeasurementIsNotAJobItem() {
        var measurement = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh());

        assertThat(JobItemMapper.isJobItem(measurement)).isFalse();
        assertThatThrownBy(() -> JobItemMapper.fromItem(measurement))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Not a job item");
    }
}
