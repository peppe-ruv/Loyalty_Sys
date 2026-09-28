import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleLogout } from "@/lib/auth/handlers";

// Uscita: `POST` con `X-LH-CSRF`, risponde `{redirectTo}` (logout dell'IdP con id_token_hint). Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function POST(req: NextRequest) {
  return enterpriseOnly((bff) => handleLogout(req, bff));
}
