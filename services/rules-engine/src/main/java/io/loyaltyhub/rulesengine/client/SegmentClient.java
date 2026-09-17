package io.loyaltyhub.rulesengine.client;

import java.util.Set;

/** Porta verso segment-service: segmenti correnti del membro per il targeting delle regole (RF-65). */
public interface SegmentClient {
    Set<String> segmentsOf(String memberId);
}
