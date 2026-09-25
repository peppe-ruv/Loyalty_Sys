import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import { describeCampaign, type CampaignDraft, type ConditionNode, type EffectSpec } from "./describe";

// Testbook TB-WEB §DESC: frase generata di BO-06/BO-21 (docs/08 §BO-06): «rilegge la regola in italiano a ogni
// modifica», esempio: "Quando arriva **Acquisto completato** da ecommerce o app, se **importo ≥ 50 €** e il membro è
// **GOLD o PLATINUM**, assegna **1 giocata a Ruota d'Autunno**, al massimo **1 volta al giorno**."
// Oracolo: l'esempio (parola per parola) e, dove l'esempio tace, le proprietà che ne discendono: ogni parametro della
// regola compare nella frase, in italiano (nessun codice interno di comparatore o modalità).

const base: CampaignDraft = { triggerActionTypes: ["purchase.completed"], effects: [{ type: "GRANT_POINTS", mode: "FIXED", value: 10, currency: "PTS" }] };
const d = (over: Partial<CampaignDraft> & Record<string, unknown>): CampaignDraft => ({ ...base, ...over }) as CampaignDraft;
const leaf = (field: string, cmp: string, value?: unknown): ConditionNode => ({ field, cmp, value });

it("[TB-WEB-DESC-001] esempio completo della spec, parola per parola", () => {
  const draft = d({
    sources: ["ecommerce", "app"],
    conditions: { op: "all", rules: [leaf("data.amount", "gte", 50)] },
    audience: { tiers: ["GOLD", "PLATINUM"] },
    effects: [{ type: "GRANT_PLAYS", count: 1, contestCode: "IW-AUTUNNO" }],
    limits: { perMember: [{ max: 1, period: "DAY" }] },
    contestNames: { "IW-AUTUNNO": "Ruota d'Autunno" },
  });
  expect(describeCampaign(draft)).toBe(
    "Quando arriva **Acquisto completato** da ecommerce o app, se **importo ≥ 50 €** e il membro è **GOLD o PLATINUM**, assegna **1 giocata a Ruota d'Autunno**, al massimo **1 volta al giorno**.",
  );
});

it("[TB-WEB-DESC-002] un trigger noto → «Quando arriva **Acquisto completato**»", () => {
  expect(describeCampaign(d({}))).toMatch(/^Quando arriva \*\*Acquisto completato\*\*, /);
});

it("[TB-WEB-DESC-003] due trigger → uniti da «o»", () => {
  expect(describeCampaign(d({ triggerActionTypes: ["purchase.completed", "review.submitted"] }))).toContain("**Acquisto completato o Recensione inviata**");
});

it("[TB-WEB-DESC-004] tipo custom col nome fornito dal chiamante (BO-09) → il nome, non il codice", () => {
  expect(describeCampaign(d({ triggerActionTypes: ["store.visit"], actionLabels: { "store.visit": "Visita in negozio" } }))).toContain("**Visita in negozio**");
});

it("[TB-WEB-DESC-005] tipo sconosciuto senza nome → il codice", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-DESC-005 — nessuna fonte dice come nominare un tipo senza nome noto.
  expect(describeCampaign(d({ triggerActionTypes: ["store.visit"] }))).toContain("**store.visit**");
});

it("[TB-WEB-DESC-006] nessun trigger → «Quando arriva un'azione»", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-DESC-006 — bozza incompleta: la spec non dà la frase.
  expect(describeCampaign(d({ triggerActionTypes: [] }))).toMatch(/^Quando arriva un'azione, /);
});

it("[TB-WEB-DESC-007] fonti ammesse (ecommerce, app) → «da ecommerce o app»", () => {
  expect(describeCampaign(d({ sources: ["ecommerce", "app"] }))).toContain("**Acquisto completato** da ecommerce o app");
});

it("[TB-WEB-DESC-008] importo ≥ 50 → «**importo ≥ 50 €**» (importo in euro)", () => {
  expect(describeCampaign(d({ conditions: leaf("data.amount", "gte", 50) }))).toContain("se **importo ≥ 50 €**");
});

// Comparatori (docs/03 §3.3): la spec dà solo "≥"; per gli altri la frase deve contenere campo e valori e nessun codice.
const CMP: [string, string, unknown, string[]][] = [
  ["eq", "context.hour", 9, ["9"]],
  ["neq", "context.hour", 9, ["9"]],
  ["gt", "context.hour", 9, ["9"]],
  ["lt", "context.hour", 9, ["9"]],
  ["lte", "context.hour", 9, ["9"]],
  ["in", "context.dayOfWeek", ["SAT", "SUN"], ["SAT", "SUN"]],
  ["nin", "context.dayOfWeek", ["SAT", "SUN"], ["SAT", "SUN"]],
  ["contains", "member.labels", "ebill", ["ebill"]],
  ["ncontains", "member.labels", "ebill", ["ebill"]],
  ["exists", "data.coupon", undefined, []],
  ["nexists", "data.coupon", undefined, []],
  ["between", "context.hour", [9, 18], ["9", "18"]],
  ["startsWith", "data.sku", "ECO-", ["ECO-"]],
];
it.each(
  rows(
    CMP.map(([cmp, field, value, values], i) => ({
      id: `TB-WEB-DESC-0${String(9 + i).padStart(2, "0")}`,
      desc: `comparatore ${cmp} su ${field} → campo e valori in italiano, senza il codice «${cmp}»`,
      cmp,
      field,
      value,
      values,
    })),
  ),
)("[%s] %s", (_id, _desc, { cmp, field, value, values }) => {
  const s = describeCampaign(d({ conditions: leaf(field, cmp, value) }));
  const cond = /se \*\*(.*?)\*\*/.exec(s)?.[1] ?? "";
  for (const v of values) expect(cond).toContain(v);
  expect(cond.split(/\s+/)).not.toContain(cmp);
  expect(cond).toContain(field.split(".").pop() === "hour" ? "ora" : field === "context.dayOfWeek" ? "giorno" : field.split(".").pop()!);
});
// DESC-009 … DESC-021 (13 comparatori).

it("[TB-WEB-DESC-022] gruppo TUTTE → condizioni unite da «e»", () => {
  expect(describeCampaign(d({ conditions: { op: "all", rules: [leaf("context.hour", "gte", 9), leaf("context.hour", "lt", 18)] } }))).toContain(
    "se **ora ≥ 9** e **ora < 18**",
  );
});

it("[TB-WEB-DESC-023] gruppo ALMENO UNA → «(a oppure b)»", () => {
  expect(describeCampaign(d({ conditions: { op: "any", rules: [leaf("context.dayOfWeek", "eq", "SAT"), leaf("context.dayOfWeek", "eq", "SUN")] } }))).toContain(
    "se (**giorno = SAT** oppure **giorno = SUN**)",
  );
});

it("[TB-WEB-DESC-024] gruppo NESSUNA con due righe → «non (a e b)» come le valuta il motore (Q-90)", () => {
  expect(describeCampaign(d({ conditions: { op: "not", rules: [leaf("member.tier", "eq", "BASE"), leaf("member.status", "eq", "BLOCKED")] } }))).toContain(
    "se non (**livello = BASE** e **stato = BLOCKED**)",
  );
});

it("[TB-WEB-DESC-025] gruppo vuoto → nessun «se»", () => {
  expect(describeCampaign(d({ conditions: { op: "all", rules: [] } }))).not.toContain(" se ");
});

it("[TB-WEB-DESC-026] gruppi annidati (TUTTE › ALMENO UNA) → «a e (b oppure c)»", () => {
  const conditions: ConditionNode = {
    op: "all",
    rules: [leaf("data.amount", "gte", 50), { op: "any", rules: [leaf("member.tier", "eq", "GOLD"), leaf("member.tier", "eq", "PLATINUM")] }],
  };
  expect(describeCampaign(d({ conditions }))).toMatch(/se \*\*importo ≥ 50( €)?\*\* e \(\*\*livello = GOLD\*\* oppure \*\*livello = PLATINUM\*\*\)/);
});

it.each(
  rows([
    { id: "TB-WEB-DESC-027", desc: "pubblico «tutti» → nessuna frase sul pubblico", audience: { all: true, tiers: [], segments: [] }, has: null as string | null },
    // Q-210 DECISA: con all=true gli elenchi non vuoti restringono comunque, quindi la frase li riporta.
    { id: "TB-WEB-DESC-027", desc: "all=true con tier GOLD → «il membro è **GOLD**» (Q-210)", audience: { all: true, tiers: ["GOLD"] }, has: "se il membro è **GOLD**" },
    { id: "TB-WEB-DESC-028", desc: "pubblico GOLD o PLATINUM → «il membro è **GOLD o PLATINUM**»", audience: { tiers: ["GOLD", "PLATINUM"] }, has: "se il membro è **GOLD o PLATINUM**" },
    // Q-211 DECISA: senza elenchi e all non vero il pubblico è vuoto (la campagna non scatta per nessuno).
    { id: "TB-WEB-DESC-029", desc: "pubblico con elenchi vuoti → «il pubblico è **vuoto**» (Q-211)", audience: { tiers: [], segments: [] }, has: "se il pubblico è **vuoto** (non scatta per nessuno)" },
  ]),
)("[%s] %s", (_id, _desc, { audience, has }) => {
  const s = describeCampaign(d({ audience }));
  if (has) expect(s).toContain(has);
  else expect(s).not.toContain(" se ");
});

it("[TB-WEB-DESC-030] pubblico per segmenti → «è nel segmento **SEG-1 o SEG-2**»", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-DESC-030 — la spec non dà la frase dei segmenti (oggi codici, non nomi).
  expect(describeCampaign(d({ audience: { segments: ["SEG-1", "SEG-2"] } }))).toContain("se è nel segmento **SEG-1 o SEG-2**");
});

it("[TB-WEB-DESC-031] condizioni e pubblico insieme → prima le condizioni, poi il pubblico (come nell'esempio)", () => {
  expect(describeCampaign(d({ conditions: leaf("context.hour", "gte", 9), audience: { tiers: ["GOLD"] } }))).toContain("se **ora ≥ 9** e il membro è **GOLD**");
});

const eff = (e: EffectSpec | EffectSpec[]) => describeCampaign(d({ effects: Array.isArray(e) ? e : [e] }));

it("[TB-WEB-DESC-032] GRANT_POINTS FIXED 300 PTS → «assegna **300 PTS**»", () => {
  expect(eff({ type: "GRANT_POINTS", mode: "FIXED", value: 300, currency: "PTS" })).toContain("assegna **300 PTS**");
});

it("[TB-WEB-DESC-033] GRANT_POINTS PER_AMOUNT 1 STS ogni 1 € → valore, valuta e passo in euro", () => {
  expect(eff({ type: "GRANT_POINTS", mode: "PER_AMOUNT", value: 1, unitStep: 1, currency: "STS" })).toContain("assegna **1 STS ogni 1 €**");
});

it("[TB-WEB-DESC-034] GRANT_POINTS FROM_FIELD da data.points → la frase nomina il campo sorgente", () => {
  expect(eff({ type: "GRANT_POINTS", mode: "FROM_FIELD", currency: "PTS", amountField: "data.points" } as EffectSpec)).toMatch(/points/);
});

it("[TB-WEB-DESC-035] GRANT_POINTS LOOKUP su data.plan → la frase nomina il campo della tabella", () => {
  expect(eff({ type: "GRANT_POINTS", mode: "LOOKUP", currency: "PTS", amountField: "data.plan", lookup: { BASIC: 100 } } as EffectSpec)).toMatch(/plan/);
});

it("[TB-WEB-DESC-036] MULTIPLIER ×2 su PTS → «**PTS ×2**»", () => {
  expect(eff({ type: "MULTIPLIER", factor: 2, currency: "PTS" })).toContain("**PTS ×2**");
});

it("[TB-WEB-DESC-037] GRANT_PLAYS 2 giocate → «2 giocate» (plurale)", () => {
  expect(eff({ type: "GRANT_PLAYS", count: 2, contestCode: "IW-AUTUNNO" })).toContain("2 giocate");
});

// TESTBOOK: ambiguo, vedi TB-WEB-DESC-038…042 — la spec non dà la frase di questi effetti (oggi senza il codice del
// premio o del badge).
it.each(
  rows([
    { id: "TB-WEB-DESC-038", desc: "ISSUE_COUPON → «**un coupon**»", e: { type: "ISSUE_COUPON", rewardCode: "RWD-COFFEE-5" } as EffectSpec, has: "assegna **un coupon**" },
    { id: "TB-WEB-DESC-039", desc: "AWARD_BADGE → «**un badge**»", e: { type: "AWARD_BADGE", badgeCode: "BDG-FIRST" } as EffectSpec, has: "assegna **un badge**" },
    { id: "TB-WEB-DESC-040", desc: "SEND_MESSAGE con template → «**il messaggio MSG-WELCOME**»", e: { type: "SEND_MESSAGE", templateCode: "MSG-WELCOME" } as EffectSpec, has: "assegna **il messaggio MSG-WELCOME**" },
    { id: "TB-WEB-DESC-041", desc: "SEND_MESSAGE senza template → «**un messaggio**»", e: { type: "SEND_MESSAGE" } as EffectSpec, has: "assegna **un messaggio**" },
    { id: "TB-WEB-DESC-042", desc: "tipo di effetto sconosciuto → il codice", e: { type: "DONATE" } as EffectSpec, has: "assegna **DONATE**" },
  ]),
)("[%s] %s", (_id, _desc, { e, has }) => {
  expect(eff(e)).toContain(has);
});

it("[TB-WEB-DESC-043] nessun effetto → «**nessun effetto**»", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-DESC-043 — bozza incompleta.
  expect(describeCampaign(d({ effects: [] }))).toContain(", **nessun effetto**");
});

it("[TB-WEB-DESC-044] più effetti → uniti da «e» nell'ordine dell'elenco", () => {
  expect(eff([{ type: "GRANT_POINTS", mode: "FIXED", value: 100, currency: "PTS" }, { type: "GRANT_POINTS", mode: "FIXED", value: 50, currency: "STS" }])).toContain(
    "assegna **100 PTS** e **50 STS**",
  );
});

it("[TB-WEB-DESC-045] limite 1 al giorno → «al massimo **1 volta al giorno**»", () => {
  expect(describeCampaign(d({ limits: { perMember: [{ max: 1, period: "DAY" }] } }))).toContain("al massimo **1 volta al giorno**");
});

it("[TB-WEB-DESC-046] limite 2 alla settimana → «**2 volte alla settimana**» (plurale)", () => {
  expect(describeCampaign(d({ limits: { perMember: [{ max: 2, period: "WEEK" }] } }))).toContain("**2 volte alla settimana**");
});

it.each(
  rows([
    { id: "TB-WEB-DESC-047", desc: "periodo MONTH → «al mese»", period: "MONTH", has: "al mese" },
    { id: "TB-WEB-DESC-048", desc: "periodo EDITION → «per edizione»", period: "EDITION", has: "per edizione" },
    { id: "TB-WEB-DESC-049", desc: "periodo ALWAYS («sempre») → «in totale»", period: "ALWAYS", has: "in totale" },
  ]),
)("[%s] limite: %s", (_id, _desc, { period, has }) => {
  expect(describeCampaign(d({ limits: { perMember: [{ max: 3, period }] } }))).toContain(`${has}**.`);
});

it("[TB-WEB-DESC-050] nessun limite → nessun «al massimo»", () => {
  expect(describeCampaign(d({ limits: { perMember: [] } }))).not.toContain("al massimo");
});

it("[TB-WEB-DESC-051] più limiti per membro → solo il primo nella frase", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-DESC-051 — l'esempio ha un solo limite.
  const s = describeCampaign(d({ limits: { perMember: [{ max: 1, period: "DAY" }, { max: 5, period: "MONTH" }] } }));
  expect(s).toContain("al giorno");
  expect(s).not.toContain("al mese");
});

it("[TB-WEB-DESC-052] frase sempre chiusa dal punto finale", () => {
  expect(describeCampaign({})).toMatch(/\.$/);
});
