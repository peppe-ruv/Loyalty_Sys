package io.loyaltyhub.common.web;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.jackson.JsonNodeDeserializer;
import tools.jackson.databind.deser.jdk.StringDeserializer;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collection;
import java.util.Map;

/**
 * Rifiuta con {@code 400} un corpo JSON che contiene il carattere NUL ({@code U+0000}, anche come escape
 * {@code \u0000}) in un valore di testo o nella chiave di una mappa (Q-532 causa (3), F2-SEC-12, ADR-042). Il rifiuto
 * avviene durante la lettura del corpo: l'eccezione di Jackson diventa {@code HttpMessageNotReadableException} e
 * {@link GlobalExceptionHandler} la porta a {@code 400 BAD_REQUEST} (mai il valore ricevuto nel dettaglio).
 *
 * <p>Copre: ogni {@code String} di un record o bean, le chiavi di ogni {@code Map<String, …>}, e gli alberi liberi, cioè
 * {@code JsonNode} e {@code Object} ({@code Map<String, Object>}), dove i valori non passano dal deserializzatore di
 * stringhe (per esempio {@code shipping} di una richiesta di premio, salvato in una colonna jsonb). È un bean di
 * {@code lh-common}: lo registra il {@code ObjectMapper} di Spring Boot, quindi vale per ogni servizio e per l'hub.
 *
 * <p><strong>Dove vale.</strong> Solo durante la conversione del corpo di una richiesta HTTP, cioè tra
 * {@code beforeBodyRead} e {@code afterBodyRead} di {@link NulBodyScopeAdvice} (vedi {@link NulScope}): il parsing che un
 * handler fa da sé di un testo ricevuto (il file d'import JSON, Q-371), i consumer Kafka, i seed e i worker dei lavori non
 * cambiano. Un handler con una regola propria sui NUL sul corpo lo dichiara con {@link NulTolerantBody}.
 */
public class NulRejectingModule extends SimpleModule {

    public NulRejectingModule() {
        super("lh-nul-rejecting");
        addDeserializer(String.class, new NulRejectingStringDeserializer());
        addKeyDeserializer(String.class, new NulRejectingKeyDeserializer());
        // Gli alberi liberi: {@code JsonNode} non passa dai modificatori di Jackson (il suo deserializzatore è scelto
        // prima), va registrato per tipo; {@code Object} (Map<String, Object>, List<Object>) sì.
        addTreeDeserializer(JsonNode.class);
        addTreeDeserializer(ObjectNode.class);
        addTreeDeserializer(ArrayNode.class);
        setDeserializerModifier(new FreeFormModifier());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void addTreeDeserializer(Class<? extends JsonNode> type) {
        addDeserializer((Class) type, new TreeScanningDeserializer(JsonNodeDeserializer.getDeserializer(type)));
    }

    /** Un {@code String} letto dal corpo: quello standard, più il rifiuto del NUL. */
    static final class NulRejectingStringDeserializer extends StdScalarDeserializer<String> {

        NulRejectingStringDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) {
            String value = StringDeserializer.instance.deserialize(p, ctxt);
            if (NulScope.rejecting() && NulCharacters.in(value)) {
                return ctxt.reportInputMismatch(String.class, NulCharacters.BODY_MESSAGE);
            }
            return value;
        }

        @Override
        public boolean isCachable() {
            return true;
        }
    }

    /** La chiave di una {@code Map<String, …>}: rifiutata se contiene un NUL. */
    static final class NulRejectingKeyDeserializer extends KeyDeserializer {

        @Override
        public Object deserializeKey(String key, DeserializationContext ctxt) {
            if (NulScope.rejecting() && NulCharacters.in(key)) {
                return ctxt.reportInputMismatch(String.class, NulCharacters.BODY_MESSAGE);
            }
            return key;
        }
    }

    /** Avvolge il deserializzatore di {@code Object} (alberi liberi di mappe, elenchi e testi). */
    static final class FreeFormModifier extends ValueDeserializerModifier {

        @Override
        public ValueDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription.Supplier beanDesc,
                                                        ValueDeserializer<?> deserializer) {
            Class<?> type = beanDesc.getBeanClass();
            if (type == Object.class) {
                return new TreeScanningDeserializer(deserializer);
            }
            return deserializer;
        }
    }

    /** Il risultato di un albero libero si ispeziona a lettura finita: chiavi e valori di testo, a ogni profondità. */
    static final class TreeScanningDeserializer extends DelegatingDeserializer {

        TreeScanningDeserializer(ValueDeserializer<?> delegatee) {
            super(delegatee);
        }

        @Override
        protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> newDelegatee) {
            return new TreeScanningDeserializer(newDelegatee);
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) {
            Object tree = super.deserialize(p, ctxt);
            if (NulScope.rejecting() && containsNul(tree)) {
                return ctxt.reportInputMismatch(Object.class, NulCharacters.BODY_MESSAGE);
            }
            return tree;
        }
    }

    /** {@code true} se una stringa, una chiave o un nodo di testo dell'albero contiene un NUL. */
    static boolean containsNul(Object node) {
        if (node instanceof String text) {
            return NulCharacters.in(text);
        }
        if (node instanceof JsonNode json) {
            if (json.isString()) {
                return NulCharacters.in(json.stringValue());
            }
            if (json.isObject()) {
                for (Map.Entry<String, JsonNode> property : json.properties()) {
                    if (NulCharacters.in(property.getKey()) || containsNul(property.getValue())) {
                        return true;
                    }
                }
                return false;
            }
            if (json.isArray()) {
                for (JsonNode element : json.values()) {
                    if (containsNul(element)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (containsNul(entry.getKey()) || containsNul(entry.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof Collection<?> items) {
            for (Object item : items) {
                if (containsNul(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
