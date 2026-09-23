package io.loyaltyhub.reward.application;

import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.domain.Band;
import io.loyaltyhub.reward.domain.Category;
import io.loyaltyhub.reward.domain.Reward;
import io.loyaltyhub.reward.domain.RewardStatus;
import io.loyaltyhub.reward.infra.CatalogRepository;
import io.loyaltyhub.reward.infra.RewardRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gestione del catalogo (docs/servizi/reward-service.md §3): categorie, fasce e premi con il ciclo di vita comune
 * degli oggetti governati (docs/03 §3.6, docs/06 §7). Ogni scrittura va in audit; i cambi di stato producono il
 * fatto {@code reward.status.changed} (forma di EVT-FACT-11, chiave = codice del premio).
 */
@Service
public class CatalogAdminService {

    public static final String STATUS_CHANGED = "io.loyaltyhub.fact.reward.status.changed";

    /** Corpo di creazione/modifica di un premio; i campi {@code null} restano invariati in modifica. */
    public record RewardRequest(String code, String name, String description, String terms, String imageUrl,
                                String type, String category, String band, String fulfilment, String couponPoolId,
                                Integer stockTotal, Integer perMemberLimit, List<String> eligibleTiers,
                                List<String> eligibleSegments, Instant validFrom, Instant validTo) {
    }

    private final CatalogRepository catalog;
    private final RewardRepository rewards;
    private final AuditPublisher audit;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final boolean approvalEnabled;

    public CatalogAdminService(CatalogRepository catalog, RewardRepository rewards, AuditPublisher audit,
                               LhEventFactory events, OutboxWriter outbox, Clock clock,
                               @Value("${loyaltyhub.approval.enabled:false}") boolean approvalEnabled) {
        this.catalog = catalog;
        this.rewards = rewards;
        this.audit = audit;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
        this.approvalEnabled = approvalEnabled;
    }

    // ---------- categorie ----------

    @Transactional
    public Category saveCategory(Category c) {
        if (blank(c.code()) || blank(c.name())) {
            throw LhException.validation("CATEGORY_INVALID", "code e name sono obbligatori.");
        }
        boolean exists = catalog.category(c.code()).isPresent();
        catalog.upsertCategory(c);
        audit.record("REWARD_CATEGORY", c.code(), exists ? AuditEntry.Action.UPDATE : AuditEntry.Action.CREATE,
                (exists ? "Modificata" : "Creata") + " categoria " + c.name(), null, Map.of("name", c.name()));
        return catalog.category(c.code()).orElseThrow();
    }

    // ---------- fasce ----------

    /** Crea o modifica una fascia: soglie uniche e crescenti con l'ordine (422 {@code BAND_THRESHOLD_DUPLICATE}). */
    @Transactional
    public Band saveBand(Band b) {
        if (blank(b.code()) || blank(b.name()) || b.pointsThreshold() <= 0) {
            throw LhException.validation("BAND_INVALID", "code, name e una soglia positiva sono obbligatori.");
        }
        List<Band> others = new ArrayList<>(catalog.bands().stream().filter(x -> !x.code().equals(b.code())).toList());
        if (others.stream().anyMatch(x -> x.pointsThreshold() == b.pointsThreshold())) {
            throw LhException.validation("BAND_THRESHOLD_DUPLICATE", "Esiste già una fascia con soglia " + b.pointsThreshold() + ".");
        }
        others.add(b);
        others.sort((x, y) -> Integer.compare(x.sortOrder(), y.sortOrder()));
        for (int i = 1; i < others.size(); i++) {
            if (others.get(i).pointsThreshold() <= others.get(i - 1).pointsThreshold()) {
                throw LhException.validation("BAND_THRESHOLD_DUPLICATE",
                        "Le soglie devono crescere con l'ordine delle fasce (" + others.get(i - 1).code() + " → " + others.get(i).code() + ").");
            }
        }
        boolean exists = catalog.band(b.code()).isPresent();
        catalog.upsertBand(b);
        audit.record("REWARD_BAND", b.code(), exists ? AuditEntry.Action.UPDATE : AuditEntry.Action.CREATE,
                (exists ? "Modificata" : "Creata") + " fascia " + b.name() + " (" + b.pointsThreshold() + " PTS)",
                null, Map.of("pointsThreshold", b.pointsThreshold()));
        return catalog.band(b.code()).orElseThrow();
    }

    @Transactional
    public void deleteBand(String code) {
        Band b = catalog.band(code).orElseThrow(() -> LhException.notFound("Fascia non trovata: " + code));
        if (catalog.rewardsInBand(code) > 0) {
            throw LhException.conflict("BAND_IN_USE", "La fascia " + code + " ha dei premi: spostali prima di eliminarla.");
        }
        catalog.deleteBand(code);
        audit.record("REWARD_BAND", code, AuditEntry.Action.DELETE, "Eliminata fascia " + b.name(),
                Map.of("pointsThreshold", b.pointsThreshold()), null);
    }

    // ---------- premi ----------

    public Reward get(String id) {
        return rewards.findById(id).or(() -> rewards.findByCode(id))
                .orElseThrow(() -> LhException.notFound("Premio non trovato: " + id));
    }

    @Transactional
    public Reward create(RewardRequest r) {
        if (blank(r.code())) {
            throw LhException.badRequest("code è obbligatorio");
        }
        if (rewards.findByCode(r.code()).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Codice premio già esistente: " + r.code());
        }
        Reward created = new Reward(Ulid.next(clock), r.code(), r.name(), r.description(), r.terms(), r.imageUrl(),
                upper(r.type()), r.category(), r.band(), upper(r.fulfilment()), r.couponPoolId(), r.stockTotal(),
                r.stockTotal(), r.perMemberLimit(), list(r.eligibleTiers()), list(r.eligibleSegments()),
                r.validFrom(), r.validTo(), RewardStatus.DRAFT, 0, ActorHolder.get().asActorString(), null);
        validate(created);
        rewards.insert(created);
        audit.record("REWARD", created.code(), AuditEntry.Action.CREATE, "Creato premio " + created.name(),
                null, Map.of("code", created.code(), "band", created.bandCode(), "status", "DRAFT"));
        return rewards.findByCode(created.code()).orElseThrow();
    }

    /**
     * Modifica: ammessa in {@code DRAFT}/{@code REJECTED}/{@code PAUSED}; in {@code LIVE} solo {@code stockTotal},
     * {@code validTo}, {@code imageUrl} (altrimenti 409 {@code REWARD_LIVE_LOCKED}). Cambiare lo stock totale sposta
     * il residuo della stessa differenza (mai sotto zero).
     */
    @Transactional
    public Reward update(String id, RewardRequest r) {
        Reward c = get(id);
        if (r.code() != null && !r.code().equals(c.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un premio non si modifica: " + c.code());
        }
        Reward m = merge(c, r);
        switch (c.status()) {
            case DRAFT, REJECTED, PAUSED -> { }
            case LIVE -> {
                List<String> locked = lockedChanges(c, m);
                if (!locked.isEmpty()) {
                    throw LhException.conflict("REWARD_LIVE_LOCKED", "Su un premio LIVE si modificano solo stock totale, "
                            + "fine validità e immagine; campi bloccati: " + String.join(", ", locked) + ".");
                }
            }
            default -> throw LhException.conflict("REWARD_NOT_EDITABLE", "Un premio " + c.status() + " non si modifica.");
        }
        validate(m);
        rewards.update(m);
        audit.record("REWARD", c.code(), AuditEntry.Action.UPDATE, "Modificato premio " + m.name(),
                Map.of("stockTotal", String.valueOf(c.stockTotal()), "band", c.bandCode()),
                Map.of("stockTotal", String.valueOf(m.stockTotal()), "band", m.bandCode()));
        return rewards.findById(c.id()).orElseThrow();
    }

    @Transactional
    public Reward duplicate(String id) {
        Reward c = get(id);
        String code = c.code() + "-COPY";
        int n = 2;
        while (rewards.findByCode(code).isPresent()) {
            code = c.code() + "-COPY" + n++;
        }
        Reward copy = new Reward(Ulid.next(clock), code, c.name() + " (copia)", c.description(), c.terms(), c.imageUrl(),
                c.type(), c.categoryCode(), c.bandCode(), c.fulfilment(), c.couponPoolId(), c.stockTotal(), c.stockTotal(),
                c.perMemberLimit(), c.eligibleTiers(), c.eligibleSegments(), c.validFrom(), c.validTo(),
                RewardStatus.DRAFT, 0, ActorHolder.get().asActorString(), null);
        rewards.insert(copy);
        audit.record("REWARD", code, AuditEntry.Action.CREATE, "Duplicato premio " + c.code() + " in " + code,
                null, Map.of("from", c.code()));
        return rewards.findByCode(code).orElseThrow();
    }

    @Transactional
    public Reward transition(String id, String action, String comment) {
        Reward r = get(id);
        String a = action == null ? "" : action.trim().toUpperCase();
        if ((a.equals("APPROVE") || a.equals("REJECT"))
                && ActorHolder.get().role() != Role.ADMIN && ActorHolder.get().role() != Role.LEGAL) {
            throw LhException.forbiddenRole("Approvare o respingere richiede il ruolo LEGAL o ADMIN.");
        }
        RewardStatus from = r.status();
        RewardStatus to = switch (a) {
            case "SUBMIT" -> approvalEnabled ? require(from, RewardStatus.IN_REVIEW, RewardStatus.DRAFT, RewardStatus.REJECTED)
                    : require(from, RewardStatus.LIVE, RewardStatus.DRAFT, RewardStatus.REJECTED);
            case "APPROVE" -> require(from, RewardStatus.LIVE, RewardStatus.IN_REVIEW);
            case "REJECT" -> require(from, RewardStatus.REJECTED, RewardStatus.IN_REVIEW);
            case "PUBLISH" -> require(from, RewardStatus.LIVE, RewardStatus.DRAFT, RewardStatus.IN_REVIEW, RewardStatus.PAUSED);
            case "PAUSE" -> require(from, RewardStatus.PAUSED, RewardStatus.LIVE);
            case "RESUME" -> require(from, RewardStatus.LIVE, RewardStatus.PAUSED);
            case "END" -> require(from, RewardStatus.ENDED, RewardStatus.LIVE, RewardStatus.PAUSED);
            case "ARCHIVE" -> require(from, RewardStatus.ARCHIVED, RewardStatus.DRAFT, RewardStatus.REJECTED,
                    RewardStatus.ENDED, RewardStatus.PAUSED, RewardStatus.IN_REVIEW);
            default -> throw LhException.badRequest("Transizione sconosciuta: " + action);
        };
        rewards.updateStatus(r.id(), to);
        outbox.write(events.newRoot(STATUS_CHANGED, "reward:" + r.code(), Map.of(
                "rewardCode", r.code(), "name", r.name(), "previousStatus", from.name(), "newStatus", to.name())));
        audit.record("REWARD", r.code(), AuditEntry.Action.TRANSITION,
                r.name() + ": " + from + " → " + to + " (" + a + ")" + (blank(comment) ? "" : " — " + comment),
                Map.of("status", from.name()), Map.of("status", to.name()));
        return rewards.findById(r.id()).orElseThrow();
    }

    // ---------- interni ----------

    private void validate(Reward r) {
        List<String> errors = new ArrayList<>();
        if (blank(r.name())) errors.add("name obbligatorio");
        if (!Reward.TYPES.contains(r.type())) errors.add("type deve essere uno tra " + Reward.TYPES);
        if (!Reward.FULFILMENTS.contains(r.fulfilment())) errors.add("fulfilment deve essere uno tra " + Reward.FULFILMENTS);
        if (r.bandCode() == null || catalog.band(r.bandCode()).isEmpty()) errors.add("fascia inesistente: " + r.bandCode());
        if (r.categoryCode() != null && catalog.category(r.categoryCode()).isEmpty()) errors.add("categoria inesistente: " + r.categoryCode());
        if ("AUTO_COUPON".equals(r.fulfilment()) && !"COUPON".equals(r.type())) errors.add("AUTO_COUPON solo per premi COUPON");
        if (r.stockTotal() != null && r.stockTotal() < 0) errors.add("stockTotal non può essere negativo");
        if (r.perMemberLimit() != null && r.perMemberLimit() < 1) errors.add("perMemberLimit deve essere ≥ 1");
        if (r.validFrom() != null && r.validTo() != null && !r.validTo().isAfter(r.validFrom())) errors.add("validTo deve seguire validFrom");
        if (!errors.isEmpty()) {
            throw LhException.validation("REWARD_INVALID", String.join("; ", errors));
        }
    }

    private Reward merge(Reward c, RewardRequest r) {
        Integer total = r.stockTotal() != null ? r.stockTotal() : c.stockTotal();
        Integer remaining = c.stockRemaining();
        if (r.stockTotal() != null && c.stockTotal() != null && remaining != null) {
            remaining = Math.max(0, remaining + (r.stockTotal() - c.stockTotal()));
        } else if (r.stockTotal() != null && c.stockTotal() == null) {
            remaining = r.stockTotal();
        }
        return new Reward(c.id(), c.code(), pick(r.name(), c.name()), pick(r.description(), c.description()),
                pick(r.terms(), c.terms()), pick(r.imageUrl(), c.imageUrl()), r.type() != null ? upper(r.type()) : c.type(),
                pick(r.category(), c.categoryCode()), pick(r.band(), c.bandCode()),
                r.fulfilment() != null ? upper(r.fulfilment()) : c.fulfilment(), pick(r.couponPoolId(), c.couponPoolId()),
                total, remaining, r.perMemberLimit() != null ? r.perMemberLimit() : c.perMemberLimit(),
                r.eligibleTiers() != null ? r.eligibleTiers() : c.eligibleTiers(),
                r.eligibleSegments() != null ? r.eligibleSegments() : c.eligibleSegments(),
                r.validFrom() != null ? r.validFrom() : c.validFrom(), r.validTo() != null ? r.validTo() : c.validTo(),
                c.status(), c.version(), c.createdBy(), c.updatedAt());
    }

    private static List<String> lockedChanges(Reward b, Reward a) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(b.name(), a.name())) changed.add("name");
        if (!Objects.equals(b.description(), a.description())) changed.add("description");
        if (!Objects.equals(b.terms(), a.terms())) changed.add("terms");
        if (!Objects.equals(b.type(), a.type())) changed.add("type");
        if (!Objects.equals(b.categoryCode(), a.categoryCode())) changed.add("category");
        if (!Objects.equals(b.bandCode(), a.bandCode())) changed.add("band");
        if (!Objects.equals(b.fulfilment(), a.fulfilment())) changed.add("fulfilment");
        if (!Objects.equals(b.couponPoolId(), a.couponPoolId())) changed.add("couponPoolId");
        if (!Objects.equals(b.perMemberLimit(), a.perMemberLimit())) changed.add("perMemberLimit");
        if (!b.eligibleTiers().equals(a.eligibleTiers())) changed.add("eligibleTiers");
        if (!b.eligibleSegments().equals(a.eligibleSegments())) changed.add("eligibleSegments");
        if (!Objects.equals(b.validFrom(), a.validFrom())) changed.add("validFrom");
        return changed;
    }

    private static RewardStatus require(RewardStatus from, RewardStatus to, RewardStatus... allowed) {
        for (RewardStatus s : allowed) {
            if (s == from) {
                return to;
            }
        }
        throw LhException.conflict("INVALID_TRANSITION", "Transizione non valida da " + from + " a " + to);
    }

    private static String pick(String v, String fallback) {
        return v != null ? v : fallback;
    }

    private static String upper(String v) {
        return v == null ? null : v.trim().toUpperCase();
    }

    private static List<String> list(List<String> v) {
        return v == null ? List.of() : v;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
