import { describe, expect, it } from "vitest";
import {
  campaignImpact,
  campaignsUsing,
  describeField,
  flatRows,
  isValidCode,
  ROW_ERROR,
  rowErrors,
  rowsToSchema,
  sampleFromRows,
  schemaToRows,
  type FieldRow,
} from "./schema";

const ROWS: FieldRow[] = [
  { name: "reading", kind: "number", required: true, options: "" },
  { name: "channel", kind: "enum", required: false, options: "APP, WEB" },
  { name: "readAt", kind: "date", required: false, options: "" },
];

describe("editor a righe dei tipi custom", () => {
  it("genera un JSON Schema con required, enum, format e x-lh-pii: false su ogni campo (Q-435)", () => {
    expect(rowsToSchema(ROWS)).toEqual({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      required: ["reading"],
      properties: {
        reading: { type: "number", "x-lh-pii": false },
        channel: { type: "string", enum: ["APP", "WEB"], "x-lh-pii": false },
        readAt: { type: "string", format: "date", "x-lh-pii": false },
      },
    });
  });

  it("salva etichetta e spiegazione come title e description (Q-434)", () => {
    const schema = rowsToSchema([
      { name: "storeId", kind: "string", required: true, options: "", label: " Codice negozio ", description: "Punto vendita" },
    ]);
    expect((schema.properties as Record<string, unknown>).storeId).toEqual({
      type: "string",
      title: "Codice negozio",
      description: "Punto vendita",
      "x-lh-pii": false,
    });
  });

  it("rilegge lo schema generato (andata e ritorno), etichette comprese", () => {
    const withLabels = ROWS.map((r) => ({ ...r, label: `L-${r.name}`, description: "" }));
    expect(schemaToRows(rowsToSchema(withLabels))).toEqual(withLabels);
    expect(schemaToRows({ type: "object", properties: { items: { type: "array" } } })).toBeNull();
    expect(schemaToRows(null)).toBeNull();
  });

  it("flatRows copia i campi piatti e salta elenchi e oggetti", () => {
    const { rows, skipped } = flatRows({
      type: "object",
      required: ["orderId"],
      properties: { orderId: { type: "string" }, items: { type: "array" }, amount: { type: "number" } },
    });
    expect(rows?.map((r) => r.name)).toEqual(["orderId", "amount"]);
    expect(rows?.[0].required).toBe(true);
    expect(skipped).toEqual(["items"]);
  });

  it("segnala nomi non validi, doppioni, elenchi vuoti e dati personali", () => {
    const errors = rowErrors([
      { name: "Reading", kind: "number", required: false, options: "" },
      { name: "a", kind: "string", required: false, options: "" },
      { name: "a", kind: "string", required: false, options: "" },
      { name: "kind", kind: "enum", required: false, options: " , " },
      { name: "email", kind: "string", required: false, options: "" },
      { name: "contatto", kind: "string", required: false, options: "", label: "Telefono del membro" },
    ]);
    expect(errors).toEqual({
      0: ROW_ERROR.name,
      2: ROW_ERROR.duplicate,
      3: ROW_ERROR.options,
      4: ROW_ERROR.personal,
      5: ROW_ERROR.personal,
    });
    expect(ROW_ERROR.name).toMatch(/codiceNegozio/);
    expect(ROW_ERROR.personal).toBe("I dati personali non viaggiano nelle azioni: usa l'identificativo del membro.");
  });

  it("propone dati d'esempio coerenti, con i valori tipici dei campi standard", () => {
    expect(sampleFromRows(ROWS, new Date("2026-09-24T10:00:00Z"))).toEqual({ reading: 10.5, channel: "APP", readAt: "2026-09-24" });
    expect(
      sampleFromRows([
        { name: "amount", kind: "number", required: true, options: "" },
        { name: "storeId", kind: "string", required: true, options: "" },
      ]),
    ).toEqual({ amount: 25.5, storeId: "NEG-001" });
  });

  it("valida il codice e conta le campagne che lo usano", () => {
    expect(isValidCode("meter.reading.sent")).toBe(true);
    expect(isValidCode("meter")).toBe(false);
    expect(isValidCode("Meter.Read")).toBe(false);
    expect(campaignsUsing("a.b", [{ code: "C1", triggerActionTypes: ["a.b"] }, { code: "C2", triggerActionTypes: [] }])).toEqual(["C1"]);
  });

  it("impatto: campagne che usano l'azione e quante sono attive (Q-436)", () => {
    const campaigns = [
      { id: "1", code: "C1", name: "Uno", status: "LIVE", triggerActionTypes: ["store.visited"] },
      { id: "2", code: "C2", name: "Due", status: "DRAFT", triggerActionTypes: ["store.visited", "purchase.completed"] },
      { id: "3", code: "C3", name: "Tre", status: "LIVE", triggerActionTypes: ["purchase.completed"] },
    ];
    const impact = campaignImpact("store.visited", campaigns);
    expect(impact.using.map((c) => c.id)).toEqual(["1", "2"]);
    expect(impact.active).toBe(1);
    expect(campaignImpact("x.y", campaigns)).toEqual({ using: [], active: 0 });
  });

  it("descrive i campi", () => {
    expect(describeField({ path: "data.channel", type: "string", required: true, enum: ["APP", "WEB"] })).toBe("elenco · obbligatorio · APP, WEB");
    expect(describeField({ path: "data.d", type: "string", required: false, format: "date" })).toBe("data");
  });
});
