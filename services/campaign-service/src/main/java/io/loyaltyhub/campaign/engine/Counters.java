package io.loyaltyhub.campaign.engine;

/**
 * Vista (sola lettura) dei contatori che il motore consulta (docs/03 §3.5): limiti per membro, budget
 * globale e storico azioni. La consumazione atomica avviene fuori dal motore, nella transazione del servizio.
 */
public interface Counters {

    /** Numero di match già registrati per (campagna, membro, periodo, chiave-periodo). */
    int memberMatches(String campaignId, String memberId, String period, String periodKey);

    /** Punti già decisi complessivamente per la campagna (per il budget globale). */
    long globalPointsDecided(String campaignId);

    /** Match globali già registrati per la campagna. */
    long globalMatches(String campaignId);

    /** Azioni dello stesso tipo precedenti a questa per il membro ({@code history.actionCount}). */
    long historyActionCount(String memberId, String actionType);

    /** Giorni dall'ultima azione dello stesso tipo, o {@code -1} se mai ({@code history.daysSinceLastAction}). */
    long historyDaysSinceLastAction(String memberId, String actionType);

    /** Punti già decisi dalla campagna per il membro, da sempre ({@code limits.perMemberPoints}, F-CMP-05). */
    default long memberPoints(String campaignId, String memberId) {
        return 0;
    }

    /** Istante di business dell'ultimo match della campagna per il membro, o {@code null} ({@code limits.cooldownMinutes}). */
    default java.time.Instant memberLastMatchAt(String campaignId, String memberId) {
        return null;
    }
}
