package io.loyaltyhub.gamification.demo;

import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.demo.DemoResettable;
import io.loyaltyhub.common.demo.SeedDates;
import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.gamification.application.ContestAdminService;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.domain.Prize;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.InstantRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;

/**
 * Carica i concorsi con i montepremi da {@code contests.json} (docs/servizi/gamification-service.md §6, docs/10 §6) e
 * rigenera gli istanti col seme fisso sulla finestra relativa a oggi. Ogni concorso nasce {@code DRAFT}, riceve gli
 * istanti e poi prende lo stato del seed; gli {@code ENDED} hanno gli istanti aperti annullati. Profilo {@code demo},
 * ripetibile via {@code /v1/demo/reset}.
 */
@Component
@Profile("demo")
public class GamificationSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(GamificationSeeder.class);

    private final SeedLoader seed;
    private final ContestRepository contests;
    private final InstantRepository instants;
    private final PlayRepository plays;
    private final ContestAdminService admin;
    private final Clock clock;

    public GamificationSeeder(SeedLoader seed, ContestRepository contests, InstantRepository instants, PlayRepository plays,
                              ContestAdminService admin, Clock clock) {
        this.seed = seed;
        this.contests = contests;
        this.instants = instants;
        this.plays = plays;
        this.admin = admin;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        resetToSeed();
    }

    @Override
    public String demoComponent() {
        return "gamification";
    }

    @Override
    @Transactional
    public void resetToSeed() {
        plays.deleteAll();
        contests.deleteAll();
        int count = 0;
        for (JsonNode c : seed.readTree("contests.json")) {
            String code = c.path("code").asString();
            Contest contest = new Contest(Ulid.next(clock), code, c.path("name").asString(), text(c, "description"),
                    text(c, "rulesText"), c.path("mechanic").asString(), SeedDates.resolve(c.path("startAt").asString(), clock),
                    SeedDates.resolve(c.path("endAt").asString(), clock), c.path("freePlayDaily").asBoolean(false),
                    intOrNull(c, "maxPlaysPerMemberPerDay"), intOrNull(c, "maxWinsPerMember"),
                    c.path("distribution").asString("UNIFORM"),
                    c.hasNonNull("seed") ? c.get("seed").asLong() : ContestAdminService.seedFor(code), null,
                    ApprovalStatus.DRAFT, 0, text(c, "createdBy"), null);
            contests.insert(contest);
            int i = 0;
            for (JsonNode p : c.path("prizes")) {
                int qty = p.path("quantity").asInt();
                contests.insertPrize(new Prize(Ulid.next(clock), contest.id(), p.path("code").asString(), p.path("name").asString(),
                        p.path("type").asString(), p.hasNonNull("points") ? p.get("points").asLong() : null,
                        text(p, "rewardCode"), qty, qty, text(p, "imageUrl"), text(p, "wheelColor"), p.path("sortOrder").asInt(++i)));
            }
            admin.generateInstants(contest.id(), null, false);
            ApprovalStatus status = ApprovalStatus.valueOf(c.path("status").asString("DRAFT"));
            if (status != ApprovalStatus.DRAFT) {
                contests.updateStatus(contest.id(), status);
            }
            if (status == ApprovalStatus.ENDED || status == ApprovalStatus.ARCHIVED) {
                instants.voidOpen(contest.id());
            }
            count++;
        }
        log.info("Seed gamification caricato: {} concorsi", count);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asInt() : null;
    }
}
