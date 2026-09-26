package io.loyaltyhub.gamification.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Doppia lettura di {@code member.registered/updated} {@code :1} e {@code :2} nello snapshot del gioco (ADR-032, Q-346, Q-368). */
class MemberSnapshotFactTest {

    private static final String V1 = "urn:loyaltyhub:schema:fact.member.updated:1";
    private static final String V2 = "urn:loyaltyhub:schema:fact.member.updated:2";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionFromDataschema() {
        assertThat(MemberSnapshotFact.schemaVersion(V1)).isEqualTo(1);
        assertThat(MemberSnapshotFact.schemaVersion(V2)).isEqualTo(2);
        assertThat(MemberSnapshotFact.schemaVersion("urn:loyaltyhub:schema:fact.member.registered:2")).isEqualTo(2);
        assertThat(MemberSnapshotFact.schemaVersion(null)).as("produttori di Fase 1 senza dataschema").isEqualTo(1);
        assertThat(MemberSnapshotFact.schemaVersion("")).isEqualTo(1);
        assertThat(MemberSnapshotFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:")).isEqualTo(1);
        assertThat(MemberSnapshotFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:x")).isEqualTo(1);
        assertThat(MemberSnapshotFact.schemaVersion("urn:loyaltyhub:schema:fact.member.updated:0")).isEqualTo(1);
    }

    @Test
    void v1CarriesTheNickname() {
        MemberSnapshotFact f = MemberSnapshotFact.parse(V1, json("""
                {"memberId":"MBR-000003","firstName":"Giulia","lastName":"Ferri","nickname":"giu_f","status":"ACTIVE"}
                """));
        assertThat(f.version()).isEqualTo(1);
        assertThat(f.nickname()).isEqualTo("giu_f");
        assertThat(f.status()).isEqualTo("ACTIVE");
    }

    @Test
    void v1WithoutNicknameUsesFirstNameAndInitial() {
        MemberSnapshotFact f = MemberSnapshotFact.parse(null, json("{\"firstName\":\"Marco\",\"lastName\":\"Bianchi\",\"status\":\"ACTIVE\"}"));
        assertThat(f.version()).isEqualTo(1);
        assertThat(f.nickname()).isEqualTo("Marco B.");
    }

    @Test
    void v2HasNoNicknameAndKeepsTheStatus() {
        MemberSnapshotFact f = MemberSnapshotFact.parse(V2, json("""
                {"memberId":"MBR-000003","externalId":"CRM-3003","emailHash":"%s","status":"SUSPENDED","channel":"APP",
                 "registeredAt":"2026-01-12T09:30:00Z","locale":"it","birthYear":1988,"province":"MI",
                 "referralCode":"GIU-4KD2","referredBy":null,"labels":["vip"],"attributes":{"segmentHint":"digital"}}
                """.formatted("a".repeat(64))));
        assertThat(f.version()).isEqualTo(2);
        assertThat(f.nickname()).as("assente in :2: non cancella il soprannome noto").isNull();
        assertThat(f.status()).isEqualTo("SUSPENDED");
    }

    @Test
    void v2NeverReadsTheNicknameEvenIfPresent() {
        MemberSnapshotFact f = MemberSnapshotFact.parse(V2, json("{\"nickname\":\"intruso\",\"firstName\":\"Intrusa\",\"status\":\"ACTIVE\"}"));
        assertThat(f.nickname()).isNull();
    }

    @Test
    void missingOrOddPayloadsDoNotThrow() {
        assertThat(MemberSnapshotFact.parse(V2, json("{\"memberId\":\"MBR-000004\"}")).status()).as("stato assente").isNull();
        assertThat(MemberSnapshotFact.parse(V2, json("{\"status\":null}")).status()).isNull();
        assertThat(MemberSnapshotFact.parse(V2, json("{\"status\":42}")).status()).isNull();
        assertThat(MemberSnapshotFact.parse(V1, json("[]")).nickname()).isNull();
        assertThat(MemberSnapshotFact.parse(V2, null).status()).isNull();
        assertThat(MemberSnapshotFact.parse(V1, null).nickname()).isNull();
    }

    private JsonNode json(String s) {
        return mapper.readTree(s);
    }
}
