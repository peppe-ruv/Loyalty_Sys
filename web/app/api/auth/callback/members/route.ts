import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleCallback } from "@/lib/auth/handlers";

// Ritorno dall'IdP per il realm dei membri (redirect URI del client `portal`, deploy/idp/realm-members.json, ADR-051).
// Con un solo realm configurato vale come la callback degli operatori. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function GET(req: NextRequest) {
  return enterpriseOnly((bff) => handleCallback(req, bff), "members");
}
