package io.loyaltyhub.ingestion.testbook;

import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Righe dei CSV del testbook TB-ING ({@code src/test/resources/testbook/ing/}, separatore {@code |}, prima riga di
 * intestazione) come test dinamici chiamati {@code [<ID>] <descrizione>} con sorgente la riga del CSV. Così i rapporti
 * XML di surefire/failsafe portano il solo nome del caso (senza il nome del metodo davanti, come accadrebbe con
 * {@code @ParameterizedTest}) e {@code scripts/testbook-report.mjs} li abbina alle righe del testbook (docs/16 §1bis).
 */
final class TestbookIngRows {

    private TestbookIngRows() {
    }

    /** Una riga: celle già ripulite dagli spazi ai lati; cella vuota ⇒ {@code null}. */
    record Row(String id, int line, String[] cells) {
        String getString(int i) {
            String v = i < cells.length ? cells[i].trim() : "";
            return v.isEmpty() ? null : v;
        }

        Integer getInteger(int i) {
            return Integer.valueOf(getString(i));
        }
    }

    @FunctionalInterface
    interface RowTest {
        void run(Row row) throws Throwable;
    }

    /** Un test dinamico per riga; {@code after} gira sempre dopo ogni riga (es. ripristino dell'orologio). */
    static Stream<DynamicTest> of(String file, RowTest body, Runnable after) {
        String resource = "/testbook/ing/" + file;
        List<DynamicTest> tests = new ArrayList<>();
        List<String> lines = read(resource);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            Row row = new Row(cells[0].trim(), i + 1, cells);
            URI source = URI.create("classpath:" + resource + "?line=" + (i + 1));
            tests.add(DynamicTest.dynamicTest("[" + row.id() + "] " + cells[1].trim(), source, () -> {
                try {
                    body.run(row);
                } finally {
                    after.run();
                }
            }));
        }
        return tests.stream();
    }

    private static List<String> read(String resource) {
        try (InputStream in = TestbookIngRows.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("CSV del testbook mancante: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
