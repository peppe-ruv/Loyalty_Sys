package io.loyaltyhub.wallet.domain;

import java.math.BigDecimal;
import java.util.List;

/** Livello del programma (docs/servizi/wallet-service.md §2). Il {@code multiplier} è il vantaggio del tier. */
public record Tier(
        String code,
        String name,
        int rank,
        long thresholdSts,
        BigDecimal multiplier,
        List<String> benefits,
        String color,
        String icon
) {
}
