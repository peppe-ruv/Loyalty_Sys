package io.loyaltyhub.hub;

import io.loyaltyhub.campaign.infra.MemberSnapshotRepository;
import io.loyaltyhub.campaign.messaging.MemberSnapshotAwait;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign §5 (Q-169, Q-489) sul bus in-process (profilo {@code inproc}, ADR-024): il listener delle azioni attende lo
 * snapshot con {@code Acknowledgment#nack(Duration)}; il bus deve riconsegnare lo stesso record dopo il ritardo, non
 * trattare il nack come un errore. Prima, l'ack senza effetto del bus lanciava {@code UnsupportedOperationException},
 * il bus la ritentava come un errore e l'azione finiva in DLQ ({@code lh-campaign}) invece che in {@code NO_MEMBER}.
 */
@SpringBootTest(
        classes = HubApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=hub")
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HubInProcessSnapshotWaitIT {

    private static final EmbeddedPostgres PG = TestbookE2eSupportIT.startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private KafkaTemplate<String, String> bus;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MemberSnapshotAwait snapshotAwait;

    @Autowired
    private MemberSnapshotRepository snapshots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestbookE2eSupportIT.datasource(registry, PG);
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void unknownMemberEndsAsNoMemberWithoutDeadLetter() throws Exception {
        String memberId = "MBR-INPROC-NOM-" + System.nanoTime();
        String actionId = "act-inproc-nom-" + System.nanoTime();

        int waitingBefore = snapshotAwait.pendingCount();
        publishAction(actionId, memberId);
        // Il tempo si misura dal primo nack di campaign: sul bus FIFO l'azione può arrivare in ritardo perché la
        // precedono le consegne (e i ritentativi) degli altri gruppi, es. lh-member per un membro che non conosce.
        awaitWaitStarted(waitingBefore);
        long start = System.nanoTime();
        Optional<String> outcome = awaitOutcome(actionId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome).as("valutazione di %s", actionId).contains("NO_MEMBER");
        assertThat(elapsedMs).as("ms dal primo nack a NO_MEMBER (0,5 + 1 + 2 s)").isBetween(3_300L, 6_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM dlq_entry WHERE event_id = ? AND consumer = 'lh-campaign'")
                .param(actionId).query(Long.class).single()).as("voci DLQ di lh-campaign").isZero();
    }

    @Test
    void snapshotArrivingDuringTheWaitTriggersTheWelcomeBonus() throws Exception {
        String memberId = "MBR-INPROC-REG-" + System.nanoTime();
        String actionId = "act-inproc-reg-" + System.nanoTime();

        int waitingBefore = snapshotAwait.pendingCount();
        publishAction(actionId, memberId);
        awaitWaitStarted(waitingBefore);
        // Il thread del bus è fermo nell'attesa: lo snapshot arriva da fuori (come dal fatto consegnato altrove).
        snapshots.upsertIdentity(memberId, "ACTIVE", null, Instant.now(), null, "{}");

        assertThat(awaitOutcome(actionId)).as("valutazione di %s", actionId).contains("MATCHED");
        assertThat(jdbc.sql("SELECT results::text FROM evaluation_log WHERE action_id = ?")
                .param(actionId).query(String.class).single()).contains("CMP-WELCOME");
        assertThat(jdbc.sql("SELECT count(*) FROM dlq_entry WHERE event_id = ? AND consumer = 'lh-campaign'")
                .param(actionId).query(Long.class).single()).isZero();
    }

    private void publishAction(String actionId, String memberId) throws Exception {
        String now = Instant.now().toString();
        String value = mapper.writeValueAsString(Map.of(
                "specversion", "1.0", "id", actionId, "source", "urn:loyaltyhub:source:internal",
                "type", "io.loyaltyhub.action.member.registered", "subject", "member:" + memberId, "time", now,
                "lhcorrelationid", actionId, "lhhop", 1, "data", Map.of("channel", "PORTAL", "referred", false)));
        bus.send(new ProducerRecord<>("lh.actions.v1", memberId, value));
    }

    /** Segnale esplicito: campaign ha ricevuto l'azione e ha fatto il primo nack in attesa dello snapshot. */
    private void awaitWaitStarted(int waitingBefore) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (snapshotAwait.pendingCount() <= waitingBefore) {
            assertThat(System.currentTimeMillis()).as("attesa dello snapshot iniziata").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private Optional<String> awaitOutcome(String actionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        Optional<String> outcome = Optional.empty();
        while (outcome.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            outcome = jdbc.sql("SELECT outcome FROM evaluation_log WHERE action_id = ?")
                    .param(actionId).query(String.class).optional();
        }
        return outcome;
    }
}
