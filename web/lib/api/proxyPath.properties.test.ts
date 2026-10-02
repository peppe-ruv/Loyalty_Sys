import { describe, it, expect } from "vitest";
import { safeSegments, upstreamUrl } from "./proxyPath";
import { assertProperty, fc } from "@/test/properties";

// Proprietà del percorso inoltrato dal proxy /api/lh (docs/07 §4-bis, ADR-042, F2-SEC-03): il percorso deciso dal browser
// non deve poter diventare un altro percorso a valle. Ogni segmento ammette solo `[A-Za-z0-9._~-]` e non può essere
// vuoto, `.` o `..` (altrimenti 400 INVALID_PATH, in `route.ts`); l'URL costruito ha esattamente il percorso chiesto.

const SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-";
const safeChar = fc.constantFrom(...SAFE.split(""));

/** Segmento ammesso: caratteri non riservati, mai vuoto, mai `.` o `..` (ma sì `a.b`, `...`, `-`, `~`). */
const safeSegment = fc
  .array(safeChar, { minLength: 1, maxLength: 12 })
  .map((chars) => chars.join(""))
  .filter((s) => s !== "." && s !== "..");

const safePath = fc.array(safeSegment, { minLength: 1, maxLength: 6 });

/** Un carattere fuori da `[A-Za-z0-9._~-]`: separatori, escape, parametri di matrice, spazi, controllo, Unicode. */
const unsafeChar = fc.oneof(
  fc.constantFrom("/", "\\", "%", ";", "?", "#", " ", "\0", "\n", "\t", ":", "@", "*", "+", "ü", "٣", "‮", "﻿"),
  fc.string({ unit: "binary", minLength: 1, maxLength: 1 }).filter((c) => !SAFE.includes(c)),
);

/** Segmento con almeno un carattere non ammesso, in qualunque posizione. */
const segmentWithUnsafeChar = fc
  .tuple(fc.array(safeChar, { maxLength: 5 }), unsafeChar, fc.array(safeChar, { maxLength: 5 }))
  .map(([head, bad, tail]) => head.join("") + bad + tail.join(""));

/** Segmenti che non devono mai passare: vuoto, `.`, `..` e le loro forme codificate e con parametri di matrice. */
const forbiddenSegment = fc.constantFrom("", ".", "..", "%2e%2e", "%2E", "%2f", "a;x", "a/b", "..;", " ..");

const BASES = ["http://wallet-service:8084", "http://localhost:8080/", "https://svc.internal/api/v2/"];

/** Un percorso qualunque: segmenti ammessi mescolati a segmenti cattivi e a stringhe arbitrarie. */
const anyPath = fc.array(fc.oneof(safeSegment, forbiddenSegment, segmentWithUnsafeChar, fc.string({ unit: "binary" })), {
  maxLength: 6,
});

function expectedPathname(base: string, path: readonly string[]): string {
  const prefix = new URL(base).pathname.replace(/\/+$/, "");
  return `${prefix}/${path.join("/")}`;
}

describe("safeSegments (proprietà)", () => {
  it("accetta ogni percorso non vuoto di segmenti con soli caratteri non riservati", () => {
    assertProperty(fc.property(safePath, (path) => safeSegments(path) === true));
  });

  it("rifiuta ogni percorso con un segmento che contiene un carattere non ammesso, in qualunque posizione", () => {
    assertProperty(
      fc.property(safePath, segmentWithUnsafeChar, fc.nat(), (path, bad, at) => {
        const index = at % (path.length + 1);
        const withBad = [...path.slice(0, index), bad, ...path.slice(index)];
        return safeSegments(withBad) === false;
      }),
    );
  });

  it("rifiuta segmenti vuoti, `.` e `..` (anche codificati) e il percorso vuoto", () => {
    expect(safeSegments([])).toBe(false);
    assertProperty(
      fc.property(safePath, forbiddenSegment, fc.nat(), (path, bad, at) => {
        const index = at % (path.length + 1);
        return safeSegments([...path.slice(0, index), bad, ...path.slice(index)]) === false;
      }),
    );
  });
});

describe("upstreamUrl (proprietà)", () => {
  it("per un percorso ammesso costruisce un URL con host invariato e il percorso esatto, senza query né frammento", () => {
    assertProperty(
      fc.property(fc.constantFrom(...BASES), safePath, (base, path) => {
        const url = upstreamUrl(base, path);
        expect(url).not.toBeNull();
        expect(url!.origin).toBe(new URL(base).origin);
        expect(url!.pathname).toBe(expectedPathname(base, path));
        expect(url!.search).toBe("");
        expect(url!.hash).toBe("");
      }),
    );
  });

  it("per qualunque input è `null` oppure ha esattamente il percorso chiesto: mai un percorso diverso", () => {
    assertProperty(
      fc.property(fc.constantFrom(...BASES), anyPath, (base, path) => {
        const url = upstreamUrl(base, path);
        if (url === null) return true;
        return (
          url.origin === new URL(base).origin &&
          url.pathname === expectedPathname(base, path) &&
          url.search === "" &&
          url.hash === ""
        );
      }),
    );
  });

  it("è `null` se e solo se i segmenti non sono tutti ammessi (stessa decisione di safeSegments)", () => {
    assertProperty(
      fc.property(fc.constantFrom(...BASES), anyPath, (base, path) => {
        return (upstreamUrl(base, path) === null) === !safeSegments(path);
      }),
    );
  });
});
