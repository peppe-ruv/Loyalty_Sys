package io.loyaltyhub.common.demo;

/**
 * Un servizio che sa riportare il proprio stato ai seed (docs/10 §1.3, docs/06 §1).
 * {@code POST /v1/demo/reset} invoca tutti i {@code DemoResettable} registrati. Deve essere idempotente.
 */
public interface DemoResettable {

    /** Nome del componente resettato (per la risposta del reset). */
    String demoComponent();

    /** Riporta lo stato del servizio esattamente ai dati di {@code seed/}. */
    void resetToSeed();
}
