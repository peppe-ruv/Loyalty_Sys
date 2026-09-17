package io.loyaltyhub.ledger;

import io.loyaltyhub.common.event.Currency;
import io.loyaltyhub.common.test.PostgresIntegrationTest;
import io.loyaltyhub.ledger.domain.LedgerService;
import io.loyaltyhub.ledger.domain.Movement;
import io.loyaltyhub.ledger.domain.Repositories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Comportamenti del ledger che dipendono dal database: idempotenza garantita dall'indice univoco,
 * saldo che non scende sotto zero, registro append-only, outbox scritta nella stessa transazione
 * (RF-04, RF-15, RF-87, RI-08).
 */
@SpringBootTest(properties = "ledger.outbox.relay-ms=3600000")
class LedgerServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired LedgerService ledger;
    @Autowired Repositories.MovementRepository movements;
    @Autowired Repositories.OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    /** Un membro diverso per test: il container è condiviso e i saldi sono per membro. */
    private String member() {
        return "m-" + UUID.randomUUID();
    }

    /** Saldo letto dal database: il metodo del repository prende un lock e richiederebbe una transazione. */
    private long available(String memberId) {
        Long value = jdbc.queryForObject(
                "SELECT coalesce(max(available), 0) FROM ledger.balance WHERE member_id = ? AND currency = ?",
                Long.class, memberId, Currency.PREMIO.code());
        return value == null ? 0L : value;
    }

    @Test
    void accreditaEScriveLOutboxNellaStessaTransazione() {
        String memberId = member();
        long outboxBefore = outbox.count();

        ledger.post(memberId, "azione-1", List.of(new LedgerService.Posting(Currency.PREMIO, 984, "TEST", null, null)));

        assertThat(available(memberId)).isEqualTo(984);
        assertThat(outbox.count()).isEqualTo(outboxBefore + 1);
    }

    @Test
    void laStessaChiaveSulloStessoWalletNonAccreditaDueVolte() {
        String memberId = member();
        var posting = List.of(new LedgerService.Posting(Currency.PREMIO, 100, "TEST", null, null));

        ledger.post(memberId, "azione-2", posting);
        List<Movement> second = ledger.post(memberId, "azione-2", posting);

        assertThat(second).isEmpty();
        assertThat(available(memberId)).isEqualTo(100);
        assertThat(movements.findByActionKey("azione-2")).hasSize(1);
    }

    @Test
    void lAddebitoOltreIlDisponibileVieneRifiutatoEIlSaldoNonCambia() {
        String memberId = member();
        ledger.post(memberId, "azione-3", List.of(new LedgerService.Posting(Currency.PREMIO, 50, "TEST", null, null)));

        assertThatThrownBy(() -> ledger.debit(memberId, "spesa-3", 80, "TEST"))
                .isInstanceOf(LedgerService.InsufficientBalanceException.class);

        assertThat(available(memberId)).isEqualTo(50);
    }

    @Test
    void loStornoRiportaIlSaldoAlValorePrecedente() {
        String memberId = member();
        ledger.post(memberId, "azione-4", List.of(new LedgerService.Posting(Currency.PREMIO, 300, "TEST", null, null)));

        List<Movement> reversed = ledger.reverse("azione-4", "RESO");

        assertThat(reversed).hasSize(1);
        assertThat(available(memberId)).isZero();
    }

    @Test
    void dueCampagnePremianoLoStessoWalletEIlSecondoAccreditoNonSiPerde() {
        String memberId = member();
        // Ogni campagna ha la sua chiave derivata dall'azione: prima erano la stessa e il secondo
        // accredito veniva scartato in silenzio (decision-service) o violava il vincolo (rules-engine).
        ledger.post(memberId, "azione-6:campagna-A", List.of(new LedgerService.Posting(Currency.PREMIO, 200, "RULE:A", null, null)));
        ledger.post(memberId, "azione-6:campagna-B", List.of(new LedgerService.Posting(Currency.PREMIO, 50, "RULE:B", null, null)));

        assertThat(available(memberId)).isEqualTo(250);
    }

    @Test
    void loStornoDellAzioneRitrovaGliAccreditiDiTutteLeCampagne() {
        String memberId = member();
        ledger.post(memberId, "azione-7:campagna-A", List.of(new LedgerService.Posting(Currency.PREMIO, 200, "RULE:A", null, null)));
        ledger.post(memberId, "azione-7:campagna-B", List.of(new LedgerService.Posting(Currency.PREMIO, 50, "RULE:B", null, null)));

        List<Movement> reversed = ledger.reverse("azione-7", "RESO");

        assertThat(reversed).hasSize(2);
        assertThat(available(memberId)).isZero();
    }

    @Test
    void loStornoRipetutoNonRistorna() {
        String memberId = member();
        ledger.post(memberId, "azione-8:campagna-A", List.of(new LedgerService.Posting(Currency.PREMIO, 120, "RULE:A", null, null)));
        ledger.reverse("azione-8", "RESO");

        List<Movement> again = ledger.reverse("azione-8", "RESO");

        assertThat(again).isEmpty();
        assertThat(available(memberId)).isZero();
    }

    @Test
    void loStornoNonTocaIMovimentiDiScadenza() {
        String memberId = member();
        // Accredito già scaduto: il job di scadenza emette un movimento EXPIRY con chiave derivata.
        ledger.post(memberId, "azione-9", List.of(new LedgerService.Posting(
                Currency.PREMIO, 70, "TEST", null, java.time.Instant.now().minusSeconds(60))));
        ledger.expire(java.time.Instant.now());
        assertThat(available(memberId)).isZero();

        List<Movement> reversed = ledger.reverse("azione-9", "RESO");

        // Un solo storno, quello dell'accredito: la scadenza non è un movimento da stornare.
        assertThat(reversed).hasSize(1);
        assertThat(movements.findByActionKey("azione-9:EXPIRY")).hasSize(1);
    }

    @Test
    void ilRegistroEAppendOnlyAnchePerChiScriveSql() {
        String memberId = member();
        ledger.post(memberId, "azione-5", List.of(new LedgerService.Posting(Currency.PREMIO, 10, "TEST", null, null)));

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger.movement SET amount = 999999 WHERE action_key = ?", "azione-5"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger.movement WHERE action_key = ?", "azione-5"))
                .hasMessageContaining("append-only");
    }
}
