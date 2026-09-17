package it.iren.loyalty.segmentservice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Valuta i criteri di un segmento su una {@link MemberSnapshot}. Pura e senza stato: la usano lo scheduler e il simulatore del backoffice. */
public class SegmentEvaluator {
    private static final ZoneId ROME = ZoneId.of("Europe/Rome");

    public boolean matches(Segment segment, MemberSnapshot m, Instant now) {
        if (segment.criteria() == null || segment.criteria().isEmpty()) return false;
        Stream<Segment.Criterion> s = segment.criteria().stream();
        return segment.match() == Segment.Match.ANY ? s.anyMatch(c -> test(c, m, now)) : s.allMatch(c -> test(c, m, now));
    }

    boolean test(Segment.Criterion c, MemberSnapshot m, Instant now) {
        List<MemberSnapshot.ActionSummary> acts = m.actions() == null ? List.of() : m.actions();
        return switch (c.type()) {
            case ANNIVERSARY -> {
                if (m.enrolledAt() == null) yield false;
                LocalDate today = LocalDate.ofInstant(now, ROME), joined = LocalDate.ofInstant(m.enrolledAt(), ROME);
                LocalDate next = joined.withYear(today.getYear());
                if (next.isBefore(today)) next = next.plusYears(1);
                // anniversario (almeno il primo) entro N giorni da oggi
                yield next.getYear() > joined.getYear() && ChronoUnit.DAYS.between(today, next) <= (c.num("days") == null ? 0 : c.num("days"));
            }
            case AVG_ACTION_VALUE -> {
                List<MemberSnapshot.ActionSummary> sel = withAmount(filter(acts, c, now));
                if (sel.isEmpty()) yield false;
                BigDecimal sum = sel.stream().map(MemberSnapshot.ActionSummary::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield between(sum.divide(BigDecimal.valueOf(sel.size()), 2, RoundingMode.HALF_UP).doubleValue(), c);
            }
            case ACTION_COUNT -> between(filter(acts, c, now).size(), c);
            case ACTION_VALUE -> between(filter(acts, c, now).stream().map(MemberSnapshot.ActionSummary::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue(), c);
            case LAST_ACTION_DAYS_AGO -> {
                var last = filter(acts, c, Instant.MAX).stream().map(MemberSnapshot.ActionSummary::occurredAt).filter(Objects::nonNull).max(Instant::compareTo);
                yield last.isPresent() && between(Duration.between(last.get(), now).toDays(), c);
            }
            case ACTION_PERIOD -> {
                Instant from = c.str("from") == null ? Instant.MIN : Instant.parse(c.str("from")), to = c.str("to") == null ? Instant.MAX : Instant.parse(c.str("to"));
                yield filter(acts, c, Instant.MAX).stream().anyMatch(a -> a.occurredAt() != null && !a.occurredAt().isBefore(from) && a.occurredAt().isBefore(to));
            }
            case BOUGHT_SKU -> lines(acts, c, now).anyMatch(l -> l.sku() != null && c.values().contains(l.sku()));
            case BOUGHT_BRAND -> lines(acts, c, now).anyMatch(l -> l.brand() != null && c.values().contains(l.brand()));
            case BOUGHT_CATEGORY -> lines(acts, c, now).anyMatch(l -> l.category() != null && c.values().contains(l.category()));
            case BOUGHT_LABEL -> lines(acts, c, now).anyMatch(l -> l.labels() != null && l.labels().stream().anyMatch(c.values()::contains));
            case ACTION_IN_CHANNEL -> filter(acts, c, now).stream().anyMatch(a -> a.channel() != null && c.values().contains(a.channel()));
            case CHANNEL_SHARE -> {
                List<MemberSnapshot.ActionSummary> sel = withAmount(filter(acts, c, now));
                BigDecimal total = sel.stream().map(MemberSnapshot.ActionSummary::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (total.signum() == 0) yield false;
                BigDecimal in = sel.stream().filter(a -> Objects.equals(a.channel(), c.str("channel"))).map(MemberSnapshot.ActionSummary::amountOrZero).reduce(BigDecimal.ZERO, BigDecimal::add);
                yield in.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP).doubleValue() >= (c.num("minPercent") == null ? 0 : c.num("minPercent"));
            }
            case HAS_LABEL -> m.labels() != null && c.values().stream().anyMatch(m.labels()::containsKey);
            case LABEL_VALUE -> m.labels() != null && Objects.equals(m.labels().get(c.str("key")), c.str("value"));
            case STATIC_LIST -> c.values().contains(m.memberId());
            case TIER -> m.tier() != null && c.values().contains(m.tier());
            case POINTS_BALANCE -> between(m.premioAvailable(), c);
            case CONSENT -> m.consents() != null && Boolean.TRUE.equals(m.consents().get(c.str("key")));
        };
    }

    private static List<MemberSnapshot.ActionSummary> filter(List<MemberSnapshot.ActionSummary> acts, Segment.Criterion c, Instant now) {
        String type = c.str("actionType");
        Instant since = c.days() == Integer.MAX_VALUE || now == Instant.MAX ? Instant.MIN : now.minus(Duration.ofDays(c.days()));
        return acts.stream()
                .filter(a -> type == null || type.equals(a.actionType()))
                .filter(a -> a.occurredAt() == null || !a.occurredAt().isBefore(since))
                .toList();
    }

    private static List<MemberSnapshot.ActionSummary> withAmount(List<MemberSnapshot.ActionSummary> acts) {
        return acts.stream().filter(a -> a.amountEur() != null).toList();
    }

    private static Stream<MemberSnapshot.Line> lines(List<MemberSnapshot.ActionSummary> acts, Segment.Criterion c, Instant now) {
        return filter(acts, c, now).stream().filter(a -> a.lines() != null).flatMap(a -> a.lines().stream());
    }

    private static boolean between(double v, Segment.Criterion c) {
        Double min = c.num("min"), max = c.num("max");
        return (min == null || v >= min) && (max == null || v <= max);
    }
}
