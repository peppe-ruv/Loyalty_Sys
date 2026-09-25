package io.loyaltyhub.gamification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Generatore degli istanti vincenti (docs/03 §6, docs/servizi/gamification-service.md §5; F-IW-03): per ogni premio,
 * in ordine di {@code sort_order}, {@code quantity_total} istanti in {@code [startAt, endAt)}. {@code UNIFORM} = uniforme
 * sul periodo; {@code BUSINESS_HOURS} = solo 08–22 {@code Europe/Rome} (rigetta e ricampiona). {@link SplittableRandom}
 * col seme del concorso: stesso seme e stessi parametri → stessi istanti.
 */
public final class InstantGenerator {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");
    private static final int OPEN_HOUR = 8;
    private static final int CLOSE_HOUR = 22;
    private static final int MAX_ATTEMPTS = 10_000;
    public static final String NO_BUSINESS_HOURS = "Nessun orario 08–22 nel periodo del concorso";

    public record PrizeQuantity(String prizeId, int quantity, int sortOrder) {
    }

    public record GeneratedInstant(String prizeId, Instant at) {
    }

    private InstantGenerator() {
    }

    public static List<GeneratedInstant> generate(List<PrizeQuantity> prizes, Instant startAt, Instant endAt,
                                                  String distribution, long seed) {
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("endAt deve seguire startAt");
        }
        boolean businessHours = "BUSINESS_HOURS".equals(distribution);
        if (businessHours && !hasBusinessHours(startAt, endAt)) {
            // Q-294 DECISA: errore di validazione (422 CONTEST_INVALID nel servizio), non un 500.
            throw new IllegalArgumentException(NO_BUSINESS_HOURS);
        }
        SplittableRandom random = new SplittableRandom(seed);
        long start = startAt.toEpochMilli();
        long span = endAt.toEpochMilli() - start;
        List<GeneratedInstant> out = new ArrayList<>();
        List<PrizeQuantity> ordered = prizes.stream()
                .sorted(Comparator.comparingInt(PrizeQuantity::sortOrder).thenComparing(PrizeQuantity::prizeId))
                .toList();
        for (PrizeQuantity p : ordered) {
            for (int i = 0; i < p.quantity(); i++) {
                out.add(new GeneratedInstant(p.prizeId(), next(random, start, span, businessHours)));
            }
        }
        return out;
    }

    private static Instant next(SplittableRandom random, long start, long span, boolean businessHours) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Instant candidate = Instant.ofEpochMilli(start + random.nextLong(span));
            if (!businessHours || inBusinessHours(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(NO_BUSINESS_HOURS);
    }

    /** Vero se {@code [startAt, endAt)} contiene almeno un istante tra le 08 e le 22 di Roma. */
    public static boolean hasBusinessHours(Instant startAt, Instant endAt) {
        LocalDate last = LocalDate.ofInstant(endAt, ZONE);
        for (LocalDate d = LocalDate.ofInstant(startAt, ZONE); !d.isAfter(last); d = d.plusDays(1)) {
            Instant open = d.atTime(OPEN_HOUR, 0).atZone(ZONE).toInstant();
            Instant close = d.atTime(CLOSE_HOUR, 0).atZone(ZONE).toInstant();
            if (open.isBefore(endAt) && close.isAfter(startAt)) {
                return true;
            }
        }
        return false;
    }

    public static boolean inBusinessHours(Instant at) {
        int hour = ZonedDateTime.ofInstant(at, ZONE).getHour();
        return hour >= OPEN_HOUR && hour < CLOSE_HOUR;
    }
}
