import { describe, expect, it } from "vitest";
import {
  ALL_COMPARATORS,
  MAX_DEPTH,
  appendChild,
  attributeField,
  buildCatalog,
  canAddGroup,
  changeComparator,
  changeField,
  coerceScalar,
  commonDataFields,
  comparatorLabel,
  comparatorsFor,
  countLeaves,
  dataFieldType,
  emptyGroup,
  fieldWarnings,
  fromJson,
  groupDepth,
  groupFields,
  isPlausiblePath,
  leafProblem,
  levelOf,
  lookupField,
  newLeaf,
  parseConditionsText,
  parseList,
  removeNode,
  toJson,
  toJsonText,
  updateNode,
  validateTree,
  valueShape,
  valueText,
  type ConditionField,
  type EventTypeField,
  type UiGroup,
  type UiLeaf,
} from "./conditions";
import { describeCampaign } from "./describe";

const PURCHASE: EventTypeField[] = [
  { path: "data.orderId", type: "string", required: true },
  { path: "data.amount", type: "number", required: true },
  { path: "data.currency", type: "string", required: true },
  { path: "data.channel", type: "string", required: false, enum: ["ONLINE", "STORE", "APP"] },
  { path: "data.items", type: "array", required: false },
  { path: "data.items[*].category", type: "string", required: false },
  { path: "data.items[*].quantity", type: "integer", required: false },
];
const RETURNED: EventTypeField[] = [
  { path: "data.orderId", type: "string", required: true },
  { path: "data.amount", type: "integer", required: true },
];
const METER: EventTypeField[] = [
  { path: "data.amount", type: "number", required: false },
  { path: "data.channel", type: "string", required: true, enum: ["WEB", "APP"] },
  { path: "data.readingDate", type: "string", required: true, format: "date" },
  { path: "data.verified", type: "boolean", required: false },
];

const CATALOG = buildCatalog({
  dataFields: PURCHASE,
  tiers: ["BASE", "SILVER", "GOLD", "PLATINUM"],
  segments: [{ code: "SEG-BIG", name: "Grandi spese" }],
  attributes: [
    { key: "householdSize", label: "Componenti del nucleo", type: "NUMBER", options: null },
    { key: "preferredChannel", label: "Canale preferito", type: "STRING", options: ["APP", "WEB", "STORE"] },
    { key: "hasGasContract", label: "Contratto gas", type: "BOOLEAN", options: null },
    { key: "since", label: "Cliente dal", type: "DATE", options: null },
  ],
});

const field = (path: string): ConditionField => lookupField(CATALOG, path);
const leaf = (f: string, cmp: UiLeaf["cmp"], value?: unknown): UiLeaf => ({ kind: "leaf", id: `l-${f}-${cmp}`, field: f, cmp, value });

describe("tipi e comparatori", () => {
  it("mappa i tipi dello schema sui tipi del costruttore", () => {
    expect(dataFieldType({ type: "integer" })).toBe("number");
    expect(dataFieldType({ type: "number" })).toBe("number");
    expect(dataFieldType({ type: "string", enum: ["A"] })).toBe("enum");
    expect(dataFieldType({ type: "string", enum: [] })).toBe("string");
    expect(dataFieldType({ type: "string", format: "date" })).toBe("date");
    expect(dataFieldType({ type: "string", format: "date-time" })).toBe("date");
    expect(dataFieldType({ type: "boolean" })).toBe("boolean");
    expect(dataFieldType({ type: "array" })).toBe("list");
    expect(dataFieldType({ type: "object" })).toBe("object");
  });

  it("offre solo i comparatori coerenti col tipo (docs/08 §BO-06)", () => {
    expect(comparatorsFor("number")).toEqual(expect.arrayContaining(["eq", "neq", "gt", "gte", "lt", "lte", "between", "in", "nin", "exists", "nexists"]));
    expect(comparatorsFor("number")).not.toContain("startsWith");
    expect(comparatorsFor("string")).toEqual(expect.arrayContaining(["startsWith", "contains", "ncontains", "in", "nin"]));
    expect(comparatorsFor("string")).not.toContain("gt");
    expect(comparatorsFor("enum")).toEqual(["eq", "neq", "in", "nin"]);
    expect(comparatorsFor("boolean")).toEqual(["eq", "exists", "nexists"]);
    expect(comparatorsFor("list")).toEqual(["contains", "ncontains", "exists", "nexists"]);
    expect(comparatorsFor("date")).toEqual(expect.arrayContaining(["eq", "gt", "gte", "lt", "lte", "between"]));
    expect(comparatorsFor("date")).not.toContain("in");
    expect(comparatorsFor("unknown")).toEqual(ALL_COMPARATORS);
  });

  it("etichette in italiano, con le forme per le date", () => {
    expect(comparatorLabel("gte")).toBe("almeno");
    expect(comparatorLabel("gte", "date")).toBe("dal");
    expect(comparatorLabel("lt", "date")).toBe("prima del");
    expect(comparatorLabel("in", "date")).toBe("è uno tra");
  });

  it("forma del valore per comparatore", () => {
    expect(valueShape("exists")).toBe("none");
    expect(valueShape("nexists")).toBe("none");
    expect(valueShape("in")).toBe("list");
    expect(valueShape("nin")).toBe("list");
    expect(valueShape("between")).toBe("range");
    expect(valueShape("contains")).toBe("scalar");
  });
});

describe("intersezione dei campi data.* fra trigger", () => {
  it("un solo trigger: tutti i suoi campi", () => {
    expect(commonDataFields({ "purchase.completed": PURCHASE }, ["purchase.completed"]).map((f) => f.path)).toEqual(PURCHASE.map((f) => f.path));
  });

  it("più trigger: solo i percorsi comuni, number+integer → number, required solo se ovunque", () => {
    const common = commonDataFields({ a: PURCHASE, b: RETURNED }, ["a", "b"]);
    expect(common.map((f) => f.path)).toEqual(["data.orderId", "data.amount"]);
    expect(common[1]).toMatchObject({ type: "number", required: true });
  });

  it("enum su entrambi → unione; tipi incompatibili → string", () => {
    const common = commonDataFields({ a: PURCHASE, c: METER }, ["a", "c"]);
    expect(common.map((f) => f.path)).toEqual(["data.amount", "data.channel"]);
    expect(common[0]).toMatchObject({ type: "number", required: false });
    expect(common[1].enum).toEqual(["ONLINE", "STORE", "APP", "WEB"]);
    const mixed = commonDataFields({ a: [{ path: "data.x", type: "number" }], b: [{ path: "data.x", type: "boolean" }] }, ["a", "b"]);
    expect(mixed[0].type).toBe("string");
  });

  it("enum solo da una parte → niente enum; format solo se uguale", () => {
    const common = commonDataFields(
      {
        a: [{ path: "data.c", type: "string", enum: ["X"] }, { path: "data.d", type: "string", format: "date" }],
        b: [{ path: "data.c", type: "string" }, { path: "data.d", type: "string", format: "date" }],
      },
      ["a", "b"],
    );
    expect(common[0].enum).toBeUndefined();
    expect(common[1].format).toBe("date");
  });

  it("ignora i trigger non ancora caricati; nessun trigger → nessun campo", () => {
    expect(commonDataFields({ a: PURCHASE }, ["a", "zzz"]).length).toBe(PURCHASE.length);
    expect(commonDataFields({ a: PURCHASE }, [])).toEqual([]);
    expect(commonDataFields({}, ["a"])).toEqual([]);
  });
});

describe("catalogo", () => {
  it("contiene i quattro spazi nell'ordine data, member, context, history", () => {
    const spaces = [...new Set(CATALOG.map((f) => f.space))];
    expect(spaces).toEqual(["data", "member", "context", "history"]);
    expect(groupFields(CATALOG).map((g) => g.space)).toEqual(["data", "member", "context", "history"]);
  });

  it("campi data.* col tipo giusto e l'avviso sugli array", () => {
    expect(field("data.amount").type).toBe("number");
    expect(field("data.channel")).toMatchObject({ type: "enum", options: ["ONLINE", "STORE", "APP"] });
    expect(field("data.items").type).toBe("list");
    expect(field("data.items[*].category")).toMatchObject({ type: "string", label: "items › category", hint: expect.stringContaining("almeno un elemento") });
  });

  it("membro: tier dai codici, stato, segmenti con nome, etichette, numeri", () => {
    expect(field("member.tier").options).toEqual(["BASE", "SILVER", "GOLD", "PLATINUM"]);
    expect(field("member.status").options).toEqual(["ACTIVE", "INACTIVE", "BLOCKED"]);
    expect(field("member.segments")).toMatchObject({ type: "list", options: ["SEG-BIG"], optionLabels: { "SEG-BIG": "Grandi spese" } });
    expect(field("member.labels").type).toBe("list");
    expect(field("member.age").type).toBe("number");
    expect(field("member.registeredDaysAgo").type).toBe("number");
  });

  it("tier di riserva quando wallet non risponde", () => {
    expect(lookupField(buildCatalog({}), "member.tier").options).toEqual(["BASE", "SILVER", "GOLD", "PLATINUM"]);
    expect(lookupField(buildCatalog({}), "member.segments").options).toBeUndefined();
  });

  it("attributi custom → member.attributes.<key>, opzioni ⇒ enum", () => {
    expect(field("member.attributes.householdSize")).toMatchObject({ type: "number", label: "Componenti del nucleo" });
    expect(field("member.attributes.preferredChannel")).toMatchObject({ type: "enum", options: ["APP", "WEB", "STORE"] });
    expect(field("member.attributes.hasGasContract").type).toBe("boolean");
    expect(field("member.attributes.since").type).toBe("date");
    expect(attributeField({ key: "n", label: "", type: "NUMBER", options: ["1", "2"] })).toMatchObject({ type: "enum", numericOptions: true, label: "n" });
  });

  it("contesto e storico", () => {
    expect(field("context.dayOfWeek").options).toEqual(["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]);
    expect(field("context.hour").type).toBe("number");
    expect(field("context.date").type).toBe("date");
    expect(field("context.source").type).toBe("string");
    // Il motore confronta l'URN (docs/05 §2): il suggerimento non deve indurre a scrivere il solo codice.
    expect(field("context.source").hint).toContain("urn:loyaltyhub:source:");
    expect(field("history.actionCount").type).toBe("number");
    expect(field("history.daysSinceLastAction").type).toBe("number");
  });

  it("un percorso fuori catalogo resta accettato come campo libero", () => {
    expect(field("data.promoCode")).toMatchObject({ type: "unknown", custom: true, space: "data" });
    expect(field("member.attributes.nuovo")).toMatchObject({ type: "unknown", space: "member" });
    expect(isPlausiblePath("data.promo.code")).toBe(true);
    expect(isPlausiblePath("data.items[*].sku")).toBe(true);
    expect(isPlausiblePath("promo")).toBe(false);
    expect(isPlausiblePath("data.")).toBe(false);
  });

  it("ricerca per etichetta o percorso", () => {
    const g = groupFields(CATALOG, "nucleo");
    expect(g).toHaveLength(1);
    expect(g[0].fields.map((f) => f.path)).toEqual(["member.attributes.householdSize"]);
    expect(groupFields(CATALOG, "history.").map((x) => x.space)).toEqual(["history"]);
  });
});

describe("JSON ⇄ albero", () => {
  it("vuoto/null → gruppo TUTTE vuoto, e ritorno a null", () => {
    for (const raw of [null, undefined, {}, { op: "all", rules: [] }]) {
      const r = fromJson(raw);
      expect(r.error).toBeNull();
      expect(r.tree!.rules).toEqual([]);
      expect(toJson(r.tree!)).toBeNull();
    }
    expect(parseConditionsText("   ").tree!.rules).toEqual([]);
    expect(parseConditionsText("null").tree!.rules).toEqual([]);
  });

  it("andata e ritorno senza perdite, numeri come numeri", () => {
    const json = {
      op: "all",
      rules: [
        { field: "data.amount", cmp: "gte", value: 50 },
        { field: "member.tier", cmp: "in", value: ["GOLD", "PLATINUM"] },
        { op: "any", rules: [{ field: "context.dayOfWeek", cmp: "in", value: ["SAT", "SUN"] }, { field: "member.labels", cmp: "exists" }] },
        { op: "not", rules: [{ field: "data.items[*].category", cmp: "eq", value: "energia" }] },
        { field: "context.hour", cmp: "between", value: [8, 12] },
      ],
    };
    const r = fromJson(json);
    expect(r.error).toBeNull();
    expect(toJson(r.tree!)).toEqual(json);
  });

  it("una foglia alla radice viene avvolta in TUTTE", () => {
    const r = fromJson({ field: "data.amount", cmp: "gt", value: 1 });
    expect(toJson(r.tree!)).toEqual({ op: "all", rules: [{ field: "data.amount", cmp: "gt", value: 1 }] });
  });

  it("cmp assente → eq (come il motore); campi sconosciuti mantenuti", () => {
    const r = fromJson({ op: "any", rules: [{ field: "data.whatever.x", value: "A" }] });
    expect(toJson(r.tree!)).toEqual({ op: "any", rules: [{ field: "data.whatever.x", cmp: "eq", value: "A" }] });
  });

  it("errori di struttura con messaggio, albero null", () => {
    expect(fromJson([1, 2]).error).toMatch(/oggetto/);
    expect(fromJson({ op: "xor", rules: [] }).error).toMatch(/xor/);
    expect(fromJson({ op: "all", rules: {} }).error).toMatch(/elenco/);
    expect(fromJson({ op: "all", rules: [{ field: "data.a", cmp: "like", value: 1 }] }).error).toMatch(/like/);
    expect(fromJson({ op: "all", rules: [{ foo: 1 }] }).error).toMatch(/rules\[0\]/);
    expect(fromJson({ op: "all", rules: [{ field: 3, cmp: "eq" }] }).error).toMatch(/field/);
    expect(parseConditionsText("{ op: ").error).toMatch(/JSON non valido/);
  });

  it("rifiuta più di 3 livelli di gruppi", () => {
    const deep = { op: "all", rules: [{ op: "any", rules: [{ op: "not", rules: [{ op: "all", rules: [] }] }] }] };
    expect(groupDepth(deep as never)).toBe(4);
    expect(fromJson(deep).error).toMatch(/3 livelli/);
    const ok = { op: "all", rules: [{ op: "any", rules: [{ op: "not", rules: [{ field: "data.a", cmp: "exists" }] }] }] };
    expect(fromJson(ok).error).toBeNull();
  });

  it("toglie i gruppi vuoti e il valore per exists/nexists", () => {
    const tree: UiGroup = {
      kind: "group",
      id: "r",
      op: "all",
      rules: [
        { kind: "group", id: "g", op: "not", rules: [] },
        { kind: "leaf", id: "l", field: "data.channel", cmp: "exists", value: "residuo" },
      ],
    };
    expect(toJson(tree)).toEqual({ op: "all", rules: [{ field: "data.channel", cmp: "exists" }] });
    expect(JSON.parse(toJsonText(emptyGroup()))).toEqual({ op: "all", rules: [] });
  });

  it("la frase generata rilegge lo stesso JSON", () => {
    const r = fromJson({ op: "all", rules: [{ field: "data.amount", cmp: "gte", value: 50 }] });
    const text = describeCampaign({ triggerActionTypes: ["purchase.completed"], conditions: toJson(r.tree!) ?? undefined, effects: [] });
    expect(text).toContain("importo ≥ 50");
    const custom = describeCampaign({ triggerActionTypes: ["store.visit"], actionLabels: { "store.visit": "Visita in negozio" }, effects: [] });
    expect(custom).toContain("**Visita in negozio**");
  });
});

describe("operazioni sull'albero", () => {
  it("aggiunge, aggiorna e toglie nodi in modo immutabile", () => {
    const root = emptyGroup();
    const l = newLeaf(field("data.amount"));
    const t1 = appendChild(root, root.id, l);
    expect(root.rules).toHaveLength(0);
    expect(countLeaves(t1)).toBe(1);
    const t2 = updateNode(t1, l.id, (n) => ({ ...(n as UiLeaf), value: 10 }));
    expect((t2.rules[0] as UiLeaf).value).toBe(10);
    expect((t1.rules[0] as UiLeaf).value).toBe("");
    const t3 = removeNode(t2, l.id);
    expect(t3.rules).toEqual([]);
    const t4 = updateNode(t1, root.id, (g) => ({ ...(g as UiGroup), op: "any" }));
    expect(t4.op).toBe("any");
  });

  it("limita i gruppi a 3 livelli", () => {
    const root = emptyGroup();
    const g2 = emptyGroup("any");
    const g3 = emptyGroup("not");
    let t = appendChild(root, root.id, g2);
    t = appendChild(t, g2.id, g3);
    expect(levelOf(t, root.id)).toBe(1);
    expect(levelOf(t, g3.id)).toBe(3);
    expect(canAddGroup(t, g2.id)).toBe(true);
    expect(canAddGroup(t, g3.id)).toBe(false);
    expect(canAddGroup(t, "missing")).toBe(false);
    const same = appendChild(t, g3.id, emptyGroup());
    expect(same).toBe(t);
    const nested = { ...emptyGroup(), rules: [emptyGroup()] } as UiGroup;
    expect(appendChild(t, g2.id, nested)).toBe(t);
    expect(appendChild(t, g3.id, newLeaf(field("data.amount"))).rules).toHaveLength(1);
  });
});

describe("valori", () => {
  it("nuova foglia: comparatore e valore di default del tipo", () => {
    expect(newLeaf(field("data.amount"))).toMatchObject({ cmp: "gte", value: "" });
    expect(newLeaf(field("member.attributes.hasGasContract"))).toMatchObject({ cmp: "eq", value: true });
    expect(newLeaf(field("member.labels"))).toMatchObject({ cmp: "contains" });
    expect(newLeaf(field("data.items")).cmp).toBe("contains");
  });

  it("converte testo in numeri (anche con la virgola) e booleani", () => {
    const num = field("data.amount");
    expect(coerceScalar("42", num)).toBe(42);
    expect(coerceScalar("12,5", num)).toBe(12.5);
    expect(coerceScalar("abc", num)).toBe("abc");
    expect(coerceScalar("", num)).toBe("");
    expect(coerceScalar("true", field("member.attributes.hasGasContract"))).toBe(true);
    expect(coerceScalar("false", field("member.attributes.hasGasContract"))).toBe(false);
    expect(coerceScalar("007", field("data.orderId"))).toBe("007");
    expect(coerceScalar("3", { type: "enum", numericOptions: true })).toBe(3);
    expect(coerceScalar("5", field("data.nuovo"))).toBe(5);
    expect(coerceScalar("true", field("data.nuovo"))).toBe(true);
    expect(coerceScalar("A1", field("data.nuovo"))).toBe("A1");
  });

  it("elenchi separati da virgola, senza vuoti né doppioni", () => {
    expect(parseList("10, 20,,20 ; 30", field("data.amount"))).toEqual([10, 20, 30]);
    expect(parseList("GOLD, PLATINUM", field("member.tier"))).toEqual(["GOLD", "PLATINUM"]);
    expect(valueText([10, 20])).toBe("10, 20");
    expect(valueText(null)).toBe("");
    expect(valueText(true)).toBe("true");
  });

  it("cambio di comparatore: scalare ⇄ elenco ⇄ intervallo senza perdere il dato", () => {
    const f = field("data.amount");
    const l = leaf("data.amount", "gte", 50);
    const asList = changeComparator(l, "in", f);
    expect(asList.value).toEqual([50]);
    const back = changeComparator(asList, "eq", f);
    expect(back.value).toBe(50);
    const range = changeComparator(l, "between", f);
    expect(range.value).toEqual([50, null]);
    const none = changeComparator(l, "exists", f);
    expect("value" in none).toBe(false);
    expect(changeComparator(none, "gte", f).value).toBe("");
    expect(changeComparator(leaf("data.amount", "in", []), "eq", f).value).toBe("");
    expect(changeComparator(leaf("data.amount", "between", [1, 2]), "between", f).value).toEqual([1, 2]);
    expect(changeComparator(leaf("data.amount", "eq", ""), "in", f).value).toEqual([]);
  });

  it("cambio di campo: tiene comparatore e valore solo se compatibili", () => {
    const amount = leaf("data.amount", "gt", 5);
    const hour = changeField(amount, field("data.amount"), field("context.hour"));
    expect(hour).toMatchObject({ field: "context.hour", cmp: "gt", value: 5 });
    const tier = changeField(amount, field("data.amount"), field("member.tier"));
    expect(tier).toMatchObject({ field: "member.tier", cmp: "eq", value: "" });
    const channel = leaf("data.channel", "in", ["APP"]);
    const pref = changeField(channel, field("data.channel"), field("member.attributes.preferredChannel"));
    expect(pref.value).toEqual([]);
    const free = changeField(amount, field("data.amount"), field("data.promo"));
    expect(free).toMatchObject({ field: "data.promo", cmp: "eq", value: "" });
  });
});

describe("validazione", () => {
  it("foglie valide", () => {
    expect(leafProblem(leaf("data.amount", "gte", 50), field("data.amount"))).toBeNull();
    expect(leafProblem(leaf("member.tier", "in", ["GOLD"]), field("member.tier"))).toBeNull();
    expect(leafProblem(leaf("context.hour", "between", [8, 12]), field("context.hour"))).toBeNull();
    expect(leafProblem(leaf("context.date", "between", ["2026-01-01", "2026-02-01"]), field("context.date"))).toBeNull();
    expect(leafProblem(leaf("member.labels", "exists"), field("member.labels"))).toBeNull();
    expect(leafProblem(leaf("member.attributes.hasGasContract", "eq", false), field("member.attributes.hasGasContract"))).toBeNull();
    expect(leafProblem(leaf("data.promo", "eq", "X"), field("data.promo"))).toBeNull();
  });

  it("segnala valori mancanti o del tipo sbagliato", () => {
    expect(leafProblem(leaf("", "eq", 1), field(""))).toMatch(/campo/);
    expect(leafProblem(leaf("data.amount", "startsWith", "1"), field("data.amount"))).toMatch(/Operatore/);
    expect(leafProblem(leaf("data.amount", "gte", ""), field("data.amount"))).toMatch(/mancante/);
    expect(leafProblem(leaf("data.amount", "gte", "12a"), field("data.amount"))).toMatch(/numero/);
    expect(leafProblem(leaf("member.tier", "eq", "IRON"), field("member.tier"))).toMatch(/IRON/);
    expect(leafProblem(leaf("member.tier", "in", []), field("member.tier"))).toMatch(/almeno un valore/);
    expect(leafProblem(leaf("data.amount", "in", [1, "x"]), field("data.amount"))).toMatch(/numero/);
    expect(leafProblem(leaf("context.hour", "between", [8, null]), field("context.hour"))).toMatch(/due valori/);
    expect(leafProblem(leaf("context.hour", "between", [12, 8]), field("context.hour"))).toMatch(/supera/);
    expect(leafProblem(leaf("context.date", "gte", "ieri"), field("context.date"))).toMatch(/Data/);
    expect(leafProblem(leaf("context.date", "between", ["2026-03-01", "2026-01-01"]), field("context.date"))).toMatch(/supera/);
    expect(leafProblem(leaf("member.attributes.hasGasContract", "eq", "sì"), field("member.attributes.hasGasContract"))).toMatch(/sì o no/);
    expect(leafProblem(leaf("member.labels", "contains", ""), field("member.labels"))).toMatch(/mancante/);
    expect(leafProblem(leaf("data.promo", "between", ["a", "b"]), field("data.promo"))).toMatch(/numeri/);
  });

  it("validateTree mappa id → problema su tutto l'albero", () => {
    const r = fromJson({
      op: "all",
      rules: [
        { field: "data.amount", cmp: "gte", value: 5 },
        { op: "any", rules: [{ field: "member.tier", cmp: "eq", value: "IRON" }] },
      ],
    });
    const problems = validateTree(r.tree!, CATALOG);
    expect(Object.values(problems)).toEqual(["Valore non ammesso: IRON"]);
  });
});

describe("avvisi sui campi", () => {
  const tree = fromJson({
    op: "all",
    rules: [
      { field: "data.amount", cmp: "gte", value: 5 },
      { field: "data.channel", cmp: "eq", value: "APP" },
      { field: "data.nowhere", cmp: "exists" },
      { field: "member.tier", cmp: "eq", value: "GOLD" },
      { field: "member.nickname", cmp: "exists" },
      { field: "foo.bar", cmp: "exists" },
      { field: "member.attributes.ghost", cmp: "exists" },
    ],
  }).tree!;
  const ids = tree.rules.map((r) => r.id);

  it("campo non comune a tutti i trigger: avviso col trigger mancante, campo mantenuto", () => {
    const w = fieldWarnings(tree, { triggers: ["a", "b"], fieldsByTrigger: { a: PURCHASE, b: RETURNED }, catalog: CATALOG });
    expect(w[ids[0]]).toBeUndefined();
    expect(w[ids[1]]).toMatch(/manca in b/);
    expect(w[ids[2]]).toMatch(/non dichiarato/);
    expect(w[ids[3]]).toBeUndefined();
    expect(w[ids[4]]).toMatch(/fuori catalogo/);
    expect(w[ids[5]]).toMatch(/Spazio sconosciuto/);
    expect(w[ids[6]]).toMatch(/fuori catalogo/);
    expect(tree.rules).toHaveLength(7);
  });

  it("nessun avviso sui data.* finché i campi non sono arrivati", () => {
    const w = fieldWarnings(tree, { triggers: ["a", "b"], fieldsByTrigger: { a: PURCHASE }, catalog: CATALOG });
    expect(w[ids[1]]).toBeUndefined();
    expect(w[ids[2]]).toBeUndefined();
  });

  it("attributi non segnalati se il catalogo dei membri non è disponibile", () => {
    const w = fieldWarnings(tree, { triggers: [], fieldsByTrigger: {}, catalog: CATALOG, unavailable: ["member"] });
    expect(w[ids[6]]).toBeUndefined();
  });

  it("MAX_DEPTH è 3", () => {
    expect(MAX_DEPTH).toBe(3);
  });
});
