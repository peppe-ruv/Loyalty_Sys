package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.approval.ApprovalHistory;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.gamification.application.ContestAdminService;
import io.loyaltyhub.gamification.application.ContestAdminService.ContestRequest;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.domain.Prize;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.InstantRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gestione dei concorsi instant win (docs/servizi/gamification-service.md §3; BO-14). Scritture e transizioni per
 * {@code object.edit} (ADMIN, MARKETING; APPROVE/REJECT a LEGAL/ADMIN, verificato nel servizio). La tabella degli istanti
 * è l'unica lettura riservata: solo ADMIN e LEGAL ("chi configura il concorso non deve conoscere gli istanti").
 */
@RestController
@RequestMapping("/v1")
public class ContestController {

    public record TransitionRequest(String action, String comment) {
    }

    public record GenerateRequest(Long seed) {
    }

    public record DeliveryRequest(String status, String note) {
    }

    public record InstantCounts(long total, long open, long claimed, long voided) {
    }

    /** Concorso con montepremi e contatori (elenco e dettaglio BO-14). */
    public record ContestView(String id, String code, String name, String description, String rulesText, String mechanic,
                              Instant startAt, Instant endAt, boolean freePlayDaily, Integer maxPlaysPerMemberPerDay,
                              Integer maxWinsPerMember, String distribution, long seed, Instant instantsGeneratedAt,
                              ApprovalStatus status, long version, String createdBy, Instant updatedAt, List<Prize> prizes,
                              InstantCounts instants, long plays, long wins, int prizesTotal, int prizesRemaining) {
    }

    public record PrizeStat(String code, String name, String type, int total, int remaining, long won) {
    }

    public record ContestStats(String code, long plays, long wins, double winRate, long players, long winners,
                               int prizesTotal, int prizesRemaining, List<PrizeStat> prizes, List<PlayRepository.DayStat> daily) {
    }

    public record Histogram(String code, String distribution, List<InstantRepository.DayCount> days) {
    }

    private final ContestAdminService admin;
    private final ContestRepository contests;
    private final InstantRepository instants;
    private final PlayRepository plays;

    public ContestController(ContestAdminService admin, ContestRepository contests, InstantRepository instants,
                             PlayRepository plays) {
        this.admin = admin;
        this.contests = contests;
        this.instants = instants;
        this.plays = plays;
    }

    @GetMapping("/contests")
    @Transactional(readOnly = true)
    public List<ContestView> list(@RequestParam(required = false) String status) {
        Map<String, InstantRepository.Counts> counts = instants.countsByContest();
        Map<String, PlayRepository.Totals> totals = plays.totalsByContest();
        List<ContestView> out = new ArrayList<>();
        for (Contest c : contests.findAll()) {
            if (status != null && !status.isBlank() && !List.of(status.toUpperCase().split(",")).contains(c.status().name())) {
                continue;
            }
            out.add(view(c, contests.prizes(c.id()), counts.getOrDefault(c.id(), InstantRepository.Counts.NONE),
                    totals.getOrDefault(c.id(), PlayRepository.Totals.NONE)));
        }
        return out;
    }

    @GetMapping("/contests/{id}")
    @Transactional(readOnly = true)
    public ContestView get(@PathVariable String id) {
        return view(admin.get(id));
    }

    @PostMapping("/contests")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<ContestView> create(@RequestBody ContestRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(admin.create(r)));
    }

    @PutMapping("/contests/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ContestView update(@PathVariable String id, @RequestBody ContestRequest r) {
        return view(admin.update(id, r));
    }

    @PostMapping("/contests/{id}/transitions")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL})
    public ContestView transition(@PathVariable String id, @RequestBody TransitionRequest t) {
        return view(admin.transition(id, t.action(), t.comment()));
    }

    /** Storico delle transizioni (docs/03 §3.6: chi, quando, commento), dal più recente. */
    @GetMapping("/contests/{id}/approval-history")
    public List<ApprovalHistory> approvalHistory(@PathVariable String id) {
        return admin.history(id);
    }

    @PostMapping("/contests/{id}/instants/generate")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ContestAdminService.GenerateResult generate(@PathVariable String id, @RequestBody(required = false) GenerateRequest r) {
        return admin.generateInstants(id, r == null ? null : r.seed(), true);
    }

    @GetMapping("/contests/{id}/instants")
    @RequiresRole({Role.ADMIN, Role.LEGAL})
    @Transactional(readOnly = true)
    public PageResponse<InstantRepository.InstantRow> instants(@PathVariable String id,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(required = false) String prizeId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "50") int size) {
        Contest c = admin.get(id);
        int p = Math.max(0, page);
        int s = Math.clamp(size, 1, 100);
        return PageResponse.of(instants.search(c.id(), status, prizeId, p, s), p, s, instants.count(c.id(), status, prizeId));
    }

    @GetMapping("/contests/{id}/instants/histogram")
    @Transactional(readOnly = true)
    public Histogram histogram(@PathVariable String id) {
        Contest c = admin.get(id);
        return new Histogram(c.code(), c.distribution(), instants.histogram(c.id()));
    }

    @GetMapping("/contests/{id}/winners")
    @Transactional(readOnly = true)
    public List<PlayRepository.Winner> winners(@PathVariable String id) {
        return plays.winners(admin.get(id).id());
    }

    @GetMapping(value = "/contests/{id}/winners.csv", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<String> winnersCsv(@PathVariable String id) {
        Contest c = admin.get(id);
        StringBuilder csv = new StringBuilder("playId,memberId,nickname,prizeCode,prizeName,prizeType,playedAt,deliveryStatus,deliveryNote\n");
        for (PlayRepository.Winner w : plays.winners(c.id())) {
            csv.append(String.join(",", cell(w.playId()), cell(w.memberId()), cell(w.nickname()), cell(w.prizeCode()),
                    cell(w.prizeName()), cell(w.prizeType()), cell(w.playedAt() == null ? null : w.playedAt().toString()),
                    cell(w.deliveryStatus()), cell(w.deliveryNote()))).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + c.code().toLowerCase() + "-vincitori.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv.toString());
    }

    @GetMapping("/contests/{id}/stats")
    @Transactional(readOnly = true)
    public ContestStats stats(@PathVariable String id) {
        Contest c = admin.get(id);
        PlayRepository.Totals t = plays.totals(c.id());
        List<Prize> prizes = contests.prizes(c.id());
        Map<String, Long> wonByPrize = new TreeMap<>();
        for (PlayRepository.Winner w : plays.winners(c.id())) {
            wonByPrize.merge(w.prizeCode(), 1L, Long::sum);
        }
        List<PrizeStat> ps = prizes.stream()
                .map(p -> new PrizeStat(p.code(), p.name(), p.type(), p.quantityTotal(), p.quantityRemaining(),
                        wonByPrize.getOrDefault(p.code(), 0L)))
                .toList();
        double rate = t.plays() == 0 ? 0 : Math.round(t.wins() * 10000.0 / t.plays()) / 100.0;
        return new ContestStats(c.code(), t.plays(), t.wins(), rate, t.players(), t.winners(),
                prizes.stream().mapToInt(Prize::quantityTotal).sum(), prizes.stream().mapToInt(Prize::quantityRemaining).sum(),
                ps, fillDays(plays.daily(c.id())));
    }

    @PostMapping("/plays/{playId}/delivery")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public ResponseEntity<Void> delivery(@PathVariable String playId, @RequestBody DeliveryRequest r) {
        admin.updateDelivery(playId, r.status(), r.note());
        return ResponseEntity.noContent().build();
    }

    // ---------- interni ----------

    private ContestView view(Contest c) {
        return view(c, contests.prizes(c.id()), instants.countsByContest().getOrDefault(c.id(), InstantRepository.Counts.NONE),
                plays.totals(c.id()));
    }

    private static ContestView view(Contest c, List<Prize> prizes, InstantRepository.Counts ic, PlayRepository.Totals t) {
        return new ContestView(c.id(), c.code(), c.name(), c.description(), c.rulesText(), c.mechanic(), c.startAt(), c.endAt(),
                c.freePlayDaily(), c.maxPlaysPerMemberPerDay(), c.maxWinsPerMember(), c.distribution(), c.seed(),
                c.instantsGeneratedAt(), c.status(), c.version(), c.createdBy(), c.updatedAt(), prizes,
                new InstantCounts(ic.total(), ic.open(), ic.claimed(), ic.voided()), t.plays(), t.wins(),
                prizes.stream().mapToInt(Prize::quantityTotal).sum(), prizes.stream().mapToInt(Prize::quantityRemaining).sum());
    }

    /** Serie giornaliera continua (giorni senza giocate a zero), per un grafico senza buchi. */
    private static List<PlayRepository.DayStat> fillDays(List<PlayRepository.DayStat> days) {
        if (days.isEmpty()) {
            return days;
        }
        Map<LocalDate, PlayRepository.DayStat> byDay = new TreeMap<>();
        days.forEach(d -> byDay.put(d.day(), d));
        List<PlayRepository.DayStat> out = new ArrayList<>();
        for (LocalDate d = days.getFirst().day(); !d.isAfter(days.getLast().day()); d = d.plusDays(1)) {
            out.add(byDay.getOrDefault(d, new PlayRepository.DayStat(d, 0, 0)));
        }
        return out;
    }

    private static String cell(String v) {
        if (v == null) {
            return "";
        }
        String safe = v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@") ? "'" + v : v;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }
}
