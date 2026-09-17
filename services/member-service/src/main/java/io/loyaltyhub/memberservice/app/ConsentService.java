package io.loyaltyhub.memberservice.app;

import io.loyaltyhub.common.event.CanonicalEvents;
import io.loyaltyhub.common.event.EventTypes;
import io.loyaltyhub.common.event.RewardingAction;
import io.loyaltyhub.common.metrics.LoyaltyMetrics;
import io.loyaltyhub.memberservice.domain.Consent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Gestione consensi (RF-135): registrazione con prova, storico immutabile, scadenza automatica, propagazione
 * (CONSENT_V1 per Customer 360 e BI; azione CONSENT_CHANGED per le campagne). Mantiene sincronizzata la mappa
 * semplice {@code member.consents} usata dalle API storiche (retro-compatibilità).
 */
@Service
public class ConsentService {
    private static final String SOURCE = "urn:loyaltyhub:member-service";
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Consent.PurposeSource purposes;
    private final LoyaltyMetrics metrics;

    public ConsentService(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, Consent.PurposeSource purposes, LoyaltyMetrics metrics) {
        this.jdbc = jdbc; this.kafka = kafka; this.purposes = purposes; this.metrics = metrics;
    }

    @Transactional
    public Consent record(String memberId, String purpose, boolean granted, String source, String legalBasis, String version, Instant expiresAt, String evidence) {
        Consent.Purpose p = purposes.byCode(purpose);
        Instant now = Instant.now();
        String basis = legalBasis != null ? legalBasis : p != null ? p.defaultLegalBasis() : "consent";
        String ver = version != null ? version : p != null ? p.currentVersion() : "1";
        Instant exp = expiresAt;
        if (exp == null && granted && p != null && p.validityMonths() != null) exp = ZonedDateTime.ofInstant(now, java.time.ZoneId.of("Europe/Rome")).plusMonths(p.validityMonths()).toInstant();
        if (p != null && p.required() && !granted) throw new IllegalArgumentException("il consenso '" + purpose + "' è necessario al programma: per revocarlo chiudere l'adesione");
        Consent c = new Consent(purpose, granted, source == null ? "api" : source, basis, ver, now, exp, evidence);
        Boolean prev = jdbc.query("SELECT granted FROM memberservice.consent WHERE member_id = ? AND purpose = ? FOR UPDATE", rs -> rs.next() ? rs.getBoolean(1) : null, memberId, purpose);
        jdbc.update("INSERT INTO memberservice.consent(member_id, purpose, granted, source, legal_basis, version, granted_at, expires_at, evidence) VALUES (?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (member_id, purpose) DO UPDATE SET granted = EXCLUDED.granted, source = EXCLUDED.source, legal_basis = EXCLUDED.legal_basis, version = EXCLUDED.version, granted_at = EXCLUDED.granted_at, expires_at = EXCLUDED.expires_at, evidence = EXCLUDED.evidence, updated_at = now()",
                memberId, purpose, granted, c.source(), basis, ver, Timestamp.from(now), exp == null ? null : Timestamp.from(exp), evidence);
        jdbc.update("INSERT INTO memberservice.consent_history(member_id, purpose, granted, source, legal_basis, version, granted_at, expires_at, evidence) VALUES (?,?,?,?,?,?,?,?,?)",
                memberId, purpose, granted, c.source(), basis, ver, Timestamp.from(now), exp == null ? null : Timestamp.from(exp), evidence);
        jdbc.update("UPDATE memberservice.member SET consents = consents || jsonb_build_object(?::text, ?::boolean), updated_at = now() WHERE id = ?", purpose, granted, memberId);
        metrics.consent(purpose, granted);
        if (prev == null || prev != granted) publish(memberId, c, prev);
        return c;
    }

    public List<Consent> current(String memberId) {
        return jdbc.query("SELECT purpose, granted, source, legal_basis, version, granted_at, expires_at, evidence FROM memberservice.consent WHERE member_id = ? ORDER BY purpose", (rs, i) -> map(rs), memberId);
    }

    public List<Consent> history(String memberId) {
        return jdbc.query("SELECT purpose, granted, source, legal_basis, version, granted_at, expires_at, evidence FROM memberservice.consent_history WHERE member_id = ? ORDER BY recorded_at DESC LIMIT 200", (rs, i) -> map(rs), memberId);
    }

    /** Scadenza automatica: i consensi scaduti diventano revocati (fonte "expiry") e propagati. */
    @Scheduled(cron = "${members.consent-expiry-cron:0 15 3 * * *}", zone = "Europe/Rome")
    @Transactional
    public void expire() {
        var rows = jdbc.queryForList("SELECT member_id, purpose FROM memberservice.consent WHERE granted AND expires_at IS NOT NULL AND expires_at <= now() LIMIT 5000");
        for (var r : rows) record(String.valueOf(r.get("member_id")), String.valueOf(r.get("purpose")), false, "expiry", null, null, null, "scaduto");
    }

    private void publish(String memberId, Consent c, Boolean previous) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("memberId", memberId); p.put("purpose", c.purpose()); p.put("granted", c.granted()); p.put("previous", previous); p.put("source", c.source());
        p.put("legalBasis", c.legalBasis()); p.put("version", c.version()); p.put("grantedAt", c.grantedAt().toString()); p.put("expiresAt", c.expiresAt() == null ? null : c.expiresAt().toString());
        kafka.send(EventTypes.TOPIC_CONSENTS, memberId, CanonicalEvents.serialize(CanonicalEvents.of(EventTypes.CONSENT_V1, SOURCE, "member:" + memberId, p)));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("purpose", c.purpose()); attrs.put("granted", c.granted()); attrs.put("source", c.source());
        var a = new RewardingAction(EventTypes.ACTION_CONSENT_CHANGED, "consent:" + memberId + ":" + c.purpose() + ":" + c.grantedAt().toEpochMilli(), null, c.grantedAt(), null, attrs);
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(CanonicalEvents.action(SOURCE, memberId, a)));
    }

    private static Consent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Consent(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getString(8));
    }
}
