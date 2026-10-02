import { describe, it } from "vitest";
import { correlationIdFrom } from "./proxyHeaders";
import { ulid } from "@/lib/ids";
import { assertProperty, fc } from "@/test/properties";

// Proprietà dell'`X-Correlation-Id` del proxy /api/lh (docs/07 §3): il valore del browser passa solo se ha la forma
// `[A-Za-z0-9-]{1,64}`; altrimenti il proxy ne genera uno nuovo (`correlationIdFrom(h) ?? ulid()`, in `route.ts`). Niente
// testo arbitrario nei log né verso i servizi. L'oracolo controlla i caratteri uno a uno, non con la stessa espressione
// regolare del codice.

const isIdChar = (code: number): boolean =>
  (code >= 0x30 && code <= 0x39) || (code >= 0x41 && code <= 0x5a) || (code >= 0x61 && code <= 0x7a) || code === 0x2d;

function shapeIsValid(value: string): boolean {
  if (value.length < 1 || value.length > 64) return false;
  for (let i = 0; i < value.length; i++) if (!isIdChar(value.charCodeAt(i))) return false;
  return true;
}

const idChar = fc.constantFrom(..."ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-".split(""));
const validId = fc.array(idChar, { minLength: 1, maxLength: 64 }).map((chars) => chars.join(""));

/** Testo di un header: caratteri Latin-1 (quelli che `Headers` accetta), con sporcizia di ogni genere. */
const latin1Text = fc
  .array(fc.integer({ min: 0x20, max: 0xff }), { maxLength: 90 })
  .map((codes) => String.fromCharCode(...codes));

function headerWith(value: string): Headers | null {
  try {
    return new Headers({ "x-correlation-id": value });
  } catch {
    return null; // valore non ammesso come header: il browser non potrebbe nemmeno mandarlo
  }
}

describe("correlationIdFrom (proprietà)", () => {
  it("accetta ogni id di forma valida, restituendolo identico (UUID e ULID compresi)", () => {
    assertProperty(
      fc.property(validId, (id) => correlationIdFrom(new Headers({ "x-correlation-id": id })) === id),
    );
    assertProperty(fc.property(fc.uuid(), (id) => correlationIdFrom(new Headers({ "x-correlation-id": id })) === id));
    assertProperty(fc.property(fc.nat(2 ** 48 - 1), (t) => {
      const id = ulid(t);
      return correlationIdFrom(new Headers({ "x-correlation-id": id })) === id;
    }));
  });

  it("per qualunque testo: o restituisce esattamente l'header di forma valida, o `null`", () => {
    assertProperty(
      fc.property(latin1Text, (text) => {
        const headers = headerWith(text);
        fc.pre(headers !== null);
        const sent = headers!.get("x-correlation-id");
        const result = correlationIdFrom(headers!);
        // `Headers` toglie gli spazi ai bordi: l'oracolo guarda il valore che il codice vede davvero.
        return sent !== null && shapeIsValid(sent) ? result === sent : result === null;
      }),
    );
  });

  it("rifiuta ogni id più lungo di 64 caratteri", () => {
    const tooLong = fc.array(idChar, { minLength: 65, maxLength: 200 }).map((chars) => chars.join(""));
    assertProperty(fc.property(tooLong, (id) => correlationIdFrom(new Headers({ "x-correlation-id": id })) === null));
  });

  it("rifiuta ogni id con un carattere fuori da `[A-Za-z0-9-]`, in qualunque posizione", () => {
    assertProperty(
      fc.property(
        validId,
        fc.constantFrom("_", ".", "/", "%", ";", ":", "=", "<", "é", "\u00ad", "\u00a0"),
        fc.nat(),
        (id, bad, at) => {
          const index = at % (id.length + 1);
          const headers = headerWith(id.slice(0, index) + bad + id.slice(index));
          fc.pre(headers !== null);
          return correlationIdFrom(headers!) === null;
        },
      ),
    );
  });

  it("senza header, o con header vuoto, restituisce `null`", () => {
    assertProperty(fc.property(fc.constantFrom("", " "), (v) => correlationIdFrom(new Headers({ "x-correlation-id": v })) === null));
    assertProperty(fc.property(fc.constant(0), () => correlationIdFrom(new Headers()) === null));
  });
});

describe("ulid del proxy (proprietà)", () => {
  it("ha 26 caratteri Crockford e supera sempre la validazione dell'id di correlazione", () => {
    assertProperty(
      fc.property(fc.nat(2 ** 48 - 1), (t) => {
        const id = ulid(t);
        return id.length === 26 && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(id) && shapeIsValid(id);
      }),
    );
  });

  it("è ordinabile per tempo: un istante successivo dà sempre un id lessicograficamente maggiore", () => {
    assertProperty(
      fc.property(fc.nat(2 ** 48 - 2), fc.integer({ min: 1, max: 2 ** 40 }), (t, delta) => {
        const later = Math.min(t + delta, 2 ** 48 - 1);
        return t === later || ulid(t) < ulid(later);
      }),
    );
  });
});
