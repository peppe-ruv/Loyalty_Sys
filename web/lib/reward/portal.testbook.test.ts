import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import type { PortalCoupon, PortalReward, Redemption } from "@/lib/api/types";
import { COUPON_WORDS, bandProgress, blockReason, isSettled, redemptionWords, rewardBadge, sortCoupons } from "./portal";

// Testbook TB-WEB §RWD: catalogo e richieste nel portale (docs/09 PT-03, PT-04, PT-13; docs/03 §5 "Un premio visibile ma
// non raggiungibile si mostra con i punti mancanti").

const COST = 1500;
function reward(over: Partial<PortalReward>): PortalReward {
  return {
    code: "RWD-X",
    name: "Premio",
    type: "COUPON",
    imageUrl: null,
    category: null,
    pointsCost: COST,
    stockState: "AVAILABLE",
    lockedByTier: null,
    perMemberLimitReached: false,
    ...over,
  } as PortalReward;
}

// Tabella decisionale completa (3 × 2 × 2 × 3 = 36 ≤ 64): stock × riservato a tier × limite per membro × saldo vs costo.
// Oracolo (PT-04): si può richiedere solo se non esaurito, non riservato, limite non raggiunto e saldo ≥ costo; altrimenti
// il motivo del blocco è uno dei motivi applicabili ("Esaurito", "Riservato a GOLD", "Già richiesto", "Ti mancano N
// punti"). Con più motivi la spec non fissa quale mostrare: il test accetta uno qualunque di quelli applicabili.
const STOCK = ["AVAILABLE", "LOW", "SOLD_OUT"] as const;
const LOCK = [false, true];
const LIMIT = [false, true];
const BAL = [
  { label: "saldo 1.150 < costo", balance: 1150 },
  { label: "saldo = costo", balance: 1500 },
  { label: "saldo 1.501 > costo", balance: 1501 },
];
const table: { id: string; desc: string; r: PortalReward; balance: number; allowed: string[] }[] = [];
for (const stock of STOCK)
  for (const lock of LOCK)
    for (const limit of LIMIT)
      for (const b of BAL) {
        const allowed: string[] = [];
        if (stock === "SOLD_OUT") allowed.push("Esaurito");
        if (lock) allowed.push("Riservato a GOLD");
        if (limit) allowed.push("Già richiesto");
        if (b.balance < COST) allowed.push("Ti mancano 350 punti");
        table.push({
          id: `TB-WEB-RWD-${String(table.length + 1).padStart(3, "0")}`,
          desc: `stock ${stock}, ${lock ? "riservato a GOLD" : "per tutti"}, ${limit ? "limite raggiunto" : "limite libero"}, ${b.label} → ${allowed.length ? allowed.join(" | ") : "richiedibile"}`,
          r: reward({ stockState: stock, lockedByTier: lock ? { requiredTiers: ["GOLD"] } : null, perMemberLimitReached: limit }),
          balance: b.balance,
          allowed,
        });
      }

it.each(rows(table))("[%s] PT-04 motivo del blocco: %s", (_id, _desc, { r, balance, allowed }) => {
  const reason = blockReason(r, balance);
  if (allowed.length === 0) expect(reason).toBeNull();
  else expect(allowed).toContain(reason);
});

// PT-03 griglia (3 × 2 × 2 = 12): un'etichetta di stato tra quelle applicabili; LOW → "Ultimi pezzi".
const grid: { id: string; desc: string; r: PortalReward; allowed: string[] }[] = [];
for (const stock of STOCK)
  for (const lock of LOCK)
    for (const limit of LIMIT) {
      const allowed: string[] = [];
      if (stock === "SOLD_OUT") allowed.push("Esaurito");
      if (stock === "LOW") allowed.push("Ultimi pezzi");
      if (lock) allowed.push("Riservato a GOLD");
      if (limit) allowed.push("Già richiesto");
      grid.push({
        id: `TB-WEB-RWD-0${37 + grid.length}`,
        desc: `stock ${stock}, ${lock ? "riservato a GOLD" : "per tutti"}, ${limit ? "limite raggiunto" : "limite libero"} → ${allowed.length ? allowed.join(" | ") : "nessuna etichetta"}`,
        r: reward({ stockState: stock, lockedByTier: lock ? { requiredTiers: ["GOLD"] } : null, perMemberLimitReached: limit }),
        allowed,
      });
    }

it.each(rows(grid))("[%s] PT-03 etichetta: %s", (_id, _desc, { r, allowed }) => {
  const badge = rewardBadge(r);
  if (allowed.length === 0) expect(badge).toBeNull();
  else expect(allowed).toContain(badge);
});

it.each(
  rows([
    { id: "TB-WEB-RWD-049", desc: "saldo 1.150, soglia 1.500 → non raggiunta, mancano 350", balance: 1150, threshold: 1500, reached: false, missing: 350 },
    { id: "TB-WEB-RWD-050", desc: "saldo 1.499 (soglia − 1) → mancano 1", balance: 1499, threshold: 1500, reached: false, missing: 1 },
    { id: "TB-WEB-RWD-051", desc: "saldo = soglia → raggiunta, barra piena", balance: 1500, threshold: 1500, reached: true, missing: 0 },
    { id: "TB-WEB-RWD-052", desc: "saldo 1.501 (soglia + 1) → raggiunta, barra non oltre il 100 %", balance: 1501, threshold: 1500, reached: true, missing: 0 },
    { id: "TB-WEB-RWD-053", desc: "saldo 0 → mancano 1.500, barra vuota", balance: 0, threshold: 1500, reached: false, missing: 1500 },
  ]),
)("[%s] PT-03 fascia: %s", (_id, _desc, { balance, threshold, reached, missing }) => {
  const p = bandProgress(balance, threshold);
  expect([p.reached, p.missing]).toEqual([reached, missing]);
  expect(p.pct).toBeCloseTo(Math.min(100, (balance / threshold) * 100), 5);
});

it("[TB-WEB-RWD-054] fascia con soglia 0 → raggiunta, barra piena", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-RWD-054 — una fascia a 0 punti non è prevista dalla spec.
  expect(bandProgress(0, 0)).toEqual({ reached: true, missing: 0, pct: 100 });
});

it("[TB-WEB-RWD-055] punti mancanti col separatore delle migliaia → «Ti mancano 1.350 punti»", () => {
  expect(blockReason(reward({ pointsCost: 1500 }), 150)).toBe("Ti mancano 1.350 punti");
});

it("[TB-WEB-RWD-056] riservato a più livelli (GOLD, PLATINUM) → «Riservato a GOLD e PLATINUM»", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-RWD-056 — la spec mostra solo "Riservato a GOLD" (un livello).
  expect(blockReason(reward({ lockedByTier: { requiredTiers: ["GOLD", "PLATINUM"] } }), 5000)).toBe("Riservato a GOLD e PLATINUM");
});

it("[TB-WEB-RWD-057] lockedByTier con elenco vuoto → non riservato", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-RWD-057 — ramo senza specifica (lockedByTier presente ma senza livelli).
  expect(blockReason(reward({ lockedByTier: { requiredTiers: [] } }), 5000)).toBeNull();
});

type R = Pick<Redemption, "status" | "couponCode" | "fulfilmentNote" | "rejectReason">;
const red = (over: Partial<R>): R => ({ status: "PENDING", couponCode: null, fulfilmentNote: null, rejectReason: null, ...over }) as R;

it.each(
  rows([
    { id: "TB-WEB-RWD-058", desc: "PENDING → «in conferma»", r: red({ status: "PENDING" }), expected: "in conferma" },
    { id: "TB-WEB-RWD-059", desc: "CONFIRMED → «confermata»", r: red({ status: "CONFIRMED" }), expected: "confermata" },
    { id: "TB-WEB-RWD-060", desc: "FULFILLED fisico con nota di spedizione → «spedita»", r: red({ status: "FULFILLED", fulfilmentNote: "BRT 123" }), expected: "spedita" },
    { id: "TB-WEB-RWD-061", desc: "CANCELLED dall'assistenza (rimborso) → «annullata — punti restituiti»", r: red({ status: "CANCELLED", rejectReason: "CARE" }), expected: "annullata — punti restituiti" },
    { id: "TB-WEB-RWD-062", desc: "REJECTED per timeout → «non andata a buon fine»", r: red({ status: "REJECTED", rejectReason: "TIMEOUT" }), expected: "non andata a buon fine" },
    { id: "TB-WEB-RWD-063", desc: "REJECTED per saldo → «Punti non sufficienti» (PT-04 3b)", r: red({ status: "REJECTED", rejectReason: "INSUFFICIENT_BALANCE" }), expected: "punti non sufficienti" },
  ]),
)("[%s] PT-13 stato in parole: %s", (_id, _desc, { r, expected }) => {
  expect(redemptionWords(r).toLowerCase()).toBe(expected);
});

// TESTBOOK: ambiguo, vedi TB-WEB-RWD-064…066 — casi che l'elenco di PT-13 non nomina.
it.each(
  rows([
    { id: "TB-WEB-RWD-064", desc: "FULFILLED con coupon → «Coupon emesso»", r: red({ status: "FULFILLED", couponCode: "CAFE-1" }), expected: "Coupon emesso" },
    { id: "TB-WEB-RWD-065", desc: "FULFILLED senza nota né coupon → «Completata»", r: red({ status: "FULFILLED" }), expected: "Completata" },
    { id: "TB-WEB-RWD-066", desc: "CANCELLED dal membro (da PENDING, nessun addebito) → «Annullata da te»", r: red({ status: "CANCELLED", rejectReason: "MEMBER" }), expected: "Annullata da te" },
  ]),
)("[%s] PT-13 stato in parole: %s", (_id, _desc, { r, expected }) => {
  expect(redemptionWords(r)).toBe(expected);
});

// PT-04 flusso: 3a CONFIRMED/FULFILLED → esito positivo (per i coupon il codice), 3b REJECTED → negativo.
it.each(
  rows([
    { id: "TB-WEB-RWD-067", desc: "PENDING → si continua ad attendere", status: "PENDING", coupon: false, attention: false, expected: false },
    { id: "TB-WEB-RWD-068", desc: "CONFIRMED premio fisico → esito positivo", status: "CONFIRMED", coupon: false, attention: false, expected: true },
    { id: "TB-WEB-RWD-069", desc: "CONFIRMED coupon senza codice → si attende il codice", status: "CONFIRMED", coupon: true, attention: false, expected: false },
    { id: "TB-WEB-RWD-071", desc: "FULFILLED → esito", status: "FULFILLED", coupon: true, attention: false, expected: true },
    { id: "TB-WEB-RWD-072", desc: "REJECTED → esito negativo", status: "REJECTED", coupon: false, attention: false, expected: true },
    { id: "TB-WEB-RWD-073", desc: "CANCELLED → esito", status: "CANCELLED", coupon: false, attention: false, expected: true },
  ]),
)("[%s] PT-04 attesa: %s", (_id, _desc, { status, coupon, attention, expected }) => {
  expect(isSettled({ status: status as Redemption["status"], needsAttention: attention }, coupon)).toBe(expected);
});

it("[TB-WEB-RWD-070] CONFIRMED coupon da verificare (needsAttention, pool vuoto) → si smette di attendere", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-RWD-070 — PT-04 non tratta il coupon che non arriva perché il pool è vuoto.
  expect(isSettled({ status: "CONFIRMED", needsAttention: true }, true)).toBe(true);
});

const coupon = (code: string, status: PortalCoupon["status"], expiresAt: string | null) => ({ code, status, expiresAt }) as PortalCoupon;

it("[TB-WEB-RWD-074] PT-13 coupon: gli attivi prima, i non attivi in fondo", () => {
  const out = sortCoupons([coupon("U", "USED", "2026-10-01"), coupon("A", "ISSUED", "2026-12-01"), coupon("E", "EXPIRED", "2026-01-01")]);
  expect(out.map((c) => c.code)[0]).toBe("A");
  expect(out.slice(1).every((c) => c.status !== "ISSUED")).toBe(true);
});

it("[TB-WEB-RWD-075] PT-13 coupon attivi: scadenza più vicina in cima", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-RWD-075 — PT-13 non fissa l'ordine fra i coupon attivi.
  const out = sortCoupons([coupon("B", "ISSUED", "2026-12-01"), coupon("A", "ISSUED", "2026-10-01")]);
  expect(out.map((c) => c.code)).toEqual(["A", "B"]);
});

it("[TB-WEB-RWD-076] PT-13 stato del coupon in parole: attivo, usato, scaduto", () => {
  expect([COUPON_WORDS.ISSUED, COUPON_WORDS.USED, COUPON_WORDS.EXPIRED].map((w) => w.toLowerCase())).toEqual(["attivo", "usato", "scaduto"]);
});
