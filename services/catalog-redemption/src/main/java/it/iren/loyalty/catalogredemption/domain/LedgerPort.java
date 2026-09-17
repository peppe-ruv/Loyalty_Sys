package it.iren.loyalty.catalogredemption.domain;

/**
 * Porta verso il ledger (CLAUDE.md §7): il riscatto addebita e, se poi qualcosa va storto, storna.
 * L'implementazione REST sta nella configurazione del servizio; qui il dominio vede due sole
 * operazioni, il che rende provabile la compensazione.
 */
public interface LedgerPort {

    /** Saldo insufficiente: il ledger ha rifiutato l'addebito. */
    class InsufficientBalance extends RuntimeException {
        public InsufficientBalance(String message) { super(message); }
    }

    /** Scala unità al membro; solleva {@link InsufficientBalance} se il saldo non basta. */
    void debit(String memberId, String actionKey, long units, String reason);

    /** Storna i movimenti di quella chiave. È idempotente: un secondo storno non fa nulla (RI-08). */
    void reverse(String actionKey);
}
