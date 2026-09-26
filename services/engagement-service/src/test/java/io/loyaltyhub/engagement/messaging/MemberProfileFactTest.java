package io.loyaltyhub.engagement.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Doppia lettura di {@code member.registered/updated} {@code :1} e {@code :2} (ADR-032, docs/18 §3.4, Q-346). */
class MemberProfileFactTest {

    private static final String V1 = "urn:loyaltyhub:schema:fact.member.updated:1";
    private static final String V2 = "urn:loyaltyhub:schema:fact.member.updated:2";
    private static final String HASH = "a".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionFromDataschema() {
        assertThat(MemberProfileFact.schemaVersion(V1)).isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion(V2)).isEqualTo(2);
        assertThat(MemberProfileFact.schemaVersion("urn:loyaltyhub:schema:fact.member.registered:2")).isEqualTo(2);
        assertThat(MemberProfileFact.schemaVersion(null)).as("produttori di Fase 1 senza dataschema").isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion("")).isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:")).isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:x")).isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:0")).isEqualTo(1);
        assertThat(MemberProfileFact.schemaVersion("senza-versione")).isEqualTo(1);
    }

    @Test
    void v1CarriesFirstName() {
        MemberProfileFact p = MemberProfileFact.parse(V1, json("""
                {"memberId":"MBR-000003","firstName":"Giulia","lastName":"Rossi","email":"giulia@example.org",
                 "birthDate":"1990-04-02","city":"Milano","status":"ACTIVE","registeredAt":"2025-01-10T09:00:00Z"}
                """));
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.firstName()).isEqualTo("Giulia");
        assertThat(p.status()).isEqualTo("ACTIVE");
        assertThat(p.registeredAt()).isEqualTo(Instant.parse("2025-01-10T09:00:00Z"));
    }

    @Test
    void v1WithoutDataschemaIsReadAsV1() {
        MemberProfileFact p = MemberProfileFact.parse(null, json("{\"firstName\":\"Marco\",\"status\":\"ACTIVE\"}"));
        assertThat(p.version()).isEqualTo(1);
        assertThat(p.firstName()).isEqualTo("Marco");
        assertThat(p.registeredAt()).isNull();
    }

    @Test
    void v2HasNoPersonalData() {
        MemberProfileFact p = MemberProfileFact.parse(V2, json("""
                {"memberId":"MBR-000003","externalId":"EXT-3","emailHash":"%s","status":"SUSPENDED","channel":"WEB",
                 "registeredAt":"2025-01-10T09:00:00Z","locale":"en","birthYear":1990,"province":"MI",
                 "referralCode":"AUR-3","referredBy":null,"labels":["vip"],"attributes":{"favouriteSport":"RUN"}}
                """.formatted(HASH)));
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.firstName()).as("assente in :2: non cancella il nome noto").isNull();
        assertThat(p.status()).isEqualTo("SUSPENDED");
        assertThat(p.registeredAt()).isEqualTo(Instant.parse("2025-01-10T09:00:00Z"));
    }

    @Test
    void v2NeverReadsFirstNameEvenIfPresent() {
        MemberProfileFact p = MemberProfileFact.parse(V2, json("{\"firstName\":\"Intrusa\",\"status\":\"ACTIVE\"}"));
        assertThat(p.firstName()).isNull();
    }

    @Test
    void v2WithNullableFieldsAtNull() {
        MemberProfileFact p = MemberProfileFact.parse(V2, json("""
                {"memberId":"MBR-000004","status":"ACTIVE","birthYear":null,"province":null,"referredBy":null}
                """));
        assertThat(p.version()).isEqualTo(2);
        assertThat(p.firstName()).isNull();
        assertThat(p.status()).isEqualTo("ACTIVE");
        assertThat(p.registeredAt()).isNull();
    }

    @Test
    void absentOrOddFieldsAreNullNeverThrow() {
        MemberProfileFact empty = MemberProfileFact.parse(V2, json("{}"));
        assertThat(empty.status()).isNull();
        assertThat(empty.firstName()).isNull();
        assertThat(empty.registeredAt()).isNull();

        MemberProfileFact odd = MemberProfileFact.parse(V1, json("""
                {"firstName":"  ","status":{"x":1},"registeredAt":"ieri"}
                """));
        assertThat(odd.firstName()).isNull();
        assertThat(odd.status()).isNull();
        assertThat(odd.registeredAt()).isNull();

        MemberProfileFact numeric = MemberProfileFact.parse(V1, json("{\"firstName\":42,\"registeredAt\":1700000000}"));
        assertThat(numeric.firstName()).isNull();
        assertThat(numeric.registeredAt()).isNull();

        assertThat(MemberProfileFact.parse(V2, null).status()).isNull();
        assertThat(MemberProfileFact.parse(V1, json("[1,2]")).firstName()).isNull();
    }

    private JsonNode json(String text) {
        return mapper.readTree(text);
    }
}
