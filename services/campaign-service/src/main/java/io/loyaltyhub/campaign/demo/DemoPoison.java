package io.loyaltyhub.campaign.demo;

import io.loyaltyhub.common.event.LhEvent;
import io.loyaltyhub.common.kafka.NonRetryableEventException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Flag demo {@code data._poison=true} di {@code SCN-POISON} (docs/10 §8): <strong>solo</strong> col profilo
 * {@code demo} il motore rifiuta l'azione con un'eccezione non ritentabile, così il messaggio finisce in DLQ
 * (BO-27) e il tracciato è {@code FAILED}. Fuori dal profilo demo il flag è ignorato.
 */
// SPEC-GAP: Q-108 — docs/10 §8 dice sia "il consumer fallisce 3 volte" sia "eccezione non ritentabile": vale la
// seconda (più specifica), quindi un solo tentativo e poi DLQ con lh-attempts=1.
@Component
public class DemoPoison {

    /** Codice errore del record DLQ (header {@code lh-error-code}). */
    public static final String ERROR_CODE = "DEMO_POISON";

    private final boolean demo;

    public DemoPoison(Environment env) {
        this.demo = env.matchesProfiles("demo");
    }

    /** Solleva {@link NonRetryableEventException} se l'azione porta il flag demo {@code _poison}. */
    public void check(LhEvent<JsonNode> event) {
        JsonNode data = event.data();
        if (demo && data != null && data.path("_poison").asBoolean(false)) {
            throw new NonRetryableEventException(ERROR_CODE,
                    "Azione avvelenata di prova (data._poison=true, SCN-POISON): il motore non la elabora");
        }
    }
}
