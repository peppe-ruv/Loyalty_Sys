package io.loyaltyhub.wallet.testbook;

import io.loyaltyhub.wallet.domain.MemberTier;
import io.loyaltyhub.wallet.domain.PointsLot;
import io.loyaltyhub.wallet.domain.WalletBalance;
import io.loyaltyhub.wallet.infra.MemberTierRepository;
import io.loyaltyhub.wallet.infra.PointsLotRepository;
import io.loyaltyhub.wallet.infra.WalletRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.loyaltyhub.wallet.testbook.WalTestbook.MAPPER;

/**
 * Supporto dei test d'integrazione del testbook TB-WAL: HTTP con l'intestazione {@code X-LH-Actor}, lettura dei fatti
 * e dell'audit dall'outbox (stessa transazione della scrittura, docs/06 §1), preparazione dei dati di un membro fresco.
 * La preparazione scrive lotto e saldo insieme, così resta vero l'invariante «saldo attivo = Σ lotti ACTIVE».
 */
final class WalItSupport {

    record Resp(int status, JsonNode body) {
        String code() {
            return body == null ? null : body.path("code").asString(null);
        }
    }

    private final RestClient rest;
    private final JdbcClient jdbc;
    private final WalletRepository wallets;
    private final MemberTierRepository memberTiers;
    private final PointsLotRepository lots;

    WalItSupport(int port, JdbcClient jdbc, WalletRepository wallets, MemberTierRepository memberTiers,
                 PointsLotRepository lots) {
        this.rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.memberTiers = memberTiers;
        this.lots = lots;
    }

    // ---------- HTTP ----------

    /** {@code actor}: {@code ADMIN}, {@code CARE}… (username fisso), {@code NONE} = header assente, altrimenti valore grezzo. */
    Resp http(HttpMethod method, String path, String actor, Object body) {
        RestClient.RequestBodySpec req = rest.method(method).uri(path).contentType(MediaType.APPLICATION_JSON);
        String header = actorHeader(actor);
        if (header != null) {
            req.header("X-LH-Actor", header);
        }
        if (body != null) {
            req.body(body instanceof String s ? s : MAPPER.writeValueAsString(body));
        }
        return req.exchange((rq, rs) -> {
            byte[] bytes = rs.getBody().readAllBytes();
            JsonNode node = bytes.length == 0 ? null : MAPPER.readTree(bytes);
            return new Resp(rs.getStatusCode().value(), node);
        });
    }

    Resp get(String path) {
        return http(HttpMethod.GET, path, "ANALYST", null);
    }

    static String actorHeader(String actor) {
        if (actor == null || "NONE".equals(actor)) {
            return null;
        }
        if ("INVALID".equals(actor)) {
            return "PIRATE:jack";
        }
        if (actor.contains(":")) {
            return actor;
        }
        return actor + ":" + actor.toLowerCase() + ".testbook";
    }

    // ---------- outbox: fatti e audit ----------

    /** Payload completi (envelope) dei fatti di un tipo con una data chiave, in ordine di scrittura. */
    List<JsonNode> facts(String type, String key) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE type = ? AND msg_key = ? ORDER BY created_at")
                .params("io.loyaltyhub.fact." + type, key).query(String.class).list()
                .stream().map(MAPPER::readTree).toList();
    }

    List<JsonNode> memberFacts(String type, String memberId) {
        return facts(type, memberId);
    }

    /** Voci di audit ({@code lh.audit.v1}) con chiave {@code entityType:entityId}. */
    List<JsonNode> audit(String key) {
        return jdbc.sql("SELECT payload::text FROM outbox WHERE topic = 'lh.audit.v1' AND msg_key = ? ORDER BY created_at")
                .param(key).query(String.class).list().stream().map(MAPPER::readTree).toList();
    }

    long countAuditSince(String action, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox WHERE topic = 'lh.audit.v1'
                          AND payload -> 'data' ->> 'action' = ? AND created_at >= ?
                        """)
                .params(action, Timestamp.from(since)).query(Long.class).single();
    }

    // ---------- preparazione ----------

    /** Membro fresco con wallet PTS/STS, livello, STS di periodo e stato indicati. */
    void member(String memberId, String tier, long periodSts, String status) {
        wallets.ensureExists(memberId, "PTS");
        wallets.ensureExists(memberId, "STS");
        memberTiers.set(memberId, tier, periodSts);
        memberTiers.updateStatus(memberId, status);
    }

    /** Lotto preparato con saldo coerente: ACTIVE → saldo attivo, PENDING → in attesa, EXHAUSTED/EXPIRED → remaining 0. */
    String lot(String memberId, String currency, long amount, String status, Instant earnedAt, Instant availableAt,
               Instant expiresAt) {
        String id = WalTestbook.freshId("LOT");
        long remaining = PointsLot.ACTIVE.equals(status) || PointsLot.PENDING.equals(status) ? amount : 0;
        wallets.ensureExists(memberId, currency);
        lots.insert(new PointsLot(id, memberId, currency, amount, remaining, status, earnedAt, availableAt, expiresAt, null));
        if (PointsLot.ACTIVE.equals(status)) {
            wallets.credit(memberId, currency, amount);
        } else if (PointsLot.PENDING.equals(status)) {
            wallets.creditPending(memberId, currency, amount);
        }
        return id;
    }

    // ---------- letture ----------

    WalletBalance balance(String memberId, String currency) {
        return wallets.find(memberId, currency).orElse(new WalletBalance(memberId, currency, 0, 0, 0, 0, 0));
    }

    MemberTier tier(String memberId) {
        return memberTiers.find(memberId).orElseThrow();
    }

    /** Tutti i lotti del membro (anche esauriti e scaduti), in ordine di inserimento. */
    List<Map<String, Object>> allLots(String memberId) {
        return jdbc.sql("SELECT * FROM points_lot WHERE member_id = ? ORDER BY id").param(memberId).query().listOfRows();
    }

    Map<String, Object> lotRow(String lotId) {
        return jdbc.sql("SELECT * FROM points_lot WHERE id = ?").param(lotId).query().singleRow();
    }

    /** Movimenti del libro mastro del membro (tutte le colonne), in ordine di scrittura. */
    List<Map<String, Object>> ledger(String memberId) {
        return jdbc.sql("SELECT * FROM ledger_entry WHERE member_id = ? ORDER BY created_at, id").param(memberId)
                .query().listOfRows();
    }

    List<Map<String, Object>> ledger(String memberId, String type) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : ledger(memberId)) {
            if (type.equals(r.get("type"))) {
                out.add(r);
            }
        }
        return out;
    }

    List<Map<String, Object>> consumptions(String ledgerEntryId) {
        return jdbc.sql("SELECT * FROM lot_consumption WHERE ledger_entry_id = ?").param(ledgerEntryId).query().listOfRows();
    }

    /** Σ remaining dei lotti ACTIVE (docs/03 §4.2: saldo attivo). */
    long activeLotsSum(String memberId, String currency) {
        return jdbc.sql("SELECT coalesce(sum(remaining), 0) FROM points_lot WHERE member_id = ? AND currency = ? AND status = 'ACTIVE'")
                .params(memberId, currency).query(Long.class).single();
    }

    static Instant instant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    static long num(Object n) {
        return ((Number) n).longValue();
    }
}
