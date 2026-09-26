package io.loyaltyhub.insight.infra;

import io.loyaltyhub.common.privacy.PersonalData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Anonimizzazione di un membro nelle copie conservate da insight (F-MBR-05, M7.5): event store, voci di audit e voci
 * DLQ restano (sono la storia del sistema: tracciati, KPI, movimenti), ma senza dati personali.
 * <ol>
 *   <li>Raccoglie i valori personali già noti del membro dalle sue copie (snapshot di {@code member.registered/updated}
 *       e {@code before/after} dell'audit {@code MEMBER}): nome, cognome, nickname, e-mail, telefono, id esterno.</li>
 *   <li>Sulle righe del membro (eventi con {@code member_id}, audit sull'entità, voci DLQ) toglie le chiavi personali
 *       ({@link PersonalData#KEYS}) e sostituisce quei valori nel testo con "Membro anonimo".</li>
 *   <li>Sulle righe di altre entità sostituisce solo i valori inequivocabili (e-mail, nome completo, telefono, id
 *       esterno), mai il solo nome di battesimo (potrebbe essere di un altro membro).</li>
 * </ol>
 * Doppia lettura {@code member.*:1}/{@code :2} (ADR-032, Q-346, docs/18 §3.4): le copie {@code :1} portano nome,
 * cognome, soprannome ed e-mail e si ripuliscono come sempre; le copie {@code :2} non li hanno (i campi assenti si
 * saltano) e portano {@code emailHash}, che sulle righe del membro si toglie e sulle altre si sostituisce come
 * valore inequivocabile. {@code locale}, {@code birthYear} e {@code province} restano, come stato ed etichette.
 */
// SPEC-GAP: Q-122 — PersonalData.KEYS (lh-common) non elenca emailHash di member.*:2 (Q-367): è uno pseudonimo
// reversibile da chi ha LH_PSEUDONYM_KEY, quindi scelta conservativa, insight lo toglie in anonimizzazione come le
// chiavi personali; birthYear, province e locale sono x-lh-pii:false (ADR-032) e restano.
// SPEC-GAP: Q-126 — docs/servizi/insight-service.md non dice come trattare le copie degli eventi di un membro
// anonimizzato: scelta conservativa, si riscrivono le copie (non si maschera in lettura), una volta per fatto di
// anonimizzazione ricevuto. Un evento del membro con dati personali che arrivasse dopo entrambi i fatti non sarebbe
// ripulito: member-service non ne emette più (modifiche bloccate) e i suoi audit di anonimizzazione non ne contengono.
@Repository
public class MemberRedactionRepository {

    /** Pseudonimo dell'e-mail in {@code member.registered/updated:2} (contracts/events/fact, Q-367). */
    static final String EMAIL_HASH = "emailHash";

    private static final Set<String> TOKEN_KEYS =
            Set.of("firstName", "lastName", "nickname", "email", "phone", "externalId", EMAIL_HASH);

    private record Row(String id, String json) {
    }

    private record AuditRow(String id, String summary, String before, String after) {
    }

    private record DlqRow(String id, String payload, String errorMessage) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public MemberRedactionRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return quante righe sono state riscritte (idempotente: alla seconda chiamata 0) */
    public int redact(String memberId) {
        Known known = known(memberId);
        int changed = 0;

        // 1. event store: righe del membro e voci di audit sull'entità membro.
        for (Row r : jdbc.sql("""
                        SELECT event_id AS id, payload::text AS json FROM event_store
                        WHERE member_id = ? OR (family = 'AUDIT' AND payload -> 'data' ->> 'entityId' = ?)
                        """)
                .params(memberId, memberId).query((rs, n) -> new Row(rs.getString("id"), rs.getString("json"))).list()) {
            changed += rewriteEvent(r, known.all(), true);
        }
        // 1b. event store: altre righe che citano valori inequivocabili del membro.
        for (String token : known.strong()) {
            for (Row r : jdbc.sql("""
                            SELECT event_id AS id, payload::text AS json FROM event_store
                            WHERE payload::text ILIKE ? AND (member_id IS NULL OR member_id <> ?)
                            """)
                    .params(like(token), memberId).query((rs, n) -> new Row(rs.getString("id"), rs.getString("json"))).list()) {
                changed += rewriteEvent(r, known.strong(), false);
            }
        }

        // 2. voci di audit.
        for (AuditRow a : auditRows("entity_id = ?", memberId)) {
            changed += rewriteAudit(a, known.all(), true);
        }
        for (String token : known.strong()) {
            for (AuditRow a : auditRows("entity_id <> ? AND (summary ILIKE ? OR before::text ILIKE ? OR after::text ILIKE ?)",
                    memberId, like(token), like(token), like(token))) {
                changed += rewriteAudit(a, known.strong(), false);
            }
        }

        // 3. voci DLQ del membro.
        for (DlqRow d : jdbc.sql("SELECT id, payload::text AS payload, error_message FROM dlq_entry WHERE member_id = ?")
                .param(memberId)
                .query((rs, n) -> new DlqRow(rs.getString("id"), rs.getString("payload"), rs.getString("error_message")))
                .list()) {
            String payload = json(stripPseudonyms(PersonalData.redactAndScrub(mapper.readTree(d.payload()), known.all())));
            String message = PersonalData.scrub(d.errorMessage(), known.all());
            if (!Objects.equals(payload, normalize(d.payload())) || !Objects.equals(message, d.errorMessage())) {
                jdbc.sql("UPDATE dlq_entry SET payload = cast(? AS jsonb), error_message = ? WHERE id = ?")
                        .params(payload, message, d.id()).update();
                changed++;
            }
        }
        return changed;
    }

    // ---------- valori noti ----------

    private record Known(List<String> all, List<String> strong) {
    }

    private Known known(String memberId) {
        Set<String> first = new LinkedHashSet<>();
        Set<String> last = new LinkedHashSet<>();
        Set<String> others = new LinkedHashSet<>();
        Set<String> strong = new LinkedHashSet<>();
        List<JsonNode> sources = new ArrayList<>();
        jdbc.sql("""
                        SELECT payload -> 'data' AS d FROM event_store
                        WHERE member_id = ? AND short_type IN ('member.registered', 'member.updated')
                        """)
                .param(memberId).query((rs, n) -> rs.getString("d")).list()
                .forEach(s -> sources.add(s == null ? null : mapper.readTree(s)));
        jdbc.sql("SELECT before::text AS b, after::text AS a FROM audit_entry WHERE entity_type = 'MEMBER' AND entity_id = ?")
                .param(memberId).query((rs, n) -> new String[]{rs.getString("b"), rs.getString("a")}).list()
                .forEach(pair -> {
                    for (String s : pair) {
                        sources.add(s == null ? null : mapper.readTree(s));
                    }
                });
        for (JsonNode d : sources) {
            if (d == null || !d.isObject()) {
                continue;
            }
            String f = text(d, "firstName");
            String l = text(d, "lastName");
            if (f != null) first.add(f);
            if (l != null) last.add(l);
            if (f != null && l != null) strong.add(f + " " + l);
            for (String k : TOKEN_KEYS) {
                String v = text(d, k);
                if (v == null || k.equals("firstName") || k.equals("lastName")) {
                    continue;
                }
                others.add(v);
                if (!k.equals("nickname")) {
                    strong.add(v);
                }
            }
        }
        List<String> all = new ArrayList<>(strong);
        all.addAll(first);
        all.addAll(last);
        all.addAll(others);
        return new Known(PersonalData.tokens(all), PersonalData.tokens(strong));
    }

    // ---------- riscritture ----------

    private int rewriteEvent(Row r, List<String> tokens, boolean stripKeys) {
        String redacted = rewriteJson(r.json(), tokens, stripKeys);
        if (redacted.equals(normalize(r.json()))) {
            return 0;
        }
        jdbc.sql("UPDATE event_store SET payload = cast(? AS jsonb) WHERE event_id = ?").params(redacted, r.id()).update();
        return 1;
    }

    private List<AuditRow> auditRows(String where, Object... args) {
        return jdbc.sql("SELECT id, summary, before::text AS b, after::text AS a FROM audit_entry WHERE " + where)
                .params(List.of(args))
                .query((rs, n) -> new AuditRow(rs.getString("id"), rs.getString("summary"), rs.getString("b"), rs.getString("a")))
                .list();
    }

    private int rewriteAudit(AuditRow a, List<String> tokens, boolean stripKeys) {
        String summary = PersonalData.scrub(a.summary(), tokens);
        String before = rewriteJson(a.before(), tokens, stripKeys);
        String after = rewriteJson(a.after(), tokens, stripKeys);
        if (Objects.equals(summary, a.summary()) && Objects.equals(before, normalize(a.before()))
                && Objects.equals(after, normalize(a.after()))) {
            return 0;
        }
        jdbc.sql("UPDATE audit_entry SET summary = ?, before = cast(? AS jsonb), after = cast(? AS jsonb) WHERE id = ?")
                .params(summary, before, after, a.id()).update();
        return 1;
    }

    private String rewriteJson(String raw, List<String> tokens, boolean stripKeys) {
        if (raw == null) {
            return null;
        }
        JsonNode node = mapper.readTree(raw);
        if (stripKeys) {
            return json(stripPseudonyms(PersonalData.redactAndScrub(node, tokens)));
        }
        return json(PersonalData.scrubAll(node, tokens));
    }

    /** Toglie {@link #EMAIL_HASH} a ogni livello (oggetti e array) dalla copia già ripulita; {@code null} resta tale. */
    static JsonNode stripPseudonyms(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove(EMAIL_HASH);
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                stripPseudonyms(e.getValue());
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode item : arr) {
                stripPseudonyms(item);
            }
        }
        return node;
    }

    private String normalize(String raw) {
        return raw == null ? null : json(mapper.readTree(raw));
    }

    private String json(JsonNode node) {
        return mapper.writeValueAsString(node);
    }

    private static String like(String token) {
        return "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String text(JsonNode d, String field) {
        JsonNode v = d.get(field);
        return v == null || v.isNull() || !v.isString() || v.asString().isBlank() ? null : v.asString().trim();
    }
}
