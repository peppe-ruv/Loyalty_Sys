package it.iren.loyalty.ingressadapters.dedup;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controllo duplicati (RF-02, RI-01): la chiave è unica per fonte; la tabella è ripulita dopo 24 mesi.
 * L'inserimento è atomico: due ricezioni concorrenti della stessa chiave producono un solo ACCEPTED.
 */
@Service
public class DedupService {
    private final JdbcTemplate jdbc;

    public DedupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean firstSeen(String idempotencyKey) {
        try {
            jdbc.update("INSERT INTO ingressadapters.seen_keys(idempotency_key, seen_at) VALUES (?, now())", idempotencyKey);
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
