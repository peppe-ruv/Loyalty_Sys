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
 * campi personali assenti.
 */
class MemberRedactionRepositoryTest {

    private static final String HASH = "21cebef191120423981adfc0ba20d56791dd828ae5fe4bf3c5ab840bcc9deacb";

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

    @Test
    void v2RowWithNullsAndNoPersonalFieldsDoesNotFail() {
        assertThatCode(() -> memberRow("""
                {"memberId":"MBR-000003","status":"ANONYMIZED","birthYear":null,"province":null}"""))
                .doesNotThrowAnyException();
        assertThat(MemberRedactionRepository.stripPseudonyms(null)).isNull();
        assertThat(MemberRedactionRepository.stripPseudonyms(mapper.readTree("[1,\"x\",null]")).size()).isEqualTo(3);
    }
}
