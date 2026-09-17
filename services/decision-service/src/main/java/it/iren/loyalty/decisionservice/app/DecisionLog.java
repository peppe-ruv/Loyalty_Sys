package it.iren.loyalty.decisionservice.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.iren.loyalty.decisionservice.domain.Decision;
import it.iren.loyalty.decisionservice.domain.DecisionContext;
import it.iren.loyalty.decisionservice.domain.DecisionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Decision log (RF-129, RF-135): ogni decisione è conservata per intero (azioni scelte, scartate con motivo, punteggi,
 * previsioni, policy, esperimento) per spiegabilità, audit e BI. Alimenta anche i limiti per membro (cooldown, periodo).
 */
@Component
public class DecisionLog {
    private static final ObjectMapper MAPPER = JsonMapper.builder().addModule(new JavaTimeModule()).findAndAddModules().build();
    private final JdbcTemplate jdbc;

    public DecisionLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(Decision d, boolean executed) {
        try {
            jdbc.update("INSERT INTO decisionservice.decision_log(decision_id, member_id, event_id, event_type, correlation_id, policy_version, experiment_id, variant, primary_action, risk_level, executed, decision, decided_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT (decision_id) DO NOTHING",
                    d.decisionId(), d.memberId(), d.eventId(), d.eventType(), d.correlationId(), d.policyVersion(), d.experimentId(), d.variant(), d.primaryAction(), d.riskLevel(),
                    executed, MAPPER.writeValueAsString(d), Timestamp.from(d.decidedAt()));
            for (var c : d.actions()) {
                jdbc.update("INSERT INTO decisionservice.decision_action(decision_id, member_id, action, reference, channel, score, source, source_id, decided_at) VALUES (?,?,?,?,?,?,?,?,?)",
                        d.decisionId(), d.memberId(), c.action().name(), c.reference(), c.channel(), c.score(), c.source(), c.sourceId(), Timestamp.from(d.decidedAt()));
            }
        } catch (Exception e) { throw new IllegalStateException("decision log", e); }
    }

    public Optional<Decision> find(String decisionId) {
        var rows = jdbc.query("SELECT decision FROM decisionservice.decision_log WHERE decision_id = ?", (rs, i) -> rs.getString(1), decisionId);
        return rows.stream().findFirst().map(this::parse);
    }

    public List<Decision> byMember(String memberId, int limit) {
        return jdbc.query("SELECT decision FROM decisionservice.decision_log WHERE member_id = ? ORDER BY decided_at DESC LIMIT ?", (rs, i) -> parse(rs.getString(1)), memberId, limit);
    }

    /** Azioni arbitrate già eseguite dal membro nell'ultimo periodo (per cooldown e limiti). */
    public List<DecisionContext.PriorAction> priorActions(String memberId, Instant since) {
        return jdbc.query("SELECT action, reference, decided_at FROM decisionservice.decision_action WHERE member_id = ? AND decided_at >= ? AND action NOT IN ('AWARD_POINTS','UPGRADE_TIER','GRANT_BADGE','SET_ATTRIBUTE','EMIT_EVENT') ORDER BY decided_at DESC",
                (rs, i) -> new DecisionContext.PriorAction(DecisionPolicy.ActionType.valueOf(rs.getString(1)), rs.getString(2), rs.getTimestamp(3).toInstant()), memberId, Timestamp.from(since));
    }

    /** Statistiche per la BI leggera del backoffice: decisioni per azione primaria e variante nelle ultime 24 ore. */
    public List<java.util.Map<String, Object>> stats24h() {
        return jdbc.queryForList("SELECT primary_action, coalesce(experiment_id, '-') AS experiment, coalesce(variant, '-') AS variant, count(*) AS n FROM decisionservice.decision_log WHERE decided_at >= now() - interval '24 hours' GROUP BY 1,2,3 ORDER BY 4 DESC");
    }

    private Decision parse(String json) {
        try { return MAPPER.readValue(json, Decision.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
