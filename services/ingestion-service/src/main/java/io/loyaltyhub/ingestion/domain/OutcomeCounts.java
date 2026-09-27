package io.loyaltyhub.ingestion.domain;

import java.util.Collection;

/** Conteggi per esito di un batch o di un import (rapporto «accettati/duplicati/respinti/non abbinati», docs/18 §3.6). */
public record OutcomeCounts(int accepted, int duplicate, int rejected, int unmatched, int invalid) {

    public static final OutcomeCounts ZERO = new OutcomeCounts(0, 0, 0, 0, 0);

    public static OutcomeCounts of(Collection<ItemOutcome> outcomes) {
        OutcomeCounts c = ZERO;
        for (ItemOutcome o : outcomes) {
            c = c.plus(o);
        }
        return c;
    }

    public OutcomeCounts plus(ItemOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> new OutcomeCounts(accepted + 1, duplicate, rejected, unmatched, invalid);
            case DUPLICATE -> new OutcomeCounts(accepted, duplicate + 1, rejected, unmatched, invalid);
            case REJECTED -> new OutcomeCounts(accepted, duplicate, rejected + 1, unmatched, invalid);
            case UNMATCHED -> new OutcomeCounts(accepted, duplicate, rejected, unmatched + 1, invalid);
            case INVALID -> new OutcomeCounts(accepted, duplicate, rejected, unmatched, invalid + 1);
        };
    }

    public int total() {
        return accepted + duplicate + rejected + unmatched + invalid;
    }
}
