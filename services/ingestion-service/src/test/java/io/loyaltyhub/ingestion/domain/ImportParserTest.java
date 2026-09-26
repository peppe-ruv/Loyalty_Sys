package io.loyaltyhub.ingestion.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lettura degli import file (F2-ING-02): formati, controlli sul contenuto, righe non leggibili. */
class ImportParserTest {

    private static final String ECOM = "urn:loyaltyhub:source:ecommerce";
    private static final ImportParser.FieldTypes PURCHASE = type -> type.endsWith("purchase.completed")
            ? Map.of("amount", "number", "currency", "string", "orderId", "string", "items", "array",
            "quantity", "integer", "gift", "boolean")
            : Map.of();

    private final ImportParser parser = new ImportParser(JsonMapper.builder().build());

    private List<ImportRecord> csv(String text, String defaultSource) {
        return parse(ImportFormat.CSV, text, defaultSource, PURCHASE);
    }

    /** Tutti i record consegnati dal parser (i test non fermano la lettura, salvo {@link #sinkStopsTheReading}). */
    private List<ImportRecord> parse(ImportFormat format, String text, String defaultSource, ImportParser.FieldTypes types) {
        List<ImportRecord> out = new ArrayList<>();
        int delivered = parser.read(format, text, defaultSource, types, out::add);
        assertThat(delivered).isEqualTo(out.size());
        return out;
    }

    @Test
    void sinkStopsTheReading() {
        List<ImportRecord> seen = new ArrayList<>();
        String csv = "id,type,subject,time\na,x,s,t\nb,x,s,t\nc,x,s,t\n";
        int delivered = parser.read(ImportFormat.CSV, csv, ECOM, ImportParser.FieldTypes.NONE, r -> {
            seen.add(r);
            return seen.size() < 2;
        });
        assertThat(delivered).isEqualTo(2);
        assertThat(seen).extracting(ImportRecord::id).containsExactly("a", "b");
        String ndjson = "{\"id\":\"a\"}\n{\"id\":\"b\"}\n";
        assertThat(parser.read(ImportFormat.NDJSON, ndjson, ECOM, ImportParser.FieldTypes.NONE, r -> false)).isEqualTo(1);
        assertThat(parser.read(ImportFormat.JSON, "[{\"id\":\"a\"},{\"id\":\"b\"}]", ECOM, ImportParser.FieldTypes.NONE,
                r -> false)).isEqualTo(1);
    }

    @Test
    void jsonArrayWithTrailingContentIsInvalid() {
        assertThatThrownBy(() -> parse(ImportFormat.JSON, "[{\"id\":\"a\"}] [", ECOM, ImportParser.FieldTypes.NONE))
                .isInstanceOfSatisfying(ImportFileException.class, e -> assertThat(e.code()).isEqualTo("IMPORT_INVALID"));
    }

    @Nested
    class Csv {

        @Test
        void headerColumnsAndDataFieldsAreTypedFromTheSchema() {
            List<ImportRecord> rows = csv("""
                    id,source,type,subject,time,data.orderId,data.amount,data.currency,data.quantity,data.gift
                    e-1,urn:loyaltyhub:source:ecommerce,purchase.completed,member:MBR-000002,2026-09-25T10:00:00Z,ORD-1,130.5,EUR,2,true
                    """, null);
            assertThat(rows).hasSize(1);
            ImportRecord r = rows.getFirst();
            assertThat(r.readable()).isTrue();
            assertThat(r.row()).isEqualTo(1);
            assertThat(r.specversion()).isEqualTo("1.0");
            assertThat(r.id()).isEqualTo("e-1");
            assertThat(r.source()).isEqualTo(ECOM);
            assertThat(r.subject()).isEqualTo("member:MBR-000002");
            assertThat(r.data().path("orderId").asString()).isEqualTo("ORD-1");
            assertThat(r.data().path("amount").isNumber()).isTrue();
            assertThat(r.data().path("amount").decimalValue()).isEqualByComparingTo("130.5");
            assertThat(r.data().path("quantity").isIntegralNumber()).isTrue();
            assertThat(r.data().path("gift").asBoolean()).isTrue();
        }

        @Test
        void semicolonSeparatorDecimalCommaQuotesAndDefaultSource() {
            List<ImportRecord> rows = csv("\uFEFFid;type;subject;time;data.amount;data.orderId\r\n"
                    + "e-2;purchase.completed;external:CRM-103;2026-09-25T10:00:00Z;24,90;\"ORD;\"\"2\"\"\"\r\n"
                    + "\r\n"
                    + "e-3;purchase.completed;email:a@example.org;2026-09-25T10:00:00Z;10;\"multi\nriga\"\r\n", ECOM);
            assertThat(rows).extracting(ImportRecord::id).containsExactly("e-2", "e-3");
            assertThat(rows).extracting(ImportRecord::source).containsOnly(ECOM);
            assertThat(rows.getFirst().data().path("amount").decimalValue()).isEqualByComparingTo("24.90");
            assertThat(rows.getFirst().data().path("orderId").asString()).isEqualTo("ORD;\"2\"");
            assertThat(rows.get(1).data().path("orderId").asString()).isEqualTo("multi\nriga");
            assertThat(rows.get(1).row()).as("le righe vuote non contano").isEqualTo(2);
        }

        @Test
        void dataColumnIsMergedWithDataFieldsAndEmptyCellsAreOmitted() {
            List<ImportRecord> rows = csv("""
                    id,type,subject,time,data,data.amount
                    e-4,purchase.completed,member:MBR-1,2026-09-25T10:00:00Z,"{""currency"":""EUR"",""amount"":1}",99
                    e-5,app.login.daily,member:MBR-1,2026-09-25T10:00:00Z,,
                    """, ECOM);
            assertThat(rows.getFirst().data().path("currency").asString()).isEqualTo("EUR");
            assertThat(rows.getFirst().data().path("amount").decimalValue()).isEqualByComparingTo("99");
            assertThat(rows.get(1).data().isObject()).isTrue();
            assertThat(rows.get(1).data().size()).as("nessun dato: oggetto vuoto").isZero();
        }

        @Test
        void unreadableRowsBecomeInvalidWithoutEchoingTheValue() {
            List<ImportRecord> rows = csv("""
                    id,type,subject,time,data.amount,data.quantity
                    e-6,purchase.completed,member:MBR-1,2026-09-25T10:00:00Z,tanti,1
                    e-7,purchase.completed,member:MBR-1,2026-09-25T10:00:00Z,1,1.5
                    e-8,purchase.completed,member:MBR-1
                    e-9,purchase.completed,member:MBR-1,2026-09-25T10:00:00Z,1,1
                    """, ECOM);
            assertThat(rows).extracting(ImportRecord::readable).containsExactly(false, false, false, true);
            assertThat(rows.getFirst().error()).isEqualTo("data.amount: atteso un numero").doesNotContain("tanti");
            assertThat(rows.get(1).error()).isEqualTo("data.quantity: atteso un numero intero");
            assertThat(rows.get(2).error()).isEqualTo("attese 6 colonne, trovate 3");
            assertThat(rows.get(2).id()).as("l'id resta per il rapporto").isEqualTo("e-8");
        }

        @Test
        void invalidDataJsonColumnIsAnInvalidRow() {
            List<ImportRecord> rows = csv("""
                    id,type,subject,time,data
                    e-10,app.login.daily,member:MBR-1,2026-09-25T10:00:00Z,{rotto
                    e-11,app.login.daily,member:MBR-1,2026-09-25T10:00:00Z,[1]
                    """, ECOM);
            assertThat(rows).extracting(ImportRecord::error)
                    .containsExactly("colonna data: JSON non valido", "colonna data: deve essere un oggetto JSON");
        }

        @Test
        void headerErrorsRejectTheWholeFile() {
            assertThatThrownBy(() -> csv("id,type,subject\n", ECOM))
                    .isInstanceOf(ImportFileException.class).hasMessageContaining("mancano le colonne time");
            assertThatThrownBy(() -> csv("id,type,subject,time\n", null))
                    .hasMessageContaining("source (o la fonte predefinita");
            assertThatThrownBy(() -> csv("id,type,subject,time,colore\n", ECOM))
                    .hasMessageContaining("colonna sconosciuta «colore»");
            assertThatThrownBy(() -> csv("id,ID,type,subject,time\n", ECOM)).hasMessageContaining("colonna ripetuta «id»");
            assertThatThrownBy(() -> csv("id,type,subject,time,data.a.b\n", ECOM))
                    .hasMessageContaining("solo campi di primo livello");
            assertThatThrownBy(() -> csv("id,type,subject,time\n\"e-1,x,y,z\n", ECOM))
                    .isInstanceOf(ImportFileException.class).hasMessageContaining("virgolette");
            assertThatThrownBy(() -> csv("\n\n", ECOM))
                    .isInstanceOfSatisfying(ImportFileException.class, e -> assertThat(e.code()).isEqualTo("IMPORT_EMPTY"));
        }

        @Test
        void jsonContentInACsvFileIsAFormatMismatch() {
            assertThatThrownBy(() -> csv("{\"id\":\"x\"}\n", ECOM))
                    .isInstanceOfSatisfying(ImportFileException.class,
                            e -> assertThat(e.code()).isEqualTo("IMPORT_FORMAT_MISMATCH"));
        }
    }

    @Nested
    class Json {

        @Test
        void ndjsonOneEventPerLineWithDefaults() {
            List<ImportRecord> rows = parse(ImportFormat.NDJSON, """
                    {"id":"n-1","type":"app.login.daily","subject":"member:MBR-000002","time":"2026-09-25T10:00:00Z","data":{"platform":"IOS"}}

                    {non json
                    ["non","oggetto"]
                    {"specversion":"1.0","id":7,"source":"urn:loyaltyhub:source:app","type":"app.login.daily","subject":"member:MBR-000002","time":"2026-09-25T10:00:00Z","data":{}}
                    """, ECOM, ImportParser.FieldTypes.NONE);
            assertThat(rows).extracting(ImportRecord::row).containsExactly(1, 2, 3, 4);
            assertThat(rows.getFirst().source()).isEqualTo(ECOM);
            assertThat(rows.getFirst().specversion()).isEqualTo("1.0");
            assertThat(rows.getFirst().data().path("platform").asString()).isEqualTo("IOS");
            assertThat(rows.get(1).error()).isEqualTo("JSON non valido");
            assertThat(rows.get(2).error()).contains("non è un oggetto");
            assertThat(rows.get(3).id()).isEqualTo("7");
            assertThat(rows.get(3).source()).isEqualTo("urn:loyaltyhub:source:app");
        }

        @Test
        void jsonArrayIsTheBatchShape() {
            List<ImportRecord> rows = parse(ImportFormat.JSON, """
                    [{"id":"j-1","type":"app.login.daily","subject":"member:MBR-1","time":"2026-09-25T10:00:00Z","data":{}}, 3]
                    """, null, ImportParser.FieldTypes.NONE);
            assertThat(rows).hasSize(2);
            assertThat(rows.getFirst().readable()).isTrue();
            assertThat(rows.getFirst().source()).as("nessuna fonte predefinita").isNull();
            assertThat(rows.get(1).readable()).isFalse();
        }

        @Test
        void contentMustMatchTheFormat() {
            assertThatThrownBy(() -> parse(ImportFormat.JSON, "{\"id\":1}", null, ImportParser.FieldTypes.NONE))
                    .isInstanceOfSatisfying(ImportFileException.class,
                            e -> assertThat(e.code()).isEqualTo("IMPORT_FORMAT_MISMATCH"));
            assertThatThrownBy(() -> parse(ImportFormat.NDJSON, "id,type\n", null, ImportParser.FieldTypes.NONE))
                    .isInstanceOfSatisfying(ImportFileException.class,
                            e -> assertThat(e.code()).isEqualTo("IMPORT_FORMAT_MISMATCH"));
            assertThatThrownBy(() -> parse(ImportFormat.JSON, "[{\"id\":1}", null, ImportParser.FieldTypes.NONE))
                    .isInstanceOfSatisfying(ImportFileException.class,
                            e -> assertThat(e.code()).isEqualTo("IMPORT_INVALID"));
        }
    }

    @Nested
    class FileChecks {

        @Test
        void decodeStripsBomAndRejectsBinaryOrNonUtf8() {
            assertThat(ImportParser.decode("\uFEFFid".getBytes(StandardCharsets.UTF_8))).isEqualTo("id");
            assertThatThrownBy(() -> ImportParser.decode(new byte[]{'i', 'd', (byte) 0xC3, (byte) 0x28}))
                    .isInstanceOfSatisfying(ImportFileException.class, e -> assertThat(e.code()).isEqualTo("IMPORT_NOT_TEXT"));
            assertThatThrownBy(() -> ImportParser.decode(new byte[]{'P', 'K', 0, 3}))
                    .isInstanceOfSatisfying(ImportFileException.class, e -> assertThat(e.code()).isEqualTo("IMPORT_NOT_TEXT"));
        }

        @Test
        void fileNameIsSanitizedForDisplay() {
            assertThat(ImportParser.sanitizeFileName("C:\\dati\\../ordini<script>.csv")).isEqualTo("ordiniscript.csv");
            assertThat(ImportParser.sanitizeFileName("../../etc/passwd")).isEqualTo("passwd");
            assertThat(ImportParser.sanitizeFileName("a\u0000b\nc.csv")).isEqualTo("abc.csv");
            assertThat(ImportParser.sanitizeFileName(null)).isEqualTo("import");
            assertThat(ImportParser.sanitizeFileName("x".repeat(200) + ".csv")).hasSize(120).endsWith(".csv");
        }

        @Test
        void formatFromExtensionAndDeclaredContentType() {
            assertThat(ImportFormat.fromFileName("ordini.CSV")).contains(ImportFormat.CSV);
            assertThat(ImportFormat.fromFileName("eventi.jsonl")).contains(ImportFormat.NDJSON);
            assertThat(ImportFormat.fromFileName("eventi.ndjson")).contains(ImportFormat.NDJSON);
            assertThat(ImportFormat.fromFileName("eventi.json")).contains(ImportFormat.JSON);
            assertThat(ImportFormat.fromFileName("ordini.xlsx")).isEmpty();
            assertThat(ImportFormat.fromFileName("ordini.csv.exe")).isEmpty();
            assertThat(ImportFormat.fromFileName("senza-estensione")).isEmpty();
            assertThat(ImportFormat.CSV.acceptsContentType("text/csv; charset=utf-8")).isTrue();
            assertThat(ImportFormat.CSV.acceptsContentType("application/vnd.ms-excel")).isTrue();
            assertThat(ImportFormat.CSV.acceptsContentType(null)).isTrue();
            assertThat(ImportFormat.CSV.acceptsContentType("application/octet-stream")).isTrue();
            assertThat(ImportFormat.CSV.acceptsContentType("application/pdf")).isFalse();
            assertThat(ImportFormat.JSON.acceptsContentType("image/png")).isFalse();
        }
    }

    @Nested
    class Report {

        @Test
        void formulaCellsAreNeutralizedAndQuoted() {
            assertThat(ReportCsv.cell("=HYPERLINK(\"http://x\")")).isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
            assertThat(ReportCsv.cell("+1")).isEqualTo("'+1");
            assertThat(ReportCsv.cell("-5")).isEqualTo("'-5");
            assertThat(ReportCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
            assertThat(ReportCsv.cell("\tx")).isEqualTo("'\tx");
            assertThat(ReportCsv.cell("a,b")).isEqualTo("\"a,b\"");
            assertThat(ReportCsv.cell("a;b")).isEqualTo("\"a;b\"");
            assertThat(ReportCsv.cell("normale")).isEqualTo("normale");
            assertThat(ReportCsv.cell(null)).isEmpty();
            assertThat(ReportCsv.line(java.util.Arrays.asList("1", null, "=x"))).isEqualTo("1,,'=x\n");
        }

        @Test
        void outcomeCountsAddUp() {
            OutcomeCounts c = OutcomeCounts.of(List.of(ItemOutcome.ACCEPTED, ItemOutcome.ACCEPTED, ItemOutcome.DUPLICATE,
                    ItemOutcome.REJECTED, ItemOutcome.UNMATCHED, ItemOutcome.INVALID));
            assertThat(c).isEqualTo(new OutcomeCounts(2, 1, 1, 1, 1));
            assertThat(c.total()).isEqualTo(6);
            assertThat(ItemOutcome.of(InboundStatus.UNMATCHED)).isEqualTo(ItemOutcome.UNMATCHED);
        }
    }
}
