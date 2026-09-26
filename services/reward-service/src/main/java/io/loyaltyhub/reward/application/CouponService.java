package io.loyaltyhub.reward.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.reward.domain.Coupon;
import io.loyaltyhub.reward.domain.CouponCodes;
import io.loyaltyhub.reward.domain.CouponPool;
import io.loyaltyhub.reward.domain.CouponStatus;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.infra.CouponRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pool e codici coupon (docs/servizi/reward-service.md §3, §5; F-CPN-01..03, BO-12): creazione pool, generazione
 * con seme, import, verifica alla cassa, uso, annullo, emissione a un membro e scadenza.
 */
@Service
public class CouponService {

    public record PoolRequest(String code, String name, String prefix, Integer validityDays) {
    }

    public record RewardRef(String id, String code, String name, String status) {
    }

    public record PoolView(String id, String code, String name, String prefix, int validityDays,
                           Map<CouponStatus, Long> counts, long total, List<RewardRef> rewards) {
    }

    public record GenerateResult(int generated, long seed, long available) {
    }

    public record ImportResult(int imported, List<String> skipped, long available) {
    }

    public record CouponView(String code, String poolId, String poolCode, String poolName, CouponStatus status,
                             String memberId, String rewardCode, String origin, String redemptionId,
                             Instant issuedAt, Instant expiresAt, Instant usedAt, Instant voidedAt) {
    }

    private static final int MAX_PAGE_SIZE = 100;

    private final CouponRepository coupons;
    private final RewardRepository rewards;
    private final AuditPublisher audit;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public CouponService(CouponRepository coupons, RewardRepository rewards, AuditPublisher audit,
                         LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.coupons = coupons;
        this.rewards = rewards;
        this.audit = audit;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    // ---------- pool ----------

    @Transactional(readOnly = true)
    public List<PoolView> pools() {
        Map<String, Map<CouponStatus, Long>> counts = coupons.countsByPool();
        return coupons.pools().stream().map(p -> view(p, counts.get(p.id()))).toList();
    }

    @Transactional(readOnly = true)
    public PoolView pool(String idOrCode) {
        CouponPool p = requirePool(idOrCode);
        return view(p, coupons.countsByPool().get(p.id()));
    }

    @Transactional
    public PoolView createPool(PoolRequest r) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        String prefix = r.prefix() == null ? "" : r.prefix().trim().toUpperCase();
        if (code.isEmpty() || r.name() == null || r.name().isBlank()) {
            throw LhException.badRequest("code e name sono obbligatori");
        }
        if (!CouponCodes.validPrefix(prefix)) {
            throw LhException.validation("COUPON_PREFIX_INVALID", "Il prefisso deve avere 2–10 lettere maiuscole o cifre.");
        }
        int validity = r.validityDays() == null ? 90 : r.validityDays();
        if (validity < 1 || validity > 3650) {
            throw LhException.validation("COUPON_VALIDITY_INVALID", "La validità deve essere tra 1 e 3650 giorni.");
        }
        if (coupons.pool(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Pool già esistente: " + code);
        }
        CouponPool p = new CouponPool(Ulid.next(clock), code, r.name().trim(), prefix, validity, seedFor(code), null);
        coupons.insertPool(p);
        audit.record("COUPON_POOL", code, AuditEntry.Action.CREATE, "Creato pool coupon " + p.name() + " (" + prefix + ")",
                null, Map.of("prefix", prefix, "validityDays", validity));
        return pool(p.id());
    }

    /** Seme stabile di un pool: dipende solo dal codice, così un pool ricreato genera gli stessi codici. */
    public static long seedFor(String poolCode) {
        long h = 1125899906842597L;
        for (char c : poolCode.toCharArray()) {
            h = 31 * h + c;
        }
        return h;
    }

    /**
     * Genera {@code count} codici nuovi ({@code ≤ 5000}). Il seme della generazione deriva da quello del pool e dal
     * numero di codici già presenti: stesso stato di partenza, stessi codici. Il pool è bloccato per la durata.
     */
    @Transactional
    public GenerateResult generate(String poolId, int count, boolean audited) {
        if (count < 1 || count > CouponCodes.MAX_GENERATE) {
            throw LhException.validation("COUPON_COUNT_INVALID", "Si generano da 1 a " + CouponCodes.MAX_GENERATE + " codici per volta.");
        }
        CouponPool p = coupons.lockPool(requirePool(poolId).id()).orElseThrow();
        long seed = CouponCodes.batchSeed(p.seed(), coupons.countInPool(p.id()));
        CouponCodes generator = new CouponCodes(p.prefix(), seed);
        int generated = 0;
        // Le collisioni (con codici già esistenti) sono rarissime: si ripete finché non si arriva al numero chiesto.
        for (int round = 0; generated < count && round < 10; round++) {
            Set<String> batch = new LinkedHashSet<>();
            while (batch.size() < count - generated) {
                batch.add(generator.next());
            }
            generated += coupons.insertAvailable(p.id(), batch).size();
        }
        if (audited) {
            audit.record("COUPON_POOL", p.code(), AuditEntry.Action.UPDATE, "Generati " + generated + " codici nel pool " + p.code(),
                    null, Map.of("generated", generated, "seed", seed));
        }
        return new GenerateResult(generated, seed, coupons.countAvailable(p.id()));
    }

    /** Importa un elenco di codici: validi e nuovi → {@code AVAILABLE}; gli altri finiscono in {@code skipped}. */
    @Transactional
    public ImportResult importCodes(String poolId, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw LhException.validation("COUPON_IMPORT_EMPTY", "Nessun codice da importare.");
        }
        if (raw.size() > CouponCodes.MAX_GENERATE) {
            throw LhException.validation("COUPON_COUNT_INVALID", "Si importano al massimo " + CouponCodes.MAX_GENERATE + " codici per volta.");
        }
        CouponPool p = coupons.lockPool(requirePool(poolId).id()).orElseThrow();
        List<String> skipped = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        for (String r : raw) {
            String c = CouponCodes.normalize(r);
            if (c == null || !candidates.add(c)) {
                skipped.add(r == null ? "" : r.trim());
            }
        }
        List<String> inserted = coupons.insertAvailable(p.id(), candidates);
        Set<String> ok = new java.util.HashSet<>(inserted);
        for (String c : candidates) {
            if (!ok.contains(c)) {
                skipped.add(c); // già esistente (in questo o in un altro pool)
            }
        }
        audit.record("COUPON_POOL", p.code(), AuditEntry.Action.UPDATE,
                "Importati " + inserted.size() + " codici nel pool " + p.code() + " (" + skipped.size() + " scartati)",
                null, Map.of("imported", inserted.size(), "skipped", skipped.size()));
        return new ImportResult(inserted.size(), skipped, coupons.countAvailable(p.id()));
    }

    @Transactional(readOnly = true)
    public PageResponse<CouponView> coupons(String poolId, String status, String memberId, int page, int size) {
        CouponPool p = requirePool(poolId);
        io.loyaltyhub.common.web.PageParams paging = io.loyaltyhub.common.web.PageParams.of(page, size); // SPEC-GAP: Q-332
        int s = Math.min(paging.size(), MAX_PAGE_SIZE);
        int n = paging.page();
        List<CouponView> items = coupons.search(p.id(), status, memberId, n, s).stream().map(c -> view(c, p)).toList();
        return PageResponse.of(items, n, s, coupons.count(p.id(), status, memberId));
    }

    // ---------- codici ----------

    /** Verifica alla cassa: un {@code ISSUED} oltre la scadenza si mostra già come {@code EXPIRED}. */
    @Transactional(readOnly = true)
    public CouponView get(String code) {
        Coupon c = requireCoupon(code);
        return view(c.expiredAt(clock.instant()) ? withStatus(c, CouponStatus.EXPIRED) : c,
                coupons.pool(c.poolId()).orElse(null));
    }

    /**
     * Uso alla cassa ({@code F-CPN-03}): solo un coupon {@code ISSUED} e valido. Già usato → 409; scaduto → 410
     * (e resta {@code EXPIRED}); annullato o mai emesso → 409.
     */
    @Transactional(noRollbackFor = LhException.class)
    public CouponView use(String code) {
        Coupon c = coupons.lock(normalized(code)).orElseThrow(() -> LhException.notFound("Coupon non trovato: " + code));
        Instant now = clock.instant();
        switch (c.status()) {
            case USED -> throw LhException.conflict("COUPON_ALREADY_USED", "Coupon già usato il " + c.usedAt() + ".");
            case VOID -> throw LhException.conflict("COUPON_VOID", "Coupon annullato.");
            case AVAILABLE -> throw LhException.conflict("COUPON_NOT_ISSUED", "Coupon non ancora emesso a un membro.");
            case EXPIRED -> throw LhException.gone("COUPON_EXPIRED", "Coupon scaduto il " + c.expiresAt() + ".");
            case ISSUED -> {
                if (c.expiredAt(now)) {
                    coupons.markExpired(c.code());
                    throw LhException.gone("COUPON_EXPIRED", "Coupon scaduto il " + c.expiresAt() + ".");
                }
            }
        }
        coupons.markUsed(c.code(), now);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("couponCode", c.code());
        if (c.rewardCode() != null) {
            data.put("rewardCode", c.rewardCode());
        }
        outbox.write(events.newRoot(LhEventTypes.Fact.COUPON_USED, subjectOf(c), data,
                LhSource.service("reward"), ActorHolder.get().asActorString()));
        audit.record("COUPON", c.code(), AuditEntry.Action.UPDATE, "Coupon " + c.code() + " usato alla cassa",
                Map.of("status", c.status().name()), Map.of("status", "USED"));
        return get(c.code());
    }

    /**
     * Annullo ({@code coupon.void}), Q-278 DECISA: solo da {@code AVAILABLE} (ritiro di un codice mai emesso) e da
     * {@code ISSUED} ancora valido. Usato, già annullato o scaduto → 409: lo storico di un codice scaduto non cambia
     * (un {@code ISSUED} oltre la scadenza, job non ancora eseguito, passa a {@code EXPIRED} come all'uso).
     */
    @Transactional(noRollbackFor = LhException.class)
    public CouponView voidCoupon(String code) {
        Coupon c = coupons.lock(normalized(code)).orElseThrow(() -> LhException.notFound("Coupon non trovato: " + code));
        if (c.status() == CouponStatus.USED) {
            throw LhException.conflict("COUPON_ALREADY_USED", "Un coupon usato non si annulla.");
        }
        if (c.status() == CouponStatus.VOID) {
            throw LhException.conflict("COUPON_VOID", "Coupon già annullato.");
        }
        if (c.status() == CouponStatus.EXPIRED) {
            throw LhException.conflict("COUPON_EXPIRED", "Un coupon scaduto non si annulla (scaduto il " + c.expiresAt() + ").");
        }
        if (c.status() == CouponStatus.ISSUED && c.expiredAt(clock.instant())) {
            coupons.markExpired(c.code());
            throw LhException.conflict("COUPON_EXPIRED", "Un coupon scaduto non si annulla (scaduto il " + c.expiresAt() + ").");
        }
        coupons.markVoid(c.code(), clock.instant());
        audit.record("COUPON", c.code(), AuditEntry.Action.UPDATE, "Coupon " + c.code() + " annullato",
                Map.of("status", c.status().name()), Map.of("status", "VOID"));
        return get(c.code());
    }

    /**
     * Emette un codice del pool al membro (richiesta premio o effetto {@code coupon.issue}): preleva il primo libero
     * con {@code SKIP LOCKED}, scadenza = {@link #expiryFor} (fine giornata a Roma), fatto {@code coupon.issued} figlio di
     * {@code cause} se c'è. Vuoto se il pool è esaurito.
     */
    @Transactional
    public Optional<Coupon> issue(String poolId, String memberId, String rewardCode, String origin,
                                  String redemptionId, String effectId, LhEvent<?> cause) {
        CouponPool p = requirePool(poolId);
        Optional<String> code = coupons.takeAvailable(p.id());
        if (code.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant expiresAt = expiryFor(now, p.validityDays());
        coupons.markIssued(code.get(), memberId, rewardCode, origin, redemptionId, effectId, now, expiresAt);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("couponCode", code.get());
        data.put("rewardCode", rewardCode);
        data.put("expiresAt", expiresAt.toString());
        data.put("origin", origin);
        if (redemptionId != null) {
            data.put("redemptionId", redemptionId);
        }
        outbox.write(cause != null
                ? events.childOf(cause, LhEventTypes.Fact.COUPON_ISSUED, data)
                : events.newRoot(LhEventTypes.Fact.COUPON_ISSUED, "member:" + memberId, data));
        return coupons.find(code.get());
    }

    /** Le date di business della loyalty sono in ora italiana (come le scadenze di fine mese del wallet). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    /**
     * «Ultimo istante» di un giorno alla precisione di {@code timestamptz} (microsecondi): con {@link LocalTime#MAX}
     * il driver arrotonderebbe al primo istante del giorno dopo.
     */
    static final LocalTime LAST_INSTANT = LocalTime.MAX.truncatedTo(ChronoUnit.MICROS);

    /**
     * Scadenza di un coupon emesso in {@code issuedAt} (reward §5 «oggi + validity_days», Q-277 DECISA): fine del
     * giorno (23:59:59.999999, Europe/Rome) della data di emissione a Roma + {@code validityDays}. Non si sposta col
     * cambio dell'ora e non scade a metà giornata.
     */
    public static Instant expiryFor(Instant issuedAt, int validityDays) {
        LocalDate lastDay = issuedAt.atZone(ZONE).toLocalDate().plusDays(validityDays);
        return lastDay.atTime(LAST_INSTANT).atZone(ZONE).toInstant();
    }

    /** Job di scadenza: {@code ISSUED} con scadenza ≤ {@code asOf} → {@code EXPIRED}. */
    @Transactional
    public int expire(Instant asOf) {
        return coupons.expireIssued(asOf);
    }

    /** Coupon del membro (PT-13). */
    @Transactional(readOnly = true)
    public List<CouponView> memberCoupons(String memberId) {
        Instant now = clock.instant();
        Map<String, CouponPool> pools = new LinkedHashMap<>();
        coupons.pools().forEach(p -> pools.put(p.id(), p));
        return coupons.byMember(memberId).stream()
                .map(c -> view(c.expiredAt(now) ? withStatus(c, CouponStatus.EXPIRED) : c, pools.get(c.poolId())))
                .toList();
    }

    // ---------- helper ----------

    private CouponPool requirePool(String idOrCode) {
        return coupons.pool(idOrCode).orElseThrow(() -> LhException.notFound("Pool coupon non trovato: " + idOrCode));
    }

    private Coupon requireCoupon(String code) {
        return coupons.find(normalized(code)).orElseThrow(() -> LhException.notFound("Coupon non trovato: " + code));
    }

    private static String normalized(String code) {
        return code == null ? "" : code.trim().toUpperCase();
    }

    private static String subjectOf(Coupon c) {
        return c.memberId() != null ? "member:" + c.memberId() : "coupon:" + c.code();
    }

    private PoolView view(CouponPool p, Map<CouponStatus, Long> raw) {
        Map<CouponStatus, Long> counts = new EnumMap<>(CouponStatus.class);
        for (CouponStatus s : CouponStatus.values()) {
            counts.put(s, raw == null ? 0L : raw.getOrDefault(s, 0L));
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<RewardRef> linked = rewards.findByPool(p.id()).stream()
                .map((Reward r) -> new RewardRef(r.id(), r.code(), r.name(), r.status().name())).toList();
        return new PoolView(p.id(), p.code(), p.name(), p.prefix(), p.validityDays(), counts, total, linked);
    }

    private static CouponView view(Coupon c, CouponPool p) {
        return new CouponView(c.code(), c.poolId(), p == null ? null : p.code(), p == null ? null : p.name(), c.status(),
                c.memberId(), c.rewardCode(), c.origin(), c.redemptionId(), c.issuedAt(), c.expiresAt(), c.usedAt(),
                c.voidedAt());
    }

    private static Coupon withStatus(Coupon c, CouponStatus s) {
        return new Coupon(c.code(), c.poolId(), s, c.memberId(), c.rewardCode(), c.origin(), c.redemptionId(),
                c.effectId(), c.issuedAt(), c.expiresAt(), c.usedAt(), c.voidedAt());
    }
}
