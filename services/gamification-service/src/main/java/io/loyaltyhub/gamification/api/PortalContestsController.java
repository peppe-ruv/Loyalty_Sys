package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.gamification.application.PlayService;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Portale: concorsi in corso, giocata sincrona, storico (docs/servizi/gamification-service.md §3 Portale; PT-05, PT-06).
 * Mai quantità residue né istanti: solo i premi in palio.
 * <p>Il membro viene solo dal token (Q-410, ADR-048, docs/06 §3.4): nessun {@code memberId} in query o percorso; lo
 * risolve {@code EndpointAccessInterceptor} e lo consegna come {@link MemberPrincipal}. Il campo {@code memberId} del
 * corpo della giocata resta solo per il profilo {@code demo} (deprecato; in {@code enterprise} dà
 * {@code 400 MEMBER_FROM_TOKEN}, anche se è il proprio) e la giocata è attribuita a {@code member:<id>} (Q-556).
 */
@RestController
@RequestMapping("/v1/portal/contests")
public class PortalContestsController {

    public record PortalPrize(String code, String name, String type, Long points, String imageUrl, String wheelColor) {
    }

    public record PortalContest(String code, String name, String description, String rulesText, String mechanic,
                                Instant startAt, Instant endAt, int playsAvailable, boolean freePlayDaily,
                                boolean freePlayAvailable, int credits, int playsToday, Integer dailyLimit,
                                List<PortalPrize> prizes) {
    }

    /** Corpo della giocata: vuoto ({@code {}}); {@code memberId} vale solo nel profilo {@code demo}. */
    public record PlayRequest(
            @Schema(deprecated = true, description = "solo profilo demo") String memberId) {
    }

    private final ContestRepository contests;
    private final PlayService playService;
    private final MemberSnapshotRepository members;
    private final Clock clock;

    public PortalContestsController(ContestRepository contests, PlayService playService, MemberSnapshotRepository members,
                                    Clock clock) {
        this.contests = contests;
        this.playService = playService;
        this.members = members;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @MemberEndpoint
    public List<PortalContest> live(MemberPrincipal principal) {
        String memberId = principal.requireParam();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, BusinessCalendar.ZONE);
        boolean active = members.find(memberId).map(s -> "ACTIVE".equals(s.status())).orElse(false);
        return contests.findByStatus(ApprovalStatus.LIVE).stream()
                .filter(c -> playService.isPlayable(c, now))
                .map(c -> view(c, active ? playService.credits(c, memberId, today) : null))
                .toList();
    }

    @PostMapping("/{code}/play")
    @MemberEndpoint
    public PlayService.PlayResult play(@PathVariable String code, @RequestBody PlayRequest r, MemberPrincipal principal) {
        // Il corpo legacy vale solo in demo: con un token, un memberId (anche il proprio) è già rifiutato (MEMBER_FROM_TOKEN).
        return playService.play(code, principal.merge(r == null ? null : r.memberId()));
    }

    @GetMapping("/{code}/plays")
    @Transactional(readOnly = true)
    @MemberEndpoint
    public List<PlayRepository.MemberPlay> plays(@PathVariable String code, MemberPrincipal principal,
                                                 @RequestParam(defaultValue = "10") int limit) {
        return playService.history(code, principal.requireParam(), limit);
    }

    private PortalContest view(Contest c, PlayService.Credits cr) {
        List<PortalPrize> prizes = contests.prizes(c.id()).stream()
                .map(p -> new PortalPrize(p.code(), p.name(), p.type(), p.points(), p.imageUrl(), p.wheelColor()))
                .toList();
        return new PortalContest(c.code(), c.name(), c.description(), c.rulesText(), c.mechanic(), c.startAt(), c.endAt(),
                cr == null ? 0 : cr.playsAvailable(), c.freePlayDaily(), cr != null && cr.freePlayAvailable(),
                cr == null ? 0 : cr.credits(), cr == null ? 0 : cr.playsToday(), c.maxPlaysPerMemberPerDay(), prizes);
    }
}
