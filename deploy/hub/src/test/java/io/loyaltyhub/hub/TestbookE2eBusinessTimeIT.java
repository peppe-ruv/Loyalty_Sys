package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-E2E — tempo di business (docs/03 §3.1: il {@code time} dell'azione, calendari e limiti su Europe/Rome):
 * mezzanotte di Roma e fine settimana, il giorno di 25 ore del cambio dell'ora di ottobre, il limite giornaliero, la
 * scadenza dei lotti e il periodo delle classifiche a fine mese, la serie di accessi a cavallo del cambio dell'ora.
 * «Oggi» è lunedì 02/11/2026 alle 13:00 di Roma, così tutto ottobre 2026 (cambio dell'ora il 25) è nella finestra di
 * ingresso di 30 giorni.
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookE2eBusinessTimeIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookE2eBusinessTimeIT extends TestbookE2eSupportIT {

    private static final EmbeddedPostgres PG = startPg();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    /** Orologio del caso, registrato solo da questa classe (vedi {@link TestbookE2eJourneysIT.Today}). */
    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-11-02T12:00:00Z");
        }
    }

    @TestFactory
    Stream<DynamicTest> tempo() {
        return rows("tempo.csv", this::tempo);
    }

    private void tempo(Row row) {
        Member m = newMember();
        String last = null;
        boolean purchases = false;
        for (String token : row.get("eventi").trim().split("\\s+")) {
            String[] p = token.split("\\|");
            Instant at = p[1].endsWith("Z") ? Instant.parse(p[1]) : rome(p[1]);
            if (p[0].equals("P")) {
                purchases = true;
                last = purchase(m.subject(), Double.parseDouble(p[2]), at);
            } else {
                last = act("app", "app.login.daily", m.subject(), at, Map.of("platform", "WEB"));
            }
            quiet();
        }
        List<Ev> chain = chain(last);
        assertTree(chain);
        String root = chain.stream().filter(e -> e.causation() == null).findFirst().orElseThrow().id();
        Ev evaluated = chain.stream().filter(e -> e.key().equals("FACT:campaign.evaluated") && root.equals(e.causation()))
                .findFirst().orElseThrow();
        String[] expected = row.get("ultimo").split(":");
        if (expected[0].equals("LIMIT")) {
            assertThat(skipped(evaluated)).containsEntry(expected[1], "LIMIT");
            assertThat(ledger(m.id(), last).stream().filter(l -> l.endsWith(":" + expected[1]))).as("nessun accredito").isEmpty();
            long paid = ledgerAll(m.id()).stream().filter(l -> l.startsWith("EARN:PTS:") && l.endsWith(":" + expected[1])).count();
            assertThat(paid).as("accrediti di %s nel giorno", expected[1]).isEqualTo(purchases ? 3 : 1);
        } else {
            assertThat(ledger(m.id(), last)).contains(row.get("ultimo"));
        }
        if (row.is("weekend", "si")) {
            assertThat(matched(evaluated)).contains("CMP-WEEKEND-X2");
        } else if (row.is("weekend", "no")) {
            assertThat(skipped(evaluated)).containsEntry("CMP-WEEKEND-X2", "CONDITION");
        }
        if (!row.blank("scadenza")) {
            Instant expires = jdbc.sql("""
                            SELECT l.expires_at FROM wallet.points_lot l JOIN wallet.ledger_entry e ON e.id = l.ledger_entry_id
                            WHERE e.member_id = ? AND e.correlation_id = ? AND e.campaign_code = 'CMP-PURCHASE-BASE' AND e.currency = 'PTS'
                            """).params(m.id(), last).query(java.sql.Timestamp.class).single().toInstant();
            assertThat(expires).as("fine del mese di earned_at + 12 mesi a Roma (wallet §5)").isEqualTo(Instant.parse(row.get("scadenza")));
        }
        if (!row.blank("periodo")) {
            // TESTBOOK: ambiguo, vedi TB-E2E-TIM-009 (time del bonus badge: istante di elaborazione, non quello dell'acquisto)
            List<String> scores = jdbc.sql("""
                            SELECT s.period_key || '=' || s.score FROM gamification.leaderboard_score s
                            JOIN gamification.leaderboard b ON b.id = s.leaderboard_id
                            WHERE b.code = 'LDB-MONTH-PTS' AND s.member_id = ?
                            """).param(m.id()).query(String.class).list().stream().sorted().toList();
            assertThat(scores).as("punteggi per mese di Roma dell'accredito").isEqualTo(sorted(row.get("periodo")));
            String purchaseMonth = row.get("periodo").split("=")[0];
            assertThat(jdbc.sql("""
                            SELECT p.period_key FROM gamification.achievement_progress p JOIN gamification.achievement a ON a.id = p.achievement_id
                            WHERE a.code = 'ACH-3-PURCHASES-MONTH' AND p.member_id = ?
                            """).param(m.id()).query(String.class).list()).containsExactly(purchaseMonth);
        }
        if (row.id().equals("TB-E2E-TIM-013")) {
            assertChain(last, "A:app.login.daily=1 A:achievement.completed=1 A:badge.awarded=1 F:campaign.evaluated=3 E:points.grant=2 "
                    + "F:wallet.points.earned=2 F:achievement.completed=1 F:badge.awarded=1 F:message.delivered=3");
            assertThat(badges(m.id())).containsExactly("BDG-STREAK");
            assertThat(pts(m.id())).isEqualTo(100 + 7 * 5 + 100);
        }
    }
}
