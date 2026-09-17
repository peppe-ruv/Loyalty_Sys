package it.iren.loyalty.ledger.domain;

import java.util.List;
import java.util.Optional;

/** Porta verso il backoffice: wallet configurati (RF-87). L'implementazione di default conosce solo PREMIO e STATUS. */
public interface WalletTypeSource {
    List<WalletType> all();
    default Optional<WalletType> byCode(String code) { return all().stream().filter(w -> w.code().equals(code)).findFirst(); }
}
