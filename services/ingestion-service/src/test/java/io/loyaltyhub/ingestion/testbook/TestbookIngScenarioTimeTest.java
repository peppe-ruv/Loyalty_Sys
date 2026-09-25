package io.loyaltyhub.ingestion.testbook;

import io.loyaltyhub.ingestion.domain.ScenarioTime;
import io.loyaltyhub.ingestion.testbook.TestbookIngRows.Row;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testbook TB-ING, area SCT — istante di un passo di scenario (campo {@code at}) risolto su Europe/Rome secondo le
 * espressioni di docs/10 §1 ({@code @lastWeekday}, {@code @lastSaturday} = ultimo giorno <em>precedente a oggi</em>;
 * suffisso {@code T10:30}; {@code @now}, {@code @today±…}). Logica pura, orologio fisso per riga.
 */
class TestbookIngScenarioTimeTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    @TestFactory
    Stream<DynamicTest> sct() {
        return TestbookIngRows.of("sct.csv", this::sctRow, () -> {
        });
    }

    // TESTBOOK: ambiguo, vedi TB-ING-SCT-008, TB-ING-SCT-016, TB-ING-SCT-018 (atteso = comportamento attuale, marcato AMBIGUO nel CSV)
    void sctRow(Row a) {
        String at = "-".equals(a.getString(2)) ? null : a.getString(2);
        Instant now = LocalDateTime.parse(a.getString(3)).atZone(ROME).toInstant();
        String expected = a.getString(4);
        if (expected.equals("ERRORE")) {
            assertThatThrownBy(() -> ScenarioTime.resolve(at, now)).isInstanceOf(RuntimeException.class);
            return;
        }
        Instant want = expected.startsWith("@")
                ? Instant.parse(expected.substring(1))
                : LocalDateTime.parse(expected).atZone(ROME).toInstant();
        assertThat(ScenarioTime.resolve(at, now)).isEqualTo(want);
    }
}
