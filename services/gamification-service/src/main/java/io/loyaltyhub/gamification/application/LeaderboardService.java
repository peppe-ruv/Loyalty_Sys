package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.gamification.domain.Leaderboard;
import io.loyaltyhub.gamification.infra.LeaderboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Classifiche (docs/03 §8, docs/servizi/gamification-service.md §5; F-LDB-01): punteggi {@code PTS_EARNED}/
 * {@code STS_EARNED} dall'importo effettivo di {@code wallet.points.earned}, {@code ACTION_COUNT} dalle azioni elencate.
 * Il periodo è quello dell'evento; configurazione con audit.
 */
@Service
public class LeaderboardService {

    private static final Pattern CODE = Pattern.compile("^LDB-[A-Z0-9-]{2,36}$");

    public record LeaderboardRequest(String code, String name, String metric, List<String> actionTypes, String period,
                                     Integer topN, String status) {
    }

    private final LeaderboardRepository leaderboards;
    private final AuditPublisher audit;
    private final Clock clock;

    public LeaderboardService(LeaderboardRepository leaderboards, AuditPublisher audit, Clock clock) {
        this.leaderboards = leaderboards;
        this.audit = audit;
        this.clock = clock;
    }

    /** Fatto {@code wallet.points.earned}: somma l'importo effettivo nelle classifiche della sua valuta. */
    @Transactional
    public void onPointsEarned(LhEvent<JsonNode> fact) {
        String memberId = fact.memberId();
        JsonNode d = fact.data();
        long amount = d == null ? 0 : d.path("amount").asLong(0);
        if (memberId == null || amount <= 0) {
            return;
        }
        String currency = d.path("currency").asString("");
        Instant at = fact.time() != null ? fact.time() : clock.instant();
        for (Leaderboard l : leaderboards.findActive()) {
            if (currency.equals(l.currency())) {
                leaderboards.add(l.id(), Leaderboard.periodKey(l.period(), at), memberId, amount, clock.instant());
            }
        }
    }

    /** Azione: +1 nelle classifiche {@code ACTION_COUNT} che la elencano. */
    @Transactional
    public void onAction(LhEvent<JsonNode> action) {
        String memberId = action.memberId();
        if (memberId == null || action.type() == null || !action.type().startsWith(LhEventTypes.Action.PREFIX)) {
            return;
        }
        String shortType = action.type().substring(LhEventTypes.Action.PREFIX.length());
        Instant at = action.time() != null ? action.time() : clock.instant();
        for (Leaderboard l : leaderboards.findActive()) {
            if ("ACTION_COUNT".equals(l.metric()) && l.actionTypes().contains(shortType)) {
                leaderboards.add(l.id(), Leaderboard.periodKey(l.period(), at), memberId, 1, clock.instant());
            }
        }
    }

    public Leaderboard get(String idOrCode) {
        return leaderboards.find(idOrCode).orElseThrow(() -> LhException.notFound("Classifica non trovata: " + idOrCode));
    }

    public String currentPeriod(Leaderboard l) {
        return Leaderboard.periodKey(l.period(), clock.instant());
    }

    @Transactional
    public Leaderboard create(LeaderboardRequest r) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("LEADERBOARD_INVALID", "Codice classifica non valido (es. LDB-MONTH-QUIZ).");
        }
        if (leaderboards.find(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Classifica già esistente: " + code);
        }
        Leaderboard l = build(Ulid.next(clock), code, r, null);
        leaderboards.insert(l);
        audit.record("LEADERBOARD", code, AuditEntry.Action.CREATE, "Creata classifica " + l.name(), null,
                Map.of("metric", l.metric(), "period", l.period(), "topN", l.topN()));
        return get(l.id());
    }

    @Transactional
    public Leaderboard update(String id, LeaderboardRequest r) {
        Leaderboard cur = get(id);
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(cur.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di una classifica non si modifica: " + cur.code());
        }
        Leaderboard l = build(cur.id(), cur.code(), r, cur);
        if (!l.metric().equals(cur.metric()) || !l.period().equals(cur.period())) {
            // I punteggi già raccolti non sono ricalcolabili: metrica e periodo restano quelli della creazione.
            throw LhException.conflict("LEADERBOARD_LOCKED", "Metrica e periodo non si cambiano: crea una nuova classifica.");
        }
        leaderboards.update(l);
        audit.record("LEADERBOARD", l.code(), AuditEntry.Action.UPDATE, "Modificata classifica " + l.name(),
                Map.of("topN", cur.topN(), "status", cur.status()), Map.of("topN", l.topN(), "status", l.status()));
        return get(l.id());
    }

    private static Leaderboard build(String id, String code, LeaderboardRequest r, Leaderboard cur) {
        String metric = upper(r.metric() != null ? r.metric() : cur == null ? null : cur.metric());
        String period = upper(r.period() != null ? r.period() : cur == null ? "MONTH" : cur.period());
        List<String> types = r.actionTypes() != null ? r.actionTypes().stream().map(String::trim).filter(s -> !s.isBlank()).toList()
                : cur == null ? List.of() : cur.actionTypes();
        int topN = r.topN() != null ? r.topN() : cur == null ? 10 : cur.topN();
        String status = upper(r.status() != null ? r.status() : cur == null ? "ACTIVE" : cur.status());
        String name = r.name() != null ? r.name().trim() : cur == null ? null : cur.name();
        List<String> problems = new ArrayList<>();
        if (name == null || name.isBlank()) problems.add("nome obbligatorio");
        if (metric == null || !Leaderboard.METRICS.contains(metric)) problems.add("metrica tra " + Leaderboard.METRICS);
        if (period == null || !Leaderboard.PERIODS.contains(period)) problems.add("periodo tra " + Leaderboard.PERIODS);
        if ("ACTION_COUNT".equals(metric) && types.isEmpty()) problems.add("ACTION_COUNT richiede almeno un tipo di azione");
        if (topN < 3 || topN > 50) problems.add("top N tra 3 e 50");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) problems.add("stato ACTIVE o INACTIVE");
        if (!problems.isEmpty()) {
            throw LhException.validation("LEADERBOARD_INVALID", "Classifica non valida: " + String.join("; ", problems) + ".");
        }
        return new Leaderboard(id, code, name, metric, "ACTION_COUNT".equals(metric) ? types : List.of(), period, topN, status);
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}
