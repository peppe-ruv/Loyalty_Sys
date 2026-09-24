package io.loyaltyhub.member.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Etichette che un'azione esterna attribuisce al membro. docs/10 §3 definisce {@code SEG-DIGITAL} come "etichetta
 * {@code ebill} e {@code directdebit}" e SCN-DIGITAL vuole che Marco ci entri al ricalcolo dopo le due attivazioni:
 * qualcuno deve quindi mettere le etichette quando le attivazioni arrivano.
 * SPEC-GAP: Q-80 — nessuna fonte dice chi valorizza le etichette; member le aggiunge alla prima azione del tipo mappato
 * (mai le toglie) ed emette {@code member.updated} con lo snapshot completo.
 */
public final class ActionLabels {

    /** Tipo azione (forma breve) → etichetta. */
    public static final Map<String, String> BY_ACTION = Map.of(
            "ebill.activated", "ebill",
            "directdebit.activated", "directdebit");

    private ActionLabels() {
    }

    /** Etichette dopo l'azione, oppure {@code null} se l'azione non ne aggiunge di nuove. */
    public static List<String> after(List<String> current, String shortActionType) {
        String label = BY_ACTION.get(shortActionType);
        List<String> labels = current == null ? List.of() : current;
        if (label == null || labels.contains(label)) {
            return null;
        }
        List<String> out = new ArrayList<>(labels);
        out.add(label);
        return out;
    }
}
