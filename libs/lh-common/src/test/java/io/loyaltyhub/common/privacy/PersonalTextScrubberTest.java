package io.loyaltyhub.common.privacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pulizia dei valori personali di un membro anonimizzato senza corrompere i valori sicuri (F-MBR-05, M8.12a, revisioni
 * P9 e P18): parole intere soltanto; si saltano identificativi, istanti, envelope e i codici nei campi di codice, mentre
 * ogni testo libero (anche {@code reason} e {@code subject}) si ripulisce. Spostato da insight in lh-common con la
 * classe (Q-404): la stessa regola vale per engagement, ingestion e insight.
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

    @Test
    @DisplayName("Unicode: forma NFC, segni diacritici parte della parola, trattino basso come separatore")
    void unicodeAndUnderscore() {
        String nfd = Normalizer.normalize("José Quintilio ha scritto", Normalizer.Form.NFD);
        assertThat(PersonalTextScrubber.scrub(nfd, List.of("José"))).isEqualTo("Membro anonimo Quintilio ha scritto");
        assertThat(PersonalTextScrubber.scrub(nfd, List.of("Jose"))).as("«Jose» non è una parola di «José»")
                .isEqualTo(nfd);
        assertThat(PersonalTextScrubber.scrub("Ottavio_Q | Ottavio99 | l'Ottavio | «Ottavio»", List.of("Ottavio")))
                .isEqualTo("Membro anonimo_Q | Ottavio99 | l'Membro anonimo | «Membro anonimo»");
        assertThat(PersonalTextScrubber.scrub("nessun valore", List.of("Ottavio"))).isSameAs("nessun valore");
    }

    @Test
    @DisplayName("valori trovati: così come compaiono nella voce, per la prova di audit_redact")
    void foundAsTheyAppear() {
        JsonNode after = mapper.readTree("""
                {"status":"SUSPENDED","reason":"Reclamo di OTTAVIO Quintilio","lines":[{"note":"per ottavio"}]}""");
        assertThat(PersonalTextScrubber.found(after, List.of("Ottavio", "Anon")))
                .containsExactlyInAnyOrder("OTTAVIO", "ottavio");
        assertThat(PersonalTextScrubber.found("Anonimizzato ANONYMIZED", List.of("Anon"))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"id", "time", "source", "type", "specversion", "dataschema", "memberId", "entityId",
            "createdAt", "lhactor", "lhcorrelationid", "entityType", "service", "contentHash"})
    @DisplayName("identificativi, istanti ed envelope: mai riscritti")
    void fixedKeys(String key) {
        assertThat(PersonalTextScrubber.isFixed(key)).isTrue();
        assertThat(PersonalTextScrubber.isSafe(key, "Ottavio Quintilio")).isTrue();
    }

    @ParameterizedTest(name = "{0}={1} -> sicuro {2}")
    @CsvSource(delimiter = '|', value = {
            "status|ACTIVE|true", "newStatus|ANONYMIZED|true", "eventType|io.loyaltyhub.fact.member.updated|true",
            "rewardCode|RWD-ZED|true", "action|UPDATE|true", "currency|PTS|true",
            "status|Chiesto da Ottavio|false", "reason|FRAUD_SUSPECTED|true", "reason|Rimborso chiesto da Ottavio|false",
            "reason|Ottavio|false", "subject|email:ottavio@example.test|false", "subject|member:MBR-1|false",
            "note|MEMBER_REQUEST|true", "reason|TEST|true", "reason|GOODWILL|true", "note|3331234567|false", "reason|12345|false", "reason|Test del cassiere|false", "externalId|EXT-00042|false", "emailHash|abc123hash|false",
            "subjectRef|0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9|false"})
    @DisplayName("codici nei campi di codice e costanti: sicuri; testo libero, reason e subject: si ripuliscono")
    void safeValues(String key, String value, boolean safe) {
        assertThat(PersonalTextScrubber.isSafe(key, value)).isEqualTo(safe);
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
        assertThat(out.path("subject").asString()).isEqualTo("member:MBR-1");
    }

    @Test
    @DisplayName("soprannome «Test»: il motivo enumerato TEST resta, il testo libero con il soprannome si ripulisce")
    void nicknameEqualToAnEnumeratedReason() {
        JsonNode fact = mapper.readTree("""
                {"type":"io.loyaltyhub.fact.wallet.points.adjusted","subject":"member:MBR-1",
                 "data":{"memberId":"MBR-1","reason":"TEST","note":"Rettifica chiesta da Test"}}""");
        JsonNode out = PersonalTextScrubber.redactAndScrub(fact, List.of("Test"));
        assertThat(out.path("data").path("reason").asString()).isEqualTo("TEST");
        assertThat(out.path("data").path("note").asString()).isEqualTo("Rettifica chiesta da Membro anonimo");
    }

    @Test
    @DisplayName("valore di sole cifre uguale a un dato del membro (telefono, id esterno numerico): si ripulisce")
    void digitsOnlyValueIsNotAConstant() {
        JsonNode row = mapper.readTree("""
                {"contact":"3331234567","reason":"GOODWILL","ref":"TEST"}""");
        JsonNode out = PersonalTextScrubber.scrubAll(row, List.of("3331234567", "Test", "Goodwill"));
        assertThat(out.path("contact").asString()).isEqualTo("Membro anonimo");
        assertThat(out.path("reason").asString()).isEqualTo("GOODWILL");
        assertThat(out.path("ref").asString()).isEqualTo("TEST");
    }

    @Test
    @DisplayName("P18: reason e subject con il nome o l'e-mail del membro si ripuliscono")
    void freeTextReasonAndSubject() {
        JsonNode fact = mapper.readTree("""
                {"type":"io.loyaltyhub.fact.member.status.changed","subject":"email:ottavio.q@example.test",
                 "data":{"memberId":"MBR-1","newStatus":"ANONYMIZED",
                         "reason":"Diritto all'oblio chiesto da Ottavio Quintilio via ottavio.q@example.test"}}""");
        JsonNode out = PersonalTextScrubber.redactAndScrub(fact,
                List.of("ottavio.q@example.test", "Ottavio Quintilio", "Ottavio"));
        assertThat(out.path("data").path("reason").asString())
                .isEqualTo("Diritto all'oblio chiesto da Membro anonimo via Membro anonimo");
        assertThat(out.path("subject").asString()).isEqualTo("email:Membro anonimo");
        assertThat(out.path("data").path("newStatus").asString()).isEqualTo("ANONYMIZED");
    }

    @Test
    @DisplayName("righe di altre entità: valori ripuliti dentro oggetti e array, strutture intatte")
    void scrubAllKeepsStructure() {
        JsonNode row = mapper.readTree("""
                {"rewardCode":"RWD-ZED","status":"CONFIRMED","lines":[{"label":"Spedire a Zed Quintilio","sku":"ZED"}],
                 "emailHash":"abc123hash","subject":"external:EXT-00042"}""");
        JsonNode out = PersonalTextScrubber.scrubAll(row, List.of("Zed Quintilio", "abc123hash", "EXT-00042"));
        assertThat(out.path("rewardCode").asString()).isEqualTo("RWD-ZED");
        assertThat(out.path("status").asString()).isEqualTo("CONFIRMED");
        assertThat(out.path("lines").get(0).path("label").asString()).isEqualTo("Spedire a Membro anonimo");
        assertThat(out.path("emailHash").asString()).as("pseudonimo sostituito (Q-367)").isEqualTo("Membro anonimo");
        assertThat(out.path("subject").asString()).isEqualTo("external:Membro anonimo");
        assertThat(row.path("lines").get(0).path("label").asString()).as("originale intatto").contains("Zed");
    }

    @Test
    @DisplayName("[Q-552] subjectRef: come emailHash, fuori da KEYS ma mai sicuro; sostituito se noto; l'attributo subject dell'envelope non si tocca")
    void subjectRefHasTheEmailHashRegime() {
        String ref = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9";
        assertThat(PersonalData.KEYS).doesNotContain(PersonalData.SUBJECT_REF);
        assertThat(PersonalData.SUBJECT_REF).isEqualTo("subjectRef");
        // Non è in KEYS: redact non lo toglie (lo fa chi conserva i fatti, come per emailHash) e l'envelope resta intatto.
        JsonNode fact = mapper.readTree("""
                {"type":"io.loyaltyhub.fact.member.registered","subject":"member:MBR-000101",
                 "data":{"memberId":"MBR-000101","subjectRef":"%s","status":"ACTIVE","firstName":"Ada"}}""".formatted(ref));
        JsonNode redacted = PersonalData.redact(fact);
        assertThat(redacted.path("data").path("subjectRef").asString()).isEqualTo(ref);
        assertThat(redacted.path("data").has("firstName")).isFalse();
        assertThat(redacted.path("subject").asString()).isEqualTo("member:MBR-000101");
        // Se il servizio lo conosce come valore del membro, lo scrubber lo sostituisce (mai «sicuro»).
        JsonNode scrubbed = PersonalTextScrubber.redactAndScrub(fact, List.of(ref, "Ada"));
        assertThat(scrubbed.path("data").path("subjectRef").asString()).isEqualTo("Membro anonimo");
        assertThat(scrubbed.path("subject").asString()).isEqualTo("member:MBR-000101");
        assertThat(scrubbed.path("data").path("memberId").asString()).isEqualTo("MBR-000101");
    }
}
