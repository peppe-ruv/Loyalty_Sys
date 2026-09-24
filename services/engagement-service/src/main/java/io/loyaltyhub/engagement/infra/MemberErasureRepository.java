package io.loyaltyhub.engagement.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import io.loyaltyhub.engagement.domain.WebhookSignature;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Anonimizzazione di un membro in engagement (F-MBR-05, M7.5). I messaggi consegnati e le consegne webhook restano
 * (sono il registro di ciò che è successo), ma senza dati personali:
 * <ul>
 *   <li>snapshot: nome cancellato, stato {@code ANONYMIZED} (nessun nuovo messaggio, Q-70);</li>
 *   <li>messaggi: il nome reso nei template ({@code {{member.firstName}}}) diventa "Membro anonimo";</li>
 *   <li>consegne webhook: il CloudEvent conservato perde i campi personali e viene rifirmato col segreto del webhook,
 *       così un ritento resta verificabile dal destinatario.</li>
 * </ul>
 */
@Repository
public class MemberErasureRepository {

    private record Message(String id, String title, String body) {
    }

    private record Delivery(String id, String payload, String secret) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MemberErasureRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // SPEC-GAP: Q-125 — engagement conosce solo il nome (non il cognome): nei testi già resi si sostituisce quello;
    // un template che rendesse altri dati personali da `data.*` li lascerebbe nel testo (nessun template seed lo fa).
    public void erase(String memberId) {
        String firstName = jdbc.sql("SELECT first_name FROM engagement_member_snapshot WHERE member_id = ?")
                .param(memberId).query(String.class).optional().orElse(null);
        List<String> tokens = PersonalData.tokens(java.util.Collections.singletonList(firstName));

        if (!tokens.isEmpty()) {
            for (Message m : jdbc.sql("SELECT id, title, body FROM inbox_message WHERE member_id = ?").param(memberId)
                    .query((rs, n) -> new Message(rs.getString("id"), rs.getString("title"), rs.getString("body"))).list()) {
                String title = PersonalData.scrub(m.title(), tokens);
                String body = PersonalData.scrub(m.body(), tokens);
                if (!title.equals(m.title()) || !body.equals(m.body())) {
                    jdbc.sql("UPDATE inbox_message SET title = ?, body = ? WHERE id = ?").params(title, body, m.id()).update();
                }
            }
        }

        for (Delivery d : jdbc.sql("""
                        SELECT d.id, d.payload, w.secret FROM webhook_delivery d JOIN webhook w ON w.id = d.webhook_id
                        WHERE d.member_id = ?
                        """)
                .param(memberId)
                .query((rs, n) -> new Delivery(rs.getString("id"), rs.getString("payload"), rs.getString("secret"))).list()) {
            String payload = redactPayload(d.payload(), tokens);
            if (!payload.equals(d.payload())) {
                jdbc.sql("UPDATE webhook_delivery SET payload = ?, signature = ? WHERE id = ?")
                        .params(payload, WebhookSignature.sign(d.secret(), payload), d.id()).update();
            }
        }

        jdbc.sql("""
                        INSERT INTO engagement_member_snapshot (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET first_name = NULL, status = excluded.status, updated_at = now()
                        """)
                .params(memberId, PersonalData.ANONYMIZED).update();
    }

    private String redactPayload(String payload, List<String> tokens) {
        try {
            JsonNode node = mapper.readTree(payload);
            return mapper.writeValueAsString(PersonalData.redactAndScrub(node, tokens));
        } catch (RuntimeException e) {
            return PersonalData.scrub(payload, tokens); // corpo non JSON: almeno il nome noto
        }
    }
}
