import { describe, expect, it } from "vitest";
import { LhError } from "@/lib/api/client";
import {
  checkFile,
  countEntries,
  formatBytes,
  importFormData,
  inboundHref,
  isActive,
  newIdempotencyKey,
  progressPercent,
  reportHref,
  retryAllUnmatched,
  retrySummary,
  rowFilterOf,
  statusLabel,
  uploadErrorMessage,
} from "./imports";

// BO-32 Import (docs/08 §BO-32, F2-ING-02): logica pura della pagina.

describe("checkFile", () => {
  it("ammette solo le estensioni del servizio, non vuoto, sotto il limite", () => {
    expect(checkFile({ name: "ordini.CSV", size: 10 })).toBeNull();
    expect(checkFile({ name: "eventi.ndjson", size: 10 })).toBeNull();
    expect(checkFile({ name: "eventi.jsonl", size: 10 })).toBeNull();
    expect(checkFile({ name: "eventi.json", size: 10 })).toBeNull();
    expect(checkFile({ name: "ordini.xlsx", size: 10 })).toMatch(/Formato non ammesso/);
    expect(checkFile({ name: "ordini.csv.exe", size: 10 })).toMatch(/Formato non ammesso/);
    expect(checkFile({ name: "ordini.csv", size: 0 })).toBe("Il file è vuoto.");
    expect(checkFile({ name: "ordini.csv", size: 2_000_000 })).toMatch(/supera il limite di 1 MB/);
    expect(checkFile(null)).toBe("Scegli un file da caricare.");
  });
});

describe("stato e avanzamento", () => {
  it("in coda e in elaborazione sono attivi; percentuale intera e limitata", () => {
    expect(isActive("QUEUED")).toBe(true);
    expect(isActive("RUNNING")).toBe(true);
    expect(isActive("DONE")).toBe(false);
    expect(isActive("FAILED")).toBe(false);
    expect(progressPercent({ rowsDone: 1, rowsTotal: 3 })).toBe(33);
    expect(progressPercent({ rowsDone: 0, rowsTotal: 0 })).toBe(0);
    expect(progressPercent({ rowsDone: 9, rowsTotal: 3 })).toBe(100);
    expect(statusLabel("DONE")).toBe("Completato");
    expect(statusLabel("ALTRO")).toBe("ALTRO");
  });

  it("conteggi nell'ordine del rapporto", () => {
    expect(countEntries({ accepted: 1, duplicate: 2, rejected: 3, unmatched: 4, invalid: 5 }).map((c) => `${c.key}=${c.value}`)).toEqual([
      "ACCEPTED=1",
      "DUPLICATE=2",
      "REJECTED=3",
      "UNMATCHED=4",
      "INVALID=5",
    ]);
  });

  it("filtro delle righe: solo gli esiti ammessi", () => {
    expect(rowFilterOf("UNMATCHED")).toBe("UNMATCHED");
    expect(rowFilterOf("ACCEPTED")).toBe("");
    expect(rowFilterOf(null)).toBe("");
  });
});

describe("richiesta e risposte", () => {
  it("corpo multipart con file, kind=EVENTS e fonte facoltativa", () => {
    const file = new File(["id,type\n"], "ordini.csv", { type: "text/csv" });
    const form = importFormData(file, "ecommerce");
    expect((form.get("file") as File).name).toBe("ordini.csv");
    expect(form.get("kind")).toBe("EVENTS");
    expect(form.get("source")).toBe("ecommerce");
    expect(importFormData(file, "").has("source")).toBe(false);
  });

  it("chiave di idempotenza nuova per ogni invio", () => {
    expect(newIdempotencyKey(() => "abc")).toBe("bo32-abc");
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });

  it("errori del caricamento: detail del servizio, servizio addormentato, 413, 403", () => {
    expect(uploadErrorMessage(new LhError(422, "IMPORT_TOO_MANY_ROWS", "Al massimo 10000 righe", false))).toBe(
      "Al massimo 10000 righe",
    );
    expect(uploadErrorMessage(new LhError(503, "SERVICE_ASLEEP", "", true))).toMatch(/svegliando/);
    expect(uploadErrorMessage(new LhError(413, "BAD_REQUEST", "Richiesta non valida", false))).toMatch(/limite del server/);
    expect(uploadErrorMessage(new LhError(403, "FORBIDDEN_ROLE", "no", false))).toMatch(/ADMIN o CARE/);
  });

  it("collegamenti: riga del monitor e rapporto via proxy", () => {
    expect(inboundHref({ inboundEventId: "01ABC" })).toBe("/backoffice/observe/inbound?e=01ABC");
    expect(inboundHref({ inboundEventId: null })).toBeNull();
    expect(reportHref("01X")).toBe("/api/lh/ingestion/v1/imports/01X/report.csv");
  });

  it("Riprova non abbinati prosegue a blocchi e somma gli esiti", async () => {
    const calls: number[] = [];
    const total = await retryAllUnmatched(async (after) => {
      calls.push(after);
      return after === 0
        ? { retried: 200, accepted: 150, stillUnmatched: 50, rejected: 0, nextAfterRow: 380 }
        : { retried: 3, accepted: 1, stillUnmatched: 1, rejected: 1 };
    });
    expect(calls).toEqual([0, 380]);
    expect(total).toEqual({ retried: 203, accepted: 151, stillUnmatched: 51, rejected: 1, nextAfterRow: null });
    const capped = await retryAllUnmatched(async () => ({ retried: 1, accepted: 1, stillUnmatched: 0, rejected: 0, nextAfterRow: 9 }), 2);
    expect(capped.nextAfterRow).toBe(9);
  });

  it("sintesi di Riprova non abbinati", () => {
    expect(retrySummary({ retried: 2, accepted: 1, stillUnmatched: 1, rejected: 0 })).toBe(
      "Riprovate 2 righe: 1 accettate, 1 ancora non abbinate.",
    );
    expect(retrySummary({ retried: 0, accepted: 0, stillUnmatched: 0, rejected: 0 })).toMatch(/già stati risolti/);
  });

  it("dimensioni leggibili", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(1_048_576)).toBe("1 MB");
  });
});
