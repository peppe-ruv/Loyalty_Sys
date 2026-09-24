package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalStateMachine;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.audit.AuditEntry;
import io.loyaltyhub.common.audit.AuditPublisher;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.domain.InstantGenerator;
import io.loyaltyhub.gamification.domain.Prize;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.InstantRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestione dei concorsi instant win (docs/servizi/gamification-service.md §3, §5; F-IW-01..03, F-IW-07, BO-14):
 * concorso e montepremi, ciclo di vita comune, generazione degli istanti con seme, consegna dei premi fisici.
 * Istanti e premi sono immutabili dal {@code LIVE} in poi; ogni modifica che li riguarda prima di allora invalida gli
 * istanti già generati (vanno rigenerati prima di pubblicare).
 */
@Service
public class ContestAdminService {

    public static final String STATUS_CHANGED = "io.loyaltyhub.fact.contest.status.changed";
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9-]{2,39}$");

    public record PrizeRequest(String code, String name, String type, Long points, String rewardCode, Integer quantity,
                               String imageUrl, String wheelColor, Integer sortOrder) {
    }

    public record ContestRequest(String code, String name, String description, String rulesText, String mechanic,
                                 Instant startAt, Instant endAt, Boolean freePlayDaily, Integer maxPlaysPerMemberPerDay,
                                 Integer maxWinsPerMember, String distribution, Long seed, List<PrizeRequest> prizes) {
    }

    public record GenerateResult(int instants, long seed, Instant generatedAt) {
    }

    public record CloseResult(int contests, int voided) {
    }

    public record PlantResult(String instantId, String prizeCode, String prizeName, Instant instantAt) {
    }

    private final ContestRepository contests;
    private final InstantRepository instants;
    private final PlayRepository plays;
    private final AuditPublisher audit;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ContestAdminService(ContestRepository contests, InstantRepository instants, PlayRepository plays,
                               AuditPublisher audit, LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.contests = contests;
        this.instants = instants;
        this.plays = plays;
        this.audit = audit;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    public Contest get(String idOrCode) {
        return contests.find(idOrCode).orElseThrow(() -> LhException.notFound("Concorso non trovato: " + idOrCode));
    }

    @Transactional
    public Contest create(ContestRequest r) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw LhException.validation("CONTEST_INVALID", "Codice concorso non valido (es. IW-PRIMAVERA).");
        }
        if (contests.find(code).isPresent()) {
            throw LhException.conflict("CODE_TAKEN", "Concorso già esistente: " + code);
        }
        Contest c = new Contest(Ulid.next(clock), code, r.name(), r.description(), r.rulesText(), upper(r.mechanic()),
                r.startAt(), r.endAt(), Boolean.TRUE.equals(r.freePlayDaily()), r.maxPlaysPerMemberPerDay(),
                r.maxWinsPerMember(), r.distribution() == null ? "UNIFORM" : upper(r.distribution()),
                r.seed() != null ? r.seed() : seedFor(code), null, ApprovalStatus.DRAFT, 0,
                ActorHolder.get().asActorString(), null);
        List<Prize> prizes = prizes(c.id(), r.prizes());
        validate(c, prizes);
        contests.insert(c);
        prizes.forEach(contests::insertPrize);
        audit.record("CONTEST", code, AuditEntry.Action.CREATE, "Creato concorso " + c.name(), null,
                Map.of("code", code, "prizes", prizes.size(), "units", units(prizes)));
        return get(c.id());
    }

    /**
     * Modifica. Prima del {@code LIVE} tutto è modificabile (premi compresi); cambiare premi, periodo, distribuzione o
     * seme cancella gli istanti generati. Da {@code LIVE}/{@code PAUSED} solo nome, descrizione e regolamento
     * ({@code 409 CONTEST_LIVE_LOCKED}); {@code ENDED}/{@code ARCHIVED} non si modificano.
     */
    @Transactional
    public Contest update(String id, ContestRequest r) {
        Contest c = contests.lock(get(id).id()).orElseThrow();
        if (r.code() != null && !r.code().trim().equalsIgnoreCase(c.code())) {
            throw LhException.conflict("CODE_IMMUTABLE", "Il codice di un concorso non si modifica: " + c.code());
        }
        if (c.status() == ApprovalStatus.ENDED || c.status() == ApprovalStatus.ARCHIVED) {
            throw LhException.conflict("CONTEST_NOT_EDITABLE", "Un concorso " + c.status() + " non si modifica.");
        }
        Contest m = new Contest(c.id(), c.code(), or(r.name(), c.name()), or(r.description(), c.description()),
                or(r.rulesText(), c.rulesText()), r.mechanic() == null ? c.mechanic() : upper(r.mechanic()),
                or(r.startAt(), c.startAt()), or(r.endAt(), c.endAt()), or(r.freePlayDaily(), c.freePlayDaily()),
                or(r.maxPlaysPerMemberPerDay(), c.maxPlaysPerMemberPerDay()), or(r.maxWinsPerMember(), c.maxWinsPerMember()),
                r.distribution() == null ? c.distribution() : upper(r.distribution()), or(r.seed(), c.seed()),
                c.instantsGeneratedAt(), c.status(), c.version(), c.createdBy(), c.updatedAt());
        boolean instantsAffected = r.prizes() != null || !Objects.equals(m.startAt(), c.startAt())
                || !Objects.equals(m.endAt(), c.endAt()) || !Objects.equals(m.distribution(), c.distribution())
                || m.seed() != c.seed();
        boolean rulesChanged = !Objects.equals(m.mechanic(), c.mechanic()) || m.freePlayDaily() != c.freePlayDaily()
                || !Objects.equals(m.maxPlaysPerMemberPerDay(), c.maxPlaysPerMemberPerDay())
                || !Objects.equals(m.maxWinsPerMember(), c.maxWinsPerMember());
        if (c.locked() && (instantsAffected || rulesChanged)) {
            throw LhException.conflict("CONTEST_LIVE_LOCKED",
                    "Un concorso LIVE non cambia premi, periodo, istanti o regole di gioco: solo nome, descrizione e regolamento.");
        }
        List<Prize> prizes = r.prizes() != null ? prizes(c.id(), r.prizes()) : contests.prizes(c.id());
        validate(m, prizes);
        if (instantsAffected) {
            instants.deleteByContest(c.id());
            m = new Contest(m.id(), m.code(), m.name(), m.description(), m.rulesText(), m.mechanic(), m.startAt(), m.endAt(),
                    m.freePlayDaily(), m.maxPlaysPerMemberPerDay(), m.maxWinsPerMember(), m.distribution(), m.seed(), null,
                    m.status(), m.version(), m.createdBy(), m.updatedAt());
        }
        contests.update(m);
        if (r.prizes() != null) {
            contests.deletePrizes(c.id());
            prizes.forEach(contests::insertPrize);
        }
        audit.record("CONTEST", c.code(), AuditEntry.Action.UPDATE,
                "Modificato concorso " + m.name() + (instantsAffected ? " (istanti da rigenerare)" : ""),
                Map.of("units", units(contests.prizes(c.id()))), Map.of("units", units(prizes)));
        return get(c.id());
    }

    /**
     * Transizione del ciclo di vita comune (docs/03 §3.6). Verso {@code LIVE} servono gli istanti generati
     * ({@code 422 INSTANTS_NOT_GENERATED}); {@code END} rende {@code VOID} gli istanti ancora aperti.
     */
    @Transactional
    public Contest transition(String id, String action, String comment) {
        Contest c = contests.lock(get(id).id()).orElseThrow();
        ApprovalAction a;
        try {
            a = ApprovalAction.valueOf(action == null ? "" : action.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw LhException.validation("INVALID_ACTION", "Azione sconosciuta: " + action);
        }
        Role role = ActorHolder.get().role();
        if ((a == ApprovalAction.APPROVE || a == ApprovalAction.REJECT) && role != Role.ADMIN && role != Role.LEGAL) {
            throw LhException.forbiddenRole("Approvare o respingere un concorso richiede il ruolo LEGAL o ADMIN.");
        }
        ApprovalStatus to = ApprovalStateMachine.next(c.status(), a, false, comment != null && !comment.isBlank());
        if (to == ApprovalStatus.LIVE && c.instantsGeneratedAt() == null) {
            throw LhException.validation("INSTANTS_NOT_GENERATED", "Genera gli istanti vincenti prima di pubblicare il concorso.");
        }
        int voided = changeStatus(c, to, ActorHolder.get().asActorString());
        audit.record("CONTEST", c.code(), AuditEntry.Action.TRANSITION,
                c.name() + ": " + c.status() + " → " + to + " (" + a + ")" + (voided > 0 ? ", " + voided + " istanti annullati" : "")
                        + (comment == null || comment.isBlank() ? "" : " — " + comment),
                Map.of("status", c.status().name()), Map.of("status", to.name()));
        return get(c.id());
    }

    /**
     * Fine concorso (docs/servizi/gamification-service.md §5; job ogni 5 minuti e BO-30): ogni concorso {@code LIVE}
     * con {@code end_at <= asOf} passa a {@code ENDED} e i suoi istanti {@code OPEN} diventano {@code VOID}, con lo
     * stesso fatto {@code contest.status.changed} della transizione manuale. Da schedulato l'attore è {@code system}.
     */
    @Transactional
    public CloseResult closeEnded(Instant asOf) {
        boolean scheduled = ActorHolder.get() == ActorContext.ANONYMOUS;
        String actor = scheduled ? "system" : ActorHolder.get().asActorString();
        int closed = 0;
        int voidedTotal = 0;
        for (String id : contests.liveEndedBy(asOf)) {
            Contest c = contests.lock(id).orElse(null);
            if (c == null || c.status() != ApprovalStatus.LIVE || c.endAt().isAfter(asOf)) {
                continue; // cambiato nel frattempo
            }
            int voided = changeStatus(c, ApprovalStatus.ENDED, actor);
            String summary = c.name() + ": LIVE → ENDED (fine concorso, riferimento " + asOf + ")"
                    + (voided > 0 ? ", " + voided + " istanti annullati" : "");
            Map<String, Object> before = Map.of("status", ApprovalStatus.LIVE.name());
            Map<String, Object> after = Map.of("status", ApprovalStatus.ENDED.name(), "voided", voided);
            if (scheduled) {
                audit.recordJob("CONTEST", c.code(), summary, before, after);
            } else {
                audit.record("CONTEST", c.code(), AuditEntry.Action.JOB, summary, before, after);
            }
            closed++;
            voidedTotal += voided;
        }
        return new CloseResult(closed, voidedTotal);
    }

    /**
     * Aiuto demo (F-IW-08, BO-14): l'ultimo istante {@code OPEN} del premio viene anticipato a {@code now − 1 s} e marcato
     * {@code planted}, così la prossima giocata vince quel premio. Il montepremi resta invariato (nessun istante nuovo).
     * Solo concorsi {@code LIVE} ({@code 409 CONTEST_NOT_LIVE}); premio del concorso ({@code 422 PRIZE_NOT_FOUND}) con
     * almeno un istante aperto ({@code 422 NO_OPEN_INSTANT}).
     */
    @Transactional
    public PlantResult plantInstant(String id, String prizeCode) {
        Contest c = contests.lock(get(id).id()).orElseThrow();
        if (c.status() != ApprovalStatus.LIVE) {
            throw LhException.conflict("CONTEST_NOT_LIVE",
                    "Si pianta un istante solo in un concorso LIVE (" + c.code() + " è " + c.status() + ").");
        }
        String code = upper(prizeCode);
        Prize prize = contests.prizes(c.id()).stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> LhException.validation("PRIZE_NOT_FOUND",
                        "Premio non presente nel concorso " + c.code() + ": " + prizeCode));
        Instant at = clock.instant().minusSeconds(1);
        InstantRepository.PlantedInstant planted = instants.plantLast(c.id(), prize.id(), at)
                .orElseThrow(() -> LhException.validation("NO_OPEN_INSTANT",
                        "Nessun istante aperto rimasto per il premio " + prize.code() + "."));
        audit.record("CONTEST", c.code(), AuditEntry.Action.UPDATE,
                "Istante piantato per " + prize.name() + " (" + prize.code() + "): la prossima giocata vince",
                null, Map.of("instantId", planted.id(), "prizeCode", prize.code(),
                        "instantAt", planted.instantAt().toString(), "planted", true));
        return new PlantResult(planted.id(), prize.code(), prize.name(), planted.instantAt());
    }

    /**
     * (Ri)genera tutti gli istanti da zero col seme indicato o con quello del concorso (stesso seme → stessi istanti).
     * Vietato dal {@code LIVE} in poi ({@code 409 INSTANTS_LOCKED}).
     */
    @Transactional
    public GenerateResult generateInstants(String id, Long seed, boolean audited) {
        Contest c = contests.lock(get(id).id()).orElseThrow();
        if (c.locked()) {
            throw LhException.conflict("INSTANTS_LOCKED", "Gli istanti di un concorso " + c.status() + " non si rigenerano.");
        }
        long s = seed != null ? seed : c.seed();
        List<Prize> prizes = contests.prizes(c.id());
        if (prizes.isEmpty()) {
            throw LhException.validation("CONTEST_INVALID", "Il concorso non ha premi: niente da generare.");
        }
        var generated = InstantGenerator.generate(
                prizes.stream().map(p -> new InstantGenerator.PrizeQuantity(p.id(), p.quantityTotal(), p.sortOrder())).toList(),
                c.startAt(), c.endAt(), c.distribution(), s);
        instants.deleteByContest(c.id());
        List<String> ids = new ArrayList<>(generated.size());
        for (int i = 0; i < generated.size(); i++) {
            ids.add(Ulid.next(clock));
        }
        instants.insertAll(c.id(), ids, generated.stream().map(InstantGenerator.GeneratedInstant::prizeId).toList(),
                generated.stream().map(InstantGenerator.GeneratedInstant::at).toList());
        contests.resetRemaining(c.id());
        Instant now = clock.instant();
        contests.markGenerated(c.id(), s, now);
        if (audited) {
            audit.record("CONTEST", c.code(), AuditEntry.Action.JOB,
                    "Generati " + generated.size() + " istanti vincenti per " + c.name() + " (seme " + s + ")",
                    null, Map.of("instants", generated.size(), "seed", s, "distribution", c.distribution()));
        }
        return new GenerateResult(generated.size(), s, now);
    }

    /** Consegna di un premio fisico vinto (BO-14 vincitori; {@code delivery.handle}). */
    @Transactional
    public void updateDelivery(String playId, String status, String note) {
        var play = plays.lock(playId).orElseThrow(() -> LhException.notFound("Giocata non trovata: " + playId));
        if (!"WIN".equals(play.outcome()) || !"PHYSICAL".equals(play.prizeType())) {
            throw LhException.conflict("DELIVERY_NOT_APPLICABLE", "La consegna manuale vale solo per le vincite di premi fisici.");
        }
        String s = upper(status);
        if (!"PENDING".equals(s) && !"DELIVERED".equals(s)) {
            throw LhException.validation("DELIVERY_STATUS_INVALID", "Stato di consegna: PENDING o DELIVERED.");
        }
        plays.updateDelivery(playId, s, note == null || note.isBlank() ? null : note.trim());
        audit.record("PLAY", playId, AuditEntry.Action.UPDATE, "Consegna premio fisico: " + s + (note == null ? "" : " — " + note),
                Map.of("deliveryStatus", play.deliveryStatus()), Map.of("deliveryStatus", s));
    }

    /** Seme stabile dal codice, quando chi crea il concorso non lo indica. */
    public static long seedFor(String code) {
        long h = 1469598103934665603L;
        for (char ch : code.toCharArray()) {
            h = (h ^ ch) * 1099511628211L;
        }
        return Math.abs(h % 100_000_000L);
    }

    // ---------- interni ----------

    /** Cambio di stato comune a transizione manuale e job: stato, istanti aperti → {@code VOID} se {@code ENDED}, fatto. */
    private int changeStatus(Contest c, ApprovalStatus to, String actor) {
        contests.updateStatus(c.id(), to);
        int voided = to == ApprovalStatus.ENDED ? instants.voidOpen(c.id()) : 0;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contestCode", c.code());
        data.put("name", c.name());
        data.put("previousStatus", c.status().name());
        data.put("newStatus", to.name());
        outbox.write(events.newRoot(STATUS_CHANGED, "contest:" + c.code(), data, LhSource.service("gamification"), actor));
        return voided;
    }

    private List<Prize> prizes(String contestId, List<PrizeRequest> requests) {
        List<Prize> out = new ArrayList<>();
        if (requests == null) {
            return out;
        }
        int i = 0;
        for (PrizeRequest p : requests) {
            int qty = p.quantity() == null ? 0 : p.quantity();
            out.add(new Prize(Ulid.next(clock), contestId, p.code() == null ? null : p.code().trim().toUpperCase(), p.name(),
                    upper(p.type()), p.points(), p.rewardCode(), qty, qty, p.imageUrl(), p.wheelColor(),
                    p.sortOrder() != null ? p.sortOrder() : ++i));
        }
        return out;
    }

    private static void validate(Contest c, List<Prize> prizes) {
        List<String> problems = new ArrayList<>();
        if (c.name() == null || c.name().isBlank()) problems.add("nome obbligatorio");
        if (!Contest.MECHANICS.contains(c.mechanic())) problems.add("meccanica tra " + Contest.MECHANICS);
        if (!Contest.DISTRIBUTIONS.contains(c.distribution())) problems.add("distribuzione tra " + Contest.DISTRIBUTIONS);
        if (c.startAt() == null || c.endAt() == null || !c.endAt().isAfter(c.startAt())) problems.add("periodo non valido");
        if (c.maxPlaysPerMemberPerDay() != null && c.maxPlaysPerMemberPerDay() < 1) problems.add("limite giornaliero ≥ 1");
        Set<String> codes = new HashSet<>();
        for (Prize p : prizes) {
            if (p.code() == null || !codes.add(p.code())) problems.add("codici premio mancanti o ripetuti");
            if (p.name() == null || p.name().isBlank()) problems.add("nome premio obbligatorio");
            if (!Prize.TYPES.contains(p.type())) problems.add("tipo premio tra " + Prize.TYPES);
            if ("POINTS".equals(p.type()) && (p.points() == null || p.points() <= 0)) problems.add(p.code() + ": punti > 0");
            if ("COUPON".equals(p.type()) && (p.rewardCode() == null || p.rewardCode().isBlank())) problems.add(p.code() + ": premio coupon senza rewardCode");
            if (p.quantityTotal() < 1) problems.add(p.code() + ": quantità ≥ 1");
        }
        if (!problems.isEmpty()) {
            throw LhException.validation("CONTEST_INVALID", "Concorso non valido: " + String.join("; ", new java.util.LinkedHashSet<>(problems)) + ".");
        }
    }

    private static int units(List<Prize> prizes) {
        return prizes.stream().mapToInt(Prize::quantityTotal).sum();
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
