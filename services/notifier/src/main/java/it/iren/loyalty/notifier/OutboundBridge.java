package it.iren.loyalty.notifier;

import it.iren.loyalty.common.event.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Ponte verso CRM, marketing automation e data platform (RI-06): qui solo il consumer; i connettori sono adattatori per destinazione. */
@Component
public class OutboundBridge {
    private static final Logger log = LoggerFactory.getLogger(OutboundBridge.class);

    @KafkaListener(topics = {EventTypes.TOPIC_MOVEMENTS, EventTypes.TOPIC_TIERS, EventTypes.TOPIC_REDEMPTIONS, EventTypes.TOPIC_CONTESTS})
    public void forward(byte[] payload) {
        log.debug("outbound event {} bytes", payload.length);
    }
}
