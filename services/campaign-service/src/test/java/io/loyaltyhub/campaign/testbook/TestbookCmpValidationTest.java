package io.loyaltyhub.campaign.testbook;

import io.loyaltyhub.campaign.api.CreateCampaignRequest;
import io.loyaltyhub.campaign.application.CampaignAdminService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.List;

import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.json;
import static io.loyaltyhub.campaign.testbook.CmpEngineFixture.list;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TB-CMP §8 (docs/testbook/TB-CMP-campagne.md): validazioni di salvataggio (docs/servizi/campaign-service.md §5,
 * {@code POST /v1/campaigns/validate} §3). La validazione è logica pura del servizio: nessun repository è usato.
 */
class TestbookCmpValidationTest {

    /** Solo {@link CampaignAdminService#validate} è invocato: non tocca dipendenze. */
    private final CampaignAdminService service = new CampaignAdminService(null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);

    /**
     * TB-CMP-VAL: almeno un trigger, almeno un effetto, MULTIPLIER.factor in [1.1, 5] ai limiti, codici di concorso,
     * premio, badge e template non vuoti, endAt &gt; startAt.
     * TESTBOOK: ambiguo, vedi TB-CMP-VAL-036 (mode sconosciuto), VAL-037 (comparatore sconosciuto): la scheda §5 non li
     * elenca; si asserisce il comportamento attuale.
     * Q-243 DECISA (VAL-038: startAt non ISO-8601 rifiutato anche senza endAt, 422).
     */
    @ParameterizedTest(name = "[{0}] {1}", quoteTextArguments = false)
    @CsvFileSource(resources = "/testbook/cmp/validation.csv", numLinesToSkip = 1, delimiter = '|', quoteCharacter = '`')
    void validate(String id, String description, String triggers, String effects, String schedule, String conditions,
                  boolean valid, int errorCount) {
        List<String> t = "NULL".equals(triggers) ? null : "EMPTY".equals(triggers) ? List.of() : list(triggers);
        CreateCampaignRequest r = new CreateCampaignRequest("CMP-TB-VAL", "Validazione", null, null, null, t, null,
                json(conditions), json(effects), null, json(schedule), 100, null, false, List.of(), null);

        List<String> errors = service.validate(r);

        assertThat(errors.isEmpty()).as(id + " valida? errori=" + errors).isEqualTo(valid);
        assertThat(errors).as(id + " numero di errori").hasSize(errorCount);
    }
}
