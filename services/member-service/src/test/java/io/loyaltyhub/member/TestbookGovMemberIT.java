package io.loyaltyhub.member;

import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.member.api.AnonymizeRequest;
import io.loyaltyhub.member.api.CreateMemberRequest;
import io.loyaltyhub.member.api.MemberView;
import io.loyaltyhub.member.api.StatusChangeRequest;
import io.loyaltyhub.member.domain.MemberStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"lh.facts.v1", "lh.audit.v1"})
@ActiveProfiles("demo")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestbookGovMemberIT {

    private static final EmbeddedPostgres PG = startPg();

    @Value("${local.server.port}")
    private int port;

    private static EmbeddedPostgres startPg() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = PG.getJdbcUrl("postgres", "postgres");
        registry.add("spring.datasource.url", () -> base + "&currentSchema=member");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @AfterAll
    void tearDown() throws Exception {
        PG.close();
    }

    private RestClient client(Role role) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port);
        if (role != null) {
            builder.defaultHeader("X-LH-Actor", role.name() + ":marta");
        }
        return builder.build();
    }

    private MemberView createMember(String idSuffix) {
        CreateMemberRequest req = new CreateMemberRequest(
                "Test", "User", "testuser", "test" + idSuffix + "@example.com", "+393331234567",
                "Rome", "M", "POS", null, null
        );
        return client(Role.ADMIN).post()
                .uri("/v1/members")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(MemberView.class);
    }

    @Test
    @DisplayName("[TB-GOV-032] changeStatus(INACTIVE) + ruolo ADMIN -> status=INACTIVE")
    void changeStatusRolesAdmin() {
        MemberView m = createMember("admin1");
        MemberView updated = client(Role.ADMIN).post()
                .uri("/v1/members/{id}/status", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StatusChangeRequest(MemberStatus.INACTIVE.name(), "Test reason"))
                .retrieve()
                .body(MemberView.class);
        assertThat(updated.status()).isEqualTo(MemberStatus.INACTIVE.name());
    }

    @Test
    @DisplayName("[TB-GOV-033] changeStatus(BLOCKED) + ruolo CARE -> status=BLOCKED")
    void changeStatusRolesCare() {
        MemberView m = createMember("care1");
        MemberView updated = client(Role.CARE).post()
                .uri("/v1/members/{id}/status", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StatusChangeRequest(MemberStatus.BLOCKED.name(), "Care reason"))
                .retrieve()
                .body(MemberView.class);
        assertThat(updated.status()).isEqualTo(MemberStatus.BLOCKED.name());
    }

    @Test
    @DisplayName("[TB-GOV-034] changeStatus(CLOSED) + ruolo MARKETING -> 403 ForbiddenRole")
    void changeStatusForbiddenRolesMarketing() {
        MemberView m = createMember("mkt1");
        assertThatThrownBy(() -> client(Role.MARKETING).post()
                .uri("/v1/members/{id}/status", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StatusChangeRequest(MemberStatus.CLOSED.name(), "Marketing reason"))
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @DisplayName("[TB-GOV-035] changeStatus(ACTIVE) + no role -> 401/403 (no header)")
    void changeStatusNoRole() {
        MemberView m = createMember("norole");
        assertThatThrownBy(() -> client(null).post()
                .uri("/v1/members/{id}/status", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new StatusChangeRequest(MemberStatus.ACTIVE.name(), "No role reason"))
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isIn(401, 403));
    }

    @Test
    @DisplayName("[TB-GOV-036] anonymize + ruolo ADMIN, confirm=ID -> status=ANONYMIZED, dati azzerati")
    void anonymizeAdminSuccess() {
        MemberView m = createMember("anon1");
        MemberView anonymized = client(Role.ADMIN).post()
                .uri("/v1/members/{id}/anonymize", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AnonymizeRequest(m.id()))
                .retrieve()
                .body(MemberView.class);

        assertThat(anonymized.status()).isEqualTo(MemberStatus.ANONYMIZED.name());
        assertThat(anonymized.firstName()).isNull();
        assertThat(anonymized.lastName()).isNull();
        assertThat(anonymized.email()).isNull();
        assertThat(anonymized.phone()).isNull();
        assertThat(anonymized.nickname()).isEqualTo("Membro anonimo");
        // Verifica che l'ID sia preservato
        assertThat(anonymized.id()).isEqualTo(m.id());
    }

    @Test
    @DisplayName("[TB-GOV-037] anonymize + ruolo CARE -> 403 ForbiddenRole")
    void anonymizeForbiddenRole() {
        MemberView m = createMember("anon2");
        assertThatThrownBy(() -> client(Role.CARE).post()
                .uri("/v1/members/{id}/anonymize", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AnonymizeRequest(m.id()))
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    @DisplayName("[TB-GOV-038] anonymize + ruolo ADMIN, confirm errato -> 422")
    void anonymizeWrongConfirm() {
        MemberView m = createMember("anon3");
        assertThatThrownBy(() -> client(Role.ADMIN).post()
                .uri("/v1/members/{id}/anonymize", m.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AnonymizeRequest("wrong-id"))
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(e -> assertThat(((HttpClientErrorException) e).getStatusCode().value()).isEqualTo(422));
    }
}
