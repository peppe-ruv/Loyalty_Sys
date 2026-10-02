import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleLogout } from "@/lib/auth/handlers";
import { parseRealm } from "@/lib/auth/realm";

// Uscita: modulo `POST` con il token CSRF, risponde 303 verso il logout dell'IdP (id_token_hint). Con due realm
// (ADR-051) `?realm=members` chiude la sola sessione del portale. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function POST(req: NextRequest) {
  return enterpriseOnly((bff) => handleLogout(req, bff), parseRealm(req.nextUrl.searchParams.get("realm")) ?? "operators");
}
