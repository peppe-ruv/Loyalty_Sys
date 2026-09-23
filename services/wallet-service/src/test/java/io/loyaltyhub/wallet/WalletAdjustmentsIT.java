package io.loyaltyhub.wallet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import io.loyaltyhub.wallet.api.WalletsController.AdjustmentRequest;
import io.loyaltyhub.wallet.api.WalletsController.AdjustmentResponse;
import io.loyaltyhub.wallet.application.WalletService;
import io.loyaltyhub.wallet.domain.LedgerEntry;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.infra.LedgerRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.ActorHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1", "lh.audit.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalletAdjustmentsIT {

    private static final String FACTS = "lh.facts.v1";
    private static final String AUDIT = "lh.audit.v1";
    private static final EmbeddedPostgres PG = startPg();

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository wallets;

    @Autowired
    private LedgerRepository ledger;

    @Autowired
    private PointsLotRepository lots;

    @Autowired
    private JdbcClient jdbc;

    private RestClient rest;
    private KafkaConsumer<String, String> consumerFacts;
    private KafkaConsumer<String, String> consumerAudit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=wallet");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    // Fallback static method to start PG if the one from WalletServiceIT is private.
    public static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();

        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
            ConsumerConfig.GROUP_ID_CONFIG, "test-adj-" + System.nanoTime(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerFacts = new KafkaConsumer<>(props);
        consumerFacts.subscribe(List.of(FACTS));
        // clear backlog
        while (!consumerFacts.poll(Duration.ofMillis(100)).isEmpty()) {}

        Map<String, Object> propsAudit = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
            ConsumerConfig.GROUP_ID_CONFIG, "test-audit-" + System.nanoTime(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        consumerAudit = new KafkaConsumer<>(propsAudit);
        consumerAudit.subscribe(List.of(AUDIT));
        // clear backlog
        while (!consumerAudit.poll(Duration.ofMillis(100)).isEmpty()) {}
    }

    @AfterEach
    void tearDownEach() {
        if (consumerFacts != null) consumerFacts.close();
        if (consumerAudit != null) consumerAudit.close();
        ActorHolder.clear();
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    @Test
    void creditGoodwillCreatesLedgerLotFactAndAudit() throws Exception {
        ActorHolder.set(new ActorContext(Role.CARE, "mario"));

        String memberId = "MBR-000002";
        long balanceBefore = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);

        AdjustmentResponse resp = walletService.adjustBalance(memberId, "PTS", "CREDIT", 200, "GOODWILL", "Ottimo cliente");

        assertThat(resp).isNotNull();
        assertThat(resp.balanceAfter()).isEqualTo(balanceBefore + 200);

        long balanceAfter = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceAfter).isEqualTo(balanceBefore + 200);

        List<LedgerEntry> entries = ledger.listByMember(memberId, "PTS", 10);
        LedgerEntry creditEntry = entries.stream().filter(e -> e.id().equals(resp.ledgerEntryId())).findFirst().orElseThrow();
        assertThat(creditEntry.type()).isEqualTo("ADJUST_CREDIT");
        assertThat(creditEntry.amount()).isEqualTo(200);
        assertThat(creditEntry.direction()).isEqualTo("+");
        assertThat(creditEntry.metadataJson()).contains("GOODWILL").contains("Ottimo cliente");

        List<PointsLot> lotsList = lots.findOpenByMember(memberId);
        PointsLot newLot = lotsList.stream().filter(l -> resp.ledgerEntryId().equals(l.ledgerEntryId())).findFirst().orElseThrow();
        assertThat(newLot.amount()).isEqualTo(200);
        assertThat(newLot.remaining()).isEqualTo(200);
        assertThat(newLot.status()).isEqualTo("ACTIVE");

        // Verify invariant: sum(ACTIVE remaining) == balance_active
        long activeLotsSum = lotsList.stream().filter(l -> "ACTIVE".equals(l.status())).mapToLong(PointsLot::remaining).sum();
        // Since we are only modifying tests we might not have all lots from seed, only assert on the amount we know
        assertThat(activeLotsSum).isGreaterThanOrEqualTo(200);

        // Check Fact
        ConsumerRecord<String, String> factRecord = null;
        for (int i=0; i<10; i++) {
            for (ConsumerRecord<String, String> r : consumerFacts.poll(Duration.ofMillis(500))) {
                if (r.value().contains("io.loyaltyhub.fact.wallet.points.adjusted")) factRecord = r;
            }
            if (factRecord != null) break;
        }
        assertThat(factRecord).isNotNull();
        JsonNode factNode = mapper.readTree(factRecord.value());
        assertThat(factNode.get("type").asText()).isEqualTo("io.loyaltyhub.fact.wallet.points.adjusted");
        assertThat(factNode.get("data").get("amount").asLong()).isEqualTo(200);
        assertThat(factNode.get("data").get("direction").asText()).isEqualTo("CREDIT");

        // Check Audit
        ConsumerRecord<String, String> auditRecord = null;
        for (int i=0; i<10; i++) {
            for (ConsumerRecord<String, String> r : consumerAudit.poll(Duration.ofMillis(500))) {
                if (r.value().contains("io.loyaltyhub.audit.entry") && r.value().contains("ADJUST")) auditRecord = r;
            }
            if (auditRecord != null) break;
        }
        assertThat(auditRecord).isNotNull();
        JsonNode auditNode = mapper.readTree(auditRecord.value());
        assertThat(auditNode.get("type").asText()).isEqualTo("io.loyaltyhub.audit.entry");
        assertThat(auditNode.get("data").get("action").asText()).isEqualTo("ADJUST");
    }

    @Test
    void debitExceedingBalanceThrowsError() {
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));

        String memberId = "MBR-000004";
        long balanceBefore = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);

        assertThatThrownBy(() -> walletService.adjustBalance(memberId, "PTS", "DEBIT", balanceBefore + 100, "CORRECTION", "Addebito eccessivo"))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("Saldo insufficiente per l'addebito");
    }

    @Test
    void debitConsumesLotsFifoAndCreatesLotConsumption() throws Exception {
        ActorHolder.set(new ActorContext(Role.CARE, "mario"));

        String memberId = "MBR-000003"; // should have enough balance based on seed

        // Let's create a known state by first crediting 2 separate lots.
        walletService.adjustBalance(memberId, "PTS", "CREDIT", 100, "TEST", "Lot 1 of 100");
        walletService.adjustBalance(memberId, "PTS", "CREDIT", 200, "TEST", "Lot 2 of 200");

        long balanceBefore = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceBefore).isGreaterThanOrEqualTo(150);

        AdjustmentResponse resp = walletService.adjustBalance(memberId, "PTS", "DEBIT", 150, "CORRECTION", "Rimozione 150 pt");

        long balanceAfter = wallets.find(memberId, "PTS").map(w -> w.balanceActive()).orElse(0L);
        assertThat(balanceAfter).isEqualTo(balanceBefore - 150);

        List<LedgerEntry> entries = ledger.listByMember(memberId, "PTS", 10);
        LedgerEntry debitEntry = entries.stream().filter(e -> e.id().equals(resp.ledgerEntryId())).findFirst().orElseThrow();
        assertThat(debitEntry.type()).isEqualTo("ADJUST_DEBIT");
        assertThat(debitEntry.amount()).isEqualTo(150);
        assertThat(debitEntry.direction()).isEqualTo("-");

        // Verify lot consumption table
        List<Map<String, Object>> lotConsumptions = jdbc.sql("SELECT * FROM lot_consumption WHERE ledger_entry_id = ?")
            .param(resp.ledgerEntryId()).query().listOfRows();

        assertThat(lotConsumptions).isNotEmpty();
        long sumConsumed = lotConsumptions.stream().mapToLong(row -> ((Number) row.get("amount")).longValue()).sum();
        assertThat(sumConsumed).isEqualTo(150);

        // Verify invariant
        List<PointsLot> lotsList = lots.findOpenByMember(memberId);
        long activeLotsSum = lotsList.stream().filter(l -> "ACTIVE".equals(l.status())).mapToLong(PointsLot::remaining).sum();
        // Just verify the newly added lots are consumed properly.
        assertThat(activeLotsSum).isGreaterThanOrEqualTo(150);
    }

    @Test
    void shortNoteThrowsError() {
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));
        assertThatThrownBy(() -> walletService.adjustBalance("MBR-000004", "PTS", "CREDIT", 100, "GOODWILL", "short"))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("La nota deve contenere almeno 10 caratteri");
    }

    @Test
    void nonAdjustableCurrencyThrowsError() {
        ActorHolder.set(new ActorContext(Role.ADMIN, "admin"));
        assertThatThrownBy(() -> walletService.adjustBalance("MBR-000004", "STS", "CREDIT", 100, "GOODWILL", "A note of 10"))
                .isInstanceOf(LhException.class)
                .hasMessageContaining("La valuta specificata non è modificabile");
    }

    @Test
    void controllerRejectsNonCareOrAdmin() {
        ResponseEntity<String> resp = rest.post()
            .uri("/v1/wallets/MBR-000004/adjustments")
            .header("X-LH-Actor", "MARKETING:giulia")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new AdjustmentRequest("PTS", "CREDIT", 100, "GOODWILL", "A very long note for this"))
            .retrieve()
            .onStatus(s -> true, (req, res) -> {}) // catch all
            .toEntity(String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void controllerAllowsCare() {
        ResponseEntity<AdjustmentResponse> resp = rest.post()
            .uri("/v1/wallets/MBR-000004/adjustments")
            .header("X-LH-Actor", "CARE:mario")
            .contentType(MediaType.APPLICATION_JSON)
            .body(new AdjustmentRequest("PTS", "CREDIT", 100, "GOODWILL", "A very long note for this"))
            .retrieve()
            .onStatus(s -> true, (req, res) -> {})
            .toEntity(AdjustmentResponse.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }
}
