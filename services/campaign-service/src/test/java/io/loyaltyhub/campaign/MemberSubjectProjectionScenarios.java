package io.loyaltyhub.campaign;

import io.loyaltyhub.common.identity.SubjectRef;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.testsupport.ListenerGroups;
import io.loyaltyhub.testsupport.TestSubjectKeys;
import io.loyaltyhub.campaign.infra.CampaignMemberSubjectLookup;
import io.loyaltyhub.campaign.messaging.MemberSnapshotHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.campaign.MemberFactsSupport.ABSENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proiezione locale del legame {@code subjectRef → membro} in campaign-service (F2-SEC-09, ADR-048, Q-550; docs/06 §3.4 e
 * §9): fatti veri {@code member.registered} / {@code member.updated} (schema {@code :1} e {@code :2}) sul bus embedded, lo
 * stato letto da {@code member_snapshot}. Senza Docker: EmbeddedKafka + Zonky.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.actions.v1", "lh.effects.v1", "lh.facts.v1", "lh.audit.v1", "lh.dlq.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MemberSubjectProjectionScenarios {

    private static final EmbeddedPostgres PG = startPg();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                PG.close();
            } catch (Exception ignored) {
                // chiusura best effort a fine JVM
            }
        }));
    }
    private static final byte[] KEY = TestSubjectKeys.random();
    private static final String ISS = "https://idp.example.test/realms/loyaltyhub";

    private static final String T1 = "2026-09-01T10:00:00Z";
    private static final String T2 = "2026-09-02T10:00:00Z";
    private static final String T3 = "2026-09-03T10:00:00Z";
    private static final String T4 = "2026-09-04T10:00:00Z";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private CampaignMemberSubjectLookup lookup;

    @Autowired
    private MemberSnapshotHandler snapshotHandler;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private MeterRegistry meters;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=campaign");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @BeforeAll
    void waitForListenerGroup() {
        ListenerGroups.awaitStable(listeners);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-001] member.registered:1 con subjectRef: snapshot e legame nascono insieme")
    void registeredV1LinksAndCreatesTheSnapshot() {
        String ref = ref("reg-v1");
        publish(MemberFactsSupport.registered(id(1), ref, T1, 1));

        assertThat(subjectRef(id(1))).isEqualTo(ref);
        assertThat(lookup.memberId(ref)).contains(id(1));
        assertThat(snapshotStatus(id(1))).isEqualTo("ACTIVE"); // nella stessa transazione del legame
    }

    @Test
    @DisplayName("[TB-CMP-MBP-002] member.registered:2 con subjectRef: legato")
    void registeredV2Links() {
        String ref = ref("reg-v2");
        publish(MemberFactsSupport.registered(id(2), ref, T1, 2));

        assertThat(lookup.memberId(ref)).contains(id(2));
    }

    @Test
    @DisplayName("[TB-CMP-MBP-003] member.updated:1 e :2 legano un membro registrato senza subjectRef")
    void updatedLinks() {
        publish(MemberFactsSupport.registered(id(3), ABSENT, T1, 2),
                MemberFactsSupport.registered(id(4), ABSENT, T1, 2));
        assertThat(subjectRef(id(3))).isNull();

        publish(MemberFactsSupport.updated(id(3), ref("upd-v1"), T2, 1),
                MemberFactsSupport.updated(id(4), ref("upd-v2"), T2, 2));

        assertThat(lookup.memberId(ref("upd-v1"))).contains(id(3));
        assertThat(lookup.memberId(ref("upd-v2"))).contains(id(4));
    }

    @Test
    @DisplayName("[TB-CMP-MBP-004] subjectRef assente: nessun effetto, un member-service più vecchio non slega nessuno")
    void absentClaimHasNoEffect() {
        publish(MemberFactsSupport.registered(id(5), ref("abs"), T1, 2));
        publish(MemberFactsSupport.updated(id(5), ABSENT, T2, 2));
        publish(MemberFactsSupport.registered(id(6), ABSENT, T1, 2));

        assertThat(lookup.memberId(ref("abs"))).contains(id(5));
        assertThat(subjectRef(id(6))).isNull();
        assertThat(refAt(id(6))).isNull();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-005] uno pseudonimo che non ha la forma (non 64 esadecimali) non lega nessuno")
    void malformedClaimHasNoEffect() {
        publish(MemberFactsSupport.registered(id(7), "non-uno-pseudonimo", T1, 2));

        assertThat(subjectRef(id(7))).isNull();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-006] subjectRef null: il legame si rimuove")
    void nullClaimUnlinks() {
        publish(MemberFactsSupport.registered(id(8), ref("nul"), T1, 2));
        assertThat(lookup.memberId(ref("nul"))).contains(id(8));

        publish(MemberFactsSupport.updated(id(8), null, T2, 2));

        assertThat(lookup.memberId(ref("nul"))).isEmpty();
        assertThat(subjectRef(id(8))).isNull();
        assertThat(refAt(id(8))).isEqualTo(Instant.parse(T2));
    }

    @Test
    @DisplayName("[TB-CMP-MBP-007] un fatto più vecchio dell'ultimo aggiornamento del legame non ri-lega né slega")
    void staleFactHasNoEffect() {
        publish(MemberFactsSupport.registered(id(9), ref("stale-1"), T2, 2));
        // replay vecchio: un altro pseudonimo con un istante precedente non sposta il legame
        publish(MemberFactsSupport.updated(id(9), ref("stale-0"), T1, 2));
        publish(MemberFactsSupport.updated(id(9), null, T1, 2));

        assertThat(lookup.memberId(ref("stale-1"))).contains(id(9));
        assertThat(lookup.memberId(ref("stale-0"))).isEmpty();
        // un fatto più recente vale
        publish(MemberFactsSupport.updated(id(9), ref("stale-2"), T3, 2));
        assertThat(lookup.memberId(ref("stale-2"))).contains(id(9));
        assertThat(lookup.memberId(ref("stale-1"))).isEmpty();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-008] stesso pseudonimo su due membri: vince il più recente, il replay vecchio non lo riprende")
    void newerRelinkWinsAndOldReplayDoesNot() {
        String ref = ref("relink");
        double before = relinked();
        publish(MemberFactsSupport.registered(id(10), ref, T1, 2)); // il vecchio membro
        publish(MemberFactsSupport.registered(id(11), ref, T2, 2)); // la stessa persona si registra di nuovo

        assertThat(lookup.memberId(ref)).contains(id(11));
        assertThat(subjectRef(id(10))).isNull();
        assertThat(relinked()).isEqualTo(before + 1);

        // replay del fatto vecchio (altro id evento, istante T1): il detentore è più recente, nessun effetto
        publish(MemberFactsSupport.updated(id(10), ref, T1, 2));
        assertThat(lookup.memberId(ref)).contains(id(11));
        assertThat(relinked()).isEqualTo(before + 1);

        // un fatto ancora più recente del vecchio membro riprende lo pseudonimo
        publish(MemberFactsSupport.updated(id(10), ref, T3, 2));
        assertThat(lookup.memberId(ref)).contains(id(10));
        assertThat(subjectRef(id(11))).isNull();
        assertThat(relinked()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-009] a parità di istante decide l'id del membro (il maggiore), su ogni replica")
    void tieBreaksOnTheMemberId() {
        String ref = ref("tie");
        publish(MemberFactsSupport.registered(id(13), ref, T2, 2));
        publish(MemberFactsSupport.registered(id(12), ref, T2, 2)); // pari istante, id minore: perde

        assertThat(lookup.memberId(ref)).contains(id(13));
        assertThat(subjectRef(id(12))).isNull();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-010] anonimizzazione: lapide definitiva, nessun replay ri-lega il membro")
    void anonymizationIsATombstone() {
        String ref = ref("anon");
        publish(MemberFactsSupport.registered(id(14), ref, T1, 2));
        assertThat(lookup.memberId(ref)).contains(id(14));

        publish(MemberFactsSupport.statusChanged(id(14), "ANONYMIZED", T2));

        assertThat(lookup.memberId(ref)).isEmpty();
        assertThat(erased(id(14))).isTrue();
        assertThat(subjectRef(id(14))).isNull();

        // replay del fatto di registrazione e un member.updated con un istante successivo: la lapide vince
        publish(MemberFactsSupport.registered(id(14), ref, T1, 2));
        publish(MemberFactsSupport.updated(id(14), ref, T4, 2));
        assertThat(lookup.memberId(ref)).isEmpty();
        assertThat(erased(id(14))).isTrue();

        // la stessa persona che si registra di nuovo è un nuovo membro e ottiene il legame
        publish(MemberFactsSupport.registered(id(15), ref, T4, 2));
        assertThat(lookup.memberId(ref)).contains(id(15));
    }

    @Test
    @DisplayName("[TB-CMP-MBP-011] anche member.updated con status ANONYMIZED cancella il legame")
    void anonymizedByUpdatedFact() {
        String ref = ref("anon-upd");
        publish(MemberFactsSupport.registered(id(16), ref, T1, 2));
        publish(MemberFactsSupport.updatedAnonymized(id(16), T2));

        assertThat(lookup.memberId(ref)).isEmpty();
        assertThat(erased(id(16))).isTrue();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-012] lo stesso fatto due volte: stesso stato (consumer idempotente)")
    void sameFactTwiceIsIdempotent() {
        String ref = ref("idem");
        publish(MemberFactsSupport.registeredAs("EV-IDEM-" + idBase(), id(17), ref, T1, 2));
        long rows = jdbc.sql("SELECT count(*) FROM member_snapshot WHERE member_id = ?").param(id(17)).query(Long.class).single();
        // rigioco lo stesso evento (stesso id): il processed_event lo scarta
        publish(MemberFactsSupport.registeredAs("EV-IDEM-" + idBase(), id(17), ref, T1, 2));

        assertThat(lookup.memberId(ref)).contains(id(17));
        assertThat(jdbc.sql("SELECT count(*) FROM member_snapshot WHERE member_id = ?").param(id(17)).query(Long.class).single())
                .isEqualTo(rows);
    }

    @Test
    @DisplayName("[TB-CMP-MBP-013] snapshot e legame nella stessa transazione: un rollback li annulla insieme")
    void snapshotAndLinkShareTheTransaction() throws Exception {
        String memberId = id(18);
        String ref = ref("tx");
        JsonNode data = mapper.valueToTree(Map.of("memberId", memberId, "status", "ACTIVE", "subjectRef", ref));
        LhEvent<JsonNode> event = new LhEvent<>("1.0", "EV-TX-1", "urn:loyaltyhub:service:member",
                "io.loyaltyhub.fact.member.registered", "member:" + memberId, Instant.parse(T1), null, null, null, "COR-TX",
                null, 0, null, data);

        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            snapshotHandler.handle(event);
            // dentro la transazione i due effetti ci sono...
            assertThat(snapshotStatus(memberId)).isEqualTo("ACTIVE");
            assertThat(subjectRef(memberId)).isEqualTo(ref);
            status.setRollbackOnly();
        });

        // ...e dopo il rollback non resta né lo snapshot né il legame: partecipano entrambi alla transazione del chiamante
        assertThat(snapshotStatus(memberId)).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM member_snapshot WHERE member_id = ?").param(memberId).query(Long.class).single()).isZero();
        assertThat(lookup.memberId(ref)).isEmpty();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-015] un fatto senza time non vale «adesso»: non sorpassa un detentore datato e non sposta subject_ref_at")
    void factWithoutTimeIsConservative() {
        publish(MemberFactsSupport.registered(id(15), ref("notime"), T2, 2));
        // altro membro, stesso pseudonimo, senza time: non toglie il legame al detentore con tempo
        publish(MemberFactsSupport.registered(id(16), ref("notime"), null, 2));
        assertThat(lookup.memberId(ref("notime"))).contains(id(15));
        assertThat(subjectRef(id(16))).isNull();
        assertThat(refAt(id(16))).isNull();
        // senza time nemmeno un altro pseudonimo sposta subject_ref_at del membro già legato
        publish(MemberFactsSupport.updated(id(15), ref("notime-2"), null, 2));
        assertThat(lookup.memberId(ref("notime-2"))).contains(id(15));
        assertThat(refAt(id(15))).isEqualTo(Instant.parse(T2));
        // e un fatto datato prima di quel tempo resta obsoleto
        publish(MemberFactsSupport.updated(id(15), ref("notime-3"), T1, 2));
        assertThat(lookup.memberId(ref("notime-3"))).isEmpty();
    }

    @Test
    @DisplayName("[TB-CMP-MBP-014] la lookup non conosce uno pseudonimo non legato")
    void unknownRefIsNotLinked() {
        assertThat(lookup.memberId(ref("mai-visto"))).isEmpty();
        assertThat(lookup.authoritative()).isFalse();
        assertThat(lookup.modulePackage()).isEqualTo("io.loyaltyhub.campaign");
    }

    // ---------- supporto ----------

    private void publish(RecordMetadata... records) {
        ListenerGroups.awaitCommitted(listeners, List.of(records));
    }

    /** Base numerica degli id dei membri di questa classe concreta: due classi sullo stesso contesto non si pestano i piedi. */
    abstract int idBase();

    private String id(int n) {
        return String.format("MBR-%06d", idBase() + n);
    }

    /** Lo pseudonimo di un soggetto fittizio, distinto per classe concreta. */
    private String ref(String sub) {
        return SubjectRef.of(KEY, ISS, sub + "@" + idBase());
    }

    private String subjectRef(String memberId) {
        return jdbc.sql("SELECT subject_ref FROM member_snapshot WHERE member_id = ?").param(memberId)
                .query(String.class).optional().orElse(null);
    }

    private Instant refAt(String memberId) {
        return jdbc.sql("SELECT subject_ref_at FROM member_snapshot WHERE member_id = ?").param(memberId)
                .query((rs, n) -> java.util.Optional.ofNullable(rs.getTimestamp(1)).map(java.sql.Timestamp::toInstant))
                .single().orElse(null);
    }

    private boolean erased(String memberId) {
        return jdbc.sql("SELECT subject_erased FROM member_snapshot WHERE member_id = ?").param(memberId)
                .query(Boolean.class).single();
    }

    private String snapshotStatus(String memberId) {
        return jdbc.sql("SELECT status FROM member_snapshot WHERE member_id = ?").param(memberId)
                .query(String.class).optional().orElse(null);
    }

    private double relinked() {
        var counter = meters.find("lh_member_subject_relinked_total").counter();
        return counter == null ? 0 : counter.count();
    }

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
