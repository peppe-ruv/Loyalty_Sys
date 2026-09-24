import { describe, expect, it } from "vitest";
import { campaignsUsing, describeField, isValidCode, rowErrors, rowsToSchema, sampleFromRows, schemaToRows, type FieldRow } from "./schema";

const ROWS: FieldRow[] = [
  { name: "reading", kind: "number", required: true, options: "" },
  { name: "channel", kind: "enum", required: false, options: "APP, WEB" },
  { name: "readAt", kind: "date", required: false, options: "" },
];

describe("editor a righe dei tipi custom", () => {
  it("genera un JSON Schema con required, enum e format", () => {
    expect(rowsToSchema(ROWS)).toEqual({
      $schema: "https://json-schema.org/draft/2020-12/schema",
      type: "object",
      required: ["reading"],
      properties: {
        reading: { type: "number" },
        channel: { type: "string", enum: ["APP", "WEB"] },
        readAt: { type: "string", format: "date" },
      },
    });
  });

  it("rilegge lo schema generato (andata e ritorno)", () => {
    expect(schemaToRows(rowsToSchema(ROWS))).toEqual([
      ROWS[0],
      { ...ROWS[1], options: "APP, WEB" },
      ROWS[2],
    ]);
    expect(schemaToRows({ type: "object", properties: { items: { type: "array" } } })).toBeNull();
    expect(schemaToRows(null)).toBeNull();
  });

  it("segnala nomi non validi, doppioni ed elenchi vuoti", () => {
    const errors = rowErrors([
      { name: "Reading", kind: "number", required: false, options: "" },
      { name: "a", kind: "string", required: false, options: "" },
      { name: "a", kind: "string", required: false, options: "" },
      { name: "kind", kind: "enum", required: false, options: " , " },
    ]);
    expect(Object.keys(errors)).toEqual(["0", "2", "3"]);
  });

  it("propone dati d'esempio coerenti", () => {
    expect(sampleFromRows(ROWS, new Date("2026-09-24T10:00:00Z"))).toEqual({ reading: 10.5, channel: "APP", readAt: "2026-09-24" });
  });

  it("valida il codice e conta le campagne che lo usano", () => {
    expect(isValidCode("meter.reading.sent")).toBe(true);
    expect(isValidCode("meter")).toBe(false);
    expect(isValidCode("Meter.Read")).toBe(false);
    expect(campaignsUsing("a.b", [{ code: "C1", triggerActionTypes: ["a.b"] }, { code: "C2", triggerActionTypes: [] }])).toEqual(["C1"]);
  });

  it("descrive i campi", () => {
    expect(describeField({ path: "data.channel", type: "string", required: true, enum: ["APP", "WEB"] })).toBe("elenco · obbligatorio · APP, WEB");
    expect(describeField({ path: "data.d", type: "string", required: false, format: "date" })).toBe("data");
  });
});
