package io.loyaltyhub.notifier.delivery;

import java.time.Instant;
import java.util.Map;

/**
 * Adattatore di canale (RF-132, omnicanalità): la piattaforma decide, il canale esegue. Ogni canale (APP inbox,
 * EMAIL/SMS/PUSH via fornitori, WEBHOOK/CRM, OPERATOR) implementa la stessa porta; aggiungere un canale non tocca il
 * decision-service. Il recapito reale (email, numero) resta nel CRM (ADR-012): qui viaggia solo l'id membro.
 */
public interface ChannelAdapter {
    /** Codice canale come appare nelle policy e nel routing: app, email, sms, push, webhook, operator, web. */
    String channel();

    Result deliver(Delivery delivery);

    record Delivery(String deliveryId, String memberId, String decisionId, String action, String reference, String channel,
                    String subject, String body, Map<String, Object> params, Instant expiresAt, String correlationId) {}

    /** status: SENT (messaggio inviato), PRESENTED (offerta mostrata/inbox), QUEUED (coda operatore), FAILED, SKIPPED. */
    record Result(String status, String detail, String providerRef) {
        public static Result sent(String ref) { return new Result("SENT", null, ref); }
        public static Result presented(String ref) { return new Result("PRESENTED", null, ref); }
        public static Result queued(String ref) { return new Result("QUEUED", null, ref); }
        public static Result failed(String why) { return new Result("FAILED", why, null); }
        public boolean ok() { return "SENT".equals(status) || "PRESENTED".equals(status) || "QUEUED".equals(status); }
    }
}
