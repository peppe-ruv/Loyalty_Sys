package it.iren.loyalty.segmentservice;

import it.iren.loyalty.segmentservice.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Adattatori di default: segmenti di esempio in memoria (in produzione: CMS) e snapshot dal read-model via REST. */
@Configuration
public class SegmentConfig {

    @Bean
    @ConditionalOnMissingBean
    SegmentSource seedSegmentSource() {
        return () -> List.of(
                new Segment("green-customers", "Clienti con offerta green", true, Segment.Match.ALL,
                        List.of(new Segment.Criterion(Segment.Type.BOUGHT_LABEL, Map.of("values", List.of("green"))))),
                new Segment("inactive-90d", "Inattivi da 90 giorni", true, Segment.Match.ALL,
                        List.of(new Segment.Criterion(Segment.Type.LAST_ACTION_DAYS_AGO, Map.of("min", 90)))),
                new Segment("top-spenders-year", "Spesa annua sopra 1.000 €", true, Segment.Match.ALL,
                        List.of(new Segment.Criterion(Segment.Type.ACTION_VALUE, Map.of("min", 1000, "days", 365)))),
                new Segment("anniversary-week", "Anniversario di adesione entro 7 giorni", true, Segment.Match.ALL,
                        List.of(new Segment.Criterion(Segment.Type.ANNIVERSARY, Map.of("days", 7)))));
    }

    @Bean
    @ConditionalOnMissingBean
    SnapshotSource readModelSnapshots(RestClient.Builder builder, JdbcTemplate jdbc) {
        RestClient rm = builder.baseUrl(System.getenv().getOrDefault("READ_MODEL_URL", "http://read-model:8088")).build();
        return new SnapshotSource() {
            @Override public Optional<MemberSnapshot> snapshotOf(String memberId) {
                try { return Optional.ofNullable(rm.get().uri("/v1/read/members/{id}/snapshot", memberId).retrieve().body(MemberSnapshot.class)); }
                catch (Exception e) { return Optional.empty(); }
            }
            @Override public Stream<MemberSnapshot> allActive() {
                // Lotti per id: il read-model espone una pagina keyset; qui la si consuma pigramente.
                return Stream.iterate(page(rm, ""), p -> !p.isEmpty(), p -> page(rm, p.get(p.size() - 1).memberId())).flatMap(List::stream);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<MemberSnapshot> page(RestClient rm, String after) {
        try {
            var body = rm.get().uri(u -> u.path("/v1/read/members/snapshots").queryParam("after", after).queryParam("size", 1000).build()).retrieve().body(List.class);
            if (body == null) return List.of();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
            return ((List<Object>) body).stream().map(o -> mapper.convertValue(o, MemberSnapshot.class)).toList();
        } catch (Exception e) { return List.of(); }
    }
}
