package io.loyaltyhub.common.condition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tabella completa del cast tipizzato comune (Q-215, Q-216 decise): ogni tipo JSON del valore osservato × ogni forma del
 * valore della regola, con l'esito di {@code eq} e {@code neq} (le negazioni sono false quando il cast fallisce).
 * Colonne: valore osservato (JSON) | valore della regola (JSON) | eq | neq. {@code ~} = valore della regola assente.
 */
class TypedCastTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String s) {
        return "~".equals(s) ? null : JSON.readTree(s);
    }

    // ---------- tabella: tipo osservato × forma del valore della regola ----------

    @ParameterizedTest(name = "[{index}] {0} eq {1} → {2}, neq → {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            # --- numero JSON 5: il valore della regola diventa numero (BigDecimal) solo se è numero o ^-?\\d+(\\.\\d+)?$
            5                             | 5                               | true  | false
            5                             | 5.0                             | true  | false
            5                             | 6                               | false | true
            5                             | "5"                             | true  | false
            5                             | " 5"                            | false | false
            5                             | "5 "                            | false | false
            5                             | "5.0"                           | true  | false
            5                             | "5e1"                           | false | false
            5                             | "+5"                            | false | false
            5                             | "05"                            | true  | false
            5                             | "-0"                            | false | true
            5                             | "5,0"                           | false | false
            5                             | ""                              | false | false
            5                             | "TRUE"                          | false | false
            5                             | "true"                          | false | false
            5                             | true                            | false | false
            5                             | "2026-02-29"                    | false | false
            5                             | "2026-03-29T02:30:00+01:00"     | false | false
            5                             | null                            | false | false
            5                             | ~                               | false | false
            5                             | [5]                             | false | false
            5                             | {"v":5}                         | false | false
            0                             | "-0"                            | true  | false
            -0.5                          | "-0.5"                          | true  | false
            5.0                           | 5                               | true  | false
            0.3                           | "0.30"                          | true  | false
            10000000000000001             | 10000000000000000               | false | true
            10000000000000001             | "10000000000000001"             | true  | false
            # --- testo numerico "5": resta testo (mai un numero estratto dal dato), la regola deve essere testo
            "5"                           | 5                               | false | false
            "5"                           | 5.0                             | false | false
            "5"                           | "5"                             | true  | false
            "5"                           | " 5"                            | false | true
            "5"                           | "5.0"                           | false | true
            "5"                           | "05"                            | false | true
            "5"                           | ""                              | false | true
            "5"                           | true                            | false | false
            "5"                           | null                            | false | false
            # --- testo
            "APP"                         | "APP"                           | true  | false
            "APP"                         | "app"                           | false | true
            "APP"                         | 5                               | false | false
            "APP"                         | false                           | false | false
            "APP"                         | ""                              | false | true
            "TRUE"                        | "TRUE"                          | true  | false
            "TRUE"                        | true                            | false | false
            ""                            | ""                              | true  | false
            ""                            | 0                               | false | false
            # --- booleano: la regola diventa booleano solo se booleano o esattamente "true"/"false"
            true                          | true                            | true  | false
            true                          | false                           | false | true
            true                          | "true"                          | true  | false
            true                          | "false"                         | false | true
            true                          | "TRUE"                          | false | false
            true                          | "True"                          | false | false
            true                          | " true"                         | false | false
            true                          | 1                               | false | false
            true                          | "1"                             | false | false
            true                          | ""                              | false | false
            false                         | "false"                         | true  | false
            # --- data AAAA-MM-GG: la regola deve essere una data valida, stessa granularità
            "2026-03-29"                  | "2026-03-29"                    | true  | false
            "2026-03-29"                  | "2026-03-30"                    | false | true
            "2026-03-29"                  | "2026-03-29T02:30:00+01:00"     | false | false
            "2026-03-29"                  | "2026-02-29"                    | false | false
            "2026-03-29"                  | "2026-3-29"                     | false | false
            "2026-03-29"                  | "29/03/2026"                    | false | false
            "2026-03-29"                  | 20260329                        | false | false
            "2026-03-29"                  | ""                              | false | false
            "2028-02-29"                  | "2028-02-29"                    | true  | false
            # --- testo che sembra una data ma non lo è (2026 non è bisestile): resta testo
            "2026-02-29"                  | "2026-02-29"                    | true  | false
            "2026-02-29"                  | "2026-03-01"                    | false | true
            # --- istante ISO con fuso: stesso istante anche con fusi diversi; niente data senza ora né ora senza fuso
            "2026-03-29T01:30:00Z"        | "2026-03-29T02:30:00+01:00"     | true  | false
            "2026-03-29T01:30:00Z"        | "2026-03-29T03:30:00+02:00"     | true  | false
            "2026-03-29T01:30:00Z"        | "2026-03-29T01:30:00Z"          | true  | false
            "2026-03-29T01:30:00Z"        | "2026-03-29T01:31:00Z"          | false | true
            "2026-03-29T01:30:00Z"        | "2026-03-29"                    | false | false
            "2026-03-29T01:30:00Z"        | "2026-03-29T01:30:00"           | false | false
            "2026-03-29T01:30:00Z"        | "2026-03-29 01:30:00Z"          | false | false
            "2026-03-29T02:30:00+01:00"   | "2026-03-29T01:30:00Z"          | true  | false
            # --- valore osservato non scalare: nessun cast
            [5]                           | 5                               | false | false
            {"v":5}                       | 5                               | false | false
            """)
    void eqNeqTable(String actual, String value, boolean eq, boolean neq) {
        JsonNode a = JSON.readTree(actual);
        JsonNode v = json(value);
        assertThat(TypedCast.compare("eq", a, v)).as("%s eq %s", actual, value).isEqualTo(eq);
        assertThat(TypedCast.compare("neq", a, v)).as("%s neq %s", actual, value).isEqualTo(neq);
    }

    // ---------- conversioni del valore della regola ----------

    @ParameterizedTest(name = "[{index}] numero ← {0} = {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            5                  | 5
            5.25               | 5.25
            -3                 | -3
            "5"                | 5
            "5.0"              | 5.0
            "05"               | 5
            "-0"               | 0
            "-12.50"           | -12.50
            " 5"               | ~
            "5 "               | ~
            "5e1"              | ~
            "+5"               | ~
            "5."               | ~
            ".5"               | ~
            "1,5"              | ~
            "1.000,5"          | ~
            "1_000"            | ~
            ""                 | ~
            "TRUE"             | ~
            "NaN"              | ~
            "Infinity"         | ~
            "0x10"             | ~
            true               | ~
            null               | ~
            [5]                | ~
            """)
    void toNumber(String value, String expected) {
        var cast = TypedCast.toNumber(JSON.readTree(value));
        if ("~".equals(expected)) {
            assertThat(cast).as(value).isEmpty();
        } else {
            assertThat(cast).as(value).hasValueSatisfying(n -> assertThat(n).isEqualByComparingTo(new BigDecimal(expected)));
        }
    }

    @ParameterizedTest(name = "[{index}] booleano ← {0} = {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            true     | true
            false    | false
            "true"   | true
            "false"  | false
            "TRUE"   | ~
            "False"  | ~
            " true"  | ~
            "yes"    | ~
            "1"      | ~
            1        | ~
            0        | ~
            ""       | ~
            null     | ~
            """)
    void toBoolean(String value, String expected) {
        var cast = TypedCast.toBoolean(JSON.readTree(value));
        if ("~".equals(expected)) {
            assertThat(cast).as(value).isEmpty();
        } else {
            assertThat(cast).as(value).contains(Boolean.parseBoolean(expected));
        }
    }

    @ParameterizedTest(name = "[{index}] data ← {0} = {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            "2026-03-29"                 | 2026-03-29
            "2028-02-29"                 | 2028-02-29
            "2026-02-29"                 | ~
            "2026-02-30"                 | ~
            "2026-13-01"                 | ~
            "2026-3-29"                  | ~
            "26-03-29"                   | ~
            "+2026-03-29"                | ~
            " 2026-03-29"                | ~
            "2026-03-29T02:30:00+01:00"  | ~
            "29/03/2026"                 | ~
            20260329                     | ~
            ""                           | ~
            """)
    void toDate(String value, String expected) {
        var cast = TypedCast.toDate(JSON.readTree(value));
        if ("~".equals(expected)) {
            assertThat(cast).as(value).isEmpty();
        } else {
            assertThat(cast).as(value).contains(LocalDate.parse(expected));
        }
    }

    @ParameterizedTest(name = "[{index}] istante ← {0} = {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            "2026-03-29T02:30:00+01:00"      | 2026-03-29T01:30:00Z
            "2026-03-29T01:30:00Z"           | 2026-03-29T01:30:00Z
            "2026-03-29T01:30Z"              | 2026-03-29T01:30:00Z
            "2026-03-29T01:30:00.123+00:00"  | 2026-03-29T01:30:00.123Z
            "2026-10-25T02:30:00+02:00"      | 2026-10-25T00:30:00Z
            "2026-03-29T01:30:00"            | ~
            "2026-03-29T25:30:00Z"           | ~
            "2026-02-29T10:00:00Z"           | ~
            "2026-03-29T01:30:00z"           | ~
            "2026-03-29 01:30:00Z"           | ~
            "2026-03-29T01:30:00+0100"       | ~
            "2026-03-29"                     | ~
            1774747800                       | ~
            """)
    void toInstant(String value, String expected) {
        var cast = TypedCast.toInstant(JSON.readTree(value));
        if ("~".equals(expected)) {
            assertThat(cast).as(value).isEmpty();
        } else {
            assertThat(cast).as(value).contains(Instant.parse(expected));
        }
    }

    // ---------- tipo del valore osservato ----------

    @Test
    void observedType() {
        assertThat(TypedCast.typed(JSON.readTree("5")).type()).isEqualTo(TypedCast.Type.NUMBER);
        assertThat(TypedCast.typed(JSON.readTree("\"5\"")).type()).isEqualTo(TypedCast.Type.STRING);
        assertThat(TypedCast.typed(JSON.readTree("true")).type()).isEqualTo(TypedCast.Type.BOOLEAN);
        assertThat(TypedCast.typed(JSON.readTree("\"2026-03-29\"")).type()).isEqualTo(TypedCast.Type.DATE);
        assertThat(TypedCast.typed(JSON.readTree("\"2026-02-29\"")).type()).isEqualTo(TypedCast.Type.STRING);
        assertThat(TypedCast.typed(JSON.readTree("\"2026-03-29T02:30:00+01:00\"")).type()).isEqualTo(TypedCast.Type.INSTANT);
        assertThat(TypedCast.typed(JSON.readTree("null"))).isNull();
        assertThat(TypedCast.typed(JSON.readTree("[1]"))).isNull();
        assertThat(TypedCast.typed(null)).isNull();
        // valori Java calcolati dai servizi (età, giorni, saldi)
        assertThat(TypedCast.typed(26.0).value()).isEqualTo(BigDecimal.valueOf(26.0));
        assertThat(TypedCast.typed(1500L).type()).isEqualTo(TypedCast.Type.NUMBER);
        assertThat(TypedCast.typed(Double.NaN)).isNull();
        assertThat(TypedCast.typed(LocalDate.of(2026, 3, 29)).type()).isEqualTo(TypedCast.Type.DATE);
        assertThat(TypedCast.compare("eq", 26.0, JSON.readTree("26"))).isTrue();
        assertThat(TypedCast.compare("eq", 26.0, JSON.readTree("\"26\""))).isTrue();
    }

    // ---------- altri comparatori ----------

    @ParameterizedTest(name = "[{index}] {0} {1} {2} → {3}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            60                       | gt         | 50                               | true
            60                       | gt         | "50"                             | true
            60                       | gt         | "50e0"                           | false
            "60"                     | gt         | 50                               | false
            "60"                     | gt         | "50"                             | false
            50                       | gte        | 50                               | true
            49.99                    | gte        | "50"                             | false
            50                       | lt         | "50.01"                          | true
            50                       | lte        | 50.0                             | true
            true                     | gt         | 0                                | false
            "APP"                    | gt         | "AAA"                            | false
            "2026-09-20"             | gt         | "2026-09-01"                     | true
            "2026-09-20"             | lt         | "2026-09-01"                     | false
            "2026-09-20"             | gt         | "2026-09-01T00:00:00Z"           | false
            "2026-09-20T10:00:00Z"   | gt         | "2026-09-20T11:00:00+02:00"      | true
            "2026-09-20T10:00:00Z"   | gte        | "2026-09-20"                     | false
            15                       | between    | [10,20]                          | true
            10                       | between    | [10,20]                          | true
            20                       | between    | ["10","20"]                      | true
            9.99                     | between    | [10,20]                          | false
            15                       | between    | [20,10]                          | false
            15                       | between    | [10]                             | false
            15                       | between    | [10,20,30]                       | false
            15                       | between    | [10,"x"]                         | false
            15                       | between    | 10                               | false
            "15"                     | between    | [10,20]                          | false
            "2026-02-28"             | between    | ["2026-01-01","2026-12-31"]      | true
            "2026-02-28"             | between    | ["2026-01-01","2026-02-29"]      | false
            3                        | in         | [1,2,3]                          | true
            3                        | in         | [1,"3"]                          | true
            3                        | in         | [1,"x",3]                        | false
            3                        | in         | []                               | false
            3                        | in         | 3                                | false
            3                        | in         | [[3]]                            | false
            3                        | nin        | [1,2]                            | true
            3                        | nin        | [1,3]                            | false
            3                        | nin        | [1,"x"]                          | false
            3                        | nin        | []                               | true
            "APP"                    | nin        | [5]                              | false
            "PROMO-ESTATE"           | contains   | "ESTATE"                         | true
            "PROMO-ESTATE"           | ncontains  | "INVERNO"                        | true
            "PROMO-ESTATE"           | ncontains  | "ESTATE"                         | false
            130                      | contains   | "3"                              | false
            130                      | ncontains  | "x"                              | false
            "2026-02-28"             | contains   | "2026-02"                        | false
            "2026-02-28"             | ncontains  | "X"                              | false
            "A5"                     | contains   | 5                                | false
            "PROMO-ESTATE"           | startsWith | "PROMO"                          | true
            "PROMO-ESTATE"           | startsWith | "promo"                          | false
            130                      | startsWith | "13"                             | false
            true                     | startsWith | "t"                              | false
            "2026-02-28"             | startsWith | "2026"                           | false
            "PROMO"                  | regex      | "PRO.*"                          | false
            50                       | foo        | 50                               | false
            50                       | exists     | ~                                | true
            50                       | nexists    | ~                                | false
            """)
    void comparators(String actual, String cmp, String value, boolean expected) {
        assertThat(TypedCast.compare(cmp, JSON.readTree(actual), json(value))).as("%s %s %s", actual, cmp, value)
                .isEqualTo(expected);
    }

    @Test
    void unknownOrMissingComparatorIsFalse() {
        assertThat(TypedCast.compare(null, JSON.readTree("5"), JSON.readTree("5"))).isFalse();
        assertThat(TypedCast.compare("EQ", JSON.readTree("5"), JSON.readTree("5"))).isFalse();
    }

    // ---------- elenchi osservati ----------

    @Test
    void listMembership() {
        List<Object> tags = List.of("A", "B");
        List<Object> numbers = List.of(new BigDecimal("1"), new BigDecimal("2"));
        assertThat(TypedCast.listContains(tags, JSON.readTree("\"B\""))).isTrue();
        assertThat(TypedCast.listContains(tags, JSON.readTree("\"C\""))).isFalse();
        assertThat(TypedCast.listNotContains(tags, JSON.readTree("\"C\""))).isTrue();
        assertThat(TypedCast.listNotContains(tags, JSON.readTree("\"A\""))).isFalse();
        assertThat(TypedCast.listContains(numbers, JSON.readTree("\"2\""))).as("regola convertita nel tipo dell'elemento").isTrue();
        assertThat(TypedCast.listContains(numbers, JSON.readTree("2.0"))).isTrue();
        assertThat(TypedCast.listNotContains(numbers, JSON.readTree("\"x\""))).as("tipo incompatibile: negazione falsa").isFalse();
        assertThat(TypedCast.listNotContains(List.of(), JSON.readTree("\"x\""))).as("elenco vuoto").isTrue();
        assertThat(TypedCast.listContains(List.of(), JSON.readTree("\"x\""))).isFalse();
        assertThat(TypedCast.listContains(tags, JSON.readTree("[\"A\"]"))).as("valore non scalare").isFalse();
        assertThat(TypedCast.listNotContains(tags, null)).isFalse();
        assertThat(TypedCast.listContains(List.of("5"), JSON.readTree("5"))).as("mai numero → testo").isFalse();
    }
}
