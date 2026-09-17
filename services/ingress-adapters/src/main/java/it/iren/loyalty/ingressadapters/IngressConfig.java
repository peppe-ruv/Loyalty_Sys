package it.iren.loyalty.ingressadapters;

import it.iren.loyalty.common.event.EventTypes;
import it.iren.loyalty.ingressadapters.catalog.Product;
import it.iren.loyalty.ingressadapters.catalog.ProductCatalog;
import it.iren.loyalty.ingressadapters.schema.EventSchema;
import it.iren.loyalty.ingressadapters.schema.SchemaRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/** Adattatori di default: schemi di esempio (in produzione dal CMS, collezione event-schemas) e catalogo su Postgres. */
@Configuration
public class IngressConfig {
    @Bean @ConditionalOnMissingBean
    SchemaRegistry seedSchemas() {
        List<EventSchema> seed = List.of(
                new EventSchema("BILL_PAID_ON_TIME", "Bolletta pagata nei termini", 1, true, true, List.of(
                        new EventSchema.Attribute(EventTypes.ATTR_AMOUNT_EUR, EventSchema.Type.NUMBER, true, "importo bolletta"),
                        new EventSchema.Attribute("contractType", EventSchema.Type.TEXT, false, "GAS | LUCE | ACQUA | TLR"))),
                new EventSchema("SELF_READING_SENT", "Autolettura inviata", 1, true, true, List.of(new EventSchema.Attribute("meter", EventSchema.Type.TEXT, false, "GAS | LUCE | ACQUA"))),
                new EventSchema(EventTypes.ACTION_TRANSACTION, "Transazione", 1, true, true, List.of(
                        new EventSchema.Attribute(EventTypes.ATTR_AMOUNT_EUR, EventSchema.Type.NUMBER, true, "totale"),
                        new EventSchema.Attribute(EventTypes.ATTR_LINES, EventSchema.Type.LIST, false, "righe"))),
                new EventSchema("bike_ride", "Giro in bici (evento app)", 1, true, false, List.of(
                        new EventSchema.Attribute("distanceKm", EventSchema.Type.NUMBER, true, null), new EventSchema.Attribute("startedAt", EventSchema.Type.DATETIME, false, null))));
        return new SchemaRegistry() {
            @Override public Optional<EventSchema> byActionType(String t) { return seed.stream().filter(s -> s.actionType().equals(t) && s.active()).findFirst(); }
            @Override public List<EventSchema> all() { return seed; }
        };
    }

    @Bean @ConditionalOnMissingBean
    ProductCatalog productCatalog(JdbcTemplate jdbc) {
        return sku -> jdbc.query("SELECT sku, name, category, brand, price, labels, attributes::text FROM ingressadapters.product WHERE sku = ? AND active", rs -> {
            if (!rs.next()) return Optional.<Product>empty();
            java.util.Map<String, String> attrs = new java.util.HashMap<>();
            try { attrs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(rs.getString(7), new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {}); } catch (Exception ignored) {}
            String labels = rs.getString(6);
            return Optional.of(new Product(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5), labels == null || labels.isBlank() ? List.of() : List.of(labels.split(";")), attrs, true));
        }, sku);
    }
}
