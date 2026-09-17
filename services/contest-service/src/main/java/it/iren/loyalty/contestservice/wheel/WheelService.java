package it.iren.loyalty.contestservice.wheel;

import it.iren.loyalty.contestservice.instantwin.InstantWinService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Giro della ruota (RF-95): verifica attività, giri residui, addebita il costo, estrae lo spicchio (in modalità instant
 * win chiede prima l'esito al modulo periziato), applica i limiti di business (stock, budget, vincite giornaliere) e
 * registra il giro. Tutto in una transazione, come la giocata (RF-51).
 */
@Service
public class WheelService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WheelService.class);

    public record Result(UUID spinId, String slotId, String label, boolean won, String rewardId, String wallet, long units, int spinsLeft) {}
    public static class SpinRejected extends RuntimeException { public SpinRejected(String m) { super(m); } }

    private final JdbcTemplate jdbc;
    private final WheelSource wheels;
    private final InstantWinService instantWin;
    private final RestClient ledger, catalog;
    private final SecureRandom rnd = new SecureRandom();
    private final it.iren.loyalty.common.metrics.LoyaltyMetrics metrics;
    private final it.iren.loyalty.contestservice.ContestEvents events;

    public WheelService(JdbcTemplate jdbc, WheelSource wheels, InstantWinService instantWin, RestClient.Builder b, it.iren.loyalty.common.metrics.LoyaltyMetrics metrics, it.iren.loyalty.contestservice.ContestEvents events) {
        this.jdbc = jdbc; this.wheels = wheels; this.instantWin = instantWin; this.metrics = metrics; this.events = events;
        this.ledger = b.baseUrl(System.getenv().getOrDefault("LEDGER_URL", "http://ledger:8083")).build();
        this.catalog = b.baseUrl(System.getenv().getOrDefault("CATALOG_URL", "http://catalog-redemption:8085")).build();
    }

    @Transactional
    public Result spin(String wheelId, String memberId, String deviceFingerprint, String ip) {
        FortuneWheel w = wheels.byId(wheelId).orElseThrow(() -> new SpinRejected("UNKNOWN_WHEEL"));
        Instant now = Instant.now();
        if (!w.isActiveAt(now)) throw new SpinRejected("NOT_ACTIVE");
        long spins = jdbc.queryForObject("SELECT count(*) FROM contestservice.wheel_spin WHERE wheel_id = ? AND member_id = ? AND spun_at >= ?", Long.class, wheelId, memberId, Timestamp.from(periodStart(now, w.spinsPeriod())));
        if (w.spinsPerMember() > 0 && spins >= w.spinsPerMember()) throw new SpinRejected("NO_SPINS_LEFT");
        UUID spinId = UUID.randomUUID();
        String costKey = "wheel:" + spinId + ":COST";
        if (w.costUnits() > 0) {
            try { ledger.post().uri("/v1/ledger/debits").body(Map.of("memberId", memberId, "actionKey", costKey, "points", w.costUnits(), "reason", "WHEEL_SPIN", "currency", w.costWallet() == null ? "PREMIO" : w.costWallet())).retrieve().toBodilessEntity(); }
            catch (org.springframework.web.client.HttpClientErrorException.Conflict e) { throw new SpinRejected("INSUFFICIENT_BALANCE"); }
        }
        try {
            return play(w, wheelId, memberId, deviceFingerprint, ip, now, spinId, spins);
        } catch (RuntimeException e) {
            // Il costo della giocata è già addebitato su un altro servizio: il rollback locale non lo
            // annulla. Senza compensazione il membro pagherebbe una giocata mai avvenuta (RF-97).
            if (w.costUnits() > 0) compensate(costKey, e);
            throw e;
        }
    }

    /** Storno di compensazione; è idempotente, quindi un tentativo perso si può ripetere a mano (RI-08). */
    private void compensate(String costKey, RuntimeException cause) {
        try {
            ledger.post().uri("/v1/ledger/reversals/{k}", costKey).retrieve().toBodilessEntity();
        } catch (RuntimeException failed) {
            log.error("storno di compensazione non riuscito per {}: {} (causa originale: {})", costKey, failed, cause.toString());
        }
    }

    private Result play(FortuneWheel w, String wheelId, String memberId, String deviceFingerprint, String ip,
                        Instant now, UUID spinId, long spins) {
        boolean instantWon = w.mode() == FortuneWheel.Mode.INSTANT_WIN_BACKED && instantWin.play(UUID.fromString(w.contestId()), memberId, deviceFingerprint, ip).won();
        List<FortuneWheel.Slot> eligible = w.eligible(instantWon).stream().filter(s -> s.stock() < 0 || remainingStock(wheelId, s) > 0).toList();
        FortuneWheel.Slot slot = w.draw(rnd, eligible);
        if (slot == null) slot = w.slots().stream().filter(FortuneWheel.Slot::isEmpty).findFirst().orElseThrow(() -> new SpinRejected("NO_SLOT_AVAILABLE"));
        // livello di business logic: budget e vincite giornaliere possono trasformare una vincita in spicchio vuoto
        boolean won = !slot.isEmpty();
        if (won && w.maxWinsPerMemberPerDay() > 0) {
            long winsToday = jdbc.queryForObject("SELECT count(*) FROM contestservice.wheel_spin WHERE wheel_id = ? AND member_id = ? AND won AND spun_at >= ?", Long.class, wheelId, memberId, Timestamp.from(now.atZone(ZoneId.of("Europe/Rome")).toLocalDate().atStartOfDay(ZoneId.of("Europe/Rome")).toInstant()));
            if (winsToday >= w.maxWinsPerMemberPerDay()) won = false;
        }
        if (won && w.budgetUnits() > 0 && slot.units() > 0) {
            Long spent = jdbc.queryForObject("SELECT coalesce(sum(units),0) FROM contestservice.wheel_spin WHERE wheel_id = ? AND won", Long.class, wheelId);
            if (spent + slot.units() > w.budgetUnits()) won = false;
        }
        jdbc.update("INSERT INTO contestservice.wheel_spin(id, wheel_id, member_id, slot_id, won, reward_id, wallet, units, spun_at, device_fingerprint, ip) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                spinId, wheelId, memberId, slot.id(), won, won ? slot.rewardId() : null, won ? slot.wallet() : null, won ? slot.units() : 0, Timestamp.from(now), deviceFingerprint, ip);
        if (won && slot.rewardId() != null) catalog.post().uri("/v1/grants").body(Map.of("memberId", memberId, "rewardId", slot.rewardId(), "grantKey", "wheel:" + spinId)).retrieve().toBodilessEntity();
        if (won && slot.units() > 0) ledger.post().uri("/v1/ledger/postings").body(Map.of("memberId", memberId, "actionKey", "wheel:" + spinId + ":WIN", "currency", slot.wallet() == null ? "PREMIO" : slot.wallet(), "amount", slot.units(), "reason", "WHEEL:" + wheelId)).retrieve().toBodilessEntity();
        metrics.spin(wheelId, won);
        events.spun(wheelId, memberId, spinId.toString(), won, slot.id(), now);
        return new Result(spinId, slot.id(), slot.label(), won, won ? slot.rewardId() : null, won ? slot.wallet() : null, won ? slot.units() : 0, w.spinsPerMember() > 0 ? (int) (w.spinsPerMember() - spins - 1) : -1);
    }

    private long remainingStock(String wheelId, FortuneWheel.Slot s) {
        Long used = jdbc.queryForObject("SELECT count(*) FROM contestservice.wheel_spin WHERE wheel_id = ? AND slot_id = ? AND won", Long.class, wheelId, s.id());
        return s.stock() - used;
    }

    private static Instant periodStart(Instant at, Period p) {
        var z = at.atZone(ZoneId.of("Europe/Rome"));
        return switch (p == null ? Period.TOTAL : p) {
            case HOUR -> z.truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant();
            case DAY -> z.toLocalDate().atStartOfDay(ZoneId.of("Europe/Rome")).toInstant();
            case WEEK -> z.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay(ZoneId.of("Europe/Rome")).toInstant();
            case MONTH -> z.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.of("Europe/Rome")).toInstant();
            case TOTAL -> Instant.EPOCH;
        };
    }

    public interface WheelSource { java.util.Optional<FortuneWheel> byId(String id); List<FortuneWheel> all(); }
}
