package it.iren.loyalty.engagementservice.domain;

/**
 * Badge (RF-92): etichetta di riconoscimento con codice di sistema immutabile, assegnata a mano, da effetto di campagna
 * o al completamento di una challenge; conta quante volte è stata ottenuta; usabile in condizioni e segmenti.
 */
public record Badge(String code, String name, String description, String imageUrl, boolean active, boolean stackable) {
    public Badge {
        if (code == null || !code.matches("[a-z0-9_-]{2,64}")) throw new IllegalArgumentException("badge code: lowercase, 2-64 chars");
    }
    public record Grant(String memberId, String badgeCode, int completedCount, java.time.Instant firstGrantedAt, java.time.Instant lastGrantedAt, String source) {
        public Grant grantAgain(java.time.Instant at, String source, boolean stackable) {
            return new Grant(memberId, badgeCode, stackable ? completedCount + 1 : Math.max(1, completedCount), firstGrantedAt, at, source);
        }
    }
}
