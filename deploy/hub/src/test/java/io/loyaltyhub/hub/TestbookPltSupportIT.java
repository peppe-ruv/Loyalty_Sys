package io.loyaltyhub.hub;

import org.junit.jupiter.api.DynamicTest;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base comune del testbook TB-PLT nell'hub (docs/testbook/TB-PLT-piattaforma.md): riusa gli strumenti di
 * {@link TestbookE2eSupportIT} (HTTP, quiete del bus, event store) e legge le righe da {@code /testbook/plt/}.
 * Non contiene casi.
 */
abstract class TestbookPltSupportIT extends TestbookE2eSupportIT {

    /** Un caso per riga di {@code /testbook/plt/<file>}: nome «[ID] descrizione». */
    static Stream<DynamicTest> plt(String file, Consumer<Row> body) {
        String resource = "/testbook/plt/" + file;
        return read(resource).stream().map(r -> DynamicTest.dynamicTest("[" + r.id() + "] " + r.desc(),
                URI.create("classpath:" + resource + "?line=" + r.line()), () -> body.accept(r)));
    }

    /** Risposta HTTP grezza: stato, intestazioni scelte e corpo testuale. */
    record Raw(int status, String contentType, String body) {
        JsonNode json(tools.jackson.databind.ObjectMapper mapper) {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        }
    }

    /** Richiesta con metodo, intestazioni e corpo testuali arbitrari (JSON malformato, content-type sbagliati). */
    Raw raw(String method, String path, Map<String, String> headers, String body) {
        try {
            var builder = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(java.time.Duration.ofSeconds(60));
            headers.forEach(builder::header);
            builder.method(method, body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                    : java.net.http.HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            try (var client = java.net.http.HttpClient.newHttpClient()) {
                var res = client.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Raw(res.statusCode(), res.headers().firstValue("Content-Type").orElse(""), res.body());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> headers(String... kv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                out.put(kv[i], kv[i + 1]);
            }
        }
        return out;
    }

    /**
     * Impronta dello stato demo dopo un reset (docs/10 §1.3, ADR-015): numero di righe di ogni tabella degli schemi dei
     * servizi (esclusi i registri tecnici e ciò che insight registra dagli eventi successivi al reset) più le proiezioni
     * con chiavi naturali che un reset deve riprodurre identiche (membri, saldi, livelli, codici coupon, stock, istanti,
     * stato di campagne/premi/concorsi/contenuti).
     */
    Map<String, List<String>> fingerprint() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<String> tables = jdbc.sql("""
                        SELECT table_schema || '.' || table_name FROM information_schema.tables
                        WHERE table_type = 'BASE TABLE'
                          AND table_schema IN ('ingestion','member','campaign','wallet','insight','reward','gamification','engagement')
                          AND table_name NOT IN ('flyway_schema_history','outbox','processed_event','event_store','audit_entry','topic_stat',
                                                 'dlq_entry','metric_daily','service_stat')
                        ORDER BY 1
                        """).query(String.class).list();
        List<String> counts = new ArrayList<>();
        for (String t : tables) {
            counts.add(t + "=" + count("SELECT count(*) FROM " + t));
        }
        out.put("righe", counts);
        out.put("membri", strings("SELECT id || ':' || status || ':' || coalesce(email, '-') FROM member.member ORDER BY id"));
        out.put("saldi", strings("""
                SELECT member_id || ':' || currency || ':' || balance_active || ':' || balance_pending FROM wallet.wallet ORDER BY member_id, currency"""));
        out.put("livelli", strings("SELECT member_id || ':' || tier_code FROM wallet.member_tier ORDER BY member_id"));
        out.put("coupon", strings("SELECT code || ':' || status || ':' || coalesce(member_id, '-') FROM reward.coupon ORDER BY code"));
        out.put("premi", strings("SELECT code || ':' || status || ':' || coalesce(stock_remaining, -1) FROM reward.reward ORDER BY code"));
        out.put("istanti", strings("""
                SELECT c.code || ':' || w.status || ':' || to_char(w.instant_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI')
                FROM gamification.winning_instant w JOIN gamification.contest c ON c.id = w.contest_id ORDER BY 1"""));
        out.put("campagne", strings("SELECT code || ':' || status FROM campaign.campaign ORDER BY code"));
        out.put("concorsi", strings("SELECT code || ':' || status FROM gamification.contest ORDER BY code"));
        out.put("indice", strings("SELECT member_id || ':' || status FROM ingestion.member_index ORDER BY member_id"));
        return out;
    }

    List<String> strings(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }

    static void assertSame(Map<String, List<String>> actual, Map<String, List<String>> expected, String what) {
        for (String k : expected.keySet()) {
            assertThat(actual.get(k)).as("%s — %s", what, k).isEqualTo(expected.get(k));
        }
    }
}
