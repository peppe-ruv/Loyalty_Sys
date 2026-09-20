package io.loyaltyhub.insight.live;

import tools.jackson.databind.JsonNode;

/**
 * Sintesi leggibile di un evento per il rail (docs/servizi/insight-service.md §5): una frase breve dedotta dal
 * tipo e dal payload, es. {@code wallet.points.earned} → "+162 PTS". Tabella in codice, tollerante ai campi
 * mancanti (il rail non deve mai rompersi su un payload inatteso).
 */
public final class EventSummaries {

    private EventSummaries() {
    }

    public static String of(String shortType, JsonNode data) {
        if (shortType == null) {
            return "evento";
        }
        JsonNode d = data == null ? tools.jackson.databind.node.NullNode.getInstance() : data;
        return switch (shortType) {
            case "purchase.completed" -> "Acquisto" + amountEuro(d);
            case "points.grant" -> "Effetto punti" + points(d, "amount", d.path("currency").asString("PTS"));
            case "wallet.points.earned" -> "Punti accreditati" + points(d, "amount", d.path("currency").asString("PTS"));
            case "campaign.evaluated" -> "Campagna valutata" + evaluated(d);
            case "member.registered" -> "Nuovo membro";
            case "member.updated" -> "Membro aggiornato";
            case "member.status.changed" -> "Stato membro: " + d.path("status").asString("?");
            case "tier.upgraded", "tier.changed" -> "Livello → " + d.path("tier").asString("?");
            case "app.login.daily" -> "Accesso all'app";
            case "ebill.activated" -> "Bolletta digitale attivata";
            case "directdebit.activated" -> "Domiciliazione attivata";
            case "selfreading.submitted" -> "Autolettura inviata";
            case "survey.completed" -> "Sondaggio completato";
            case "entry" -> "Audit: " + d.path("action").asString("modifica");
            default -> shortType;
        };
    }

    private static String amountEuro(JsonNode d) {
        JsonNode a = d.path("amount");
        return a.isNumber() ? " · " + a.asString() + " €" : "";
    }

    private static String points(JsonNode d, String field, String currency) {
        JsonNode a = d.path(field);
        return a.isNumber() ? " · +" + a.asString() + " " + currency : "";
    }

    private static String evaluated(JsonNode d) {
        JsonNode matched = d.path("matchedCampaigns");
        if (matched.isNumber()) {
            return " · " + matched.asString() + " campagne";
        }
        return "";
    }
}
