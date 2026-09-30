package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.domain.AchievementRules;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Portale: obiettivi con progresso e badge ottenuti / da ottenere (docs/servizi/gamification-service.md §3; PT-09).
 * Il membro viene solo dal token (Q-410, ADR-048, docs/06 §3.4): nessun {@code memberId} in query, percorso o corpo; lo
 * risolve {@code EndpointAccessInterceptor} (token in {@code enterprise}, {@code memberId}/{@code X-LH-Member} in
 * {@code demo}) e lo consegna come {@link MemberPrincipal}.
 */
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
    @MemberEndpoint
    public List<PortalAchievement> achievements(MemberPrincipal principal) {
        String memberId = principal.requireParam();
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
    @MemberEndpoint
    public List<PortalBadge> badges(MemberPrincipal principal) {
        String memberId = principal.requireParam();
        Map<String, BadgeRepository.MemberBadge> owned = badges.memberBadges(memberId).stream()
                .collect(Collectors.toMap(BadgeRepository.MemberBadge::badgeCode, Function.identity()));
        Map<String, Achievement> activeAchievementsByBadge = achievements.findAll().stream()
                .filter(a -> "ACTIVE".equals(a.status()) && a.badgeCode() != null)
                .collect(Collectors.toMap(Achievement::badgeCode, Function.identity(), (existing, replacement) -> existing));
        return badges.findAll().stream()
                .map(b -> {
                    BadgeRepository.MemberBadge mine = owned.get(b.code());
                    Achievement a = activeAchievementsByBadge.get(b.code());
                    String hint = a != null ? "Completa «" + a.name() + "»" : "Arriva con le promozioni speciali";
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
