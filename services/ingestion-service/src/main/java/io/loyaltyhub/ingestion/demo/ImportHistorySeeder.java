package io.loyaltyhub.ingestion.demo;

import io.loyaltyhub.common.demo.SeedLoader;
import io.loyaltyhub.common.ids.Ulid;
import io.loyaltyhub.ingestion.domain.ImportParser;
import io.loyaltyhub.ingestion.domain.ItemOutcome;
import io.loyaltyhub.ingestion.domain.OutcomeCounts;
import io.loyaltyhub.ingestion.infra.ImportRepository;
import io.loyaltyhub.ingestion.infra.ImportRepository.InboundRef;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Storico demo degli import file (BO-32 piena a demo appena accesa, CLAUDE.md regola 9; docs/10 §8.2) da
 * {@code seed/import-history.json}. Ogni riga cita un ingresso dello storico del monitor ({@link InboundHistorySeeder},
 * da caricare prima) con lo stesso id evento ed esito: il rapporto riusa codice, dettaglio e riga del monitor, così
 * «Riprova non abbinati» agisce sugli stessi {@code UNMATCHED} di BO-26. Le righe {@code invalid} non hanno ingresso.
 * Caricamento = due minuti prima del primo ingresso citato, fine = un secondo per riga dopo. Nessun file, nessuna
 * pubblicazione.
 */
@Component
@Profile("demo")
public class ImportHistorySeeder {

    static final String FILE = "import-history.json";
    private static final int BYTES_PER_ROW = 180;

    private final SeedLoader seed;
    private final ImportRepository imports;
    private final Clock clock;

    public ImportHistorySeeder(SeedLoader seed, ImportRepository imports, Clock clock) {
        this.seed = seed;
        this.imports = imports;
        this.clock = clock;
    }

    /** Svuota gli import (reset, docs/06 §10) e ricarica lo storico; ritorna il numero di lavori. */
    public int reseed() {
        imports.deleteAll();
        int n = 0;
        for (JsonNode job : seed.readTree(FILE).path("imports")) {
            insert(job);
            n++;
        }
        return n;
    }

    private record Row(int number, String eventId, ItemOutcome outcome, InboundRef ref, String invalidDetail) {
    }

    private void insert(JsonNode job) {
        String fileName = job.path("fileName").asString();
        List<Row> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int number = 0;
        for (JsonNode r : job.path("rows")) {
            number++;
            if (r.hasNonNull("invalid")) {
                rows.add(new Row(number, null, ItemOutcome.INVALID, null, r.path("invalid").asString()));
                continue;
            }
            String eventId = r.path("eventId").asString();
            String status = r.path("status").asString();
            InboundRef ref = imports.inboundByEventAndStatus(eventId, status).stream()
                    .filter(x -> !used.contains(x.id())).findFirst()
                    .orElseThrow(() -> new IllegalStateException(FILE + ": " + fileName + " riga " + eventId + "/" + status
                            + " senza l'ingresso corrispondente in inbound-history.json"));
            used.add(ref.id());
            rows.add(new Row(number, eventId, ItemOutcome.valueOf(status), ref, null));
        }
        Instant first = rows.stream().filter(r -> r.ref() != null).map(r -> r.ref().receivedAt()).min(Instant::compareTo)
                .orElse(clock.instant().minus(Duration.ofDays(1)));
        Instant createdAt = first.minus(Duration.ofMinutes(2));
        Instant finishedAt = createdAt.plusSeconds(rows.size());
        String id = Ulid.next(Clock.fixed(createdAt, clock.getZone()));
        OutcomeCounts counts = OutcomeCounts.of(rows.stream().map(Row::outcome).toList());
        imports.insertHistory(id, job.path("format").asString(), fileName, rows.size() * BYTES_PER_ROW,
                ImportParser.sha256(job.toString().getBytes(StandardCharsets.UTF_8)), job.path("source").asString(null), counts, job.path("createdBy").asString(),
                createdAt, finishedAt);
        for (Row r : rows) {
            if (r.outcome() == ItemOutcome.ACCEPTED) {
                continue;
            }
            if (r.ref() == null) {
                imports.insertRow(id, r.number(), null, r.outcome(), null, r.invalidDetail(), null);
            } else {
                imports.insertRow(id, r.number(), r.eventId(), r.outcome(), r.ref().rejectCode(), r.ref().detail(),
                        r.ref().id());
            }
        }
    }
}
