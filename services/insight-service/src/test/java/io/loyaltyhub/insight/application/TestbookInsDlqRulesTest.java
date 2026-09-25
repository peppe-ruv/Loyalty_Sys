package io.loyaltyhub.insight.application;

import io.loyaltyhub.insight.domain.DlqEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — regole pure della DLQ (docs/testbook/TB-INS-insight.md §3): riprocessabilità per famiglia × stato
 * ({@code DRP}, tabella completa 5 × 3), tipo breve della voce ({@code DSH}), CloudEvent del re-invio ({@code DRS}).
 * Oracolo: insight §5 («azione → re-invio con stesso id; effetti/fatti non riprocessabili»), docs/05 §2 (attributi
 * d'ingresso: specversion, id, source, type, subject, time, data), Q-104.
 */
class TestbookInsDlqRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static DlqEntry entry(String family, String type, String status) {
        return new DlqEntry("01DLQ", "EVT-1", "lh.actions.v1", type, family, "lh-campaign", "X", null, null, null,
                false, 1, null, null, MAPPER.createObjectNode(), Instant.EPOCH, status, null, null, null);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/dlq-riprocessabile.csv", numLinesToSkip = 1)
    void riprocessabile(String id, String desc, String family, String status, boolean expected) {
        // Q-N1 DECISA per AUDIT/UNKNOWN aperte (TB-INS-DRP-010, TB-INS-DRP-013)
        assertThat(entry(family, "io.loyaltyhub.action.x", status).reprocessable()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvSource(delimiter = ';', nullValues = "-", value = {
            "TB-INS-DSH-001;azione: tipo breve senza prefisso;ACTION;io.loyaltyhub.action.app.login.daily;app.login.daily",
            "TB-INS-DSH-002;audit: tipo breve entry;AUDIT;io.loyaltyhub.audit.entry;entry",
            "TB-INS-DSH-003;famiglia UNKNOWN con tipo estraneo: tipo intero;UNKNOWN;com.example.x;com.example.x",
            "TB-INS-DSH-004;senza tipo: tipo breve assente;UNKNOWN;-;-"})
    void tipoBreve(String id, String desc, String family, String type, String expected) {
        assertThat(entry(family, type, "OPEN").shortType()).isEqualTo(expected);
    }

    @Test
    @DisplayName("[TB-INS-DRS-001] envelope completo: re-inviati solo specversion, id, source, type, subject, time, data (stesso id)")
    void inboundFields() {
        ObjectNode p = MAPPER.createObjectNode().put("specversion", "1.0").put("id", "EVT-9")
                .put("source", "urn:loyaltyhub:source:app").put("type", "io.loyaltyhub.action.app.login.daily")
                .put("subject", "member:MBR-000002").put("time", "2026-09-15T10:00:00Z")
                .put("lhcorrelationid", "COR").put("lhhop", 0).put("lhactor", "system")
                .put("dataschema", "urn:x").put("lhtenant", "aurora");
        p.putObject("data").put("platform", "IOS");
        Map<String, Object> out = DlqService.inboundEvent(p);
        assertThat(out).containsOnlyKeys("specversion", "id", "source", "type", "subject", "time", "data");
        assertThat(out.get("id")).isEqualTo("EVT-9");
    }

    @Test
    @DisplayName("[TB-INS-DRS-002] attributi nulli o assenti: non inviati")
    void inboundNulls() {
        ObjectNode p = MAPPER.createObjectNode().put("id", "EVT-9").putNull("subject");
        assertThat(DlqService.inboundEvent(p)).containsOnlyKeys("id");
    }

    @Test
    @DisplayName("[TB-INS-DRS-003] payload assente: evento vuoto (nessun errore)")
    void inboundNullPayload() {
        assertThat(DlqService.inboundEvent(null)).isEmpty();
    }

    @Test
    @DisplayName("[TB-INS-DRS-004] payload grezzo non JSON ({raw}): nessun attributo d'ingresso")
    void inboundRaw() {
        assertThat(DlqService.inboundEvent(MAPPER.createObjectNode().put("raw", "boom"))).isEmpty();
    }
}
