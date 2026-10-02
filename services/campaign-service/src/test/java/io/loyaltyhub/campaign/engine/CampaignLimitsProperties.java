package io.loyaltyhub.campaign.engine;

import io.loyaltyhub.campaign.domain.Campaign;
import io.loyaltyhub.campaign.domain.CampaignStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Limiti delle campagne mai superati» (docs/03 §3.2 e §3.5, F-CMP-05, Q-165, Q-237; ADR-053 decisione 1, Q-690).
 *
 * <p>Per qualunque insieme di campagne (1–3, con limiti per membro e periodo, tetto di punti per membro, budget globale in
 * punti e in match, cooldown, e al più una campagna con {@code MULTIPLIER} che amplifica le altre) e per qualunque
 * sequenza di azioni qualificanti di più membri, con tempi che avanzano e anche tornano indietro a cavallo di giorno,
 * settimana, mese e cambio dell'ora legale, il {@link CampaignEngine} reale non decide mai oltre i limiti configurati:
 * <ul>
 *   <li>match per membro e periodo ≤ {@code perMember[].max} (DAY, WEEK, MONTH, ALWAYS);</li>
 *   <li>punti per membro ≤ {@code perMemberPoints} (l'ultimo accredito si riduce al residuo, Q-237);</li>
 *   <li>punti della campagna ≤ {@code global.maxPoints} e match ≤ {@code global.maxMatches};</li>
 *   <li>due match dello stesso membro distano almeno {@code cooldownMinutes}.</li>
 * </ul>
 * I conteggi attesi vengono ricalcolati dal test dagli effetti emessi e dai tempi delle azioni, con
 * {@link java.time} e non con {@link PeriodKeys}, così anche la chiave del periodo è sotto verifica.
 *
 * <p><b>Cosa non copre.</b> Il motore legge i contatori da {@link Counters}; la loro scrittura sta in
 * {@code EvaluationService.consumeLimits} e in {@code CounterRepository} (SQL, transazione Spring). Qui {@link LedgerOfMatches}
 * ne è la controparte in memoria con la stessa contabilità (un incremento per periodo distinto più la riga {@code ALWAYS};
 * punti = Σ effetti {@code points.grant} della campagna): resta fuori l'atomicità sotto concorrenza, che è dei test
 * d'integrazione.
 */
class CampaignLimitsProperties {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final String TYPE = "purchase.completed";
    private static final List<String> MEMBERS = List.of("MBR-1", "MBR-2", "MBR-3");
    private static final List<String> PERIODS = List.of("DAY", "WEEK", "MONTH", "ALWAYS");
    /** Mercoledì 18 marzo 2026: dopo di lui il cambio all'ora legale (29 marzo) e la fine del mese cadono nella finestra. */
    private static final Instant START = Instant.parse("2026-03-18T09:00:00Z");
    private static final int MAX_MULTIPLIER = 5;

    record PerMemberLimit(String period, int max) {
    }

    record Limits(List<PerMemberLimit> perMember, Long perMemberPoints, Long maxPoints, Long maxMatches,
                  Integer cooldownMinutes) {
    }

    /** {@code grants}: valori FIXED di uno o due effetti GRANT_POINTS; {@code multiplier}: campagna-moltiplicatore. */
    record CampaignSpec(String code, int priority, Limits limits, List<Long> grants, Integer multiplier) {
        boolean booster() {
            return multiplier != null;
        }
    }

    record Step(int member, int minutes) {
    }

    record Scenario(List<CampaignSpec> campaigns, List<Step> steps) {
    }

    // ------------------------------------------------------------------------------------------------------------
    // Generatori
    // ------------------------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<Limits> limits() {
        Arbitrary<List<PerMemberLimit>> perMember = Combinators.combine(Arbitraries.of(PERIODS),
                        Arbitraries.integers().between(1, 4)).as(PerMemberLimit::new).list().ofMaxSize(2);
        Arbitrary<Long> perMemberPoints = optional(Arbitraries.longs().between(1, 900));
        Arbitrary<Long> maxPoints = optional(Arbitraries.longs().between(1, 3_000));
        Arbitrary<Long> maxMatches = optional(Arbitraries.longs().between(1, 8));
        Arbitrary<Integer> cooldown = Arbitraries.frequencyOf(Tuple.of(3, Arbitraries.just((Integer) null)),
                Tuple.of(2, Arbitraries.integers().between(1, 3_000)));
        return Combinators.combine(perMember, perMemberPoints, maxPoints, maxMatches, cooldown).as(Limits::new);
    }

    private static Arbitrary<Long> optional(Arbitrary<Long> values) {
        return Arbitraries.frequencyOf(Tuple.of(1, Arbitraries.just((Long) null)), Tuple.of(1, values));
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<CampaignSpec> earning = Combinators.combine(limits(), Arbitraries.integers().between(1, 300),
                Arbitraries.longs().between(1, 400).list().ofMinSize(1).ofMaxSize(2))
                .as((l, priority, grants) -> new CampaignSpec(null, priority, l, grants, null));
        Arbitrary<CampaignSpec> booster = Combinators.combine(limits(), Arbitraries.integers().between(1, 300),
                Arbitraries.integers().between(2, 4))
                .as((l, priority, factor) -> new CampaignSpec(null, priority, l, List.of(), factor));
        Arbitrary<List<CampaignSpec>> campaigns = Combinators.combine(
                earning.list().ofMinSize(1).ofMaxSize(2), booster.optional()).as((e, b) -> {
            List<CampaignSpec> all = new ArrayList<>(e);
            b.ifPresent(all::add);
            List<CampaignSpec> coded = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                CampaignSpec c = all.get(i);
                coded.add(new CampaignSpec("CMP-" + (char) ('A' + i), c.priority(), c.limits(), c.grants(),
                        c.multiplier()));
            }
            return coded;
        });
        // Minuti dall'azione precedente: ravvicinati, di ore, di giorni/settimane, e qualche passo all'indietro.
        Arbitrary<Integer> minutes = Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.integers().between(0, 90)),
                Tuple.of(3, Arbitraries.integers().between(91, 1_500)),
                Tuple.of(2, Arbitraries.integers().between(1_501, 60_000)),
                Tuple.of(1, Arbitraries.integers().between(-1_500, -1)));
        Arbitrary<List<Step>> steps = Combinators.combine(Arbitraries.integers().between(0, MEMBERS.size() - 1), minutes)
                .as(Step::new).list().ofMinSize(1).ofMaxSize(30);
        return Combinators.combine(campaigns, steps).as(Scenario::new);
    }

    // ------------------------------------------------------------------------------------------------------------
    // La proprietà
    // ------------------------------------------------------------------------------------------------------------

    @Property(tries = 1000)
    void configuredLimitsAreNeverExceeded(@ForAll("scenarios") Scenario scenario) {
        CampaignEngine engine = new CampaignEngine(MAX_MULTIPLIER);
        List<Campaign> campaigns = scenario.campaigns().stream().map(CampaignLimitsProperties::toCampaign).toList();
        LedgerOfMatches counters = new LedgerOfMatches();
        Tally tally = new Tally();
        boolean sawLimit = false;
        boolean sawBudget = false;
        boolean sawReduction = false;
        boolean sawMultiplier = false;

        Instant time = START;
        int index = 0;
        for (Step step : scenario.steps()) {
            time = time.plus(Duration.ofMinutes(step.minutes()));
            String memberId = MEMBERS.get(step.member());
            EvalAction action = new EvalAction("ACT-" + (++index), TYPE, memberId, "urn:loyaltyhub:source:prop", time,
                    JSON.createObjectNode());
            MemberSnapshot member = new MemberSnapshot(memberId, "ACTIVE", "BASE", List.of(), List.of(),
                    JSON.createObjectNode(), START.minus(Duration.ofDays(400)), LocalDate.of(1990, 5, 20));

            Evaluation ev = engine.evaluate(action, member, campaigns, counters);

            for (Evaluation.CampaignResult r : ev.results()) {
                if (r.matched()) {
                    long points = ev.effects().stream().filter(g -> g.campaignCode().equals(r.campaignCode()))
                            .mapToLong(GrantedEffect::amount).sum();
                    tally.record(r.campaignCode(), memberId, time, points);
                } else if (r.reason() == Evaluation.SkipReason.LIMIT) {
                    sawLimit = true;
                } else if (r.reason() == Evaluation.SkipReason.BUDGET) {
                    sawBudget = true;
                }
            }
            for (GrantedEffect g : ev.effects()) {
                assertThat(g.amount()).as("un effetto emesso ha sempre importo positivo").isPositive();
                sawReduction |= g.amount() < (long) Math.floor(g.baseAmount() * g.campaignMultiplier());
                sawMultiplier |= g.campaignMultiplier() > 1.0;
            }
            if (ev.outcome() == Evaluation.Outcome.MATCHED) {
                counters.consume(ev, campaigns, action);
            }
            tally.assertWithin(scenario.campaigns());
        }

        Statistics.label("scarto LIMIT").collect(sawLimit);
        Statistics.label("scarto BUDGET").collect(sawBudget);
        Statistics.label("accredito ridotto al residuo").collect(sawReduction);
        Statistics.label("moltiplicatore applicato").collect(sawMultiplier);
        for (String feature : List.of("scarto LIMIT", "scarto BUDGET", "accredito ridotto al residuo",
                "moltiplicatore applicato")) {
            Statistics.label(feature).coverage(c -> c.check(true).percentage(p -> p >= 3.0));
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tabulazione indipendente dagli effetti emessi
    // ------------------------------------------------------------------------------------------------------------

    private record Match(String member, Instant time, long points) {
    }

    /** Match decisi, per campagna, ricostruiti solo da ciò che il motore ha emesso e dai tempi delle azioni. */
    private static final class Tally {
        private final Map<String, List<Match>> byCampaign = new HashMap<>();

        void record(String campaign, String member, Instant time, long points) {
            byCampaign.computeIfAbsent(campaign, k -> new ArrayList<>()).add(new Match(member, time, points));
        }

        void assertWithin(List<CampaignSpec> specs) {
            for (CampaignSpec spec : specs) {
                List<Match> matches = byCampaign.getOrDefault(spec.code(), List.of());
                Limits l = spec.limits();
                for (PerMemberLimit lim : l.perMember()) {
                    Map<String, Integer> perBucket = new HashMap<>();
                    for (Match m : matches) {
                        perBucket.merge(m.member() + "|" + bucket(lim.period(), m.time()), 1, Integer::sum);
                    }
                    assertThat(perBucket.values()).as("%s: match per membro e periodo %s (max %d)", spec.code(),
                            lim.period(), lim.max()).allMatch(n -> n <= lim.max());
                }
                if (l.perMemberPoints() != null) {
                    Map<String, Long> perMember = new HashMap<>();
                    matches.forEach(m -> perMember.merge(m.member(), m.points(), Long::sum));
                    assertThat(perMember.values()).as("%s: punti per membro (tetto %d)", spec.code(), l.perMemberPoints())
                            .allMatch(n -> n <= l.perMemberPoints());
                }
                if (l.maxPoints() != null) {
                    assertThat(matches.stream().mapToLong(Match::points).sum())
                            .as("%s: budget globale in punti", spec.code()).isLessThanOrEqualTo(l.maxPoints());
                }
                if (l.maxMatches() != null) {
                    assertThat((long) matches.size()).as("%s: budget globale in match", spec.code())
                            .isLessThanOrEqualTo(l.maxMatches());
                }
                if (l.cooldownMinutes() != null) {
                    Duration cooldown = Duration.ofMinutes(l.cooldownMinutes());
                    for (int i = 0; i < matches.size(); i++) {
                        for (int j = i + 1; j < matches.size(); j++) {
                            Match a = matches.get(i);
                            Match b = matches.get(j);
                            if (a.member().equals(b.member())) {
                                assertThat(Duration.between(a.time(), b.time()).abs())
                                        .as("%s: cooldown tra due match di %s", spec.code(), a.member())
                                        .isGreaterThanOrEqualTo(cooldown);
                            }
                        }
                    }
                }
            }
        }

        /** Periodo calcolato con java.time su Europe/Rome, senza passare da {@link PeriodKeys}. */
        private static String bucket(String period, Instant time) {
            LocalDate day = time.atZone(ROME).toLocalDate();
            return switch (period) {
                case "DAY" -> day.toString();
                case "WEEK" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                case "MONTH" -> YearMonth.from(day).toString();
                default -> "ALWAYS";
            };
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Contatori in memoria con la contabilità di EvaluationService.consumeLimits
    // ------------------------------------------------------------------------------------------------------------

    private static final class LedgerOfMatches implements Counters {
        private record Row(int matches, long points, Instant lastMatchAt) {
        }

        private final Map<String, Row> rows = new HashMap<>();
        private final Map<String, Long> totalPoints = new HashMap<>();
        private final Map<String, Long> totalMatches = new HashMap<>();

        private static String key(String campaignId, String memberId, String period, String periodKey) {
            return campaignId + "|" + memberId + "|" + period + "|" + periodKey;
        }

        /** Come {@code EvaluationService}: per ogni campagna in match, un incremento per periodo distinto + ALWAYS + totali. */
        void consume(Evaluation ev, List<Campaign> campaigns, EvalAction action) {
            for (Evaluation.CampaignResult r : ev.results()) {
                if (!r.matched()) {
                    continue;
                }
                Campaign c = campaigns.stream().filter(x -> x.code().equals(r.campaignCode())).findFirst().orElseThrow();
                long points = ev.effects().stream().filter(g -> g.campaignCode().equals(c.code()))
                        .mapToLong(GrantedEffect::amount).sum();
                Set<String> seen = new HashSet<>();
                if (c.limits() != null && c.limits().get("perMember") != null) {
                    for (var lim : c.limits().get("perMember")) {
                        String period = lim.path("period").asString("ALWAYS");
                        String periodKey = PeriodKeys.of(period, action.time());
                        if (seen.add(period + "|" + periodKey)) {
                            add(c.id(), action.memberId(), period, periodKey, points, action.time());
                        }
                    }
                }
                if (seen.add("ALWAYS|ALWAYS")) {
                    add(c.id(), action.memberId(), "ALWAYS", "ALWAYS", points, action.time());
                }
                totalPoints.merge(c.id(), points, Long::sum);
                totalMatches.merge(c.id(), 1L, Long::sum);
            }
        }

        private void add(String campaignId, String memberId, String period, String periodKey, long points, Instant at) {
            rows.merge(key(campaignId, memberId, period, periodKey), new Row(1, points, at),
                    (old, one) -> new Row(old.matches() + 1, old.points() + points,
                            old.lastMatchAt().isAfter(at) ? old.lastMatchAt() : at));
        }

        @Override
        public int memberMatches(String campaignId, String memberId, String period, String periodKey) {
            Row row = rows.get(key(campaignId, memberId, period, periodKey));
            return row == null ? 0 : row.matches();
        }

        @Override
        public long globalPointsDecided(String campaignId) {
            return totalPoints.getOrDefault(campaignId, 0L);
        }

        @Override
        public long globalMatches(String campaignId) {
            return totalMatches.getOrDefault(campaignId, 0L);
        }

        @Override
        public long historyActionCount(String memberId, String actionType) {
            return 0;
        }

        @Override
        public long historyDaysSinceLastAction(String memberId, String actionType) {
            return -1;
        }

        @Override
        public long memberPoints(String campaignId, String memberId) {
            Row row = rows.get(key(campaignId, memberId, "ALWAYS", "ALWAYS"));
            return row == null ? 0 : row.points();
        }

        @Override
        public Instant memberLastMatchAt(String campaignId, String memberId) {
            Row row = rows.get(key(campaignId, memberId, "ALWAYS", "ALWAYS"));
            return row == null ? null : row.lastMatchAt();
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Campaign toCampaign(CampaignSpec spec) {
        ArrayNode effects = JSON.createArrayNode();
        if (spec.booster()) {
            ObjectNode m = effects.addObject();
            m.put("type", "MULTIPLIER");
            m.put("currency", "PTS");
            m.put("factor", spec.multiplier());
            m.put("scope", "ALL_GRANTS");
        }
        for (long value : spec.grants()) {
            ObjectNode g = effects.addObject();
            g.put("type", "GRANT_POINTS");
            g.put("currency", "PTS");
            g.put("mode", "FIXED");
            g.put("value", value);
        }
        ObjectNode limits = JSON.createObjectNode();
        Limits l = spec.limits();
        ArrayNode perMember = limits.putArray("perMember");
        for (PerMemberLimit p : l.perMember()) {
            ObjectNode o = perMember.addObject();
            o.put("max", p.max());
            o.put("period", p.period());
        }
        if (l.perMemberPoints() != null) {
            limits.put("perMemberPoints", l.perMemberPoints());
        }
        ObjectNode global = limits.putObject("global");
        if (l.maxPoints() != null) {
            global.put("maxPoints", l.maxPoints());
        }
        if (l.maxMatches() != null) {
            global.put("maxMatches", l.maxMatches());
        }
        if (l.cooldownMinutes() != null) {
            limits.put("cooldownMinutes", l.cooldownMinutes());
        }
        return new Campaign("ID-" + spec.code(), spec.code(), "Campagna " + spec.code(), null, null, null,
                List.of(TYPE), null, null, effects, limits, null, spec.priority(), null, false, false, false,
                List.of(), CampaignStatus.LIVE, 0, null, null);
    }
}
