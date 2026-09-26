package io.loyaltyhub.member.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Regole dei soprannomi a lotti per il BFF (Q-368, ADR-032). */
class NicknamesTest {

    @Test
    void limitIsOnReceivedEntries() {
        assertThat(Nicknames.tooMany(null)).isFalse();
        assertThat(Nicknames.tooMany(repeat(200))).isFalse();
        assertThat(Nicknames.tooMany(repeat(201))).as("doppioni compresi: scelta prudente").isTrue();
    }

    @Test
    void distinctIdsDropBlanksAndDuplicatesKeepingOrder() {
        assertThat(Nicknames.distinctIds(null)).isEmpty();
        assertThat(Nicknames.distinctIds(List.of())).isEmpty();
        assertThat(Nicknames.distinctIds(Arrays.asList("MBR-000002", null, " ", "MBR-000001", " MBR-000002 ")))
                .containsExactly("MBR-000002", "MBR-000001");
    }

    @Test
    void shownNameUsesPlaceholderForAnonymized() {
        assertThat(Nicknames.shown("anna_r", MemberStatus.ACTIVE)).isEqualTo("anna_r");
        assertThat(Nicknames.shown("rob_c", MemberStatus.BLOCKED)).isEqualTo("rob_c");
        assertThat(Nicknames.shown("vecchio", MemberStatus.ANONYMIZED)).isEqualTo(Anonymization.PLACEHOLDER);
        assertThat(Nicknames.shown(null, MemberStatus.ANONYMIZED)).isEqualTo(Anonymization.PLACEHOLDER);
        assertThat(Nicknames.shown(null, MemberStatus.ACTIVE)).isNull();
        assertThat(Nicknames.shown("  ", MemberStatus.ACTIVE)).isNull();
    }

    private static List<String> repeat(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("MBR-000001");
        }
        return out;
    }
}
