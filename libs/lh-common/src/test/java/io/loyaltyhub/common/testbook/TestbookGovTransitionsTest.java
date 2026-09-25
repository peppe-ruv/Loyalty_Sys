package io.loyaltyhub.common.testbook;

import io.loyaltyhub.common.approval.ApprovalAction;
import io.loyaltyhub.common.approval.ApprovalRule;
import io.loyaltyhub.common.approval.ApprovalStatus;
import io.loyaltyhub.common.approval.GovernedTransitions;
import io.loyaltyhub.common.web.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import static io.loyaltyhub.common.testbook.TestbookGovSupport.decode;
import static io.loyaltyhub.common.testbook.TestbookGovSupport.outcome;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §4–§6 — ciclo di vita degli oggetti governati (docs/03 §3.6) con ruoli (docs/06 §3, docs/08 §2) e policy
 * (docs/06 §7), sulla logica comune a campagne, premi e concorsi ({@link GovernedTransitions}).
 * <ul>
 *   <li>{@code sm-required.csv} (SMR): stato × azione completa, policy ON e approvazione richiesta (LEGAL);</li>
 *   <li>{@code sm-none.csv} (SMN): policy ON senza approvazione, solo le celle in cui la regola entra nel ramo;</li>
 *   <li>{@code sm-off.csv} (SMF): stato × azione completa con {@code loyaltyhub.approval.enabled=false};</li>
 *   <li>{@code roles.csv} (ROL): ruolo × azione dallo stato di partenza valido, più le precedenze;</li>
 *   <li>{@code comments.csv} (CMT): commento del rifiuto (assente, vuoto, spazi…).</li>
 * </ul>
 * La regola della policy nei CSV: {@code REQ} = {@code ApprovalRule.legal} (concorsi, premi, campagne oltre soglia),
 * {@code NONE} = {@code ApprovalRule.NONE} (campagna sotto soglia, o qualunque oggetto con la policy spenta, come fanno i
 * servizi: {@code ApprovalPolicy.for*} restituisce {@code NONE} a policy spenta).
 */
class TestbookGovTransitionsTest {

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = {"/testbook/gov/sm-required.csv", "/testbook/gov/sm-none.csv", "/testbook/gov/sm-off.csv",
            "/testbook/gov/roles.csv", "/testbook/gov/comments.csv"}, numLinesToSkip = 1)
    void stateMachine(String id, String description, String policy, String rule, String from, String action,
                      String role, String comment, String expected) {
        // Righe SMF-001, ROL-051/052, ROL-056…060 — TESTBOOK: ambiguo, vedi TB-GOV §13.
        // Riga CMT-006 — Q-300 DECISA: un commento di soli spazi Unicode vale vuoto (422).
        ApprovalRule r = "REQ".equals(rule) ? ApprovalRule.legal("testbook") : ApprovalRule.NONE;
        boolean enabled = "ON".equals(policy);
        String got = outcome(() -> GovernedTransitions.next(ApprovalStatus.valueOf(from), ApprovalAction.valueOf(action),
                r, enabled, Role.valueOf(role), decode(comment)));
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/parse.csv", numLinesToSkip = 1)
    void parse(String id, String description, String raw, String expected) {
        // Righe PRS-009…016 — TESTBOOK: ambiguo, vedi TB-GOV §13 (formato e codice dell'azione sconosciuta)
        String got = outcome(() -> GovernedTransitions.parse(decode(raw)));
        assertThat(got).as("%s: %s", id, description).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/override.csv", numLinesToSkip = 1)
    void override(String id, String description, String action, String rule, String role, boolean override) {
        // Righe OVR-006, OVR-016 (ADMIN senza approvatore di policy) — Q-300 DECISA: non marcato, non scavalca nessuno
        ApprovalRule r = "REQ".equals(rule) ? ApprovalRule.legal("testbook") : ApprovalRule.NONE;
        assertThat(GovernedTransitions.isOverride(ApprovalAction.valueOf(action), r, Role.valueOf(role)))
                .as("%s: %s", id, description).isEqualTo(override);
    }
}
