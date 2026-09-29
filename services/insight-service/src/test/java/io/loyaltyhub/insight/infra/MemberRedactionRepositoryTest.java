package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Riscrittura delle copie di un membro anonimizzato (F-MBR-05) con la doppia lettura {@code member.*:1}/{@code :2}
 * (ADR-032, Q-346): la {@code :1} perde le chiavi personali, la {@code :2} perde {@code emailHash} e non fallisce sui
 * campi personali assenti; entrambe perdono {@code subjectRef} (Q-552, ADR-048) senza toccare il {@code subject}
 * dell'envelope.
 */
class MemberRedactionRepositoryTest {

    private static final String HASH = "21cebef191120423981adfc0ba20d56791dd828ae5fe4bf3c5ab840bcc9deacb";
    private static final String REF = "9f2c4b7a1e0d3c5b8a6f4e2d1c0b9a8776655443322110ffeeddccbbaa998877";

    private final ObjectMapper mapper = new ObjectMapper();

    /** Stessa pipeline di una riga del membro in {@code rewriteJson(…, stripKeys = true)}. */
    private JsonNode memberRow(String data) {
        JsonNode row = mapper.readTree("""
                {"type":"io.loyaltyhub.fact.member.updated","subject":"member:MBR-000003","data":%s}""".formatted(data));
        return MemberRedactionRepository.stripPseudonyms(PersonalData.redactAndScrub(row, List.of()));
    }

    @Test
    void v1RowLosesPersonalKeys() {
        JsonNode d = memberRow("""
                {"memberId":"MBR-000003","firstName":"Marco","lastName":"Rossi","nickname":"marco.r",
                 "email":"marco@example.test","birthDate":"1988-04-21","city":"Torino","externalId":"CRM-3003",
                 "status":"ANONYMIZED","labels":["early-adopter"]}""").path("data");
        for (String k : new String[]{"firstName", "lastName", "nickname", "email", "birthDate", "city", "externalId"}) {
            assertThat(d.has(k)).as(k).isFalse();
        }
        assertThat(d.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(d.path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(d.path("labels").size()).isEqualTo(1);
    }

    @Test
    void v2RowLosesEmailHashAndKeepsNonPersonalFields() {
        JsonNode row = memberRow("""
                {"memberId":"MBR-000003","externalId":"CRM-3003","emailHash":"%s","status":"ANONYMIZED",
                 "locale":"it","birthYear":1988,"province":"MI","referredBy":null,
                 "nested":[{"emailHash":"%s"}]}""".formatted(HASH, HASH));
        JsonNode d = row.path("data");
        assertThat(d.has("emailHash")).isFalse();
        assertThat(d.path("nested").get(0).has("emailHash")).isFalse();
        assertThat(d.has("externalId")).isFalse();
        assertThat(d.path("locale").asString()).isEqualTo("it");
        assertThat(d.path("birthYear").asInt()).isEqualTo(1988);
        assertThat(d.path("province").asString()).isEqualTo("MI");
        assertThat(row.toString()).doesNotContain(HASH);
    }

    /**
     * {@code subjectRef} (pseudonimo del legame account↔membro, Q-552, ADR-048) si toglie dalle righe del membro come
     * {@code emailHash}, a ogni livello, in {@code :1} e {@code :2}; l'attributo {@code subject} dell'envelope
     * ({@code member:<id>}) e gli altri campi non personali restano.
     */
    @Test
    void rowLosesSubjectRefAtEveryLevelAndEnvelopeSubjectIsUntouched() {
        JsonNode v2 = memberRow("""
                {"memberId":"MBR-000003","externalId":"CRM-3003","emailHash":"%s","subjectRef":"%s","status":"ANONYMIZED",
                 "locale":"it","birthYear":1988,"province":"MI","referredBy":null,
                 "nested":[{"subjectRef":"%s","emailHash":"%s"}]}""".formatted(HASH, REF, REF, HASH));
        JsonNode d = v2.path("data");
        assertThat(d.has("subjectRef")).isFalse();
        assertThat(d.has("emailHash")).isFalse();
        assertThat(d.path("nested").get(0).has("subjectRef")).isFalse();
        assertThat(d.path("nested").get(0).has("emailHash")).isFalse();
        assertThat(v2.toString()).doesNotContain(REF).doesNotContain(HASH);
        assertThat(v2.path("subject").asString()).isEqualTo("member:MBR-000003");
        assertThat(d.path("memberId").asString()).isEqualTo("MBR-000003");
        assertThat(d.path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(d.path("locale").asString()).isEqualTo("it");
        assertThat(d.path("birthYear").asInt()).isEqualTo(1988);
        assertThat(d.path("province").asString()).isEqualTo("MI");

        // Copia :1 (documenta il campo, additionalProperties true): perde le chiavi personali e anche subjectRef.
        JsonNode v1 = memberRow("""
                {"memberId":"MBR-000003","firstName":"Marco","email":"marco@example.test","subjectRef":"%s",
                 "status":"ANONYMIZED"}""".formatted(REF));
        assertThat(v1.path("data").has("subjectRef")).isFalse();
        assertThat(v1.path("data").has("firstName")).isFalse();
        assertThat(v1.toString()).doesNotContain(REF);
        assertThat(v1.path("subject").asString()).isEqualTo("member:MBR-000003");

        // subjectRef null (legame rimosso) e assente: nessun errore, il resto della riga non cambia.
        JsonNode unlinked = memberRow("""
                {"memberId":"MBR-000003","subjectRef":null,"status":"ANONYMIZED"}""");
        assertThat(unlinked.path("data").has("subjectRef")).isFalse();
        assertThat(unlinked.path("data").path("status").asString()).isEqualTo("ANONYMIZED");
        assertThat(unlinked.path("subject").asString()).isEqualTo("member:MBR-000003");
    }

    @Test
    void v2RowWithNullsAndNoPersonalFieldsDoesNotFail() {
        assertThatCode(() -> memberRow("""
                {"memberId":"MBR-000003","status":"ANONYMIZED","birthYear":null,"province":null}"""))
                .doesNotThrowAnyException();
        assertThat(MemberRedactionRepository.stripPseudonyms(null)).isNull();
        assertThat(MemberRedactionRepository.stripPseudonyms(mapper.readTree("[1,\"x\",null]")).size()).isEqualTo(3);
    }
}
