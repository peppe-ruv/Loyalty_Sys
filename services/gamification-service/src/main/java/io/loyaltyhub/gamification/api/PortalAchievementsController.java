package io.loyaltyhub.gamification.api;

import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Portale: obiettivi con progresso e badge ottenuti / da ottenere (docs/servizi/gamification-service.md §3; PT-09). */
@RestController
@RequestMapping("/v1/portal")
public class PortalAchievementsController {

    public record BadgeRef(String code, String name, String icon, String color) {
    }

    public record PortalAchievement(String code, String name, String description, String icon, String metric,
                                    String streakUnit, long value, long target, int pct, String period, String periodKey,
                                    boolean repeatable, Instant completedAt, String lastUnitKey, BadgeRef badge) {
    }

    public record PortalBadge(String code, String name, String description, String icon, String color, Instant awardedAt,
                              String origin, String unlockHint) {
    }

    private final AchievementRepository achievements;
    private final BadgeRepository badges;
    private final Clock clock;

    public PortalAchievementsController(AchievementRepository achievements, BadgeRepository badges, Clock clock) {
        this.achievements = achievements;
        this.badges = badges;
        this.clock = clock;
    }

    /**
     * Obiettivi attivi con il progresso del periodo corrente. Un obiettivo non ripetibile già completato mostra il
     * completamento (in qualunque periodo sia avvenuto).
     */
    @GetMapping("/achievements")
    @Transactional(readOnly = true)
    public List<PortalAchievement> achievements(@RequestParam String memberId) {
        Instant now = clock.instant();
        Map<String, BadgeRepository.Badge> badgeByCode = badges.findAll().stream()
                .collect(Collectors.toMap(BadgeRepository.Badge::code, Function.identity()));
        List<AchievementRepository.ProgressRow> rows = achievements.memberProgress(memberId);
        return achievements.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.status()))
                .map(a -> {
                    String key = AchievementRules.periodKey(a.period(), now);
                    Optional<AchievementRepository.ProgressRow> row = rows.stream()
                            .filter(r -> r.achievementId().equals(a.id()) && r.periodKey().equals(key)).findFirst();
                    if (!a.repeatable()) {
                        Optional<AchievementRepository.ProgressRow> done = rows.stream()
                                .filter(r -> r.achievementId().equals(a.id()) && r.completedAt() != null).findFirst();
                        if (done.isPresent()) {
                            row = done;
                        }
                    }
                    return view(a, row.orElse(null), key, badgeByCode.get(a.badgeCode()));
                })
                .sorted(Comparator.comparing((PortalAchievement p) -> p.completedAt() != null).thenComparing(p -> -p.pct()))
                .toList();
    }

    @GetMapping("/badges")
    @Transactional(readOnly = true)
    public List<PortalBadge> badges(@RequestParam String memberId) {
        Map<String, BadgeRepository.MemberBadge> owned = badges.memberBadges(memberId).stream()
                .collect(Collectors.toMap(BadgeRepository.MemberBadge::badgeCode, Function.identity()));
        List<Achievement> all = achievements.findAll();
        return badges.findAll().stream()
                .map(b -> {
                    BadgeRepository.MemberBadge mine = owned.get(b.code());
                    String hint = all.stream().filter(a -> b.code().equals(a.badgeCode()) && "ACTIVE".equals(a.status()))
                            .map(a -> "Completa «" + a.name() + "»").findFirst().orElse("Arriva con le promozioni speciali");
                    return new PortalBadge(b.code(), b.name(), b.description(), b.icon(), b.color(),
                            mine == null ? null : mine.awardedAt(), mine == null ? null : mine.origin(), hint);
                })
                .sorted(Comparator.comparing((PortalBadge b) -> b.awardedAt() == null).thenComparing(PortalBadge::code))
                .toList();
    }

    private static PortalAchievement view(Achievement a, AchievementRepository.ProgressRow row, String key, BadgeRepository.Badge b) {
        long value = row == null ? 0 : Math.min(row.value(), a.target());
        int pct = (int) Math.min(100, Math.round(value * 100.0 / Math.max(1, a.target())));
        return new PortalAchievement(a.code(), a.name(), a.description(), a.icon(), a.metric(), a.streakUnit(), value, a.target(),
                pct, a.period(), row == null ? key : row.periodKey(), a.repeatable(), row == null ? null : row.completedAt(),
                row == null ? null : row.lastUnitKey(), b == null ? null : new BadgeRef(b.code(), b.name(), b.icon(), b.color()));
    }
}
