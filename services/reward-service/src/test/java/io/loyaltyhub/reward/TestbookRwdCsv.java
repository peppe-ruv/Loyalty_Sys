package io.loyaltyhub.reward;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Righe del testbook TB-RWD come casi JUnit (docs/16 §1bis). Ogni riga CSV di {@code src/test/resources/testbook/rwd}
 * diventa un {@link DynamicTest} il cui nome inizia con {@code [<ID riga>]}; la sorgente del caso è la riga del CSV
 * (non il metodo Java), così il rapporto XML di surefire/failsafe riporta il nome del caso e
 * {@code scripts/testbook-report.mjs} lo abbina alla riga del documento. Prima colonna = id, seconda = descrizione.
 */
final class TestbookRwdCsv {

    private TestbookRwdCsv() {
    }

    /** Una riga del CSV, letta per nome di colonna. */
    record Row(String id, String desc, Map<String, String> cols, int line) {
        String get(String column) {
            String v = cols.get(column);
            if (v == null) {
                throw new IllegalArgumentException("colonna assente: " + column + " in " + id);
            }
            return v;
        }

        int integer(String column) {
            return Integer.parseInt(get(column));
        }

        boolean is(String column, String value) {
            return value.equals(get(column));
        }
    }

    /** Un caso per riga del CSV {@code /testbook/rwd/<file>}. */
    static Stream<DynamicTest> rows(String file, Consumer<Row> body) {
        String resource = "/testbook/rwd/" + file;
        return read(resource).stream().map(r -> DynamicTest.dynamicTest("[" + r.id() + "] " + r.desc(),
                URI.create("classpath:" + resource + "?line=" + r.line()), () -> body.accept(r)));
    }

    /** Un caso di scenario (riga del documento senza CSV). */
    static DynamicTest scenario(String id, String desc, Executable body) {
        return DynamicTest.dynamicTest("[" + id + "] " + desc, URI.create("classpath:/testbook/rwd/scenari?id=" + id), body);
    }

    static List<Row> read(String resource) {
        try (InputStream in = TestbookRwdCsv.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("risorsa assente: " + resource);
            }
            List<List<String>> records = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<String> header = records.get(0);
            List<Row> out = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                List<String> rec = records.get(i);
                if (rec.size() == 1 && rec.get(0).isEmpty()) {
                    continue;
                }
                Map<String, String> cols = new LinkedHashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    cols.put(header.get(c), c < rec.size() ? rec.get(c) : "");
                }
                out.add(new Row(rec.get(0), rec.get(1), cols, i + 1));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** CSV RFC 4180 (virgolette doppie, virgole e virgolette raddoppiate nei campi). */
    private static List<List<String>> parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
            } else if (ch != '\r') {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
