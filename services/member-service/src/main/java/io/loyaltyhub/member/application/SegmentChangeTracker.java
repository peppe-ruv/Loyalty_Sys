package io.loyaltyhub.member.application;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Ci sono state variazioni?" per il ricalcolo periodico (docs/servizi/member-service.md §5: ogni 15 minuti, solo se
 * {@code member_stats}/{@code member_projection} sono cambiate). Segnato dai consumer e dalle modifiche anagrafiche;
 * parte "sporco" così il primo giro dopo l'avvio ricalcola comunque. Un falso positivo costa solo un ricalcolo in più.
 */
@Component
public class SegmentChangeTracker {

    private final AtomicBoolean dirty = new AtomicBoolean(true);

    public void markChanged() {
        dirty.set(true);
    }

    /** Vero se c'erano variazioni dall'ultima chiamata (e azzera il segnale). */
    public boolean consume() {
        return dirty.getAndSet(false);
    }
}
