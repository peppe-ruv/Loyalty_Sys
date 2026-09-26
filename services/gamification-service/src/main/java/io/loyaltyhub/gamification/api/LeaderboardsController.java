package io.loyaltyhub.gamification.api;

import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.gamification.application.LeaderboardService;
import io.loyaltyhub.gamification.application.LeaderboardService.LeaderboardRequest;
import io.loyaltyhub.gamification.domain.Leaderboard;
import io.loyaltyhub.gamification.infra.LeaderboardRepository;
import org.springframework.http.HttpStatus;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Gestione delle classifiche e anteprima del ranking (docs/servizi/gamification-service.md §3; BO-16). Il ranking di
 * gestione riporta anche il {@code memberId}: il backoffice affianca il nome reale (dal member-service) al nickname.
 */
@RestController
@RequestMapping("/v1/leaderboards")
public class LeaderboardsController {

    public record Ranking(String code, String name, String metric, String period, String periodKey, String currentPeriodKey,
                          List<String> periods, int topN, List<LeaderboardRepository.Ranked> items) {
    }

    private final LeaderboardService service;
    private final LeaderboardRepository leaderboards;

    public LeaderboardsController(LeaderboardService service, LeaderboardRepository leaderboards) {
        this.service = service;
        this.leaderboards = leaderboards;
    }

    @GetMapping
    public List<Leaderboard> list() {
        return leaderboards.findAll();
    }

    @GetMapping("/{id}")
    public Leaderboard get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<Leaderboard> create(@RequestBody LeaderboardRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(r));
    }

    @PutMapping("/{id}")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public Leaderboard update(@PathVariable String id, @RequestBody LeaderboardRequest r) {
        return service.update(id, r);
    }

    @GetMapping("/{code}/ranking")
    @Transactional(readOnly = true)
    public Ranking ranking(@PathVariable String code, @RequestParam(required = false) String periodKey,
                           @RequestParam(defaultValue = "0") int limit,
                           @RequestParam(required = false, defaultValue = "") String resolve) {
        Leaderboard l = service.get(code);
        String current = service.currentPeriod(l);
        String key = periodKey == null || periodKey.isBlank() ? current : periodKey;
        List<String> periods = new ArrayList<>(leaderboards.periods(l.id()));
        if (!periods.contains(current)) {
            periods.addFirst(current);
        }
        boolean resolveIds = "ids".equals(resolve);
        List<LeaderboardRepository.Ranked> items = leaderboards.ranking(l.id(), key, limit > 0 ? limit : l.topN());
        if (resolveIds) {
            items = items.stream().map(r -> new LeaderboardRepository.Ranked(r.rank(), r.memberId(), r.memberId(), r.score(), r.reachedAt())).toList();
        }
        return new Ranking(l.code(), l.name(), l.metric(), l.period(), key, current, periods, l.topN(), items);
    }
}
