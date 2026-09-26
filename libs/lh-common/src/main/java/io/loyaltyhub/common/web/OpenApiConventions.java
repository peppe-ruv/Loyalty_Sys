package io.loyaltyhub.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Convenzioni OpenAPI (docs/06 §2): ogni endpoint ha un {@code summary} e un solo tag, l'area.
 * <ul>
 *   <li>Tag = area del controller: nome della classe senza {@code Controller}, in kebab-case
 *       ({@code PortalWalletController} → {@code portal-wallet}).</li>
 *   <li>Summary = quello di {@code @Operation} se presente, altrimenti il nome del metodo reso leggibile
 *       ({@code listCampaigns} → «List campaigns»; un nome di una parola prende l'area: «Get — webhooks»).</li>
 * </ul>
 * Q-341 / ADR-026: springdoc attivo ovunque; nel profilo {@code free} la UI Swagger è spenta (docs/11 §6).
 */
@AutoConfiguration
@ConditionalOnClass(OperationCustomizer.class)
public class OpenApiConventions {

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    OpenAPI loyaltyHubOpenApi(@Value("${spring.application.name:loyalty-hub}") String application) {
        return new OpenAPI().info(new Info()
                .title("Loyalty Hub · " + application)
                .description("API REST del PoC Loyalty Hub (docs/06 §2). Identità simulata con l'header X-LH-Actor.")
                .version("v1"));
    }

    @Bean
    OperationCustomizer loyaltyHubOperationConventions() {
        return (operation, handler) -> {
            String area = area(handler.getBeanType().getSimpleName());
            operation.setTags(new ArrayList<>(List.of(area)));
            if (operation.getSummary() == null || operation.getSummary().isBlank()) {
                operation.setSummary(summary(handler.getMethod().getName(), area));
            }
            return operation;
        };
    }

    static String area(String controllerName) {
        String base = controllerName.endsWith("Controller")
                ? controllerName.substring(0, controllerName.length() - "Controller".length())
                : controllerName;
        return words(base).stream().map(w -> w.toLowerCase(Locale.ROOT))
                .reduce((a, b) -> a + "-" + b).orElse(base.toLowerCase(Locale.ROOT));
    }

    /** Summary dal nome del metodo; un nome di una sola parola ({@code get}, {@code update}) prende anche l'area. */
    static String summary(String methodName, String area) {
        String text = summary(methodName);
        return words(methodName).size() == 1 ? text + " — " + area.replace('-', ' ') : text;
    }

    static String summary(String methodName) {
        List<String> words = words(methodName);
        if (words.isEmpty()) {
            return methodName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            boolean acronym = w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT));
            if (i == 0) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(acronym ? w.substring(1) : w.substring(1).toLowerCase(Locale.ROOT));
            } else {
                sb.append(' ').append(acronym ? w : w.toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    /** Parole di un identificatore camelCase; le sigle restano unite ({@code getDLQEntry} → get, DLQ, Entry). */
    private static List<String> words(String identifier) {
        List<String> out = new ArrayList<>();
        for (String w : identifier.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }
}
