package io.loyaltyhub.ingestion.domain;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Vincoli tra campi di {@code data} che il JSON Schema del tipo non può esprimere (passo 4 della pipeline, dopo lo
 * schema; docs/servizi/ingestion-service.md §5): un'azione impossibile non deve poter dare punti (Q-265). L'esito è
 * {@code REJECTED/INVALID_DATA} con il campo indicato, come per un errore dello schema.
 */
public final class CrossFieldRules {

    private CrossFieldRules() {
    }

    /** Errori dei vincoli tra campi per il tipo breve {@code shortType}; vuoto se non ce ne sono. */
    public static List<String> errors(String shortType, JsonNode data) {
        if (data == null || !data.isObject()) {
            return List.of();
        }
        if ("quiz.completed".equals(shortType)) {
            JsonNode correct = data.get("correctAnswers");
            JsonNode total = data.get("totalQuestions");
            if (correct != null && total != null && correct.isNumber() && total.isNumber()
                    && correct.decimalValue().compareTo(total.decimalValue()) > 0) {
                return List.of("/correctAnswers: " + correct + " supera totalQuestions (" + total + ")");
            }
        }
        return List.of();
    }
}
