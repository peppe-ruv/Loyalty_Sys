package io.loyaltyhub.member.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Completezza del profilo (docs/03 §2, F-MBR-07): {@code firstName, lastName, email, phone, birthDate, city}
 * tutti valorizzati. I nomi dei campi mancanti sono quelli dell'API, nell'ordine del form di PT-08.
 */
public final class ProfileRules {

    private ProfileRules() {
    }

    public static List<String> missingFields(Member m) {
        return missingFields(m.firstName(), m.lastName(), m.email(), m.phone(), m.birthDate() != null, m.city());
    }

    public static List<String> missingFields(String firstName, String lastName, String email, String phone,
                                             boolean hasBirthDate, String city) {
        List<String> missing = new ArrayList<>();
        if (blank(firstName)) missing.add("firstName");
        if (blank(lastName)) missing.add("lastName");
        if (blank(email)) missing.add("email");
        if (blank(phone)) missing.add("phone");
        if (!hasBirthDate) missing.add("birthDate");
        if (blank(city)) missing.add("city");
        return missing;
    }

    public static Member withCompletedAt(Member m, Instant at) {
        return new Member(m.id(), m.externalId(), m.firstName(), m.lastName(), m.nickname(), m.email(), m.phone(),
                m.birthDate(), m.gender(), m.city(), m.status(), m.channel(), m.registeredAt(), m.referralCode(),
                m.referredBy(), m.referralCompletedAt(), m.consentsJson(), m.attributesJson(), m.labels(),
                m.avatarSeed(), at, m.version());
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
