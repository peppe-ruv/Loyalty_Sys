package io.loyaltyhub.member.testbook;

import io.loyaltyhub.member.domain.SegmentCriteria;
import io.loyaltyhub.member.domain.SegmentFacts;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-GOV §11.1–§11.2 — criteri dei segmenti dinamici (docs/03 §3.3, §10; Q-87, Q-90, Q-91), logica pura di
 * {@link SegmentCriteria}: comparatori × tipo dell'attributo, campo assente, liste, campi dello spazio esteso con i
 * limiti di tempo in Europe/Rome, gruppi; validazione della forma.
 * <p>Membro di riferimento (sovrascrivibile riga per riga con {@code variant}: {@code chiave=valore;…}): GOLD, ACTIVE,
 * etichette {@code ebill, directdebit}, città Torino, nato il 24/09/2000, iscritto 30 giorni prima, saldo 1 500 PTS,
 * 5 000 guadagnati, ultima attività 46 giorni prima, {@code purchase.completed} 2 negli ultimi 30 giorni e 7 in tutto,
 * acquisti a 90 giorni 107,4; attributi {@code tStr="APP"}, {@code tNum=3}, {@code tBool=true},
 * {@code tDate="2026-02-28"}. Data di riferimento: 24/09/2026 10:00 UTC (12:00 a Roma).
 */
class TestbookGovSegmentCriteriaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant AS_OF = Instant.parse("2026-09-24T10:00:00Z");

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/criteria.csv", numLinesToSkip = 1)
    void matches(String id, String description, String criteria, String variant, boolean expected) {
        // Righe AMBIGUO (Q-90, liste vuote, 29 febbraio, giorni a Roma) — TESTBOOK: ambiguo, vedi TB-GOV §13. Q-215
        // DECISA (cast tipizzato di lh-common): date confrontate come date, mai come testo (CRT-045…056); negazioni e
        // startsWith su tipi incompatibili falsi (CRT-024, 028, 038, 042, 057…060).
        Profile p = Profile.of(variant);
        JsonNode c = MAPPER.readTree(criteria.replace('\'', '"'));
        assertThat(SegmentCriteria.matches(c, p.facts(), p.asOf)).as("%s: %s", id, description).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/gov/criteria-validation.csv", numLinesToSkip = 1)
    void validate(String id, String description, String criteria, String expected) {
        // Righe CRV-006, CRV-011, CRV-015 — TESTBOOK: ambiguo, vedi TB-GOV §13; CRV-025: Q-215 DECISA (data ISO ammessa)
        List<SegmentCriteria.Issue> issues = SegmentCriteria.validate(MAPPER.readTree(criteria.replace('\'', '"')));
        if ("OK".equals(expected)) {
            assertThat(issues).as("%s: %s", id, description).isEmpty();
        } else {
            assertThat(issues).as("%s: %s", id, description).extracting(SegmentCriteria.Issue::field).contains(expected);
        }
    }

    /** Membro di riferimento con le varianti della riga. */
    private static final class Profile {
        Instant asOf = AS_OF;
        String status = "ACTIVE";
        List<String> labels = List.of("ebill", "directdebit");
        LocalDate birthDate = LocalDate.of(2000, 9, 24);
        Instant registeredAt = AS_OF.minus(Duration.ofDays(30));
        Instant lastActivityAt = AS_OF.minus(Duration.ofDays(46));

        static Profile of(String variant) {
            Profile p = new Profile();
            if (variant == null || variant.isBlank()) {
                return p;
            }
            for (String kv : variant.split(";")) {
                String[] parts = kv.split("=", 2);
                String v = parts.length > 1 ? parts[1].trim() : "";
                switch (parts[0].trim()) {
                    case "asOf" -> p.asOf = Instant.parse(v);
                    case "status" -> p.status = v;
                    case "labels" -> p.labels = v.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(v.split("\\|")));
                    case "birthDate" -> p.birthDate = v.isEmpty() ? null : LocalDate.parse(v);
                    case "registeredAt" -> p.registeredAt = Instant.parse(v);
                    case "lastActivityAt" -> p.lastActivityAt = v.isEmpty() ? null : Instant.parse(v);
                    default -> throw new IllegalArgumentException("variante sconosciuta: " + kv);
                }
            }
            return p;
        }

        SegmentFacts facts() {
            JsonNode attributes = MAPPER.readTree(
                    "{\"tStr\":\"APP\",\"tNum\":3,\"tBool\":true,\"tDate\":\"2026-02-28\",\"story\":\"interna\"}");
            return new SegmentFacts("MBR-TB-0001", "Prova Testbook", status, "GOLD", labels, attributes, registeredAt,
                    birthDate, "Torino", 1500, 5000, lastActivityAt,
                    Map.of("purchase.completed", new SegmentFacts.ActionWindow(2, 7)), 107.4);
        }
    }
}
