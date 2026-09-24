package io.loyaltyhub.member.demo;

import tools.jackson.databind.JsonNode;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Codes;
import io.loyaltyhub.member.domain.Member;
import io.loyaltyhub.member.domain.MemberStatus;
import io.loyaltyhub.member.domain.ProfileRules;
import io.loyaltyhub.member.infra.MemberProjectionRepository;
import io.loyaltyhub.member.infra.MemberRepository;
import io.loyaltyhub.member.infra.MemberStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/**
 * Carica i 12 membri canonici (docs/10 §2, docs/servizi/member-service.md §6) da {@code seed/members.json}:
 * anagrafica, proiezione saldi/tier e statistiche azzerate. Attivo col profilo {@code demo}, idempotente,
 * ripetibile via {@code POST /v1/demo/reset} ({@link DemoResettable}). I codici invito sono riproducibili
 * (seme fisso), così il reset demo è deterministico (docs/10 §1.3).
 */
@Component
@Profile("demo")
public class MemberSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(MemberSeeder.class);

    private final SeedLoader seed;
    private final MemberRepository members;
    private final MemberProjectionRepository projections;
    private final MemberStatsRepository stats;
    private final Clock clock;

    public MemberSeeder(SeedLoader seed, MemberRepository members, MemberProjectionRepository projections,
                        MemberStatsRepository stats, Clock clock) {
        this.seed = seed;
        this.members = members;
        this.projections = projections;
        this.stats = stats;
        this.clock = clock;
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
        // Ripristino pulito e idempotente (docs/10 §1.3): prima i figli (FK), poi l'anagrafica.
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
            Instant now = clock.instant();
            Member member = new Member(
                    id, text(m.get("externalId")), text(m.get("firstName")), text(m.get("lastName")),
                    text(m.get("nickname")), text(m.get("email")), text(m.get("phone")), birthDate, null,
                    text(m.get("city")), status, "IMPORT", now, Codes.random(8, rnd), text(m.get("referredBy")),
                    null, consents, attributes, List.of(), avatarSeed, null, 0);
            // Profilo già completo nei seed (docs/10 §2: incompleti solo Anna ed Elisa): nessun fatto da riemettere.
            if (ProfileRules.missingFields(member).isEmpty()) {
                member = ProfileRules.withCompletedAt(member, now);
            }
            members.insert(member);

            projections.upsert(id, m.path("tier").asString("BASE"),
                    m.path("sts").asLong(0), m.path("points").asLong(0), 0, m.path("points").asLong(0));
            stats.resetTo(id);
        }
        log.info("Seed member caricato (profilo demo): {} membri + proiezione + statistiche", count());
    }

    private long count() {
        return members.count(null, null, null);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
