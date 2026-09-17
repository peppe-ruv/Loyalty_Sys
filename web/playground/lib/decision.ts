/**
 * Motore decisionale del playground.
 *
 * **Non è il motore.** Quello vero è `DecisionEngine` in `services/decision-service` e resta
 * l'unica autorità: qui c'è una riproduzione fedele ma ridotta, che serve a far vedere *come*
 * si decide — vincoli con codice motivo, punteggio, arbitrato delle prime azioni, budget
 * giornaliero — senza avviare quattordici servizi.
 *
 * Le regole riprodotte, nell'ordine in cui il motore le applica:
 * 1. azione non abilitata dalla policy → `ACTION_DISABLED`;
 * 2. rischio del membro oltre il massimo dell'azione → `RISK_LEVEL`;
 * 3. azioni **contrattuali** (punti, livello, badge, attributi, eventi): sempre applicate, mai
 *    arbitrate e mai limitate dal budget — l'AI non tocca punti e saldi (RF-130);
 * 4. rischio oltre la soglia di blocco → `RISK_BLOCK` per tutto il resto;
 * 5. consenso mancante → `CONSENT_MISSING`; ore di silenzio → `QUIET_HOURS`;
 *    tetto di contatti per canale → `NO_CHANNEL`;
 * 6. punteggio (valore × moltiplicatore del livello − costo + propensione), ordine decrescente;
 * 7. budget giornaliero di unità del programma → `UNITS_BUDGET` a chi non ci sta più;
 * 8. oltre il numero massimo di azioni per evento → `OUTRANKED`.
 */

export const CONTRACTUAL_ACTIONS = ['AWARD_POINTS', 'UPGRADE_TIER', 'GRANT_BADGE', 'SET_ATTRIBUTE', 'EMIT_EVENT'] as const;

export const RISK_ORDER = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type RiskLevel = (typeof RISK_ORDER)[number];

export type ActionType =
  | (typeof CONTRACTUAL_ACTIONS)[number]
  | 'ISSUE_REWARD'
  | 'ISSUE_COUPON'
  | 'SHOW_OFFER'
  | 'SEND_MESSAGE'
  | 'TRIGGER_CAMPAIGN'
  | 'ASK_FOR_FEEDBACK';

export interface Candidate {
  id: string;
  action: ActionType;
  reference: string;
  /** Unità concesse dall'azione: consumano il budget giornaliero del programma. */
  units: number;
  value: number;
  cost: number;
  /** Canali ammessi dall'azione; vuoto = l'azione non ha bisogno di un canale. */
  channels: string[];
  /** Finalità di consenso richiesta per contattare il membro. */
  requiredConsent?: string;
  maxRiskLevel: RiskLevel;
  enabled: boolean;
  priority: number;
  source: string;
}

export interface MemberProfile {
  id: string;
  label: string;
  tier: 'BASE' | 'PLUS' | 'TOP';
  riskLevel: RiskLevel;
  consents: Record<string, boolean>;
  /** Contatti ricevuti negli ultimi 7 giorni per canale: è la pressione commerciale. */
  contacts7d: Record<string, number>;
  preferredChannel: string;
  propensity: number;
  churnRisk: number;
}

export interface Policy {
  id: string;
  version: string;
  maxArbitratedPerEvent: number;
  /** Tetto giornaliero di unità del programma; 0 = nessun tetto. */
  dailyUnitsBudget: number;
  contactCap7dByChannel: Record<string, number>;
  quietHoursFrom: number;
  quietHoursTo: number;
  blockRiskLevel: RiskLevel;
  tierBoost: Record<MemberProfile['tier'], number>;
  channelOrder: string[];
}

export interface Chosen {
  candidate: Candidate;
  channel: string | null;
  score: number;
  reasons: string[];
  contractual: boolean;
}

export interface Rejected {
  candidate: Candidate;
  reasonCode: string;
  detail: string;
}

export interface Decision {
  chosen: Chosen[];
  rejected: Rejected[];
  unitsBefore: number;
  unitsAfter: number;
}

const MESSAGING: ActionType[] = ['SEND_MESSAGE', 'ASK_FOR_FEEDBACK'];

function isContractual(action: ActionType): boolean {
  return (CONTRACTUAL_ACTIONS as readonly string[]).includes(action);
}

function riskAtLeast(level: RiskLevel, threshold: RiskLevel): boolean {
  return RISK_ORDER.indexOf(level) >= RISK_ORDER.indexOf(threshold);
}

function riskAbove(level: RiskLevel, max: RiskLevel): boolean {
  return RISK_ORDER.indexOf(level) > RISK_ORDER.indexOf(max);
}

function inQuietHours(policy: Policy, hour: number): boolean {
  const { quietHoursFrom: from, quietHoursTo: to } = policy;
  return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
}

/** Primo canale ammesso che non ha già esaurito il tetto di contatti a 7 giorni. */
function channelFor(candidate: Candidate, member: MemberProfile, policy: Policy): string | null {
  if (candidate.channels.length === 0) return null;
  const order = [member.preferredChannel, ...policy.channelOrder].filter((channel, index, all) => all.indexOf(channel) === index);
  for (const channel of order) {
    if (!candidate.channels.includes(channel)) continue;
    const cap = policy.contactCap7dByChannel[channel];
    const used = member.contacts7d[channel] ?? 0;
    if (cap !== undefined && used >= cap) continue;
    return channel;
  }
  return null;
}

function score(candidate: Candidate, member: MemberProfile, policy: Policy, reasons: string[]): number {
  const boost = policy.tierBoost[member.tier];
  const base = candidate.value * boost - candidate.cost + member.propensity * candidate.value;
  reasons.push(`valore ${String(candidate.value)} × ${String(boost)} (livello ${member.tier})`);
  reasons.push(`costo ${String(candidate.cost)}`);
  reasons.push(`propensione ${member.propensity.toFixed(2)}`);
  let total = base;
  if (member.churnRisk > 0.5 && !isContractual(candidate.action)) {
    total += member.churnRisk * candidate.value;
    reasons.push(`rischio abbandono ${member.churnRisk.toFixed(2)}`);
  }
  return Math.round(total * 100) / 100;
}

export function decide(
  candidates: Candidate[],
  member: MemberProfile,
  policy: Policy,
  options: { hour: number; unitsGrantedToday: number },
): Decision {
  const chosen: Chosen[] = [];
  const rejected: Rejected[] = [];
  const scored: Chosen[] = [];
  const blocked = riskAtLeast(member.riskLevel, policy.blockRiskLevel);

  for (const candidate of candidates) {
    if (!candidate.enabled) {
      rejected.push({ candidate, reasonCode: 'ACTION_DISABLED', detail: `azione non abilitata nella policy ${policy.id}` });
      continue;
    }
    if (riskAbove(member.riskLevel, candidate.maxRiskLevel)) {
      rejected.push({ candidate, reasonCode: 'RISK_LEVEL', detail: `rischio ${member.riskLevel} oltre ${candidate.maxRiskLevel}` });
      continue;
    }
    if (isContractual(candidate.action)) {
      chosen.push({ candidate, channel: null, score: candidate.priority, reasons: ['ALWAYS_APPLY'], contractual: true });
      continue;
    }
    if (blocked) {
      rejected.push({ candidate, reasonCode: 'RISK_BLOCK', detail: `rischio ${member.riskLevel}: solo azioni contrattuali` });
      continue;
    }
    if (candidate.requiredConsent !== undefined && member.consents[candidate.requiredConsent] !== true) {
      rejected.push({ candidate, reasonCode: 'CONSENT_MISSING', detail: `manca il consenso «${candidate.requiredConsent}»` });
      continue;
    }
    if (MESSAGING.includes(candidate.action) && inQuietHours(policy, options.hour)) {
      rejected.push({
        candidate,
        reasonCode: 'QUIET_HOURS',
        detail: `ore di silenzio ${String(policy.quietHoursFrom)}–${String(policy.quietHoursTo)}`,
      });
      continue;
    }
    const channel = channelFor(candidate, member, policy);
    if (channel === null && candidate.channels.length > 0) {
      rejected.push({ candidate, reasonCode: 'NO_CHANNEL', detail: 'nessun canale disponibile entro i tetti di contatto' });
      continue;
    }
    const reasons: string[] = [];
    scored.push({ candidate, channel, score: score(candidate, member, policy, reasons), reasons, contractual: false });
  }

  scored.sort((a, b) => b.score - a.score || b.candidate.priority - a.candidate.priority);

  let spent = options.unitsGrantedToday;
  let taken = 0;
  for (const item of scored) {
    if (taken >= policy.maxArbitratedPerEvent) {
      rejected.push({ candidate: item.candidate, reasonCode: 'OUTRANKED', detail: `punteggio ${String(item.score)} inferiore alle azioni scelte` });
      continue;
    }
    if (policy.dailyUnitsBudget > 0 && item.candidate.units > 0 && spent + item.candidate.units > policy.dailyUnitsBudget) {
      rejected.push({
        candidate: item.candidate,
        reasonCode: 'UNITS_BUDGET',
        detail: `budget giornaliero ${String(policy.dailyUnitsBudget)} unità: ${String(spent)} già concesse, ne servono ${String(item.candidate.units)}`,
      });
      continue;
    }
    spent += item.candidate.units;
    taken += 1;
    chosen.push(item);
  }

  return { chosen, rejected, unitsBefore: options.unitsGrantedToday, unitsAfter: spent };
}
