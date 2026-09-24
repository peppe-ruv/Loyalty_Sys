package io.loyaltyhub.ingestion.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Anonimizzazione di un membro in ingestion (F-MBR-05, M7.5): cancella e-mail e identificativo esterno da
 * {@code member_index} e ripulisce le righe di {@code inbound_event} che lo riguardano (subject {@code email:…}/
 * {@code external:…} e payload conservato). Le righe restano: sono i movimenti in ingresso.
 */
@Repository
public class MemberErasureRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MemberErasureRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private record Keys(String email, String externalId) {
    }

    private record Row(String id, String subject, String payload, String rejectDetail) {
    }

    /** Idempotente: una seconda chiamata non trova più nulla da cambiare. */
    // SPEC-GAP: Q-128 — cancellati e-mail e id esterno dall'indice, un evento successivo che cita il membro per
    // email:/external: non si risolve più (UNMATCHED) invece di REJECTED/MEMBER_NOT_ACTIVE; per member:<id> resta REJECTED.
    // Scelta conservativa: l'indice non conserva dati che colleghino l'id a una persona.
    public void erase(String memberId) {
        Keys keys = jdbc.sql("SELECT email_lower, external_id FROM member_index WHERE member_id = ?").param(memberId)
                .query((rs, n) -> new Keys(rs.getString("email_lower"), rs.getString("external_id")))
                .optional().orElse(new Keys(null, null));
        jdbc.sql("""
                        INSERT INTO member_index (member_id, status) VALUES (?, ?)
                        ON CONFLICT (member_id) DO UPDATE SET external_id = NULL, email_lower = NULL, status = excluded.status
                        """)
                .params(memberId, PersonalData.ANONYMIZED).update();

        List<String> subjects = new ArrayList<>();
        if (keys.email() != null) {
            subjects.add("email:" + keys.email().toLowerCase(Locale.ROOT));
        }
        if (keys.externalId() != null) {
            subjects.add("external:" + keys.externalId().toLowerCase(Locale.ROOT));
        }
        List<String> tokens = PersonalData.tokens(java.util.Arrays.asList(keys.email(), keys.externalId()));
        StringBuilder where = new StringBuilder("member_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(memberId);
        for (String s : subjects) {
            where.append(" OR lower(subject) = ?");
            args.add(s);
        }
        List<Row> rows = jdbc.sql("SELECT id, subject, payload::text AS payload, reject_detail FROM inbound_event WHERE "
                        + where)
                .params(args)
                .query((rs, n) -> new Row(rs.getString("id"), rs.getString("subject"), rs.getString("payload"),
                        rs.getString("reject_detail")))
                .list();
        String memberSubject = "member:" + memberId;
        for (Row r : rows) {
            JsonNode payload = PersonalData.redactAndScrub(mapper.readTree(r.payload()), tokens);
            if (payload instanceof ObjectNode obj && obj.has("subject") && !obj.path("subject").asString("").startsWith("member:")) {
                obj.put("subject", memberSubject);
            }
            String subject = r.subject() != null && r.subject().startsWith("member:") ? r.subject() : memberSubject;
            jdbc.sql("UPDATE inbound_event SET subject = ?, payload = cast(? AS jsonb), reject_detail = ? WHERE id = ?")
                    .params(subject, mapper.writeValueAsString(payload), PersonalData.scrub(r.rejectDetail(), tokens), r.id())
                    .update();
        }
    }
}
