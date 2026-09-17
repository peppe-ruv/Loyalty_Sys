package it.iren.loyalty.ingressadapters.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventSchemaTest {
    @Test void validatesTypesRequiredAndUnknown() {
        var s = new EventSchema("bike_ride", "Giro", 1, true, false, List.of(new EventSchema.Attribute("distanceKm", EventSchema.Type.NUMBER, true, null), new EventSchema.Attribute("startedAt", EventSchema.Type.DATETIME, false, null)));
        assertThat(s.validate(Map.of("distanceKm", 12, "startedAt", "2027-03-10T10:00:00Z"))).isEmpty();
        assertThat(s.validate(Map.of("distanceKm", "abc", "extra", 1))).hasSize(2);
        assertThat(s.validate(Map.of())).containsExactly("missing distanceKm");
    }
}
