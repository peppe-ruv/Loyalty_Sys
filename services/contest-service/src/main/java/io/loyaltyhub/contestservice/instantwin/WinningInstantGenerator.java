package io.loyaltyhub.contestservice.instantwin;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Genera gli istanti vincenti di un concorso (RF-30, ADR-007 opzione A).
 * <p>
 * Casualità: {@link SecureRandom} (DRBG del JDK, seed dal sistema operativo). Ogni istante è estratto
 * uniformemente nel periodo del concorso; con fasce orarie pesate l'estrazione avviene in due passi:
 * scelta della fascia proporzionale al peso, poi istante uniforme nella fascia. Nessun istante coincide
 * con un altro (unicità garantita a livello di millisecondo).
 * <p>
 * Questa classe è deterministica dato il generatore: i test la esercitano con un generatore a seme fisso.
 * È l'oggetto principale della perizia tecnica (RC-02): non dipende da servizi esterni né da orologi.
 */
public final class WinningInstantGenerator {
    public record Slot(Instant from, Instant to, double weight) {
        public Slot {
            if (!to.isAfter(from)) throw new IllegalArgumentException("slot must have positive duration");
            if (weight < 0) throw new IllegalArgumentException("weight must be >= 0");
        }
    }

    private final SecureRandom random;

    public WinningInstantGenerator() { this(new SecureRandom()); }
    public WinningInstantGenerator(SecureRandom random) { this.random = random; }

    /** Istanti uniformi nel periodo [start, end). */
    public List<Instant> generate(Instant start, Instant end, int prizes) {
        return generate(List.of(new Slot(start, end, 1.0)), prizes);
    }

    /** Istanti pesati per fascia (RF-30): fasce disgiunte, pesi proporzionali al traffico atteso. */
    public List<Instant> generate(List<Slot> slots, int prizes) {
        if (prizes <= 0) throw new IllegalArgumentException("prizes must be > 0");
        double total = slots.stream().mapToDouble(Slot::weight).sum();
        if (total <= 0) throw new IllegalArgumentException("total weight must be > 0");
        long capacity = slots.stream().mapToLong(s -> Duration.between(s.from(), s.to()).toMillis()).sum();
        if (capacity < prizes) throw new IllegalArgumentException("period too short for " + prizes + " distinct instants");

        List<Instant> out = new ArrayList<>(prizes);
        java.util.Set<Long> seen = new java.util.HashSet<>(prizes * 2);
        while (out.size() < prizes) {
            Slot slot = pick(slots, total);
            long span = Duration.between(slot.from(), slot.to()).toMillis();
            long offset = nextLong(span);
            long millis = slot.from().toEpochMilli() + offset;
            if (seen.add(millis)) out.add(Instant.ofEpochMilli(millis));
        }
        Collections.sort(out);
        return out;
    }

    private Slot pick(List<Slot> slots, double total) {
        double r = random.nextDouble() * total;
        double acc = 0;
        for (Slot s : slots) {
            acc += s.weight();
            if (r < acc) return s;
        }
        return slots.get(slots.size() - 1);
    }

    /** Uniforme in [0, bound) senza bias di modulo. */
    private long nextLong(long bound) {
        long m = bound - 1;
        long r = random.nextLong();
        if ((bound & m) == 0L) return r & m;
        long u = r >>> 1;
        while (u + m - (r = u % bound) < 0L) u = random.nextLong() >>> 1;
        return r;
    }
}
