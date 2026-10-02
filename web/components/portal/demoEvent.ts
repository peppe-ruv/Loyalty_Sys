import { sourceUrn } from "@/lib/campaign/sources";

/**
 * CloudEvent del pannello demo per /v1/events. La fonte parte sempre come URN `urn:loyaltyhub:source:<codice>`:
 * l'ingestion rifiuta il codice breve (400, Q-258).
 */
export function buildDemoEvent(
  memberId: string,
  type: string,
  source: string,
  data: Record<string, unknown>,
  id: string,
  now: Date = new Date(),
) {
  return {
    specversion: "1.0",
    id,
    source: sourceUrn(source),
    type,
    subject: "member:" + memberId,
    time: now.toISOString(),
    data,
  };
}
