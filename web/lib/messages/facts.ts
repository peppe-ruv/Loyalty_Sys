import type { SampleEvent } from "./types";

// Catalogo dei fatti per BO-19 (docs/05 §5; stessi tipi ammessi da engagement, RuleAdminService.FACT_TYPES): etichetta
// in italiano e un `data` campione con i campi del contratto (contracts/events/fact/*.schema.json). Serve a tre cose:
// scegliere il tipo di fatto di una regola, suggerire i segnaposto {{data.*}} e costruire l'evento campione
// dell'anteprima (POST /v1/message-templates/{code}/render). `message.delivered` è escluso (eviterebbe cicli).

export type FieldKind = "text" | "number" | "date";

export interface FactInfo {
  type: string;
  label: string;
  /** Payload di esempio: chiavi = campi del contratto, valori plausibili per l'anteprima. */
  sample: Record<string, unknown>;
}

const F = (type: string, label: string, sample: Record<string, unknown>): FactInfo => ({ type, label, sample });

export const FACTS: FactInfo[] = [
  F("member.registered", "Iscrizione al programma", { memberId: "MBR-000002", firstName: "Marco", status: "ACTIVE", channel: "WEB", registeredAt: "2026-09-15T10:00:00Z", city: "Torino", referralCode: "MARCO-7K2" }),
  F("member.updated", "Profilo aggiornato", { memberId: "MBR-000002", firstName: "Marco", status: "ACTIVE", city: "Torino" }),
  F("member.status.changed", "Cambio di stato del membro", { previousStatus: "ACTIVE", newStatus: "BLOCKED", reason: "Verifica in corso" }),
  F("member.profile.completed", "Profilo completato", { memberId: "MBR-000002" }),
  F("member.birthday", "Compleanno", { age: 39 }),
  F("member.segment.entered", "Ingresso in un segmento", { segmentCode: "SEG-DIGITAL" }),
  F("member.segment.left", "Uscita da un segmento", { segmentCode: "SEG-DIGITAL" }),
  F("referral.completed", "Invito completato", { role: "REFERRER", counterpartMemberId: "MBR-000009", qualifyingActionId: "01J8ZS0A1B2C3D4E5F6G7H8J9K" }),
  F("campaign.evaluated", "Campagne valutate", { actionId: "01J8ZS0A1B2C3D4E5F6G7H8J9K", actionType: "purchase.completed", matched: [], skipped: [] }),
  F("campaign.status.changed", "Cambio di stato di una campagna", { campaignCode: "CMP-PURCHASE-BASE", name: "Punti sugli acquisti", previousStatus: "PAUSED", newStatus: "LIVE" }),
  F("wallet.points.earned", "Punti guadagnati", { ledgerEntryId: "LED-000001", campaignCode: "CMP-PURCHASE-BASE", currency: "PTS", baseAmount: 130, tierCode: "SILVER", tierMultiplier: 1.25, amount: 162, balanceAfter: 2512, expiresAt: "2027-12-31T22:59:59Z", pending: false }),
  F("wallet.points.spent", "Punti spesi", { ledgerEntryId: "LED-000002", currency: "PTS", amount: 1500, balanceAfter: 1012, redemptionId: "RDM-000001" }),
  F("wallet.spend.rejected", "Spesa di punti rifiutata", { redemptionId: "RDM-000001", reason: "INSUFFICIENT_BALANCE", requested: 1500, available: 1150 }),
  F("wallet.points.refunded", "Punti restituiti", { redemptionId: "RDM-000001", amount: 1500, balanceAfter: 2512 }),
  F("wallet.points.expired", "Punti scaduti", { currency: "PTS", amount: 300, balanceAfter: 2212 }),
  F("wallet.points.expiring", "Punti in scadenza", { currency: "PTS", amount: 1900, expiresAt: "2026-10-31T22:59:59Z" }),
  F("wallet.points.adjusted", "Rettifica di punti", { direction: "CREDIT", currency: "PTS", amount: 200, reason: "GOODWILL", note: "Scuse per il disservizio", balanceAfter: 2712 }),
  F("wallet.points.released", "Punti resi disponibili", { currency: "PTS", amount: 400, balanceAfter: 2912 }),
  F("tier.upgraded", "Salita di livello", { previousTier: "SILVER", newTier: "GOLD", periodSts: 3020 }),
  F("tier.downgraded", "Discesa di livello", { previousTier: "GOLD", newTier: "SILVER", editionCode: "ED-2026" }),
  F("tier.retained", "Livello mantenuto", { tier: "GOLD", editionCode: "ED-2026" }),
  F("edition.closed", "Edizione chiusa", { editionCode: "ED-2026", retained: 9, downgraded: 2 }),
  F("reward.redemption.requested", "Premio richiesto", { redemptionId: "RDM-000001", rewardCode: "RWD-COFFEE-5", rewardName: "Buono caffè 5 €", currency: "PTS", pointsCost: 500 }),
  F("reward.redemption.confirmed", "Richiesta premio confermata", { redemptionId: "RDM-000001", rewardCode: "RWD-COFFEE-5", rewardName: "Buono caffè 5 €", pointsCost: 500 }),
  F("reward.redemption.fulfilled", "Premio evaso", { redemptionId: "RDM-000001", rewardCode: "RWD-COFFEE-5", couponCode: "CAFE-7Q2M-K9", note: "" }),
  F("reward.redemption.rejected", "Richiesta premio rifiutata", { redemptionId: "RDM-000001", reason: "INSUFFICIENT_BALANCE" }),
  F("reward.redemption.cancelled", "Richiesta premio annullata", { redemptionId: "RDM-000001", reason: "Annullata dal membro", refund: true, pointsCost: 500 }),
  F("coupon.issued", "Coupon emesso", { couponCode: "CAFE-7Q2M-K9", rewardCode: "RWD-COFFEE-5", expiresAt: "2026-12-31T22:59:59Z", origin: "CAMPAIGN" }),
  F("coupon.used", "Coupon usato", { couponCode: "CAFE-7Q2M-K9", rewardCode: "RWD-COFFEE-5" }),
  F("contest.plays.granted", "Giocate ottenute", { contestCode: "IW-AUTUNNO", count: 1, effectId: "4D7E1A9C0B2F3E5D6C8A7B9E1F", campaignCode: "CMP-SURVEY" }),
  F("contest.played", "Giocata effettuata", { contestCode: "IW-AUTUNNO", playId: "PLY-000001", outcome: "LOSE", playsAvailable: 2 }),
  F("contest.won", "Vincita instant win", { contestCode: "IW-AUTUNNO", playId: "PLY-000001", prizeCode: "COFFEE", prizeName: "Buono caffè 5 €", prizeType: "COUPON", rewardCode: "RWD-COFFEE-5" }),
  F("achievement.progressed", "Progresso di un obiettivo", { achievementCode: "ACH-3-PURCHASES-MONTH", periodKey: "2026-09", value: 2, target: 3 }),
  F("achievement.completed", "Obiettivo completato", { achievementCode: "ACH-3-PURCHASES-MONTH", achievementName: "Tris del mese", periodKey: "2026-09" }),
  F("badge.awarded", "Badge ottenuto", { badgeCode: "BDG-TRIS", badgeName: "Tris", origin: "ACHIEVEMENT" }),
  F("content.status.changed", "Cambio di stato di un contenuto", { contentId: "01J8ZS0A1B2C3D4E5F6G7H8J9K", contentCode: "CNT-HERO-AUTUNNO", kind: "CARD", previousStatus: "DRAFT", newStatus: "LIVE" }),
];

const BY_TYPE = new Map(FACTS.map((f) => [f.type, f]));

export function factInfo(type: string): FactInfo | undefined {
  return BY_TYPE.get(type);
}

export function factLabel(type: string): string {
  return BY_TYPE.get(type)?.label ?? type;
}

/**
 * Campi dell'effetto `message.send` (EVT-EFF-05) visibili come {{data.*}} quando il template è usato da una campagna
 * con SEND_MESSAGE: engagement unisce i campi dell'effetto e i `params` della campagna (docs/03 §3.4).
 */
export function effectSample(campaignCode: string, templateCode: string, params: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    effectId: "4D7E1A9C0B2F3E5D6C8A7B9E1F",
    campaignCode,
    actionId: "01J8ZS0A1B2C3D4E5F6G7H8J9K",
    actionType: "member.birthday",
    templateCode,
    ...params,
  };
}

/** Sorgente dell'anteprima: un tipo di fatto oppure una campagna che invia il template (SEND_MESSAGE). */
export type PreviewSource = { kind: "fact"; factType: string } | { kind: "campaign"; campaignCode: string; params?: Record<string, unknown> };

/** Evento campione per `POST …/render`: CloudEvent con `subject` sul membro scelto. */
export function sampleEvent(source: PreviewSource, templateCode: string, memberId: string, now: Date = new Date()): SampleEvent {
  const isFact = source.kind === "fact";
  return {
    id: "01PREVIEW00000000000000000",
    type: isFact ? `io.loyaltyhub.fact.${source.factType}` : "io.loyaltyhub.effect.message.send",
    subject: `member:${memberId}`,
    time: now.toISOString(),
    source: isFact ? "urn:loyaltyhub:service:preview" : "urn:loyaltyhub:service:campaign",
    data: isFact ? { ...(factInfo(source.factType)?.sample ?? {}) } : effectSample(source.campaignCode, templateCode, source.params),
  };
}

const DATE_FIELD = /(At|Date)$/;

/** Natura di un campo campione, per proporre il formattatore giusto (`|number`, `|date`). */
export function fieldKind(name: string, value: unknown): FieldKind {
  if (typeof value === "number") return "number";
  if (DATE_FIELD.test(name) && typeof value === "string") return "date";
  return "text";
}

export interface Placeholder {
  /** Testo da inserire, es. `{{data.amount|number}}`. */
  token: string;
  /** Da quale sorgente arriva (per il raggruppamento dei suggerimenti). */
  group: "data" | "member" | "event";
}

/** Segnaposto sempre disponibili (contesto `{data, member, event}` di engagement, docs/servizi §5). */
export const COMMON_PLACEHOLDERS: Placeholder[] = [
  { token: "{{member.firstName}}", group: "member" },
  { token: "{{member.tierCode}}", group: "member" },
  { token: "{{event.time|date}}", group: "event" },
];

/**
 * Segnaposto suggeriti per un template: i campi `data.*` delle sorgenti che lo usano (tipi di fatto delle regole,
 * campagne con SEND_MESSAGE), senza duplicati e nell'ordine di comparsa, più quelli comuni.
 */
export function placeholdersFor(sources: PreviewSource[], templateCode = ""): Placeholder[] {
  const seen = new Set<string>();
  const out: Placeholder[] = [];
  for (const s of sources) {
    const sample = s.kind === "fact" ? factInfo(s.factType)?.sample ?? {} : effectSample(s.campaignCode, templateCode, s.params);
    for (const [name, value] of Object.entries(sample)) {
      if (Array.isArray(value) || (value !== null && typeof value === "object")) continue;
      const kind = fieldKind(name, value);
      const token = `{{data.${name}${kind === "number" ? "|number" : kind === "date" ? "|date" : ""}}}`;
      if (seen.has(token)) continue;
      seen.add(token);
      out.push({ token, group: "data" });
    }
  }
  return [...out, ...COMMON_PLACEHOLDERS];
}
