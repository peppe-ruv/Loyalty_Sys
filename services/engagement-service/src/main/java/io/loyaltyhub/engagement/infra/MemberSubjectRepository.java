package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.common.identity.MemberSubjectRules;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Proiezione locale del legame {@code subjectRef → membro} sulla tabella snapshot {@code engagement_member_snapshot}
 * (F2-SEC-09, ADR-048, Q-550). Solo SQL costante e parametrico (regola 19); la decisione è di {@link MemberSubjectRules}.
 */
@Repository
public class MemberSubjectRepository {

    private final JdbcClient jdbc;

    public MemberSubjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Crea la riga del membro se manca (con lo stato indicato), senza toccarne una esistente. */
    public void ensureRow(String memberId, String status) {
        jdbc.sql("INSERT INTO engagement_member_snapshot (member_id, status) VALUES (?, ?) ON CONFLICT (member_id) DO NOTHING")
                .params(memberId, status).update();
    }

    /** Stato del legame del membro, con la riga bloccata fino al commit; vuoto se la riga non esiste. */
    public Optional<MemberSubjectRules.Current> lockCurrent(String memberId) {
        return jdbc.sql("SELECT subject_erased, subject_ref_at FROM engagement_member_snapshot WHERE member_id = ? FOR UPDATE")
                .param(memberId)
                .query((rs, n) -> new MemberSubjectRules.Current(rs.getBoolean("subject_erased"), instant(rs.getTimestamp("subject_ref_at"))))
                .optional();
    }

    /** L'altro membro che detiene {@code subjectRef}, con la riga bloccata fino al commit. */
    public Optional<MemberSubjectRules.Holder> lockHolder(String subjectRef, String exceptMemberId) {
        return jdbc.sql("SELECT member_id, subject_ref_at FROM engagement_member_snapshot WHERE subject_ref = ? AND member_id <> ? FOR UPDATE")
                .params(subjectRef, exceptMemberId)
                .query((rs, n) -> new MemberSubjectRules.Holder(rs.getString("member_id"), instant(rs.getTimestamp("subject_ref_at"))))
                .optional();
    }

    /** Toglie lo pseudonimo al detentore sorpassato (il suo istante resta: un replay vecchio non lo riprende). */
    public void clearRef(String memberId) {
        jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = NULL WHERE member_id = ?").param(memberId).update();
    }

    /**
     * {@code subject_ref = r}, {@code subject_ref_at = at}. Con {@code at == null} (fatto senza {@code time}) l'istante
     * del legame resta com'è: un fatto senza tempo non diventa mai il più recente (Q-550).
     */
    public void link(String memberId, String subjectRef, Instant at) {
        if (at == null) {
            jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = ? WHERE member_id = ?").params(subjectRef, memberId).update();
            return;
        }
        jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = ?, subject_ref_at = ? WHERE member_id = ?")
                .params(subjectRef, Timestamp.from(at), memberId).update();
    }

    /** {@code subject_ref = NULL}, {@code subject_ref_at = at}; con {@code at == null} l'istante resta com'è. */
    public void unlink(String memberId, Instant at) {
        if (at == null) {
            jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = NULL WHERE member_id = ?").param(memberId).update();
            return;
        }
        jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = NULL, subject_ref_at = ? WHERE member_id = ?")
                .params(Timestamp.from(at), memberId).update();
    }

    /**
     * Lapide definitiva dell'anonimizzazione: nessun legame, nessun replay lo ripristina. Vale anche senza {@code time}
     * (la cancellazione non aspetta un orologio); allora {@code subject_ref_at} resta com'è.
     */
    public void erase(String memberId, Instant at) {
        if (at == null) {
            jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = NULL, subject_erased = true WHERE member_id = ?")
                    .param(memberId).update();
            return;
        }
        jdbc.sql("UPDATE engagement_member_snapshot SET subject_ref = NULL, subject_erased = true, subject_ref_at = ? WHERE member_id = ?")
                .params(Timestamp.from(at), memberId).update();
    }

    /** Il membro legato a {@code subjectRef}, se non cancellato (lookup del token, non autorevole). */
    public Optional<String> memberIdBySubjectRef(String subjectRef) {
        return jdbc.sql("SELECT member_id FROM engagement_member_snapshot WHERE subject_ref = ? AND NOT subject_erased")
                .param(subjectRef).query(String.class).optional();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
