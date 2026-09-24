import type { Role } from "@/lib/persona/personas";
import type { ApprovalItem, EntityType, PolicyView } from "./types";

// Coda approvazioni di BO-21 (docs/08 §BO-21): aggregazione lato web delle tre code, ciascuna con il proprio stato
// (una fonte addormentata non blocca le altre). Logica pura, testata a parte.

export interface SourceResult {
  entityType: EntityType;
  items: ApprovalItem[] | undefined;
  failed: boolean;
}

/**
 * Unisce le fonti, dal più vecchio in attesa (chi aspetta da più tempo viene prima). Nell'hub consolidato le tre fonti
 * rispondono dallo stesso processo con la coda completa: si deduplica per tipo e id.
 */
export function mergeQueues(sources: SourceResult[]): ApprovalItem[] {
  const seen = new Map<string, ApprovalItem>();
  for (const i of sources.flatMap((s) => s.items ?? [])) seen.set(`${i.entityType}:${i.id}`, i);
  return [...seen.values()]
    .sort((a, b) => (a.submittedAt ?? "").localeCompare(b.submittedAt ?? "") || a.code.localeCompare(b.code));
}

/** «Da approvare»: gli oggetti in revisione che il ruolo può decidere (il ruolo della policy, oppure ADMIN). */
export function toApproveBy(items: ApprovalItem[], role: Role): ApprovalItem[] {
  return items.filter(
    (i) => i.status === "IN_REVIEW" && (role === "ADMIN" || (i.requiredRole ?? "LEGAL") === role),
  );
}

/** «Inviate da me»: dal più recente. */
export function sentByMe(items: ApprovalItem[]): ApprovalItem[] {
  return [...items].sort((a, b) => (b.submittedAt ?? "").localeCompare(a.submittedAt ?? ""));
}

/** Esito leggibile di un oggetto inviato: in attesa, approvato, rifiutato (con commento). */
export function outcomeOf(i: ApprovalItem): { label: string; tone: "wait" | "ok" | "ko" } {
  if (i.status === "IN_REVIEW") return { label: "In attesa", tone: "wait" };
  if (i.decision === "REJECT") return { label: "Rifiutato", tone: "ko" };
  if (i.decision === "APPROVE") return { label: i.status === "LIVE" ? "Approvato e pubblicato" : "Approvato", tone: "ok" };
  return { label: i.status, tone: "wait" };
}

/** La pubblicazione diretta da DRAFT è ammessa? (docs/06 §7) — senza policy nota, la barra mostra entrambe le vie. */
export function requiresApproval(
  entityType: EntityType,
  policy: PolicyView | undefined,
  campaign?: { requiresLegal?: boolean; budgetPoints?: number | null },
): boolean | undefined {
  if (!policy) return undefined;
  if (!policy.enabled) return false;
  if (entityType !== "CAMPAIGN") return true;
  return Boolean(campaign?.requiresLegal) || (campaign?.budgetPoints ?? 0) > policy.campaignBudgetThreshold;
}

/** "RUOLO:username" → "username (RUOLO)". */
export function formatActor(actor: string | null): string {
  if (!actor) return "—";
  const [role, user] = actor.split(":");
  return user ? `${user} (${role})` : actor;
}
