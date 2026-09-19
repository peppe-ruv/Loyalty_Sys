package io.loyaltyhub.common.demo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Carica i dati demo canonici da {@code classpath:/seed/*.json} (docs/10 §1): un'unica cartella
 * {@code seed/} inclusa come risorsa da ogni modulo, letta col profilo {@code demo}.
 */
public class SeedLoader {

    private final ObjectMapper mapper;

    public SeedLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public boolean exists(String fileName) {
        return new ClassPathResource("seed/" + fileName).exists();
    }

    /** Albero JSON grezzo del file seed (per risolvere date con {@link SeedDates} campo per campo). */
    public JsonNode readTree(String fileName) {
        try (InputStream in = open(fileName)) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed non leggibile: " + fileName, e);
        }
    }

    /** Deserializza un file seed in un oggetto del tipo indicato. */
    public <T> T read(String fileName, Class<T> type) {
        try (InputStream in = open(fileName)) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed non leggibile: " + fileName, e);
        }
    }

    /** Deserializza un file seed che contiene un array di elementi del tipo indicato. */
    public <T> List<T> readList(String fileName, Class<T> type) {
        try (InputStream in = open(fileName)) {
            var listType = mapper.getTypeFactory().constructCollectionType(List.class, type);
            return mapper.readValue(in, listType);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed non leggibile: " + fileName, e);
        }
    }

    private InputStream open(String fileName) throws IOException {
        ClassPathResource resource = new ClassPathResource("seed/" + fileName);
        if (!resource.exists()) {
            throw new IOException("File seed assente: seed/" + fileName);
        }
        return resource.getInputStream();
    }
}
