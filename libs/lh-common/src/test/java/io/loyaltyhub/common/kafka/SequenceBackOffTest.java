package io.loyaltyhub.common.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceBackOffTest {

    @Test
    void yieldsConfiguredSequenceAndStops() {
        SequenceBackOff backOff = new SequenceBackOff(new long[]{100, 200});
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(100);
        assertThat(execution.nextBackOff()).isEqualTo(200);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
