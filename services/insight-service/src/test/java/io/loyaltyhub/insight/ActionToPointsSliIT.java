package io.loyaltyhub.insight;

import io.loyaltyhub.insight.application.EventIngestService;
import io.loyaltyhub.insight.infra.EventStoreRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLI «azione → punti» (M8.6a, F2-OBS-01, ADR-036, Q-523): {@code lh_action_to_points_seconds} conta una volta per ogni
 * {@code wallet.points.earned} <em>nuovo</em> la cui azione radice ({@code lhcorrelationid}) è nell'event store, con il
 * tempo trascorso dalla registrazione dell'azione. Gli altri fatti, gli effetti, i duplicati e le catene senza azione
 * nello store non lo toccano. Il contesto è quello degli altri IT di insight (Kafka incorporato + Postgres Zonky,
 * profilo {@code demo}); i record sono costruiti a mano e passati a {@link EventIngestService}, come fa il listener.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"loyaltyhub.insight.retention.cron=-", "loyaltyhub.insight.audit.anchor-cron=-"})
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ActionToPointsSliIT {

    private static final String METRIC = "lh_action_to_points_seconds";
    private static final String ACTION_TYPE = "io.loyaltyhub.action.purchase.completed";
    private static final String EARNED = "io.loyaltyhub.fact.wallet.points.earned";
    private static final String SPENT = "io.loyaltyhub.fact.wallet.points.spent";
    private static final String GRANT_EFFECT = "io.loyaltyhub.effect.points.grant";

    private static final EmbeddedPostgres PG = startPg();

    @Autowired
    private EventIngestService ingest;

    @Autowired
    private EventStoreRepository events;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry registry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=insight");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @BeforeEach
    void clean() {
        events.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("un accredito nuovo con l'azione radice nello store conta una volta, col tempo trascorso")
    void newPointsEarnedIsObservedOnceWithElapsedTime() {
        String action = "ACT-SLI-A";
        ingest.ingest("lh.actions.v1", "ACTION", record("lh.actions.v1", 0, event(action, ACTION_TYPE, action)));
        // L'azione è stata registrata da 3 secondi: la query del repository usa clock_timestamp().
        jdbc.sql("UPDATE event_store SET received_at = now() - interval '3 seconds' WHERE event_id = ?")
                .param(action).update();
        Timer t = timer();
        long before = t.count();
        double totalBefore = t.totalTime(TimeUnit.SECONDS);
        long within2Before = countAtOrBelow(t, 2.0);
        long within5Before = countAtOrBelow(t, 5.0);

        ingest.ingest("lh.facts.v1", "FACT", record("lh.facts.v1", 1, event("FCT-SLI-1", EARNED, action)));

        assertThat(t.count() - before).isEqualTo(1);
        assertThat(t.totalTime(TimeUnit.SECONDS) - totalBefore).as("tempo osservato, in secondi").isBetween(2.5, 10.0);
        assertThat(countAtOrBelow(t, 2.0) - within2Before).as("nessuna osservazione entro 2 s").isZero();
        assertThat(countAtOrBelow(t, 5.0) - within5Before).as("una osservazione entro 5 s").isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("lo stesso accredito riletto (duplicato) non conta due volte")
    void replayedPointsEarnedIsNotCountedTwice() {
        String action = "ACT-SLI-B";
        ingest.ingest("lh.actions.v1", "ACTION", record("lh.actions.v1", 0, event(action, ACTION_TYPE, action)));
        ConsumerRecord<String, String> earned = record("lh.facts.v1", 1, event("FCT-SLI-2", EARNED, action));
        ingest.ingest("lh.facts.v1", "FACT", earned);
        long afterFirst = timer().count();

        ingest.ingest("lh.facts.v1", "FACT", earned);

        assertThat(timer().count()).isEqualTo(afterFirst);
    }

    @Test
    @Order(3)
    @DisplayName("un accredito con azione radice sconosciuta non produce osservazioni")
    void unknownRootActionIsIgnored() {
        long before = timer().count();

        ingest.ingest("lh.facts.v1", "FACT", record("lh.facts.v1", 1, event("FCT-SLI-3", EARNED, "ACT-SLI-MISSING")));

        assertThat(timer().count()).isEqualTo(before);
    }

    @Test
    @Order(4)
    @DisplayName("gli altri fatti del wallet non contano (points.spent)")
    void otherFactsAreIgnored() {
        String action = "ACT-SLI-D";
        ingest.ingest("lh.actions.v1", "ACTION", record("lh.actions.v1", 0, event(action, ACTION_TYPE, action)));
        long before = timer().count();

        ingest.ingest("lh.facts.v1", "FACT", record("lh.facts.v1", 1, event("FCT-SLI-4", SPENT, action)));

        assertThat(timer().count()).isEqualTo(before);
    }

    @Test
    @Order(5)
    @DisplayName("un effetto (points.grant) con la stessa correlazione non conta")
    void effectsAreIgnored() {
        String action = "ACT-SLI-E";
        ingest.ingest("lh.actions.v1", "ACTION", record("lh.actions.v1", 0, event(action, ACTION_TYPE, action)));
        long before = timer().count();

        ingest.ingest("lh.effects.v1", "EFFECT", record("lh.effects.v1", 1, event("EFX-SLI-5", GRANT_EFFECT, action)));

        assertThat(timer().count()).isEqualTo(before);
    }

    @Test
    @Order(6)
    @DisplayName("una correlazione che non è di una azione (è un fatto) non conta")
    void correlationOfANonActionIsIgnored() {
        // Il fatto F risulta nello store come FACT: un secondo accredito che lo dichiara come radice non è un'azione.
        ingest.ingest("lh.facts.v1", "FACT", record("lh.facts.v1", 0, event("FCT-SLI-6a", SPENT, "FCT-SLI-6a")));
        long before = timer().count();

        ingest.ingest("lh.facts.v1", "FACT", record("lh.facts.v1", 1, event("FCT-SLI-6b", EARNED, "FCT-SLI-6a")));

        assertThat(timer().count()).isEqualTo(before);
    }

    // ---------- helper ----------

    private Timer timer() {
        Timer t = registry.find(METRIC).timer();
        assertThat(t).as("il timer %s è registrato all'avvio", METRIC).isNotNull();
        return t;
    }

    /** Conteggio cumulativo delle osservazioni entro {@code seconds} (bucket esplicito degli SLO). */
    private static long countAtOrBelow(Timer timer, double seconds) {
        CountAtBucket bucket = Arrays.stream(timer.takeSnapshot().histogramCounts())
                .filter(b -> b.bucket(TimeUnit.SECONDS) == seconds)
                .findFirst()
                .orElseThrow(() -> new AssertionError("bucket " + seconds + " s assente"));
        return (long) bucket.count();
    }

    private static String event(String id, String type, String correlationId) {
        return """
                {"specversion":"1.0","id":"%s","source":"urn:loyaltyhub:service:wallet","type":"%s",\
                "subject":"member:MBR-000001","time":"%s","datacontenttype":"application/json",\
                "lhtenant":"aurora","lhcorrelationid":"%s","lhhop":0,\
                "data":{"amount":10,"currency":"PTS"}}"""
                .formatted(id, type, Instant.now(), correlationId);
    }

    private static ConsumerRecord<String, String> record(String topic, long offset, String json) {
        return new ConsumerRecord<>(topic, 0, offset, "MBR-000001", json);
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
