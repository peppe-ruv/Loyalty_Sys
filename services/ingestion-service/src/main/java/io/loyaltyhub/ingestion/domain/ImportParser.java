package io.loyaltyhub.ingestion.domain;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lettura di un import file (F2-ING-02, docs/18 §3.6) in record di eventi, senza toccare il database. I record si
 * consegnano uno alla volta a un {@link Sink} (mai l'elenco di tutte le righe in memoria), che può fermare la lettura.
 * Regole:
 * <ul>
 *   <li><b>Testo UTF-8</b>: byte non UTF-8 o un carattere NUL ⇒ il file non è testo ({@code IMPORT_NOT_TEXT}); il BOM
 *       iniziale si ignora.</li>
 *   <li><b>Contenuto riconosciuto, non l'estensione</b>: {@code JSON} comincia con {@code [}, {@code NDJSON} con
 *       {@code {}, un {@code CSV} con nessuno dei due ({@code IMPORT_FORMAT_MISMATCH}).</li>
 *   <li><b>CSV</b> ({@link CsvReader}, separatore {@code ,} oppure {@code ;} dedotto dall'intestazione): colonne
 *       {@code id, type, subject, time} obbligatorie, {@code source} obbligatoria se manca la fonte predefinita,
 *       {@code specversion} e {@code data} (oggetto JSON) facoltative, più {@code data.<campo>} per i campi di primo livello
 *       di {@code data}, convertiti col tipo dello schema del tipo azione ({@code integer, number, boolean, array,
 *       object}; altrimenti testo; per {@code number} la virgola decimale è ammessa). Colonne sconosciute o ripetute ⇒
 *       {@code IMPORT_INVALID}: niente dati ignorati in silenzio.</li>
 *   <li><b>NDJSON/JSON</b>: un CloudEvent per riga / per elemento dell'array (letto in streaming).</li>
 *   <li>In tutti i formati {@code specversion} assente vale {@code 1.0} e {@code source} assente vale la fonte
 *       predefinita del lavoro, se indicata. Un record illeggibile (JSON non valido, colonne in numero diverso, valore non
 *       convertibile) è una riga {@code INVALID} con il motivo, che non riporta mai il valore letto (può essere un dato
 *       personale).</li>
 * </ul>
 */
public final class ImportParser {

    public static final String DEFAULT_SPECVERSION = "1.0";
    static final Set<String> FIXED_COLUMNS = Set.of("specversion", "id", "source", "type", "subject", "time", "data");
    static final String DATA_PREFIX = "data.";

    /** Tipi JSON Schema dei campi di primo livello di {@code data} per un tipo azione (breve o completo). */
    @FunctionalInterface
    public interface FieldTypes {
        Map<String, String> of(String type);

        FieldTypes NONE = type -> Map.of();
    }

    /** Destinatario dei record, in ordine; {@code false} ferma la lettura. */
    @FunctionalInterface
    public interface Sink {
        boolean accept(ImportRecord record);
    }

    private final ObjectMapper mapper;

    public ImportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ================= file =================

    /** Testo del file: UTF-8 stretto, BOM iniziale rimosso, nessun NUL. */
    public static String decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new ImportFileException("IMPORT_NOT_TEXT", "Il file non è testo UTF-8.");
        }
        if (text.indexOf('\0') >= 0) {
            throw new ImportFileException("IMPORT_NOT_TEXT", "Il file contiene caratteri binari: non è un file di testo.");
        }
        return stripBom(text);
    }

    /**
     * Nome del file da mostrare: senza percorso, solo caratteri stampabili, al massimo 120 caratteri. Mai usato per
     * scrivere su disco.
     */
    public static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        name.codePoints().filter(cp -> !Character.isISOControl(cp) && cp != '"' && cp != '<' && cp != '>')
                .forEach(sb::appendCodePoint);
        String clean = sb.toString().strip();
        if (clean.length() > 120) {
            clean = clean.substring(clean.length() - 120);
        }
        return clean.isEmpty() ? "import" : clean;
    }

    /** Impronta SHA-256 esadecimale del file caricato (evidenza dell'import, mai il contenuto). */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Attributo scalare di un CloudEvent JSON (stringa o numero, come lo legge Jackson per {@code POST /v1/events});
     * {@code null} se assente, nullo, oggetto o array. Condiviso con l'ingresso batch.
     */
    public static String attribute(JsonNode event, String name) {
        JsonNode n = event.get(name);
        if (n == null || n.isNull() || n.isContainer()) {
            return null;
        }
        return n.asString();
    }

    /**
     * Consegna i record a {@code sink} nell'ordine del file, finché il file finisce o {@code sink} risponde {@code false};
     * lancia {@link ImportFileException} solo per errori dell'intero file. Ritorna quanti record ha consegnato.
     */
    public int read(ImportFormat format, String content, String defaultSource, FieldTypes types, Sink sink) {
        String text = stripBom(content);
        char first = firstSignificant(text);
        return switch (format) {
            case JSON -> {
                if (first != '[') {
                    throw mismatch("un file .json deve contenere un array di eventi ([ … ])");
                }
                yield readJsonArray(text, defaultSource, sink);
            }
            case NDJSON -> {
                if (first != '{' && first != 0) {
                    throw mismatch("un file NDJSON contiene un evento JSON ({ … }) per riga");
                }
                yield readNdjson(text, defaultSource, sink);
            }
            case CSV -> {
                if (first == '{' || first == '[') {
                    throw mismatch("il contenuto è JSON, non CSV");
                }
                yield readCsv(text, defaultSource, types == null ? FieldTypes.NONE : types, sink);
            }
        };
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static ImportFileException mismatch(String why) {
        return new ImportFileException("IMPORT_FORMAT_MISMATCH", "Il contenuto non corrisponde al formato del file: " + why + ".");
    }

    private static char firstSignificant(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return 0;
    }

    // ================= JSON / NDJSON =================

    /** Array JSON letto in streaming: un elemento alla volta, mai l'albero intero. */
    private int readJsonArray(String text, String defaultSource, Sink sink) {
        int row = 0;
        try (JsonParser p = mapper.createParser(text)) {
            p.nextToken(); // START_ARRAY, garantito dal controllo del primo carattere
            for (JsonToken t = p.nextToken(); t != JsonToken.END_ARRAY; t = p.nextToken()) {
                if (t == null) {
                    throw invalidJson();
                }
                // Solo il sotto-albero dell'elemento (readTree del mapper vorrebbe un documento senza seguito).
                JsonNode node = p.readValueAsTree();
                if (!sink.accept(fromJson(++row, node, defaultSource))) {
                    return row;
                }
            }
            if (p.nextToken() != null) {
                throw invalidJson();
            }
        } catch (JacksonException e) {
            throw invalidJson();
        }
        return row;
    }

    private static ImportFileException invalidJson() {
        return new ImportFileException("IMPORT_INVALID", "Il file JSON non è valido: controlla parentesi e virgole.");
    }

    private int readNdjson(String text, String defaultSource, Sink sink) {
        int row = 0;
        Iterator<String> lines = text.lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isBlank()) {
                continue;
            }
            row++;
            ImportRecord record;
            try {
                record = fromJson(row, mapper.readTree(line), defaultSource);
            } catch (JacksonException e) {
                record = ImportRecord.invalid(row, null, "JSON non valido");
            }
            if (!sink.accept(record)) {
                return row;
            }
        }
        return row;
    }

    private static ImportRecord fromJson(int row, JsonNode node, String defaultSource) {
        if (node == null || !node.isObject()) {
            return ImportRecord.invalid(row, null, "l'elemento non è un oggetto JSON (CloudEvent)");
        }
        String specversion = attribute(node, "specversion");
        String source = attribute(node, "source");
        return new ImportRecord(row,
                specversion == null ? DEFAULT_SPECVERSION : specversion,
                attribute(node, "id"),
                source == null ? defaultSource : source,
                attribute(node, "type"),
                attribute(node, "subject"),
                attribute(node, "time"),
                node.get("data"),
                null);
    }

    // ================= CSV =================

    private int readCsv(String text, String defaultSource, FieldTypes types, Sink sink) {
        CsvReader reader = new CsvReader(text, CsvReader.delimiterOf(text));
        List<String> cells = nextNonBlank(reader);
        if (cells == null) {
            throw new ImportFileException("IMPORT_EMPTY", "Il file è vuoto: nessuna intestazione né riga di dati.");
        }
        Header header = header(cells, defaultSource != null);
        Map<String, Map<String, String>> typeCache = new HashMap<>();
        int row = 0;
        for (cells = nextNonBlank(reader); cells != null; cells = nextNonBlank(reader)) {
            if (!sink.accept(csvRecord(++row, cells, header, defaultSource, types, typeCache))) {
                return row;
            }
        }
        return row;
    }

    /** Righe vuote ignorate ovunque (non contano come righe del rapporto). */
    private static List<String> nextNonBlank(CsvReader reader) {
        List<String> cells = reader.next();
        while (cells != null && CsvReader.blank(cells)) {
            cells = reader.next();
        }
        return cells;
    }

    /** Colonne dell'intestazione: posizione di ogni attributo e dei campi {@code data.<campo>}. */
    record Header(int size, Map<String, Integer> fixed, Map<String, Integer> dataFields) {
    }

    static Header header(List<String> cells, boolean hasDefaultSource) {
        Map<String, Integer> fixed = new LinkedHashMap<>();
        Map<String, Integer> dataFields = new LinkedHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String raw = cells.get(i).strip();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (FIXED_COLUMNS.contains(lower)) {
                if (fixed.putIfAbsent(lower, i) != null) {
                    throw invalidHeader("colonna ripetuta «" + lower + "»");
                }
            } else if (lower.startsWith(DATA_PREFIX)) {
                String field = raw.substring(DATA_PREFIX.length());
                if (field.isEmpty() || field.contains(".")) {
                    throw invalidHeader("«" + raw + "»: solo campi di primo livello (data.campo); per strutture annidate usa la colonna data con un oggetto JSON");
                }
                if (dataFields.putIfAbsent(field, i) != null) {
                    throw invalidHeader("colonna ripetuta «" + raw + "»");
                }
            } else {
                throw invalidHeader("colonna sconosciuta «" + (raw.length() > 40 ? raw.substring(0, 40) + "…" : raw)
                        + "» (ammesse: id, source, type, subject, time, specversion, data, data.<campo>)");
            }
        }
        List<String> missing = new ArrayList<>();
        for (String required : List.of("id", "type", "subject", "time")) {
            if (!fixed.containsKey(required)) {
                missing.add(required);
            }
        }
        if (!hasDefaultSource && !fixed.containsKey("source")) {
            missing.add("source (o la fonte predefinita dell'import)");
        }
        if (!missing.isEmpty()) {
            throw invalidHeader("mancano le colonne " + String.join(", ", missing));
        }
        return new Header(cells.size(), fixed, dataFields);
    }

    private static ImportFileException invalidHeader(String why) {
        return new ImportFileException("IMPORT_INVALID", "Intestazione CSV non valida: " + why + ".");
    }

    private ImportRecord csvRecord(int row, List<String> cells, Header h, String defaultSource, FieldTypes types,
                                   Map<String, Map<String, String>> typeCache) {
        String id = blankToNull(cell(cells, h.fixed().get("id")));
        if (cells.size() != h.size()) {
            return ImportRecord.invalid(row, id, "attese " + h.size() + " colonne, trovate " + cells.size());
        }
        String type = blankToNull(cell(cells, h.fixed().get("type")));
        ObjectNode data;
        String dataJson = blankToNull(cell(cells, h.fixed().get("data")));
        if (dataJson != null) {
            JsonNode parsed;
            try {
                parsed = mapper.readTree(dataJson);
            } catch (JacksonException e) {
                return ImportRecord.invalid(row, id, "colonna data: JSON non valido");
            }
            if (parsed == null || !parsed.isObject()) {
                return ImportRecord.invalid(row, id, "colonna data: deve essere un oggetto JSON");
            }
            data = (ObjectNode) parsed;
        } else {
            data = JsonNodeFactory.instance.objectNode();
        }
        if (!h.dataFields().isEmpty()) {
            Map<String, String> fieldTypes = type == null ? Map.of() : typeCache.computeIfAbsent(type, types::of);
            for (Map.Entry<String, Integer> f : h.dataFields().entrySet()) {
                String raw = cells.get(f.getValue());
                if (raw.isBlank()) {
                    continue;
                }
                try {
                    data.set(f.getKey(), coerce(raw.strip(), fieldTypes.get(f.getKey())));
                } catch (IllegalArgumentException e) {
                    return ImportRecord.invalid(row, id, "data." + f.getKey() + ": " + e.getMessage());
                }
            }
        }
        String specversion = blankToNull(cell(cells, h.fixed().get("specversion")));
        String source = blankToNull(cell(cells, h.fixed().get("source")));
        return new ImportRecord(row,
                specversion == null ? DEFAULT_SPECVERSION : specversion,
                id,
                source == null ? defaultSource : source,
                type,
                blankToNull(cell(cells, h.fixed().get("subject"))),
                blankToNull(cell(cells, h.fixed().get("time"))),
                data,
                null);
    }

    /**
     * Valore di una cella {@code data.<campo>} nel tipo JSON dello schema; un tipo sconosciuto (campo non descritto o
     * schema assente) resta testo, e sarà lo schema a respingere la riga se serve altro ({@code INVALID_DATA}).
     */
    JsonNode coerce(String raw, String jsonType) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (jsonType == null) {
            return f.stringNode(raw);
        }
        return switch (jsonType) {
            case "integer" -> {
                try {
                    yield f.numberNode(Long.parseLong(raw));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("atteso un numero intero");
                }
            }
            case "number" -> {
                String normalized = raw.indexOf('.') < 0 ? raw.replace(',', '.') : raw;
                try {
                    yield f.numberNode(new BigDecimal(normalized));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("atteso un numero");
                }
            }
            case "boolean" -> {
                String b = raw.toLowerCase(Locale.ROOT);
                if (!b.equals("true") && !b.equals("false")) {
                    throw new IllegalArgumentException("atteso true o false");
                }
                yield f.booleanNode(b.equals("true"));
            }
            case "array", "object" -> {
                JsonNode parsed;
                try {
                    parsed = mapper.readTree(raw);
                } catch (JacksonException e) {
                    throw new IllegalArgumentException("atteso JSON (" + jsonType + ")");
                }
                boolean ok = jsonType.equals("array") ? parsed != null && parsed.isArray() : parsed != null && parsed.isObject();
                if (!ok) {
                    throw new IllegalArgumentException("atteso JSON (" + jsonType + ")");
                }
                yield parsed;
            }
            default -> f.stringNode(raw);
        };
    }

    private static String cell(List<String> cells, Integer index) {
        return index == null || index >= cells.size() ? null : cells.get(index);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
