package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.LhException;
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
 */
@RestController
@RequestMapping("/v1/portal/leaderboards")
public class PortalLeaderboardsController {

    public record Entry(int rank, String nickname, long score, boolean isMe) {
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
    public List<PortalLeaderboard> list(@RequestParam String memberId,
                                        @RequestParam(required = false, defaultValue = "") String resolve) {
        return leaderboards.findActive().stream().map(l -> view(l, memberId, "ids".equals(resolve))).toList();
    }

    @GetMapping("/{code}")
    @Transactional(readOnly = true)
    public PortalLeaderboard one(@PathVariable String code, @RequestParam String memberId,
                                 @RequestParam(required = false, defaultValue = "") String resolve) {
        Leaderboard l = service.get(code);
        if (!"ACTIVE".equals(l.status())) {
            throw LhException.notFound("Classifica non disponibile: " + code);
        }
        return view(l, memberId, "ids".equals(resolve));
    }

    private PortalLeaderboard view(Leaderboard l, String memberId, boolean resolveIds) {
        List<LeaderboardRepository.Ranked> all = leaderboards.ranking(l.id(), service.currentPeriod(l), 0);
        List<Entry> top = all.stream().limit(l.topN())
                .map(r -> new Entry(r.rank(), resolveIds ? r.memberId() : (r.nickname() == null ? "Socio Aurora" : r.nickname()), r.score(), r.memberId().equals(memberId)))
                .toList();
        Me me = all.stream().filter(r -> r.memberId().equals(memberId)).findFirst().map(r -> new Me(r.rank(), r.score())).orElse(null);
        return new PortalLeaderboard(l.code(), l.name(), l.metric(), l.period(), service.currentPeriod(l), l.topN(), top, me, all.size());
    }
}
