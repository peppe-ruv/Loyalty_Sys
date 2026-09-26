package io.loyaltyhub.reward.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Anonimizzazione delle richieste premio di un membro (F-MBR-05, M7.5): le richieste e la loro cronologia restano
 * (sono movimenti), l'indirizzo di spedizione si cancella e i nomi noti spariscono dalle note.
 */
@Repository
public class RedemptionErasureRepository {

    private record Note(String id, String note) {
    }

    private final JdbcClient jdbc;

    public RedemptionErasureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // SPEC-GAP: Q-123 — l'indirizzo di spedizione (nome, via, città) è interamente personale: si cancella anche per le
    // richieste fisiche ancora da evadere (l'evasione di un membro anonimizzato non ha più un destinatario). Le note
    // libere dell'operatore vengono interamente svuotate (Q-369).
    public void erase(String memberId) {
        jdbc.sql("UPDATE redemption SET shipping = NULL WHERE member_id = ? AND shipping IS NOT NULL")
                .param(memberId).update();
        jdbc.sql("UPDATE redemption SET fulfilment_note = NULL WHERE member_id = ? AND fulfilment_note IS NOT NULL")
                .param(memberId).update();
        jdbc.sql("""
                        UPDATE redemption_history SET note = NULL
                        FROM redemption r
                        WHERE r.id = redemption_history.redemption_id AND r.member_id = ? AND redemption_history.note IS NOT NULL
                        """)
                .param(memberId).update();
    }
}
