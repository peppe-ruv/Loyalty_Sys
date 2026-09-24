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
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import io.loyaltyhub.gamification.domain.Leaderboard;
import io.loyaltyhub.gamification.infra.InstantRepository;
import io.loyaltyhub.gamification.infra.LeaderboardRepository;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Carica i concorsi con i montepremi da {@code contests.json} (docs/servizi/gamification-service.md §6, docs/10 §6) e
 * rigenera gli istanti col seme fisso sulla finestra relativa a oggi. Ogni concorso nasce {@code DRAFT}, riceve gli
 * istanti e poi prende lo stato del seed. Poi lo storico da {@code gamification-history.json}: snapshot dei membri,
 * crediti e giocate di Matteo, vincite di {@code IW-ESTATE} (ogni vincita reclama un istante reale già passato, così
 * istanti, premi residui e vincitori restano coerenti); infine gli {@code ENDED} hanno gli istanti aperti annullati.
 * Profilo {@code demo}, ripetibile via {@code /v1/demo/reset}.
 */
@Component
@Profile("demo")
public class GamificationSeeder implements ApplicationRunner, DemoResettable {

    private static final Logger log = LoggerFactory.getLogger(GamificationSeeder.class);

    private final SeedLoader seed;
    private final ContestRepository contests;
    private final InstantRepository instants;
    private final PlayRepository plays;
    private final MemberSnapshotRepository members;
    private final ContestAdminService admin;
    private final AchievementRepository achievements;
    private final BadgeRepository badges;
    private final LeaderboardRepository leaderboards;
    private final Clock clock;

    public GamificationSeeder(SeedLoader seed, ContestRepository contests, InstantRepository instants, PlayRepository plays,
                              MemberSnapshotRepository members, ContestAdminService admin,
                              AchievementRepository achievements, BadgeRepository badges,
                              LeaderboardRepository leaderboards, Clock clock) {
        this.seed = seed;
        this.contests = contests;
        this.instants = instants;
        this.plays = plays;
        this.members = members;
        this.admin = admin;
        this.achievements = achievements;
        this.badges = badges;
        this.leaderboards = leaderboards;
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
        members.deleteAll();
        achievements.deleteAll();
        badges.deleteAll();
        seedAchievements();
        leaderboards.deleteAll();
        seedLeaderboards();
        for (JsonNode m : seed.readTree("members.json")) {
            members.upsert(m.path("id").asString(), text(m, "nickname"), m.path("status").asString("ACTIVE"));
        }
        Map<String, Contest> byCode = new HashMap<>();
        Map<String, ApprovalStatus> targetStatus = new HashMap<>();
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
            byCode.put(code, contest);
            targetStatus.put(code, status);
            count++;
        }
        int history = seedHistory(byCode);
        for (Map.Entry<String, ApprovalStatus> e : targetStatus.entrySet()) {
            Contest c = byCode.get(e.getKey());
            if (e.getValue() == ApprovalStatus.ENDED || e.getValue() == ApprovalStatus.ARCHIVED) {
                instants.voidOpen(c.id());
            }
            contests.recomputeRemaining(c.id());
        }
        log.info("Seed gamification caricato: {} concorsi, {} giocate storiche", count, history);
    }

    /**
     * Badge, obiettivi e progressi notevoli (docs/10 §6): il periodo è quello di {@code periodAt}/{@code completedAt}
     * (o di oggi); un obiettivo completato col badge collegato assegna il badge alla stessa data.
     */
    private void seedAchievements() {
        for (JsonNode b : seed.readTree("badges.json")) {
            badges.upsert(new BadgeRepository.Badge(b.path("code").asString(), b.path("name").asString(), text(b, "description"),
                    text(b, "icon"), text(b, "color")));
        }
        Map<String, Achievement> byCode = new HashMap<>();
        for (JsonNode a : seed.readTree("achievements.json")) {
            List<String> types = new ArrayList<>();
            a.path("actionTypes").forEach(t -> types.add(t.asString()));
            Achievement ach = new Achievement(Ulid.next(clock), a.path("code").asString(), a.path("name").asString(),
                    text(a, "description"), text(a, "icon"), types, a.hasNonNull("filter") ? a.get("filter") : null,
                    a.path("metric").asString(), text(a, "sumField"), text(a, "streakUnit"), a.path("target").asLong(),
                    a.path("period").asString("EVER"), a.path("repeatable").asBoolean(false), text(a, "badgeCode"),
                    a.path("status").asString("ACTIVE"));
            achievements.insert(ach);
            byCode.put(ach.code(), ach);
        }
        for (JsonNode p : seed.readTree("gamification-history.json").path("achievementProgress")) {
            Achievement a = byCode.get(p.path("achievementCode").asString());
            String memberId = p.path("memberId").asString();
            Instant completedAt = p.hasNonNull("completedAt") ? SeedDates.resolve(p.get("completedAt").asString(), clock) : null;
            Instant periodAt = p.hasNonNull("periodAt") ? SeedDates.resolve(p.get("periodAt").asString(), clock)
                    : completedAt != null ? completedAt : clock.instant();
            String lastUnit = p.hasNonNull("lastUnitAt")
                    ? AchievementRules.unitKey(a.streakUnit() == null ? "DAY" : a.streakUnit(), SeedDates.resolve(p.get("lastUnitAt").asString(), clock))
                    : null;
            List<String> distinct = new ArrayList<>();
            p.path("distinctSeen").forEach(t -> distinct.add(t.asString()));
            achievements.seedProgress(a.id(), memberId, AchievementRules.periodKey(a.period(), periodAt), p.path("value").asLong(),
                    distinct, lastUnit, completedAt);
            if (completedAt != null && a.badgeCode() != null) {
                badges.award(memberId, a.badgeCode(), "ACHIEVEMENT", null, completedAt);
            }
        }
    }

    /**
     * Classifiche (docs/10 §6): i punti del mese dal file di storico, i punti status dell'edizione dai saldi di
     * {@code wallets.json} (stessi numeri del wallet). Solo membri esistenti: i non attivi restano fuori dal ranking.
     */
    private void seedLeaderboards() {
        Map<String, Leaderboard> byCode = new HashMap<>();
        for (JsonNode l : seed.readTree("leaderboards.json")) {
            List<String> types = new ArrayList<>();
            l.path("actionTypes").forEach(t -> types.add(t.asString()));
            Leaderboard lb = new Leaderboard(Ulid.next(clock), l.path("code").asString(), l.path("name").asString(),
                    l.path("metric").asString(), types, l.path("period").asString("MONTH"), l.path("topN").asInt(10),
                    l.path("status").asString("ACTIVE"));
            leaderboards.insert(lb);
            byCode.put(lb.code(), lb);
        }
        for (JsonNode s : seed.readTree("gamification-history.json").path("leaderboardScores")) {
            Leaderboard lb = byCode.get(s.path("leaderboardCode").asString());
            Instant at = SeedDates.resolve(s.path("reachedAt").asString(), clock);
            leaderboards.add(lb.id(), Leaderboard.periodKey(lb.period(), clock.instant()), s.path("memberId").asString(),
                    s.path("score").asLong(), at);
        }
        Leaderboard sts = byCode.get("LDB-EDITION-STS");
        if (sts != null) {
            int i = 0;
            for (JsonNode w : seed.readTree("wallets.json")) {
                long periodSts = w.path("periodSts").asLong(0);
                if (periodSts > 0) {
                    leaderboards.add(sts.id(), Leaderboard.periodKey(sts.period(), clock.instant()), w.path("memberId").asString(),
                            periodSts, clock.instant().minus(Duration.ofDays(2 + (i++ % 20))));
                }
            }
        }
    }

    /** Crediti, giocate esplicite e storico dei concorsi chiusi; restituisce le giocate inserite. */
    private int seedHistory(Map<String, Contest> byCode) {
        JsonNode h = seed.readTree("gamification-history.json");
        for (JsonNode g : h.path("grants")) {
            Contest c = byCode.get(g.path("contestCode").asString());
            plays.insertGrant(Ulid.next(clock), g.path("memberId").asString(), c.id(), g.path("count").asInt(1),
                    g.path("effectId").asString(), text(g, "campaignCode"), SeedDates.resolve(g.path("grantedAt").asString(), clock));
        }
        List<PlayRepository.NewPlay> rows = new ArrayList<>();
        List<InstantRepository.HistoryClaim> claims = new ArrayList<>();
        for (JsonNode p : h.path("plays")) {
            Contest c = byCode.get(p.path("contestCode").asString());
            String memberId = p.path("memberId").asString();
            Instant at = SeedDates.resolve(p.path("at").asString(), clock);
            String playId = Ulid.next(clock);
            String prizeId = null;
            if ("WIN".equals(p.path("outcome").asString())) {
                String prizeCode = p.path("prizeCode").asString();
                var instant = instants.openBefore(c.id(), at).stream()
                        .filter(i -> i.prizeCode().equals(prizeCode) && claims.stream().noneMatch(x -> x.instantId().equals(i.id())))
                        .findFirst();
                if (instant.isPresent()) {
                    prizeId = instant.get().prizeId();
                    claims.add(new InstantRepository.HistoryClaim(instant.get().id(), memberId, playId, at));
                } else {
                    log.warn("Seed: nessun istante {} aperto prima di {} per {}", prizeCode, at, c.code());
                }
            }
            rows.add(play(playId, c, memberId, prizeId == null ? "LOSE" : "WIN", prizeId, at, "NA"));
        }
        for (JsonNode cc : h.path("closedContests")) {
            closedContest(byCode.get(cc.path("contestCode").asString()), cc, rows, claims);
        }
        instants.claimAll(claims);
        plays.insertAll(rows);
        return rows.size();
    }

    /**
     * Storico di un concorso chiuso: i primi {@code claimed} istanti in ordine di tempo vanno ai vincitori (mescolati col
     * seme del file), ogni vincita ha {@code losingPlaysPerWin} giocate perdenti dello stesso membro in orario 8–22.
     */
    private void closedContest(Contest c, JsonNode cc, List<PlayRepository.NewPlay> rows, List<InstantRepository.HistoryClaim> claims) {
        SplittableRandom rnd = new SplittableRandom(cc.path("seed").asLong(1));
        List<String> winners = new ArrayList<>();
        for (JsonNode w : cc.path("winners")) {
            for (int i = 0; i < w.path("wins").asInt(); i++) {
                winners.add(w.path("memberId").asString());
            }
        }
        for (int i = winners.size() - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            String t = winners.get(i);
            winners.set(i, winners.get(j));
            winners.set(j, t);
        }
        List<InstantRepository.InstantRow> open = instants.openBefore(c.id(), c.endAt());
        int claimed = Math.min(cc.path("claimed").asInt(winners.size()), Math.min(open.size(), winners.size()));
        List<String> deliveries = new ArrayList<>();
        cc.path("physicalDelivery").forEach(d -> deliveries.add(d.asString()));
        int physical = 0;
        for (int k = 0; k < claimed; k++) {
            InstantRepository.InstantRow i = open.get(k);
            String memberId = winners.get(k);
            String playId = Ulid.next(clock);
            Instant at = i.instantAt().plus(Duration.ofMinutes(1 + rnd.nextInt(120)));
            if (!at.isBefore(c.endAt())) {
                at = c.endAt().minusSeconds(60);
            }
            claims.add(new InstantRepository.HistoryClaim(i.id(), memberId, playId, at));
            String delivery = "NA";
            if (contests.prize(i.prizeId()).filter(p -> "PHYSICAL".equals(p.type())).isPresent()) {
                delivery = physical < deliveries.size() ? deliveries.get(physical) : "DELIVERED";
                physical++;
            }
            rows.add(play(playId, c, memberId, "WIN", i.prizeId(), at, delivery));
            for (int l = 0; l < cc.path("losingPlaysPerWin").asInt(0); l++) {
                rows.add(play(Ulid.next(clock), c, memberId, "LOSE", null, randomBusinessTime(c, rnd), "NA"));
            }
        }
    }

    private static Instant randomBusinessTime(Contest c, SplittableRandom rnd) {
        long days = Math.max(1, Duration.between(c.startAt(), c.endAt()).toDays());
        ZonedDateTime day = c.startAt().atZone(BusinessCalendar.ZONE).plusDays(rnd.nextLong(days));
        Instant t = day.withHour(8 + rnd.nextInt(14)).withMinute(rnd.nextInt(60)).withSecond(rnd.nextInt(60)).toInstant();
        return t.isBefore(c.startAt()) ? c.startAt().plusSeconds(60) : (t.isBefore(c.endAt()) ? t : c.endAt().minusSeconds(120));
    }

    private static PlayRepository.NewPlay play(String id, Contest c, String memberId, String outcome, String prizeId, Instant at,
                                               String delivery) {
        return new PlayRepository.NewPlay(id, c.id(), memberId, "FREE_DAILY", outcome, prizeId, at,
                LocalDate.ofInstant(at, BusinessCalendar.ZONE), null, delivery);
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asString() : null;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asInt() : null;
    }
}
