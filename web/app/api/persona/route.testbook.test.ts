// @vitest-environment node
import { expect, it } from "vitest";
import { NextRequest } from "next/server";
import { POST } from "./route";
import { parsePersona } from "@/lib/persona/cookie";
import { rows } from "@/test/testbook";

// Testbook TB-WEB §PERS (cambio persona): docs/07 §4 — il selettore scrive il cookie `lh_persona` (30 giorni).

function post(body: string) {
  return POST(new NextRequest("http://localhost/api/persona", { method: "POST", body, headers: { "content-type": "application/json" } }));
}

function cookieOf(res: Response) {
  const header = res.headers.get("set-cookie") ?? "";
  const value = /lh_persona=([^;]*)/.exec(header)?.[1];
  // Il valore scritto è codificato di nuovo da Next; alla richiesta successiva `cookies().get()` lo decodifica una volta
  // e parsePersona la seconda: qui si ripete la stessa lettura.
  return { header, persona: parsePersona(value ? decodeURIComponent(value) : undefined) };
}

it("[TB-WEB-PERS-030] cambio a persona BO nota → cookie con ruolo dall'elenco, Max-Age 30 giorni", async () => {
  const res = await post(JSON.stringify({ kind: "BO", username: "paolo.care" }));
  expect(res.status).toBe(200);
  const { header, persona } = cookieOf(res);
  expect(persona).toEqual({ kind: "BO", username: "paolo.care", role: "CARE" });
  expect(header).toMatch(/Max-Age=2592000/i);
  expect(header).toMatch(/Path=\//i);
});

it("[TB-WEB-PERS-031] il ruolo nel corpo non conta: BO sara.analyst con role ADMIN → ANALYST", async () => {
  const res = await post(JSON.stringify({ kind: "BO", username: "sara.analyst", role: "ADMIN" }));
  expect(cookieOf(res).persona).toEqual({ kind: "BO", username: "sara.analyst", role: "ANALYST" });
});

it("[TB-WEB-PERS-032] cambio a membro → cookie MEMBER", async () => {
  const res = await post(JSON.stringify({ kind: "MEMBER", memberId: "MBR-000005" }));
  expect(res.status).toBe(200);
  expect(cookieOf(res).persona).toEqual({ kind: "MEMBER", memberId: "MBR-000005" });
});

it.each(rows([
  { id: "TB-WEB-PERS-033", desc: "kind assente", body: JSON.stringify({ username: "marta.admin" }) },
  { id: "TB-WEB-PERS-034", desc: "MEMBER senza memberId", body: JSON.stringify({ kind: "MEMBER" }) },
  { id: "TB-WEB-PERS-035", desc: "corpo non JSON", body: "{rotto" },
]))("[%s] corpo non valido (%s) → 400 INVALID_PERSONA, nessun cookie", async (_id, _desc, { body }) => {
  // TESTBOOK: ambiguo, vedi TB-WEB-PERS-033…035 — il rifiuto discende da docs/07 §4 (solo le due forme di persona), il
  // codice INVALID_PERSONA no.
  const res = await post(body);
  expect(res.status).toBe(400);
  expect(await res.json()).toEqual({ error: "INVALID_PERSONA" });
  expect(res.headers.get("set-cookie")).toBeNull();
});
