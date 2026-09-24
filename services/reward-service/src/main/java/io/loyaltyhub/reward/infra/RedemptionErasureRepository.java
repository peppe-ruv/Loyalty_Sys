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

    // SPEC-GAP: Q-B4 — l'indirizzo di spedizione (nome, via, città) è interamente personale: si cancella anche per le
    // richieste fisiche ancora da evadere (l'evasione di un membro anonimizzato non ha più un destinatario). Le note
    // libere dell'operatore restano, con i nomi noti (nome/cognome dello snapshot) sostituiti da "Membro anonimo".
    public void erase(String memberId, List<String> knownTokens) {
        // Anche il nome scritto nell'indirizzo (può differire da quello anagrafico) si cerca poi nelle note.
        List<String> names = jdbc.sql("""
                        SELECT DISTINCT shipping ->> 'name' FROM redemption
                        WHERE member_id = ? AND shipping IS NOT NULL AND shipping ->> 'name' IS NOT NULL
                        """)
                .param(memberId).query(String.class).list();
        List<String> tokens = new java.util.ArrayList<>(knownTokens);
        tokens.addAll(PersonalData.tokens(names));
        jdbc.sql("UPDATE redemption SET shipping = NULL WHERE member_id = ? AND shipping IS NOT NULL")
                .param(memberId).update();
        if (tokens.isEmpty()) {
            return;
        }
        for (Note n : jdbc.sql("SELECT id, fulfilment_note AS note FROM redemption WHERE member_id = ? AND fulfilment_note IS NOT NULL")
                .param(memberId).query((rs, i) -> new Note(rs.getString("id"), rs.getString("note"))).list()) {
            String scrubbed = PersonalData.scrub(n.note(), tokens);
            if (!scrubbed.equals(n.note())) {
                jdbc.sql("UPDATE redemption SET fulfilment_note = ? WHERE id = ?").params(scrubbed, n.id()).update();
            }
        }
        for (Note n : jdbc.sql("""
                        SELECT h.id, h.note FROM redemption_history h JOIN redemption r ON r.id = h.redemption_id
                        WHERE r.member_id = ? AND h.note IS NOT NULL
                        """)
                .param(memberId).query((rs, i) -> new Note(rs.getString("id"), rs.getString("note"))).list()) {
            String scrubbed = PersonalData.scrub(n.note(), tokens);
            if (!scrubbed.equals(n.note())) {
                jdbc.sql("UPDATE redemption_history SET note = ? WHERE id = ?").params(scrubbed, n.id()).update();
            }
        }
    }
}
