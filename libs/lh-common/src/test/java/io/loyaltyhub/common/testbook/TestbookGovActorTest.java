package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.web.ActorContext;
import io.loyaltyhub.common.web.ActorHolder;
import io.loyaltyhub.common.web.EndpointAccessInterceptor;
import io.loyaltyhub.common.web.PublicEndpoint;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static io.loyaltyhub.common.testbook.TestbookGovSupport.decode;
import static io.loyaltyhub.common.testbook.TestbookGovSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §3 — identità simulata (docs/06 §3): lettura di {@code X-LH-Actor} ({@link ActorContext#parse}) e guardia
 * {@link RequiresRole} applicata da {@link EndpointAccessInterceptor} (docs/08 §2: ADMIN passa sempre, annotazione
 * vuota = regola «scrittura», ANALYST mai); deny by default (F2-SEC-09): senza {@link RequiresRole} né
 * {@link PublicEndpoint} con motivo l'endpoint è rifiutato a tutti, ADMIN compreso.
 */
class TestbookGovActorTest {

    @AfterEach
    void clear() {
        ActorHolder.clear();
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/actor.csv", numLinesToSkip = 1)
    void parse(String id, String description, String header, String role, String username) {
        // Righe ACT-007…014 — Q-298 DECISA: solo RUOLO:username canonico vale il ruolo, ogni altra forma vale ANALYST
        ActorContext actor = ActorContext.parse(decode(header));
        assertThat(actor.asActorString()).as("%s: %s", id, description).isEqualTo(role + ":" + username);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/guard.csv", numLinesToSkip = 1)
    void guard(String id, String description, String annotation, String role, boolean allowed) throws Exception {
        boolean classLevel = "CLASS_LEGAL".equals(annotation);
        Object bean = classLevel ? new LegalOnly() : new Endpoints();
        String method = classLevel ? "write" : annotation.toLowerCase();
        HandlerMethod handler = new HandlerMethod(bean, bean.getClass().getMethod(method));
        ActorHolder.set(new ActorContext(Role.valueOf(role), "testbook"));
        String got = outcome(() -> new EndpointAccessInterceptor()
                .preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler));
        // Deny by default: nessuna dichiarazione o motivo vuoto ⇒ ENDPOINT_NOT_DECLARED, altrimenti FORBIDDEN_ROLE.
        String denied = "NONE".equals(annotation) || "OPEN_BLANK".equals(annotation)
                ? "403:ENDPOINT_NOT_DECLARED" : "403:FORBIDDEN_ROLE";
        assertThat(got).as("%s: %s", id, description).isEqualTo(allowed ? "true" : denied);
    }

    /** Endpoint fittizi, uno per variante di annotazione. */
    public static class Endpoints {
        public void none() {
        }

        @RequiresRole
        public void empty() {
        }

        @RequiresRole({Role.MARKETING})
        public void marketing() {
        }

        @RequiresRole({Role.ADMIN, Role.CARE})
        public void admin_care() {
        }

        @RequiresRole({Role.ADMIN})
        public void admin() {
        }

        @RequiresRole({Role.SOURCE})
        public void source() {
        }

        @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST})
        public void read_all() {
        }

        /** Lettura di programma aperta ai membri (M8.10f, Q-410): in demo {@code members = true} non cambia l'esito. */
        @RequiresRole(value = {Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE, Role.ANALYST}, members = true)
        public void members_read() {
        }

        @PublicEndpoint(reason = "sonda del testbook")
        public void open() {
        }

        @PublicEndpoint(reason = " ")
        public void open_blank() {
        }
    }

    /** Annotazione a livello di classe. */
    @RequiresRole({Role.LEGAL})
    public static class LegalOnly {
        public void write() {
        }
    }
}
