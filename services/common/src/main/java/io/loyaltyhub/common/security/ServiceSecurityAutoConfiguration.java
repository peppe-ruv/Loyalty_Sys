package io.loyaltyhub.common.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

/**
 * Sicurezza delle API interne (RF-43). Finora i servizi si fidavano della rete: chiunque riuscisse a parlare con il
 * cluster poteva accreditare punti. Qui ogni servizio diventa un <b>resource server OAuth2/OIDC</b> che pretende un
 * token valido con lo scope del programma, e ogni chiamata fra servizi porta un token ottenuto con le proprie
 * credenziali ({@link ServiceTokens}).
 *
 * <p>È spenta per difetto ({@code loyalty.security.enabled=false}): l'ambiente locale e i test restano semplici, e
 * negli ambienti veri si accende da configurazione, senza toccare il codice. Restano sempre aperte le sonde di salute
 * e le metriche, che il cluster deve poter leggere prima ancora che il servizio sia in grado di validare un token.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(ServiceSecurityProperties.class)
public class ServiceSecurityAutoConfiguration {

    /**
     * Percorsi sempre aperti: sonde e metriche (le legge il cluster, che non ha un token del programma) e la
     * specifica OpenAPI, che è il contratto e non espone dati. I percorsi delle metriche sono anche letterali:
     * l'endpoint Prometheus non è sempre risolvibile per identificativo quando la sicurezza si configura presto.
     */
    static final String[] PUBLIC_PATHS = {
            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus", "/actuator/metrics/**",
            // Senza questo, un 404 viene inoltrato a /error e la catena lo trasforma in un 401 che non c'entra nulla.
            "/error",
            "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "loyalty.security.enabled", havingValue = "true")
    SecurityFilterChain apiSecurity(HttpSecurity http, ServiceSecurityProperties props) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("SCOPE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http
                // Nessuna sessione e nessun form: sono API fra servizi, il token è l'unica credenziale.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // Sonde e metriche: le legge il cluster, che non ha un token del programma.
                        .requestMatchers(EndpointRequest.to("health", "info", "prometheus")).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().hasAuthority("SCOPE_" + props.getRequiredScope()))
                .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }

    /**
     * Con la sicurezza spenta si dichiara comunque una catena che lascia passare tutto: senza, Spring Security
     * imporrebbe la sua (login form e password generata a ogni avvio) e l'ambiente locale smetterebbe di funzionare
     * appena qualcuno aggiunge la dipendenza.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "loyalty.security.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openApiSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
    }

    /**
     * Sorgente dei token di servizio. Il client verso l'IAM è costruito da un builder <b>pulito</b>, non da quello
     * iniettato: quest'ultimo porta l'intercettore che aggiunge il token, e chiedere un token con un client che vuole
     * già un token è una ricorsione.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "loyalty.security.client.enabled", havingValue = "true")
    ServiceTokens serviceTokens(ServiceSecurityProperties props) {
        return new ServiceTokens(RestClient.builder().build(), props.getTokenUri(), props.getClientId(), props.getClientSecret(), props.getRequiredScope());
    }

    /**
     * Ogni {@code RestClient} costruito dal builder iniettato porta il token di servizio: gli adattatori verso gli
     * altri servizi non devono ricordarsene uno per uno, ed è per questo che la convenzione vuole il builder iniettato
     * e non un client costruito a mano.
     */
    @Bean
    @ConditionalOnProperty(name = "loyalty.security.client.enabled", havingValue = "true")
    org.springframework.boot.web.client.RestClientCustomizer serviceTokenCustomizer(ServiceTokens tokens) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String token = tokens.token();
            if (token != null && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION))
                request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        });
    }
}
