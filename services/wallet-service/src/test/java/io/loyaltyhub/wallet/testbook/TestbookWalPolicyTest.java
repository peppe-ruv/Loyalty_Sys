package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.wallet.domain.Edition;
import io.loyaltyhub.wallet.domain.ExpiryPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static io.loyaltyhub.wallet.testbook.WalTestbook.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-WAL §2 — calcolo della scadenza di un lotto (docs/03 §4.1–4.2, wallet-service §5, Q-47): logica pura di
 * {@link ExpiryPolicy}, edizioni e policy lette dai seed. Oracolo: «ultimo istante del mese di earned_at + n mesi, in
 * Europe/Rome»; END_OF_EDITION_PLUS_GRACE → fine di {@code redemptionGraceUntil}.
 */
class TestbookWalPolicyTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/wal/policy.csv", numLinesToSkip = 1)
    void expiresAt(String id, String description, String policy, String earnedRome, String editions, String expectedDay) {
        Instant earned = WalTestbook.rome(earnedRome);
        List<Edition> eds = WalTestbook.seedEditions(!"NOGRACE".equals(editions));

        Instant expiresAt = ExpiryPolicy.expiresAt(policy(policy), earned, day -> containing(eds, day));

        if ("NULL".equals(expectedDay)) {
            assertThat(expiresAt).as("%s: il lotto non scade", id).isNull();
        } else {
            LocalDate day = LocalDate.parse(expectedDay);
            assertThat(WalTestbook.isLastInstantOf(expiresAt, day))
                    .as("%s: atteso l'ultimo istante del %s (Europe/Rome), ottenuto %s", id, day, WalTestbook.describe(expiresAt))
                    .isTrue();
        }
    }

    private static Optional<Edition> containing(List<Edition> eds, LocalDate day) {
        return eds.stream().filter(e -> !day.isBefore(e.startDate()) && !day.isAfter(e.endDate())).findFirst();
    }

    private static JsonNode policy(String token) {
        if ("NULL".equals(token)) {
            return null;
        }
        if ("SEED_PTS".equals(token)) {
            return WalTestbook.seedPolicy("PTS");
        }
        if ("SEED_STS".equals(token)) {
            return WalTestbook.seedPolicy("STS");
        }
        String[] p = token.split(":");
        ObjectNode n = MAPPER.createObjectNode();
        switch (p[0]) {
            case "ROLLING" -> {
                n.put("type", "ROLLING_MONTHS");
                if (p.length > 1) {
                    n.put("months", Integer.parseInt(p[1]));
                }
            }
            case "EOEG" -> {
                n.put("type", "END_OF_EDITION_PLUS_GRACE");
                if (p.length > 1) {
                    n.put("graceDays", Integer.parseInt(p[1]));
                }
            }
            case "NEVER" -> n.put("type", "NEVER");
            case "UNKNOWN" -> n.put("type", "WEEKLY");
            default -> throw new IllegalArgumentException(token);
        }
        return n;
    }
}
