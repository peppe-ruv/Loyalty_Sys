import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleBackchannelLogout } from "@/lib/auth/handlers";

// Back-channel logout dell'IdP (`backchannel.logout.url` del client `web`, deploy/idp/realm.json). Chiamata
// server-to-server autenticata dal logout token firmato. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function POST(req: NextRequest) {
  return enterpriseOnly((bff) => handleBackchannelLogout(req, bff));
}
