package io.loyaltyhub.gamification.api;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.gamification.application.AchievementAdminService;
import io.loyaltyhub.gamification.application.AchievementAdminService.AchievementRequest;
import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Gestione di obiettivi e badge (docs/servizi/gamification-service.md §3; BO-15). Scritture: {@code object.edit}. */
@RestController
@RequestMapping("/v1")
public class AchievementsController {

    public record AchievementView(@JsonUnwrapped Achievement achievement, long completions, long inProgress) {
    }

    public record BadgeView(String code, String name, String description, String icon, String color, long holders,
                            List<String> unlockedBy) {
    }

    private final AchievementAdminService admin;
    private final AchievementRepository achievements;
    private final BadgeRepository badges;

    public AchievementsController(AchievementAdminService admin, AchievementRepository achievements, BadgeRepository badges) {
        this.admin = admin;
        this.achievements = achievements;
        this.badges = badges;
    }

    @GetMapping("/achievements")
    @Transactional(readOnly = true)
    public List<AchievementView> list() {
        Map<String, AchievementRepository.Stats> stats = achievements.stats();
        return achievements.findAll().stream()
                .map(a -> view(a, stats.getOrDefault(a.id(), AchievementRepository.Stats.NONE)))
                .toList();
    }

    @GetMapping("/achievements/{id}")
    @Transactional(readOnly = true)
    public AchievementView get(@PathVariable String id) {
        Achievement a = admin.get(id);
        return view(a, achievements.stats().getOrDefault(a.id(), AchievementRepository.Stats.NONE));
    }

    @PostMapping("/achievements")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<AchievementView> create(@RequestBody AchievementRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(admin.create(r), AchievementRepository.Stats.NONE));
    }

    @PutMapping("/achievements/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public AchievementView update(@PathVariable String id, @RequestBody AchievementRequest r) {
        return get(admin.update(id, r).id());
    }

    @GetMapping("/badges")
    @Transactional(readOnly = true)
    public List<BadgeView> badges() {
        Map<String, Long> holders = badges.holders();
        List<Achievement> all = achievements.findAll();
        return badges.findAll().stream()
                .map(b -> new BadgeView(b.code(), b.name(), b.description(), b.icon(), b.color(), holders.getOrDefault(b.code(), 0L),
                        all.stream().filter(a -> b.code().equals(a.badgeCode())).map(Achievement::code).toList()))
                .toList();
    }

    @PostMapping("/badges")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<BadgeRepository.Badge> createBadge(@RequestBody BadgeRepository.Badge b) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.saveBadge(b.code(), b, true));
    }

    @PutMapping("/badges/{code}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public BadgeRepository.Badge updateBadge(@PathVariable String code, @RequestBody BadgeRepository.Badge b) {
        return admin.saveBadge(code, b, false);
    }

    private static AchievementView view(Achievement a, AchievementRepository.Stats s) {
        return new AchievementView(a, s.completions(), s.inProgress());
    }
}
