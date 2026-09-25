package io.loyaltyhub.insight.live;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-INS — logica pura del flusso live (docs/testbook/TB-INS-insight.md §6): filtro della sottoscrizione
 * ({@code SFL}: all-pairs L9 sui 4 filtri a 3 livelli + guasti singoli + casi speciali) e sintesi per tipo
 * ({@code SUM}, «tabella in codice» di insight §5, mai rotta da un payload inatteso). Oracolo: insight §3, §5.
 */
class TestbookInsLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String nullable(String v) {
        return "-".equals(v) ? null : "EMPTY".equals(v) ? "" : v;
    }

    private static Set<String> set(String v) {
        if ("-".equals(v)) {
            return null;
        }
        Set<String> s = new LinkedHashSet<>();
        if (!"EMPTY".equals(v)) {
            for (String x : v.split("\\|")) {
                s.add(x);
            }
        }
        return s;
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/ins/sse-filtro.csv", numLinesToSkip = 1)
    void filtro(String id, String desc, String topics, String types, String member, String corr, String evTopic,
                String evType, String evMember, String evCorr, boolean expected) {
        LiveEventHub.Filter f = new LiveEventHub.Filter(set(topics), set(types), nullable(member), nullable(corr));
        LiveEvent e = new LiveEvent("EVT-1", evTopic, "ACTION", evType, nullable(evMember), nullable(evCorr),
                Instant.parse("2026-09-15T10:00:00Z"), "s");
        assertThat(f.matches(e)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvSource(delimiter = ';', value = {
            "TB-INS-SUM-001;acquisto 130 €: sintesi con l'importo;purchase.completed;{\"amount\":130};130 €",
            "TB-INS-SUM-002;accredito 162 PTS (esempio di insight §5, AMBIGUO sulla forma): contiene +162 PTS;wallet.points.earned;{\"amount\":162,\"currency\":\"PTS\"};+162 PTS",
            "TB-INS-SUM-003;accredito STS: contiene la valuta STS;wallet.points.earned;{\"amount\":130,\"currency\":\"STS\"};+130 STS",
            "TB-INS-SUM-004;cambio di stato del membro: precedente → nuovo;member.status.changed;{\"previousStatus\":\"ACTIVE\",\"newStatus\":\"BLOCKED\"};ACTIVE → BLOCKED",
            "TB-INS-SUM-005;tier.upgraded: livello nuovo;tier.upgraded;{\"previousTier\":\"SILVER\",\"newTier\":\"GOLD\"};GOLD",
            "TB-INS-SUM-006;voce di audit: l'azione;entry;{\"action\":\"UPDATE\"};UPDATE",
            "TB-INS-SUM-007;tipo senza voce nella tabella: il tipo breve;coupon.used;{};coupon.used",
            "TB-INS-SUM-008;acquisto senza importo: nessun errore;purchase.completed;{};Acquisto",
            "TB-INS-SUM-009;dati nulli: nessun errore;wallet.points.earned;null;Punti",
            "TB-INS-SUM-010;tipo nullo: testo generico;-;{};evento"})
    void sintesi(String id, String desc, String type, String data, String expectedFragment) {
        JsonNode d = "null".equals(data) ? null : MAPPER.readTree(data);
        String s = EventSummaries.of("-".equals(type) ? null : type, d);
        assertThat(s).isNotBlank().contains(expectedFragment);
    }
}
