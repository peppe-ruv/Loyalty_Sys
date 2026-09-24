package io.loyaltyhub.gamification.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Anonimizzazione di un membro nel gioco (F-MBR-05, M7.5): nelle classifiche e nei vincitori compare il segnaposto
 * "Membro anonimo"; giocate, punteggi e badge restano (sono movimenti e statistiche).
 */
@Repository
public class MemberErasureRepository {

    private final JdbcClient jdbc;

    public MemberErasureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // SPEC-GAP: Q-B5 — la nota di consegna di un premio vinto è testo libero dell'operatore (può contenere un
    // indirizzo) e gamification non conosce il nome reale per ripulirla: scelta conservativa, si cancella.
    public void erase(String memberId) {
        jdbc.sql("""
                        INSERT INTO gamification_member_snapshot (member_id, nickname, status) VALUES (?, ?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET nickname = excluded.nickname, status = excluded.status,
                          updated_at = now()
                        """)
                .params(memberId, PersonalData.PLACEHOLDER, PersonalData.ANONYMIZED).update();
        jdbc.sql("UPDATE play SET delivery_note = NULL WHERE member_id = ? AND delivery_note IS NOT NULL")
                .param(memberId).update();
    }
}
