package io.loyaltyhub.gamification.application;

import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.event.LhEventFactory;
import io.loyaltyhub.common.event.LhEventTypes;
import io.loyaltyhub.common.event.LhSource;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.common.outbox.OutboxWriter;
import io.loyaltyhub.common.time.BusinessCalendar;
import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.gamification.domain.Contest;
import io.loyaltyhub.gamification.domain.Prize;
import io.loyaltyhub.gamification.infra.ContestRepository;
import io.loyaltyhub.gamification.infra.InstantRepository;
import io.loyaltyhub.gamification.infra.MemberSnapshotRepository;
import io.loyaltyhub.gamification.infra.PlayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Giocata instant win (docs/03 §6, docs/servizi/gamification-service.md §5; F-IW-04, F-IW-05). Una transazione:
 * lock (membro, concorso), verifica di membro, concorso e crediti, claim del primo istante {@code OPEN} già passato
 * ({@code FOR UPDATE SKIP LOCKED}), decremento del premio, outbox {@code contest.played} (+ {@code contest.won}).
 * L'esito è sincrono perché locale; la consegna del premio è asincrona (ponte → {@code instantwin.won}, M5.3).
 */
@Service
public class PlayService {

    /** Crediti di un membro su un concorso oggi (Europe/Rome). */
    public record Credits(boolean freePlayAvailable, int credits, int playsToday, Integer dailyLimit, int playsAvailable) {
    }

    public record PrizeView(String code, String name, String type, Long points, String rewardCode, String wheelColor) {
    }

    public record PlayResult(String playId, String outcome, PrizeView prize, int playsAvailable, String correlationId) {
    }

    private final ContestRepository contests;
    private final InstantRepository instants;
    private final PlayRepository plays;
    private final MemberSnapshotRepository members;
    private final LhEventFactory events;
    private final OutboxWriter outbox;
    private final Clock clock;

    public PlayService(ContestRepository contests, InstantRepository instants, PlayRepository plays,
                       MemberSnapshotRepository members, LhEventFactory events, OutboxWriter outbox, Clock clock) {
        this.contests = contests;
        this.instants = instants;
        this.plays = plays;
        this.members = members;
        this.events = events;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Disponibili = giocata gratuita di oggi (se il concorso la prevede e non è stata usata) + Σ crediti − giocate da
     * credito, col tetto giornaliero {@code maxPlaysPerMemberPerDay} (docs/03 §6).
     */
    public Credits credits(Contest c, String memberId, LocalDate today) {
        boolean free = c.freePlayDaily() && !plays.freeUsedOn(memberId, c.id(), today);
        int credits = Math.max(0, plays.sumGrants(memberId, c.id()) - plays.countCreditPlays(memberId, c.id()));
        int playedToday = plays.countOnDate(memberId, c.id(), today);
        int available = (free ? 1 : 0) + credits;
        if (c.maxPlaysPerMemberPerDay() != null) {
            available = Math.max(0, Math.min(available, c.maxPlaysPerMemberPerDay() - playedToday));
        }
        return new Credits(free, credits, playedToday, c.maxPlaysPerMemberPerDay(), available);
    }

    public boolean isPlayable(Contest c, Instant now) {
        return c.status() == ApprovalStatus.LIVE && !now.isBefore(c.startAt()) && now.isBefore(c.endAt());
    }

    @Transactional
    public PlayResult play(String contestCode, String memberId) {
        if (memberId == null || memberId.isBlank()) {
            throw LhException.validation("MEMBER_REQUIRED", "memberId obbligatorio.");
        }
        Contest c = contests.find(contestCode).filter(x -> x.code().equals(contestCode))
                .orElseThrow(() -> LhException.notFound("Concorso non trovato: " + contestCode));
        plays.lockMemberContest(memberId, c.id());
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, BusinessCalendar.ZONE);

        String status = members.find(memberId).map(MemberSnapshotRepository.Snapshot::status).orElse(null);
        if (!"ACTIVE".equals(status)) {
            throw LhException.validation("MEMBER_NOT_ACTIVE", "Il membro non può giocare (stato " + (status == null ? "sconosciuto" : status) + ").");
        }
        if (!isPlayable(c, now)) {
            throw LhException.validation("CONTEST_NOT_LIVE", "Il concorso " + c.name() + " non è in corso.");
        }
        Credits credits = credits(c, memberId, today);
        if (c.maxPlaysPerMemberPerDay() != null && credits.playsToday() >= c.maxPlaysPerMemberPerDay()) {
            throw LhException.validation("DAILY_LIMIT_REACHED",
                    "Hai già fatto " + credits.playsToday() + " giocate oggi: il massimo è " + c.maxPlaysPerMemberPerDay() + ".");
        }
        if (credits.playsAvailable() <= 0) {
            throw LhException.validation("NO_PLAYS_AVAILABLE", "Nessuna giocata disponibile: torna domani o guadagnane altre.");
        }
        String kind = credits.freePlayAvailable() ? "FREE_DAILY" : "CREDIT";
        String playId = Ulid.next(clock);

        boolean winsCapped = c.maxWinsPerMember() != null && plays.countWins(memberId, c.id()) >= c.maxWinsPerMember();
        Optional<Prize> prize = winsCapped ? Optional.empty()
                : instants.claim(c.id(), memberId, playId, now).flatMap(contests::prize);
        prize.ifPresent(p -> contests.decrementRemaining(p.id()));
        String outcome = prize.isPresent() ? "WIN" : "LOSE";

        int remaining = credits.playsAvailable() - 1;
        Map<String, Object> played = new LinkedHashMap<>();
        played.put("contestCode", c.code());
        played.put("playId", playId);
        played.put("outcome", outcome);
        played.put("kind", kind);
        played.put("playsAvailable", remaining);
        LhEvent<Map<String, Object>> playedEvent = events.newRoot(LhEventTypes.Fact.CONTEST_PLAYED, "member:" + memberId, played,
                LhSource.service("gamification"), "MEMBER:" + memberId);
        plays.insert(new PlayRepository.NewPlay(playId, c.id(), memberId, kind, outcome, prize.map(Prize::id).orElse(null), now,
                today, playedEvent.lhcorrelationid(), prize.filter(p -> "PHYSICAL".equals(p.type())).isPresent() ? "PENDING" : "NA"));
        outbox.write(playedEvent);
        prize.ifPresent(p -> {
            Map<String, Object> won = new LinkedHashMap<>();
            won.put("contestCode", c.code());
            won.put("playId", playId);
            won.put("prizeCode", p.code());
            won.put("prizeName", p.name());
            won.put("prizeType", p.type());
            if (p.points() != null) {
                won.put("points", p.points());
            }
            if (p.rewardCode() != null) {
                won.put("rewardCode", p.rewardCode());
            }
            outbox.write(events.childOf(playedEvent, LhEventTypes.Fact.CONTEST_WON, won));
        });
        return new PlayResult(playId, outcome, prize.map(PlayService::view).orElse(null), Math.max(0, remaining),
                playedEvent.lhcorrelationid());
    }

    public List<PlayRepository.MemberPlay> history(String contestCode, String memberId, int limit) {
        Contest c = contests.find(contestCode).filter(x -> x.code().equals(contestCode))
                .orElseThrow(() -> LhException.notFound("Concorso non trovato: " + contestCode));
        return plays.history(memberId, c.id(), Math.clamp(limit, 1, 50));
    }

    public static PrizeView view(Prize p) {
        return new PrizeView(p.code(), p.name(), p.type(), p.points(), p.rewardCode(), p.wheelColor());
    }
}
