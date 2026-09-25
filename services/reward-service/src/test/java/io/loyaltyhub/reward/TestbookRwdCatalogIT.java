package io.loyaltyhub.reward;

import io.loyaltyhub.reward.TestbookRwdCsv.Row;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.loyaltyhub.reward.TestbookRwdCsv.scenario;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-RWD — catalogo: creazione e validazione (CAT), categorie, modifica per stato e campi bloccati in LIVE,
 * versione e ricalcolo dello stock, duplica (EDT), fasce (BND), ciclo di vita via API (LCY-101…123), statistiche
 * (STK-022/023). Oracolo: reward-service §2–§3, docs/03 §3.6 e §5, docs/06 §2–§3 e §7, docs/08 §2 e BO-10/11, Q-112.
 */
class TestbookRwdCatalogIT extends TestbookRwdBase {

    private static final AtomicInteger LADDER = new AtomicInteger();

    // ---------- CAT: creazione ----------

    // Q-281 DECISA (TB-RWD-CAT-007, -016, -021 come prima; -010: AUTO_COUPON senza pool → 422 REWARD_INVALID)
    @TestFactory
    Stream<DynamicTest> create() {
        return TestbookRwdCsv.rows("reward-create.csv", row -> {
            CLOCK.set(T0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", fresh("RWD-TB"));
            body.put("name", "Premio testbook");
            body.put("type", "DIGITAL");
            body.put("fulfilment", "INSTANT");
            body.put("band", "F1");
            body.put("category", "TEMPO");
            body.put("stockTotal", 10);
            applyOverrides(body, row.get("overrides"));
            Resp r = send("POST", "/v1/rewards", actorFor(row.get("role")), body);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (r.status() == 201) {
                assertThat(r.body().path("status").asString()).isEqualTo("DRAFT");
                assertThat(r.body().path("version").asLong()).isZero();
                Object total = body.get("stockTotal");
                if (total == null) {
                    assertThat(r.body().path("stockTotal").isMissingNode() || r.body().path("stockTotal").isNull()).isTrue();
                } else {
                    assertThat(r.body().path("stockRemaining").asInt()).isEqualTo((Integer) total);
                }
                if (body.get("type") instanceof String t) {
                    assertThat(r.body().path("type").asString()).isEqualTo(t.trim().toUpperCase());
                }
            }
        });
    }

    /** {@code k=v;k=v}: {@code <null>} toglie il campo, {@code <blank>} spazi, {@code <pool>} pool nuovo, {@code <dup>} codice esistente. */
    private void applyOverrides(Map<String, Object> body, String overrides) {
        if ("-".equals(overrides)) {
            return;
        }
        for (String pair : overrides.split(";")) {
            int eq = pair.indexOf('=');
            String key = pair.substring(0, eq);
            String raw = pair.substring(eq + 1);
            Object value = switch (raw) {
                case "<null>" -> null;
                case "<blank>" -> "   ";
                case "<pool>" -> pool(1, 30);
                case "<dup>" -> reward("DRAFT", Map.of()).path("code").asString();
                case "<other>" -> fresh("RWD-OTHER");
                default -> switch (key) {
                    case "stockTotal", "perMemberLimit" -> Integer.parseInt(raw);
                    case "eligibleTiers", "eligibleSegments" -> split(raw);
                    case "validFrom", "validTo" -> raw.contains("T") ? raw : instant(raw, T0);
                    default -> raw;
                };
            };
            if (value == null) {
                body.remove(key);
            } else {
                body.put(key, value);
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> category() {
        return TestbookRwdCsv.rows("category.csv", row -> {
            String code = row.get("code");
            Map<String, Object> body = new LinkedHashMap<>();
            if (!"-".equals(row.get("name"))) {
                body.put("name", row.get("name"));
            }
            body.put("icon", "sparkles");
            body.put("sortOrder", 99);
            Resp r;
            if ("PUT".equals(code)) {
                String existing = fresh("CAT-TB");
                call("POST", "/v1/reward-categories", "ADMIN:testbook", Map.of("code", existing, "name", "Vecchio", "icon", "x", "sortOrder", 98));
                r = send("PUT", "/v1/reward-categories/" + existing, actorFor(row.get("role")), body);
            } else {
                body.put("code", "NEW".equals(code) ? fresh("CAT-TB") : code);
                r = send("POST", "/v1/reward-categories", actorFor(row.get("role")), body);
            }
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (r.status() / 100 == 2) {
                assertThat(r.body().path("name").asString()).isEqualTo(row.get("name"));
            }
        });
    }

    // ---------- EDT: modifica per stato ----------

    private Map<String, Object> editableBase() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", "Premio testbook");
        f.put("description", "Descrizione");
        f.put("terms", "Termini");
        f.put("imageUrl", "/demo/a.png");
        f.put("perMemberLimit", 2);
        f.put("stockTotal", 10);
        f.put("validFrom", T0.minusSeconds(86_400).toString());
        f.put("validTo", T0.plusSeconds(30 * 86_400).toString());
        return f;
    }

    // Q-281 DECISA (TB-RWD-EDT-029: codice non modificabile, 409 CODE_IMMUTABLE)
    @TestFactory
    Stream<DynamicTest> edit() {
        return TestbookRwdCsv.rows("reward-edit.csv", row -> {
            CLOCK.set(T0);
            String id;
            JsonNode before = null;
            if (row.is("state", "UNKNOWN")) {
                id = fresh("NOPE");
            } else {
                before = reward(row.get("state"), editableBase());
                id = before.path("id").asString();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            applyOverrides(body, row.get("change"));
            if (before != null) {
                body.put("version", before.path("version").asLong());
            }
            Resp r = send("PUT", "/v1/rewards/" + id, actorFor(row.get("role")), body);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (before == null) {
                return;
            }
            JsonNode after = rewardById(id);
            if (r.status() == 200) {
                assertThat(after.path("version").asLong()).isEqualTo(before.path("version").asLong() + 1);
                for (Map.Entry<String, Object> e : body.entrySet()) {
                    if (!e.getKey().equals("version")) {
                        assertThat(fieldOf(after, e.getKey())).as(e.getKey()).isEqualTo(normalized(e.getValue()));
                    }
                }
            } else {
                assertThat(after.path("version").asLong()).as("nessuna modifica").isEqualTo(before.path("version").asLong());
                assertThat(after.path("status").asString()).isEqualTo(before.path("status").asString());
            }
        });
    }

    /** Valore del campo nella vista del premio (i nomi del corpo e della vista differiscono per fascia e categoria). */
    private static String fieldOf(JsonNode reward, String key) {
        JsonNode v = reward.path(switch (key) {
            case "band" -> "bandCode";
            case "category" -> "categoryCode";
            default -> key;
        });
        return v.isArray() ? String.join("|", texts(v)) : v.asString();
    }

    private static String normalized(Object value) {
        if (value instanceof List<?> l) {
            return String.join("|", l.stream().map(String::valueOf).toList());
        }
        String s = String.valueOf(value);
        return s.endsWith("Z") ? java.time.Instant.parse(s).toString() : s;
    }

    // ---------- EDT: stock e versione (M7.6) ----------

    // Q-280 DECISA (TB-RWD-EDT-049: version obbligatoria; -051: da illimitato il residuo sottrae le richieste in corso)
    @TestFactory
    Stream<DynamicTest> stockRecalculation() {
        return TestbookRwdCsv.rows("stock-recalc.csv", row -> {
            CLOCK.set(T0);
            String memberId = member("ACTIVE", "GOLD");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fulfilment", "MANUAL");
            f.put("stockTotal", row.is("initialTotal", "-") ? null : row.integer("initialTotal"));
            JsonNode rw = reward("LIVE", f);
            String id = rw.path("id").asString();
            String code = rw.path("code").asString();
            String mode = row.get("mode");
            long version = rewardById(id).path("version").asLong();
            for (int i = 0; i < row.integer("held"); i++) {
                assertThat(requestRedemption(memberId, code, null).status()).isEqualTo(202);
            }
            if ("N".equals(mode)) {
                version = rewardById(id).path("version").asLong();
            } else if ("STALE".equals(mode)) {
                call("PUT", "/v1/rewards/" + id, "MARKETING:testbook", Map.of("imageUrl", "/demo/b.png", "version", version));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (row.is("newTotal", "-")) {
                body.put("imageUrl", "/demo/c.png");
            } else {
                body.put("stockTotal", row.integer("newTotal"));
            }
            if (!"NOVERSION".equals(mode)) {
                body.put("version", version);
            }
            Resp r = send("PUT", "/v1/rewards/" + id, "MARKETING:testbook", body);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if ("STALE".equals(mode)) {
                assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
            }
            if ("NOVERSION".equals(mode)) {
                assertThat(r.code()).isEqualTo("VERSION_REQUIRED");
            }
            JsonNode after = rewardById(id);
            assertThat(after.path("stockTotal").asInt()).as("stockTotal").isEqualTo(row.integer("expTotal"));
            assertThat(after.path("stockRemaining").asInt()).as("stockRemaining").isEqualTo(row.integer("expRemaining"));
        });
    }

    // ---------- BND: fasce ----------

    /** Scala di tre fasce nuove sopra tutte le altre: soglie e ordini crescono con l'esecuzione. */
    record Ladder(String[] codes, long[] thresholds, int sort) {
    }

    private Ladder ladder() {
        int k = LADDER.incrementAndGet();
        long base = 1_000_000L + k * 100_000L;
        int sort = 1000 + k * 10;
        String[] codes = new String[3];
        long[] thr = new long[3];
        for (int i = 0; i < 3; i++) {
            codes[i] = fresh("BTB");
            thr[i] = base + i * 10_000L;
            call("POST", "/v1/reward-bands", "ADMIN:testbook",
                    Map.of("code", codes[i], "name", "Fascia test " + i, "pointsThreshold", thr[i], "color", "#000000", "sortOrder", sort + i));
        }
        return new Ladder(codes, thr, sort);
    }

    private static long threshold(String token, Ladder l) {
        if (token.startsWith("L")) {
            int idx = token.charAt(1) - '1';
            long off = token.length() > 2 ? Long.parseLong(token.substring(2)) : 0;
            return l.thresholds()[idx] + off;
        }
        return Long.parseLong(token);
    }

    private JsonNode band(String code) {
        for (JsonNode b : get("/v1/reward-bands")) {
            if (code.equals(b.path("code").asString())) {
                return b;
            }
        }
        return null;
    }

    // Q-281 DECISA (TB-RWD-BND-012: soglia di fascia ≥ 1)
    @TestFactory
    Stream<DynamicTest> bands() {
        return TestbookRwdCsv.rows("bands.csv", row -> {
            CLOCK.set(T0);
            Ladder l = ladder();
            String op = row.get("op");
            String actor = actorFor(row.get("role"));
            Resp r;
            String checkCode = null;
            long expectedThreshold = 0;
            if (op.startsWith("CREATE")) {
                String code = "CREATE_TOP_NOCODE".equals(op) ? "   " : fresh("BTB");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", code);
                if (!"CREATE_TOP_NONAME".equals(op)) {
                    body.put("name", "Fascia nuova");
                }
                long t = threshold(row.get("threshold"), l);
                body.put("pointsThreshold", t);
                body.put("color", "#111111");
                body.put("sortOrder", "CREATE_BOTTOM".equals(op) ? l.sort() - 1 : l.sort() + 3);
                r = send("POST", "/v1/reward-bands", actor, body);
                checkCode = code;
                expectedThreshold = t;
            } else if ("UPDATE_MID".equals(op)) {
                long t = threshold(row.get("threshold"), l);
                r = send("PUT", "/v1/reward-bands/" + l.codes()[1], actor,
                        Map.of("name", "Fascia test 1", "pointsThreshold", t, "color", "#000000", "sortOrder", l.sort() + 1));
                checkCode = l.codes()[1];
                expectedThreshold = r.status() == 200 ? t : l.thresholds()[1];
            } else if (op.startsWith("DELETE_WITH:")) {
                reward(op.substring("DELETE_WITH:".length()), Map.of("band", l.codes()[2]));
                r = send("DELETE", "/v1/reward-bands/" + l.codes()[2], actor, null);
                assertThat(band(l.codes()[2])).as("fascia ancora presente").isNotNull();
            } else if ("DELETE_EMPTY".equals(op)) {
                r = send("DELETE", "/v1/reward-bands/" + l.codes()[2], actor, null);
                assertThat(band(l.codes()[2]) == null).as("fascia eliminata").isEqualTo(r.status() == 204);
            } else {
                r = send("DELETE", "/v1/reward-bands/" + fresh("NOPE"), actor, null);
            }
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (checkCode != null && op.startsWith("CREATE")) {
                assertThat(band(checkCode) != null).as("fascia creata").isEqualTo(r.status() == 201);
            } else if (checkCode != null) {
                assertThat(band(checkCode).path("pointsThreshold").asLong()).isEqualTo(expectedThreshold);
            }
        });
    }

    // ---------- LCY: transizioni via API ----------

    @TestFactory
    Stream<DynamicTest> lifecycleApi() {
        return TestbookRwdCsv.rows("lifecycle-api.csv", row -> {
            CLOCK.set(T0);
            String id = row.is("from", "UNKNOWN") ? fresh("NOPE") : reward(row.get("from"), Map.of()).path("id").asString();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", row.get("action"));
            if (!row.is("comment", "-")) {
                body.put("comment", row.get("comment"));
            }
            Resp r = send("POST", "/v1/rewards/" + id + "/transitions", actorFor(row.get("role")), body);
            assertThat(r.status()).as("→ " + r.body()).isEqualTo(row.integer("expHttp"));
            if (!row.is("expCode", "-")) {
                assertThat(r.code()).isEqualTo(row.get("expCode"));
            }
            if (!row.is("expState", "-")) {
                assertThat(rewardById(id).path("status").asString()).isEqualTo(row.get("expState"));
            }
        });
    }

    // ---------- scenari ----------

    @TestFactory
    Stream<DynamicTest> scenarios() {
        return Stream.of(
                // Q-280 DECISA (TB-RWD-EDT-053): il ripristino non supera totale − richieste che tengono stock
                scenario("TB-RWD-EDT-053", "ripristino dello stock dopo una riduzione del totale", this::restoreAfterReduction),
                scenario("TB-RWD-EDT-060", "duplica di un DRAFT", this::duplicateDraft),
                // Q-281 DECISA (TB-RWD-EDT-061: seconda copia -COPY2)
                scenario("TB-RWD-EDT-061", "seconda duplica", this::duplicateTwice),
                scenario("TB-RWD-EDT-062", "duplica di un LIVE con prenotazioni", this::duplicateLive),
                scenario("TB-RWD-EDT-063", "duplica con ruolo CARE", this::duplicateAsCare),
                scenario("TB-RWD-BND-040", "il costo nel catalogo segue la soglia della fascia", this::costFollowsThreshold),
                scenario("TB-RWD-BND-041", "costo fissato alla richiesta", this::costFixedAtRequest),
                scenario("TB-RWD-BND-042", "fasce del portale in ordine di soglia", this::portalBandsOrdered),
                scenario("TB-RWD-LCY-120", "storico delle transizioni con commento", this::approvalHistory),
                scenario("TB-RWD-LCY-121", "fatto reward.status.changed", this::statusChangedFact),
                scenario("TB-RWD-LCY-122", "coda approvazioni nel formato comune", this::approvalsQueue),
                scenario("TB-RWD-LCY-123", "rifiutato torna modificabile e reinviabile", this::rejectedIsEditable),
                scenario("TB-RWD-STK-022", "statistiche: stock sotto il 10 %", this::statsLowStock),
                scenario("TB-RWD-STK-023", "statistiche: premi e richieste per stato", this::statsByStatus),
                scenario("TB-RWD-AUD-001", "audit della creazione di un premio", this::auditCreate),
                scenario("TB-RWD-AUD-002", "audit della modifica di un premio", this::auditUpdate),
                scenario("TB-RWD-AUD-003", "audit delle transizioni con override ADMIN", this::auditTransitions),
                scenario("TB-RWD-AUD-004", "audit delle fasce", this::auditBands),
                scenario("TB-RWD-AUD-005", "audit dei pool", this::auditPools),
                scenario("TB-RWD-AUD-006", "audit delle categorie", this::auditCategory));
    }

    private static List<String> actions(List<JsonNode> audits) {
        return audits.stream().map(a -> a.path("data").path("action").asString()).toList();
    }

    void auditCreate() {
        CLOCK.set(T0);
        String code = fresh("RWD-TB");
        call("POST", "/v1/rewards", "MARKETING:luca.marketing", Map.of("code", code, "name", "Premio audit", "type", "DIGITAL",
                "fulfilment", "INSTANT", "band", "F1"));
        List<JsonNode> a = audits("REWARD", code);
        assertThat(actions(a)).containsExactly("CREATE");
        assertThat(a.get(0).path("lhactor").asString()).isEqualTo("MARKETING:luca.marketing");
    }

    void auditUpdate() {
        CLOCK.set(T0);
        JsonNode rw = reward("DRAFT", Map.of("stockTotal", 10));
        call("PUT", "/v1/rewards/" + rw.path("id").asString(), "MARKETING:luca.marketing",
                Map.of("stockTotal", 12, "version", rw.path("version").asLong()));
        List<JsonNode> a = audits("REWARD", rw.path("code").asString());
        JsonNode update = a.get(a.size() - 1);
        assertThat(update.path("data").path("action").asString()).isEqualTo("UPDATE");
        assertThat(update.path("data").path("before").path("stockTotal").asString()).isEqualTo("10");
        assertThat(update.path("data").path("after").path("stockTotal").asString()).isEqualTo("12");
    }

    void auditTransitions() {
        CLOCK.set(T0);
        JsonNode rw = reward("DRAFT", Map.of());
        String id = rw.path("id").asString();
        call("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:luca.marketing", Map.of("action", "SUBMIT"));
        call("POST", "/v1/rewards/" + id + "/transitions", "ADMIN:sara.admin", Map.of("action", "APPROVE"));
        List<JsonNode> t = audits("REWARD", rw.path("code").asString()).stream()
                .filter(x -> "TRANSITION".equals(x.path("data").path("action").asString())).toList();
        assertThat(t).hasSize(2);
        assertThat(t.get(0).path("data").path("summary").asString()).doesNotContain("override");
        assertThat(t.get(1).path("data").path("summary").asString()).contains("override");
        assertThat(t.get(1).path("lhactor").asString()).isEqualTo("ADMIN:sara.admin");
    }

    void auditBands() {
        CLOCK.set(T0);
        Ladder l = ladder();
        String code = l.codes()[2];
        call("PUT", "/v1/reward-bands/" + code, "MARKETING:testbook",
                Map.of("name", "Fascia test 2", "pointsThreshold", l.thresholds()[2] + 1, "color", "#000000", "sortOrder", l.sort() + 2));
        assertThat(send("DELETE", "/v1/reward-bands/" + code, "MARKETING:testbook", null).status()).isEqualTo(204);
        assertThat(actions(audits("REWARD_BAND", code))).containsExactly("CREATE", "UPDATE", "DELETE");
    }

    void auditPools() {
        CLOCK.set(T0);
        String poolId = pool(2, 30);
        String code = get("/v1/coupon-pools/" + poolId).path("code").asString();
        assertThat(actions(audits("COUPON_POOL", code))).containsExactly("CREATE", "UPDATE");
    }

    void auditCategory() {
        String code = fresh("CAT-TB");
        call("POST", "/v1/reward-categories", "MARKETING:testbook", Map.of("code", code, "name", "Categoria audit", "icon", "x", "sortOrder", 97));
        assertThat(actions(audits("REWARD_CATEGORY", code))).containsExactly("CREATE");
    }

    void restoreAfterReduction() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        JsonNode rw = reward("LIVE", Map.of("fulfilment", "MANUAL", "stockTotal", 5));
        String id = rw.path("id").asString();
        List<Resp> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            held.add(requestRedemption(memberId, rw.path("code").asString(), null));
        }
        call("PUT", "/v1/rewards/" + id, "MARKETING:testbook", Map.of("stockTotal", 2, "version", rewardById(id).path("version").asLong()));
        assertThat(stock(id)).isZero();
        JsonNode first = held.get(0).body();
        awaitProcessed(publishFact("io.loyaltyhub.fact.wallet.spend.rejected", memberId, first.path("correlationId").asString(),
                Map.of("redemptionId", first.path("redemptionId").asString(), "reason", "INSUFFICIENT_BALANCE")));
        // 2 richieste ancora in corso su un totale di 2: nessuna unità da riaprire.
        assertThat(stock(id)).isZero();
    }

    void duplicateDraft() {
        CLOCK.set(T0);
        JsonNode orig = reward("DRAFT", Map.of("type", "EXPERIENCE", "fulfilment", "MANUAL", "band", "F3", "stockTotal", 7, "perMemberLimit", 1));
        Resp r = send("POST", "/v1/rewards/" + orig.path("id").asString() + "/duplicate", "MARKETING:testbook", null);
        assertThat(r.status()).isEqualTo(201);
        JsonNode copy = r.body();
        assertThat(copy.path("code").asString()).isEqualTo(orig.path("code").asString() + "-COPY");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("version").asLong()).isZero();
        for (String f : List.of("type", "fulfilment", "bandCode", "stockTotal", "perMemberLimit", "categoryCode")) {
            assertThat(copy.path(f).asString()).as(f).isEqualTo(orig.path(f).asString());
        }
    }

    void duplicateTwice() {
        CLOCK.set(T0);
        JsonNode orig = reward("DRAFT", Map.of());
        String id = orig.path("id").asString();
        call("POST", "/v1/rewards/" + id + "/duplicate", "MARKETING:testbook", null);
        JsonNode second = call("POST", "/v1/rewards/" + id + "/duplicate", "MARKETING:testbook", null);
        assertThat(second.path("code").asString()).isEqualTo(orig.path("code").asString() + "-COPY2");
    }

    void duplicateLive() {
        CLOCK.set(T0);
        String memberId = member("ACTIVE", "GOLD");
        JsonNode orig = reward("LIVE", Map.of("fulfilment", "MANUAL", "stockTotal", 10));
        for (int i = 0; i < 3; i++) {
            requestRedemption(memberId, orig.path("code").asString(), null);
        }
        assertThat(stock(orig.path("id").asString())).isEqualTo(7);
        JsonNode copy = call("POST", "/v1/rewards/" + orig.path("id").asString() + "/duplicate", "ADMIN:testbook", null);
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("stockRemaining").asInt()).isEqualTo(10);
    }

    void duplicateAsCare() {
        CLOCK.set(T0);
        JsonNode orig = reward("DRAFT", Map.of());
        assertThat(send("POST", "/v1/rewards/" + orig.path("id").asString() + "/duplicate", "CARE:testbook.care", null).status())
                .isEqualTo(403);
    }

    void costFollowsThreshold() {
        CLOCK.set(T0);
        Ladder l = ladder();
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of("band", l.codes()[1])).path("code").asString();
        assertThat(catalogEntry(memberId, code).path("pointsCost").asLong()).isEqualTo(l.thresholds()[1]);
        long changed = l.thresholds()[1] + 5_000;
        call("PUT", "/v1/reward-bands/" + l.codes()[1], "MARKETING:testbook",
                Map.of("name", "Fascia test 1", "pointsThreshold", changed, "color", "#000000", "sortOrder", l.sort() + 1));
        assertThat(catalogEntry(memberId, code).path("pointsCost").asLong()).isEqualTo(changed);
    }

    void costFixedAtRequest() {
        CLOCK.set(T0);
        Ladder l = ladder();
        String memberId = member("ACTIVE", "GOLD");
        String code = reward("LIVE", Map.of("band", l.codes()[1])).path("code").asString();
        Resp first = requestRedemption(memberId, code, null);
        long changed = l.thresholds()[1] + 5_000;
        call("PUT", "/v1/reward-bands/" + l.codes()[1], "MARKETING:testbook",
                Map.of("name", "Fascia test 1", "pointsThreshold", changed, "color", "#000000", "sortOrder", l.sort() + 1));
        Resp second = requestRedemption(memberId, code, null);
        String firstId = first.body().path("redemptionId").asString();
        String secondId = second.body().path("redemptionId").asString();
        assertThat(redemption(firstId).path("pointsCost").asLong()).isEqualTo(l.thresholds()[1]);
        assertThat(redemption(secondId).path("pointsCost").asLong()).isEqualTo(changed);
        assertThat(facts("io.loyaltyhub.fact.reward.redemption.requested", secondId).get(0).path("data").path("pointsCost").asLong())
                .isEqualTo(changed);
    }

    void portalBandsOrdered() {
        CLOCK.set(T0);
        Ladder l = ladder();
        List<Long> thresholds = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (JsonNode b : get("/v1/portal/catalog?memberId=" + member("ACTIVE", "GOLD")).path("bands")) {
            thresholds.add(b.path("pointsThreshold").asLong());
            codes.add(b.path("code").asString());
        }
        assertThat(thresholds).isSorted();
        assertThat(codes).containsSubsequence(l.codes()[0], l.codes()[1], l.codes()[2]);
    }

    void approvalHistory() {
        CLOCK.set(T0);
        String id = reward("DRAFT", Map.of()).path("id").asString();
        call("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:luca.marketing", Map.of("action", "SUBMIT"));
        CLOCK.set(T0.plusSeconds(3600)); // la decisione arriva un'ora dopo: lo storico è ordinato per istante
        call("POST", "/v1/rewards/" + id + "/transitions", "LEGAL:giulia.legal", Map.of("action", "REJECT", "comment", "Termini da rivedere"));
        JsonNode history = get("/v1/rewards/" + id + "/approval-history");
        assertThat(history.size()).isEqualTo(2);
        JsonNode reject = history.get(0);
        assertThat(reject.path("action").asString()).isEqualTo("REJECT");
        assertThat(reject.path("actor").asString()).isEqualTo("LEGAL:giulia.legal");
        assertThat(reject.path("comment").asString()).isEqualTo("Termini da rivedere");
        assertThat(reject.path("toStatus").asString()).isEqualTo("DRAFT");
        assertThat(history.get(1).path("action").asString()).isEqualTo("SUBMIT");
        assertThat(history.get(1).path("actor").asString()).isEqualTo("MARKETING:luca.marketing");
        assertThat(rewardById(id).path("status").asString()).isEqualTo("DRAFT");
    }

    void statusChangedFact() {
        CLOCK.set(T0);
        JsonNode rw = reward("APPROVED", Map.of());
        String code = rw.path("code").asString();
        call("POST", "/v1/rewards/" + rw.path("id").asString() + "/transitions", "MARKETING:testbook", Map.of("action", "PUBLISH"));
        JsonNode fact = tap().await("lh.facts.v1", r -> "io.loyaltyhub.fact.reward.status.changed".equals(r.event().path("type").asString())
                && code.equals(r.event().path("data").path("rewardCode").asString())
                && "LIVE".equals(r.event().path("data").path("newStatus").asString())).event();
        assertThat(fact.path("data").path("previousStatus").asString()).isEqualTo("APPROVED");
    }

    void approvalsQueue() {
        CLOCK.set(T0);
        JsonNode rw = reward("IN_REVIEW", Map.of());
        JsonNode item = null;
        for (JsonNode i : get("/v1/approvals?status=IN_REVIEW")) {
            if (rw.path("code").asString().equals(i.path("code").asString())) {
                item = i;
            }
        }
        assertThat(item).as("premio nella coda").isNotNull();
        assertThat(item.path("entityType").asString()).isEqualTo("REWARD");
        assertThat(item.path("requiredRole").asString()).isEqualTo("LEGAL");
        assertThat(item.path("id").asString()).isEqualTo(rw.path("id").asString());
    }

    void rejectedIsEditable() {
        CLOCK.set(T0);
        String id = reward("IN_REVIEW", Map.of()).path("id").asString();
        call("POST", "/v1/rewards/" + id + "/transitions", "LEGAL:testbook", Map.of("action", "REJECT", "comment", "Da rivedere"));
        JsonNode r = rewardById(id);
        assertThat(send("PUT", "/v1/rewards/" + id, "MARKETING:testbook", Map.of("name", "Corretto", "version", r.path("version").asLong()))
                .status()).isEqualTo(200);
        assertThat(send("POST", "/v1/rewards/" + id + "/transitions", "MARKETING:testbook", Map.of("action", "SUBMIT")).status())
                .isEqualTo(200);
        assertThat(rewardById(id).path("status").asString()).isEqualTo("IN_REVIEW");
    }

    void statsLowStock() {
        CLOCK.set(T0);
        String nine = reward("LIVE", Map.of("stockTotal", 100)).path("id").asString();
        String zero = reward("LIVE", Map.of("stockTotal", 100)).path("id").asString();
        String ten = reward("LIVE", Map.of("stockTotal", 100)).path("id").asString();
        String unlimited = reward("LIVE", Map.of()).path("id").asString();
        jdbc.sql("UPDATE reward SET stock_remaining = 9 WHERE id = ?").param(nine).update();
        jdbc.sql("UPDATE reward SET stock_remaining = 0 WHERE id = ?").param(zero).update();
        jdbc.sql("UPDATE reward SET stock_remaining = 10 WHERE id = ?").param(ten).update();
        List<String> low = new ArrayList<>();
        get("/v1/rewards/stats").path("lowStock").forEach(x -> low.add(x.path("id").asString()));
        assertThat(low).contains(nine, zero).doesNotContain(ten, unlimited);
    }

    void statsByStatus() {
        CLOCK.set(T0);
        long before = get("/v1/rewards/stats").path("rewardsByStatus").path("DRAFT").asLong();
        reward("DRAFT", Map.of());
        JsonNode stats = get("/v1/rewards/stats");
        assertThat(stats.path("rewardsByStatus").path("DRAFT").asLong()).isEqualTo(before + 1);
        assertThat(stats.path("redemptionsByStatus").isObject()).isTrue();
    }
}
