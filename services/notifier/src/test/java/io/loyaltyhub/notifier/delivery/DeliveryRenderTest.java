package io.loyaltyhub.notifier.delivery;

import io.loyaltyhub.notifier.templates.MessageTemplate;
import io.loyaltyhub.notifier.templates.TemplateSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Il testo consegnato deve appartenere al canale su cui si consegna (RF-77, RF-132):
 * prima di questa correzione la ricerca scorreva tutti i canali in ordine di enum e il corpo
 * di una email finiva in un SMS.
 */
class DeliveryRenderTest {

    /** Sorgente con i soli modelli passati; ignora quelli di altri id o lingue. */
    private static TemplateSource sourceOf(MessageTemplate... templates) {
        List<MessageTemplate> all = List.of(templates);
        return (eventType, channel, locale) -> all.stream()
                .filter(t -> t.eventType().equals(eventType) && t.channel() == channel && t.locale().equals(locale))
                .findFirst();
    }

    private static MessageTemplate template(MessageTemplate.Channel channel, String subject, String body) {
        return new MessageTemplate("offerta", channel, "it", subject, body, true);
    }

    @Test
    void usaIlModelloDelCanaleSuCuiConsegna() {
        var templates = sourceOf(
                template(MessageTemplate.Channel.EMAIL, "Oggetto email", "<p>Corpo email</p>"),
                template(MessageTemplate.Channel.SMS, "", "Corpo SMS"));

        var sms = DeliveryService.render(templates, "offerta", "sms", "SHOW_OFFER", "off-1", Map.of(), Map.of());

        assertThat(sms.channel()).isEqualTo(MessageTemplate.Channel.SMS);
        assertThat(sms.body()).isEqualTo("Corpo SMS");
    }

    @Test
    void senzaModelloPerQuelCanaleUsaIlTestoGenericoNonQuelloDiUnAltroCanale() {
        var templates = sourceOf(template(MessageTemplate.Channel.EMAIL, "Oggetto email", "<p>Corpo email</p>"));

        var push = DeliveryService.render(templates, "offerta", "push", "SHOW_OFFER", "off-1",
                Map.of("title", "Titolo push", "body", "Corpo push"), Map.of());

        assertThat(push.channel()).isEqualTo(MessageTemplate.Channel.PUSH);
        assertThat(push.subject()).isEqualTo("Titolo push");
        assertThat(push.body()).isEqualTo("Corpo push");
    }

    @Test
    void risolveISegnapostoDelModello() {
        var templates = sourceOf(template(MessageTemplate.Channel.IN_APP, "Ciao", "Hai {{units}} punti"));

        var inApp = DeliveryService.render(templates, "offerta", "app", "SEND_MESSAGE", null, Map.of(), Map.of("units", 984));

        assertThat(inApp.body()).isEqualTo("Hai 984 punti");
    }

    @Test
    void iCanaliSenzaModelloProprioRicevonoIlTestoInApp() {
        assertThat(DeliveryService.templateChannel("app")).isEqualTo(MessageTemplate.Channel.IN_APP);
        assertThat(DeliveryService.templateChannel("web")).isEqualTo(MessageTemplate.Channel.IN_APP);
        assertThat(DeliveryService.templateChannel("webhook")).isEqualTo(MessageTemplate.Channel.IN_APP);
        assertThat(DeliveryService.templateChannel("operator")).isEqualTo(MessageTemplate.Channel.IN_APP);
        assertThat(DeliveryService.templateChannel("email")).isEqualTo(MessageTemplate.Channel.EMAIL);
        assertThat(DeliveryService.templateChannel(null)).isEqualTo(MessageTemplate.Channel.IN_APP);
    }
}
