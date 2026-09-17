package it.iren.loyalty.memberservice.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Campi custom tipizzati (RF-99), equivalente dei "custom fields" di Open Loyalty: gruppi di campi per entità
 * (MEMBER, CAMPAIGN, REWARD, WHEEL, TRANSACTION), tipi STRING (lunghezza, regex), NUMBER (min/max), BOOLEAN, DATE,
 * SINGLE_SELECT / MULTI_SELECT (valori da una collezione, RF-100), SUBGROUP ripetibile solo per i membri.
 * Le etichette chiave/valore (RF-72) restano il meccanismo semplice; i campi custom sono validati e permessi per ruolo.
 */
public record CustomFieldSchema(Entity entity, String group, String name, List<Field> fields, boolean repeatable, String editRole) {
    public enum Entity { MEMBER, CAMPAIGN, REWARD, WHEEL, TRANSACTION }
    public enum Type { STRING, NUMBER, BOOLEAN, DATE, SINGLE_SELECT, MULTI_SELECT }
    public record Field(String name, Type type, boolean required, Integer maxLength, String regex, Double min, Double max, String collection) {}

    /** Errori di validazione dei valori del gruppo (vuoto = valido). {@code collections} risolve i select. */
    public List<String> validate(Map<String, Object> values, java.util.function.BiPredicate<String, String> collections) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> v = values == null ? Map.of() : values;
        for (Field f : fields) {
            Object x = v.get(f.name());
            if (x == null) { if (f.required()) errors.add(group + "." + f.name() + " required"); continue; }
            switch (f.type()) {
                case STRING -> {
                    String s = x.toString();
                    if (f.maxLength() != null && s.length() > f.maxLength()) errors.add(f.name() + " too long");
                    if (f.regex() != null && !s.matches(f.regex())) errors.add(f.name() + " does not match " + f.regex());
                }
                case NUMBER -> {
                    try { double d = Double.parseDouble(x.toString()); if ((f.min() != null && d < f.min()) || (f.max() != null && d > f.max())) errors.add(f.name() + " out of range"); }
                    catch (NumberFormatException e) { errors.add(f.name() + " not a number"); }
                }
                case BOOLEAN -> { if (!(x instanceof Boolean) && !x.toString().matches("true|false")) errors.add(f.name() + " not boolean"); }
                case DATE -> { try { java.time.LocalDate.parse(x.toString().substring(0, 10)); } catch (Exception e) { errors.add(f.name() + " not a date"); } }
                case SINGLE_SELECT -> { if (f.collection() != null && !collections.test(f.collection(), x.toString())) errors.add(f.name() + " not in " + f.collection()); }
                case MULTI_SELECT -> {
                    if (!(x instanceof List<?> l)) { errors.add(f.name() + " must be a list"); break; }
                    for (Object o : l) if (f.collection() != null && !collections.test(f.collection(), String.valueOf(o))) errors.add(f.name() + ": " + o + " not in " + f.collection());
                }
            }
        }
        for (String k : v.keySet()) if (fields.stream().noneMatch(f -> f.name().equals(k))) errors.add("unknown field " + group + "." + k);
        return errors;
    }
}
