package io.loyaltyhub.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * wallet-service — libro mastro dei punti (docs/04 §3, docs/servizi/wallet-service.md). M1.4: applica gli
 * effetti {@code points.grant} (moltiplicatore di tier incluso), tiene saldi e movimenti, produce
 * {@code wallet.points.earned}. Lotti, scadenze, salita di livello, edizioni (M3) e saga di spesa (M4) dopo.
 */
@SpringBootApplication
public class WalletApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
