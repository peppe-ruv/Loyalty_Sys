package io.loyaltyhub.reward;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.reward.infra.MemberSnapshotRepository;
import io.loyaltyhub.reward.infra.RedemptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemberSnapshotIT extends TestbookRwdBase {

    @Autowired
    private MemberSnapshotRepository snapshotRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Test
    void doubleReadAndMerge() {
        String memberId = fresh("MBR-SNP");
        // member.registered:2 without firstName/lastName
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.registered", memberId, null,
                Map.of("memberId", memberId, "status", "ACTIVE")));

        var snap1 = snapshotRepository.find(memberId).orElseThrow();
        assertThat(snap1.status()).isEqualTo("ACTIVE");
        assertThat(snap1.firstName()).isNull();
        assertThat(snap1.lastName()).isNull();

        // Simulate member-service updating the local DB directly (since member.updated:1 with names is no longer written per feedback)
        snapshotRepository.seed(memberId, "ACTIVE", "GOLD", "Test", "User");

        var snap2 = snapshotRepository.find(memberId).orElseThrow();
        assertThat(snap2.firstName()).isEqualTo("Test");
        assertThat(snap2.lastName()).isEqualTo("User");

        // member.updated:2 without names again (should not overwrite existing names)
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.updated", memberId, null,
                Map.of("memberId", memberId, "status", "ACTIVE")));

        var snap3 = snapshotRepository.find(memberId).orElseThrow();
        assertThat(snap3.firstName()).isEqualTo("Test");
        assertThat(snap3.lastName()).isEqualTo("User");
    }

    @Test
    void anonymizeEmptiesNotes() {
        String memberId = member("ACTIVE", "GOLD");

        // Setup a redemption with manual note
        JsonNode rw = reward("LIVE", Map.of("band", "F1", "type", "PHYSICAL", "fulfilment", "MANUAL"));
        Resp req = requestRedemption(memberId, rw.path("code").asString(),
                Map.of("name", "Test User", "address", "Via Roma 1", "city", "Milano", "province", "MI", "zipCode", "20100", "country", "IT"));
        assertThat(req.status()).isEqualTo(202);
        String redemptionId = req.body().path("redemptionId").asString();
        String correlationId = req.body().path("correlationId").asString();

        awaitProcessed(publishFact("io.loyaltyhub.fact.wallet.points.spent", memberId, correlationId,
                Map.of("redemptionId", redemptionId, "amount", 500, "currency", "PTS", "balanceAfter", 1000)));
        awaitStatus(redemptionId, "CONFIRMED");

        // Fulfill manually with a note
        Resp fulfil = send("POST", "/v1/redemptions/" + redemptionId + "/fulfil", "CARE:testbook.care",
                Map.of("note", "Spedito a Test User con corriere", "tracking", "TRK-123"));
        assertThat(fulfil.status()).isEqualTo(200);

        // Verify note is saved
        var r1 = redemption(redemptionId);
        assertThat(r1.path("fulfilmentNote").asString()).contains("Test User");

        // Verify history has a note
        List<String> notesBefore = jdbc.sql("SELECT note FROM redemption_history WHERE redemption_id = ? AND note IS NOT NULL")
                .param(redemptionId).query(String.class).list();
        assertThat(notesBefore).isNotEmpty();

        // Anonymize the member
        awaitProcessed(publishFact("io.loyaltyhub.fact.member.status.changed", memberId, null,
                Map.of("previousStatus", "ACTIVE", "newStatus", "ANONYMIZED")));

        // Check snapshot
        var snap = snapshotRepository.find(memberId).orElseThrow();
        assertThat(snap.status()).isEqualTo("ANONYMIZED");

        // Check redemption info
        var r2 = redemption(redemptionId);
        assertThat(r2.path("status").asString()).isEqualTo("FULFILLED");
        assertThat(r2.path("shipping").isNull() || r2.path("shipping").isMissingNode()).isTrue();
        assertThat(r2.path("fulfilmentNote").isNull() || r2.path("fulfilmentNote").isMissingNode()).isTrue();

        // Check redemption history notes
        List<String> notesAfter = jdbc.sql("SELECT note FROM redemption_history WHERE redemption_id = ? AND note IS NOT NULL")
                .param(redemptionId).query(String.class).list();
        assertThat(notesAfter).isEmpty();
    }
}
