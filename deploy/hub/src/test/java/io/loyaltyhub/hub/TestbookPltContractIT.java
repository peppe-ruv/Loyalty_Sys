package io.loyaltyhub.hub;

import io.loyaltyhub.common.event.JsonSchemaValidator;
import io.loyaltyhub.common.event.LhHeaders;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-PLT §CTR, §LOP e §BRT nell'hub (ADR-023/024): conformità ai contratti di <em>ogni</em> {@code type} dichiarato in
 * docs/05 (schema ed esempio nel repo, e ogni evento realmente prodotto dai flussi del PoC valido contro envelope e
 * schema, sul topic della sua famiglia, col {@code dataschema}, la {@code source} e la chiave giusti); guardia anti-ciclo
 * del ponte ai confini di {@code lhhop} (docs/05 §7); messaggi illeggibili sul bus in-process (docs/04 §5).
 *
 * <p>I flussi che producono gli eventi girano una volta, prima delle righe; un flusso che fallisce non ferma gli altri
 * (l'effetto si vede nella riga del tipo che non risulta prodotto).
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookPltContractIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TestbookPltContractIT extends TestbookPltSupportIT {

    private static final EmbeddedPostgres PG = startPg();
    private static final Path CONTRACTS = Paths.get("../../contracts/events").toAbsolutePath().normalize();
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private final JsonSchemaValidator validator = new JsonSchemaValidator();
    private final List<String> flowProblems = new ArrayList<>();
    private boolean produced;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-09-24T10:00:00Z");
        }
    }

    // ================= conformità per type =================

    @TestFactory
    @Order(1)
    Stream<DynamicTest> contracts() {
        return plt("contratti.csv", row -> {
            produceOnce();
            String type = row.get("tipo");
            String familyDotName = type.substring("io.loyaltyhub.".length());
            String family = familyDotName.substring(0, familyDotName.indexOf('.'));
            String name = familyDotName.substring(family.length() + 1);
            // 1. contratto nel repo: schema ed esempio validi (docs/05 §9)
            Path schemaFile = CONTRACTS.resolve(family).resolve(name + ".schema.json");
            Path exampleFile = CONTRACTS.resolve("examples").resolve(family + "." + name + ".json");
            assertThat(Files.exists(schemaFile)).as("schema %s", schemaFile).isTrue();
            assertThat(Files.exists(exampleFile)).as("esempio %s", exampleFile).isTrue();
            String schema = read(schemaFile);
            String envelope = read(CONTRACTS.resolve("envelope.schema.json"));
            JsonNode example = mapper.readTree(read(exampleFile));
            assertThat(validator.validate("envelope", envelope, example.toString())).as("esempio ↔ envelope").isEmpty();
            assertThat(validator.validate(type, schema, example.path("data").toString())).as("esempio ↔ schema").isEmpty();
            assertThat(example.path("type").asString()).as("type dell'esempio").isEqualTo(type);
            // 2. eventi realmente prodotti
            List<JsonNode> events = new ArrayList<>();
            List<String> topics = new ArrayList<>();
            jdbc.sql("SELECT payload::text, topic FROM insight.event_store WHERE type = ?").param(type)
                    .query((rs, i) -> {
                        events.add(mapper.readTree(rs.getString(1)));
                        topics.add(rs.getString(2));
                        return null;
                    }).list();
            if (row.is("prodotto", "no")) {
                assertThat(events).as("%s: nessun produttore nel perimetro del PoC", type).isEmpty();
                return;
            }
            assertThat(events).as("%s prodotto dai flussi del PoC (problemi dei flussi: %s)", type, flowProblems).isNotEmpty();
            String expectedTopic = switch (family) {
                case "action" -> "lh.actions.v1";
                case "effect" -> "lh.effects.v1";
                case "fact" -> "lh.facts.v1";
                default -> "lh.audit.v1";
            };
            for (int i = 0; i < events.size(); i++) {
                JsonNode e = events.get(i);
                String what = type + " " + e.path("id").asString();
                assertThat(topics.get(i)).as("topic di %s", what).isEqualTo(expectedTopic);
                assertThat(validator.validate("envelope", envelope, e.toString())).as("envelope di %s", what).isEmpty();
                assertThat(validator.validate(type, schema, e.path("data").toString())).as("data di %s", what).isEmpty();
                assertThat(e.path("dataschema").asString()).as("dataschema di %s", what)
                        .isEqualTo("urn:loyaltyhub:schema:" + familyDotName + ":1");
                assertThat(e.path("lhtenant").asString()).as("lhtenant di %s", what).isEqualTo("aurora");
                String source = e.path("source").asString();
                if (family.equals("action")) {
                    assertThat(source).as("source di %s", what).startsWith("urn:loyaltyhub:source:");
                } else {
                    assertThat(source).as("source di %s", what).startsWith("urn:loyaltyhub:service:");
                    assertThat(e.path("id").asString()).as("id ULID di %s", what).matches(ULID);
                }
                if (source.equals("urn:loyaltyhub:source:internal")) {
                    assertThat(e.path("id").asString()).as("id ULID dell'azione interna %s", what).matches(ULID);
                    assertThat(e.path("lhhop").asInt()).as("lhhop del ponte %s", what).isBetween(1, 3);
                }
                if (family.equals("audit")) {
                    assertThat(e.path("lhactor").asString("")).as("lhactor dell'audit %s", what).isNotBlank();
                    assertThat(e.path("subject").asString()).as("subject dell'audit %s", what).contains(":");
                } else if (row.is("chiave", "membro")) {
                    assertThat(e.path("subject").asString()).as("subject di %s", what).startsWith("member:");
                } else {
                    assertThat(e.path("subject").asString()).as("subject di configurazione di %s", what)
                            .matches("^[a-z]+:.+$").doesNotStartWith("member:");
                }
            }
        });
    }

    // ================= guardia anti-ciclo del ponte (docs/05 §7) =================

    @TestFactory
    @Order(2)
    Stream<DynamicTest> loopGuard() {
        return plt("loop-guard.csv", row -> {
            produceOnce();
            Member m = newMember();
            String factId = "01TBPLT" + uniqueTag().toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-Z]", "");
            String type = "io.loyaltyhub.fact." + row.get("fatto");
            Map<String, Object> data = new LinkedHashMap<>();
            if (type.endsWith("badge.awarded")) {
                data.put("badgeCode", "BDG-FIRST");
                data.put("badgeName", "Rompighiaccio");
                data.put("origin", "ACHIEVEMENT");
            } else {
                data.put("ledgerEntryId", "L-" + factId);
                data.put("currency", "PTS");
                data.put("amount", 1);
                data.put("balanceAfter", 1);
            }
            int hop = (int) row.num("lhhop");
            ObjectNode fact = mapper.createObjectNode();
            fact.put("specversion", "1.0").put("id", factId).put("source", "urn:loyaltyhub:service:gamification")
                    .put("type", type).put("subject", m.subject()).put("time", clock.instant().toString())
                    .put("datacontenttype", "application/json").put("dataschema", "urn:loyaltyhub:schema:x:1")
                    .put("lhtenant", "aurora").put("lhcorrelationid", "CORR-" + factId).put("lhhop", hop);
            fact.set("data", mapper.valueToTree(data));
            publish("lh.facts.v1", m.id(), fact.toString(), type);
            quiet();
            List<Integer> bridged = jdbc.sql("""
                            SELECT hop FROM insight.event_store WHERE causation_id = ? AND family = 'ACTION'""")
                    .param(factId).query(Integer.class).list();
            List<String> dlq = jdbc.sql("""
                            SELECT consumer || ':' || error_code || ':' || attempts FROM insight.dlq_entry WHERE event_id = ?""")
                    .param(factId).query(String.class).list();
            String got = "azioni=" + bridged + " dlq=" + dlq;
            assertThat(got).isEqualTo(row.get("atteso"));
        });
    }

    // ================= messaggi illeggibili sul bus (docs/04 §5) =================

    @TestFactory
    @Order(3)
    Stream<DynamicTest> unreadable() {
        return plt("bus-illeggibili.csv", row -> {
            produceOnce();
            String key = "MBR-TBPLT-" + uniqueTag();
            String value = row.get("valore").replace("{key}", key);
            List<String> before = strings("SELECT id FROM insight.dlq_entry");
            publish(row.get("topic"), key, value, row.blank("lhtype") ? null : row.get("lhtype"));
            quiet();
            List<String> entries = jdbc.sql("""
                            SELECT id, consumer || ':' || attempts || ':' || retryable FROM insight.dlq_entry ORDER BY consumer""")
                    .query((rs, i) -> before.contains(rs.getString(1)) ? null : rs.getString(2)).list().stream()
                    .filter(java.util.Objects::nonNull).toList();
            String consumer = row.get("consumer");
            List<String> mine = entries.stream().filter(e -> e.startsWith(consumer + ":")).toList();
            assertThat(String.join(" ", mine)).as("voci DLQ %s", entries).isEqualTo(row.get("atteso"));
        });
    }

    // ================= flussi che producono gli eventi =================

    private synchronized void produceOnce() {
        if (produced) {
            return;
        }
        produced = true;
        Instant weekday = todayAt(9, 0);
        Member a = newMember();
        // Azioni esterne: ogni tipo EVT-ACT-01…10 dalla sua fonte (seed/sources.json), dati di esempio del seed.
        flow("azioni esterne", () -> {
            Map<String, String> sourceOf = Map.of("purchase.completed", "ecommerce", "purchase.returned", "ecommerce",
                    "ebill.activated", "billing", "directdebit.activated", "billing", "selfreading.submitted", "app",
                    "app.login.daily", "app", "survey.completed", "partner", "quiz.completed", "partner",
                    "review.submitted", "ecommerce", "newsletter.subscribed", "crm");
            JsonNode types = mapper.readTree(read(Paths.get("../../seed/event-types.json").toAbsolutePath().normalize()));
            for (JsonNode t : types) {
                String code = t.path("code").asString();
                if (sourceOf.containsKey(code)) {
                    Map<String, Object> data = mapper.convertValue(t.path("sampleData"), Map.class);
                    act(sourceOf.get(code), code, a.subject(), weekday, data);
                }
            }
            quiet();
        });
        // Scenari del seed (docs/10 §8): tier-up col ponte, amico, benvenuto, digitale, messaggio velenoso.
        for (String scn : List.of("SCN-TIER-UP", "SCN-REFERRAL", "SCN-ONBOARDING", "SCN-DIGITAL", "SCN-POISON")) {
            flow(scn, () -> runScenario(scn));
        }
        // Membri: aggiornamento, profilo completo, cambio stato, segmento statico (ingresso e uscita).
        flow("membri", () -> {
            Member b = newMember();
            JsonNode view = get("/v1/members/" + b.id());
            ok(send("PATCH", "/v1/members/" + b.id(), CARE, Map.of("version", view.path("version").asLong(),
                    "city", "Torino", "birthDate", "1990-05-12", "phone", "+39 333 000 9999")), 200);
            ok(send("POST", "/v1/members/" + b.id() + "/status", CARE, Map.of("status", "BLOCKED", "reason", "prova")), 200);
            ok(send("POST", "/v1/members/" + b.id() + "/status", CARE, Map.of("status", "ACTIVE", "reason", "prova")), 200);
            ok(send("PUT", "/v1/segments/SEG-VIP-EVENT/members", MARKETING,
                    Map.of("memberIds", List.of("MBR-000005", "MBR-000004", b.id()))), 200);
            quiet();
            ok(send("PUT", "/v1/segments/SEG-VIP-EVENT/members", MARKETING,
                    Map.of("memberIds", List.of("MBR-000005", "MBR-000004"))), 200);
            quiet();
        });
        // Campagna con tutti gli effetti non monetari e punti in attesa, poi rilascio (F-CMP-03, F-WAL-02).
        flow("campagna effetti", () -> {
            String code = "CMP-TBPLT-" + uniqueTag().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            JsonNode c = ok(send("POST", "/v1/campaigns", MARKETING, Map.ofEntries(
                    Map.entry("code", code), Map.entry("name", "Contratti"), Map.entry("memberDescription", "prova"),
                    Map.entry("priority", 100), Map.entry("triggerActionTypes", List.of("newsletter.subscribed")),
                    Map.entry("audience", Map.of("all", true)), Map.entry("conditions", Map.of("op", "all", "rules", List.of())),
                    Map.entry("effects", List.of(
                            Map.of("type", "GRANT_POINTS", "currency", "PTS", "mode", "FIXED", "value", 7, "pendingDays", 2),
                            Map.of("type", "AWARD_BADGE", "badgeCode", "BDG-STREAK"),
                            Map.of("type", "SEND_MESSAGE", "templateCode", "MSG-COUPON-GIFT"))),
                    Map.entry("limits", Map.of()), Map.entry("schedule", Map.of("startAt", "2026-01-01T00:00:00Z")))), 201);
            ok(send("POST", "/v1/campaigns/" + c.path("id").asString() + "/transitions", MARKETING, Map.of("action", "PUBLISH")), 200);
            Member d = newMember();
            act("crm", "newsletter.subscribed", d.subject(), weekday, Map.of());
            quiet();
            ok(send("POST", "/v1/demo/jobs/release-pending?asOf=" + clock.instant().plusSeconds(4 * 86_400), ADMIN, null), 200);
            ok(send("POST", "/v1/campaigns/CMP-SURVEY/transitions", MARKETING, Map.of("action", "PAUSE")), 200);
            ok(send("POST", "/v1/campaigns/CMP-SURVEY/transitions", MARKETING, Map.of("action", "RESUME")), 200);
            quiet();
        });
        // Wallet: rettifica, scadenze e preavvisi con la macchina del tempo (US-E11-08).
        flow("wallet", () -> {
            credit(a.id(), 50);
            ok(send("POST", "/v1/demo/jobs/expiry-warnings", ADMIN, null), 200);
            ok(send("POST", "/v1/demo/jobs/expire-points?asOf=" + clock.instant().plusSeconds(31L * 86_400), ADMIN, null), 200);
            quiet();
        });
        // Premi: saga riuscita con coupon, rifiutata per saldo, annullata con rimborso; uso del coupon; stato del premio.
        flow("premi", () -> {
            Member r = newMember();
            credit(r.id(), 2000);
            Resp ok = redeem(r.id(), "RWD-COFFEE-5", false);
            quiet();
            String couponCode = jdbc.sql("SELECT code FROM reward.coupon WHERE member_id = ? LIMIT 1").param(r.id())
                    .query(String.class).optional().orElse(null);
            if (couponCode != null) {
                ok(send("POST", "/v1/coupons/" + couponCode + "/use", CARE, Map.of()), 200);
            }
            Resp physical = redeem(r.id(), "RWD-BORRACCIA", true);
            quiet();
            ok(send("POST", "/v1/redemptions/" + physical.body().path("redemptionId").asString() + "/cancel", CARE,
                    Map.of("reason", "Articolo danneggiato")), 200);
            Member poor = newMember();
            redeem(poor.id(), "RWD-SHOP-25", false);
            ok(send("POST", "/v1/rewards/RWD-GIFT-50/transitions", "LEGAL:elena.legal", Map.of("action", "APPROVE")), 200);
            quiet();
            assertThat(ok.status()).isEqualTo(202);
        });
        // Gioco: vincita a punti e a coupon con istanti piantati; stato di un concorso.
        flow("gioco", () -> {
            onlyPlantedInstants();
            Member g = newMember();
            plantAndPlay(g.id(), "PTS-50");
            plantAndPlay(newMember().id(), "COFFEE");
            ok(send("POST", "/v1/contests/IW-NATALE/transitions", "LEGAL:elena.legal", Map.of("action", "APPROVE")), 200);
            quiet();
        });
        // Contenuti: pubblicazione diretta (policy CONTENT «mai», docs/06 §7).
        flow("contenuti", () -> {
            String code = "CNT-TBPLT-" + uniqueTag().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]", "");
            JsonNode c = ok(send("POST", "/v1/contents", MARKETING, Map.of("code", code, "kind", "CARD",
                    "placement", "HOME_GRID", "title", "Contratti")), 201);
            ok(send("POST", "/v1/contents/" + c.path("id").asString() + "/transitions", MARKETING, Map.of("action", "PUBLISH")), 200);
            quiet();
        });
        // Chiusura dell'edizione corrente (F-TIER-05): livelli mantenuti o scesi, edizione chiusa.
        flow("edizione", () -> {
            String edition = jdbc.sql("SELECT code FROM wallet.edition WHERE status = 'ACTIVE' LIMIT 1").query(String.class).single();
            ok(send("POST", "/v1/editions/" + edition + "/close?dryRun=false", ADMIN, null), 200, 202);
            quiet();
        });
        quiet();
    }

    private void flow(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            flowProblems.add(name + ": " + t.getMessage());
        }
    }

    private void publish(String topic, String key, String value, String type) {
        RecordHeaders headers = new RecordHeaders();
        if (type != null) {
            headers.add(new RecordHeader(LhHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8)));
        }
        bus.publish(new ProducerRecord<>(topic, null, key, value, headers));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
