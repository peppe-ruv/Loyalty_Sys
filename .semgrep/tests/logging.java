// Fixture di semgrep --test per .semgrep/rules/logging.yml. `ruleid:` = deve segnalare, `ok:` = non deve segnalare.
package io.loyaltyhub.fixture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingFixture {

    private static final Logger log = LoggerFactory.getLogger(LoggingFixture.class);
    private static final Logger LOGGER = LoggerFactory.getLogger("fixture");

    // ---- ammessi -------------------------------------------------------------------------------------------------

    void safe(String memberId, int count, Exception e) {
        // ok: lh-log-segreto
        log.info("Membro {} ha {} azioni", memberId, count);
        // ok: lh-log-segreto
        log.warn("Token scaduto: rifiutata la richiesta");
        // ok: lh-log-segreto
        log.error("Errore nel job", e);
        // ok: lh-log-segreto
        LOGGER.debug("Invalidazione della sessione {}", memberId);
    }

    void presenceOnly(String token, String password) {
        // ok: lh-log-segreto
        log.debug("Token presente: {}", token != null);
        // ok: lh-log-segreto
        log.debug("Password vuota: {}", password.isEmpty());
        // ok: lh-log-segreto
        log.debug("Lunghezza del token: {}", token.length());
    }

    void receiverNamedLikeSecret() {
        // ok: lh-log-segreto
        LoggerFactory.getLogger(TokenService.class).info("Avvio");
    }

    void notALogger(Recorder tokenRecorder, String token) {
        // ok: lh-log-segreto
        tokenRecorder.record(token);
    }

    // ---- vietati -------------------------------------------------------------------------------------------------

    void leaks(String token, String secret, String password, String authorization, String apiKey) {
        // ruleid: lh-log-segreto
        log.info("Token ricevuto: {}", token);
        // ruleid: lh-log-segreto
        log.debug("Segreto {}", secret);
        // ruleid: lh-log-segreto
        log.warn("Password errata: " + password);
        // ruleid: lh-log-segreto
        LOGGER.error("Header {}", authorization);
        // ruleid: lh-log-segreto
        log.trace("Chiave {}", apiKey);
    }

    void leaksFromRequest(jakarta.servlet.http.HttpServletRequest request) {
        // ruleid: lh-log-segreto
        log.info("Authorization: {}", request.getHeader("Authorization"));
    }

    void leaksFromGetter(Session session) {
        // ruleid: lh-log-segreto
        log.info("Sessione {}", session.accessToken());
        // ruleid: lh-log-segreto
        LoggerFactory.getLogger(LoggingFixture.class).info("Sessione {}", session.getRefreshToken());
    }

    interface Recorder { void record(String v); }
    interface Session { String accessToken(); String getRefreshToken(); }
    static final class TokenService { }
}
