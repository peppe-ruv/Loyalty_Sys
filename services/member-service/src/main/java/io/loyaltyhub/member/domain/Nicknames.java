package io.loyaltyhub.member.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Soprannomi a lotti per il BFF (Q-368, ADR-032, docs/18 §3.4): da {@code member.*:2} il {@code nickname} non viaggia
 * più sul bus, quindi classifiche del portale ed export dei vincitori ricevono da gamification solo i {@code memberId} e
 * il BFF, lato server, chiede qui il nome da mostrare. Regole pure: limiti della richiesta e nome mostrato.
 */
public final class Nicknames {

    /** Massimo di id per richiesta (Q-368): oltre → {@code 400 TOO_MANY_IDS}. */
    public static final int MAX_IDS = 200;

    private Nicknames() {
    }

    /**
     * {@code true} se la richiesta supera {@link #MAX_IDS} voci. Il limite vale sulle voci ricevute, doppioni e vuoti
     * compresi (scelta prudente: il costo lo decide il chiamante, non la deduplica).
     */
    public static boolean tooMany(List<String> memberIds) {
        return memberIds != null && memberIds.size() > MAX_IDS;
    }

    /** Id richiesti senza vuoti né doppioni, nell'ordine ricevuto; lista vuota se non ne resta nessuno. */
    public static List<String> distinctIds(List<String> memberIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (memberIds != null) {
            for (String id : memberIds) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * Nome mostrato in classifica: il segnaposto per un membro {@code ANONYMIZED} (F-MBR-05, docs/03 §2), altrimenti il
     * {@code nickname} salvato; {@code null} se manca (il BFF mostra il suo segnaposto).
     */
    public static String shown(String nickname, MemberStatus status) {
        if (status == MemberStatus.ANONYMIZED) {
            return Anonymization.PLACEHOLDER;
        }
        if (nickname == null || nickname.isBlank()) {
            return null;
        }
        return nickname;
    }
}
