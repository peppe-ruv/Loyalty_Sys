package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.MemberEndpoint;
import io.loyaltyhub.common.web.MemberPrincipal;
import io.loyaltyhub.gamification.application.LeaderboardService;
import io.loyaltyhub.gamification.domain.Leaderboard;
import io.loyaltyhub.gamification.infra.LeaderboardRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Portale: classifiche con podio, top N e posizione del membro (docs/servizi/gamification-service.md §3; PT-10).
 * Solo nickname, mai nomi reali né identificativi degli altri membri.
 * <p>Con {@code resolve=ids} (Q-368, ADR-032) ogni voce porta il {@code memberId} al posto del nickname dello snapshot:
 * è la variante per il BFF, che chiede i soprannomi a member-service e li inserisce lato server, senza mai inoltrare
 * al browser gli id degli altri membri. Senza parametro la risposta resta quella di sempre.
 * <p>Il membro viene solo dal token (Q-410, ADR-048, docs/06 §3.4): {@code isMe} e {@code me} sono quelli del
 * {@link MemberPrincipal}, nessun {@code memberId} in query. Per un principal da token ({@code enterprise}) il
 * parametro {@code resolve=ids} è ignorato (Q-559): il membro non riceve mai gli id degli altri; i soprannomi
 * restano quelli dello snapshot o il segnaposto finché Q-411 non dà al BFF un'identità di servizio.
 */
@RestController
@RequestMapping("/v1/portal/leaderboards")
public class PortalLeaderboardsController {

    /** {@code memberId} solo con {@code resolve=ids} (omesso altrimenti); {@code nickname} solo senza. */
    public record Entry(int rank, String memberId, String nickname, long score, boolean isMe) {
    }

    public record Me(int rank, long score) {
    }

    public record PortalLeaderboard(String code, String name, String metric, String period, String periodKey, int topN,
                                    List<Entry> top, Me me, int participants) {
    }

    private final LeaderboardService service;
    private final LeaderboardRepository leaderboards;

    public PortalLeaderboardsController(LeaderboardService service, LeaderboardRepository leaderboards) {
        this.service = service;
        this.leaderboards = leaderboards;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @MemberEndpoint
    public List<PortalLeaderboard> list(MemberPrincipal principal, @RequestParam(required = false) String resolve) {
        String memberId = principal.requireParam();
        boolean ids = ids(resolve, principal);
        return leaderboards.findActive().stream().map(l -> view(l, memberId, ids)).toList();
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    @MemberEndpoint
    public PortalLeaderboard one(@PathVariable String code, MemberPrincipal principal,
                                 @RequestParam(required = false) String resolve) {
        String memberId = principal.requireParam();
        boolean ids = ids(resolve, principal);
        Leaderboard l = service.get(code);
        if (!"ACTIVE".equals(l.status())) {
            throw LhException.notFound("Classifica non disponibile: " + code);
        }
        return view(l, memberId, ids);
    }

    /**
     * {@code resolve=ids} solo per il BFF demo (Q-368); per un principal da token è ignorato (Q-559, D12). Il valore si
     * valida comunque: un {@code resolve} diverso da {@code ids} resta {@code 400}.
     */
    private static boolean ids(String resolve, MemberPrincipal principal) {
        boolean requested = Resolve.ids(resolve);
        return requested && principal.origin() != MemberPrincipal.Origin.TOKEN;
    }

    private PortalLeaderboard view(Leaderboard l, String memberId, boolean ids) {
        List<LeaderboardRepository.Ranked> all = leaderboards.ranking(l.id(), service.currentPeriod(l), 0);
        List<Entry> top = all.stream().limit(l.topN())
                .map(r -> ids
                        ? new Entry(r.rank(), r.memberId(), null, r.score(), r.memberId().equals(memberId))
                        : new Entry(r.rank(), null, r.nickname() == null ? "Socio Aurora" : r.nickname(), r.score(), r.memberId().equals(memberId)))
                .toList();
        Me me = all.stream().filter(r -> r.memberId().equals(memberId)).findFirst().map(r -> new Me(r.rank(), r.score())).orElse(null);
        return new PortalLeaderboard(l.code(), l.name(), l.metric(), l.period(), service.currentPeriod(l), l.topN(), top, me, all.size());
    }
}
