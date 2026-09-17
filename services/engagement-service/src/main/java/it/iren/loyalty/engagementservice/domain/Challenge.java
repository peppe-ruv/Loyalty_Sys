package it.iren.loyalty.engagementservice.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Challenge (RF-91): fino a 6 milestone indipendenti (ognuna un {@link Achievement} DIRECT o REFERRAL, ossia progredita
 * dalle azioni del membro o dei suoi presentati), finestre di disponibilità (giorni della settimana, ore, date),
 * visibilità, regole con trigger MILESTONE_PROGRESSED o CHALLENGE_COMPLETED e condizioni sul numero di completamenti,
 * limiti e budget. Le missioni del programma annuale (RF-21) sono challenge con {@code programYear} valorizzato.
 */
public record Challenge(
        String id, String version, String name, boolean active,
        Instant startsAt, Instant endsAt,
        Availability availability,
        Visibility visibility,
        List<Milestone> milestones,
        List<Rule> rules,
        Limit completionLimit,
        Integer programYear,
        String badgeCode
) {
    public static final int MAX_MILESTONES = 6;
    public record Milestone(String id, String name, Kind kind, Achievement definition) {
        public enum Kind { DIRECT, REFERRAL }
    }
    /** Finestre in cui le azioni contano: giorni della settimana (1=lun), ore [from,to), date esplicite; vuoto = sempre. */
    public record Availability(Set<Integer> daysOfWeek, Integer hourFrom, Integer hourTo, Set<String> dates) {
        public static final Availability ALWAYS = new Availability(Set.of(), null, null, Set.of());
        public boolean admits(Instant at) {
            var z = at.atZone(java.time.ZoneId.of("Europe/Rome"));
            if (daysOfWeek != null && !daysOfWeek.isEmpty() && !daysOfWeek.contains(z.getDayOfWeek().getValue())) return false;
            if (hourFrom != null && hourTo != null && (z.getHour() < hourFrom || z.getHour() >= hourTo)) return false;
            if (dates != null && !dates.isEmpty() && !dates.contains(z.toLocalDate().toString())) return false;
            return true;
        }
    }
    public record Visibility(String mode, Set<String> segments, Set<String> tiers) { public static final Visibility EVERYONE = new Visibility("EVERYONE", Set.of(), Set.of()); }
    /** Regola: scatta su avanzamento milestone o completamento challenge; condizione opzionale sul conteggio completamenti. */
    public record Rule(String id, Trigger trigger, Integer maxCompletionCount, List<Effect> effects) {
        public enum Trigger { MILESTONE_PROGRESSED, CHALLENGE_COMPLETED }
    }
    public record Effect(String type, String wallet, long units, String reference, Map<String, String> params) {}
    public record Limit(int max, Achievement.Period period) { public static final Limit NONE = new Limit(0, Achievement.Period.TOTAL); }

    public Challenge {
        if (milestones == null || milestones.isEmpty() || milestones.size() > MAX_MILESTONES) throw new IllegalArgumentException("1.." + MAX_MILESTONES + " milestones");
        if (availability == null) availability = Availability.ALWAYS;
        if (visibility == null) visibility = Visibility.EVERYONE;
        if (completionLimit == null) completionLimit = Limit.NONE;
        if (rules == null) rules = List.of();
    }

    public boolean isActiveAt(Instant t) { return active && (startsAt == null || !t.isBefore(startsAt)) && (endsAt == null || t.isBefore(endsAt)); }
}
