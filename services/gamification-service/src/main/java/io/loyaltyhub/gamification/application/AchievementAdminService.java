package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.gamification.domain.Achievement;
import io.loyaltyhub.gamification.infra.AchievementRepository;
import io.loyaltyhub.gamification.infra.BadgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Configurazione di obiettivi e badge (BO-15; F-ACH-01, F-ACH-03), con audit. */
@Service
public class AchievementAdminService {

    private static final Pattern ACH_CODE = Pattern.compile("^ACH-[A-Z0-9-]{2,36}$");
    private static final Pattern BDG_CODE = Pattern.compile("^BDG-[A-Z0-9-]{2,36}$");

    public record AchievementRequest(String code, String name, String description, String icon, List<String> actionTypes,
                                     JsonNode filter, String metric, String sumField, String streakUnit, Long target,
                                     String period, Boolean repeatable, String badgeCode, String status) {
    }

    private final AchievementRepository achievements;
    private final BadgeRepository badges;
    private final AuditPublisher audit;
    private final Clock clock;

    public AchievementAdminService(AchievementRepository achievements, BadgeRepository badges, AuditPublisher audit, Clock clock) {
        this.achievements = achievements;
        this.badges = badges;
        this.audit = audit;
        this.clock = clock;
    }

    public Achievement get(String idOrCode) {
        return achievements.find(idOrCode).orElseThrow(() -> LhException.notFound("Obiettivo non trovato: " + idOrCode));
    }

    @Transactional
    public Achievement create(AchievementRequest r) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!ACH_CODE.matcher(code).matches()) {
            throw LhException.validation("ACHIEVEMENT_INVALID", "Codice obiettivo non valido (es. ACH-PRIMO-QUIZ).");
        }
        if (achievements.find(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Obiettivo già esistente: " + code);
        }
        Achievement a = build(Ulid.next(clock), code, r, null);
        achievements.insert(a);
        audit.record("ACHIEVEMENT", code, AuditEntry.Action.CREATE, "Creato obiettivo " + a.name(), null,
                Map.of("metric", a.metric(), "target", a.target(), "period", a.period()));
        return get(a.id());
    }

    @Transactional
    public Achievement update(String id, AchievementRequest r) {
        Achievement current = get(id);
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(current.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un obiettivo non si modifica: " + current.code());
        }
        Achievement a = build(current.id(), current.code(), r, current);
        achievements.update(a);
        audit.record("ACHIEVEMENT", a.code(), AuditEntry.Action.UPDATE, "Modificato obiettivo " + a.name(),
                Map.of("target", current.target(), "status", current.status()), Map.of("target", a.target(), "status", a.status()));
        return get(a.id());
    }

    @Transactional
    public BadgeRepository.Badge saveBadge(String code, BadgeRepository.Badge b, boolean creating) {
        String c = code == null ? "" : code.trim().toUpperCase();
        if (!BDG_CODE.matcher(c).matches()) {
            throw LhException.validation("BADGE_INVALID", "Codice badge non valido (es. BDG-CURIOSO).");
        }
        if (b.name() == null || b.name().isBlank()) {
            throw LhException.validation("BADGE_INVALID", "Il badge richiede un nome.");
        }
        boolean exists = badges.find(c).isPresent();
        if (creating && exists) {
            throw LhException.conflict("CODE_TAKEN", "Badge già esistente: " + c);
        }
        if (!creating && !exists) {
            throw LhException.notFound("Badge non trovato: " + c);
        }
        BadgeRepository.Badge saved = new BadgeRepository.Badge(c, b.name().trim(), b.description(), b.icon(), b.color());
        badges.upsert(saved);
        audit.record("BADGE", c, creating ? AuditEntry.Action.CREATE : AuditEntry.Action.UPDATE,
                (creating ? "Creato" : "Modificato") + " badge " + saved.name(), null, Map.of("name", saved.name()));
        return saved;
    }

    private Achievement build(String id, String code, AchievementRequest r, Achievement cur) {
        String metric = upper(r.metric() != null ? r.metric() : cur == null ? null : cur.metric());
        String period = upper(r.period() != null ? r.period() : cur == null ? "EVER" : cur.period());
        List<String> types = r.actionTypes() != null ? r.actionTypes().stream().map(String::trim).filter(s -> !s.isBlank()).toList()
                : cur == null ? List.of() : cur.actionTypes();
        long target = r.target() != null ? r.target() : cur == null ? 0 : cur.target();
        String sumField = r.sumField() != null ? blankToNull(r.sumField()) : cur == null ? null : cur.sumField();
        String unit = r.streakUnit() != null ? upper(r.streakUnit()) : cur == null ? null : cur.streakUnit();
        String badge = r.badgeCode() != null ? blankToNull(r.badgeCode()) : cur == null ? null : cur.badgeCode();
        String status = upper(r.status() != null ? r.status() : cur == null ? "ACTIVE" : cur.status());
        String name = r.name() != null ? r.name().trim() : cur == null ? null : cur.name();
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) problems.add("nome obbligatorio");
        if (metric == null || !Achievement.METRICS.contains(metric)) problems.add("metrica tra " + Achievement.METRICS);
        if (period == null || !Achievement.PERIODS.contains(period)) problems.add("periodo tra " + Achievement.PERIODS);
        if (types.isEmpty()) problems.add("almeno un tipo di azione");
        if (target < 1) problems.add("traguardo ≥ 1");
        if ("SUM".equals(metric) && sumField == null) problems.add("SUM richiede il campo da sommare");
        if ("STREAK".equals(metric) && (unit == null || !Achievement.STREAK_UNITS.contains(unit))) problems.add("STREAK richiede l'unità DAY o WEEK");
        if ("DISTINCT_TYPES".equals(metric) && target > types.size()) problems.add("traguardo oltre il numero di tipi osservati");
        if (badge != null && badges.find(badge).isEmpty()) problems.add("badge inesistente " + badge);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) problems.add("stato ACTIVE o INACTIVE");
        if (!problems.isEmpty()) {
            throw LhException.validation("ACHIEVEMENT_INVALID", "Obiettivo non valido: " + String.join("; ", problems) + ".");
        }
        return new Achievement(id, code, name, r.description() != null ? r.description() : cur == null ? null : cur.description(),
                r.icon() != null ? r.icon() : cur == null ? null : cur.icon(), types,
                r.filter() != null ? r.filter() : cur == null ? null : cur.filter(), metric,
                "SUM".equals(metric) ? sumField : null, "STREAK".equals(metric) ? unit : null, target, period,
                r.repeatable() != null ? r.repeatable() : cur != null && cur.repeatable(), badge, status);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
