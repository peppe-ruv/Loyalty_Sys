package io.loyaltyhub.campaign.engine;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/** L'azione da valutare (docs/03 §3.1): tipo breve, membro, istante di business, sorgente, payload. */
public record EvalAction(
        String actionId,
        String type,
        String memberId,
        String source,
        Instant time,
        JsonNode data
) {
}
