import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import type { Role } from "@/lib/persona/personas";
import { formatActor, mergeQueues, outcomeOf, requiresApproval, sentByMe, toApproveBy } from "./queue";
import type { ApprovalItem, EntityType, PolicyView } from "./types";

// Testbook TB-WEB §APR: policy delle approvazioni (docs/06 §7, Q-08: soglia 100 000 punti) e coda di BO-21
// (docs/08 §BO-21, F-APR-01…03).

const POLICY: PolicyView = { enabled: true, campaignBudgetThreshold: 100_000, rows: [] };
const OFF: PolicyView = { ...POLICY, enabled: false };

// Tabella decisionale completa CAMPAIGN: requiresLegal {true, false, assente} × budget {assente, 99 999, 100 000, 100 001}.
const LEGAL = [true, false, undefined] as const;
const BUDGET = [null, 99_999, 100_000, 100_001] as const;
const table = LEGAL.flatMap((requiresLegal, li) =>
  BUDGET.map((budgetPoints, bi) => {
    const expected = requiresLegal === true || (budgetPoints ?? 0) > 100_000;
    return {
      id: `TB-WEB-APR-${String(li * BUDGET.length + bi + 1).padStart(3, "0")}`,
      desc: `CAMPAIGN requiresLegal=${requiresLegal ?? "assente"}, budget=${budgetPoints ?? "assente"} → approvazione ${expected ? "richiesta" : "non richiesta"}`,
      requiresLegal,
      budgetPoints,
      expected,
    };
  }),
);

it.each(rows(table))("[%s] %s", (_id, _desc, { requiresLegal, budgetPoints, expected }) => {
  expect(requiresApproval("CAMPAIGN", POLICY, { requiresLegal, budgetPoints })).toBe(expected);
});

it.each(
  rows([
    { id: "TB-WEB-APR-013", desc: "REWARD con policy attiva → sempre richiesta (LEGAL)", type: "REWARD" as EntityType, policy: POLICY, expected: true },
    { id: "TB-WEB-APR-014", desc: "CONTEST con policy attiva → sempre richiesta (LEGAL)", type: "CONTEST" as EntityType, policy: POLICY, expected: true },
    { id: "TB-WEB-APR-015", desc: "CAMPAIGN requiresLegal e budget 200 000 con approval.enabled=false → DRAFT → LIVE diretto", type: "CAMPAIGN" as EntityType, policy: OFF, expected: false },
    { id: "TB-WEB-APR-016", desc: "REWARD con approval.enabled=false → DRAFT → LIVE diretto", type: "REWARD" as EntityType, policy: OFF, expected: false },
    { id: "TB-WEB-APR-017", desc: "CONTEST con approval.enabled=false → DRAFT → LIVE diretto", type: "CONTEST" as EntityType, policy: OFF, expected: false },
  ]),
)("[%s] %s", (_id, _desc, { type, policy, expected }) => {
  expect(requiresApproval(type, policy, { requiresLegal: true, budgetPoints: 200_000 })).toBe(expected);
});

it("[TB-WEB-APR-018] policy non ancora nota → indeciso (undefined)", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-APR-018 — docs/06 §7 non prevede la policy ignota lato UI (vedi LIFE-041…045).
  expect(requiresApproval("CAMPAIGN", undefined, { requiresLegal: true })).toBeUndefined();
});

it("[TB-WEB-APR-019] soglia letta dalla policy, non fissa: soglia 50 000, budget 60 000 → richiesta", () => {
  expect(requiresApproval("CAMPAIGN", { ...POLICY, campaignBudgetThreshold: 50_000 }, { requiresLegal: false, budgetPoints: 60_000 })).toBe(true);
});

function item(over: Partial<ApprovalItem>): ApprovalItem {
  return {
    entityType: "REWARD",
    id: "r1",
    code: "RWD-X",
    name: "Premio X",
    status: "IN_REVIEW",
    submittedBy: "MARKETING:luca.marketing",
    submittedAt: "2026-09-18T08:00:00Z",
    requiredRole: "LEGAL",
    reason: null,
    summary: null,
    decidedBy: null,
    decidedAt: null,
    decision: null,
    comment: null,
    ...over,
  };
}

// docs/06 §7: "APPROVE/REJECT ⇒ ruolo della policy o ADMIN"; BO-21 "Da approvare (per il ruolo corrente)".
it.each(
  rows(
    (["ADMIN", "MARKETING", "LEGAL", "CARE", "ANALYST"] as Role[]).map((role, i) => ({
      id: `TB-WEB-APR-0${20 + i}`,
      desc: `«Da approvare» per ${role} con oggetto IN_REVIEW che richiede LEGAL → ${role === "ADMIN" || role === "LEGAL" ? "presente" : "assente"}`,
      role,
      expected: role === "ADMIN" || role === "LEGAL" ? 1 : 0,
    })),
  ),
)("[%s] %s", (_id, _desc, { role, expected }) => {
  expect(toApproveBy([item({})], role)).toHaveLength(expected);
});

it("[TB-WEB-APR-025] ruolo richiesto assente → vale LEGAL (unico ruolo approvatore della policy)", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-APR-025 — il formato comune (docs/06 §7) ha sempre requiredRole; se manca, LEGAL.
  const i = item({ requiredRole: null });
  expect([toApproveBy([i], "LEGAL").length, toApproveBy([i], "MARKETING").length]).toEqual([1, 0]);
});

it("[TB-WEB-APR-026] oggetto non più IN_REVIEW (APPROVED) → fuori da «Da approvare» anche per ADMIN", () => {
  expect(toApproveBy([item({ status: "APPROVED" })], "ADMIN")).toEqual([]);
});

it("[TB-WEB-APR-027] ruolo richiesto dalla policy diverso da LEGAL (CARE) → lo vede CARE, non LEGAL", () => {
  const i = item({ requiredRole: "CARE" });
  expect([toApproveBy([i], "CARE").length, toApproveBy([i], "LEGAL").length]).toEqual([1, 0]);
});

it("[TB-WEB-APR-028] hub consolidato: stesso oggetto dalle tre fonti → una sola riga", () => {
  const i = item({});
  const merged = mergeQueues([
    { entityType: "CAMPAIGN", items: [i], failed: false },
    { entityType: "REWARD", items: [i], failed: false },
    { entityType: "CONTEST", items: [i], failed: false },
  ]);
  expect(merged).toHaveLength(1);
});

it("[TB-WEB-APR-029] stesso id ma tipo diverso → due righe", () => {
  const merged = mergeQueues([
    { entityType: "CAMPAIGN", items: [item({ entityType: "CAMPAIGN", id: "x" })], failed: false },
    { entityType: "REWARD", items: [item({ entityType: "REWARD", id: "x" })], failed: false },
  ]);
  expect(merged).toHaveLength(2);
});

it("[TB-WEB-APR-030] ordine: chi aspetta da più tempo prima, a parità per codice", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-APR-030 — BO-21 non fissa l'ordine della coda.
  const merged = mergeQueues([
    {
      entityType: "REWARD",
      items: [
        item({ id: "b", code: "B", submittedAt: "2026-09-18T09:00:00Z" }),
        item({ id: "c", code: "C", submittedAt: "2026-09-18T08:00:00Z" }),
        item({ id: "a", code: "A", submittedAt: "2026-09-18T08:00:00Z" }),
      ],
      failed: false,
    },
  ]);
  expect(merged.map((m) => m.code)).toEqual(["A", "C", "B"]);
});

it("[TB-WEB-APR-031] una fonte addormentata non blocca le altre", () => {
  const merged = mergeQueues([
    { entityType: "CAMPAIGN", items: undefined, failed: true },
    { entityType: "REWARD", items: [item({ id: "r1" })], failed: false },
    { entityType: "CONTEST", items: [item({ entityType: "CONTEST", id: "k1" })], failed: false },
  ]);
  expect(merged.map((m) => `${m.entityType}:${m.id}`).sort()).toEqual(["CONTEST:k1", "REWARD:r1"]);
});

it("[TB-WEB-APR-032] «Inviate da me»: dal più recente", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-APR-032 — BO-21 non fissa l'ordine.
  const out = sentByMe([item({ id: "old", submittedAt: "2026-09-01T00:00:00Z" }), item({ id: "new", submittedAt: "2026-09-18T00:00:00Z" })]);
  expect(out.map((i) => i.id)).toEqual(["new", "old"]);
});

// TESTBOOK: ambiguo, vedi TB-WEB-APR-033…037 — BO-21 non fissa le parole dell'esito nella scheda «Inviate da me».
it.each(
  rows([
    { id: "TB-WEB-APR-033", desc: "IN_REVIEW → «In attesa»", over: { status: "IN_REVIEW" }, expected: "In attesa" },
    { id: "TB-WEB-APR-034", desc: "rifiutato (torna DRAFT) → «Rifiutato»", over: { status: "DRAFT", decision: "REJECT" as const }, expected: "Rifiutato" },
    { id: "TB-WEB-APR-035", desc: "approvato (APPROVED) → «Approvato»", over: { status: "APPROVED", decision: "APPROVE" as const }, expected: "Approvato" },
    { id: "TB-WEB-APR-036", desc: "approvato e già LIVE → «Approvato e pubblicato»", over: { status: "LIVE", decision: "APPROVE" as const }, expected: "Approvato e pubblicato" },
    { id: "TB-WEB-APR-037", desc: "nessuna decisione, stato DRAFT → codice di stato", over: { status: "DRAFT" }, expected: "DRAFT" },
  ]),
)("[%s] esito %s", (_id, _desc, { over, expected }) => {
  expect(outcomeOf(item(over)).label).toBe(expected);
});

// TESTBOOK: ambiguo, vedi TB-WEB-APR-038…040 — docs/08 §3.6 descrive ActorStamp ("Luca Serra · MARKETING · 3 min fa");
// per la colonna «inviato da» di BO-21 non c'è un formato.
it.each(
  rows([
    { id: "TB-WEB-APR-038", desc: "«LEGAL:elena.legal» → «elena.legal (LEGAL)»", actor: "LEGAL:elena.legal" as string | null, expected: "elena.legal (LEGAL)" },
    { id: "TB-WEB-APR-039", desc: "attore assente → «—»", actor: null, expected: "—" },
    { id: "TB-WEB-APR-040", desc: "attore senza «:» («system») → «system»", actor: "system", expected: "system" },
  ]),
)("[%s] formatActor %s", (_id, _desc, { actor, expected }) => {
  expect(formatActor(actor)).toBe(expected);
});
