package it.iren.loyalty.memberservice.app;

import it.iren.loyalty.common.event.CanonicalEvents;
import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.common.event.RewardingAction;
import it.iren.loyalty.memberservice.domain.Anonymizer;
import it.iren.loyalty.memberservice.domain.Member;
import it.iren.loyalty.memberservice.domain.ReferralPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Casi d'uso del membro: adesione (con eventuale codice referral), aggiornamento consensi/etichette, completamento
 * profilo, referral, anonimizzazione. Ogni cambiamento emette MEMBER_V1 e, dove previsto, un'azione premiante interna.
 */
@Service
public class MemberService {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, byte[]> kafka;
    private final ReferralPolicy referral;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    public MemberService(JdbcTemplate jdbc, KafkaTemplate<String, byte[]> kafka, ReferralPolicy referral) {
        this.jdbc = jdbc; this.kafka = kafka; this.referral = referral;
    }

    @Transactional
    public Member enroll(String memberId, String channel, Map<String, Boolean> consents, String referralCode) {
        Optional<Member> existing = find(memberId);
        if (existing.isPresent()) return existing.get();
        Instant now = Instant.now();
        String referredBy = referralCode == null ? null : jdbc.query("SELECT id FROM memberservice.member WHERE referral_code = ? AND status = 'ACTIVE'",
                rs -> rs.next() ? rs.getString(1) : null, referralCode.trim().toUpperCase());
        if (memberId.equals(referredBy)) referredBy = null;
        Member m = new Member(memberId, Member.Status.ACTIVE, now, channel, Map.of(), consents == null ? Map.of() : consents, referral.codeFor(memberId), referredBy, null);
        jdbc.update("INSERT INTO memberservice.member(id, status, enrolled_at, channel, labels, consents, referral_code, referred_by) VALUES (?,?,?,?,?::jsonb,?::jsonb,?,?)",
                m.id(), m.status().name(), Timestamp.from(now), channel, write(m.labels()), write(m.consents()), m.referralCode(), referredBy);
        publishMember(m);
        publishAction(memberId, new RewardingAction(EventTypes.ACTION_MEMBER_ENROLLED, "member:" + memberId + ":ENROLLED", null, now, null, Map.of(EventTypes.ATTR_CHANNEL, channel == null ? "" : channel)));
        if (Boolean.TRUE.equals(m.consents().get("newsletter")))
            publishAction(memberId, new RewardingAction(EventTypes.ACTION_NEWSLETTER_SUBSCRIBED, "member:" + memberId + ":NEWSLETTER", null, now, null, Map.of()));
        if (referredBy != null && referral.trigger() == ReferralPolicy.Trigger.ON_ENROLLMENT) completeReferral(m, now);
        return m;
    }

    /** Chiamato dal consumer delle azioni: prima azione del membro → FIRST_ACTION e, se previsto, completamento del referral. */
    @Transactional
    public void onAction(String memberId, String actionType, Instant at) {
        Member m = find(memberId).orElse(null);
        if (m == null || !m.canEarn()) return;
        boolean first = jdbc.update("UPDATE memberservice.member SET first_action_at = ? WHERE id = ? AND first_action_at IS NULL", Timestamp.from(at), memberId) == 1;
        if (first && !EventTypes.ACTION_MEMBER_ENROLLED.equals(actionType))
            publishAction(memberId, new RewardingAction(EventTypes.ACTION_FIRST_ACTION, "member:" + memberId + ":FIRST_ACTION", actionType, at, null, Map.of("firstActionType", actionType)));
        if (m.referredBy() != null && referral.completes(actionType)) completeReferral(m, at);
    }

    private void completeReferral(Member referred, Instant at) {
        int updated = jdbc.update("UPDATE memberservice.member SET referral_completed_at = ? WHERE id = ? AND referral_completed_at IS NULL", Timestamp.from(at), referred.id());
        if (updated == 0) return;
        int year = at.atZone(java.time.ZoneId.of("Europe/Rome")).getYear();
        Long done = jdbc.queryForObject("SELECT count(*) FROM memberservice.member WHERE referred_by = ? AND referral_completed_at IS NOT NULL AND extract(year FROM referral_completed_at) = ?", Long.class, referred.referredBy(), year);
        if (done != null && done > referral.maxReferralsPerYear()) return; // oltre il tetto annuo: il presentato è comunque premiato dalle regole di adesione
        referral.completed(referred.referredBy(), referred.id(), at).forEach(a -> publishAction(a.actionType().equals(EventTypes.ACTION_REFERRAL_COMPLETED) ? referred.referredBy() : referred.id(), a));
    }

    @Transactional
    public Member update(String memberId, Map<String, String> labels, Map<String, Boolean> consents, Boolean profileCompleted) {
        Member m = find(memberId).orElseThrow();
        Map<String, String> l = new HashMap<>(m.labels()); if (labels != null) l.putAll(labels);
        Map<String, Boolean> c = new HashMap<>(m.consents()); if (consents != null) c.putAll(consents);
        Instant now = Instant.now();
        Instant completedAt = m.profileCompletedAt();
        if (Boolean.TRUE.equals(profileCompleted) && completedAt == null) {
            completedAt = now;
            publishAction(memberId, new RewardingAction(EventTypes.ACTION_PROFILE_COMPLETED, "member:" + memberId + ":PROFILE_COMPLETED", null, now, null, Map.of()));
        }
        if (consents != null && Boolean.TRUE.equals(consents.get("newsletter")) && !Boolean.TRUE.equals(m.consents().get("newsletter")))
            publishAction(memberId, new RewardingAction(EventTypes.ACTION_NEWSLETTER_SUBSCRIBED, "member:" + memberId + ":NEWSLETTER", null, now, null, Map.of()));
        jdbc.update("UPDATE memberservice.member SET labels = ?::jsonb, consents = ?::jsonb, profile_completed_at = ?, updated_at = now() WHERE id = ?",
                write(l), write(c), completedAt == null ? null : Timestamp.from(completedAt), memberId);
        Member out = new Member(m.id(), m.status(), m.enrolledAt(), m.enrollmentChannel(), l, c, m.referralCode(), m.referredBy(), completedAt);
        publishMember(out);
        return out;
    }

    @Transactional
    public Member setStatus(String memberId, Member.Status status) {
        Member m = find(memberId).orElseThrow();
        Member out = status == Member.Status.ANONYMIZED ? Anonymizer.anonymize(m, Instant.now())
                : new Member(m.id(), status, m.enrolledAt(), m.enrollmentChannel(), m.labels(), m.consents(), m.referralCode(), m.referredBy(), m.profileCompletedAt());
        jdbc.update("UPDATE memberservice.member SET status = ?, channel = ?, labels = ?::jsonb, consents = ?::jsonb, referral_code = ?, referred_by = ?, updated_at = now() WHERE id = ?",
                out.status().name(), out.enrollmentChannel(), write(out.labels()), write(out.consents()), out.referralCode(), out.referredBy(), memberId);
        publishMember(out);
        return out;
    }

    public Optional<Member> find(String memberId) {
        return jdbc.query("SELECT id, status, enrolled_at, channel, labels, consents, referral_code, referred_by, profile_completed_at FROM memberservice.member WHERE id = ?", rs -> {
            if (!rs.next()) return Optional.<Member>empty();
            return Optional.of(new Member(rs.getString(1), Member.Status.valueOf(rs.getString(2)), rs.getTimestamp(3).toInstant(), rs.getString(4),
                    readMap(rs.getString(5), String.class), readMap(rs.getString(6), Boolean.class), rs.getString(7), rs.getString(8),
                    rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant()));
        }, memberId);
    }

    private void publishMember(Member m) {
        var e = CanonicalEvents.of(EventTypes.MEMBER_V1, "urn:iren:loyalty:members", "member:" + m.id(), m);
        kafka.send(EventTypes.TOPIC_MEMBERS, m.id(), CanonicalEvents.serialize(e));
    }

    private void publishAction(String memberId, RewardingAction a) {
        var e = CanonicalEvents.of(EventTypes.ACTION_V1, "urn:iren:loyalty:members", "member:" + memberId, a);
        kafka.send(EventTypes.TOPIC_ACTIONS, memberId, CanonicalEvents.serialize(e));
    }

    private String write(Object o) { try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); } }
    private <V> Map<String, V> readMap(String s, Class<V> v) {
        try { return s == null ? Map.of() : json.readValue(s, json.getTypeFactory().constructMapType(Map.class, String.class, v)); }
        catch (Exception e) { return Map.of(); }
    }
}
