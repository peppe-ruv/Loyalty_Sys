package io.loyaltyhub.member.infra;

import io.loyaltyhub.member.domain.MemberIdentity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Persistenza del legame account↔membro {@code member_identity} (F2-IAM-03, ADR-048, Q-551). Solo SQL costante e
 * parametrico (regola 19). La colonna {@code subject} è un dato personale: non viene mai letta in chiaro fuori da
 * questo repository se non per {@link #insert} (la lookup usa solo {@code subject_ref}).
 */
@Repository
public class MemberIdentityRepository {

    private final JdbcClient jdbc;

    public MemberIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserisce il legame; l'indice unico su {@code (issuer, subject)} e su {@code subject_ref} impedisce doppioni. */
    public void insert(MemberIdentity identity) {
        jdbc.sql("""
                        INSERT INTO member_identity (member_id, issuer, subject, subject_ref, linked_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(identity.memberId(), identity.issuer(), identity.subject(), identity.subjectRef(),
                        Timestamp.from(identity.linkedAt() == null ? Instant.now() : identity.linkedAt()))
                .update();
    }

    /**
     * Blocca, fino alla fine della transazione, chi registra lo stesso account (advisory lock su {@code subject_ref}):
     * la seconda richiesta attende, rilegge il legame e risponde senza provocare una violazione dell'indice unico (che
     * porterebbe il {@code sub} nel {@code DETAIL} dell'errore del database, regola 20). Da chiamare in una transazione.
     */
    public void lockSubject(String subjectRef) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param(subjectRef).query((rs, n) -> 1).single();
    }

    /** Membro legato all'account con questo pseudonimo (la lookup autorevole di {@code MemberSubjectLookup}). */
    public Optional<String> memberIdBySubjectRef(String subjectRef) {
        return jdbc.sql("SELECT member_id FROM member_identity WHERE subject_ref = ?").param(subjectRef)
                .query(String.class).optional();
    }

    /** Pseudonimo del legame di un membro, se ce n'è uno (per {@code member.updated}, Q-552). */
    public Optional<String> subjectRefOf(String memberId) {
        return jdbc.sql("SELECT subject_ref FROM member_identity WHERE member_id = ?").param(memberId)
                .query(String.class).optional();
    }

    /** Cancella il legame di un membro (anonimizzazione, D11). {@code true} se c'era. */
    public boolean deleteByMemberId(String memberId) {
        return jdbc.sql("DELETE FROM member_identity WHERE member_id = ?").param(memberId).update() > 0;
    }

    /** Reset demo: prima dei membri (chiave esterna). */
    public void deleteAll() {
        jdbc.sql("DELETE FROM member_identity").update();
    }
}
