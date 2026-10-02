import { it } from "@/lib/i18n/it";
import { actionLabel } from "@/lib/campaign/describe";

// Etichette italiane dei tipi evento per BO-25 (issue #204): le azioni riusano actionLabel(), fatti ed effetti il
// dizionario `it.traces.events`. Un tipo dei contratti senza etichetta fa fallire eventLabels.test.ts.

const L = it.traces.events;

/** Nome leggibile di un nodo del tracciato, dato famiglia e tipo breve; ripiego: il codice. */
export function eventLabel(family: string, shortType: string): string {
  switch (family) {
    case "ACTION": {
      const label = actionLabel(shortType);
      return label !== shortType ? label : (L.fact[shortType] ?? shortType);
    }
    case "EFFECT":
      return L.effect[shortType] ?? shortType;
    case "AUDIT":
      return L.audit;
    case "DLQ":
      return L.dlq;
    default:
      return L.fact[shortType] ?? (actionLabel(shortType) !== shortType ? actionLabel(shortType) : shortType);
  }
}

/**
 * Tipi «solo tecnici»: aggiornano lo stato interno ma non danno nulla al membro. Un tracciato che parte da uno di
 * questi e non ha esito è nascosto di default nell'elenco (interruttore «Nascondi eventi solo tecnici»).
 */
export const TECH_ONLY_TYPES: ReadonlySet<string> = new Set([
  "member.segment.entered",
  "member.segment.left",
  "member.updated",
  "member.updated.v2",
  "campaign.status.changed",
  "content.status.changed",
  "contest.status.changed",
  "reward.status.changed",
  "edition.closed",
  "entry",
]);
