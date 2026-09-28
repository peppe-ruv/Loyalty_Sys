import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleLogin } from "@/lib/auth/handlers";

// Login OIDC del BFF (ADR-027, docs/07 §4-bis): `GET /api/auth/login?returnTo=/percorso`. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return enterpriseOnly((bff) => handleLogin(req, bff));
}
