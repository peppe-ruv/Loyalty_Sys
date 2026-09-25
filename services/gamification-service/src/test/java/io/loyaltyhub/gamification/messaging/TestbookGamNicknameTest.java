package io.loyaltyhub.gamification.messaging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — nickname mostrato in classifica ({@code NCK}) (docs/testbook/TB-GAM-gioco.md §19). Oracolo: docs/03 §8
 * «il nome mostrato è il nickname del membro (default: nome + iniziale del cognome)», F-LDB-01 (nickname per privacy).
 */
class TestbookGamNicknameTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // TESTBOOK: ambiguo, vedi TB-GAM-NCK-005 (nome assente)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/nickname.csv", numLinesToSkip = 1, quoteCharacter = '\'')
    void nickname(String id, String desc, String data, String expected) {
        assertThat(MemberSnapshotHandler.nickname(MAPPER.readTree(data))).isEqualTo("-".equals(expected) ? null : expected);
    }
}
