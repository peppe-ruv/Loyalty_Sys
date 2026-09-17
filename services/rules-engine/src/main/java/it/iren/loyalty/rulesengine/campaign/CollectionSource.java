package it.iren.loyalty.rulesengine.campaign;

/** Porta verso le collezioni di valori del backoffice (RF-100): liste riutilizzabili di SKU, città, email, ecc. */
public interface CollectionSource {
    boolean contains(String collection, String value);
}
