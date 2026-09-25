import { describe, it, expect } from "vitest";
import { execSync } from "child_process";

describe("architecture/imports", () => {
  it("non ci sono importazioni incrociate tra backoffice e portal", () => {
    // Troviamo i file del portal che importano dal backoffice
    let portalImportsBo = "";
    try {
      portalImportsBo = execSync(
        "grep -rnw 'app/portal' 'components/portal' -e '@/app/backoffice' -e '@/components/bo' || true",
        { encoding: "utf8", cwd: __dirname + "/.." }
      );
    } catch (e) {
      // ignore
    }

    // Troviamo i file del backoffice che importano dal portal
    let boImportsPortal = "";
    try {
      boImportsPortal = execSync(
        "grep -rnw 'app/backoffice' 'components/bo' -e '@/app/portal' -e '@/components/portal' || true",
        { encoding: "utf8", cwd: __dirname + "/.." }
      );
    } catch (e) {
      // ignore
    }

    // QueryState vive in components/shared (debito AUDIT-WEB-1 risolto): nessuna eccezione ammessa.
    const unexpectedPortalImportsBo = portalImportsBo.split("\n").filter((line) => line.trim() !== "");

    expect(unexpectedPortalImportsBo).toEqual([]);
    expect(boImportsPortal.trim()).toBe("");
  });
});
