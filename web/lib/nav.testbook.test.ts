import { expect, it } from "vitest";
import { NAV, activeHref, visibleNav } from "./nav";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §NAV: sidebar del backoffice (docs/08 §1). Oracolo: la tabella Gruppo · Voce · ID · Route · M di
// docs/08 §1 trascritta qui (BO-03 e BO-06 sono pagine di dettaglio, non voci). Una riga = un caso.

const SPEC: { id: string; bo: string; group: string; label: string; href: string; m: number }[] = [
  { id: "TB-WEB-NAV-001", bo: "BO-01", group: "Panoramica", label: "Dashboard", href: "/backoffice", m: 2 },
  { id: "TB-WEB-NAV-002", bo: "BO-02", group: "Clienti", label: "Membri", href: "/backoffice/members", m: 1 },
  { id: "TB-WEB-NAV-003", bo: "BO-04", group: "Clienti", label: "Segmenti", href: "/backoffice/segments", m: 6 },
  { id: "TB-WEB-NAV-004", bo: "BO-05", group: "Programma", label: "Campagne", href: "/backoffice/campaigns", m: 1 },
  { id: "TB-WEB-NAV-005", bo: "BO-07", group: "Programma", label: "Livelli", href: "/backoffice/program/tiers", m: 3 },
  { id: "TB-WEB-NAV-006", bo: "BO-08", group: "Programma", label: "Valute ed edizioni", href: "/backoffice/program/currencies", m: 3 },
  { id: "TB-WEB-NAV-007", bo: "BO-09", group: "Programma", label: "Azioni e fonti", href: "/backoffice/program/actions", m: 1 },
  { id: "TB-WEB-NAV-008", bo: "BO-10", group: "Premi", label: "Catalogo", href: "/backoffice/rewards", m: 4 },
  { id: "TB-WEB-NAV-009", bo: "BO-11", group: "Premi", label: "Fasce", href: "/backoffice/rewards/bands", m: 4 },
  { id: "TB-WEB-NAV-010", bo: "BO-12", group: "Premi", label: "Coupon", href: "/backoffice/rewards/coupons", m: 4 },
  { id: "TB-WEB-NAV-011", bo: "BO-13", group: "Premi", label: "Richieste premio", href: "/backoffice/rewards/redemptions", m: 4 },
  { id: "TB-WEB-NAV-012", bo: "BO-14", group: "Gioco", label: "Concorsi", href: "/backoffice/game/contests", m: 5 },
  { id: "TB-WEB-NAV-013", bo: "BO-15", group: "Gioco", label: "Obiettivi e badge", href: "/backoffice/game/achievements", m: 5 },
  { id: "TB-WEB-NAV-014", bo: "BO-16", group: "Gioco", label: "Classifiche", href: "/backoffice/game/leaderboards", m: 5 },
  { id: "TB-WEB-NAV-015", bo: "BO-17", group: "Gioco", label: "Referral", href: "/backoffice/game/referral", m: 5 },
  { id: "TB-WEB-NAV-016", bo: "BO-18", group: "Contenuti", label: "Card e pop-up", href: "/backoffice/content", m: 6 },
  { id: "TB-WEB-NAV-017", bo: "BO-19", group: "Contenuti", label: "Messaggi", href: "/backoffice/content/messages", m: 6 },
  { id: "TB-WEB-NAV-018", bo: "BO-20", group: "Contenuti", label: "Tema e brand", href: "/backoffice/content/theme", m: 6 },
  { id: "TB-WEB-NAV-019", bo: "BO-21", group: "Governance", label: "Approvazioni", href: "/backoffice/governance/approvals", m: 7 },
  { id: "TB-WEB-NAV-020", bo: "BO-22", group: "Governance", label: "Audit", href: "/backoffice/governance/audit", m: 2 },
  { id: "TB-WEB-NAV-021", bo: "BO-23", group: "Governance", label: "Webhook", href: "/backoffice/governance/webhooks", m: 7 },
  { id: "TB-WEB-NAV-022", bo: "BO-24", group: "Osservabilità", label: "Flusso live", href: "/backoffice/observe/live", m: 2 },
  { id: "TB-WEB-NAV-023", bo: "BO-25", group: "Osservabilità", label: "Tracciati", href: "/backoffice/observe/traces", m: 2 },
  { id: "TB-WEB-NAV-024", bo: "BO-26", group: "Osservabilità", label: "Monitor ingressi", href: "/backoffice/observe/inbound", m: 1 },
  { id: "TB-WEB-NAV-025", bo: "BO-27", group: "Osservabilità", label: "DLQ", href: "/backoffice/observe/dlq", m: 7 },
  { id: "TB-WEB-NAV-026", bo: "BO-28", group: "Demo", label: "Simulatore eventi", href: "/backoffice/demo/simulator", m: 1 },
  { id: "TB-WEB-NAV-027", bo: "BO-29", group: "Demo", label: "Scenari", href: "/backoffice/demo/scenarios", m: 2 },
  { id: "TB-WEB-NAV-028", bo: "BO-30", group: "Demo", label: "Console demo", href: "/backoffice/demo/console", m: 1 },
];

const findItem = (bo: string) => {
  for (const g of NAV) {
    const item = g.items.find((i) => i.id === bo);
    if (item) return { group: g.label, ...item };
  }
  return undefined;
};

it.each(rows(SPEC.map((s) => ({ ...s, desc: `voce ${s.bo}: gruppo «${s.group}», etichetta «${s.label}», route ${s.href}, milestone M${s.m}` }))))(
  "[%s] %s",
  (_id, _desc, { bo, group, label, href, m }) => {
  const item = findItem(bo);
  expect({ group: item?.group, label: item?.label, href: item?.href, milestone: item?.milestone }).toEqual({
    group,
    label,
    href,
    milestone: m,
  });
  },
);

const ids = (realized: number) => visibleNav(realized).flatMap((g) => g.items.map((i) => i.id));
const specIds = (realized: number) => SPEC.filter((s) => s.m <= realized).map((s) => s.bo).sort();

it("[TB-WEB-NAV-029] milestone realizzata 0 → nessuna voce né gruppo", () => {
  expect(visibleNav(0)).toEqual([]);
});

it("[TB-WEB-NAV-030] milestone 1 → solo le 6 voci M1, i gruppi senza voci non compaiono", () => {
  expect(ids(1).sort()).toEqual(specIds(1));
  expect(visibleNav(1).map((g) => g.label)).toEqual(["Clienti", "Programma", "Osservabilità", "Demo"]);
});

it("[TB-WEB-NAV-031] milestone 6 (M7 − 1) → BO-21, BO-23, BO-27 assenti", () => {
  expect(ids(6)).not.toEqual(expect.arrayContaining(["BO-21"]));
  expect(ids(6).filter((i) => ["BO-21", "BO-23", "BO-27"].includes(i))).toEqual([]);
  expect(ids(6).sort()).toEqual(specIds(6));
});

it("[TB-WEB-NAV-032] milestone 7 → tutte le 28 voci", () => {
  expect(ids(7).sort()).toEqual(specIds(7));
  expect(ids(7)).toHaveLength(28);
});

it("[TB-WEB-NAV-033] milestone 8 (oltre l'ultima) → ancora le stesse 28 voci, nessuna in più", () => {
  expect(ids(8).sort()).toEqual(specIds(8));
});

it("[TB-WEB-NAV-034] sidebar di default (M1–M7 realizzate secondo docs/14) → tutte le 28 voci", () => {
  expect(visibleNav().flatMap((g) => g.items.map((i) => i.id)).sort()).toEqual(specIds(7));
});

// Voce attiva (docs/08 §1 "voce attiva con barra teal").
it.each(
  rows([
  { id: "TB-WEB-NAV-035", path: "/backoffice/members", expected: "/backoffice/members", desc: "route esatta" },
  { id: "TB-WEB-NAV-036", path: "/backoffice/members/MBR-000002", expected: "/backoffice/members", desc: "dettaglio BO-03 accende Membri" },
  { id: "TB-WEB-NAV-037", path: "/backoffice/rewards/bands", expected: "/backoffice/rewards/bands", desc: "prefisso più lungo: Fasce, non Catalogo" },
  { id: "TB-WEB-NAV-038", path: "/backoffice/content/messages", expected: "/backoffice/content/messages", desc: "Messaggi, non Card e pop-up" },
  { id: "TB-WEB-NAV-039", path: "/backoffice/campaigns/new", expected: "/backoffice/campaigns", desc: "editor BO-06 accende Campagne" },
  { id: "TB-WEB-NAV-040", path: "/backoffice", expected: "/backoffice", desc: "radice accende la Dashboard" },
  { id: "TB-WEB-NAV-043", path: "/portal/rewards", expected: null, desc: "percorso del portale" },
  ].map((c) => ({ ...c, desc: `voce attiva per ${c.path} (${c.desc}) → ${c.expected}` }))),
)("[%s] %s", (_id, _desc, { path, expected }) => {
  expect(activeHref(path, visibleNav(7))).toBe(expected);
});

// TESTBOOK: ambiguo, vedi TB-WEB-NAV-041 e TB-WEB-NAV-042 — docs/08 §1 non dice quale voce si accende su un percorso
// senza voce propria: oggi vince il prefisso `/backoffice/` (Dashboard); Membri non si accende per un prefisso senza «/».
it("[TB-WEB-NAV-041] voce attiva per /backoffice/unknown (sottopagina senza voce) → Dashboard", () => {
  expect(activeHref("/backoffice/unknown", visibleNav(7))).toBe("/backoffice");
});

it("[TB-WEB-NAV-042] voce attiva per /backoffice/membersX (prefisso senza «/») → non Membri (Dashboard)", () => {
  expect(activeHref("/backoffice/membersX", visibleNav(7))).toBe("/backoffice");
});

it("[TB-WEB-NAV-044] voce non ancora realizzata non si accende (milestone 1, /backoffice/segments)", () => {
  expect(activeHref("/backoffice/segments", visibleNav(1))).toBeNull();
});

// Contatori della sidebar (docs/08 §1): Approvazioni (IN_REVIEW), Richieste premio, DLQ.
it("[TB-WEB-NAV-045] contatore su Approvazioni (BO-21, oggetti IN_REVIEW)", () => {
  expect(findItem("BO-21")?.counter).toBeDefined();
});

it("[TB-WEB-NAV-046] contatore su Richieste premio (BO-13)", () => {
  expect(findItem("BO-13")?.counter).toBe("redemptions");
});

it("[TB-WEB-NAV-047] contatore su DLQ (BO-27)", () => {
  expect(findItem("BO-27")?.counter).toBe("dlq");
});
