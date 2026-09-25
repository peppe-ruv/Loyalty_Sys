package io.loyaltyhub.gamification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-GAM — configurazione del concorso ({@code EDT}), generazione e visibilità degli istanti ({@code INS}),
 * vincitori e statistiche ({@code RPT}), fine concorso ({@code END}) (docs/testbook/TB-GAM-gioco.md §8–§11).
 * Oracolo: docs/03 §3.6, §6, docs/06 §2, §3, docs/08 §2 e BO-14, gamification §2, §3, §5, §7, Q-56, Q-112, Q-113.
 */
class TestbookGamContestIT extends TestbookGamBase {

    private static final Instant START = T0.plus(Duration.ofDays(1));
    private static final Instant END = T0.plus(Duration.ofDays(9));

    // ---------- creazione ----------

    // TESTBOOK: ambiguo, vedi TB-GAM-EDT-008 (codice minuscolo normalizzato) e TB-GAM-EDT-018 (distribuzione di default)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/concorso-creazione.csv", numLinesToSkip = 1)
    void creazione(String id, String desc, String role, String field, String value, int expHttp, String expField) {
        CLOCK.set(T0);
        Map<String, Object> body = contestBody(contestCode(id), START, END);
        if (opt(field) != null) {
            Object v = switch (value) {
                case "-" -> null;
                case "@40" -> "IW-" + "X".repeat(37);
                case "@41" -> "IW-" + "X".repeat(38);
                case "@DUP" -> {
                    String dup = contestCode(id);
                    createContest(contestBody(dup, START, END));
                    yield dup;
                }
                case "=start" -> START.toString();
                case "start-1s" -> START.minusSeconds(1).toString();
                case "start+1ms" -> plus(START, 1).toString();
                default -> field.startsWith("max") || field.equals("seed") ? (Object) Long.parseLong(value) : value;
            };
            body.put(field, v);
        }
        Resp r = call("POST", "/v1/contests", actor(role), body);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 201) {
            assertThat(r.body().path("status").asString()).isEqualTo("DRAFT");
            if (opt(expField) != null) {
                String[] kv = expField.split("=", 2);
                JsonNode actual = r.body().path(kv[0]);
                if ("*".equals(kv[1])) assertThat(actual.isNull() || actual.isMissingNode()).isFalse();
                else assertThat(actual.asString()).isEqualTo(kv[1]);
            }
        }
    }

    // ---------- modifica e campi bloccati ----------

    // TESTBOOK: ambiguo, vedi TB-GAM-EDT-062 e TB-GAM-EDT-063 (PAUSED come LIVE: nessuna decisione per i concorsi)
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/concorso-modifica.csv", numLinesToSkip = 1)
    void modifica(String id, String desc, String status, String field, int expHttp, String expInstants) {
        CLOCK.set(T0);
        String contest = contestIn(contestCode(id), status, START, END, null, null);
        JsonNode before = get("/v1/contests/" + contest);
        List<String> instantsBefore = instantIds(contest);
        Object value = switch (field) {
            case "name" -> "Nome nuovo";
            case "description" -> "Descrizione nuova";
            case "rulesText" -> "Regolamento cambiato";
            case "endAt" -> END.plus(Duration.ofDays(1)).toString();
            case "startAt" -> START.minus(Duration.ofHours(1)).toString();
            case "prizes" -> List.of(prize("PTS-10", "POINTS", 10L, null, 5), prize("PHY-NEW", "PHYSICAL", null, null, 1));
            case "distribution" -> "BUSINESS_HOURS";
            case "seed" -> before.path("seed").asLong() + 1;
            case "mechanic" -> "SCRATCH";
            case "freePlayDaily" -> false;
            case "maxPlaysPerMemberPerDay" -> 3;
            case "maxWinsPerMember" -> 2;
            default -> throw new IllegalArgumentException(field);
        };
        Resp r = call("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of(field, value));
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        JsonNode after = get("/v1/contests/" + contest);
        if (expHttp == 200) {
            if ("prizes".equals(field)) assertThat(after.path("prizes").size()).isEqualTo(2);
            else if (field.endsWith("At")) assertThat(Instant.parse(after.path(field).asString())).isEqualTo(Instant.parse((String) value));
            else assertThat(after.path(field).asString()).isEqualTo(String.valueOf(value));
        } else {
            assertThat(r.code()).isEqualTo("CONTEST_LIVE_LOCKED");
            assertThat(after.path(field).toString()).isEqualTo(before.path(field).toString());
        }
        if ("KEPT".equals(expInstants)) {
            assertThat(instantIds(contest)).isEqualTo(instantsBefore);
            assertThat(after.path("instantsGeneratedAt").isNull() || after.path("instantsGeneratedAt").isMissingNode()).isFalse();
        } else {
            assertThat(instantIds(contest)).isEmpty();
            assertThat(after.path("instantsGeneratedAt").isNull() || after.path("instantsGeneratedAt").isMissingNode()).isTrue();
        }
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-EDT-040 (immutabilità del codice decisa solo per le campagne, Q-51)
    @Test
    @DisplayName("[TB-GAM-EDT-040] cambio del codice: 409 CODE_IMMUTABLE")
    void codeImmutable() {
        String contest = createContest(contestBody(contestCode("TB-GAM-EDT-040"), START, END));
        Resp r = call("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("code", "IW-ALTRO-CODICE"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CODE_IMMUTABLE");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-041] stesso codice in minuscolo nel corpo: modifica accettata")
    void sameCodeOtherCase() {
        String code = contestCode("TB-GAM-EDT-041");
        String contest = createContest(contestBody(code, START, END));
        JsonNode r = ok("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("code", code.toLowerCase(), "name", "Rinominato"), 200);
        assertThat(r.path("code").asString()).isEqualTo(code);
        assertThat(r.path("name").asString()).isEqualTo("Rinominato");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-042] versione superata: 409 VERSION_CONFLICT (Q-112)")
    void staleVersion() {
        String contest = createContest(contestBody(contestCode("TB-GAM-EDT-042"), START, END));
        long version = get("/v1/contests/" + contest).path("version").asLong();
        ok("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("name", "Prima modifica", "version", version), 200);
        Resp r = call("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("name", "Seconda modifica", "version", version));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(get("/v1/contests/" + contest).path("name").asString()).isEqualTo("Prima modifica");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-043] versione corrente: modifica accettata (Q-112)")
    void currentVersion() {
        String contest = createContest(contestBody(contestCode("TB-GAM-EDT-043"), START, END));
        long version = get("/v1/contests/" + contest).path("version").asLong();
        ok("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("name", "Con versione", "version", version), 200);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-EDT-044 (ENDED non modificabile: scelta analoga a Q-51, non registrata per i concorsi)
    @Test
    @DisplayName("[TB-GAM-EDT-044] concorso ENDED: 409 CONTEST_NOT_EDITABLE")
    void endedNotEditable() {
        notEditable("TB-GAM-EDT-044", "ENDED");
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-EDT-045
    @Test
    @DisplayName("[TB-GAM-EDT-045] concorso ARCHIVED: 409 CONTEST_NOT_EDITABLE")
    void archivedNotEditable() {
        notEditable("TB-GAM-EDT-045", "ARCHIVED");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-046] LEGAL non modifica un concorso")
    void legalCannotEdit() {
        String contest = createContest(contestBody(contestCode("TB-GAM-EDT-046"), START, END));
        assertThat(call("PUT", "/v1/contests/" + contest, actor("LEGAL"), Map.of("name", "No")).status()).isEqualTo(403);
    }

    // ---------- duplicazione (Q-113) ----------

    @Test
    @DisplayName("[TB-GAM-EDT-080] Duplica un concorso ENDED: <code>-COPY-1 in DRAFT, premi pieni, istanti da generare")
    void duplicate() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-EDT-080");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), null);
        matureOne(contest, null, T0.minusSeconds(1));
        play(code, member("ACTIVE"));
        setStatus(contest, "ENDED");
        JsonNode copy = ok("POST", "/v1/contests/" + contest + "/duplicate", actor("MARKETING"), null, 201);
        assertThat(copy.path("code").asString()).isEqualTo(code + "-COPY-1");
        assertThat(copy.path("status").asString()).isEqualTo("DRAFT");
        assertThat(copy.path("prizes").get(0).path("quantityRemaining").asInt()).isEqualTo(5);
        assertThat(copy.path("instantsGeneratedAt").isNull() || copy.path("instantsGeneratedAt").isMissingNode()).isTrue();
        assertThat(copy.path("instants").path("total").asLong()).isZero();
        assertThat(copy.path("seed").asLong()).isNotEqualTo(get("/v1/contests/" + contest).path("seed").asLong());
    }

    @Test
    @DisplayName("[TB-GAM-EDT-081] seconda copia dello stesso concorso: <code>-COPY-2")
    void duplicateTwice() {
        String code = contestCode("TB-GAM-EDT-081");
        String contest = createContest(contestBody(code, START, END));
        ok("POST", "/v1/contests/" + contest + "/duplicate", actor("MARKETING"), null, 201);
        JsonNode second = ok("POST", "/v1/contests/" + contest + "/duplicate", actor("MARKETING"), null, 201);
        assertThat(second.path("code").asString()).isEqualTo(code + "-COPY-2");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-082] copia di un codice di 40 caratteri: troncato a 40 con il suffisso")
    void duplicateLongCode() {
        String code = "IW-DUP-" + "L".repeat(33);
        String contest = createContest(contestBody(code, START, END));
        JsonNode copy = ok("POST", "/v1/contests/" + contest + "/duplicate", actor("MARKETING"), null, 201);
        assertThat(copy.path("code").asString()).hasSize(40).endsWith("-COPY-1").startsWith("IW-DUP-");
    }

    @Test
    @DisplayName("[TB-GAM-EDT-083] LEGAL non duplica")
    void legalCannotDuplicate() {
        String contest = createContest(contestBody(contestCode("TB-GAM-EDT-083"), START, END));
        assertThat(call("POST", "/v1/contests/" + contest + "/duplicate", actor("LEGAL"), null).status()).isEqualTo(403);
    }

    // ---------- generazione degli istanti ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/istanti-generazione.csv", numLinesToSkip = 1)
    void generazione(String id, String desc, String status, String role, int expHttp) {
        CLOCK.set(T0);
        Map<String, Object> body = contestBody(contestCode(id), START, END);
        body.put("prizes", List.of(prize("PTS-A", "POINTS", 10L, null, 3), prize("PHY-B", "PHYSICAL", null, null, 1),
                prize("CPN-C", "COUPON", null, "RWD-COFFEE", 5)));
        String contest = createContest(body);
        generate(contest);
        jdbc.sql("UPDATE prize SET quantity_remaining = 0 WHERE contest_id = ?").param(contest).update();
        setStatus(contest, status);
        List<String> before = instantIds(contest);
        Resp r = call("POST", "/v1/contests/" + contest + "/instants/generate", actor(role), Map.of());
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 200) {
            assertThat(r.body().path("instants").asInt()).isEqualTo(9);
            assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ?", contest)).isEqualTo(9);
            assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND (instant_at < ? OR instant_at >= ?)",
                    contest, ts(START), ts(END))).isZero();
            assertThat(instantIds(contest)).doesNotContainAnyElementsOf(before);
            assertThat(count("SELECT sum(quantity_remaining) FROM prize WHERE contest_id = ?", contest)).isEqualTo(9);
        } else {
            assertThat(instantIds(contest)).isEqualTo(before);
        }
    }

    @Test
    @DisplayName("[TB-GAM-INS-013] seme 42 due volte sulla copia di IW-AUTUNNO: stesso elenco, 355 istanti")
    void seedTwiceSameInstants() {
        String copy = ok("POST", "/v1/contests/IW-AUTUNNO/duplicate", actor("MARKETING"), null, 201).path("id").asString();
        JsonNode first = ok("POST", "/v1/contests/" + copy + "/instants/generate", actor("MARKETING"), Map.of("seed", 42), 200);
        String a = instantFingerprint(copy);
        ok("POST", "/v1/contests/" + copy + "/instants/generate", actor("MARKETING"), Map.of("seed", 42), 200);
        assertThat(first.path("instants").asInt()).isEqualTo(355);
        assertThat(instantFingerprint(copy)).isEqualTo(a);
    }

    @Test
    @DisplayName("[TB-GAM-INS-014] rigenerare riparte da zero: istanti tutti OPEN, stesso numero, quantità ripristinate")
    void regenerateFromScratch() {
        CLOCK.set(T0);
        String contest = contestIn(contestCode("TB-GAM-INS-014"), "DRAFT", START, END, null, null);
        jdbc.sql("UPDATE winning_instant SET status = 'CLAIMED', planted = true WHERE contest_id = ?").param(contest).update();
        jdbc.sql("UPDATE prize SET quantity_remaining = 1 WHERE contest_id = ?").param(contest).update();
        generate(contest);
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ?", contest)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'OPEN' AND NOT planted", contest)).isEqualTo(5);
        assertThat(remaining(contest, "PTS-10")).isEqualTo(5);
    }

    @Test
    @DisplayName("[TB-GAM-INS-015] seme indicato alla generazione: salvato sul concorso")
    void seedStored() {
        String contest = createContest(contestBody(contestCode("TB-GAM-INS-015"), START, END));
        JsonNode r = ok("POST", "/v1/contests/" + contest + "/instants/generate", actor("MARKETING"), Map.of("seed", 4242), 200);
        assertThat(r.path("seed").asLong()).isEqualTo(4242);
        assertThat(get("/v1/contests/" + contest).path("seed").asLong()).isEqualTo(4242);
    }

    @Test
    @DisplayName("[TB-GAM-INS-016] seme omesso: si usa quello salvato e gli istanti si riproducono")
    void seedOmittedReproducible() {
        String contest = createContest(contestBody(contestCode("TB-GAM-INS-016"), START, END));
        ok("POST", "/v1/contests/" + contest + "/instants/generate", actor("MARKETING"), Map.of("seed", 77), 200);
        String a = instantFingerprint(contest);
        JsonNode r = ok("POST", "/v1/contests/" + contest + "/instants/generate", actor("MARKETING"), null, 200);
        assertThat(r.path("seed").asLong()).isEqualTo(77);
        assertThat(instantFingerprint(contest)).isEqualTo(a);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-INS-017 (concorso senza premi: nessuna fonte)
    @Test
    @DisplayName("[TB-GAM-INS-017] concorso senza premi: 422 CONTEST_INVALID")
    void generateWithoutPrizes() {
        Map<String, Object> body = contestBody(contestCode("TB-GAM-INS-017"), START, END);
        body.put("prizes", List.of());
        String contest = createContest(body);
        Resp r = call("POST", "/v1/contests/" + contest + "/instants/generate", actor("MARKETING"), Map.of());
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("CONTEST_INVALID");
    }

    @Test
    @DisplayName("[TB-GAM-INS-018] BUSINESS_HOURS a cavallo del cambio d'ora di ottobre: tutti tra 08:00 e 22:00 di Roma")
    void businessHoursInDb() {
        Map<String, Object> body = contestBody(contestCode("TB-GAM-INS-018"), Instant.parse("2026-10-23T22:00:00Z"),
                Instant.parse("2026-10-27T23:00:00Z"));
        body.put("distribution", "BUSINESS_HOURS");
        body.put("prizes", List.of(prize("PHY-X", "PHYSICAL", null, null, 300)));
        String contest = createContest(body);
        generate(contest);
        List<Instant> all = jdbc.sql("SELECT instant_at FROM winning_instant WHERE contest_id = ?").param(contest)
                .query((rs, n) -> rs.getTimestamp(1).toInstant()).list();
        assertThat(all).hasSize(300);
        assertThat(all).allSatisfy(i -> assertThat(ZonedDateTime.ofInstant(i, ROME).getHour()).isBetween(8, 21));
        assertThat(all).anySatisfy(i -> assertThat(LocalDate.ofInstant(i, ROME)).isEqualTo(LocalDate.parse("2026-10-25")));
    }

    @Test
    @DisplayName("[TB-GAM-INS-019] la generazione scrive una voce di audit con numero di istanti e seme")
    void generationAudit() {
        String code = contestCode("TB-GAM-INS-019");
        String contest = createContest(contestBody(code, START, END));
        ok("POST", "/v1/contests/" + contest + "/instants/generate", actor("MARKETING"), Map.of("seed", 9), 200);
        List<JsonNode> audit = audits("CONTEST:" + code).stream()
                .filter(a -> a.path("data").path("after").has("instants")).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().path("data").path("after").path("instants").asInt()).isEqualTo(5);
        assertThat(audit.getFirst().path("data").path("after").path("seed").asLong()).isEqualTo(9);
    }

    @Test
    @DisplayName("[TB-GAM-INS-020] un istante per unità di premio: 3, 1 e 5 istanti per tre premi")
    void onePerUnit() {
        Map<String, Object> body = contestBody(contestCode("TB-GAM-INS-020"), START, END);
        body.put("prizes", List.of(prize("PTS-A", "POINTS", 10L, null, 3), prize("PHY-B", "PHYSICAL", null, null, 1),
                prize("CPN-C", "COUPON", null, "RWD-COFFEE", 5)));
        String contest = createContest(body);
        generate(contest);
        List<String> counts = jdbc.sql("""
                        SELECT p.code || '=' || count(w.id) FROM prize p LEFT JOIN winning_instant w ON w.prize_id = p.id
                        WHERE p.contest_id = ? GROUP BY p.code ORDER BY p.code
                        """).param(contest).query(String.class).list();
        assertThat(counts).containsExactly("CPN-C=5", "PHY-B=1", "PTS-A=3");
    }

    // ---------- visibilità ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/istanti-visibilita.csv", numLinesToSkip = 1)
    void visibilita(String id, String desc, String endpoint, String role, int expHttp) {
        String contest = contestIn(contestCode(id), "DRAFT", START, END, null, null);
        String path = "/v1/contests/" + contest + ("instants".equals(endpoint) ? "/instants" : "/instants/histogram");
        Resp r = call("GET", path, actor(role), null);
        assertThat(r.status()).as(r.text()).isEqualTo(expHttp);
        if (expHttp == 200 && "instants".equals(endpoint)) {
            assertThat(r.body().path("items").size()).isEqualTo(5);
            assertThat(r.body().path("page").path("totalItems").asLong()).isEqualTo(5);
        } else if (expHttp == 200) {
            long total = 0;
            for (JsonNode d : r.body().path("days")) total += d.path("total").asLong();
            assertThat(total).isEqualTo(5);
            assertThat(r.body().toString()).doesNotContain("instantAt");
        }
    }

    @Test
    @DisplayName("[TB-GAM-INS-032] filtro per stato: solo gli istanti CLAIMED")
    void filterByStatus() {
        CLOCK.set(T0);
        String code = contestCode("TB-GAM-INS-032");
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), END, T0.plus(Duration.ofDays(2)), null);
        String won = matureOne(contest, null, T0.minusSeconds(1));
        play(code, member("ACTIVE"));
        JsonNode r = ok("GET", "/v1/contests/" + contest + "/instants?status=CLAIMED", actor("LEGAL"), null, 200);
        assertThat(r.path("items").size()).isEqualTo(1);
        assertThat(r.path("items").get(0).path("id").asString()).isEqualTo(won);
    }

    @Test
    @DisplayName("[TB-GAM-INS-033] filtro per premio: solo gli istanti di quel premio")
    void filterByPrize() {
        Map<String, Object> body = contestBody(contestCode("TB-GAM-INS-033"), START, END);
        body.put("prizes", List.of(prize("PTS-A", "POINTS", 10L, null, 3), prize("PHY-B", "PHYSICAL", null, null, 2)));
        String contest = createContest(body);
        generate(contest);
        JsonNode r = ok("GET", "/v1/contests/" + contest + "/instants?prizeId=" + prizeId(contest, "PHY-B"), actor("ADMIN"), null, 200);
        assertThat(r.path("items").size()).isEqualTo(2);
        r.path("items").forEach(i -> assertThat(i.path("prizeCode").asString()).isEqualTo("PHY-B"));
    }

    @Test
    @DisplayName("[TB-GAM-INS-034] istogramma per giorno di Roma: 23:59:59 e 00:00:00 in due giorni diversi")
    void histogramRomeDays() {
        String contest = contestIn(contestCode("TB-GAM-INS-034"), "DRAFT", START, END, null, null);
        List<String> ids = instantIds(contest);
        jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE id = ?").params(ts(Instant.parse("2026-03-12T22:59:59Z")), ids.get(0)).update();
        jdbc.sql("UPDATE winning_instant SET instant_at = ? WHERE id <> ? AND contest_id = ?")
                .params(ts(Instant.parse("2026-03-12T23:00:00Z")), ids.get(0), contest).update();
        JsonNode days = ok("GET", "/v1/contests/" + contest + "/instants/histogram", actor("MARKETING"), null, 200).path("days");
        assertThat(days.size()).isEqualTo(2);
        assertThat(days.get(0).path("day").asString()).isEqualTo("2026-03-12");
        assertThat(days.get(0).path("total").asLong()).isEqualTo(1);
        assertThat(days.get(1).path("day").asString()).isEqualTo("2026-03-13");
        assertThat(days.get(1).path("total").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("[TB-GAM-INS-035] istogramma con istanti il 29 febbraio 2028")
    void histogramLeapDay() {
        String contest = contestIn(contestCode("TB-GAM-INS-035"), "DRAFT", START, END, Instant.parse("2028-02-29T12:00:00Z"), null);
        JsonNode days = ok("GET", "/v1/contests/" + contest + "/instants/histogram", actor("MARKETING"), null, 200).path("days");
        assertThat(days.size()).isEqualTo(1);
        assertThat(days.get(0).path("day").asString()).isEqualTo("2028-02-29");
        assertThat(days.get(0).path("open").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("[TB-GAM-INS-036] pagina degli istanti con size 500: ridotta a 100 (docs/06 §2)")
    void pageSizeCapped() {
        Map<String, Object> body = contestBody(contestCode("TB-GAM-INS-036"), START, END);
        body.put("prizes", List.of(prize("PHY-X", "PHYSICAL", null, null, 150)));
        String contest = createContest(body);
        generate(contest);
        JsonNode r = ok("GET", "/v1/contests/" + contest + "/instants?size=500", actor("ADMIN"), null, 200);
        assertThat(r.path("items").size()).isEqualTo(100);
        assertThat(r.path("page").path("size").asInt()).isEqualTo(100);
        assertThat(r.path("page").path("totalItems").asLong()).isEqualTo(150);
    }

    // ---------- vincitori, export, statistiche ----------

    @Test
    @DisplayName("[TB-GAM-RPT-001] vincitori: solo le giocate WIN con membro, nickname, premio e stato consegna")
    void winners() {
        String[] ctx = reportContest("TB-GAM-RPT-001");
        JsonNode winners = get("/v1/contests/" + ctx[0] + "/winners");
        assertThat(winners.size()).isEqualTo(1);
        JsonNode w = winners.get(0);
        assertThat(w.path("memberId").asString()).isEqualTo(ctx[1]);
        assertThat(w.path("nickname").asString()).isEqualTo("Socio " + ctx[1].substring(4));
        assertThat(w.path("prizeCode").asString()).isEqualTo("PHY-W");
        assertThat(w.path("prizeType").asString()).isEqualTo("PHYSICAL");
        assertThat(w.path("deliveryStatus").asString()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("[TB-GAM-RPT-002] export CSV dei vincitori: intestazione e le stesse righe dell'elenco")
    void winnersCsv() {
        String[] ctx = reportContest("TB-GAM-RPT-002");
        Resp r = call("GET", "/v1/contests/" + ctx[0] + "/winners.csv", actor("LEGAL"), null);
        assertThat(r.status()).isEqualTo(200);
        String[] lines = r.text().strip().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0]).startsWith("playId,memberId,");
        assertThat(lines[1]).contains(ctx[1]).contains("PHY-W").contains("PENDING");
    }

    @Test
    @DisplayName("[TB-GAM-RPT-003] statistiche: giocate, vincite, tasso, premi residui, serie giornaliera")
    void stats() {
        String[] ctx = reportContest("TB-GAM-RPT-003");
        JsonNode s = get("/v1/contests/" + ctx[0] + "/stats");
        assertThat(s.path("plays").asLong()).isEqualTo(2);
        assertThat(s.path("wins").asLong()).isEqualTo(1);
        assertThat(s.path("winRate").asDouble()).isEqualTo(50.0);
        assertThat(s.path("prizesTotal").asInt()).isEqualTo(3);
        assertThat(s.path("prizesRemaining").asInt()).isEqualTo(2);
        assertThat(s.path("daily").size()).isEqualTo(1);
        assertThat(s.path("daily").get(0).path("plays").asLong()).isEqualTo(2);
        assertThat(s.path("daily").get(0).path("wins").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("[TB-GAM-RPT-004] elenco dei concorsi: giocate, vincite e premi residui")
    void listCounters() {
        String[] ctx = reportContest("TB-GAM-RPT-004");
        JsonNode c = null;
        for (JsonNode x : get("/v1/contests")) if (ctx[0].equals(x.path("id").asString())) c = x;
        assertThat(c).isNotNull();
        assertThat(c.path("plays").asLong()).isEqualTo(2);
        assertThat(c.path("wins").asLong()).isEqualTo(1);
        assertThat(c.path("prizesRemaining").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("[TB-GAM-RPT-005] riepilogo del membro per la Scheda 360°: endpoint assente (Q-167)")
    void memberSummary() {
        // Q-167: GET /v1/members/{id}/gamification non esiste; la scheda di BO-03 degrada con lo stato vuoto.
        String[] ctx = reportContest("TB-GAM-RPT-005");
        Resp r = call("GET", "/v1/members/" + ctx[1] + "/gamification", actor("CARE"), null);
        assertThat(r.status()).as(r.text()).isEqualTo(404);
    }

    // ---------- fine concorso ----------

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gam/fine-concorso.csv", numLinesToSkip = 1)
    void fineConcorso(String id, String desc, String status, long endOffsetMs, String expStatus) {
        Instant asOf = Instant.parse("2020-01-01T12:00:00Z").plus(Duration.ofDays(Integer.parseInt(id.substring(id.length() - 3))));
        String contest = contestIn(contestCode(id), status, asOf.minus(Duration.ofDays(10)), plus(asOf, endOffsetMs), null, null);
        ok("POST", "/v1/demo/jobs/close-contests?asOf=" + asOf, actor("ADMIN"), null, 200);
        assertThat(status(contest)).isEqualTo(expStatus);
    }

    @Test
    @DisplayName("[TB-GAM-END-007] chiusura: OPEN → VOID, CLAIMED invariati, fatto LIVE → ENDED e audit del job")
    void closeEffects() {
        Instant asOf = Instant.parse("2020-03-01T12:00:00Z");
        String code = contestCode("TB-GAM-END-007");
        String contest = contestIn(code, "LIVE", asOf.minus(Duration.ofDays(10)), asOf.minusSeconds(60),
                asOf.minus(Duration.ofDays(1)), null);
        CLOCK.set(asOf.minus(Duration.ofDays(1)));
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("WIN");
        CLOCK.reset();
        JsonNode r = ok("POST", "/v1/demo/jobs/close-contests?asOf=" + asOf, actor("ADMIN"), null, 200);
        assertThat(r.path("voided").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(status(contest)).isEqualTo("ENDED");
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'VOID'", contest)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM winning_instant WHERE contest_id = ? AND status = 'CLAIMED'", contest)).isEqualTo(1);
        List<JsonNode> facts = outbox(FACT + "contest.status.changed", "contest:" + code);
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().path("data").path("previousStatus").asString()).isEqualTo("LIVE");
        assertThat(facts.getFirst().path("data").path("newStatus").asString()).isEqualTo("ENDED");
        assertThat(audits("CONTEST:" + code).stream().filter(a -> "JOB".equals(a.path("data").path("action").asString())
                && a.path("data").path("after").path("voided").asInt() == 4)).hasSize(1);
    }

    // TESTBOOK: ambiguo, vedi TB-GAM-END-008 (asOf come data: formato non specificato)
    @Test
    @DisplayName("[TB-GAM-END-008] asOf come data: fine di quel giorno a Roma (23:59:59 chiuso, 00:00 del giorno dopo no)")
    void asOfDate() {
        String a = contestIn(contestCode("TB-GAM-END-008"), "LIVE", Instant.parse("2020-02-01T00:00:00Z"),
                Instant.parse("2020-02-10T22:59:59Z"), null, null);
        String b = contestIn(contestCode("TB-GAM-END-008"), "LIVE", Instant.parse("2020-02-01T00:00:00Z"),
                Instant.parse("2020-02-10T23:00:00Z"), null, null);
        ok("POST", "/v1/demo/jobs/close-contests?asOf=2020-02-10", actor("ADMIN"), null, 200);
        assertThat(status(a)).isEqualTo("ENDED");
        assertThat(status(b)).isEqualTo("LIVE");
        setStatus(b, "ENDED");
    }

    @Test
    @DisplayName("[TB-GAM-END-009] MARKETING non lancia il job di fine concorso")
    void closeRole() {
        assertThat(call("POST", "/v1/demo/jobs/close-contests?asOf=2019-01-01T00:00:00Z", actor("MARKETING"), null).status())
                .isEqualTo(403);
    }

    // ---------- supporto ----------

    private List<String> instantIds(String contest) {
        return jdbc.sql("SELECT id FROM winning_instant WHERE contest_id = ? ORDER BY id").param(contest).query(String.class).list();
    }

    private String instantFingerprint(String contest) {
        List<String> rows = new ArrayList<>();
        jdbc.sql("""
                        SELECT p.code, w.instant_at FROM winning_instant w JOIN prize p ON p.id = w.prize_id
                        WHERE w.contest_id = ? ORDER BY w.instant_at, p.code
                        """).param(contest)
                .query((rs, n) -> rows.add(rs.getString(1) + "@" + rs.getTimestamp(2).toInstant()))
                .list();
        return String.join(",", rows);
    }

    private void notEditable(String rowId, String status) {
        String contest = contestIn(contestCode(rowId), status, START, END, null, null);
        Resp r = call("PUT", "/v1/contests/" + contest, actor("MARKETING"), Map.of("name", "Nuovo"));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.code()).isEqualTo("CONTEST_NOT_EDITABLE");
    }

    /** Concorso con una vincita PHYSICAL e una perdita: {id, membro vincitore}. */
    private String[] reportContest(String rowId) {
        CLOCK.set(T0);
        String code = contestCode(rowId);
        String contest = contestIn(code, "LIVE", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(1)),
                T0.plus(Duration.ofHours(5)), Map.of("prizes", List.of(prize("PHY-W", "PHYSICAL", null, null, 3))));
        matureOne(contest, null, T0.minusSeconds(1));
        String winner = member("ACTIVE");
        assertThat(play(code, winner).body().path("outcome").asString()).isEqualTo("WIN");
        assertThat(play(code, member("ACTIVE")).body().path("outcome").asString()).isEqualTo("LOSE");
        CLOCK.reset();
        return new String[]{contest, winner};
    }
}
