import type { NextRequest } from "next/server";
import { enterpriseOnly } from "@/lib/auth/bff";
import { handleBackchannelLogout } from "@/lib/auth/handlers";

// Back-channel logout del realm dei membri (`backchannel.logout.url` del client `portal`, ADR-051): chiude le sole
// sessioni del portale. Chiamata server-to-server autenticata dal logout token firmato. Solo profilo enterprise.
export const dynamic = "force-dynamic";

export function POST(req: NextRequest) {
  return enterpriseOnly((bff) => handleBackchannelLogout(req, bff), "members");
}
