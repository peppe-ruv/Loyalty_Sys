package io.loyaltyhub.wallet.domain;

import java.time.LocalDate;

/** Edizione annuale (docs/servizi/wallet-service.md §2, docs/03 §4.4). */
public record Edition(
        String code,
        String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate redemptionGraceUntil,
        String status
) {
    public static final String PLANNED = "PLANNED";
    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";
}
