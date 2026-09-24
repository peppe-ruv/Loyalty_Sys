package io.loyaltyhub.member.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regola di anonimizzazione (F-MBR-05, docs/03 §2). */
class AnonymizationTest {

    private static final Instant REGISTERED = Instant.parse("2025-01-10T09:00:00Z");
    private static final Instant COMPLETED = Instant.parse("2025-02-01T09:00:00Z");

    private static Member giulia() {
        return new Member("MBR-000003", "CRM-103", "Giulia", "Ferri", "Giulia F.", "giulia.ferri@example.org",
                "+39 333 0000003", LocalDate.of(1990, 5, 4), "F", "Bologna", MemberStatus.ACTIVE, "APP", REGISTERED,
                "ABCD2345", "MBR-000002", COMPLETED, "{\"marketing\":true,\"profiling\":true}",
                "{\"householdSize\":3,\"story\":\"A 120 punti da GOLD\"}", List.of("vip"), "giulia", COMPLETED, 7);
    }

    @Test
    void replacesTheNameAndClearsEveryPersonalField() {
        Member a = Anonymization.apply(giulia());

        assertThat(a.status()).isEqualTo(MemberStatus.ANONYMIZED);
        assertThat(a.nickname()).isEqualTo("Membro anonimo");
        assertThat(a.displayName()).isEqualTo("Membro anonimo");
        assertThat(a.firstName()).isNull();
        assertThat(a.lastName()).isNull();
        assertThat(a.email()).isNull();
        assertThat(a.phone()).isNull();
        assertThat(a.birthDate()).isNull();
        assertThat(a.gender()).isNull();
        assertThat(a.city()).isNull();
        assertThat(a.externalId()).isNull();
        assertThat(a.avatarSeed()).isNull();
        assertThat(a.consentsJson()).isEqualTo("{}");
        assertThat(a.attributesJson()).as("attributi e storia della persona").isEqualTo("{}");
    }

    @Test
    void keepsIdStatisticsAndProgramLinks() {
        Member a = Anonymization.apply(giulia());

        assertThat(a.id()).isEqualTo("MBR-000003");
        assertThat(a.registeredAt()).isEqualTo(REGISTERED);
        assertThat(a.channel()).isEqualTo("APP");
        assertThat(a.referralCode()).isEqualTo("ABCD2345");
        assertThat(a.referredBy()).isEqualTo("MBR-000002");
        assertThat(a.referralCompletedAt()).isEqualTo(COMPLETED);
        assertThat(a.profileCompletedAt()).isEqualTo(COMPLETED);
        assertThat(a.labels()).containsExactly("vip");
        assertThat(a.version()).isEqualTo(7);
        assertThat(ProfileRules.missingFields(a)).as("il profilo non è più completo").isNotEmpty();
    }

    @Test
    void isIdempotent() {
        Member once = Anonymization.apply(giulia());
        assertThat(Anonymization.apply(once)).isEqualTo(once);
    }

    @Test
    void confirmationMustBeTheMemberId() {
        assertThat(Anonymization.confirms("MBR-000003", "MBR-000003")).isTrue();
        assertThat(Anonymization.confirms("MBR-000003", " MBR-000003 ")).isTrue();
        assertThat(Anonymization.confirms("MBR-000003", "MBR-000004")).isFalse();
        assertThat(Anonymization.confirms("MBR-000003", "mbr-000003")).isFalse();
        assertThat(Anonymization.confirms("MBR-000003", null)).isFalse();
        assertThat(Anonymization.confirms("MBR-000003", "")).isFalse();
    }
}
