import type { NextRequest } from "next/server";
import { handleGet, handlePost } from "@/lib/vetrina/programma";

// V10 (F2-DIST-09, ADR-051, Q-617, Q-722, Q-723): «Carica il programma di esempio». Solo enterprise, solo ambiente di test
// dichiarato (404 altrimenti), solo ADMIN, CSRF sul POST, token dell'operatore rinnovato a ogni chiamata all'hub, mai in
// cache. GET = anteprima o `?job=<id>`; POST `{scope: "program" | "stories"}` = avvia (202 `{jobId}`, 409 se già in corso).
// La logica sta in lib/vetrina/programma.ts (solo server): questo file è sottile apposta.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return handleGet(req);
}

export function POST(req: NextRequest) {
  return handlePost(req);
}
