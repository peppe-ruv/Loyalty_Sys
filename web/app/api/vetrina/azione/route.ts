import type { NextRequest } from "next/server";
import { handleGet, handlePost } from "@/lib/vetrina/azione";

// V11 (F2-ING-02, BO-32, ADR-051, Q-675): «Invia un'azione» dalla fonte di test. Solo enterprise, solo ambiente di test
// dichiarato (404 altrimenti), solo ADMIN e CARE (i ruoli dell'import), CSRF sul POST, token dell'operatore rinnovato a
// ogni chiamata all'hub, mai in cache. GET = contesto del modulo o `?import=<id>` (esito); POST `{username, type, amount?}`
// = crea l'import di una riga (202 `{importId}`). La logica sta in lib/vetrina/azione.ts (solo server).
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return handleGet(req);
}

export function POST(req: NextRequest) {
  return handlePost(req);
}
