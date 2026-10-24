package pl.wsztajerowski.baas.model.dynamodb;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobStatus;

import static org.assertj.core.api.Assertions.assertThat;

class JobItemConditionsTest {

    @Test
    void everyPlaceholderTheGuardNamesHasAValue() {
        assertThat(JobItemConditions.NOT_TERMINAL_VALUES.keySet())
            .allSatisfy(placeholder -> assertThat(JobItemConditions.NOT_TERMINAL).contains(placeholder));
    }

    @Test
    void theGuardCoversEveryTerminalStatusAndTheFailedPrefix() {
        assertThat(JobItemConditions.NOT_TERMINAL_VALUES.values())
            .containsAll(JobStatus.EXACT_TERMINAL)
            .contains(JobStatus.FAILED_PREFIX);
    }

    @Test
    void theExistenceConditionNamesThePartitionKeyAttribute() {
        assertThat(JobItemConditions.EXISTS).isEqualTo("attribute_exists(" + MeasurementItemMapper.PK + ")");
    }
}
