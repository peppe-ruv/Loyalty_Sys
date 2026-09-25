package io.loyaltyhub.common.kafka;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Politica di backoff basata su una sequenza esplicita di ritardi (docs/04 §5).
 * Quando la sequenza si esaurisce, l'esecuzione restituisce {@link BackOffExecution#STOP}.
 */
public class SequenceBackOff implements BackOff {

    private final long[] backoffs;

    public SequenceBackOff(long[] backoffs) {
        this.backoffs = backoffs != null ? backoffs : new long[0];
    }

    @Override
    public BackOffExecution start() {
        return new SequenceBackOffExecution();
    }

    private class SequenceBackOffExecution implements BackOffExecution {
        private int attempt = 0;

        @Override
        public long nextBackOff() {
            if (attempt >= backoffs.length) {
                return STOP;
            }
            return backoffs[attempt++];
        }
    }
}
