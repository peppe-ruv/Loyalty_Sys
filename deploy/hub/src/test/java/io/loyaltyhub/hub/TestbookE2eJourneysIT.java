package io.loyaltyhub.hub;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testbook TB-E2E — percorsi del membro tra i servizi del deployable consolidato, con membri nuovi a ogni caso:
 * iscrizione e abbinamento automatico (REG), salita di livello (TIER), porta un amico (REF), cliente digitale (DIG),
 * vincita istantanea (IW), anonimizzazione (ANO). Oracolo: docs/03, docs/05, docs/10 §4 e §8, docs/servizi/*,
 * docs/17 E10 (vedi docs/testbook/TB-E2E-percorsi.md). «Oggi» è giovedì 24/09/2026 (orologio fisso che avanza).
 */
@SpringBootTest(
        classes = {HubApplication.class, TestbookE2eJourneysIT.Today.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=hub", "loyaltyhub.outbox.relay-interval-ms=50"})
@ActiveProfiles({"demo", "inproc"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookE2eJourneysIT extends TestbookE2eSupportIT {

    private static final EmbeddedPostgres PG = startPg();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        datasource(registry, PG);
    }

    @AfterAll
    void stop() throws IOException {
        PG.close();
    }

    /**
     * Orologio del caso. Non è {@code @TestConfiguration}: la scansione di {@code io.loyaltyhub.hub} la porterebbe nei
     * contesti delle altre classi; la registra solo {@code SpringBootTest.classes} (metodi {@code @Bean} «lite»).
     */
    static class Today {
        @Bean
        @Primary
        Clock testbookClock() {
            return startingAt("2026-09-24T10:00:00Z");
        }
    }

    /** Giovedì 24/09 alle 09:00 di Roma: feriale, dentro la finestra di ingresso, prima di «adesso». */
    Instant weekday() {
        return todayAt(9, 0);
    }

    // ---------- REG: iscrizione e abbinamento automatico ----------

    @TestFactory
    Stream<DynamicTest> registrazione() {
        return rows("registrazione.csv", this::registrazione);
    }

    private void registrazione(Row row) {
        switch (row.get("caso")) {
            case "iscrizione", "con-codice" -> {
                Member referrer = row.is("caso", "con-codice") ? newMember() : null;
                Member m = newMember(referrer == null ? null : referrer.referralCode());
                String cid = rootCorrelation(m.id(), "FACT", "member.registered");
                List<Ev> chain = assertChain(cid, row.get("catena"));
                assertTrace(cid, "COMPLETE");
                Ev bridged = ofKey(chain, "ACTION:member.registered").get(0);
                assertThat(bridged.data().path("channel").asString()).as("campo omonimo copiato dal ponte").isEqualTo("PORTAL");
                assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
                assertThat(ledger(m.id(), cid)).containsExactly("EARN:PTS:100:CMP-WELCOME");
                assertWelcomeState(m, row.num("ptsAttesi"));
                if (referrer != null) {
                    assertThat(jdbc.sql("SELECT referred_by FROM member.member WHERE id = ?").param(m.id()).query(String.class).single())
                            .isEqualTo(referrer.id());
                    JsonNode ref = get("/v1/portal/members/" + referrer.id() + "/referral");
                    assertThat(ref.path("invited").size()).isEqualTo(1);
                    assertThat(ref.path("completedCount").asInt()).isZero();
                    assertThat(pts(referrer.id())).as("nessun premio all'invitante prima dell'acquisto").isEqualTo(100);
                    assertThat(count("SELECT count(*) FROM insight.event_store WHERE short_type = 'referral.completed' AND member_id IN (?, ?)",
                            m.id(), referrer.id())).isZero();
                }
            }
            case "abbinamento", "abbinamento-maiuscole" -> {
                String first = uniqueName();
                String email = "tb.e2e." + uniqueTag() + "@example.org";
                String subjectEmail = row.is("caso", "abbinamento") ? email : email.toUpperCase(Locale.ROOT);
                String eventId = "tb-e2e-" + uniqueTag();
                Resp parked = postEvent(eventId, "ecommerce", "purchase.completed", "email:" + subjectEmail, weekday(),
                        Map.of("orderId", "ORD-TB-" + uniqueTag(), "amount", 200, "currency", "EUR", "channel", "ONLINE"));
                assertThat(parked.body().path("status").asString()).as(parked.body().toString()).isEqualTo("UNMATCHED");
                quiet();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE event_id = ?", eventId))
                        .as("un evento non abbinato non va sul topic").isZero();
                Resp r = register(first, email, null);
                assertThat(r.status()).isEqualTo(201);
                quiet();
                String id = r.body().path("id").asString();
                var inbound = jdbc.sql("SELECT status, member_id, resolution FROM ingestion.inbound_event WHERE event_id = ?")
                        .param(eventId).query((rs, i) -> List.of(rs.getString(1), rs.getString(2), String.valueOf(rs.getString(3))))
                        .single();
                assertThat(inbound).containsExactly("ACCEPTED", id, "AUTO_MATCH");
                String cid = correlationOf(eventId);
                assertChain(cid, row.get("catena"));
                assertTrace(cid, "COMPLETE");
                assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
                assertThat(ledger(id, cid)).containsExactly("EARN:PTS:100:CMP-BADGE-BONUS", "EARN:PTS:200:CMP-PURCHASE-BASE",
                        "EARN:STS:200:CMP-PURCHASE-BASE");
                assertThat(pts(id)).isEqualTo(row.num("ptsAttesi"));
                assertThat(badges(id)).containsExactly("BDG-FIRST");
            }
            case "abbinamento-due" -> {
                String email = "tb.e2e." + uniqueTag() + "@example.org";
                List<String> ids = new ArrayList<>();
                for (int amount : new int[]{100, 50}) {
                    String eventId = "tb-e2e-" + uniqueTag();
                    ids.add(eventId);
                    Resp parked = postEvent(eventId, "ecommerce", "purchase.completed", "email:" + email, weekday(),
                            Map.of("orderId", "ORD-TB-" + uniqueTag(), "amount", amount, "currency", "EUR"));
                    assertThat(parked.body().path("status").asString()).isEqualTo("UNMATCHED");
                }
                quiet();
                Resp r = register(uniqueName(), email, null);
                assertThat(r.status()).isEqualTo(201);
                quiet();
                String id = r.body().path("id").asString();
                Map<String, Integer> union = new HashMap<>();
                List<String> messages = new ArrayList<>();
                for (String eventId : ids) {
                    String cid = correlationOf(eventId);
                    List<Ev> chain = chain(cid);
                    assertTree(chain);
                    counts(chain).forEach((k, n) -> union.merge(k, n, Integer::sum));
                    messages.addAll(inbox(cid));
                }
                assertThat(new java.util.TreeMap<>(union)).isEqualTo(parseChain(row.get("catena")));
                assertThat(messages.stream().sorted().toList()).isEqualTo(sorted(row.get("messaggi")));
                assertThat(pts(id)).isEqualTo(row.num("ptsAttesi"));
                assertThat(badges(id)).containsExactly("BDG-FIRST");
            }
            case "altro-indirizzo" -> {
                String eventId = "tb-e2e-" + uniqueTag();
                Resp parked = postEvent(eventId, "ecommerce", "purchase.completed",
                        "email:tb.altro." + uniqueTag() + "@example.org", weekday(),
                        Map.of("orderId", "ORD-TB-" + uniqueTag(), "amount", 80, "currency", "EUR"));
                assertThat(parked.body().path("status").asString()).isEqualTo("UNMATCHED");
                Member m = newMember();
                assertThat(jdbc.sql("SELECT status FROM ingestion.inbound_event WHERE event_id = ?").param(eventId)
                        .query(String.class).single()).isEqualTo("UNMATCHED");
                assertThat(pts(m.id())).isEqualTo(row.num("ptsAttesi"));
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE event_id = ?", eventId)).isZero();
            }
            case "codice-inesistente", "codice-non-attivo" -> {
                String code = "ZZZZ9999";
                if (row.is("caso", "codice-non-attivo")) {
                    Member blocked = newMember();
                    ok(send("POST", "/v1/members/" + blocked.id() + "/status", CARE,
                            Map.of("status", "BLOCKED", "reason", "Sospeso per il caso di testbook")), 200);
                    quiet();
                    code = blocked.referralCode();
                }
                String email = "tb.e2e." + uniqueTag() + "@example.org";
                long membersBefore = count("SELECT count(*) FROM member.member");
                Resp r = register(uniqueName(), email, code);
                assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
                assertThat(r.code()).isEqualTo("REFERRAL_CODE_INVALID");
                quiet();
                assertThat(count("SELECT count(*) FROM member.member")).isEqualTo(membersBefore);
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE short_type = 'member.registered' AND payload::text ILIKE ?",
                        "%" + email + "%")).isZero();
            }
            case "email-duplicata" -> {
                Member m = newMember();
                long membersBefore = count("SELECT count(*) FROM member.member");
                long registeredBefore = count("SELECT count(*) FROM insight.event_store WHERE short_type = 'member.registered'");
                Resp r = register(uniqueName(), m.email(), null);
                // TESTBOOK: ambiguo, vedi TB-E2E-REG-009 (codice e stato dell'errore sul campo non fissati)
                assertThat(r.status()).as(r.body().toString()).isBetween(400, 499);
                quiet();
                assertThat(count("SELECT count(*) FROM member.member")).isEqualTo(membersBefore);
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE short_type = 'member.registered'"))
                        .isEqualTo(registeredBefore);
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
    }

    /** Stato dopo il benvenuto in ogni servizio che l'iscrizione tocca (docs/05 §8, member §4). */
    private void assertWelcomeState(Member m, long expectedPts) {
        JsonNode member = get("/v1/members/" + m.id());
        assertThat(member.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(m.referralCode()).as("codice amico di 8 caratteri A-Z2-9 (docs/03 §2)").matches("[A-Z2-9]{8}");
        assertThat(member.path("nickname").asString()).as("nome + iniziale del cognome (member §5)").isEqualTo(m.firstName() + " P.");
        assertThat(pts(m.id())).isEqualTo(expectedPts);
        assertThat(sts(m.id())).isZero();
        assertThat(tier(m.id())).isEqualTo("BASE");
        assertThat(jdbc.sql("SELECT email_lower || '|' || status FROM ingestion.member_index WHERE member_id = ?").param(m.id())
                .query(String.class).single()).isEqualTo(m.email().toLowerCase(Locale.ROOT) + "|ACTIVE");
        assertThat(jdbc.sql("SELECT status || '|' || tier_code FROM campaign.member_snapshot WHERE member_id = ?").param(m.id())
                .query(String.class).single()).isEqualTo("ACTIVE|BASE");
        assertThat(jdbc.sql("SELECT status FROM reward.reward_member_snapshot WHERE member_id = ?").param(m.id())
                .query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT nickname || '|' || status FROM gamification.gamification_member_snapshot WHERE member_id = ?")
                .param(m.id()).query(String.class).single()).isEqualTo(m.firstName() + " P.|ACTIVE");
        assertThat(jdbc.sql("SELECT first_name || '|' || status FROM engagement.engagement_member_snapshot WHERE member_id = ?")
                .param(m.id()).query(String.class).single()).isEqualTo(m.firstName() + "|ACTIVE");
        assertThat(jdbc.sql("SELECT balance_pts FROM member.member_projection WHERE member_id = ?").param(m.id())
                .query(Long.class).single()).isEqualTo(expectedPts);
    }

    // ---------- TIER: salita di livello ----------

    @TestFactory
    Stream<DynamicTest> livelli() {
        return rows("livelli.csv", this::livelli);
    }

    private void livelli(Row row) {
        Member m = newMember();
        String cid = null;
        for (String amount : row.get("importi").split(" ")) {
            cid = purchase(m.subject(), Double.parseDouble(amount), weekday());
            quiet();
        }
        String tier = row.get("livello");
        List<Ev> chain = assertChain(cid, row.get("catena"));
        assertTrace(cid, "COMPLETE");
        assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
        assertThat(ledger(m.id(), cid)).isEqualTo(sorted(row.get("movimenti")));
        assertThat(pts(m.id())).isEqualTo(row.num("ptsAttesi"));
        assertThat(sts(m.id())).isEqualTo(row.num("stsAttesi"));
        assertThat(tier(m.id())).isEqualTo(tier);
        List<Ev> upgrades = ofKey(chain, "FACT:tier.upgraded");
        if (!upgrades.isEmpty()) {
            assertThat(upgrades.get(0).data().path("previousTier").asString()).isEqualTo("BASE");
            assertThat(upgrades.get(0).data().path("newTier").asString()).isEqualTo(tier);
            Ev bridged = ofKey(chain, "ACTION:tier.upgraded").get(0);
            assertThat(bridged.causation()).as("l'azione del ponte nasce dal fatto tier.upgraded").isEqualTo(upgrades.get(0).id());
            assertThat(bridged.data().path("newTier").asString()).isEqualTo(tier);
        }
        // Il livello arriva a ogni servizio che ne tiene uno snapshot (docs/05 §8).
        assertThat(jdbc.sql("SELECT tier_code FROM member.member_projection WHERE member_id = ?").param(m.id()).query(String.class).single())
                .isEqualTo(tier);
        assertThat(jdbc.sql("SELECT tier_code FROM campaign.member_snapshot WHERE member_id = ?").param(m.id()).query(String.class).single())
                .isEqualTo(tier);
        assertThat(jdbc.sql("SELECT tier_code FROM reward.reward_member_snapshot WHERE member_id = ?").param(m.id()).query(String.class).single())
                .isEqualTo(tier);
        // TESTBOOK: ambiguo, vedi TB-E2E-TIER-001 (livello nello snapshot di engagement prima di un fatto tier.*: nullo = BASE)
        assertThat(jdbc.sql("SELECT coalesce(tier_code, 'BASE') FROM engagement.engagement_member_snapshot WHERE member_id = ?")
                .param(m.id()).query(String.class).single()).isEqualTo(tier);
        assertThat(count("SELECT count(*) FROM wallet.tier_history WHERE member_id = ? AND kind = 'UPGRADE'", m.id()))
                .as("una sola salita").isEqualTo(tier.equals("BASE") ? 0 : 1);
    }

    // ---------- REF: porta un amico ----------

    @TestFactory
    Stream<DynamicTest> amico() {
        return rows("amico.csv", this::amico);
    }

    private void amico(Row row) {
        Member referrer = newMember();
        String cid;
        Member invitee;
        switch (row.get("caso")) {
            case "primo-acquisto" -> {
                invitee = newMember(referrer.referralCode());
                cid = purchase(invitee.subject(), 60, weekday());
                quiet();
            }
            case "secondo-acquisto" -> {
                invitee = newMember(referrer.referralCode());
                purchase(invitee.subject(), 60, weekday());
                quiet();
                cid = purchase(invitee.subject(), 40, weekday());
                quiet();
            }
            case "prima-non-qualificante" -> {
                invitee = newMember(referrer.referralCode());
                String login = act("app", "app.login.daily", invitee.subject(), weekday(), Map.of("platform", "WEB"));
                quiet();
                assertChain(login, "A:app.login.daily=1 F:campaign.evaluated=1 E:points.grant=1 F:wallet.points.earned=1 F:message.delivered=1");
                assertThat(get("/v1/portal/members/" + referrer.id() + "/referral").path("completedCount").asInt())
                        .as("l'accesso non qualifica il referral").isZero();
                cid = purchase(invitee.subject(), 60, weekday());
                quiet();
            }
            case "undicesimo" -> {
                invitee = null;
                cid = null;
                for (int i = 1; i <= 11; i++) {
                    invitee = newMember(referrer.referralCode());
                    cid = purchase(invitee.subject(), 60, weekday());
                    quiet();
                }
                assertThat(ledgerAll(referrer.id()).stream().filter(l -> l.startsWith("EARN:PTS:") && l.endsWith(":CMP-REFERRAL-REFERRER")))
                        .as("10 premi all'invitante nell'edizione").hasSize(10);
                assertThat(ledgerAll(referrer.id())).contains("EARN:PTS:200:CMP-TIER-UP-BONUS");
                Ev referrerEval = chain(cid).stream().filter(e -> e.key().equals("FACT:campaign.evaluated")
                        && referrer.id().equals(e.memberId())).findFirst().orElseThrow();
                assertThat(skipped(referrerEval)).containsEntry("CMP-REFERRAL-REFERRER", "LIMIT");
                assertThat(tier(referrer.id())).isEqualTo("SILVER");
            }
            case "invitante-bloccato" -> {
                invitee = newMember(referrer.referralCode());
                ok(send("POST", "/v1/members/" + referrer.id() + "/status", CARE,
                        Map.of("status", "BLOCKED", "reason", "Sospeso per il caso di testbook")), 200);
                quiet();
                cid = purchase(invitee.subject(), 60, weekday());
                quiet();
                Ev referrerAction = chain(cid).stream().filter(e -> e.key().equals("ACTION:referral.completed")
                        && referrer.id().equals(e.memberId())).findFirst().orElseThrow();
                assertThat(jdbc.sql("SELECT outcome FROM campaign.evaluation_log WHERE action_id = ?").param(referrerAction.id())
                        .query(String.class).single()).as("docs/03 §3.5 passo 1").isEqualTo("NO_MEMBER");
                assertThat(chain(cid).stream().filter(e -> e.key().equals("EFFECT:points.grant"))
                        .map(Ev::memberId).distinct().toList()).containsExactly(invitee.id());
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
        if (row.is("caso", "invitante-bloccato")) {
            assertChainIncludes(cid, row.get("catena"));
        } else {
            assertChain(cid, row.get("catena"));
            assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
        }
        assertTrace(cid, "COMPLETE");
        List<Ev> chain = chain(cid);
        List<Ev> referrals = ofKey(chain, "FACT:referral.completed");
        if (!referrals.isEmpty()) {
            Map<String, String> roles = new HashMap<>();
            referrals.forEach(e -> roles.put(e.memberId(), e.data().path("role").asString()));
            assertThat(roles).as("ruoli opposti sui due membri (docs/03 §8)")
                    .containsEntry(invitee.id(), "REFEREE").containsEntry(referrer.id(), "REFERRER");
        }
        assertThat(pts(referrer.id())).as("PTS dell'invitante").isEqualTo(row.num("ptsInvitante"));
        assertThat(sts(referrer.id())).as("STS dell'invitante").isEqualTo(row.num("stsInvitante"));
        assertThat(pts(invitee.id())).as("PTS dell'invitato").isEqualTo(row.num("ptsInvitato"));
        long completed = row.is("caso", "undicesimo") ? 11 : 1;
        assertThat(get("/v1/portal/members/" + referrer.id() + "/referral").path("completedCount").asLong()).isEqualTo(completed);
    }


    // ---------- DIG: cliente che diventa digitale ----------

    @TestFactory
    Stream<DynamicTest> digitale() {
        return rows("digitale.csv", this::digitale);
    }

    private void digitale(Row row) {
        Member m = newMember();
        if (row.is("caso", "segmenti")) {
            refreshSegments();
            assertThat(segments(m.id())).as("prima: senza etichetta ebill").contains("SEG-NOT-EBILL").doesNotContain("SEG-DIGITAL");
            assertThat(homeGrid(m.id())).contains("CNT-EBILL").doesNotContain("CNT-DIGITAL-THANKS");
        }
        String ebill = act("billing", "ebill.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag()));
        quiet();
        String direct = act("billing", "directdebit.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag()));
        quiet();
        switch (row.get("caso")) {
            case "attivazioni" -> {
                assertChain(ebill, row.get("catenaBolletta"));
                assertChain(direct, row.get("catenaDomiciliazione"));
                assertTrace(direct, "COMPLETE");
                assertThat(ledger(m.id(), ebill)).containsExactly("EARN:PTS:300:CMP-EBILL", "EARN:STS:150:CMP-EBILL");
                assertThat(ledger(m.id(), direct)).containsExactly("EARN:PTS:100:CMP-BADGE-BONUS",
                        "EARN:PTS:400:CMP-DIRECT-DEBIT", "EARN:STS:200:CMP-DIRECT-DEBIT");
                assertThat(inbox(direct)).containsExactly("MSG-BADGE", "MSG-POINTS-EARNED", "MSG-POINTS-EARNED");
                // SPEC-GAP Q-80: le etichette arrivano anche agli snapshot di campaign (docs/05 §8 member.* → snapshot).
                assertThat(jdbc.sql("SELECT array_to_string(labels, ' ') FROM member.member WHERE id = ?").param(m.id())
                        .query(String.class).single()).contains("ebill").contains("directdebit");
                assertThat(jdbc.sql("SELECT array_to_string(labels, ' ') FROM campaign.member_snapshot WHERE member_id = ?")
                        .param(m.id()).query(String.class).single()).contains("ebill").contains("directdebit");
            }
            case "segmenti" -> {
                refreshSegments();
                assertThat(segments(m.id())).contains("SEG-DIGITAL").doesNotContain("SEG-NOT-EBILL");
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type = 'member.segment.entered'"
                        + " AND payload->'data'->>'segmentCode' = 'SEG-DIGITAL'", m.id())).isEqualTo(1);
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type = 'member.segment.left'"
                        + " AND payload->'data'->>'segmentCode' = 'SEG-NOT-EBILL'", m.id())).isEqualTo(1);
                assertThat(jdbc.sql("SELECT array_to_string(segments, ' ') FROM engagement.engagement_member_snapshot WHERE member_id = ?")
                        .param(m.id()).query(String.class).single()).contains("SEG-DIGITAL").doesNotContain("SEG-NOT-EBILL");
                assertThat(homeGrid(m.id())).contains("CNT-DIGITAL-THANKS").doesNotContain("CNT-EBILL");
            }
            case "ripetizione" -> {
                Map<String, List<String>> before = memberState(m.id());
                String ebill2 = act("billing", "ebill.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag()));
                quiet();
                String direct2 = act("billing", "directdebit.activated", m.subject(), weekday(), Map.of("contractId", "CTR-" + uniqueTag()));
                quiet();
                assertThat(skipped(ofKey(assertChain(ebill2, row.get("catenaBolletta")), "FACT:campaign.evaluated").get(0)))
                        .containsEntry("CMP-EBILL", "LIMIT");
                assertThat(skipped(ofKey(assertChain(direct2, row.get("catenaDomiciliazione")), "FACT:campaign.evaluated").get(0)))
                        .containsEntry("CMP-DIRECT-DEBIT", "LIMIT");
                Map<String, List<String>> after = memberState(m.id());
                assertThat(after.get("wallet.ledger_entry")).isEqualTo(before.get("wallet.ledger_entry"));
                assertThat(after.get("gamification.member_badge")).isEqualTo(before.get("gamification.member_badge"));
                assertThat(after.get("member.member")).as("etichette invariate, nessun member.updated").isEqualTo(before.get("member.member"));
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
        assertThat(badges(m.id())).containsExactly("BDG-DIGITAL");
        assertThat(pts(m.id())).isEqualTo(row.num("ptsAttesi"));
        assertThat(sts(m.id())).isEqualTo(row.num("stsAttesi"));
    }

    void refreshSegments() {
        ok(send("POST", "/v1/demo/jobs/refresh-segments", ADMIN, null), 200);
        quiet();
    }

    List<String> segments(String memberId) {
        return jdbc.sql("SELECT s.code FROM member.segment_member sm JOIN member.segment s ON s.id = sm.segment_id WHERE sm.member_id = ?")
                .param(memberId).query(String.class).list();
    }

    String homeGrid(String memberId) {
        return send("GET", "/v1/portal/content?memberId=" + memberId + "&placement=HOME_GRID", null, null).body().toString();
    }

    // ---------- IW: vincita istantanea ----------

    @TestFactory
    Stream<DynamicTest> vincita() {
        return rows("vincita.csv", this::vincita);
    }

    private void vincita(Row row) {
        onlyPlantedInstants();
        Member m = newMember();
        if (row.is("caso", "punti-silver")) {
            purchase(m.subject(), 1000, weekday());
            quiet();
            assertThat(tier(m.id())).isEqualTo("SILVER");
        }
        long before = pts(m.id());
        switch (row.get("caso")) {
            case "punti", "punti-silver", "coupon", "fisico" -> {
                ok(send("POST", "/v1/demo/contests/IW-AUTUNNO/plant-instant", ADMIN, Map.of("prizeCode", row.get("premio"))), 200, 201);
                JsonNode play = play(m.id()).body();
                assertThat(play.path("outcome").asString()).isEqualTo("WIN");
                assertThat(play.path("prize").path("code").asString()).isEqualTo(row.get("premio"));
                quiet();
                String cid = play.path("correlationId").asString();
                List<Ev> chain = assertChain(cid, row.get("catena"));
                assertTrace(cid, "COMPLETE");
                assertThat(inbox(cid)).isEqualTo(sorted(row.get("messaggi")));
                Ev won = ofKey(chain, "ACTION:instantwin.won").get(0);
                assertThat(won.data().path("prizeCode").asString()).isEqualTo(row.get("premio"));
                switch (row.get("caso")) {
                    case "coupon" -> {
                        assertThat(won.data().path("prizeType").asString()).isEqualTo("COUPON");
                        JsonNode coupon = null;
                        for (JsonNode c : get("/v1/portal/coupons?memberId=" + m.id())) {
                            coupon = c;
                        }
                        assertThat(coupon).as("coupon della vincita").isNotNull();
                        assertThat(coupon.path("code").asString()).matches("CAF-[A-Z2-9]{4}-[A-Z2-9]{4}");
                        assertThat(coupon.path("status").asString()).isEqualTo("ISSUED");
                        assertThat(coupon.path("origin").asString()).isEqualTo("CAMPAIGN");
                        assertThat(ofKey(chain, "FACT:coupon.issued").get(0).data().path("origin").asString()).isEqualTo("CAMPAIGN");
                    }
                    case "fisico" -> {
                        assertThat(won.data().path("prizeType").asString()).isEqualTo("PHYSICAL");
                        String playId = play.path("playId").asString();
                        assertThat(delivery(playId)).isEqualTo("PENDING");
                        Resp r = send("POST", "/v1/plays/" + playId + "/delivery", CARE, Map.of("status", "DELIVERED",
                                "note", "Spedito con corriere"));
                        assertThat(r.status()).as(r.body().toString()).isIn(200, 204);
                        assertThat(delivery(playId)).isEqualTo("DELIVERED");
                        Ev evaluated = ofKey(chain, "FACT:campaign.evaluated").get(0);
                        assertThat(matched(evaluated)).isEmpty();
                        assertThat(skipped(evaluated)).containsEntry("CMP-IW-PRIZE-POINTS", "CONDITION")
                                .containsEntry("CMP-IW-PRIZE-COUPON", "CONDITION");
                    }
                    default -> {
                        assertThat(won.data().path("prizeType").asString()).isEqualTo("POINTS");
                        assertThat(ledger(m.id(), cid)).containsExactly("EARN:PTS:" + row.num("deltaPts") + ":CMP-IW-PRIZE-POINTS");
                    }
                }
            }
            case "perde" -> {
                String survey = act("partner", "survey.completed", m.subject(), weekday(), Map.of("surveyId", "SRV-" + uniqueTag(), "score", 80));
                quiet();
                assertChain(survey, "A:survey.completed=1 F:campaign.evaluated=1 E:points.grant=1 E:plays.grant=1 "
                        + "F:wallet.points.earned=1 F:contest.plays.granted=1 F:message.delivered=1");
                int[] remaining = {1, 0};
                for (int expected : remaining) {
                    JsonNode play = play(m.id()).body();
                    assertThat(play.path("outcome").asString()).isEqualTo("LOSE");
                    assertThat(play.path("playsAvailable").asInt()).isEqualTo(expected);
                    quiet();
                    assertChain(play.path("correlationId").asString(), row.get("catena"));
                }
            }
            case "senza-giocate" -> {
                assertThat(play(m.id()).body().path("outcome").asString()).isEqualTo("LOSE");
                quiet();
                long facts = count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type LIKE 'contest.%'", m.id());
                Resp r = play(m.id());
                assertThat(r.status()).isEqualTo(422);
                assertThat(r.code()).isEqualTo("NO_PLAYS_AVAILABLE");
                quiet();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type LIKE 'contest.%'", m.id()))
                        .isEqualTo(facts);
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
        assertThat(pts(m.id()) - before).isEqualTo(row.num("deltaPts"));
    }


    // ---------- ANO: anonimizzazione ----------

    @TestFactory
    Stream<DynamicTest> oblio() {
        return rows("oblio.csv", this::oblio);
    }

    private void oblio(Row row) {
        Member m = newMember();
        String purchase = purchase(m.subject(), 120, weekday());
        quiet();
        List<String> ledgerBefore = ledgerAll(m.id());
        if (row.is("caso", "ruolo-care")) {
            Resp r = send("POST", "/v1/members/" + m.id() + "/anonymize", CARE, Map.of("confirm", m.id()));
            assertThat(r.status()).isEqualTo(403);
            quiet();
            assertThat(get("/v1/members/" + m.id()).path("status").asString()).isEqualTo("ACTIVE");
            assertThat(get("/v1/members/" + m.id()).path("email").asString()).isEqualTo(m.email());
            assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type = 'member.status.changed'", m.id()))
                    .isZero();
            return;
        }
        JsonNode anonymized = ok(send("POST", "/v1/members/" + m.id() + "/anonymize", ADMIN, Map.of("confirm", m.id())), 200);
        assertThat(anonymized.path("status").asString()).isEqualTo("ANONYMIZED");
        quiet();
        switch (row.get("caso")) {
            case "propagazione" -> {
                String statusCid = rootCorrelation(m.id(), "FACT", "member.status.changed");
                List<Ev> status = chain(statusCid);
                assertThat(ofKey(status, "FACT:member.status.changed").get(0).data().path("newStatus").asString()).isEqualTo("ANONYMIZED");
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE member_id = ? AND short_type = 'member.updated'", m.id()))
                        .isGreaterThanOrEqualTo(1);
                // docs/03 §2, Q-120: nome → «Membro anonimo» (nickname), e-mail/telefono → null; id, movimenti e statistiche conservati.
                JsonNode member = get("/v1/members/" + m.id());
                assertThat(member.path("nickname").asString()).isEqualTo("Membro anonimo");
                assertThat(member.path("email").isNull() || member.path("email").isMissingNode()).isTrue();
                assertThat(member.path("firstName").isNull() || member.path("firstName").isMissingNode()).isTrue();
                assertThat(jdbc.sql("SELECT status || '|' || coalesce(email_lower, '-') FROM ingestion.member_index WHERE member_id = ?")
                        .param(m.id()).query(String.class).single()).isEqualTo("ANONYMIZED|-");
                assertThat(jdbc.sql("SELECT status FROM campaign.member_snapshot WHERE member_id = ?").param(m.id())
                        .query(String.class).single()).isEqualTo("ANONYMIZED");
                assertThat(jdbc.sql("SELECT member_status FROM wallet.member_tier WHERE member_id = ?").param(m.id())
                        .query(String.class).single()).isEqualTo("ANONYMIZED");
                assertThat(jdbc.sql("SELECT status || '|' || coalesce(first_name, '-') || '|' || coalesce(last_name, '-')"
                        + " FROM reward.reward_member_snapshot WHERE member_id = ?").param(m.id()).query(String.class).single())
                        .isEqualTo("ANONYMIZED|-|-");
                assertThat(jdbc.sql("SELECT nickname || '|' || status FROM gamification.gamification_member_snapshot WHERE member_id = ?")
                        .param(m.id()).query(String.class).single()).isEqualTo("Membro anonimo|ANONYMIZED");
                assertThat(jdbc.sql("SELECT coalesce(first_name, '-') || '|' || status FROM engagement.engagement_member_snapshot"
                        + " WHERE member_id = ?").param(m.id()).query(String.class).single()).isEqualTo("-|ANONYMIZED");
                assertThat(count("SELECT count(*) FROM engagement.inbox_message WHERE member_id = ? AND (title || ' ' || body) ILIKE ?",
                        m.id(), "%" + m.firstName() + "%")).as("messaggi senza il nome").isZero();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE payload::text ILIKE ?", "%" + m.email() + "%"))
                        .as("event store senza l'e-mail (Q-126)").isZero();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE payload::text ILIKE ?", "%" + m.firstName() + "%"))
                        .as("event store senza il nome (Q-126)").isZero();
                assertThat(ledgerAll(m.id())).as("movimenti conservati").isEqualTo(ledgerBefore);
                assertThat(chain(purchase)).as("tracciati conservati").isNotEmpty();
                String ranking = get("/v1/leaderboards/LDB-MONTH-PTS/ranking?limit=100").toString();
                assertThat(ranking).doesNotContain(m.id());
            }
            case "azione-membro", "azione-email" -> {
                String eventId = "tb-e2e-" + uniqueTag();
                String subject = row.is("caso", "azione-membro") ? m.subject() : "email:" + m.email();
                Resp r = postEvent(eventId, "ecommerce", "purchase.completed", subject, weekday(),
                        Map.of("orderId", "ORD-TB-" + uniqueTag(), "amount", 30, "currency", "EUR"));
                assertThat(r.status()).isEqualTo(202);
                String[] expected = row.get("atteso").split(":");
                assertThat(r.body().path("status").asString()).isEqualTo(expected[0]);
                if (expected.length > 1) {
                    assertThat(r.body().path("rejectCode").asString()).isEqualTo(expected[1]);
                }
                quiet();
                assertThat(count("SELECT count(*) FROM insight.event_store WHERE event_id = ?", eventId)).as("niente sul topic").isZero();
                assertThat(ledgerAll(m.id())).isEqualTo(ledgerBefore);
            }
            case "richiesta", "rettifica", "giocata" -> {
                Resp r = switch (row.get("caso")) {
                    case "richiesta" -> send("POST", "/v1/portal/redemptions", null, Map.of("memberId", m.id(), "rewardCode", "RWD-COFFEE-5"));
                    case "rettifica" -> send("POST", "/v1/wallets/" + m.id() + "/adjustments", CARE, Map.of("currency", "PTS",
                            "direction", "CREDIT", "amount", 10, "reason", "GOODWILL", "note", "Rettifica dopo l'anonimizzazione"));
                    default -> play(m.id());
                };
                String[] expected = row.get("atteso").split(":");
                assertThat(r.status()).as(r.body().toString()).isEqualTo(Integer.parseInt(expected[0]));
                assertThat(r.code()).isEqualTo(expected[1]);
                quiet();
                assertThat(ledgerAll(m.id())).isEqualTo(ledgerBefore);
            }
            default -> throw new IllegalArgumentException(row.get("caso"));
        }
    }
}
