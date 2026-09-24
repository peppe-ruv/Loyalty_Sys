package io.loyaltyhub.engagement.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Politica dei ritenti dei webhook (docs/servizi/engagement-service.md §5): dopo un tentativo fallito si ritenta a
 * 1, 5 e 15 minuti; fallito anche il quarto tentativo la consegna è {@code GAVE_UP}. Un 2xx chiude in {@code OK};
 * ogni altra risposta (anche 3xx: i redirect non si seguono) o errore di rete è un fallimento.
 * Il conteggio è sui tentativi eseguiti ({@code attempt}): un *Riprova* manuale è un tentativo in più e, se fallisce,
 * segue la stessa tabella (oltre il quarto → di nuovo {@code GAVE_UP}).
 */
// SPEC-GAP: Q-A2 — la scheda dice "ritenti a 1, 5, 15 min poi GAVE_UP" senza dire cosa fa il Riprova manuale: qui è un
// solo tentativo immediato che rientra nella stessa tabella (nessun nuovo ciclo di ritenti dopo GAVE_UP).
public final class WebhookRetry {

    public static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    /** Primo invio più i tre ritenti. */
    public static final int MAX_ATTEMPTS = DELAYS.size() + 1;

    public enum Status { PENDING, OK, FAILED, GAVE_UP }

    public record Next(Status status, Instant nextAttemptAt) {
    }

    private WebhookRetry() {
    }

    /**
     * Stato dopo il tentativo numero {@code attempt} (1 = primo invio) eseguito a {@code now}.
     *
     * @param success risposta 2xx
     */
    public static Next after(int attempt, boolean success, Instant now) {
        if (success) {
            return new Next(Status.OK, null);
        }
        if (attempt >= 1 && attempt <= DELAYS.size()) {
            return new Next(Status.FAILED, now.plus(DELAYS.get(attempt - 1)));
        }
        return new Next(Status.GAVE_UP, null);
    }

    public static boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    /** Il *Riprova* manuale vale solo per consegne fallite (in attesa di ritento) o abbandonate. */
    public static boolean isRetryable(String status) {
        return Status.FAILED.name().equals(status) || Status.GAVE_UP.name().equals(status);
    }
}
