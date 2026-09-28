import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleCallback } from "@/lib/auth/handlers";

// Ritorno dall'IdP (redirect URI del client `web`, deploy/idp/realm.json). Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return enterpriseOnly((bff) => handleCallback(req, bff));
}
