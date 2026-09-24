package io.loyaltyhub.member.demo;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Codes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.member.application.SegmentRefresher;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
import io.loyaltyhub.member.domain.ProfileRules;
import io.loyaltyhub.member.domain.Segment;
import io.loyaltyhub.member.infra.MemberProjectionRepository;
import io.loyaltyhub.member.infra.MemberRepository;
import io.loyaltyhub.member.infra.MemberStatsRepository;
import io.loyaltyhub.member.infra.SegmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Carica i 12 membri canonici (docs/10 §2, docs/servizi/member-service.md §6) da {@code seed/members.json}:
 * anagrafica (etichette comprese), proiezione saldi/tier, statistiche calcolate da {@code seed/activity-history.json}
 * e i segmenti di {@code seed/segments.json}, poi ricalcola le appartenenze (docs §5: "dopo il reset"). Attivo col
 * profilo {@code demo}, idempotente, ripetibile via {@code POST /v1/demo/reset} ({@link DemoResettable}). I codici invito
 * sono riproducibili (seme fisso), così il reset demo è deterministico (docs/10 §1.3).
 */
@Component
@Profile("demo")
public class MemberSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(MemberSeeder.class);
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    private final SeedLoader seed;
    private final MemberRepository members;
    private final MemberProjectionRepository projections;
    private final MemberStatsRepository stats;
    private final SegmentRepository segments;
    private final SegmentRefresher refresher;
    private final Clock clock;
    private final Duration reannounceDelay;

    public MemberSeeder(SeedLoader seed, MemberRepository members, MemberProjectionRepository projections,
                        MemberStatsRepository stats, SegmentRepository segments, SegmentRefresher refresher, Clock clock,
                        @Value("${loyaltyhub.member.segments.reannounce-delay-ms:15000}") long reannounceDelayMs) {
        this.seed = seed;
        this.members = members;
        this.projections = projections;
        this.stats = stats;
        this.segments = segments;
        this.refresher = refresher;
        this.clock = clock;
        this.reannounceDelay = Duration.ofMillis(Math.max(reannounceDelayMs, 0));
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "member";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        // Appartenenze di prima: chi non ci sarà più riceve un `left` (gli snapshot degli altri servizi le conoscono).
        Map<String, Set<String>> before = segments.allMembershipsByCode();

        // Ripristino pulito e idempotente (docs/10 §1.3): prima i figli (FK), poi l'anagrafica.
        segments.deleteAll();
        stats.deleteAll();
        projections.deleteAll();
        members.deleteAll();

        Random rnd = new Random(20240101L); // seme fisso: codici invito riproducibili
        for (JsonNode m : seed.readTree("members.json")) {
            String id = m.path("id").asString();
            MemberStatus status = MemberStatus.valueOf(m.path("status").asString("ACTIVE"));
            String story = text(m.get("story"));
            String avatarSeed = text(m.get("avatarSeed"));
            String attributes = story != null ? "{\"story\":" + jsonString(story) + "}" : "{}";

            String birth = text(m.get("birthDate"));
            LocalDate birthDate = birth != null ? LocalDate.parse(birth) : null;
            String consents = m.has("consents") ? m.get("consents").toString() : "{}";
            // Data d'iscrizione dal seed (docs/10 §2: Anna "iscritta ieri"); senza, adesso.
            Instant registeredAt = m.hasNonNull("registeredAt") ? SeedDates.resolve(m.get("registeredAt").asString(), clock) : clock.instant();
            Member member = new Member(
                    id, text(m.get("externalId")), text(m.get("firstName")), text(m.get("lastName")),
                    text(m.get("nickname")), text(m.get("email")), text(m.get("phone")), birthDate, null,
                    text(m.get("city")), status, "IMPORT", registeredAt, Codes.random(8, rnd), text(m.get("referredBy")),
                    null, consents, attributes, strings(m.get("labels")), avatarSeed, null, 0);
            // Profilo già completo nei seed (docs/10 §2: incompleti solo Anna ed Elisa): nessun fatto da riemettere.
            if (ProfileRules.missingFields(member).isEmpty()) {
                member = ProfileRules.withCompletedAt(member, registeredAt);
            }
            members.insert(member);

            projections.upsert(id, m.path("tier").asString("BASE"),
                    m.path("sts").asLong(0), m.path("points").asLong(0), 0, m.path("points").asLong(0));
            stats.resetTo(id);
        }
        int actions = seedActivity();
        int seeded = seedSegments();

        // Ricalcolo dopo il reset (docs §5), con ri-annuncio di tutte le appartenenze (SPEC-GAP: Q-81).
        List<SegmentRefresher.Outcome> outcomes = refresher.refreshAll(clock.instant(), "system", true);
        refresher.announceLeftSince(before, "system");
        refresher.reannounceLater(reannounceDelay);
        log.info("Seed member caricato (profilo demo): {} membri, {} azioni storiche, {} segmenti ({} appartenenze)",
                count(), actions, seeded, outcomes.stream().mapToInt(SegmentRefresher.Outcome::total).sum());
    }

    /**
     * Statistiche calcolate dallo storico (docs §6): stesse regole del consumer delle azioni.
     * SPEC-GAP: Q-86 — docs/10 §1 assegna activity-history.json a campaign (contatori e ~60 valutazioni) ma il file non
     * esisteva: nasce con la sola sezione {@code memberActivity} letta da member; campaign potrà aggiungere le sue.
     */
    private int seedActivity() {
        JsonNode history = seed.readTree("activity-history.json");
        LocalDate today = LocalDate.now(clock.withZone(ROME));
        int n = 0;
        for (JsonNode entry : history.path("memberActivity")) {
            String memberId = entry.path("memberId").asString();
            for (JsonNode a : entry.path("actions")) {
                String type = a.path("type").asString();
                Instant at = SeedDates.resolve(a.path("at").asString(), clock);
                boolean purchase = "purchase.completed".equals(type);
                BigDecimal amount = purchase && a.has("amount") ? BigDecimal.valueOf(a.path("amount").asDouble()) : BigDecimal.ZERO;
                stats.recordAction(memberId, type, at, at, purchase ? 1 : 0, amount, today);
                n++;
            }
        }
        return n;
    }

    /** Segmenti di {@code seed/segments.json}; gli statici con il loro elenco (le appartenenze le scrive il ricalcolo). */
    private int seedSegments() {
        Instant now = clock.instant();
        int n = 0;
        for (JsonNode s : seed.readTree("segments.json")) {
            String type = s.path("type").asString(Segment.DYNAMIC);
            Segment seg = new Segment(Ulid.next(clock), s.path("code").asString(), s.path("name").asString(),
                    text(s.get("description")), type, Segment.DYNAMIC.equals(type) ? s.get("criteria") : null,
                    Segment.ACTIVE, 0, null, 0, now, now, "system", "system");
            segments.insert(seg);
            if (Segment.STATIC.equals(type)) {
                Set<String> ids = new LinkedHashSet<>(strings(s.get("memberIds")));
                segments.addMembers(seg.id(), ids, now);
                segments.markRefreshed(seg.id(), ids.size(), now);
            }
            n++;
        }
        return n;
    }

    private long count() {
        return members.count(null, null, null);
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> out.add(v.asString()));
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
