package io.loyaltyhub.common.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Intestazione comune delle specifiche OpenAPI (RF-133): senza, ogni servizio si presentava come «OpenAPI definition
 * v0», che non dice a chi integra né quale servizio sta leggendo né quale contratto.
 *
 * <p>La versione dichiarata è quella dell'<b>API</b> ({@code v1}, il prefisso dei percorsi), non quella del rilascio:
 * il contratto cambia quando cambia il prefisso, non a ogni build. Così le specifiche pubblicate in
 * {@code docs/contracts/} non si sporcano a ogni cambio di versione del repository.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAPI.class)
public class OpenApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OpenAPI loyaltyOpenApi(@Value("${spring.application.name:loyalty-hub}") String service) {
        return new OpenAPI().info(new Info()
                .title("Loyalty Hub — " + service)
                .version("v1")
                .description("API del servizio " + service + ". Scritture con chiave di idempotenza, liste con "
                        + "paginazione keyset (after, size), errori con codice motivo UPPER_SNAKE.")
                .license(new License().name("Apache-2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
