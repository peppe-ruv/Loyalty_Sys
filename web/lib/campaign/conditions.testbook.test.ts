import { expect, it } from "vitest";
import { rows } from "@/test/testbook";
import {
  ALL_COMPARATORS,
  appendChild,
  attributeField,
  buildCatalog,
  canAddGroup,
  changeComparator,
  changeField,
  coerceScalar,
  commonDataFields,
  comparatorsFor,
  dataFieldType,
  emptyGroup,
  fieldWarnings,
  fromJson,
  groupFields,
  isPlausiblePath,
  leafProblem,
  lookupField,
  parseConditionsText,
  parseList,
  toJson,
  type Comparator,
  type ConditionField,
  type EventTypeField,
  type UiGroup,
  type UiLeaf,
  type ValueType,
} from "./conditions";

// Testbook TB-WEB §COND: costruttore delle condizioni di BO-06 (docs/08 §BO-06 sezione "4 Se"; docs/03 §3.3; Q-90,
// Q-91, Q-92). Oracolo: gruppi TUTTE/ALMENO UNA/NESSUNA annidabili fino a 3 livelli; campi per spazio; operatore
// coerente col tipo; valore secondo il tipo; intersezione dei `data.*` fra più trigger con avviso sui campi non comuni.

const leaf = (field: string, cmp: Comparator, value?: unknown, id = "l"): UiLeaf => ({ kind: "leaf", id, field, cmp, value });
const group = (op: "all" | "any" | "not", rules: UiGroup["rules"], id = "g"): UiGroup => ({ kind: "group", id, op, rules });
const F = (type: ValueType, extra: Partial<ConditionField> = {}): ConditionField => ({ path: "data.x", label: "x", space: "data", type, ...extra });

// ---------- profondità (max 3 livelli) ----------

const nest = (levels: number): unknown => {
  let node: unknown = { field: "data.amount", cmp: "gte", value: 1 };
  for (let i = 0; i < levels; i++) node = { op: "all", rules: [node] };
  return node;
};

it.each(
  rows([
    { id: "TB-WEB-COND-001", desc: "1 livello di gruppi → accettato", levels: 1, ok: true },
    { id: "TB-WEB-COND-002", desc: "3 livelli (massimo) → accettato", levels: 3, ok: true },
    { id: "TB-WEB-COND-003", desc: "4 livelli (massimo + 1) → rifiutato «Al massimo 3 livelli»", levels: 4, ok: false },
  ]),
)("[%s] JSON con %s", (_id, _desc, { levels, ok }) => {
  const res = fromJson(nest(levels));
  if (ok) {
    expect(res.error).toBeNull();
  } else {
    expect(res.tree).toBeNull();
    expect(res.error).toMatch(/Al massimo 3 livelli/);
  }
});

it("[TB-WEB-COND-004] aggiungere un sottogruppo al livello 2 (diventa livello 3) → aggiunto", () => {
  const tree = group("all", [group("any", [], "g2")], "g1");
  const out = appendChild(tree, "g2", emptyGroup("not"));
  expect((out.rules[0] as UiGroup).rules).toHaveLength(1);
});

it("[TB-WEB-COND-005] aggiungere un sottogruppo al livello 3 (diventerebbe livello 4) → albero invariato", () => {
  const tree = group("all", [group("any", [group("not", [], "g3")], "g2")], "g1");
  expect(appendChild(tree, "g3", emptyGroup())).toBe(tree);
});

it.each(
  rows([
    { id: "TB-WEB-COND-006", desc: "livello 1 → si può aggiungere un gruppo", target: "g1", expected: true },
    { id: "TB-WEB-COND-007", desc: "livello 2 → si può aggiungere un gruppo", target: "g2", expected: true },
    { id: "TB-WEB-COND-008", desc: "livello 3 → non si può aggiungere un gruppo", target: "g3", expected: false },
  ]),
)("[%s] canAddGroup %s", (_id, _desc, { target, expected }) => {
  const tree = group("all", [group("any", [group("not", [], "g3")], "g2")], "g1");
  expect(canAddGroup(tree, target)).toBe(expected);
});

// ---------- operatore coerente col tipo ----------

it.each(
  rows([
    { id: "TB-WEB-COND-009", desc: "numero → confronti d'ordine e intervallo, niente testo", type: "number" as ValueType, must: ["eq", "gt", "gte", "lt", "lte", "between"], mustNot: ["contains", "startsWith"] },
    { id: "TB-WEB-COND-010", desc: "testo → uguaglianza, inizia con, contiene; niente ordine", type: "string" as ValueType, must: ["eq", "startsWith", "contains"], mustNot: ["gt", "gte", "lt", "lte", "between"] },
    { id: "TB-WEB-COND-011", desc: "enum → uguaglianza ed elenco; niente ordine né contiene", type: "enum" as ValueType, must: ["eq", "in", "nin"], mustNot: ["gt", "between", "contains"] },
    { id: "TB-WEB-COND-012", desc: "booleano → uguaglianza; niente ordine, elenco, contiene", type: "boolean" as ValueType, must: ["eq"], mustNot: ["gt", "in", "contains", "between"] },
    { id: "TB-WEB-COND-013", desc: "elenco → contiene; niente ordine", type: "list" as ValueType, must: ["contains", "ncontains"], mustNot: ["gt", "between"] },
    { id: "TB-WEB-COND-014", desc: "data → dal/fino al/tra (Q-91)", type: "date" as ValueType, must: ["gte", "lte", "between", "eq"], mustNot: ["contains", "in"] },
    { id: "TB-WEB-COND-015", desc: "oggetto → solo presente/assente", type: "object" as ValueType, must: ["exists", "nexists"], mustNot: ["eq", "gt"] },
    { id: "TB-WEB-COND-016", desc: "percorso fuori catalogo → tutti i 14 comparatori", type: "unknown" as ValueType, must: ALL_COMPARATORS, mustNot: [] as Comparator[] },
  ]),
)("[%s] comparatori per %s", (_id, _desc, { type, must, mustNot }) => {
  const allowed = comparatorsFor(type);
  for (const c of must) expect(allowed).toContain(c);
  for (const c of mustNot) expect(allowed).not.toContain(c);
});

// ---------- valore secondo il tipo ----------

it.each(
  rows([
    { id: "TB-WEB-COND-017", desc: "schema number → numero", f: { type: "number" } as EventTypeField, expected: "number" },
    { id: "TB-WEB-COND-018", desc: "schema integer → numero", f: { type: "integer" } as EventTypeField, expected: "number" },
    { id: "TB-WEB-COND-019", desc: "schema boolean → booleano", f: { type: "boolean" } as EventTypeField, expected: "boolean" },
    { id: "TB-WEB-COND-020", desc: "schema array → elenco", f: { type: "array" } as EventTypeField, expected: "list" },
    { id: "TB-WEB-COND-021", desc: "schema object → oggetto", f: { type: "object" } as EventTypeField, expected: "object" },
    { id: "TB-WEB-COND-022", desc: "stringa con enum → enum (select)", f: { type: "string", enum: ["ONLINE", "STORE"] } as EventTypeField, expected: "enum" },
    { id: "TB-WEB-COND-023", desc: "stringa format date → data", f: { type: "string", format: "date" } as EventTypeField, expected: "date" },
    { id: "TB-WEB-COND-024", desc: "stringa format date-time → data", f: { type: "string", format: "date-time" } as EventTypeField, expected: "date" },
    { id: "TB-WEB-COND-025", desc: "stringa semplice → testo", f: { type: "string" } as EventTypeField, expected: "string" },
  ]),
)("[%s] tipo del valore: %s", (_id, _desc, { f, expected }) => {
  expect(dataFieldType(f)).toBe(expected);
});

// ---------- catalogo per spazio (docs/03 §3.3, Q-92) ----------

const catalog = buildCatalog({ dataFields: [{ path: "data.amount", type: "number" }] });
const paths = (space: string) => catalog.filter((f) => f.space === space).map((f) => f.path);

it("[TB-WEB-COND-026] campi raggruppati per spazio nell'ordine data, member, context, history", () => {
  expect(groupFields(catalog).map((g) => g.space)).toEqual(["data", "member", "context", "history"]);
});

it("[TB-WEB-COND-027] spazio member: tier, status, segments, labels, registeredDaysAgo, age", () => {
  expect(paths("member")).toEqual(["member.tier", "member.status", "member.segments", "member.labels", "member.registeredDaysAgo", "member.age"]);
});

it("[TB-WEB-COND-028] spazio context: source, dayOfWeek (MON…SUN), hour, date", () => {
  expect(paths("context")).toEqual(["context.source", "context.dayOfWeek", "context.hour", "context.date"]);
  expect(lookupField(catalog, "context.dayOfWeek").options).toEqual(["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]);
});

it("[TB-WEB-COND-029] spazio history: actionCount, daysSinceLastAction", () => {
  expect(paths("history")).toEqual(["history.actionCount", "history.daysSinceLastAction"]);
});

it("[TB-WEB-COND-030] tier dal wallet; wallet assente → BASE, SILVER, GOLD, PLATINUM (docs/03 §4.3)", () => {
  expect(lookupField(buildCatalog({ tiers: ["GOLD", "BASE"] }), "member.tier").options).toEqual(["GOLD", "BASE"]);
  expect(lookupField(buildCatalog({}), "member.tier").options).toEqual(["BASE", "SILVER", "GOLD", "PLATINUM"]);
});

it.each(
  rows([
    { id: "TB-WEB-COND-031", desc: "NUMBER con opzioni → enum di numeri", a: { key: "n", label: "N", type: "NUMBER" as const, options: ["1", "2"] }, type: "enum", numeric: true },
    { id: "TB-WEB-COND-032", desc: "BOOLEAN → booleano", a: { key: "b", label: "B", type: "BOOLEAN" as const, options: null }, type: "boolean", numeric: undefined },
    { id: "TB-WEB-COND-033", desc: "DATE → data", a: { key: "d", label: "D", type: "DATE" as const, options: null }, type: "date", numeric: undefined },
    { id: "TB-WEB-COND-034", desc: "STRING senza opzioni → testo", a: { key: "s", label: "S", type: "STRING" as const, options: null }, type: "string", numeric: undefined },
  ]),
)("[%s] attributo custom %s (member.attributes.<k>)", (_id, _desc, { a, type, numeric }) => {
  const f = attributeField(a);
  expect([f.path, f.type, f.numericOptions]).toEqual([`member.attributes.${a.key}`, type, numeric]);
});

it("[TB-WEB-COND-035] campo su elenco data.items[*].category → nota «vero se almeno un elemento soddisfa»", () => {
  const f = lookupField(buildCatalog({ dataFields: [{ path: "data.items[*].category", type: "string" }] }), "data.items[*].category");
  expect(f.hint).toBe("vero se almeno un elemento soddisfa");
});

// ---------- intersezione dei data.* fra più trigger ----------

const FIELDS: Record<string, EventTypeField[]> = {
  purchase: [
    { path: "data.amount", type: "number", required: true },
    { path: "data.channel", type: "string", enum: ["ONLINE", "STORE"], required: true },
    { path: "data.sku", type: "string" },
  ],
  visit: [
    { path: "data.amount", type: "integer", required: false },
    { path: "data.channel", type: "string", enum: ["STORE", "APP"], required: true },
    { path: "data.storeId", type: "string" },
  ],
  login: [{ path: "data.device", type: "string" }],
};

it("[TB-WEB-COND-036] un trigger → tutti i suoi campi", () => {
  expect(commonDataFields(FIELDS, ["purchase"]).map((f) => f.path)).toEqual(["data.amount", "data.channel", "data.sku"]);
});

it("[TB-WEB-COND-037] due trigger → solo i campi comuni", () => {
  expect(commonDataFields(FIELDS, ["purchase", "visit"]).map((f) => f.path)).toEqual(["data.amount", "data.channel"]);
});

it("[TB-WEB-COND-038] trigger senza campi in comune → nessun campo data.*", () => {
  expect(commonDataFields(FIELDS, ["purchase", "login"])).toEqual([]);
});

it("[TB-WEB-COND-039] number e integer → number", () => {
  expect(commonDataFields(FIELDS, ["purchase", "visit"]).find((f) => f.path === "data.amount")?.type).toBe("number");
});

it("[TB-WEB-COND-040] enum diversi → unione dei valori", () => {
  expect(commonDataFields(FIELDS, ["purchase", "visit"]).find((f) => f.path === "data.channel")?.enum).toEqual(["ONLINE", "STORE", "APP"]);
});

it("[TB-WEB-COND-041] obbligatorio solo se lo è in tutti i trigger", () => {
  const out = commonDataFields(FIELDS, ["purchase", "visit"]);
  expect([out.find((f) => f.path === "data.amount")?.required, out.find((f) => f.path === "data.channel")?.required]).toEqual([false, true]);
});

it("[TB-WEB-COND-042] campi di un trigger non ancora arrivati → quel trigger è ignorato nell'intersezione", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-COND-042 — la spec non tratta il catalogo parziale.
  expect(commonDataFields({ purchase: FIELDS.purchase, visit: undefined }, ["purchase", "visit"]).map((f) => f.path)).toEqual([
    "data.amount",
    "data.channel",
    "data.sku",
  ]);
});

// ---------- avvisi ----------

const warn = (field: string, triggers: string[], fieldsByTrigger: Record<string, EventTypeField[] | undefined> = FIELDS, unavailable?: ("data" | "member")[]) =>
  fieldWarnings(group("all", [leaf(field, "exists", undefined, "x")]), {
    triggers,
    fieldsByTrigger,
    catalog: buildCatalog({ dataFields: commonDataFields(fieldsByTrigger, triggers) }),
    unavailable,
  }).x;

it("[TB-WEB-COND-043] campo data.* comune a tutti i trigger → nessun avviso", () => {
  expect(warn("data.amount", ["purchase", "visit"])).toBeUndefined();
});

it("[TB-WEB-COND-044] campo data.* di un solo trigger → avviso «manca in visit»", () => {
  expect(warn("data.sku", ["purchase", "visit"])).toMatch(/non comune a tutti i trigger: manca in visit/);
});

it("[TB-WEB-COND-045] campo data.* di nessun trigger → avviso «potrebbe non essere mai vera»", () => {
  expect(warn("data.coupon", ["purchase", "visit"])).toMatch(/potrebbe non essere mai vera/);
});

it("[TB-WEB-COND-046] campi dei trigger non ancora arrivati → nessun avviso", () => {
  // TESTBOOK: ambiguo, vedi TB-WEB-COND-046 — con il catalogo incompleto l'avviso non è calcolabile.
  expect(warn("data.sku", ["purchase", "visit"], { purchase: FIELDS.purchase, visit: undefined })).toBeUndefined();
});

it("[TB-WEB-COND-047] campo member del catalogo (member.tier) → nessun avviso", () => {
  expect(warn("member.tier", ["purchase"])).toBeUndefined();
});

it("[TB-WEB-COND-048] campo member fuori catalogo (member.nickname) → «fuori catalogo»", () => {
  expect(warn("member.nickname", ["purchase"])).toMatch(/fuori catalogo/);
});

it("[TB-WEB-COND-049] spazio sconosciuto (order.total) → «Spazio sconosciuto»", () => {
  expect(warn("order.total", ["purchase"])).toMatch(/Spazio sconosciuto/);
});

it("[TB-WEB-COND-050] attributo custom con member addormentato → nessun avviso «fuori catalogo»", () => {
  expect(warn("member.attributes.householdSize", ["purchase"], FIELDS, ["member"])).toBeUndefined();
});

// COND-051/052 (avviso NESSUNA, Q-90): components/bo/campaigns/ConditionBuilder.testbook.test.tsx

// ---------- validazione della riga (blocca il salvataggio) ----------

it.each(
  rows([
    { id: "TB-WEB-COND-053", desc: "campo vuoto → «Scegli un campo»", l: leaf("", "eq", 1), f: F("number"), expected: "Scegli un campo" as string | null },
    { id: "TB-WEB-COND-054", desc: "gt su booleano → «Operatore non ammesso per questo campo»", l: leaf("data.x", "gt", true), f: F("boolean"), expected: "Operatore non ammesso per questo campo" },
    { id: "TB-WEB-COND-055", desc: "presente (exists) senza valore → valida", l: leaf("data.x", "exists"), f: F("number"), expected: null },
    { id: "TB-WEB-COND-056", desc: "in con elenco vuoto → «Aggiungi almeno un valore»", l: leaf("data.x", "in", []), f: F("number"), expected: "Aggiungi almeno un valore" },
    { id: "TB-WEB-COND-057", desc: "in su numero con un testo → «Serve un numero»", l: leaf("data.x", "in", [1, "due"]), f: F("number"), expected: "Serve un numero" },
    { id: "TB-WEB-COND-058", desc: "tra con un solo estremo → «Servono due valori: da, a»", l: leaf("data.x", "between", [9, null]), f: F("number"), expected: "Servono due valori: da, a" },
    { id: "TB-WEB-COND-059", desc: "tra 18 e 9 (invertito) → «Il primo valore supera il secondo»", l: leaf("data.x", "between", [18, 9]), f: F("number"), expected: "Il primo valore supera il secondo" },
    { id: "TB-WEB-COND-060", desc: "tra 9 e 9 (estremi uguali) → valida", l: leaf("data.x", "between", [9, 9]), f: F("number"), expected: null },
    { id: "TB-WEB-COND-061", desc: "numero con testo «abc» → «Serve un numero»", l: leaf("data.x", "gte", "abc"), f: F("number"), expected: "Serve un numero" },
    { id: "TB-WEB-COND-062", desc: "numero vuoto → «Valore mancante»", l: leaf("data.x", "gte", ""), f: F("number"), expected: "Valore mancante" },
    { id: "TB-WEB-COND-063", desc: "booleano con testo «true» → «Scegli sì o no»", l: leaf("data.x", "eq", "true"), f: F("boolean"), expected: "Scegli sì o no" },
    { id: "TB-WEB-COND-064", desc: "data «18/09/2026» → «Data non valida (AAAA-MM-GG)»", l: leaf("data.x", "gte", "18/09/2026"), f: F("date"), expected: "Data non valida (AAAA-MM-GG)" },
    { id: "TB-WEB-COND-065", desc: "data «2026-09-18» → valida", l: leaf("data.x", "gte", "2026-09-18"), f: F("date"), expected: null },
    { id: "TB-WEB-COND-066", desc: "enum con valore fuori elenco → «Valore non ammesso: XYZ»", l: leaf("data.x", "eq", "XYZ"), f: F("enum", { options: ["ONLINE", "STORE"] }), expected: "Valore non ammesso: XYZ" },
    { id: "TB-WEB-COND-067", desc: "numero 0 → valido (zero non è «mancante»)", l: leaf("data.x", "gte", 0), f: F("number"), expected: null },
  ]),
)("[%s] riga: %s", (_id, _desc, { l, f, expected }) => {
  expect(leafProblem(l, f)).toBe(expected);
});

// ---------- JSON ⇄ albero ----------

it("[TB-WEB-COND-068] condizioni null → gruppo TUTTE vuoto", () => {
  const res = fromJson(null);
  expect([res.error, res.tree?.op, res.tree?.rules.length]).toEqual([null, "all", 0]);
});

it("[TB-WEB-COND-069] condizioni {} → gruppo TUTTE vuoto", () => {
  const res = fromJson({});
  expect([res.error, res.tree?.op, res.tree?.rules.length]).toEqual([null, "all", 0]);
});

it("[TB-WEB-COND-070] una foglia sola → avvolta in un gruppo TUTTE", () => {
  const res = fromJson({ field: "data.amount", cmp: "gte", value: 50 });
  expect(res.tree && toJson(res.tree)).toEqual({ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 50 }] });
});

it("[TB-WEB-COND-071] operatore di gruppo sconosciuto (xor) → errore", () => {
  expect(fromJson({ op: "xor", rules: [] }).error).toMatch(/operatore di gruppo sconosciuto «xor»/);
});

it("[TB-WEB-COND-072] comparatore sconosciuto (like) → errore", () => {
  expect(fromJson({ field: "data.sku", cmp: "like", value: "A" }).error).toMatch(/comparatore sconosciuto «like»/);
});

it("[TB-WEB-COND-073] testo non JSON nella vista JSON → «JSON non valido»", () => {
  expect(parseConditionsText("{op: all").error).toMatch(/^JSON non valido/);
});

it("[TB-WEB-COND-074] salvataggio: i gruppi vuoti (anche NESSUNA) sono tolti", () => {
  const tree = group("all", [group("not", [], "n"), leaf("data.amount", "gte", 10)]);
  expect(toJson(tree)).toEqual({ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 10 }] });
});

it("[TB-WEB-COND-075] salvataggio senza condizioni → null (il servizio le tratta come sempre vere)", () => {
  expect(toJson(group("all", []))).toBeNull();
});

it("[TB-WEB-COND-076] salvataggio di «presente» → nessun valore nel JSON", () => {
  expect(toJson(group("all", [leaf("data.coupon", "exists", "resto")]))).toEqual({ op: "all", rules: [{ field: "data.coupon", cmp: "exists" }] });
});

// ---------- conversione dei valori ----------

it.each(
  rows([
    { id: "TB-WEB-COND-077", desc: "numero con virgola «1,5» → 1.5", raw: "1,5", f: F("number"), expected: 1.5 as unknown },
    { id: "TB-WEB-COND-078", desc: "numero con testo «abc» → resta testo (segnalato da COND-061)", raw: "abc", f: F("number"), expected: "abc" },
    { id: "TB-WEB-COND-080", desc: "percorso libero «12» → numero 12", raw: "12", f: F("unknown"), expected: 12 },
  ]),
)("[%s] coerceScalar %s", (_id, _desc, { raw, f, expected }) => {
  expect(coerceScalar(raw, f)).toBe(expected);
});

it("[TB-WEB-COND-079] elenco «SAT, SUN, SAT» → senza doppioni", () => {
  expect(parseList("SAT, SUN, SAT", F("enum"))).toEqual(["SAT", "SUN"]);
});

it.each(
  rows([
    { id: "TB-WEB-COND-081", desc: "= 50 → in → [50]", from: leaf("data.x", "eq", 50), to: "in" as Comparator, expected: [50] as unknown },
    { id: "TB-WEB-COND-082", desc: "in [50, 60] → = → 50", from: leaf("data.x", "in", [50, 60]), to: "eq" as Comparator, expected: 50 },
    { id: "TB-WEB-COND-083", desc: "= 9 → tra → [9, null]", from: leaf("data.x", "eq", 9), to: "between" as Comparator, expected: [9, null] },
    { id: "TB-WEB-COND-084", desc: "= 9 → presente → nessun valore", from: leaf("data.x", "eq", 9), to: "exists" as Comparator, expected: undefined },
  ]),
)("[%s] cambio comparatore %s", (_id, _desc, { from, to, expected }) => {
  expect(changeComparator(from, to, F("number")).value).toEqual(expected);
});

it("[TB-WEB-COND-085] cambio campo tra due numeri → comparatore e valore conservati", () => {
  const out = changeField(leaf("data.amount", "gt", 50), F("number", { path: "data.amount" }), F("number", { path: "data.qty" }));
  expect([out.field, out.cmp, out.value]).toEqual(["data.qty", "gt", 50]);
});

it("[TB-WEB-COND-086] cambio campo da numero a booleano → comparatore «=» e valore sì", () => {
  const out = changeField(leaf("data.amount", "gt", 50), F("number", { path: "data.amount" }), F("boolean", { path: "data.first" }));
  expect([out.field, out.cmp, out.value]).toEqual(["data.first", "eq", true]);
});

// TESTBOOK: ambiguo, vedi TB-WEB-COND-087…089 — docs/08 prevede il campo scelto da un elenco raggruppato per spazio; il
// percorso scritto a mano (campo fuori catalogo) non è descritto: oggi è ammesso se inizia con uno spazio noto.
it.each(
  rows([
    { id: "TB-WEB-COND-087", desc: "percorso a mano «data.items[*].sku» → plausibile", path: "data.items[*].sku", expected: true },
    { id: "TB-WEB-COND-088", desc: "percorso a mano in uno spazio sconosciuto «order.total» → non plausibile", path: "order.total", expected: false },
    { id: "TB-WEB-COND-089", desc: "percorso a mano incompleto «data.» → non plausibile", path: "data.", expected: false },
  ]),
)("[%s] %s", (_id, _desc, { path, expected }) => {
  expect(isPlausiblePath(path)).toBe(expected);
});
