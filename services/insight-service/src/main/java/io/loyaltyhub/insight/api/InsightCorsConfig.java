package io.loyaltyhub.insight.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS per il solo stream SSE (docs/servizi/insight-service.md §3, ADR-020): le REST passano dal proxy Next
 * (nessun CORS), ma l'{@code EventSource} del browser chiama insight direttamente. Origini da
 * {@code LH_CORS_ALLOWED_ORIGINS} (lista separata da virgole), default {@code *} per la demo.
 */
@Configuration
public class InsightCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public InsightCorsConfig(@Value("${LH_CORS_ALLOWED_ORIGINS:*}") String origins) {
        this.allowedOrigins = origins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/v1/stream/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET")
                .allowCredentials(false);
    }
}
