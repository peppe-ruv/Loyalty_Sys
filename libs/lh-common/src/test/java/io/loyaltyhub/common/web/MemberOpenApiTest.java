package io.loyaltyhub.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il membro del token non è un parametro dell'API (Q-410, ADR-048, regola 12): springdoc non descrive né
 * {@link MemberPrincipal} né {@link MemberSubject} (le loro componenti {@code memberId} e {@code origin} non diventano
 * parametri di query), così l'OpenAPI dei servizi non cambia quando un handler passa a {@code @MemberEndpoint}.
 */
class MemberOpenApiTest {

    @RestController
    @RequestMapping("/v1/portal")
    static class Probe {
        @GetMapping("/wallet")
        @MemberEndpoint
        public Map<String, Object> wallet(MemberPrincipal principal) {
            return Map.of("member", String.valueOf(principal.idOrNull()));
        }

        @PostMapping("/members")
        @MemberEndpoint(MemberEndpoint.Mode.REGISTRATION)
        public Map<String, Object> register(@RequestBody Map<String, String> body, MemberSubject subject) {
            return Map.of("demo", subject.isDemo());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfig {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @Test
    @DisplayName("MemberPrincipal e MemberSubject non compaiono tra i parametri dell'OpenAPI generata")
    void memberTypesAreNotDocumented() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
                        HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
                        SpringDocConfiguration.class, SpringDocConfigProperties.class, SpringDocWebMvcConfiguration.class,
                        OpenApiConventions.class))
                .withUserConfiguration(ProbeConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
                    String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                            .andReturn().getResponse().getContentAsString();
                    JsonNode paths = JsonMapper.builder().build().readTree(body).path("paths");
                    assertThat(paths.has("/v1/portal/wallet")).as("l'operazione è documentata: %s", body).isTrue();
                    assertThat(paths.has("/v1/portal/members")).isTrue();
                    JsonNode wallet = paths.path("/v1/portal/wallet").path("get");
                    assertThat(wallet.has("parameters")).as("nessun parametro: %s", wallet).isFalse();
                    JsonNode register = paths.path("/v1/portal/members").path("post");
                    assertThat(register.has("parameters")).as("nessun parametro: %s", register).isFalse();
                    assertThat(body).doesNotContain("MemberPrincipal").doesNotContain("MemberSubject")
                            .doesNotContain("subjectRef").doesNotContain("\"origin\"").doesNotContain("X-LH-Member");
                });
    }
}
