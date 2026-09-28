package io.loyaltyhub.insight.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulizia dei valori personali di un membro anonimizzato senza corrompere i valori strutturali (F-MBR-05, M8.12a,
 * revisione P9): parole intere soltanto, mai stati, tipi, codici, identificativi e campi dell'envelope.
 */
class PersonalTextScrubberTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("solo parole intere: «Anon» e «Zed» non toccano ANONYMIZED, Zedda, anonimo")
    void wholeWordsOnly() {
        assertThat(PersonalTextScrubber.scrub("Stato ANONYMIZED, prima anonimo", List.of("Anon")))
                .isEqualTo("Stato ANONYMIZED, prima anonimo");
        assertThat(PersonalTextScrubber.scrub("Zed Zedda, cliente di Zed.", List.of("Zed")))
                .isEqualTo("Membro anonimo Zedda, cliente di Membro anonimo.");
        assertThat(PersonalTextScrubber.scrub("Ciao ANON!", List.of("anon"))).isEqualTo("Ciao Membro anonimo!");
    }

    @Test
    @DisplayName("e-mail e nomi completi con punteggiatura: sostituiti come parole intere")
    void emailsAndFullNames() {
        List<String> tokens = List.of("ottavio.q@example.test", "Ottavio Quintilio");
        assertThat(PersonalTextScrubber.scrub("Scritto a ottavio.q@example.test da Ottavio Quintilio", tokens))
                .isEqualTo("Scritto a Membro anonimo da Membro anonimo");
        assertThat(PersonalTextScrubber.scrub("xottavio.q@example.test", tokens)).isEqualTo("xottavio.q@example.test");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"status", "newStatus", "previousStatus", "type", "eventType", "rewardCode", "code", "reason",
            "memberId", "entityId", "id", "createdAt", "time", "source", "subject", "lhactor", "lhcorrelationid", "action",
            "entityType", "currency"})
    @DisplayName("campi strutturali: mai riscritti")
    void structuralKeys(String key) {
        assertThat(PersonalTextScrubber.isStructural(key)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"summary", "note", "name", "firstName", "nickname", "email", "externalId", "emailHash",
            "message", "title"})
    @DisplayName("campi di testo e identificativi personali: ripuliti")
    void textKeys(String key) {
        assertThat(PersonalTextScrubber.isStructural(key)).isFalse();
    }

    @Test
    @DisplayName("soprannome uguale a uno stato («Active»): gli stati restano, il testo libero si ripulisce")
    void nicknameEqualToAStatus() {
        JsonNode fact = mapper.readTree("""
                {"type":"io.loyaltyhub.fact.member.status.changed","subject":"member:MBR-1","lhactor":"ADMIN:active",
                 "data":{"memberId":"MBR-1","previousStatus":"ACTIVE","newStatus":"ANONYMIZED","status":"ACTIVE",
                         "note":"Active ha chiesto la cancellazione","nickname":"Active"}}""");
        JsonNode out = PersonalTextScrubber.redactAndScrub(fact, List.of("Active", "Anon"));
        JsonNode d = out.path("data");
        assertThat(d.path("previousStatus").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("newStatus").asString()).isEqualTo("ANONYMIZED");
        assertThat(d.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(d.path("note").asString()).isEqualTo("Membro anonimo ha chiesto la cancellazione");
        assertThat(d.has("nickname")).as("chiave personale tolta").isFalse();
        assertThat(out.path("lhactor").asString()).isEqualTo("ADMIN:active");
        assertThat(out.path("type").asString()).isEqualTo("io.loyaltyhub.fact.member.status.changed");
    }

    @Test
    @DisplayName("righe di altre entità: valori ripuliti dentro oggetti e array, strutture intatte")
    void scrubAllKeepsStructure() {
        JsonNode row = mapper.readTree("""
                {"rewardCode":"RWD-ZED","status":"CONFIRMED","lines":[{"label":"Spedire a Zed Quintilio","sku":"ZED"}],
                 "emailHash":"abc123hash"}""");
        JsonNode out = PersonalTextScrubber.scrubAll(row, List.of("Zed Quintilio", "abc123hash", "ZED"));
        assertThat(out.path("rewardCode").asString()).isEqualTo("RWD-ZED");
        assertThat(out.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(out.path("lines").get(0).path("label").asString()).isEqualTo("Spedire a Membro anonimo");
        assertThat(out.path("emailHash").asString()).as("pseudonimo sostituito (Q-367)").isEqualTo("Membro anonimo");
        assertThat(row.path("lines").get(0).path("label").asString()).as("originale intatto").contains("Zed");
    }
}
